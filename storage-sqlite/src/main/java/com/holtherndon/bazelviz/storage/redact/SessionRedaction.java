package com.holtherndon.bazelviz.storage.redact;

import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.RedactionReport;
import com.holtherndon.bazelviz.core.redact.Redactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Rewrites a session database with its sensitive columns redacted.
 *
 * <h2>A copy, never in place</h2>
 *
 * <p>The session on disk is the user's own record of their own build and stays
 * exactly as captured (ADR-004). Redaction produces a second database for
 * export, so a user who shares a session still has the unredacted one, and an
 * export that goes wrong cannot damage what it was exporting.
 *
 * <h2>The column list is the sensitive-field inventory, executable</h2>
 *
 * <p>docs/privacy.md and docs/database-schema.md maintain an inventory of every
 * column that can carry user-identifying or secret data. A document is
 * checkable by reading and a list is checkable by a test, so this is the list
 * and {@code SessionRedactionTest} asserts that every {@code TEXT} column in
 * the schema appears either here or in {@link #deliberatelyNotSensitive()}. A
 * new column added in a future migration fails that test until somebody has
 * decided which it is.
 *
 * <h2>{@code strings.value} is the one that matters most</h2>
 *
 * <p>It is the interned string dictionary, and it holds whatever the stream
 * put in it — progress output, command text, paths. Redacting the columns that
 * obviously look sensitive while leaving the dictionary alone would leak the
 * same data through the side door.
 */
public final class SessionRedaction {

    private SessionRedaction() {}

    /** Rows rewritten per batch. Large enough to be fast, small enough to bound memory. */
    private static final int BATCH = 2_000;

    /** How a column's contents are treated. */
    public enum Treatment {
        /** An absolute or relative filesystem path. */
        PATH,
        /** Free text that may embed paths and secrets. */
        TEXT,
        /** A target label, pseudonymised only when the policy says so. */
        LABEL,
        /** An environment value, read together with its name. */
        ENVIRONMENT_VALUE,
        /** A Bazel option value, read with the option name as a command flag. */
        OPTION_VALUE
    }

    /**
     * One column to rewrite.
     *
     * @param keyColumns the columns that identify a row; several tables here
     *     have composite primary keys and none has a rowid alias that can be
     *     relied on after a vacuum
     * @param nameColumn for {@link Treatment#ENVIRONMENT_VALUE}, the column
     *     holding the variable's name, which decides whether the value is a
     *     secret
     */
    public record Column(
            String table,
            List<String> keyColumns,
            String column,
            Treatment treatment,
            String nameColumn) {

        public Column {
            Objects.requireNonNull(table, "table");
            keyColumns = List.copyOf(keyColumns);
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(treatment, "treatment");
        }

        static Column of(String table, String key, String column, Treatment treatment) {
            return new Column(table, List.of(key), column, treatment, null);
        }

        static Column of(String table, List<String> keys, String column, Treatment treatment) {
            return new Column(table, keys, column, treatment, null);
        }

        /** The field name a report uses. */
        public String field() {
            return table + "." + column;
        }
    }

    /** Every column an export rewrites, in the order it rewrites them. */
    public static List<Column> sensitiveColumns() {
        return List.of(
                // The interned dictionary: progress output, command text, paths.
                Column.of("strings", "id", "value", Treatment.TEXT),
                Column.of("labels", "id", "value", Treatment.LABEL),
                Column.of("capture_sources", "id", "path", Treatment.PATH),
                Column.of("capture_sources", "id", "note", Treatment.TEXT),
                Column.of("import_diagnostics", "id", "message", Treatment.TEXT),
                Column.of("build_invocation", "singleton", "working_directory", Treatment.PATH),
                Column.of("build_invocation", "singleton", "workspace_directory", Treatment.PATH),
                Column.of("build_invocation", "singleton", "options_description", Treatment.TEXT),
                Column.of("configuration_make_variables",
                        List.of("configuration_id", "name"), "value", Treatment.TEXT),
                Column.of("configured_targets", "id", "failure_message", Treatment.TEXT),
                Column.of("target_tags",
                        List.of("target_id", "tag", "from_event"), "tag", Treatment.TEXT),
                Column.of("artifacts", "id", "path", Treatment.PATH),
                Column.of("artifacts", "id", "name", Treatment.PATH),
                Column.of("artifacts", "id", "path_prefix", Treatment.PATH),
                Column.of("artifacts", "id", "uri", Treatment.PATH),
                Column.of("actions", "id", "primary_output", Treatment.PATH),
                Column.of("actions", "id", "failure_message", Treatment.TEXT),
                Column.of("actions", "id", "command_line", Treatment.TEXT),
                Column.of("actions", "id", "stdout_uri", Treatment.PATH),
                Column.of("actions", "id", "stderr_uri", Treatment.PATH),
                Column.of("test_logs", "id", "uri", Treatment.PATH),
                Column.of("aborted_events", "id", "description", Treatment.TEXT),
                Column.of("enrichment_tasks", "id", "source_path", Treatment.PATH),
                Column.of("enrichment_tasks", "id", "error_excerpt", Treatment.TEXT),
                Column.of("action_attempts", "id", "correlation_note", Treatment.TEXT),
                new Column("attempt_env_vars", List.of("attempt_id", "name"), "value",
                        Treatment.ENVIRONMENT_VALUE, "name"),
                Column.of("profile_metadata", "id", "output_base", Treatment.PATH),
                Column.of("profile_spans", "id", "name", Treatment.TEXT),
                Column.of("profile_spans", "id", "primary_output", Treatment.PATH),
                Column.of("bazel_critical_path", "id", "description", Treatment.TEXT),
                // pprof's shared strings hold Starlark function names and source paths.
                Column.of("starlark_profile_strings", "string_index", "value", Treatment.TEXT),
                Column.of("starlark_profile_metadata", "id", "validation_detail", Treatment.TEXT),
                Column.of("graph_sources", "id", "command", Treatment.TEXT),
                Column.of("graph_sources", "id", "error_excerpt", Treatment.TEXT),
                Column.of("graph_sources", "id", "mismatch_detail", Treatment.TEXT),
                Column.of("graph_sources", "id", "target_scope_detail", Treatment.TEXT),
                Column.of("graph_sources", "id", "raw_output_path", Treatment.PATH),
                Column.of("declared_actions", "id", "execution_platform", Treatment.LABEL),
                new Column("queried_configuration_options",
                        List.of("configuration_id", "option_set_name", "ordinal"),
                        "option_value", Treatment.OPTION_VALUE, "option_name"));
    }

    /**
     * The {@code TEXT} columns an export deliberately leaves alone, and why.
     *
     * <p>Enumerated rather than assumed. A column absent from both lists is a
     * column nobody decided about, and the test refuses that — which is the
     * only way an inventory stays true across a schema that keeps growing.
     */
    public static Set<String> deliberatelyNotSensitive() {
        return Set.of(
                // Enumerations and internal identifiers.
                "schema_metadata.key", "schema_metadata.value",
                "session_info.session_uuid", "session_info.state", "session_info.app_version",
                "capture_sources.kind", "capture_sources.sha256", "capture_sources.completeness",
                "event_streams.stream_key", "event_streams.invocation_id",
                "event_streams.build_id", "event_streams.state",
                "import_diagnostics.severity", "import_diagnostics.code",
                "bep_events.decode_status",
                // A rendered event id: target labels and configuration ids, which
                // travel under labels.value's decision.
                "bep_event_ids.display",
                "mnemonics.value",
                "build_invocation.invocation_id", "build_invocation.build_tool_version",
                // The Bazel subcommand -- "build", "test" -- and not its arguments.
                "build_invocation.command", "build_invocation.exit_code_name",
                "configurations.bep_id", "configurations.mnemonic",
                "configurations.platform_name", "configurations.cpu",
                "configuration_make_variables.name",
                "targets.aspect", "targets.target_kind", "targets.test_size", "targets.outcome",
                "configured_targets.outcome", "configured_targets.failure_category",
                "target_tags.from_event",
                "artifacts.digest",
                "depsets.bep_id", "target_output_groups.name",
                "actions.outcome", "actions.failure_category", "actions.duration_unknown_reason",
                "tests.overall_status", "test_attempts.status", "test_attempts.strategy",
                "test_logs.name", "test_logs.summary_status",
                "runner_counts.name", "runner_counts.exec_kind", "cache_miss_details.reason",
                "garbage_metrics.type",
                "aborted_events.id_kind", "aborted_events.reason",
                "enrichment_tasks.kind", "enrichment_tasks.state",
                "enrichment_tasks.exit_status", "enrichment_tasks.unavailable_metrics",
                "action_attempts.correlation", "action_attempts.runner",
                "action_attempts.status", "action_attempts.start_unknown_reason",
                "action_attempts.digest_hash", "action_attempts.digest_function",
                "attempt_outputs.kind",
                // The variable's name is diagnostic; its value is the secret.
                "attempt_env_vars.name",
                "profile_metadata.build_id", "profile_metadata.bazel_version",
                "profile_metadata.anchor_source_key", "profile_metadata.anchor_meaning",
                "profile_threads.name", "build_phases.name",
                "profile_spans.category", "profile_counters.series",
                "starlark_profile_metadata.format",
                "starlark_profile_metadata.validation_state",
                "starlark_profile_sample_labels.value_kind",
                "graph_sources.kind", "graph_sources.state",
                "graph_sources.configuration_match",
                "graph_sources.target_scope",
                // A content hash of the action's own inputs and command; it
                // identifies an action and reveals none of it.
                "declared_actions.configuration_checksum", "declared_actions.action_key",
                "configured_target_nodes.configuration_checksum",
                "configured_target_nodes.rule_class", "configured_target_edges.attribute",
                "queried_configurations.checksum", "queried_configurations.mnemonic",
                "queried_configurations.platform_name",
                "queried_configuration_fragments.fragment_name",
                "queried_configuration_fragments.option_set_name",
                "queried_configuration_options.option_set_name",
                "queried_configuration_options.option_name",
                "action_edges.derivation",
                "graph_indexes.kind", "graph_indexes.direction", "graph_indexes.file_name",
                "graph_indexes.checksum");
    }

    /** What a redacted copy produced. */
    public record Result(Path database, RedactionReport report, long bytes) {

        public String describe() {
            return "Redacted copy written to " + database.getFileName() + " (" + bytes
                    + " bytes).\n" + report;
        }
    }

    /**
     * Copies {@code source} to {@code target} and redacts the copy.
     *
     * <p>The copy is made first and rewritten afterwards, rather than filtered
     * on the way: SQLite is a page-structured file and there is no streaming
     * transform that produces a valid one. That means the target briefly holds
     * the unredacted data, which is why it must be written somewhere the export
     * controls — a temporary file the caller deletes — and never handed to
     * anyone before this method returns.
     */
    public static Result copyRedacted(Path source, Path target, RedactionPolicy policy)
            throws IOException, SQLException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(policy, "policy");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        Redactor redactor = new Redactor(policy);
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + target.toAbsolutePath())) {
            connection.setAutoCommit(false);
            RedactionReport report = redact(connection, redactor);
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                // Rewrites the file, so the pages that held the original text
                // are not left in the free list of a database about to be
                // mailed to somebody.
                statement.execute("VACUUM");
            }
            return new Result(target, report, Files.size(target));
        }
    }

    /**
     * Rewrites every sensitive column on an open connection.
     *
     * <p>Private: the only safe way to reach it is {@link #copyRedacted}, which
     * has already made the copy. A caller that could redact an arbitrary open
     * connection could redact the session itself, and ADR-004 says the session
     * stays exactly as captured.
     */
    private static RedactionReport redact(Connection connection, Redactor redactor)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(redactor, "redactor");
        for (Column column : sensitiveColumns()) {
            if (!tableExists(connection, column.table())) {
                // A session written by an older schema simply has fewer tables.
                continue;
            }
            redactColumn(connection, redactor, column);
        }
        return redactor.report();
    }

    private static void redactColumn(Connection connection, Redactor redactor, Column column)
            throws SQLException {
        String keys = String.join(", ", column.keyColumns());
        String select = "SELECT " + keys + ", " + column.column()
                + (column.nameColumn() == null ? "" : ", " + column.nameColumn())
                + " FROM " + column.table() + " WHERE " + column.column() + " IS NOT NULL";
        String where = String.join(" = ? AND ", column.keyColumns()) + " = ?";
        String update = "UPDATE " + column.table() + " SET " + column.column()
                + " = ? WHERE " + where;

        List<Object[]> pending = new ArrayList<>(BATCH);
        try (PreparedStatement read = connection.prepareStatement(select);
                ResultSet rows = read.executeQuery();
                PreparedStatement write = connection.prepareStatement(update)) {
            int keyCount = column.keyColumns().size();
            while (rows.next()) {
                Object[] key = new Object[keyCount];
                for (int i = 0; i < keyCount; i++) {
                    key[i] = rows.getObject(i + 1);
                }
                String value = rows.getString(keyCount + 1);
                String name = column.nameColumn() == null ? null : rows.getString(keyCount + 2);
                String redacted = apply(redactor, column, name, value);
                if (!Objects.equals(redacted, value)) {
                    Object[] row = new Object[keyCount + 1];
                    row[0] = redacted;
                    System.arraycopy(key, 0, row, 1, keyCount);
                    pending.add(row);
                }
                if (pending.size() >= BATCH) {
                    flush(write, pending);
                }
            }
            flush(write, pending);
        }
    }

    private static void flush(PreparedStatement write, List<Object[]> pending)
            throws SQLException {
        for (Object[] row : pending) {
            for (int i = 0; i < row.length; i++) {
                write.setObject(i + 1, row[i]);
            }
            write.addBatch();
        }
        if (!pending.isEmpty()) {
            write.executeBatch();
            pending.clear();
        }
    }

    private static String apply(
            Redactor redactor, Column column, String name, String value) {
        return switch (column.treatment()) {
            case PATH -> redactor.path(value, column.field());
            case TEXT -> redactor.text(value, column.field());
            case LABEL -> redactor.label(value, column.field());
            case ENVIRONMENT_VALUE -> redactor.environmentValue(name, value, column.field());
            case OPTION_VALUE -> redactor.environmentValue(
                    name == null || name.startsWith("--") ? name : "--" + name,
                    value,
                    column.field());
        };
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
