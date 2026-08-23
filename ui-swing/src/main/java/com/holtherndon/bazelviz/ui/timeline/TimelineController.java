package com.holtherndon.bazelviz.ui.timeline;

import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the timeline supplied with data, and keeps that work off the EDT.
 *
 * <h2>The split that makes the exit criterion true</h2>
 *
 * <p>{@link TimelineView} paints; this queries. The view holds a
 * {@link TimelineModel} and a {@link SpanWindow} and has no way to reach a
 * database — so "no SQLite access occurs during painting" is a property of the
 * type graph rather than a rule someone has to keep in mind while editing a
 * paint method.
 *
 * <p>Everything here runs on one worker thread. A viewport change schedules a
 * fetch; the fetch hands its result to the EDT; the EDT assigns one field and
 * repaints. A user panning fast queues several fetches and sees the last one,
 * which is why each carries the generation it was started for.
 */
public final class TimelineController {

    private static final Logger log = LoggerFactory.getLogger(TimelineController.class);

    private final TimelineView view = new TimelineView();
    /**
     * How often a live capture may rebuild the pyramid.
     *
     * <p>Capture progress arrives per batch, which on a fast build is many
     * times a second, and a rebuild streams every span in the session. At Tier
     * 2 that is 170 ms of work — so rebuilding per tick would spend more time
     * indexing than capturing and would keep the exit criterion "Tier 2 remains
     * interactive" from being true in the one situation it matters most.
     *
     * <p>Two seconds is the overview panel's interval, for the same reason and
     * with the same consequence: the timeline is up to two seconds behind a
     * running build, and every number on it comes from one consistent read.
     */
    private static final long LIVE_REBUILD_INTERVAL_MICROS = 2_000_000;

    private ExecutorService worker;
    private SessionSource source;
    private long generation;
    private long lastLiveRebuildMicros;
    private volatile boolean rebuildInFlight;

    public TimelineController() {
        view.onViewportChanged(this::refreshWindow);
    }

    /** Called with an action id when the user picks a span on the timeline. */
    public void onSelection(java.util.function.LongConsumer handler) {
        view.onSelection(handler);
    }

    /**
     * Selects an action from elsewhere, without moving the view.
     *
     * <p>Plan 17.8's selection synchronisation, and the half that is easy to
     * get wrong: a table selection must highlight the span and must not scroll
     * the timeline to it. Someone comparing a row against the shape of the
     * build did not ask to be moved.
     */
    public void select(long actionId) {
        view.select(actionId);
    }

    /**
     * The time range the user dragged out, when there is one.
     *
     * <p>Plan 14.5's "filter selected time range": the actions table reads this
     * and shows only what ran inside it.
     */
    public java.util.Optional<long[]> selectedRange() {
        return view.viewport()
                .filter(TimelineViewport::hasRange)
                .map(v -> new long[] {
                        v.rangeFromMicros().getAsLong(), v.rangeToMicros().getAsLong()});
    }

    /** The component to put in the Timeline card. */
    public TimelineView view() {
        return view;
    }

    /** Builds the model for a newly opened session, off the EDT. */
    public void openSession(SessionSource opened) {
        closeSession();
        this.source = opened;
        long wanted = ++generation;
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-timeline");
            thread.setDaemon(true);
            return thread;
        });
        worker = executor;
        executor.execute(() -> {
            Built built;
            try {
                built = build(opened);
            } catch (RuntimeException | SQLException failure) {
                log.debug("no timeline for this session", failure);
                SwingUtilities.invokeLater(() -> view.showEmpty(
                        "This session's timeline could not be built."));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (wanted != generation) {
                    return;
                }
                if (built.model == null) {
                    view.showEmpty(built.why);
                } else {
                    view.setModel(built.model);
                    refreshWindow();
                }
            });
        });
    }

    /** Lets go of the session. */
    public void closeSession() {
        generation++;
        ExecutorService executor = worker;
        worker = null;
        source = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        view.showEmpty("No session is open.");
    }

    /**
     * Rebuilds the model after a live capture appended to the session.
     *
     * <p>The viewport is not passed and not consulted: {@link TimelineView#setModel}
     * hands it to {@link TimelineViewport#withWall}, which moves a following
     * view and leaves a navigated one exactly where it is (plan 14.4).
     */
    public void refreshLive() {
        SessionSource open = source;
        if (open == null) {
            return;
        }
        long now = System.currentTimeMillis() * 1_000L;
        if (now - lastLiveRebuildMicros < LIVE_REBUILD_INTERVAL_MICROS) {
            return;
        }
        // One rebuild at a time. Without this a build whose ticks outpace the
        // rebuild queues them up and the worker never catches up.
        if (rebuildInFlight) {
            return;
        }
        lastLiveRebuildMicros = now;
        rebuildInFlight = true;
        openSessionKeepingView(open);
    }

    private void openSessionKeepingView(SessionSource open) {
        ExecutorService executor = worker;
        if (executor == null) {
            return;
        }
        long wanted = generation;
        executor.execute(() -> {
            Built built;
            try {
                built = build(open);
            } catch (RuntimeException | SQLException failure) {
                log.debug("live timeline refresh failed", failure);
                return;
            } finally {
                rebuildInFlight = false;
            }
            SwingUtilities.invokeLater(() -> {
                if (wanted == generation && built.model != null) {
                    view.setModel(built.model);
                    refreshWindow();
                }
            });
        });
    }

    /** Fetches the exact spans for whatever range the view is showing. */
    private void refreshWindow() {
        ExecutorService executor = worker;
        SessionSource open = source;
        if (executor == null || open == null) {
            return;
        }
        var range = view.visibleRange();
        if (range.isEmpty()) {
            return;
        }
        long from = range.get()[0];
        long to = range.get()[1];
        long wanted = generation;
        executor.execute(() -> {
            SpanWindow built;
            try (var reader = open.openEntityReader()) {
                built = spansIn(open, from, to);
            } catch (RuntimeException failure) {
                log.debug("fetching timeline spans", failure);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (wanted == generation) {
                    view.setWindow(built);
                }
            });
        });
    }

    // ------------------------------------------------------------- the queries

    private record Built(TimelineModel model, String why) {}

    private Built build(SessionSource opened) throws SQLException {
        try (Connection connection = opened.openTimelineConnection()) {
            SessionSpanSource spans = new SessionSpanSource(connection, true);
            SessionSpanSource.Wall wall = spans.wall();
            if (!wall.isDrawable()) {
                return new Built(null, wall.whyNotDrawable());
            }
            long from = wall.fromMicros().getAsLong();
            long to = wall.toMicros().getAsLong();
            spans.spanCount();

            TimelineLodIndex index = TimelineLodIndex.build(spans, from, to);
            List<TimelineModel.Lane> lanes = lanes(connection, view.grouping());
            return new Built(new TimelineModel(
                    index,
                    LaneGrouping.sort(lanes, view.sortBy(), Map.of()),
                    List.of(),
                    spans.categoryNames(),
                    spans.skippedForNoTime(),
                    count(connection, "SELECT count(*) FROM action_attempts"
                            + " WHERE cache_hit IS NOT NULL"),
                    count(connection, "SELECT count(*) FROM action_attempts"
                            + " WHERE runner IS NOT NULL AND runner <> ''")), null);
        }
    }

    /**
     * The lanes for one grouping.
     *
     * <p>Only the groupings a session can actually supply produce lanes; the
     * rest return a single row, and the view's combo already refuses to offer
     * them (see {@link LaneGrouping.By#unavailableReason}).
     */
    private static List<TimelineModel.Lane> lanes(Connection connection, LaneGrouping.By by)
            throws SQLException {
        String sql = switch (by) {
            case MNEMONIC -> "SELECT coalesce(m.value, 'unknown'), count(*),"
                    + " sum(a.end_micros - a.start_micros), min(a.start_micros),"
                    + " max(a.end_micros) FROM actions a"
                    + " LEFT JOIN mnemonics m ON m.id = a.mnemonic_id"
                    + " WHERE a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                    + " GROUP BY m.value";
            case RUNNER, THREAD -> "SELECT coalesce(t.runner, 'unknown'), count(*),"
                    + " sum(t.total_micros), min(t.start_micros),"
                    + " max(t.start_micros + t.total_micros) FROM action_attempts t"
                    + " WHERE t.start_micros IS NOT NULL AND t.total_micros IS NOT NULL"
                    + " GROUP BY t.runner";
            case CACHE_RESULT -> "SELECT CASE WHEN t.cache_hit = 1 THEN 'cache hit'"
                    + "   WHEN t.cache_hit = 0 THEN 'executed' ELSE 'unknown' END, count(*),"
                    + " sum(t.total_micros), min(t.start_micros),"
                    + " max(t.start_micros + t.total_micros) FROM action_attempts t"
                    + " WHERE t.start_micros IS NOT NULL AND t.total_micros IS NOT NULL"
                    + " GROUP BY 1";
            case PACKAGE -> "SELECT coalesce(substr(l.value, 1,"
                    + "   CASE WHEN instr(l.value, ':') > 0 THEN instr(l.value, ':') - 1"
                    + "        ELSE length(l.value) END), 'unknown'), count(*),"
                    + " sum(a.end_micros - a.start_micros), min(a.start_micros),"
                    + " max(a.end_micros) FROM actions a"
                    + " LEFT JOIN labels l ON l.id = a.label_id"
                    + " WHERE a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                    + " GROUP BY 1";
            case TARGET -> "SELECT coalesce(l.value, 'unknown'), count(*),"
                    + " sum(a.end_micros - a.start_micros), min(a.start_micros),"
                    + " max(a.end_micros) FROM actions a"
                    + " LEFT JOIN labels l ON l.id = a.label_id"
                    + " WHERE a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                    + " GROUP BY l.value";
            case EXECUTION_PLATFORM -> "SELECT coalesce(d.execution_platform, 'unknown'),"
                    + " count(*), sum(a.end_micros - a.start_micros), min(a.start_micros),"
                    + " max(a.end_micros) FROM actions a"
                    + " LEFT JOIN declared_actions d ON d.action_id = a.id"
                    + " WHERE a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                    + " GROUP BY 1";
            case NONE -> null;
        };
        if (sql == null) {
            return List.of(new TimelineModel.Lane("All actions", "", 0, 0, 0, 0));
        }
        List<TimelineModel.Lane> lanes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(1);
                lanes.add(new TimelineModel.Lane(
                        name, name, rows.getLong(2), rows.getLong(3),
                        rows.getLong(4), rows.getLong(5)));
            }
        }
        return lanes.isEmpty()
                ? List.of(new TimelineModel.Lane("All actions", "", 0, 0, 0, 0)) : lanes;
    }

    private SpanWindow spansIn(SessionSource opened, long from, long to) {
        try (Connection connection = opened.openTimelineConnection()) {
            SpanWindow.Builder builder = SpanWindow.builder(from, to);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.id, a.start_micros, a.end_micros, a.outcome FROM actions a"
                            + " WHERE a.start_micros IS NOT NULL AND a.end_micros IS NOT NULL"
                            + "   AND a.end_micros >= ? AND a.start_micros <= ?"
                            + " ORDER BY a.start_micros"
                            + " LIMIT " + (SpanWindow.MAX_SPANS + 1))) {
                statement.setLong(1, from);
                statement.setLong(2, to);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        int flags = "FAILED".equals(rows.getString(4))
                                ? SpanSource.FLAG_FAILED : 0;
                        builder.add(rows.getLong(2), rows.getLong(3), flags, rows.getLong(1));
                    }
                }
            }
            return builder.build();
        } catch (SQLException failure) {
            throw new IllegalStateException("fetching timeline spans", failure);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
