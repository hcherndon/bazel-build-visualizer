package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.TestOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The tests table's read path.
 *
 * <h2>The verdict comes from the summary and nowhere else</h2>
 *
 * <p>{@code targetCompleted.success} was measured {@code true} for a test that
 * failed, so nothing here joins to it for a pass/fail. {@code overall_status}
 * is the answer, and the attempts beneath it are the evidence.
 *
 * <h2>Attempt counts are computed, not read</h2>
 *
 * <p>Bazel's {@code attemptCount} is the maximum attempts any (run, shard)
 * needed, which equals the run count for a healthy multi-run test and is
 * therefore useless as "how many times did something have to be retried". The
 * row carries both: Bazel's number under its own name, and the actual attempt
 * and failed-attempt row counts from the attempts table.
 */
public final class TestQueries implements AutoCloseable {

    private static final String COLUMNS =
            "te.id, l.value, c.bep_id, te.overall_status, te.total_run_count, te.run_count,"
                    + " te.shard_count, te.attempt_count, te.total_num_cached,"
                    // Computed from the attempts, not read from the summary.
                    // The summary's window excludes failed retries and its
                    // first start was measured 218-747 ms after the earliest
                    // attempt, so it is not the elapsed time of the test and
                    // the views must not present it as one (TS2, requirement
                    // 35). Bazel's own figures stay available under their own
                    // names for the inspector to show beside it.
                    + " (SELECT MIN(ta.start_micros) FROM test_attempts ta"
                    + "    WHERE ta.test_id = te.id),"
                    + " (SELECT MAX(ta.start_micros + COALESCE(ta.duration_micros, 0))"
                    + "    FROM test_attempts ta"
                    + "    WHERE ta.test_id = te.id AND ta.start_micros IS NOT NULL),"
                    + " te.bazel_reported_duration_micros, ct.test_timeout_seconds,"
                    + " (SELECT COUNT(*) FROM test_attempts ta WHERE ta.test_id = te.id),"
                    + " (SELECT COUNT(*) FROM test_attempts ta WHERE ta.test_id = te.id"
                    + "    AND ta.status <> 'PASSED'),"
                    + " te.bep_event_id";

    private static final String FROM =
            " FROM tests te"
                    + " JOIN configured_targets ct ON ct.id = te.configured_target_id"
                    + " JOIN targets t ON t.id = ct.target_id"
                    + " JOIN labels l ON l.id = t.label_id"
                    + " JOIN configurations c ON c.id = ct.configuration_id";

    private static final String COUNT = "SELECT COUNT(*)" + FROM;

    // Sorted so the rows a user opens this view for are the ones at the top:
    // failures, then flakes, then everything else. Keyset-paged on the same
    // (status rank, id) pair the ordering uses.
    //
    // Unlike the actions table, this ordering is an expression and no index
    // supplies it, so every page costs a scan and a sort. That is deliberate
    // here and would not be there: tests are bounded by the number of test
    // targets, in the thousands rather than the millions, and the sort is over
    // one small table with no join. If a build ever appears where it matters,
    // the fix is the one the actions table uses -- see Keyset.
    private static final String RANK =
            "(CASE te.overall_status"
                    + " WHEN 'FAILED' THEN 0 WHEN 'TIMEOUT' THEN 1 WHEN 'FAILED_TO_BUILD' THEN 2"
                    + " WHEN 'REMOTE_FAILURE' THEN 3 WHEN 'FLAKY' THEN 4 WHEN 'INCOMPLETE' THEN 5"
                    + " WHEN 'NO_STATUS' THEN 6 WHEN 'SKIPPED' THEN 7 ELSE 8 END)";

    private static final String PAGE_FIRST =
            "SELECT " + COLUMNS + FROM + " ORDER BY " + RANK + " ASC, te.id ASC LIMIT ?";

    private static final String PAGE_AFTER =
            "SELECT " + COLUMNS + FROM
                    + " WHERE (" + RANK + " > ? OR (" + RANK + " = ? AND te.id > ?))"
                    + " ORDER BY " + RANK + " ASC, te.id ASC LIMIT ?";

    private static final String ONE = "SELECT " + COLUMNS + FROM + " WHERE te.id = ?";

    private static final String ATTEMPTS =
            "SELECT id, test_id, run, shard, attempt, status, cached_locally, start_micros,"
                    + " duration_micros, exit_code, strategy, bep_event_id FROM test_attempts"
                    + " WHERE test_id = ? ORDER BY run ASC, shard ASC, attempt ASC";

    private static final String LOGS =
            "SELECT name, uri, summary_status, test_attempt_id FROM test_logs"
                    + " WHERE test_id = ? ORDER BY id ASC";

    private static final String STATUS_TALLY =
            "SELECT overall_status, COUNT(*) FROM tests GROUP BY overall_status";

    private final Connection connection;

    public TestQueries(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public long count() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COUNT);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /** The first page, worst results first. */
    public List<TestRow> firstPage(int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PAGE_FIRST)) {
            statement.setInt(1, limit);
            return readRows(statement);
        }
    }

    /** The page after {@code anchor}, in the same order. */
    public List<TestRow> pageAfter(TestRow anchor, int limit) throws SQLException {
        int rank = rankOf(anchor.overallStatus());
        try (PreparedStatement statement = connection.prepareStatement(PAGE_AFTER)) {
            statement.setInt(1, rank);
            statement.setInt(2, rank);
            statement.setLong(3, anchor.id());
            statement.setInt(4, limit);
            return readRows(statement);
        }
    }

    /**
     * Anchors for every page boundary, plus the row count, from one ordered
     * scan.
     *
     * <p>The same device the actions table uses, and for the same reason: a
     * table model addresses rows by index and this is what turns an index into
     * a seek without {@code OFFSET}. It reads only the two ordering columns.
     */
    public Index buildIndex(int pageSize) throws SQLException {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive, got " + pageSize);
        }
        String sql = "SELECT " + RANK + ", te.id" + FROM
                + " ORDER BY " + RANK + " ASC, te.id ASC";
        List<Anchor> anchors = new ArrayList<>();
        long rowCount = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                rowCount++;
                if (rowCount % pageSize == 0) {
                    anchors.add(new Anchor(rows.getInt(1), rows.getLong(2)));
                }
            }
        }
        if (!anchors.isEmpty() && rowCount % pageSize == 0) {
            anchors.removeLast();
        }
        return new Index(rowCount, anchors);
    }

    /** The page after an anchor, in the same order. */
    public List<TestRow> pageAfter(Anchor anchor, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PAGE_AFTER)) {
            statement.setInt(1, anchor.rank());
            statement.setInt(2, anchor.rank());
            statement.setLong(3, anchor.id());
            statement.setInt(4, limit);
            return readRows(statement);
        }
    }

    /** A row's position in the tests ordering. */
    public record Anchor(int rank, long id) {}

    /** A row count and the anchors that address every page of it. */
    public record Index(long rowCount, List<Anchor> anchors) {
        public Index {
            anchors = List.copyOf(anchors);
        }

        /** The anchor for {@code pageIndex}, empty for the first page. */
        public Optional<Anchor> anchorFor(long pageIndex) {
            if (pageIndex <= 0) {
                return Optional.empty();
            }
            int previous = (int) (pageIndex - 1);
            return previous < anchors.size() ? Optional.of(anchors.get(previous)) : Optional.empty();
        }
    }

    public Optional<TestRow> test(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ONE)) {
            statement.setLong(1, id);
            List<TestRow> rows = readRows(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        }
    }

    /** Every attempt of one test, in run/shard/attempt order. */
    public List<TestAttemptRow> attempts(long testId) throws SQLException {
        List<TestAttemptRow> attempts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(ATTEMPTS)) {
            statement.setLong(1, testId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    attempts.add(new TestAttemptRow(
                            rows.getLong(1),
                            rows.getLong(2),
                            rows.getInt(3),
                            rows.getInt(4),
                            rows.getInt(5),
                            outcomeOf(rows.getString(6)),
                            rows.getInt(7) != 0,
                            number(rows, 8),
                            number(rows, 9),
                            integer(rows, 10),
                            text(rows, 11),
                            number(rows, 12)));
                }
            }
        }
        return attempts;
    }

    /**
     * Where a test's logs were written.
     *
     * <p>These are URIs into the output base, which the next build or a
     * {@code bazel clean} removes. They are recorded so the session can say
     * where the log was; the content is not captured, and the inspector says so
     * rather than offering a link that silently does nothing.
     */
    public List<TestLog> logs(long testId) throws SQLException {
        List<TestLog> logs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(LOGS)) {
            statement.setLong(1, testId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    logs.add(new TestLog(
                            text(rows, 1),
                            rows.getString(2),
                            text(rows, 3),
                            number(rows, 4)));
                }
            }
        }
        return logs;
    }

    /** How many tests ended in each status, for the overview. */
    public List<StatusCount> statusTally() throws SQLException {
        List<StatusCount> tally = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(STATUS_TALLY);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                tally.add(new StatusCount(outcomeOf(rows.getString(1)), rows.getLong(2)));
            }
        }
        return tally;
    }

    /** A recorded test log. */
    public record TestLog(
            Optional<String> name,
            String uri,
            Optional<String> summaryStatus,
            OptionalLong testAttemptId) {}

    /** A status and how many tests ended in it. */
    public record StatusCount(TestOutcome status, long tests) {}

    /**
     * The display rank of a status, matching the SQL {@code CASE}.
     *
     * <p>Duplicated between here and the query on purpose: the keyset anchor
     * has to compute the same rank the database ordered by, and deriving it
     * from the enum's ordinal would silently change the ordering the day a
     * constant is inserted.
     */
    private static int rankOf(TestOutcome status) {
        return switch (status) {
            case FAILED -> 0;
            case TIMEOUT -> 1;
            case FAILED_TO_BUILD -> 2;
            case REMOTE_FAILURE -> 3;
            case FLAKY -> 4;
            case INCOMPLETE -> 5;
            case NO_STATUS -> 6;
            case SKIPPED -> 7;
            case PASSED, UNKNOWN -> 8;
        };
    }

    private static List<TestRow> readRows(PreparedStatement statement) throws SQLException {
        List<TestRow> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new TestRow(
                        result.getLong(1),
                        result.getString(2),
                        text(result, 3),
                        outcomeOf(result.getString(4)),
                        integer(result, 5),
                        integer(result, 6),
                        integer(result, 7),
                        integer(result, 8),
                        result.getInt(9),
                        number(result, 10),
                        number(result, 11),
                        number(result, 12),
                        number(result, 13),
                        result.getLong(14),
                        result.getLong(15),
                        number(result, 16)));
            }
        }
        return rows;
    }

    /** Reads back this application's own enum name, not Bazel's vocabulary. */
    private static TestOutcome outcomeOf(String stored) {
        return TestOutcome.ofStoredName(stored);
    }

    private static Optional<String> text(ResultSet result, int index) throws SQLException {
        String value = result.getString(index);
        return value == null ? Optional.empty() : Optional.of(value);
    }

    private static OptionalLong number(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static OptionalInt integer(ResultSet result, int index) throws SQLException {
        int value = result.getInt(index);
        return result.wasNull() ? OptionalInt.empty() : OptionalInt.of(value);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
