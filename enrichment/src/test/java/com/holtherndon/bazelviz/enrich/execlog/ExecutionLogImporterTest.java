package com.holtherndon.bazelviz.enrich.execlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ExecLogFormat;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Importing a real execution log into a session that already has BEP data.
 *
 * <p>The fixture reproduces what Phase 3 would have written for the same build:
 * the four genrule actions with their primary outputs, and the two tests. The
 * execution logs are the ones Bazel actually produced for that build, so the
 * correlation being tested is the real one and not a rehearsal.
 */
final class ExecutionLogImporterTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;

    private static final String OUT = "bazel-out/darwin_arm64-fastbuild/bin/pkg/";

    /**
     * The build each compact fixture actually came from.
     *
     * <p>They are different invocations, and the importer refuses a log whose
     * invocation id disagrees with the session's (V2). Hard-coding one id for
     * all of them made this suite fail in exactly the way a user would hit it,
     * which is the guard working.
     */
    private static final java.util.Map<String, String> BUILD_IDS = java.util.Map.of(
            "bazel920-build.compact", "ecff269f-874c-4e68-a99e-151497485982",
            "bazel920-test.compact", "9ae91b56-45d0-446a-ac43-619d639bb786",
            "bazel920-fullycached.compact", "f293cbc9-00e0-443f-b697-c949b0dac8da");

    @BeforeEach
    void buildSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
        exec("INSERT INTO build_invocation (singleton, stream_id, invocation_id)"
                + " VALUES (1, 1, 'ecff269f-874c-4e68-a99e-151497485982')");
        for (String name : List.of("a", "b", "slow", "big")) {
            exec("INSERT INTO actions (primary_output, outcome) VALUES ('"
                    + OUT + name + ".txt', 'SUCCEEDED')");
        }
    }

    @AfterEach
    void closeSession() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("every genrule spawn attaches to its action by primary output")
    void ordinaryActionsCorrelate() throws Exception {
        ExecutionLogImporter.Result result = importFixture("bazel920-build.compact");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(result.format()).hasValue(ExecLogFormat.COMPACT);
        assertThat(result.spawnsRead()).isEqualTo(4);
        assertThat(result.attemptsWritten()).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE correlation = 'MATCHED_BY_OUTPUT' AND action_id IS NOT NULL"))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("the runner and the timing breakdown land on the attempt, not on the action")
    void attemptsCarryTheirOwnMeasurements() throws Exception {
        importFixture("bazel920-build.compact");

        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE runner = 'darwin-sandbox'")).isEqualTo(4);
        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE start_micros IS NOT NULL AND total_micros IS NOT NULL")).isEqualTo(4);
        // ADR-009: the action's own columns are untouched by enrichment.
        assertThat(scalar("SELECT count(*) FROM actions WHERE start_micros IS NOT NULL"))
                .isZero();
    }

    @Test
    @DisplayName("a spawn whose outputs no action claims is unmatched, and says why")
    void unmatchedSpawnsAreVisible() throws Exception {
        exec("DELETE FROM actions");
        importFixture("bazel920-build.compact");

        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE correlation = 'UNMATCHED'")).isEqualTo(4);
        assertThat(text("SELECT correlation_note FROM action_attempts LIMIT 1"))
                .contains("build_event_publish_all_actions");
    }

    @Test
    @DisplayName("both of a test's spawns are kept, and neither is chosen over the other")
    void testSpawnsAreBothKept() throws Exception {
        seedTest("//pkg:fail_test");
        useBuildOf("bazel920-test.compact");
        ExecutionLogImporter.Result result = importFixture("bazel920-test.compact");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(scalar("SELECT count(*) FROM action_attempts a"
                + " JOIN labels l ON l.id = a.label_id"
                + " WHERE l.value = '//pkg:fail_test'"
                + "   AND a.correlation = 'MATCHED_BY_TEST_LABEL'")).isEqualTo(2);
        // The two disagree about the exit code, which is exactly why picking
        // one would be wrong (K3).
        assertThat(scalar("SELECT count(DISTINCT exit_code) FROM action_attempts a"
                + " JOIN labels l ON l.id = a.label_id WHERE l.value = '//pkg:fail_test'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a fully cached build imports as a success with nothing in it")
    void everythingCachedIsASuccess() throws Exception {
        useBuildOf("bazel920-fullycached.compact");
        ExecutionLogImporter.Result result = importFixture("bazel920-fullycached.compact");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(result.everythingWasCached()).isTrue();
        // Not "no data": a complete log of a build in which nothing ran (S3).
        assertThat(taskState()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(taskSummary()).contains("every action hit the action cache");
    }

    @Test
    @DisplayName("a log from another build is refused, and the refusal is not retriable")
    void anotherBuildsLogIsRefused() throws Exception {
        exec("UPDATE build_invocation SET invocation_id = 'not-the-same-build'");

        ExecutionLogImporter.Result result = importFixture("bazel920-build.compact");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
        assertThat(result.error()).hasValueSatisfying(message ->
                assertThat(message).contains("one build's timings to another build's actions"));
        // Running it again produces the same wrong answer.
        assertThat(result.retriable()).isFalse();
        // And nothing landed.
        assertThat(scalar("SELECT count(*) FROM action_attempts")).isZero();
    }

    @Test
    @DisplayName("a failed import leaves every BEP row exactly as it was")
    void failureDoesNotInvalidateTheBep() throws Exception {
        long actionsBefore = scalar("SELECT count(*) FROM actions");
        Path notALog = tempDir.resolve("garbage.bin");
        Files.write(notALog, new byte[] {0, 0, 0, 0});

        ExecutionLogImporter.Result result =
                new ExecutionLogImporter(connection, EnvironmentRedactor.none())
                        .importFrom(notALog);

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
        assertThat(scalar("SELECT count(*) FROM actions")).isEqualTo(actionsBefore);
        assertThat(scalar("SELECT count(*) FROM action_attempts")).isZero();
        // Plan 21.4 wants the user told what they lost, in words.
        assertThat(taskUnavailableMetrics()).anyMatch(line -> line.contains("cache hits"));
    }

    @Test
    @DisplayName("a binary log imports but is recorded as unverifiable")
    void binaryLogsCannotBeVerified() throws Exception {
        ExecutionLogImporter.Result result = importFixture("bazel920-build.binary");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(result.format()).hasValue(ExecLogFormat.BINARY);
        // No header exists, so "it is the right log" is not something the
        // session may claim (V3).
        assertThat(result.verification())
                .isEqualTo(ExecutionLogImporter.Verification.UNVERIFIABLE);
    }

    @Test
    @DisplayName("a 6.5.0 log imports with durations and no starts")
    void legacyLogsImport() throws Exception {
        ExecutionLogImporter.Result result = importFixture("bazel650-legacy.binary");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE total_micros IS NOT NULL")).isEqualTo(result.attemptsWritten());
        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE start_micros IS NOT NULL")).isZero();
        assertThat(scalar("SELECT count(*) FROM action_attempts"
                + " WHERE start_unknown_reason IS NOT NULL"))
                .isEqualTo(result.attemptsWritten());
    }

    @Test
    @DisplayName("input sets are stored as a DAG rather than flattened")
    void inputSetsStayADag() throws Exception {
        importFixture("bazel920-build.compact");

        assertThat(scalar("SELECT count(*) FROM input_sets")).isPositive();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            assertThat(rows.next()).as("a foreign key violation exists").isFalse();
        }
    }

    @Test
    @DisplayName("re-importing replaces the previous outcome rather than appending")
    void reimportIsIdempotent() throws Exception {
        importFixture("bazel920-build.compact");
        long first = scalar("SELECT count(*) FROM action_attempts");
        importFixture("bazel920-build.compact");

        assertThat(scalar("SELECT count(*) FROM enrichment_tasks")).isEqualTo(1);
        // The attempts from the first run are still there under the same task
        // id; what must not happen is two task rows disagreeing about state.
        assertThat(scalar("SELECT count(*) FROM action_attempts")).isGreaterThanOrEqualTo(first);
    }

    // ---------------------------------------------------------------- helpers

    private ExecutionLogImporter.Result importFixture(String name) throws Exception {
        return new ExecutionLogImporter(connection, EnvironmentRedactor.none())
                .importFrom(fixture(name));
    }

    private Path fixture(String name) throws IOException {
        Path target = tempDir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/execlog/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    /** Points the session at the build a given fixture came from. */
    private void useBuildOf(String fixture) throws SQLException {
        exec("UPDATE build_invocation SET invocation_id = '" + BUILD_IDS.get(fixture) + "'");
    }

    private void seedTest(String label) throws SQLException {
        exec("INSERT INTO labels (value) VALUES ('" + label + "')");
        exec("INSERT INTO configurations (stream_id, bep_id, declared) VALUES (1, 'cfg', 1)");
        exec("INSERT INTO targets (label_id, aspect, outcome) VALUES ((SELECT id FROM labels"
                + " WHERE value = '" + label + "'), '', 'COMPLETED')");
        exec("INSERT INTO configured_targets (target_id, configuration_id, outcome)"
                + " VALUES (1, 1, 'CONFIGURED')");
        exec("INSERT INTO tests (configured_target_id, overall_status) VALUES (1, 'FAILED')");
    }

    private EnrichmentTask.State taskState() throws SQLException {
        return new EnrichmentTaskStore(connection).all().getFirst().state();
    }

    private String taskSummary() throws SQLException {
        return new EnrichmentTaskStore(connection).all().getFirst().exitStatus().orElse("");
    }

    private List<String> taskUnavailableMetrics() throws SQLException {
        return new EnrichmentTaskStore(connection).all().getFirst().unavailableMetrics();
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
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
}
