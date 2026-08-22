package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.core.entity.FailureInfo;
import com.holtherndon.bazelviz.core.entity.FileRef;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the writer does with each command, and what it refuses to do. */
final class EntityWriterTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;
    private long streamId;
    private long nextSequence = 1;

    @BeforeEach
    void openSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("entities.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO event_streams (stream_key, state) VALUES ('build-tool', 'OPEN')");
        streamId = lastId();
    }

    @AfterEach
    void closeSession() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("a configuration nobody declared still gets a row, so its actions survive")
    void undeclaredConfigurationsBecomePlaceholders() throws Exception {
        long sequence = event();
        apply(sequence, action("bazel-out/stable-status.txt", "system", Optional.empty()));

        // "system" is referenced on every build on every measured version and
        // never published. Without the placeholder the foreign key rejects the
        // action, and the workspace-status action disappears from the table.
        assertThat(scalar("SELECT COUNT(*) FROM actions")).isEqualTo(1);
        assertThat(scalar("SELECT declared FROM configurations WHERE bep_id = 'system'")).isZero();

        // And when the real event arrives later, the same row is filled in
        // rather than duplicated.
        long declared = event();
        apply(declared, new EntityCommand.ConfigurationDeclared(
                "system", "exec", "darwin", "darwin_arm64", true, Map.of("TARGET_CPU", "arm64")));

        assertThat(scalar("SELECT COUNT(*) FROM configurations")).isEqualTo(1);
        assertThat(scalar("SELECT declared FROM configurations")).isEqualTo(1);
        assertThat(text("SELECT cpu FROM configurations")).isEqualTo("darwin_arm64");
        assertThat(scalar("SELECT COUNT(*) FROM configuration_make_variables")).isEqualTo(1);
    }

    @Test
    @DisplayName("an action with no label lands with a null label, not a blank one")
    void actionsWithoutALabel() throws Exception {
        apply(event(), action("bazel-out/stable-status.txt", "system", Optional.empty()));

        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery(
                        "SELECT label_id, mnemonic_id FROM actions")) {
            assertThat(rows.next()).isTrue();
            rows.getLong("label_id");
            assertThat(rows.wasNull()).isTrue();
        }
        assertThat(scalar("SELECT COUNT(*) FROM labels")).isZero();
    }

    @Test
    @DisplayName("a failed action stores the spawn's exit code, not Bazel's constant 1")
    void failedActionsKeepBothExitCodes() throws Exception {
        FailureInfo failure = new FailureInfo(
                Optional.of("spawn"),
                Optional.of("NON_ZERO_EXIT"),
                "Action failed: /bin/sh -c 'exit 7'",
                OptionalInt.of(7));
        EntityCommand.ActionCompleted failed = new EntityCommand.ActionCompleted(
                "bazel-out/darwin-fastbuild/bin/pkg/out.txt",
                Optional.of("//pkg:boom"),
                "cfg-1",
                Optional.of("Genrule"),
                false,
                OptionalInt.of(1),
                Optional.of(failure),
                ActionTiming.NONE,
                List.of("/bin/sh", "-c", "exit 7"),
                Optional.empty(),
                Optional.of("file:///tmp/stderr"));

        apply(event(), failed);

        // Bazel reported 1 for a command that exited 7. Showing the user 1
        // would send them looking for a failure that did not happen.
        assertThat(scalar("SELECT bazel_exit_code FROM actions")).isEqualTo(1);
        assertThat(scalar("SELECT spawn_exit_code FROM actions")).isEqualTo(7);
        assertThat(text("SELECT failure_category FROM actions")).isEqualTo("spawn/NON_ZERO_EXIT");
        assertThat(text("SELECT outcome FROM actions")).isEqualTo("FAILED");
        // The argv is stored as it arrived, newline-joined -- never re-quoted
        // into something that looks executable and is not.
        assertThat(text("SELECT command_line FROM actions")).isEqualTo("/bin/sh\n-c\nexit 7");
    }

    @Test
    @DisplayName("an action Bazel timed as zero-length records the reason, not a zero duration")
    void zeroLengthTimingIsUnknown() throws Exception {
        EntityCommand.ActionCompleted sameInstant = new EntityCommand.ActionCompleted(
                "bazel-out/bin/slow.o",
                Optional.of("//pkg:slow"),
                "cfg-1",
                Optional.of("CppCompile"),
                true,
                OptionalInt.empty(),
                Optional.empty(),
                ActionTiming.of(OptionalLong.of(1_000_000L), OptionalLong.of(1_000_000L)),
                List.of(),
                Optional.empty(),
                Optional.empty());

        apply(event(), sameInstant);

        // Bazel 8.4.1 reports this for every action, a five second sleep
        // included. Both timestamps are kept; the duration is refused.
        assertThat(scalar("SELECT start_micros FROM actions")).isEqualTo(1_000_000L);
        assertThat(scalar("SELECT end_micros FROM actions")).isEqualTo(1_000_000L);
        assertThat(text("SELECT duration_unknown_reason FROM actions"))
                .isEqualTo(ActionTiming.ZERO_LENGTH_SPAN);
    }

    @Test
    @DisplayName("a repeated primary output is counted, not merged and not thrown")
    void repeatedActionsAreCounted() throws Exception {
        try (EntityWriter writer = new EntityWriter(connection)) {
            long first = event();
            long second = event();
            writer.apply(streamId, first, action("bazel-out/a.o", "cfg-1", Optional.of("//a:a")));
            writer.apply(streamId, second, action("bazel-out/a.o", "cfg-1", Optional.of("//b:b")));
            writer.flush();

            assertThat(scalar("SELECT COUNT(*) FROM actions")).isEqualTo(1);
            // The first row wins and the second is reported rather than
            // silently overwriting it: an upsert would show one action where
            // two ran and give it the wrong owner.
            assertThat(writer.conflictingActions()).isEqualTo(1);
            assertThat(labelOfAction("bazel-out/a.o")).isEqualTo("//a:a");
        }
    }

    @Test
    @DisplayName("a target that only ever got configured is still a row")
    void configuredTargetsDoNotWaitForCompletion() throws Exception {
        apply(event(), new EntityCommand.TargetConfigured(
                "//app:main",
                Optional.empty(),
                Optional.of("java_binary rule"),
                Optional.empty(),
                List.of("manual")));

        assertThat(scalar("SELECT COUNT(*) FROM targets")).isEqualTo(1);
        assertThat(text("SELECT outcome FROM targets")).isEqualTo("CONFIGURED");
        assertThat(scalar("SELECT COUNT(*) FROM configured_targets")).isZero();
        assertThat(text("SELECT from_event FROM target_tags")).isEqualTo("CONFIGURED");
    }

    @Test
    @DisplayName("the completion's synthetic tags stay separable from the ones the user wrote")
    void tagsRecordTheirSource() throws Exception {
        apply(event(), new EntityCommand.TargetConfigured(
                "//app:some_test",
                Optional.empty(),
                Optional.of("java_test rule"),
                Optional.of("SMALL"),
                List.of("manual")));
        apply(event(), completed("//app:some_test", "cfg-1", true, List.of("manual", "small")));

        // From Bazel 7.6.1 the completion list gains size, timeout and flakiness
        // tags the user never wrote. One flat set would show `small` as if it
        // were in the BUILD file.
        assertThat(scalar(
                        "SELECT COUNT(*) FROM target_tags WHERE from_event = 'CONFIGURED'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM target_tags WHERE from_event = 'COMPLETED'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a target completing without ever being configured still gets both rows")
    void completionCreatesTheTargetItNeeds() throws Exception {
        // From Bazel 7.6.1 an analysis-failed target emits no `configured`
        // payload at all. Without this the failures view could not name it.
        apply(event(), completed("//app:never_configured", "cfg-1", false, List.of()));

        assertThat(scalar("SELECT COUNT(*) FROM targets")).isEqualTo(1);
        assertThat(text("SELECT outcome FROM configured_targets")).isEqualTo("FAILED");
        // Nothing described the target, so its kind stays unknown rather than
        // being invented from the label.
        assertThat(text("SELECT target_kind FROM targets")).isNull();
    }

    @Test
    @DisplayName("a shared file set is stored once and referenced from both parents")
    void depsetsFormADag() throws Exception {
        apply(event(), new EntityCommand.DepsetDeclared(
                "0", List.of(), List.of(generated("pkg/lib.a", 4096))));
        apply(event(), new EntityCommand.DepsetDeclared("1", List.of("0"), List.of()));
        apply(event(), new EntityCommand.DepsetDeclared("2", List.of("0"), List.of()));

        assertThat(scalar("SELECT COUNT(*) FROM depsets")).isEqualTo(3);
        assertThat(scalar("SELECT COUNT(*) FROM depset_files")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM depset_children")).isEqualTo(2);
        assertThat(scalar("SELECT size_bytes FROM artifacts")).isEqualTo(4096);
        assertThat(text("SELECT path FROM artifacts"))
                .isEqualTo("bazel-out/darwin-fastbuild/bin/pkg/lib.a");
    }

    @Test
    @DisplayName("a file set referenced but never defined is recorded as undefined")
    void forwardReferencesAreVisibleRatherThanDropped() throws Exception {
        // Measured zero times in 1,829 references, so this is evidence of a
        // truncated capture. Dropping the edge would hide it; inventing a
        // definition would be worse.
        apply(event(), new EntityCommand.DepsetDeclared("1", List.of("99"), List.of()));

        assertThat(scalar("SELECT COUNT(*) FROM depset_children")).isEqualTo(1);
        try (EntityWriter writer = new EntityWriter(connection)) {
            assertThat(writer.undefinedDepsets(streamId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an output group keeps its incomplete flag and its root set")
    void outputGroupsKeepIncompleteness() throws Exception {
        apply(event(), new EntityCommand.DepsetDeclared(
                "58", List.of(), List.of(generated("pkg/out.txt", 6))));
        apply(event(), new EntityCommand.TargetCompleted(
                "//pkg:m_boom",
                Optional.empty(),
                "cfg-1",
                false,
                List.of(),
                OptionalLong.empty(),
                List.of(new EntityCommand.OutputGroupRef("default", Optional.of("58"), true)),
                List.of(),
                Optional.empty()));

        assertThat(scalar("SELECT incomplete FROM target_output_groups")).isEqualTo(1);
        assertThat(scalar(
                        "SELECT COUNT(*) FROM target_output_groups g"
                                + " JOIN depsets d ON d.id = g.root_depset_id"
                                + " WHERE d.bep_id = '58'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a tree artifact is recorded with no size and kept out of the file rows")
    void treeArtifactsAreSeparate() throws Exception {
        apply(event(), new EntityCommand.TargetCompleted(
                "//pkg:tree",
                Optional.empty(),
                "cfg-1",
                true,
                List.of(),
                OptionalLong.empty(),
                List.of(),
                List.of(directory("pkg/treedir.d")),
                Optional.empty()));

        assertThat(scalar("SELECT is_directory FROM artifacts")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM target_directory_outputs")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM depset_files")).isZero();
        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery("SELECT size_bytes FROM artifacts")) {
            assertThat(rows.next()).isTrue();
            rows.getLong(1);
            // Bazel reports a tree twice, as the directory and as its expanded
            // children. Only the children carry bytes; a zero here would claim
            // an empty directory and a real size would double-count.
            assertThat(rows.wasNull()).isTrue();
        }
    }

    @Test
    @DisplayName("every attempt survives, and the summary's verdict is the target's")
    void testsKeepEveryAttempt() throws Exception {
        apply(event(), attempt("//t:flaky_test", 1, 1, 1, TestOutcome.FAILED));
        apply(event(), attempt("//t:flaky_test", 1, 1, 2, TestOutcome.PASSED));
        apply(event(), new EntityCommand.TestSummarized(
                "//t:flaky_test",
                "cfg-1",
                TestOutcome.FLAKY,
                OptionalInt.of(1),
                OptionalInt.of(1),
                OptionalInt.empty(),
                OptionalInt.of(2),
                0,
                OptionalLong.of(2_000_000L),
                OptionalLong.of(3_000_000L),
                OptionalLong.of(150_000L),
                List.of()));

        assertThat(scalar("SELECT COUNT(*) FROM test_attempts")).isEqualTo(2);
        assertThat(text("SELECT overall_status FROM tests")).isEqualTo("FLAKY");
        // The summary's window says 1 s; the attempts ran from 2.0 s to 2.1 s.
        // Bazel's figure excludes failed retries and understated real wall time
        // by 13x on a measured six-attempt test, so the elapsed time the views
        // show is computed from the attempts and Bazel's is kept beside it.
        assertThat(scalar("SELECT bazel_first_start_micros FROM tests")).isEqualTo(2_000_000L);
        // FLAKY exists only on the summary. An attempt carries its own truth,
        // and losing the failed one leaves a green result with no evidence.
        assertThat(text("SELECT status FROM test_attempts WHERE attempt = 1")).isEqualTo("FAILED");
        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery("SELECT shard_count FROM tests")) {
            assertThat(rows.next()).isTrue();
            rows.getLong(1);
            // Not sharded means no shard count, not zero shards.
            assertThat(rows.wasNull()).isTrue();
        }
    }

    @Test
    @DisplayName("a summary's log and its attempt's log are one file, not two")
    void testLogsMergeAcrossSources() throws Exception {
        String uri = "file:///out/t/test.log";
        apply(event(), new EntityCommand.TestAttemptCompleted(
                "//t:one_test",
                "cfg-1",
                1,
                1,
                1,
                TestOutcome.PASSED,
                false,
                OptionalLong.of(1_000L),
                OptionalLong.of(500L),
                OptionalInt.empty(),
                Optional.of("darwin-sandbox"),
                List.of(new EntityCommand.TestLogRef(
                        Optional.of("test.log"), uri, Optional.empty()))));
        apply(event(), new EntityCommand.TestSummarized(
                "//t:one_test",
                "cfg-1",
                TestOutcome.PASSED,
                OptionalInt.of(1),
                OptionalInt.of(1),
                OptionalInt.empty(),
                OptionalInt.of(1),
                0,
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                List.of(new EntityCommand.TestLogRef(
                        Optional.empty(), uri, Optional.of("PASSED")))));

        // The summary's `passed` list points at the winning attempt's log. Two
        // rows would double the log count for every passing test.
        assertThat(scalar("SELECT COUNT(*) FROM test_logs")).isEqualTo(1);
        assertThat(text("SELECT name FROM test_logs")).isEqualTo("test.log");
        assertThat(text("SELECT summary_status FROM test_logs")).isEqualTo("PASSED");
        assertThat(scalar(
                        "SELECT COUNT(*) FROM test_logs WHERE test_attempt_id IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("metrics this Bazel did not report stay null in the row")
    void unreportedMetricsStayNull() throws Exception {
        apply(event(), new EntityCommand.BuildMetricsReported(
                OptionalLong.of(120),
                OptionalLong.of(4),
                OptionalLong.of(116),
                OptionalLong.of(4),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.of(1189),
                OptionalLong.of(408),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                List.of(new EntityCommand.MnemonicWork(
                        "CppCompile", OptionalLong.of(3), OptionalLong.of(2))),
                List.of(
                        new EntityCommand.RunnerWork(
                                "darwin-sandbox", Optional.of("local"), 4, false),
                        new EntityCommand.RunnerWork("total", Optional.empty(), 4, true)),
                List.of(new EntityCommand.CacheMiss("NOT_CACHED", 4)),
                List.of(new EntityCommand.Garbage("G1 Young Generation", 12_582_912L))));

        assertThat(scalar("SELECT actions_executed FROM build_metrics")).isEqualTo(4);
        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery(
                        "SELECT packages_loaded, critical_path_micros FROM build_metrics")) {
            assertThat(rows.next()).isTrue();
            rows.getLong(1);
            assertThat(rows.wasNull()).isTrue();
            rows.getLong(2);
            assertThat(rows.wasNull()).isTrue();
        }
        assertThat(scalar("SELECT actions_executed FROM mnemonic_metrics")).isEqualTo(2);
        // Bazel appends a synthetic total; summing it alongside the real rows
        // doubles the count.
        assertThat(scalar("SELECT SUM(action_count) FROM runner_counts WHERE is_total = 0"))
                .isEqualTo(4);
        assertThat(scalar("SELECT count FROM cache_miss_details")).isEqualTo(4);
        assertThat(scalar("SELECT collected_bytes FROM garbage_metrics")).isEqualTo(12_582_912L);
    }

    @Test
    @DisplayName("an aborted target appears in the targets tree, not only in the abort log")
    void abortsCreateTheTargetTheyName() throws Exception {
        // From Bazel 7.6.1 an analysis-failed target emits no `configured`
        // payload at all -- an abort riding the targetConfigured id is the only
        // event that names it. Recording just the abort row left every such
        // target out of the tree and out of the counts, which is the whole of
        // what a failed analysis produces.
        apply(event(), new EntityCommand.TargetAborted(
                "targetConfigured",
                Optional.of("//app:never_analysed"),
                Optional.empty(),
                "ANALYSIS_FAILURE",
                ""));

        assertThat(scalar("SELECT COUNT(*) FROM targets")).isEqualTo(1);
        assertThat(text("SELECT outcome FROM targets")).isEqualTo("ABORTED");
        // No configuration in that id kind, so it never became a configured
        // target -- which is true, and better than inventing one.
        assertThat(scalar("SELECT COUNT(*) FROM configured_targets")).isZero();
    }

    @Test
    @DisplayName("an abort does not overwrite a target that really completed")
    void abortsDoNotOverwriteACompletion() throws Exception {
        apply(event(), completed("//pkg:lib", "cfg-1", true, List.of()));
        apply(event(), new EntityCommand.TargetAborted(
                "targetCompleted",
                Optional.of("//pkg:lib"),
                Optional.of("cfg-1"),
                "INCOMPLETE",
                ""));

        // Aborts arrive after buildFinished, so an unconditional update would
        // let a skipped sibling's abort relabel a target that built.
        assertThat(text("SELECT outcome FROM configured_targets")).isEqualTo("BUILT");
        // The target-level outcome does record that something aborted it.
        assertThat(text("SELECT outcome FROM targets")).isEqualTo("ABORTED");
    }

    @Test
    @DisplayName("an abort with no label stays in the log and creates nothing")
    void abortsWithoutALabelNameNoTarget() throws Exception {
        apply(event(), new EntityCommand.TargetAborted(
                "pattern", Optional.empty(), Optional.empty(), "USER_INTERRUPTED", ""));

        assertThat(scalar("SELECT COUNT(*) FROM aborted_events")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM targets")).isZero();
    }

    @Test
    @DisplayName("a redelivered abort does not inflate the count")
    void abortsAreIdempotent() throws Exception {
        long sequence = event();
        EntityCommand.TargetAborted aborted = new EntityCommand.TargetAborted(
                "targetConfigured",
                Optional.of("//app:broken"),
                Optional.empty(),
                "ANALYSIS_FAILURE",
                "");
        apply(sequence, aborted);
        apply(sequence, aborted);

        // Abort volume scales with target count -- 12,000 from one interrupt --
        // so a replayed journal that inserted them twice would double exactly
        // the number the failures view reports.
        assertThat(scalar("SELECT COUNT(*) FROM aborted_events")).isEqualTo(1);
        assertThat(text("SELECT reason FROM aborted_events")).isEqualTo("ANALYSIS_FAILURE");
        assertThat(text("SELECT description FROM aborted_events")).isNull();
    }

    @Test
    @DisplayName("the invocation row keeps what the build said and what it did not")
    void invocationRecordsBothEnds() throws Exception {
        apply(event(), new EntityCommand.InvocationStarted(
                "abc-123",
                "8.4.1",
                "build",
                "/Users/x/ws",
                "/Users/x/ws",
                "Options: --keep_going",
                0,
                OptionalLong.of(5_000_000L)));
        apply(event(), new EntityCommand.InvocationOptions(true));

        assertThat(text("SELECT build_tool_version FROM build_invocation")).isEqualTo("8.4.1");
        assertThat(scalar("SELECT publishes_all_actions FROM build_invocation")).isEqualTo(1);
        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery(
                        "SELECT overall_success, server_pid FROM build_invocation")) {
            assertThat(rows.next()).isTrue();
            // No BuildFinished yet: unknown, which is a different thing from
            // finished-and-failed.
            rows.getInt(1);
            assertThat(rows.wasNull()).isTrue();
            // A pid of zero is not a process.
            rows.getLong(2);
            assertThat(rows.wasNull()).isTrue();
        }

        apply(event(), new EntityCommand.InvocationFinished(
                "BUILD_FAILURE", 1, false, OptionalLong.of(9_000_000L)));
        assertThat(scalar("SELECT overall_success FROM build_invocation")).isZero();
        assertThat(text("SELECT exit_code_name FROM build_invocation")).isEqualTo("BUILD_FAILURE");
        // Both ends kept, so the elapsed time the user watched is derivable.
        assertThat(scalar("SELECT finished_micros - started_micros FROM build_invocation"))
                .isEqualTo(4_000_000L);
    }

    @Test
    @DisplayName("the end marker is recorded, and nothing later unsees it")
    void streamEndIsStickyOnceSeen() throws Exception {
        long stream = event();
        apply(stream, new EntityCommand.InvocationStarted(
                "abc", "9.2.0", "build", "/ws", "/ws", "", 0, OptionalLong.of(1)));
        assertThat(scalar("SELECT saw_last_message FROM build_invocation")).isZero();

        apply(event(), new EntityCommand.StreamEnded());
        assertThat(scalar("SELECT saw_last_message FROM build_invocation")).isEqualTo(1);

        // A fallback file ingested after a live capture, or a re-index, writes
        // the invocation row again. None of that unsees an end marker that
        // arrived, and a session that flipped back to "truncated" would tell
        // the user their complete capture was not.
        apply(event(), new EntityCommand.InvocationStarted(
                "abc", "9.2.0", "build", "/ws", "/ws", "", 0, OptionalLong.of(1)));
        apply(event(), new EntityCommand.InvocationFinished(
                "SUCCESS", 0, true, OptionalLong.of(9)));
        assertThat(scalar("SELECT saw_last_message FROM build_invocation")).isEqualTo(1);
    }

    @Test
    @DisplayName("applying the same commands twice leaves the same rows")
    void replayIsIdempotent() throws Exception {
        List<EntityCommand> commands = List.of(
                new EntityCommand.ConfigurationDeclared(
                        "cfg-1", "darwin-fastbuild", "darwin", "darwin_arm64", false, Map.of()),
                new EntityCommand.TargetConfigured(
                        "//pkg:lib", Optional.empty(), Optional.of("java_library rule"),
                        Optional.empty(), List.of("manual")),
                new EntityCommand.DepsetDeclared(
                        "0", List.of(), List.of(generated("pkg/lib.jar", 900))),
                completed("//pkg:lib", "cfg-1", true, List.of("manual")),
                action("bazel-out/bin/pkg/lib.jar", "cfg-1", Optional.of("//pkg:lib")),
                attempt("//t:t", 1, 1, 1, TestOutcome.PASSED));

        long[] sequences = new long[commands.size()];
        for (int i = 0; i < commands.size(); i++) {
            sequences[i] = event();
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < commands.size(); i++) {
                apply(sequences[i], commands.get(i));
            }
        }

        // Crash recovery replays the journal from the last checkpoint, so this
        // is the ordinary case rather than an edge case: the second pass must
        // change nothing.
        for (String table : List.of("configurations", "targets", "configured_targets", "depsets",
                "depset_files", "artifacts", "actions", "tests", "test_attempts", "target_tags",
                "labels", "mnemonics")) {
            assertThat(scalar("SELECT COUNT(*) FROM " + table)).as(table).isLessThanOrEqualTo(3);
        }
        assertThat(scalar("SELECT COUNT(*) FROM actions")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM targets")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM depset_files")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM target_tags")).isEqualTo(2);
    }

    @Test
    @DisplayName("every normalized row can name the event it came from")
    void provenanceIsRecorded() throws Exception {
        long configuration = event();
        long target = event();
        apply(configuration, new EntityCommand.ConfigurationDeclared(
                "cfg-1", "darwin-fastbuild", "darwin", "darwin_arm64", false, Map.of()));
        apply(target, new EntityCommand.TargetConfigured(
                "//pkg:lib", Optional.empty(), Optional.of("java_library rule"),
                Optional.empty(), List.of()));

        // The trace from a row to the bytes behind it is one join, which is
        // what makes "event-to-domain provenance is inspectable" true rather
        // than aspirational.
        assertThat(scalar(
                        "SELECT e.sequence FROM configurations c"
                                + " JOIN bep_events e ON e.id = c.bep_event_id"))
                .isEqualTo(configuration);
        assertThat(scalar(
                        "SELECT e.sequence FROM targets t"
                                + " JOIN bep_events e ON e.id = t.bep_event_id"))
                .isEqualTo(target);
    }

    // --- helpers ---------------------------------------------------------

    private void apply(long sequence, EntityCommand command) throws SQLException {
        try (EntityWriter writer = new EntityWriter(connection)) {
            writer.apply(streamId, sequence, command);
        }
    }

    private EntityCommand.ActionCompleted action(
            String primaryOutput, String configuration, Optional<String> label) {
        return new EntityCommand.ActionCompleted(
                primaryOutput,
                label,
                configuration,
                label.isPresent() ? Optional.of("Genrule") : Optional.empty(),
                true,
                OptionalInt.empty(),
                Optional.empty(),
                ActionTiming.NONE,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private EntityCommand.TargetCompleted completed(
            String label, String configuration, boolean success, List<String> tags) {
        return new EntityCommand.TargetCompleted(
                label,
                Optional.empty(),
                configuration,
                success,
                tags,
                OptionalLong.empty(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    private EntityCommand.TestAttemptCompleted attempt(
            String label, int run, int shard, int attempt, TestOutcome status) {
        return new EntityCommand.TestAttemptCompleted(
                label,
                "cfg-1",
                run,
                shard,
                attempt,
                status,
                false,
                OptionalLong.of(2_000_000L),
                OptionalLong.of(100_000L),
                OptionalInt.empty(),
                Optional.of("darwin-sandbox"),
                List.of());
    }

    private static FileRef generated(String name, long length) {
        return FileRef.of(
                        name,
                        List.of("bazel-out", "darwin-fastbuild", "bin"),
                        "deadbeef",
                        length,
                        "file:///out/" + name,
                        false)
                .orElseThrow();
    }

    private static FileRef directory(String name) {
        return FileRef.of(
                        name,
                        List.of("bazel-out", "darwin-fastbuild", "bin"),
                        "treedigest",
                        0,
                        "",
                        true)
                .orElseThrow();
    }

    /** Writes the {@code bep_events} row a command's provenance lookup needs. */
    private long event() throws SQLException {
        long sequence = nextSequence++;
        exec("INSERT INTO bep_events (stream_id, sequence, event_type, raw_segment, raw_offset,"
                + " raw_length, decode_status, receive_micros) VALUES ("
                + streamId + ", " + sequence + ", 1, 0, 0, 0, 'OK', 0)");
        return sequence;
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long lastId() throws SQLException {
        return scalar("SELECT last_insert_rowid()");
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }

    private String text(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private String labelOfAction(String primaryOutput) throws SQLException {
        return text("SELECT l.value FROM actions a JOIN labels l ON l.id = a.label_id"
                + " WHERE a.primary_output = '" + primaryOutput + "'");
    }
}
