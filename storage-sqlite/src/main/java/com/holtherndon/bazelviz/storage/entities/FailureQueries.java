package com.holtherndon.bazelviz.storage.entities;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The failures table's read path: three different kinds of bad news, kept
 * apart.
 *
 * <h2>Why three queries and not one UNION</h2>
 *
 * <p>The three do not scale together. Failed actions and failed targets are
 * bounded by what actually broke — usually a handful. Aborted targets are
 * bounded by the size of the build: an interrupt during analysis produced
 * 12,000 of them, and under {@code --nokeep_going} a single failure aborts
 * every sibling. A union would page them interleaved, so the one row the user
 * needs would sit behind thousands of "not built" rows that all say the same
 * thing.
 *
 * <p>So the view asks for the real failures first, and asks separately for how
 * many targets were not attempted — which it summarizes as a count with the
 * option to list, rather than listing by default (finding 51).
 *
 * <h2>Where the failure text comes from</h2>
 *
 * <p>{@code failure_message} is Bazel's own text, stored verbatim and never
 * parsed: its wording changes between versions. For a compiler diagnostic there
 * is no structured text at all — a syntax error produces thirteen events and
 * zero structured diagnostics — and the message lives only in the progress
 * events, which {@link #progressOutputEvents} finds.
 */
public final class FailureQueries implements AutoCloseable {

    private static final String FAILED_ACTIONS =
            "SELECT a.id, a.primary_output, l.value, a.failure_category, a.failure_message,"
                    + " a.bep_event_id FROM actions a"
                    + " LEFT JOIN labels l ON l.id = a.label_id"
                    + " WHERE a.outcome = 'FAILED' AND a.id > ? ORDER BY a.id ASC LIMIT ?";

    private static final String COUNT_FAILED_ACTIONS =
            "SELECT COUNT(*) FROM actions WHERE outcome = 'FAILED'";

    private static final String FAILED_TARGETS =
            "SELECT ct.id, l.value, ct.failure_category, ct.failure_message, ct.bep_event_id"
                    + " FROM configured_targets ct"
                    + " JOIN targets t ON t.id = ct.target_id"
                    + " JOIN labels l ON l.id = t.label_id"
                    + " WHERE ct.outcome = 'FAILED' AND ct.id > ? ORDER BY ct.id ASC LIMIT ?";

    private static final String COUNT_FAILED_TARGETS =
            "SELECT COUNT(*) FROM configured_targets WHERE outcome = 'FAILED'";

    private static final String ABORTED =
            "SELECT ab.id, l.value, ab.id_kind, ab.reason, ab.description, ab.bep_event_id"
                    + " FROM aborted_events ab"
                    + " LEFT JOIN labels l ON l.id = ab.label_id"
                    + " WHERE ab.id > ? ORDER BY ab.id ASC LIMIT ?";

    private static final String COUNT_ABORTED = "SELECT COUNT(*) FROM aborted_events";

    private static final String ABORT_REASONS =
            "SELECT COALESCE(reason, 'UNKNOWN'), COUNT(*) FROM aborted_events"
                    + " GROUP BY reason ORDER BY COUNT(*) DESC";

    private static final String PROGRESS_WITH_STDERR =
            "SELECT p.bep_event_id, e.sequence, p.stderr_bytes, e.raw_segment, e.raw_offset,"
                    + " e.raw_length FROM progress_output p"
                    + " JOIN bep_events e ON e.id = p.bep_event_id"
                    + " WHERE p.stderr_bytes > 0 ORDER BY p.ordinal ASC LIMIT ?";

    private final Connection connection;

    public FailureQueries(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public long failedActionCount() throws SQLException {
        return scalar(COUNT_FAILED_ACTIONS);
    }

    public long failedTargetCount() throws SQLException {
        return scalar(COUNT_FAILED_TARGETS);
    }

    /** How many targets were named by an abort. Can reach five figures. */
    public long abortedCount() throws SQLException {
        return scalar(COUNT_ABORTED);
    }

    public List<FailureRow> failedActions(OptionalLong afterId, int limit) throws SQLException {
        List<FailureRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(FAILED_ACTIONS)) {
            statement.setLong(1, afterId.orElse(0L));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String label = result.getString(3);
                    rows.add(new FailureRow(
                            FailureRow.Kind.ACTION,
                            result.getLong(1),
                            // An action with no label -- the workspace-status
                            // action on three of the four versions -- is named
                            // by its output rather than by a blank.
                            label != null ? label : result.getString(2),
                            text(result, 4),
                            text(result, 5),
                            number(result, 6)));
                }
            }
        }
        return rows;
    }

    public List<FailureRow> failedTargets(OptionalLong afterId, int limit) throws SQLException {
        List<FailureRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(FAILED_TARGETS)) {
            statement.setLong(1, afterId.orElse(0L));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new FailureRow(
                            FailureRow.Kind.TARGET,
                            result.getLong(1),
                            result.getString(2),
                            text(result, 3),
                            text(result, 4),
                            number(result, 5)));
                }
            }
        }
        return rows;
    }

    /**
     * Targets an abort named, paged.
     *
     * <p>Listed only on request. The reason is that the count is the useful
     * fact and the list usually is not: under {@code --nokeep_going} every
     * sibling of one broken target appears here saying {@code INCOMPLETE},
     * which is a statement about the sibling and not about them.
     */
    public List<FailureRow> abortedTargets(OptionalLong afterId, int limit) throws SQLException {
        List<FailureRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(ABORTED)) {
            statement.setLong(1, afterId.orElse(0L));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String label = result.getString(2);
                    rows.add(new FailureRow(
                            FailureRow.Kind.NOT_BUILT,
                            result.getLong(1),
                            // Patterns and other id kinds abort too and carry no
                            // label; the row still counts, so it is named by
                            // what it was.
                            label != null ? label : "(" + result.getString(3) + ")",
                            text(result, 4),
                            text(result, 5),
                            number(result, 6)));
                }
            }
        }
        return rows;
    }

    /** Abort reasons and their counts, which is what the view shows by default. */
    public List<ReasonCount> abortReasons() throws SQLException {
        List<ReasonCount> reasons = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(ABORT_REASONS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                reasons.add(new ReasonCount(rows.getString(1), rows.getLong(2)));
            }
        }
        return reasons;
    }

    /**
     * The progress events carrying console error output, newest work last.
     *
     * <p>Only the locations: the bytes stay in the journal and are read for the
     * one the user opens. A build that fails to parse produces no structured
     * diagnostic anywhere else, so without this the failures view would have
     * nothing to show for the most common kind of failure there is.
     */
    public List<ProgressRef> progressOutputEvents(int limit) throws SQLException {
        List<ProgressRef> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PROGRESS_WITH_STDERR)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    refs.add(new ProgressRef(
                            rows.getLong(1),
                            rows.getLong(2),
                            rows.getInt(3),
                            rows.getInt(4),
                            rows.getLong(5),
                            rows.getInt(6)));
                }
            }
        }
        return refs;
    }

    /** An abort reason and how many events gave it. */
    public record ReasonCount(String reason, long events) {}

    /** Where a progress event's bytes are in the journal. */
    public record ProgressRef(
            long bepEventId,
            long sequence,
            int stderrBytes,
            int rawSegment,
            long rawOffset,
            int rawLength) {}

    private long scalar(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    private static Optional<String> text(ResultSet result, int index) throws SQLException {
        String value = result.getString(index);
        return value == null ? Optional.empty() : Optional.of(value);
    }

    private static OptionalLong number(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
