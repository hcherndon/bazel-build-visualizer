package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a test row says about time, and whose measurement it is.
 *
 * <p>The fixture reproduces the shape measurement TS2 found: Bazel's summary
 * window is much narrower than the attempts it summarises, because it excludes
 * the failed retries. A view that read the summary and called it "elapsed
 * across attempts" would understate the test by the width of the retries — 13x
 * on the six-attempt test that was measured.
 */
final class TestQueriesTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;
    private long streamId;
    private long nextSequence = 1;

    @BeforeEach
    void buildFixture() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("tests.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'OPEN')");
        streamId = scalar("SELECT last_insert_rowid()");

        try (EntityWriter writer = new EntityWriter(connection)) {
            // Three attempts spanning 1.0 s to 6.5 s. Two of them failed.
            writer.apply(streamId, event(), attempt(1, TestOutcome.FAILED, 1_000_000L, 2_000_000L));
            writer.apply(streamId, event(), attempt(2, TestOutcome.FAILED, 3_000_000L, 2_000_000L));
            writer.apply(streamId, event(), attempt(3, TestOutcome.PASSED, 6_000_000L, 500_000L));
            // Bazel's summary reports only the winning attempt's window.
            writer.apply(streamId, event(), new EntityCommand.TestSummarized(
                    "//t:flaky_test",
                    "cfg-1",
                    TestOutcome.FLAKY,
                    OptionalInt.of(1),
                    OptionalInt.of(1),
                    OptionalInt.empty(),
                    OptionalInt.of(3),
                    0,
                    OptionalLong.of(6_000_000L),
                    OptionalLong.of(6_500_000L),
                    OptionalLong.of(500_000L),
                    List.of()));
            writer.flush();
        }
    }

    @AfterEach
    void closeSession() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("elapsed comes from the attempts, and Bazel's figure stays Bazel's")
    void elapsedIsComputedFromTheAttempts() throws Exception {
        try (TestQueries queries = new TestQueries(database.newReadConnection())) {
            TestRow row = queries.firstPage(10).getFirst();

            // 1.0 s to 6.5 s: the whole of what the test did, retries included.
            assertThat(row.firstStartMicros()).hasValue(1_000_000L);
            assertThat(row.lastStopMicros()).hasValue(6_500_000L);
            assertThat(row.wallMicros()).hasValue(5_500_000L);

            // Bazel's window is 0.5 s -- eleven times narrower, because it
            // excludes the two failed attempts. It is kept, under its own name.
            assertThat(row.bazelReportedDurationMicros()).hasValue(500_000L);
            assertThat(scalar("SELECT bazel_first_start_micros FROM tests"))
                    .isEqualTo(6_000_000L);

            assertThat(row.attemptRows()).isEqualTo(3);
            assertThat(row.failedAttemptRows()).isEqualTo(2);
            assertThat(row.hadFailedAttempt()).isTrue();
        }
    }

    @Test
    @DisplayName("a test with no attempts has no elapsed time rather than a zero one")
    void aTestWithNoAttemptsHasNoElapsed() throws Exception {
        try (EntityWriter writer = new EntityWriter(connection)) {
            writer.apply(streamId, event(), new EntityCommand.TestSummarized(
                    "//t:never_ran",
                    "cfg-1",
                    TestOutcome.FAILED_TO_BUILD,
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    0,
                    OptionalLong.of(1_000_000L),
                    OptionalLong.of(2_000_000L),
                    OptionalLong.empty(),
                    List.of()));
            writer.flush();
        }

        try (TestQueries queries = new TestQueries(database.newReadConnection())) {
            TestRow row = queries.firstPage(10).stream()
                    .filter(candidate -> candidate.label().equals("//t:never_ran"))
                    .findFirst()
                    .orElseThrow();

            // Bazel reported a window for a test that produced no attempt.
            // Reporting that as the test's elapsed time would put a duration on
            // a test that never ran.
            assertThat(row.attemptRows()).isZero();
            assertThat(row.firstStartMicros()).isEmpty();
            assertThat(row.wallMicros()).isEmpty();
        }
    }

    private EntityCommand.TestAttemptCompleted attempt(
            int attempt, TestOutcome status, long startMicros, long durationMicros) {
        return new EntityCommand.TestAttemptCompleted(
                "//t:flaky_test",
                "cfg-1",
                1,
                1,
                attempt,
                status,
                false,
                OptionalLong.of(startMicros),
                OptionalLong.of(durationMicros),
                OptionalInt.empty(),
                Optional.of("darwin-sandbox"),
                List.of());
    }

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

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }
}
