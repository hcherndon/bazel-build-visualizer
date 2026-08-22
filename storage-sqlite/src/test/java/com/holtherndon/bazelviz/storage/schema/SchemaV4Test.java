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
 * The schema-v4 decisions that a well-meaning refactor would undo.
 *
 * <p>Each test names the measured Bazel behaviour that forced the shape and the
 * untrue thing the tool would say without it. The measurements are in
 * {@code docs/exec-log-and-profile.md}.
 */
final class SchemaV4Test {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an attempt with no action is storable, because most spawns have none")
    void attemptsNeedNoAction() throws Exception {
        try (SessionDatabase db = migrated("no-action.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "EXECUTION_LOG");
            // Measured: 4 spawns against 13 actionCompleted events, because the
            // other nine run inside the Bazel server and never spawn (K1). A
            // NOT NULL action_id would force dropping them or inventing an
            // action, and both would misstate what ran.
            exec(c, "INSERT INTO action_attempts (task_id, log_entry_index, correlation)"
                    + " VALUES (" + task + ", 0, 'NO_ACTION_EXPECTED')");

            assertThat(scalarIsNull(c, "SELECT action_id FROM action_attempts")).isTrue();
        }
    }

    @Test
    @DisplayName("two attempts can share a label and mnemonic, because every test makes two")
    void twoAttemptsPerTest() throws Exception {
        try (SessionDatabase db = migrated("two-spawns.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "EXECUTION_LOG");
            exec(c, "INSERT INTO labels (value) VALUES ('//pkg:fail_test')");
            exec(c, "INSERT INTO mnemonics (value) VALUES ('TestRunner')");
            // The first spawn ran the test and exited 1; the second generated
            // test.xml and exited 0 (K3). A unique constraint on
            // (label, mnemonic) would reject the pair, and taking either one as
            // "the" result reports a failing test as passing half the time.
            for (int i = 0; i < 2; i++) {
                exec(c, "INSERT INTO action_attempts"
                        + " (task_id, log_entry_index, correlation, label_id, mnemonic_id, exit_code)"
                        + " VALUES (" + task + ", " + i + ", 'MATCHED_BY_TEST_LABEL', 1, 1, "
                        + (i == 0 ? 1 : 0) + ")");
            }
            assertThat(scalar(c, "SELECT count(*) FROM action_attempts")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an attempt can have a duration and no start, which is every 6.5.0 attempt")
    void durationWithoutAStart() throws Exception {
        try (SessionDatabase db = migrated("no-start.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "EXECUTION_LOG");
            // Bazel 6.5.0 never emits start_time, on any flag setting (S2).
            exec(c, "INSERT INTO action_attempts (task_id, log_entry_index, correlation,"
                    + " total_micros, start_unknown_reason) VALUES (" + task + ", 0,"
                    + " 'MATCHED_BY_OUTPUT', 25800, 'Bazel 6.5.0 does not report when a spawn"
                    + " started')");

            assertThat(scalarIsNull(c, "SELECT start_micros FROM action_attempts")).isTrue();
            assertThat(scalar(c, "SELECT total_micros FROM action_attempts")).isEqualTo(25800);
            // The reason is what stops the blank reading as "instantaneous".
            assertThat(text(c, "SELECT start_unknown_reason FROM action_attempts"))
                    .contains("6.5.0");
        }
    }

    @Test
    @DisplayName("an output that was declared and never produced is stored, not skipped")
    void unproducedOutputsSurvive() throws Exception {
        try (SessionDatabase db = migrated("invalid-output.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "EXECUTION_LOG");
            exec(c, "INSERT INTO action_attempts (task_id, log_entry_index, correlation)"
                    + " VALUES (" + task + ", 0, 'MATCHED_BY_TEST_LABEL')");
            exec(c, "INSERT INTO artifacts (path) VALUES ('testlogs/pkg/fail_test/test.xml')");
            // On 7.6.1 a failing test's entire output list is invalid entries
            // (K2). Skipping them leaves a spawn that looks like it declared
            // no outputs at all.
            exec(c, "INSERT INTO attempt_outputs (attempt_id, artifact_id, kind, produced)"
                    + " VALUES (1, 1, 'UNKNOWN', 0)");

            assertThat(scalar(c, "SELECT count(*) FROM attempt_outputs WHERE produced = 0"))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a redacted environment value is distinguishable from an unset one")
    void redactedIsNotAbsent() throws Exception {
        try (SessionDatabase db = migrated("env.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "EXECUTION_LOG");
            exec(c, "INSERT INTO action_attempts (task_id, log_entry_index, correlation)"
                    + " VALUES (" + task + ", 0, 'MATCHED_BY_OUTPUT')");
            exec(c, "INSERT INTO attempt_env_vars (attempt_id, name, value, redacted)"
                    + " VALUES (1, 'API_TOKEN', NULL, 1)");
            exec(c, "INSERT INTO attempt_env_vars (attempt_id, name, value, redacted)"
                    + " VALUES (1, 'EMPTY_ON_PURPOSE', '', 0)");

            assertThat(scalar(c,
                    "SELECT count(*) FROM attempt_env_vars WHERE redacted = 1")).isEqualTo(1);
            assertThat(scalar(c,
                    "SELECT count(*) FROM attempt_env_vars WHERE value = '' AND redacted = 0"))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a phase can start before zero, because Launch Blaze does")
    void phasesCanStartNegative() throws Exception {
        try (SessionDatabase db = migrated("phases.db")) {
            Connection c = db.writerConnection();
            // ts = 0 is "Initialize command"; Launch Blaze runs from -17,000 to
            // -20,000 us depending on version (P3).
            exec(c, "INSERT INTO build_phases (ordinal, name, start_micros, end_micros,"
                    + " end_is_derived) VALUES (0, 'Launch Blaze', -19000, 0, 0)");
            exec(c, "INSERT INTO build_phases (ordinal, name, start_micros, end_micros,"
                    + " end_is_derived) VALUES (1, 'Initialize command', 0, 58427, 1)");

            assertThat(scalar(c, "SELECT start_micros FROM build_phases WHERE ordinal = 0"))
                    .isEqualTo(-19000);
            // The derived flag is what stops a computed boundary being read as
            // a measured one.
            assertThat(scalar(c, "SELECT end_is_derived FROM build_phases WHERE ordinal = 1"))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the profile anchor is stored with its meaning, not just its value")
    void anchorCarriesItsMeaning() throws Exception {
        try (SessionDatabase db = migrated("anchor.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "PROFILE");
            // On 6.5.0 and 7.6.1 the key is named profile_finish_ts and holds
            // the start, floored to the second (P1). Storing only the long
            // loses both facts, and the second one is a one-second alignment
            // error nobody would see.
            exec(c, "INSERT INTO profile_metadata (id, task_id, anchor_micros,"
                    + " anchor_source_key, anchor_meaning, uncertainty_micros)"
                    + " VALUES (1, " + task + ", 1787434075000000, 'profile_finish_ts',"
                    + " 'START_FLOORED_TO_SECOND', 1000000)");

            assertThat(text(c, "SELECT anchor_source_key FROM profile_metadata"))
                    .isEqualTo("profile_finish_ts");
            assertThat(scalar(c, "SELECT uncertainty_micros FROM profile_metadata"))
                    .isEqualTo(1_000_000);
        }
    }

    @Test
    @DisplayName("profile_metadata holds one row and cannot hold two")
    void anchorIsASingleton() throws Exception {
        try (SessionDatabase db = migrated("singleton.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "PROFILE");
            exec(c, "INSERT INTO profile_metadata (id, task_id, anchor_meaning)"
                    + " VALUES (1, " + task + ", 'EXACT_START')");
            assertThatThrownBy(() -> exec(c, "INSERT INTO profile_metadata (id, task_id,"
                    + " anchor_meaning) VALUES (2, " + task + ", 'EXACT_START')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("Bazel's critical path has no action foreign key, on purpose")
    void criticalPathIsNotJoined() throws Exception {
        try (SessionDatabase db = migrated("critical.db")) {
            // Its only identifier is a progress message Bazel is free to reword
            // (P5). A foreign key here would be an invitation to parse it.
            List<String> columns = new ArrayList<>();
            try (Statement s = db.writerConnection().createStatement();
                    ResultSet rows = s.executeQuery("PRAGMA table_info(bazel_critical_path)")) {
                while (rows.next()) {
                    columns.add(rows.getString("name"));
                }
            }
            assertThat(columns).contains("description").doesNotContain("action_id");
        }
    }

    @Test
    @DisplayName("every v4 table survives a foreign-key check")
    void referentialIntegrity() throws Exception {
        try (SessionDatabase db = migrated("fk.db")) {
            Connection c = db.writerConnection();
            long task = insertTask(c, "EXECUTION_LOG");
            exec(c, "INSERT INTO input_sets (task_id, log_id) VALUES (" + task + ", 2)");
            exec(c, "INSERT INTO action_attempts (task_id, log_entry_index, correlation,"
                    + " input_set_id) VALUES (" + task + ", 0, 'MATCHED_BY_OUTPUT', 1)");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rows.next()).as("a foreign key violation exists").isFalse();
            }
        }
    }

    @Test
    @DisplayName("v4 is the version the runner reports, and migrating twice changes nothing")
    void migrationIsIdempotent() throws Exception {
        Path file = tempDir.resolve("idempotent.db");
        try (SessionDatabase db = SessionDatabase.open(file)) {
            MigrationRunner runner = MigrationRunner.standard();
            assertThat(runner.migrate(db)).isEqualTo(4);
            assertThat(runner.migrate(db)).isEqualTo(4);
            assertThat(MigrationRunner.LATEST_VERSION).isEqualTo(4);
        }
    }

    // ------------------------------------------------------------------ helpers

    private SessionDatabase migrated(String name) throws SQLException {
        SessionDatabase db = SessionDatabase.open(tempDir.resolve(name));
        MigrationRunner.standard().migrate(db);
        return db;
    }

    private static long insertTask(Connection c, String kind) throws SQLException {
        exec(c, "INSERT INTO enrichment_tasks (kind, state) VALUES ('" + kind + "', 'RUNNING')");
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

    private static boolean scalarIsNull(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            if (!rows.next()) {
                return false;
            }
            rows.getLong(1);
            return rows.wasNull();
        }
    }
}
