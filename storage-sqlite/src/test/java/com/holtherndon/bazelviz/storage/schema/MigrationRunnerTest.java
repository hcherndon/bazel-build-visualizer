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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MigrationRunnerTest {

    private static final Set<String> V1_TABLES = Set.of(
            "schema_metadata",
            "session_info",
            "capture_sources",
            "event_streams",
            "import_diagnostics",
            "strings",
            "bep_events",
            "bep_event_ids",
            "bep_event_edges",
            "bep_announced_missing");

    private static final Set<String> V2_TABLES = Set.of(
            "labels",
            "mnemonics",
            "build_invocation",
            "configurations",
            "configuration_make_variables",
            "targets",
            "configured_targets",
            "target_tags",
            "artifacts",
            "depsets",
            "depset_children",
            "depset_files",
            "target_output_groups",
            "target_directory_outputs",
            "actions",
            "tests",
            "test_attempts",
            "test_logs",
            "build_metrics",
            "mnemonic_metrics",
            "runner_counts",
            "cache_miss_details",
            "garbage_metrics",
            "aborted_events",
            "progress_output");

    @TempDir
    Path tempDir;

    @Test
    void appliesEveryTableAndRecordsTheVersion() throws Exception {
        try (SessionDatabase db = open("apply.db")) {
            int version = MigrationRunner.standard().migrate(db);

            assertThat(version).isEqualTo(SchemaV3.VERSION);
            assertThat(tableNames(db.writerConnection())).containsAll(V1_TABLES).containsAll(V2_TABLES);
            assertThat(MigrationRunner.currentVersion(db.writerConnection()))
                    .isEqualTo(SchemaV3.VERSION);
        }
    }

    @Test
    @DisplayName("a session at v2 is renamed forward rather than left ambiguous")
    void v3RenamesTheTestTimingColumns() throws Exception {
        try (SessionDatabase db = open("v2-to-v3.db")) {
            new MigrationRunner(List.of(new V1Migration(), new V2Migration())).migrate(db);
            assertThat(columnNames(db.writerConnection(), "tests"))
                    .contains("first_start_micros", "last_stop_micros")
                    .doesNotContain("bazel_first_start_micros");

            // The rename could have been made in v2's own DDL. It was not,
            // because then a session written before the change and one written
            // after would both record version 2 with different shapes, and
            // nothing could tell them apart.
            assertThat(MigrationRunner.standard().migrate(db)).isEqualTo(SchemaV3.VERSION);
            assertThat(columnNames(db.writerConnection(), "tests"))
                    .contains("bazel_first_start_micros", "bazel_last_stop_micros")
                    .doesNotContain("first_start_micros", "last_stop_micros");
        }
    }

    @Test
    void theShippingRunnerReachesTheAdvertisedLatestVersion() {
        // LATEST_VERSION is what a version error tells the user this build
        // supports; standard() is what it actually applies. They must agree or
        // the message misinforms.
        assertThat(MigrationRunner.standard().latestVersion())
                .isEqualTo(MigrationRunner.LATEST_VERSION);
    }

    @Test
    void runningTwiceIsANoOp() throws Exception {
        try (SessionDatabase db = open("idempotent.db")) {
            MigrationRunner runner = MigrationRunner.standard();
            runner.migrate(db);
            // A marker row survives the second run only if nothing was recreated.
            try (Statement statement = db.writerConnection().createStatement()) {
                statement.execute("INSERT INTO strings (value) VALUES ('survivor')");
            }

            int second = runner.migrate(db);

            assertThat(second).isEqualTo(SchemaV3.VERSION);
            assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM strings")).isEqualTo(1);
            assertThat(tableNames(db.writerConnection())).containsAll(V1_TABLES);
        }
    }

    @Test
    void refusesADatabaseWrittenByANewerBuild() throws Exception {
        try (SessionDatabase db = open("future.db")) {
            MigrationRunner runner = MigrationRunner.standard();
            runner.migrate(db);
            try (Statement statement = db.writerConnection().createStatement()) {
                statement.execute(
                        "UPDATE schema_metadata SET value = '99' WHERE key = 'schema_version'");
            }

            assertThatThrownBy(() -> runner.migrate(db))
                    .isInstanceOf(SchemaVersionException.class)
                    .hasMessageContaining("newer build")
                    .satisfies(thrown -> {
                        SchemaVersionException e = (SchemaVersionException) thrown;
                        assertThat(e.foundVersion()).isEqualTo(99);
                        assertThat(e.supportedVersion()).isEqualTo(SchemaV3.VERSION);
                    });

            // And it refused without touching anything.
            assertThat(MigrationRunner.currentVersion(db.writerConnection())).isEqualTo(99);
            assertThatThrownBy(() -> runner.requireCompatible(db.writerConnection()))
                    .isInstanceOf(SchemaVersionException.class);
        }
    }

    @Test
    void refusesAMetadataTableWithNoRecordedVersion() throws Exception {
        try (SessionDatabase db = open("ambiguous.db")) {
            try (Statement statement = db.writerConnection().createStatement()) {
                statement.execute("CREATE TABLE schema_metadata (key TEXT PRIMARY KEY, value TEXT)");
            }

            assertThatThrownBy(() -> MigrationRunner.standard().migrate(db))
                    .isInstanceOf(SchemaVersionException.class)
                    .hasMessageContaining("carries no");
        }
    }

    @Test
    void reportsUnmigratedForAnEmptyDatabase() throws Exception {
        try (SessionDatabase db = open("empty.db")) {
            assertThat(MigrationRunner.currentVersion(db.writerConnection()))
                    .isEqualTo(MigrationRunner.UNMIGRATED);
            MigrationRunner.standard().requireCompatible(db.writerConnection());
        }
    }

    @Test
    void aFailedMigrationLeavesTheDatabaseUntouched() throws Exception {
        MigrationRunner runner = new MigrationRunner(List.of(new V1Migration(), failingV2()));
        try (SessionDatabase db = open("rollback.db")) {
            assertThatThrownBy(() -> runner.migrate(db))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("no_such_table");

            // v1's tables went in and came back out with the failure: DDL is
            // transactional in SQLite, so the whole run is one atomic step.
            assertThat(tableNames(db.writerConnection())).doesNotContain("bep_events");
            assertThat(MigrationRunner.currentVersion(db.writerConnection()))
                    .isEqualTo(MigrationRunner.UNMIGRATED);
            // Auto-commit was restored despite the failure.
            assertThat(db.writerConnection().getAutoCommit()).isTrue();
        }
    }

    @Test
    void aLaterVersionAppliesOnTopOfAnExistingOne() throws Exception {
        try (SessionDatabase db = open("upgrade.db")) {
            MigrationRunner.standard().migrate(db);
            try (Statement statement = db.writerConnection().createStatement()) {
                statement.execute("INSERT INTO strings (value) VALUES ('kept across the upgrade')");
            }

            MigrationRunner withV4 = new MigrationRunner(
                    List.of(new V1Migration(), new V2Migration(), new V3Migration(), addsATable()));
            assertThat(withV4.migrate(db)).isEqualTo(4);

            // The shipped migrations did not run again — their data is still
            // there — and the new one's table exists.
            assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM strings")).isEqualTo(1);
            assertThat(tableNames(db.writerConnection())).contains("v4_probe");
            assertThat(MigrationRunner.currentVersion(db.writerConnection())).isEqualTo(4);

            // And the shipping runner now refuses the upgraded database.
            assertThatThrownBy(() -> MigrationRunner.standard().migrate(db))
                    .isInstanceOf(SchemaVersionException.class);
        }
    }

    @Test
    void rejectsMalformedMigrationLists() {
        assertThatThrownBy(() -> new MigrationRunner(List.of(new V1Migration(), new V1Migration())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate migration version");
    }

    @Test
    void migrationDoesNotCreateIndexesButFinalizeDoes() throws Exception {
        try (SessionDatabase db = open("indexes.db")) {
            MigrationRunner.standard().migrate(db);
            assertThat(indexNames(db.writerConnection())).doesNotContain("idx_bep_events_sequence");

            SchemaIndexes.createAll(db.writerConnection());
            assertThat(indexNames(db.writerConnection()))
                    .contains(
                            "idx_bep_events_sequence",
                            "idx_bep_events_type_sequence",
                            "idx_bep_events_id_hash",
                            "idx_bep_event_edges_child",
                            "idx_actions_start",
                            "idx_actions_mnemonic",
                            "idx_configured_targets_outcome",
                            "idx_test_attempts_status");

            // Recovery calls this unconditionally, so a second call must be free.
            SchemaIndexes.createAll(db.writerConnection());
            SchemaIndexes.analyze(db.writerConnection());
        }
    }

    private SessionDatabase open(String name) throws SQLException {
        return SessionDatabase.open(tempDir.resolve(name));
    }

    private static Migration failingV2() {
        return new Migration() {
            @Override
            public int version() {
                return 2;
            }

            @Override
            public String description() {
                return "deliberately broken";
            }

            @Override
            public void apply(Connection connection) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO no_such_table (x) VALUES (1)");
                }
            }
        };
    }

    private static Migration addsATable() {
        return new Migration() {
            @Override
            public int version() {
                return 4;
            }

            @Override
            public String description() {
                return "adds v4_probe";
            }

            @Override
            public void apply(Connection connection) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE v4_probe (id INTEGER PRIMARY KEY)");
                }
            }
        };
    }

    private static List<String> columnNames(Connection connection, String table)
            throws SQLException {
        return names(connection, "SELECT name FROM pragma_table_info('" + table + "')");
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        return names(connection, "SELECT name FROM sqlite_master WHERE type = 'table'");
    }

    private static List<String> indexNames(Connection connection) throws SQLException {
        return names(connection, "SELECT name FROM sqlite_master WHERE type = 'index'");
    }

    private static List<String> names(Connection connection, String sql) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }
}
