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
 * The schema-v2 decisions that a well-meaning refactor would undo.
 *
 * <p>Each test names the Bazel behaviour that forced the shape and the untrue
 * thing the tool would say without it. The measurements are in
 * {@code docs/bep-content.md}.
 */
final class SchemaV2Test {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an action with no label is storable, and reads back absent rather than blank")
    void actionsWithoutALabel() throws Exception {
        try (SessionDatabase db = migrated("no-label.db")) {
            Connection c = db.writerConnection();
            // BazelWorkspaceStatusAction carries no label on 6.5.0, 7.6.1 and
            // 8.4.1. A NOT NULL label column loses the row; an empty string
            // invents a target called "".
            exec(c, "INSERT INTO actions (primary_output, outcome) VALUES"
                    + " ('bazel-out/stable-status.txt', 'SUCCEEDED')");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery("SELECT label_id FROM actions")) {
                assertThat(rows.next()).isTrue();
                rows.getLong("label_id");
                assertThat(rows.wasNull()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a repeated primary output is refused, not silently merged")
    void primaryOutputIsTheIdentity() throws Exception {
        try (SessionDatabase db = migrated("dup-action.db")) {
            Connection c = db.writerConnection();
            exec(c, "INSERT INTO actions (primary_output, outcome) VALUES ('bazel-out/a.o', 'SUCCEEDED')");

            // Measured unique across every stream on all four versions. If it
            // ever is not, the identity assumption has broken and the user needs
            // to be told -- an upsert would quietly keep whichever row came
            // last and report one action where two ran.
            assertThatThrownBy(() -> exec(c,
                            "INSERT INTO actions (primary_output, outcome)"
                                    + " VALUES ('bazel-out/a.o', 'FAILED')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
        }
    }

    @Test
    @DisplayName("an action time Bazel never reported reads back unknown, not zero")
    void absentTimestampsAreUnknown() throws Exception {
        try (SessionDatabase db = migrated("no-times.db")) {
            Connection c = db.writerConnection();
            // Bazel 6.5.0 and 7.6.1 emit no action timestamps at all, and 8.4.1
            // emits endTime == startTime for every action including a five
            // second sleep. Zeroes here would put every action at the epoch and
            // report a build of instantaneous work.
            exec(c, "INSERT INTO actions (primary_output, outcome, duration_unknown_reason)"
                    + " VALUES ('bazel-out/b.o', 'SUCCEEDED', 'BAZEL_VERSION_DOES_NOT_REPORT')");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery(
                            "SELECT start_micros, end_micros, duration_unknown_reason FROM actions")) {
                assertThat(rows.next()).isTrue();
                rows.getLong("start_micros");
                assertThat(rows.wasNull()).isTrue();
                rows.getLong("end_micros");
                assertThat(rows.wasNull()).isTrue();
                assertThat(rows.getString("duration_unknown_reason"))
                        .isEqualTo("BAZEL_VERSION_DOES_NOT_REPORT");
            }
        }
    }

    @Test
    @DisplayName("the undeclared 'system' configuration is a placeholder row, so its actions survive")
    void undeclaredConfigurationsAreRecordedNotDropped() throws Exception {
        try (SessionDatabase db = migrated("system-config.db")) {
            Connection c = db.writerConnection();
            long stream = insertStream(c, "build-tool");

            // Bazel references the configuration id "system" on every build on
            // every version and never publishes a Configuration event for it.
            // The placeholder says exactly that -- referenced, never described
            // -- so the foreign key holds and no action is dropped to protect
            // referential tidiness.
            exec(c, "INSERT INTO configurations (stream_id, bep_id, declared) VALUES ("
                    + stream + ", 'system', 0)");
            long system = lastId(c);
            exec(c, "INSERT INTO actions (primary_output, outcome, configuration_id)"
                    + " VALUES ('bazel-out/c.o', 'SUCCEEDED', " + system + ")");

            assertThat(scalar(c, "SELECT COUNT(*) FROM actions")).isEqualTo(1);
            assertThat(scalar(c, "SELECT declared FROM configurations WHERE bep_id = 'system'"))
                    .isEqualTo(0);

            // And a reference to a configuration nobody recorded at all is still
            // an error -- the placeholder is a deliberate row, not a hole.
            assertThatThrownBy(() -> exec(c,
                            "INSERT INTO actions (primary_output, outcome, configuration_id)"
                                    + " VALUES ('bazel-out/d.o', 'SUCCEEDED', 987654)"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("two configurations with identical payloads and different ids are two rows")
    void configurationsAreKeyedOnTheirIdAlone() throws Exception {
        try (SessionDatabase db = migrated("twin-configs.db")) {
            Connection c = db.writerConnection();
            long stream = insertStream(c, "build-tool");

            // Measured in ordinary builds: byte-identical payloads under
            // different ids. Deduplicating on (mnemonic, cpu, platform) merges
            // configurations that are genuinely distinct, and every target under
            // the loser vanishes.
            exec(c, "INSERT INTO configurations (stream_id, bep_id, declared, mnemonic, cpu)"
                    + " VALUES (" + stream + ", 'aaa', 1, 'darwin_arm64-fastbuild', 'darwin_arm64')");
            exec(c, "INSERT INTO configurations (stream_id, bep_id, declared, mnemonic, cpu)"
                    + " VALUES (" + stream + ", 'bbb', 1, 'darwin_arm64-fastbuild', 'darwin_arm64')");

            assertThat(scalar(c, "SELECT COUNT(*) FROM configurations")).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("named set 0 on two streams is two different sets")
    void depsetIdsAreScopedToTheirStream() throws Exception {
        try (SessionDatabase db = migrated("depset-scope.db")) {
            Connection c = db.writerConnection();
            long first = insertStream(c, "invocation-one");
            long second = insertStream(c, "invocation-two");

            // The ids are dense decimals starting at 0 that every invocation
            // reuses, and a warm rerun reshuffles which content gets which id.
            // A globally unique id would cross-link one build's outputs into
            // another's on the second ingest.
            exec(c, "INSERT INTO depsets (stream_id, bep_id) VALUES (" + first + ", '0')");
            exec(c, "INSERT INTO depsets (stream_id, bep_id) VALUES (" + second + ", '0')");

            assertThat(scalar(c, "SELECT COUNT(*) FROM depsets")).isEqualTo(2);
            assertThatThrownBy(() -> exec(c,
                            "INSERT INTO depsets (stream_id, bep_id) VALUES (" + first + ", '0')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
        }
    }

    @Test
    @DisplayName("a shared file set is stored once and referenced twice")
    void depsetsAreADagNotATree() throws Exception {
        try (SessionDatabase db = migrated("depset-dag.db")) {
            Connection c = db.writerConnection();
            long stream = insertStream(c, "build-tool");
            long shared = insertDepset(c, stream, "0");
            long left = insertDepset(c, stream, "1");
            long right = insertDepset(c, stream, "2");
            exec(c, "INSERT INTO artifacts (path, name, size_bytes) VALUES ('bazel-out/lib.a', 'lib.a', 4096)");
            long artifact = lastId(c);
            exec(c, "INSERT INTO depset_files (depset_id, artifact_id, ordinal) VALUES ("
                    + shared + ", " + artifact + ", 0)");

            // In-degree up to 4 and depth up to 10 were measured in a small
            // workspace. Flattening per referencing target multiplies rows by
            // the sharing factor and double-counts every shared byte.
            exec(c, "INSERT INTO depset_children (parent_id, child_id, ordinal) VALUES ("
                    + left + ", " + shared + ", 0)");
            exec(c, "INSERT INTO depset_children (parent_id, child_id, ordinal) VALUES ("
                    + right + ", " + shared + ", 0)");

            assertThat(scalar(c, "SELECT COUNT(*) FROM depset_files")).isEqualTo(1);
            assertThat(scalar(c,
                            "SELECT COUNT(*) FROM depset_children WHERE child_id = " + shared))
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a target that was configured but never completed is still a target")
    void targetsDoNotWaitForCompletion() throws Exception {
        try (SessionDatabase db = migrated("configured-only.db")) {
            Connection c = db.writerConnection();
            long label = insertLabel(c, "//app:main");

            // An interrupt during analysis produced six configured targets and
            // zero completed ones. A schema that created the row on
            // TargetComplete would show that build as having no targets at all.
            exec(c, "INSERT INTO targets (label_id, outcome) VALUES (" + label + ", 'CONFIGURED')");

            assertThat(scalar(c, "SELECT COUNT(*) FROM targets")).isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM configured_targets")).isZero();
            // target_kind is absent for a 7.6.1+ analysis failure, which emits
            // no `configured` payload at all -- only an aborted event.
            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery("SELECT target_kind FROM targets")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("target_kind")).isNull();
            }
        }
    }

    @Test
    @DisplayName("a tag records which event supplied it")
    void tagsRememberTheirSource() throws Exception {
        try (SessionDatabase db = migrated("tags.db")) {
            Connection c = db.writerConnection();
            long label = insertLabel(c, "//app:some_test");
            exec(c, "INSERT INTO targets (label_id, outcome) VALUES (" + label + ", 'CONFIGURED')");
            long target = lastId(c);

            // From 7.6.1 Bazel appends synthetic tags to the completion list.
            // Storing one flat set shows `small` as though the user had written
            // it in a BUILD file.
            exec(c, "INSERT INTO target_tags (target_id, tag, from_event) VALUES ("
                    + target + ", 'manual', 'CONFIGURED')");
            exec(c, "INSERT INTO target_tags (target_id, tag, from_event) VALUES ("
                    + target + ", 'manual', 'COMPLETED')");
            exec(c, "INSERT INTO target_tags (target_id, tag, from_event) VALUES ("
                    + target + ", 'small', 'COMPLETED')");

            assertThat(scalar(c,
                            "SELECT COUNT(*) FROM target_tags WHERE from_event = 'CONFIGURED'"))
                    .isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM target_tags")).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a tree artifact has no size and is kept out of the file roll-up")
    void treeArtifactsAreNotSummedWithTheirChildren() throws Exception {
        try (SessionDatabase db = migrated("tree.db")) {
            Connection c = db.writerConnection();
            // Bazel reports a tree twice: once as a directoryOutput with a tree
            // digest and no length, and again as its expanded children inside a
            // named set. Adding both counts every byte twice; a zero length
            // would claim an empty directory.
            exec(c, "INSERT INTO artifacts (path, name, digest, is_directory)"
                    + " VALUES ('bazel-out/bin/tree.d', 'tree.d', 'deadbeef', 1)");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery(
                            "SELECT size_bytes, uri, is_directory FROM artifacts")) {
                assertThat(rows.next()).isTrue();
                rows.getLong("size_bytes");
                assertThat(rows.wasNull()).isTrue();
                assertThat(rows.getString("uri")).isNull();
                assertThat(rows.getInt("is_directory")).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("metrics this Bazel version did not report stay unknown")
    void unreportedMetricsAreNull() throws Exception {
        try (SessionDatabase db = migrated("metrics.db")) {
            Connection c = db.writerConnection();
            // criticalPathTime is 9.2.0 only and executionPhaseTimeInMs is
            // absent on 6.5.0. Zeroes would draw an Overview claiming a
            // zero-length execution phase.
            exec(c, "INSERT INTO build_metrics (singleton, actions_executed) VALUES (1, 42)");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery(
                            "SELECT actions_executed, critical_path_micros, execution_phase_millis,"
                                    + " packages_loaded FROM build_metrics")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("actions_executed")).isEqualTo(42);
                for (String unknown :
                        List.of("critical_path_micros", "execution_phase_millis", "packages_loaded")) {
                    rows.getLong(unknown);
                    assertThat(rows.wasNull()).as(unknown).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("a build that never finished is distinguishable from one that failed")
    void overallSuccessHasThreeStates() throws Exception {
        try (SessionDatabase db = migrated("outcome.db")) {
            Connection c = db.writerConnection();
            long stream = insertStream(c, "build-tool");
            // No BuildFinished arrived: overall_success is unknown. Bazel omits
            // the field on failure, so a false there means "finished and
            // failed" -- a different thing from "died before reporting", and the
            // UI must not show a crashed build as a clean failure.
            exec(c, "INSERT INTO build_invocation (singleton, stream_id) VALUES (1, " + stream + ")");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery(
                            "SELECT overall_success, saw_last_message FROM build_invocation")) {
                assertThat(rows.next()).isTrue();
                rows.getInt("overall_success");
                assertThat(rows.wasNull()).isTrue();
                assertThat(rows.getInt("saw_last_message")).isZero();
            }
        }
    }

    @Test
    @DisplayName("every attempt of a run and shard is kept")
    void attemptsAreNotCollapsed() throws Exception {
        try (SessionDatabase db = migrated("attempts.db")) {
            Connection c = db.writerConnection();
            long test = insertTest(c, "//t:flaky_test", "FLAKY");

            // Keeping only the last attempt leaves a green result with no
            // evidence of what made the test flaky. FLAKY is a summary-only
            // status; the attempts carry FAILED then PASSED.
            exec(c, "INSERT INTO test_attempts (test_id, run, shard, attempt, status)"
                    + " VALUES (" + test + ", 1, 1, 1, 'FAILED')");
            exec(c, "INSERT INTO test_attempts (test_id, run, shard, attempt, status)"
                    + " VALUES (" + test + ", 1, 1, 2, 'PASSED')");

            assertThat(scalar(c, "SELECT COUNT(*) FROM test_attempts")).isEqualTo(2);
            assertThatThrownBy(() -> exec(c,
                            "INSERT INTO test_attempts (test_id, run, shard, attempt, status)"
                                    + " VALUES (" + test + ", 1, 1, 2, 'PASSED')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
        }
    }

    @Test
    @DisplayName("an unsharded test records no shard count rather than zero shards")
    void shardCountIsAbsentNotZero() throws Exception {
        try (SessionDatabase db = migrated("shards.db")) {
            Connection c = db.writerConnection();
            insertTest(c, "//t:plain_test", "PASSED");

            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery(
                            "SELECT shard_count, total_num_cached FROM tests")) {
                assertThat(rows.next()).isTrue();
                rows.getLong("shard_count");
                assertThat(rows.wasNull()).isTrue();
                // But totalNumCached absent is proto3's zero -- a known value,
                // so it is stored as one.
                assertThat(rows.getLong("total_num_cached")).isZero();
            }
        }
    }

    @Test
    @DisplayName("Bazel's synthetic runner total is marked, not counted as a runner")
    void theRunnerTotalIsDistinguishable() throws Exception {
        try (SessionDatabase db = migrated("runners.db")) {
            Connection c = db.writerConnection();
            exec(c, "INSERT INTO runner_counts (name, exec_kind, action_count, is_total)"
                    + " VALUES ('darwin-sandbox', 'local', 12, 0)");
            exec(c, "INSERT INTO runner_counts (name, exec_kind, action_count, is_total)"
                    + " VALUES ('total', NULL, 12, 1)");

            assertThat(scalar(c,
                            "SELECT SUM(action_count) FROM runner_counts WHERE is_total = 0"))
                    .isEqualTo(12);
        }
    }

    @Test
    @DisplayName("aborted events are kept for every id kind they ride")
    void abortedEventsUnionAcrossIdKinds() throws Exception {
        try (SessionDatabase db = migrated("aborted.db")) {
            Connection c = db.writerConnection();
            long label = insertLabel(c, "//app:broken");

            // The same aborted payload rides targetConfigured, targetCompleted,
            // unconfiguredLabel and configuredLabel ids, and which one carries
            // an analysis failure changed between 6.5.0 and 7.6.1. Scanning a
            // single id kind finds nothing on half the versions.
            exec(c, "INSERT INTO aborted_events (id_kind, label_id, reason) VALUES ("
                    + "'targetConfigured', " + label + ", 'ANALYSIS_FAILURE')");
            exec(c, "INSERT INTO aborted_events (id_kind, label_id) VALUES ("
                    + "'unconfiguredLabel', " + label + ")");

            assertThat(scalar(c, "SELECT COUNT(*) FROM aborted_events WHERE label_id = " + label))
                    .isEqualTo(2);
            // An absent reason stays absent. Defaulting it to INCOMPLETE would
            // report a cause Bazel never gave.
            try (Statement s = c.createStatement();
                    ResultSet rows = s.executeQuery(
                            "SELECT reason FROM aborted_events WHERE id_kind = 'unconfiguredLabel'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("reason")).isNull();
            }
        }
    }

    @Test
    @DisplayName("the v2 indexes are created by the finalize step, not by the migration")
    void indexesFollowTheBulkLoad() throws Exception {
        try (SessionDatabase db = migrated("v2-indexes.db")) {
            Connection c = db.writerConnection();
            assertThat(indexNames(c)).doesNotContain("idx_actions_start");

            SchemaIndexes.createAll(c);
            assertThat(indexNames(c))
                    .contains("idx_actions_start", "idx_actions_label", "idx_targets_label",
                            "idx_depset_children_child", "idx_test_logs_test", "idx_aborted_label");
        }
    }

    // --- helpers ---------------------------------------------------------

    private SessionDatabase migrated(String name) throws SQLException {
        SessionDatabase db = SessionDatabase.open(tempDir.resolve(name));
        MigrationRunner.standard().migrate(db);
        return db;
    }

    private static long insertStream(Connection c, String key) throws SQLException {
        exec(c, "INSERT INTO event_streams (stream_key, state) VALUES ('" + key + "', 'OPEN')");
        return lastId(c);
    }

    private static long insertLabel(Connection c, String value) throws SQLException {
        exec(c, "INSERT INTO labels (value) VALUES ('" + value + "')");
        return lastId(c);
    }

    private static long insertDepset(Connection c, long stream, String bepId) throws SQLException {
        exec(c, "INSERT INTO depsets (stream_id, bep_id) VALUES (" + stream + ", '" + bepId + "')");
        return lastId(c);
    }

    private static long insertTest(Connection c, String label, String status) throws SQLException {
        long stream = insertStream(c, "stream-for-" + label);
        exec(c, "INSERT INTO configurations (stream_id, bep_id, declared) VALUES ("
                + stream + ", 'cfg', 1)");
        long configuration = lastId(c);
        long labelId = insertLabel(c, label);
        exec(c, "INSERT INTO targets (label_id, outcome) VALUES (" + labelId + ", 'CONFIGURED')");
        long target = lastId(c);
        exec(c, "INSERT INTO configured_targets (target_id, configuration_id, outcome) VALUES ("
                + target + ", " + configuration + ", 'BUILT')");
        long configuredTarget = lastId(c);
        exec(c, "INSERT INTO tests (configured_target_id, overall_status) VALUES ("
                + configuredTarget + ", '" + status + "')");
        return lastId(c);
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long lastId(Connection c) throws SQLException {
        return scalar(c, "SELECT last_insert_rowid()");
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }

    private static List<String> indexNames(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = c.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type = 'index'")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }
}
