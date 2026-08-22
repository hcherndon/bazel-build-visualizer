package com.holtherndon.bazelviz.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schema-v5 decisions a well-meaning refactor would undo.
 *
 * <p>Measurements are in {@code docs/aquery-and-cquery.md}.
 */
final class SchemaV5Test {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a declared action that never ran is storable, because most tests' are")
    void declaredActionsNeedNoExecution() throws Exception {
        try (SessionDatabase db = migrated("declared.db")) {
            Connection c = db.writerConnection();
            long source = insertSource(c);
            // aquery declares the TestRunner action of every test; a `build`
            // invocation runs none of them (Q7). A NOT NULL action_id would
            // drop them or invent an execution.
            exec(c, "INSERT INTO declared_actions (source_id, graph_id) VALUES (" + source + ", 7)");

            assertThat(isNull(c, "SELECT action_id FROM declared_actions")).isTrue();
        }
    }

    @Test
    @DisplayName("the configuration is stored as a checksum, not as aquery's own id")
    void configurationIsTheChecksum() throws Exception {
        try (SessionDatabase db = migrated("config.db")) {
            Connection c = db.writerConnection();
            long source = insertSource(c);
            // aquery's ids are 1 and 2, valid only inside one query's output.
            // The checksum is what equals the BEP's configuration id (Q6).
            exec(c, "INSERT INTO declared_actions (source_id, graph_id, configuration_checksum)"
                    + " VALUES (" + source + ", 1, '1a589d14ca3886895c1228db75ec6c30d0c253d2"
                    + "c9f4c3070e5f3535de94c607')");

            assertThat(text(c, "SELECT configuration_checksum FROM declared_actions"))
                    .hasSize(64);
            // And there is no aquery-local id column to be tempted by.
            assertThat(columns(c, "declared_actions")).doesNotContain("configuration_id");
        }
    }

    @Test
    @DisplayName("is_executable can be unknown, because Bazel 6.5.0 never says")
    void isExecutableIsNullable() throws Exception {
        try (SessionDatabase db = migrated("exec.db")) {
            Connection c = db.writerConnection();
            long source = insertSource(c);
            exec(c, "INSERT INTO declared_actions (source_id, graph_id) VALUES (" + source + ", 1)");

            // Verified rather than inferred: 6.5.0 emits the same two FileWrite
            // actions 7.6.1 marks executable, and emits no field 19 on either
            // (Q9). A DEFAULT 0 would turn "this version does not say" into
            // "not executable".
            assertThat(isNull(c, "SELECT is_executable FROM declared_actions")).isTrue();
        }
    }

    @Test
    @DisplayName("the same producer and consumer can be joined by two derivations")
    void declaredAndObservedEdgesCoexist() throws Exception {
        try (SessionDatabase db = migrated("edges.db")) {
            Connection c = db.writerConnection();
            long source = insertSource(c);
            exec(c, "INSERT INTO declared_actions (source_id, graph_id) VALUES (" + source + ", 1)");
            exec(c, "INSERT INTO declared_actions (source_id, graph_id) VALUES (" + source + ", 2)");
            // An action that declares a hundred inputs and reads three has a
            // hundred declared edges and three observed ones. Both are true.
            exec(c, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (1, 2, 'DECLARED')");
            exec(c, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (1, 2, 'OBSERVED')");

            assertThat(scalar(c, "SELECT count(*) FROM action_edges")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an edge cannot be recorded twice under one derivation")
    void edgesAreDeduplicated() throws Exception {
        try (SessionDatabase db = migrated("dedupe.db")) {
            Connection c = db.writerConnection();
            long source = insertSource(c);
            exec(c, "INSERT INTO declared_actions (source_id, graph_id) VALUES (" + source + ", 1)");
            exec(c, "INSERT INTO declared_actions (source_id, graph_id) VALUES (" + source + ", 2)");
            exec(c, "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                    + " VALUES (1, 2, 'DECLARED')");

            // Plan 13.1 step 5. Two artifacts can justify one producer/consumer
            // pair and the pair is still one edge.
            assertThatThrownBy(() -> exec(c,
                    "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
                            + " VALUES (1, 2, 'DECLARED')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("the configured-target edge has no target configuration, because cquery gives none")
    void configuredTargetEdgesAreLabelsOnly() throws Exception {
        try (SessionDatabase db = migrated("cquery.db")) {
            // cquery's proto output says which configuration a node is in and
            // not which configuration each of its dependencies resolved to. A
            // to_configuration column would be a place to put a guess.
            assertThat(columns(db.writerConnection(), "configured_target_edges"))
                    .contains("to_label_id")
                    .doesNotContain("to_configuration", "to_configuration_checksum");
        }
    }

    @Test
    @DisplayName("a graph source records whether it matched the build's configuration")
    void graphSourcesCarryTheirMatch() throws Exception {
        try (SessionDatabase db = migrated("match.db")) {
            Connection c = db.writerConnection();
            exec(c, "INSERT INTO graph_sources (kind, state, configuration_match, mismatch_detail)"
                    + " VALUES ('DECLARED_ACTIONS', 'SUCCEEDED', 'MISMATCHED',"
                    + " 'the query reported a configuration the build never used')");

            // Plan 12.4: do not silently attach uncertain graph data.
            assertThat(text(c, "SELECT configuration_match FROM graph_sources"))
                    .isEqualTo("MISMATCHED");
            assertThat(text(c, "SELECT mismatch_detail FROM graph_sources")).isNotBlank();
        }
    }

    @Test
    @DisplayName("every v5 table survives a foreign-key check")
    void referentialIntegrity() throws Exception {
        try (SessionDatabase db = migrated("fk.db")) {
            Connection c = db.writerConnection();
            long source = insertSource(c);
            exec(c, "INSERT INTO artifacts (path) VALUES ('bazel-out/bin/a.txt')");
            exec(c, "INSERT INTO graph_depsets (source_id, graph_id) VALUES (" + source + ", 1)");
            exec(c, "INSERT INTO graph_depset_artifacts (depset_id, artifact_id) VALUES (1, 1)");
            exec(c, "INSERT INTO declared_actions (source_id, graph_id, primary_output_id)"
                    + " VALUES (" + source + ", 1, 1)");
            exec(c, "INSERT INTO declared_action_inputs (action_row_id, depset_id) VALUES (1, 1)");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rows.next()).as("a foreign key violation exists").isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private SessionDatabase migrated(String name) throws SQLException {
        SessionDatabase db = SessionDatabase.open(tempDir.resolve(name));
        MigrationRunner.standard().migrate(db);
        return db;
    }

    private static long insertSource(Connection c) throws SQLException {
        exec(c, "INSERT INTO graph_sources (kind, state, configuration_match)"
                + " VALUES ('DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT')");
        return scalar(c, "SELECT last_insert_rowid()");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static boolean isNull(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            if (!rows.next()) {
                return false;
            }
            rows.getLong(1);
            return rows.wasNull();
        }
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rows = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                names.add(rows.getString("name"));
            }
        }
        return names;
    }
}
