package com.holtherndon.bazelviz.storage.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.redact.SessionRedaction;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the exports cost, measured rather than estimated.
 *
 * <p>Half a million actions: not the plan's five-million ceiling, but far
 * enough past the point where a per-row query or a retained result set stops
 * being invisible. The figures it prints go into docs/performance.md.
 */
final class ExportScaleTest {

    private static final int ACTIONS = 500_000;

    @TempDir
    Path tempDir;

    @Test
    void halfAMillionActionsExportInOnePass() throws Exception {
        Path file = tempDir.resolve("scale.sqlite");
        try (SessionDatabase database = SessionDatabase.open(file)) {
            MigrationRunner.standard().migrate(database);
            Connection writer = database.writerConnection();
            try (Statement statement = writer.createStatement()) {
                statement.execute("INSERT INTO mnemonics (id, value) VALUES (1, 'Javac')");
                statement.execute("INSERT INTO labels (id, value) VALUES (1, '//src:lib')");
                statement.execute("INSERT INTO event_streams (id, stream_key, state)"
                        + " VALUES (1, 's', 'CLOSED')");
                statement.execute("INSERT INTO build_invocation (singleton, stream_id,"
                        + " workspace_directory, saw_last_message)"
                        + " VALUES (1, 1, '/Users/someone/code/p', 1)");
            }
            writer.setAutoCommit(false);
            try (PreparedStatement insert = writer.prepareStatement(
                    "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome,"
                            + " failure_message) VALUES (?, ?, 1, 1, 'SUCCESS', ?)")) {
                for (int i = 1; i <= ACTIONS; i++) {
                    insert.setInt(1, i);
                    insert.setString(2, "/Users/someone/code/p/bazel-out/darwin/bin/o" + i + ".o");
                    insert.setString(3, i % 100 == 0
                            ? "failed with header Bearer ghp_secret" + i
                            : null);
                    insert.addBatch();
                    if (i % 25_000 == 0) {
                        insert.executeBatch();
                    }
                }
                insert.executeBatch();
            }
            writer.commit();
            writer.setAutoCommit(true);
        }

        RedactionPolicy policy = RedactionPolicy.forExport()
                .withPathPrefix("/Users/someone/code/p", "[workspace]");

        long began = System.nanoTime();
        TableExport.Result csv;
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + file.toAbsolutePath())) {
            csv = TableExport.write(connection, TableExport.Table.ACTIONS,
                    TableExport.Format.CSV, tempDir.resolve("actions"),
                    Optional.of(new Redactor(policy)));
        }
        long csvMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        began = System.nanoTime();
        TableExport.Result json;
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + file.toAbsolutePath())) {
            json = TableExport.write(connection, TableExport.Table.ACTIONS,
                    TableExport.Format.JSON, tempDir.resolve("actions"),
                    Optional.of(new Redactor(policy)));
        }
        long jsonMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        began = System.nanoTime();
        SessionRedaction.Result redacted = SessionRedaction.copyRedacted(
                file, tempDir.resolve("redacted.sqlite"), policy);
        long redactMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        System.out.printf(
                "ExportScale: %d actions | database %d bytes%n"
                        + "  CSV %d ms (%d bytes) | JSON %d ms (%d bytes)%n"
                        + "  redacted copy %d ms (%d bytes), %d redactions over %d distinct"
                        + " values%n",
                ACTIONS, Files.size(file),
                csvMillis, Files.size(csv.file()), jsonMillis, Files.size(json.file()),
                redactMillis, redacted.bytes(),
                redacted.report().redactions(), redacted.report().distinctSecrets());

        assertThat(csv.rows()).isEqualTo(ACTIONS);
        assertThat(json.rows()).isEqualTo(ACTIONS);
        // Every path carried the workspace prefix and every hundredth row a
        // bearer token, so the redaction had real work to do rather than
        // measuring an empty pass.
        assertThat(redacted.report().redactions()).isGreaterThan(ACTIONS);
        assertThat(Files.readString(csv.file(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("/Users/someone")).isFalse();
    }
}
