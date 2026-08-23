package com.holtherndon.bazelviz.ui.timeline;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Streams a session's spans out of SQLite for the timeline index.
 *
 * <h2>Which spans, and why not all of them</h2>
 *
 * <p>Plan 14.1 lists seven kinds of timeline entity. This streams the two that
 * carry a wall-clock window and a duration worth drawing: logical actions from
 * the build event stream, and execution attempts from the execution log.
 *
 * <p>Tests are not streamed as a third kind. A test's window is its attempts',
 * which are already here, and drawing both would count the same work twice in
 * the density — the bins are counts of spans, and a viewer cannot tell a
 * doubled bin from a busy one. Bazel phases are drawn as a separate band
 * because there are five of them and they are not spans of work.
 *
 * <h2>Replayable, and the same twice</h2>
 *
 * <p>{@link SpanSource} requires the feed to be identical on every call; the
 * index streams it once but the contract is not conditional on that. Each call
 * runs the same query against the same connection, so it is — provided nothing
 * writes in between, which for a finished session is guaranteed and for a live
 * one is the reason the index is rebuilt rather than updated in place.
 *
 * <h2>Spans without a start</h2>
 *
 * <p>Actions on Bazel 6.5.0 and 7.6.1 have no timestamps at all, and attempts
 * on 6.5.0 have a duration and no position. Those rows are skipped, and
 * {@link #skippedForNoTime()} counts them: a timeline drawn from a session
 * where most actions have no time is nearly empty, and the count is what turns
 * that from a puzzle into a sentence.
 */
public final class SessionSpanSource implements SpanSource {

    /**
     * Actions with both a start and an end.
     *
     * <p>The mnemonic id doubles as the category index. It is already a small
     * dense integer because Phase 3 interns mnemonics, which is exactly what
     * the index wants and the reason no separate mapping exists.
     */
    private static final String ACTIONS =
            "SELECT a.start_micros, a.end_micros, coalesce(a.mnemonic_id, 0), a.outcome"
                    + " FROM actions a"
                    + " WHERE a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL";

    /**
     * Attempts with a start and a duration.
     *
     * <p>Joined to the action only for its mnemonic; an attempt attached to no
     * action still has one of its own.
     */
    private static final String ATTEMPTS =
            "SELECT t.start_micros, t.start_micros + t.total_micros,"
                    + " coalesce(t.mnemonic_id, 0), t.cache_hit, t.runner, t.exit_code,"
                    + " t.input_bytes"
                    + " FROM action_attempts t"
                    + " WHERE t.start_micros IS NOT NULL AND t.total_micros IS NOT NULL";

    private static final String COUNT_ACTIONS_WITHOUT_TIME =
            "SELECT count(*) FROM actions WHERE start_micros IS NULL OR end_micros IS NULL";

    private static final String WALL =
            "SELECT min(t), max(t) FROM ("
                    + "  SELECT start_micros AS t FROM actions WHERE start_micros IS NOT NULL"
                    + "  UNION ALL"
                    + "  SELECT end_micros FROM actions WHERE end_micros IS NOT NULL"
                    + "  UNION ALL"
                    + "  SELECT start_micros FROM action_attempts WHERE start_micros IS NOT NULL"
                    + "  UNION ALL"
                    + "  SELECT start_micros + total_micros FROM action_attempts"
                    + "   WHERE start_micros IS NOT NULL AND total_micros IS NOT NULL)";

    /** Runner names that mean the work did not happen on this machine. */
    private static final List<String> REMOTE_RUNNERS =
            List.of("remote", "remote cache hit", "remote cache");

    private final Connection connection;
    private final boolean includeAttempts;
    private long spanCount = -1;
    private long skippedForNoTime;
    private final Map<Integer, String> categoryNames = new HashMap<>();

    /**
     * @param includeAttempts whether execution-log attempts are drawn alongside
     *     actions. Both is the truthful default — they are different
     *     measurements of overlapping work — and a viewer comparing density
     *     between two sessions, one with an execution log and one without,
     *     needs to know which they are looking at.
     */
    public SessionSpanSource(Connection connection, boolean includeAttempts) {
        this.connection = connection;
        this.includeAttempts = includeAttempts;
    }

    @Override
    public long spanCount() {
        if (spanCount < 0) {
            try {
                spanCount = count("SELECT count(*) FROM (" + ACTIONS + ")")
                        + (includeAttempts ? count("SELECT count(*) FROM (" + ATTEMPTS + ")") : 0);
                skippedForNoTime = count(COUNT_ACTIONS_WITHOUT_TIME);
            } catch (SQLException failure) {
                throw new IllegalStateException("counting timeline spans", failure);
            }
        }
        return spanCount;
    }

    @Override
    public void forEachSpan(SpanConsumer consumer) {
        try {
            streamActions(consumer);
            if (includeAttempts) {
                streamAttempts(consumer);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("streaming timeline spans", failure);
        }
    }

    private void streamActions(SpanConsumer consumer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ACTIONS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int flags = "FAILED".equals(rows.getString(4)) ? FLAG_FAILED : 0;
                // An action says nothing about caching or where it ran; only an
                // attempt does. Leaving the KNOWN flags off is what stops a
                // cache-coloured timeline painting every action as a miss.
                consumer.accept(rows.getLong(1), rows.getLong(2), rows.getInt(3),
                        flags, BYTES_UNKNOWN);
            }
        }
    }

    private void streamAttempts(SpanConsumer consumer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ATTEMPTS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int flags = 0;
                int cacheHit = rows.getInt(4);
                if (!rows.wasNull()) {
                    flags |= FLAG_CACHE_KNOWN;
                    if (cacheHit != 0) {
                        flags |= FLAG_CACHE_HIT;
                    }
                }
                String runner = rows.getString(5);
                if (runner != null && !runner.isEmpty()) {
                    flags |= FLAG_RUNNER_KNOWN;
                    if (REMOTE_RUNNERS.contains(runner)) {
                        flags |= FLAG_REMOTE;
                    }
                }
                int exitCode = rows.getInt(6);
                if (!rows.wasNull() && exitCode != 0) {
                    flags |= FLAG_FAILED;
                }
                long bytes = rows.getLong(7);
                consumer.accept(rows.getLong(1), rows.getLong(2), rows.getInt(3), flags,
                        rows.wasNull() ? BYTES_UNKNOWN : bytes);
            }
        }
    }

    /**
     * The wall this session covers, or empty when nothing has a timestamp.
     *
     * <p>Empty is the honest answer for a Bazel 6.5.0 or 7.6.1 capture with no
     * execution log: those versions report no action timestamps at all
     * (finding A4), so there is no time axis to draw and the view says that
     * rather than drawing an empty one.
     */
    public Wall wall() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(WALL);
                ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return new Wall(OptionalLong.empty(), OptionalLong.empty());
            }
            long from = rows.getLong(1);
            boolean fromNull = rows.wasNull();
            long to = rows.getLong(2);
            boolean toNull = rows.wasNull();
            return new Wall(
                    fromNull ? OptionalLong.empty() : OptionalLong.of(from),
                    toNull ? OptionalLong.empty() : OptionalLong.of(to));
        }
    }

    /**
     * Actions with no usable timestamps, which the timeline cannot draw.
     *
     * <p>Only meaningful after {@link #spanCount()}.
     */
    public long skippedForNoTime() {
        return skippedForNoTime;
    }

    /** Mnemonic names by category index, for labelling a uniform bin. */
    public Map<Integer, String> categoryNames() throws SQLException {
        if (categoryNames.isEmpty()) {
            try (PreparedStatement statement =
                            connection.prepareStatement("SELECT id, value FROM mnemonics");
                    ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    categoryNames.put(rows.getInt(1), rows.getString(2));
                }
            }
        }
        return Map.copyOf(categoryNames);
    }

    private long count(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /**
     * The session's time extent.
     *
     * @param fromMicros absent when nothing in the session has a timestamp
     */
    public record Wall(OptionalLong fromMicros, OptionalLong toMicros) {

        /** True when there is a time axis to draw. */
        public boolean isDrawable() {
            return fromMicros.isPresent() && toMicros.isPresent()
                    && toMicros.getAsLong() > fromMicros.getAsLong();
        }

        /** Why there is no timeline, when there is none. */
        public String whyNotDrawable() {
            if (fromMicros.isEmpty()) {
                return "Nothing in this session has a timestamp. Bazel 6.5.0 and 7.6.1"
                        + " report no action times at all, and no execution log has been"
                        + " imported to supply them.";
            }
            return "Everything in this session happened at the same instant, so there is"
                    + " no span of time to draw.";
        }

        /** The extent, for the index. */
        public List<Long> bounds() {
            List<Long> out = new ArrayList<>(2);
            fromMicros.ifPresent(out::add);
            toMicros.ifPresent(out::add);
            return out;
        }
    }
}
