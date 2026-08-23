package com.holtherndon.bazelviz.storage.export;

import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.core.text.Csv;
import com.holtherndon.bazelviz.core.text.Json;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Streams a session's tables out as CSV or JSON.
 *
 * <h2>Row by row, never all of them</h2>
 *
 * <p>Plan 24's Phase 9 exit criterion: "export does not require loading the
 * entire session into memory". A Tier 3 session has five million actions, so
 * every row is read, written and forgotten. Nothing here builds a list, a value
 * tree or a string of the whole export — which is also why the JSON output is
 * assembled by hand rather than through a document model.
 *
 * <h2>Exported data is redacted data</h2>
 *
 * <p>docs/privacy.md: export runs redaction mandatorily. The redactor is an
 * {@link Optional} only so a caller can dump their own session for their own
 * debugging; every path that produces a file for somebody else passes one, and
 * the {@link Result} says which happened so a file cannot be mistaken for the
 * other kind.
 *
 * <h2>Written through a temporary file</h2>
 *
 * <p>Same reason as everywhere else in Phase 9: an export interrupted halfway
 * leaves a file that looks finished, and a user who mails it finds out at the
 * other end.
 */
public final class TableExport {

    private TableExport() {}

    /** Rows fetched from SQLite at a time. */
    private static final int FETCH = 4_096;

    /** How the rows are written. */
    public enum Format {
        CSV(".csv"),
        /** A JSON array, streamed: one object per row, written as they arrive. */
        JSON(".json");

        private final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }

        public String displayName() {
            return name();
        }
    }

    /**
     * The tables an export offers.
     *
     * <p>Each is a query rather than a table name, because the useful export of
     * "actions" joins the mnemonic and label dictionaries: a file of integer
     * foreign keys is not something anybody can open in a spreadsheet.
     */
    public enum Table {
        ACTIONS("Actions",
                "SELECT act.id, l.value AS label, m.value AS mnemonic, act.outcome,"
                        + " act.primary_output, act.start_micros, act.end_micros,"
                        + " act.duration_unknown_reason, act.spawn_exit_code,"
                        + " act.failure_category, act.failure_message"
                        + " FROM actions act"
                        + " LEFT JOIN labels l ON l.id = act.label_id"
                        + " LEFT JOIN mnemonics m ON m.id = act.mnemonic_id"
                        + " ORDER BY act.id"),
        TARGETS("Targets",
                "SELECT ct.id, l.value AS label, t.target_kind, ct.outcome,"
                        + " ct.failure_category, ct.failure_message"
                        + " FROM configured_targets ct"
                        + " JOIN targets t ON t.id = ct.target_id"
                        + " LEFT JOIN labels l ON l.id = t.label_id"
                        + " ORDER BY ct.id"),
        TESTS("Tests",
                "SELECT te.id, l.value AS label, te.overall_status, te.total_run_count,"
                        // Renamed by schema v3: Bazel's own first-start and
                        // last-stop, kept under names that say whose they are.
                        + " te.attempt_count, te.bazel_first_start_micros,"
                        + " te.bazel_last_stop_micros,"
                        + " te.bazel_reported_duration_micros"
                        + " FROM tests te"
                        + " JOIN configured_targets ct ON ct.id = te.configured_target_id"
                        + " JOIN targets t ON t.id = ct.target_id"
                        + " LEFT JOIN labels l ON l.id = t.label_id"
                        + " ORDER BY te.id"),
        ATTEMPTS("Attempts",
                "SELECT a.id, a.action_id, l.value AS label, m.value AS mnemonic, a.runner,"
                        + " a.cache_hit, a.exit_code, a.status, a.start_micros, a.total_micros,"
                        + " a.queue_micros, a.setup_micros, a.execution_wall_micros,"
                        + " a.network_micros, a.upload_micros, a.fetch_micros,"
                        + " a.input_bytes, a.input_files"
                        + " FROM action_attempts a"
                        + " LEFT JOIN labels l ON l.id = a.label_id"
                        + " LEFT JOIN mnemonics m ON m.id = a.mnemonic_id"
                        + " ORDER BY a.id"),
        ARTIFACTS("Artifacts",
                "SELECT id, path, name, digest, size_bytes, is_directory, is_source, uri"
                        + " FROM artifacts ORDER BY id");

        private final String displayName;
        private final String sql;

        Table(String displayName, String sql) {
            this.displayName = displayName;
            this.sql = sql;
        }

        public String displayName() {
            return displayName;
        }

        /** A suggested file name, without an extension. */
        public String fileStem() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * What an export produced.
     *
     * @param redacted false only for a caller that asked for its own session
     *     unredacted; a file that leaves this machine always has this true
     */
    public record Result(Path file, Table table, Format format, long rows, boolean redacted) {

        public String describe() {
            return rows + " rows from " + table.displayName() + " written to "
                    + file.getFileName() + " as " + format.displayName()
                    + (redacted
                            ? ", redacted."
                            : ", NOT redacted — this file carries the session's own paths,"
                                    + " commands and environment values.");
        }
    }

    /**
     * Writes one table.
     *
     * @param redactor applied to every text column; empty means the file keeps
     *     the session's own values and says so
     */
    public static Result write(
            Connection connection, Table table, Format format, Path target,
            Optional<Redactor> redactor) throws IOException, SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(redactor, "redactor");
        Path file = withExtension(target, format);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.deleteIfExists(partial);

        long rows = 0;
        boolean ok = false;
        try {
            try (Writer out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8);
                    PreparedStatement statement = connection.prepareStatement(table.sql)) {
                statement.setFetchSize(FETCH);
                try (ResultSet result = statement.executeQuery()) {
                    List<String> columns = columnsOf(result);
                    rows = format == Format.CSV
                            ? writeCsv(out, result, columns, table, redactor)
                            : writeJson(out, result, columns, table, redactor);
                }
            }
            Files.move(partial, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(partial);
            }
        }
        return new Result(file, table, format, rows, redactor.isPresent());
    }

    private static List<String> columnsOf(ResultSet result) throws SQLException {
        int count = result.getMetaData().getColumnCount();
        List<String> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            columns.add(result.getMetaData().getColumnLabel(i));
        }
        return columns;
    }

    private static long writeCsv(
            Writer out, ResultSet result, List<String> columns, Table table,
            Optional<Redactor> redactor) throws IOException, SQLException {
        out.write(Csv.row(columns));
        out.write('\n');
        long rows = 0;
        List<String> values = new ArrayList<>(columns.size());
        while (result.next()) {
            values.clear();
            for (int i = 0; i < columns.size(); i++) {
                values.add(value(result, i + 1, columns.get(i), table, redactor));
            }
            out.write(Csv.row(values));
            out.write('\n');
            rows++;
        }
        return rows;
    }

    private static long writeJson(
            Writer out, ResultSet result, List<String> columns, Table table,
            Optional<Redactor> redactor) throws IOException, SQLException {
        out.write("[\n");
        long rows = 0;
        while (result.next()) {
            if (rows > 0) {
                out.write(",\n");
            }
            out.write("  {");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    out.write(", ");
                }
                out.write(Json.string(columns.get(i)));
                out.write(": ");
                Object raw = result.getObject(i + 1);
                if (raw == null) {
                    // A null is a value the session does not have; writing 0 or
                    // "" would make it one it does (rule 11).
                    out.write("null");
                } else if (raw instanceof Number number) {
                    out.write(number.toString());
                } else {
                    out.write(Json.string(
                            value(result, i + 1, columns.get(i), table, redactor)));
                }
            }
            out.write('}');
            rows++;
        }
        out.write("\n]\n");
        return rows;
    }

    /**
     * One cell, redacted according to what the column holds.
     *
     * <p>The treatment is chosen by column name rather than by a per-table
     * table, because the names are the same everywhere they appear — a
     * {@code label} is a label in five queries and a {@code uri} is a path in
     * three — and a per-query mapping would be five places to forget one.
     */
    private static String value(
            ResultSet result, int index, String column, Table table, Optional<Redactor> redactor)
            throws SQLException {
        String raw = result.getString(index);
        if (raw == null) {
            return "";
        }
        if (redactor.isEmpty()) {
            return raw;
        }
        Redactor redacting = redactor.orElseThrow();
        String field = table.fileStem() + "." + column;
        return switch (column) {
            case "label" -> redacting.label(raw, field);
            case "path", "primary_output", "uri" -> redacting.path(raw, field);
            case "failure_message", "name" -> redacting.text(raw, field);
            default -> redacting.text(raw, field);
        };
    }

    private static Path withExtension(Path target, Format format) {
        String name = target.getFileName().toString();
        return name.endsWith(format.extension())
                ? target
                : target.resolveSibling(name + format.extension());
    }
}
