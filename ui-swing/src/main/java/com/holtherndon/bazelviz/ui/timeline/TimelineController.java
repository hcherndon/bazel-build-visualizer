package com.holtherndon.bazelviz.ui.timeline;

import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
 * repaints. Fast navigation keeps one read in progress and one replaceable
 * latest request, and revision plus session-generation checks keep an older
 * result from reaching the view.
 */
public final class TimelineController {

    private static final Logger log = LoggerFactory.getLogger(TimelineController.class);

    private final TimelineView view = new TimelineView();
    /**
     * How often a live capture may rebuild the pyramid, and how often the
     * controller's own timer nudges one even if nothing tells it to.
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

    private final long liveRebuildIntervalMicros;

    private ExecutorService worker;
    /** One in-flight exact-window read and one replaceable latest request. */
    private LatestRequestQueue<WindowRequest> windowRequests;
    /**
     * The controller's own clock, independent of {@link #refreshLive}'s caller.
     *
     * <h2>Why this exists</h2>
     *
     * <p>{@link #refreshLive} used to be the only way a live session ever
     * rebuilt, and it only ran when something else called it — in practice,
     * {@code MainWindow}'s BES progress callback. On a quiet build, ticks are
     * sparse or stop arriving for a stretch (a long-running action between
     * progress events), and the timeline stalled along with them even though
     * time kept passing and the wall kept growing. The overview panel does not
     * have this problem because it drives its own
     * {@code ScheduledExecutorService} on a fixed delay rather than waiting to
     * be told; this is the same fix, here.
     *
     * <p>The two drivers share {@link #lastLiveRebuildMicros}'s throttle, so a
     * progress tick and a timer tick landing close together do not rebuild
     * twice — whichever runs first satisfies the interval and the other is a
     * no-op, exactly as two progress ticks would be today.
     */
    private ScheduledExecutorService ticker;
    private SessionSource source;
    /** Whether the open session is a running capture, for the in-flight band. */
    private boolean live;
    private long generation;
    /**
     * Monotonic identity of the most recently requested inspector details.
     *
     * <p>Detail reads share the timeline worker. A completed older read can
     * already be waiting on the EDT when a person selects another span. The
     * session generation alone cannot distinguish those two requests because
     * both belong to the same session.
     */
    private long detailRevision;
    private long lastLiveRebuildMicros;
    private volatile boolean rebuildInFlight;
    /**
     * The grouping and sort the current model was built with. A window fetch
     * that finds the user has changed either rebuilds the whole model instead:
     * the window's lane keys and the model's lane list must come from the same
     * grouping or every span lands in the "no current lane" row.
     */
    private LaneGrouping.By builtGrouping;
    private LaneGrouping.SortBy builtSort;

    public TimelineController() {
        this(LIVE_REBUILD_INTERVAL_MICROS);
    }

    /**
     * @param liveRebuildIntervalMicros the throttle in {@link #refreshLive} and
     *     the controller's own timer's period, both at once — a test's hook to
     *     drive several ticks in a fraction of a second, the same reason the
     *     overview panel's constructor takes its interval as a parameter.
     */
    TimelineController(long liveRebuildIntervalMicros) {
        this.liveRebuildIntervalMicros = liveRebuildIntervalMicros;
        view.onViewportChanged(this::refreshWindow);
        view.onActionPicked(this::fetchDetails);
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

    /** Called when the user drags out a time range, or clears one. */
    public void onRangeChanged(RangeListener listener) {
        view.onRangeChanged(listener);
    }

    /** Clears the visible range and notifies its Actions-table subscriber. */
    public void clearRange() {
        view.clearRange();
    }

    /** Gives the view's side inspector the shared navigation vocabulary. */
    public void installEntityActions(EntityActions actions) {
        view.installEntityActions(actions);
    }

    /** Told when the timeline's selected time range changes. */
    @FunctionalInterface
    public interface RangeListener {
        void rangeChanged(java.util.OptionalLong fromMicros, java.util.OptionalLong toMicros);
    }

    /** The component to put in the Timeline card. */
    public TimelineView view() {
        return view;
    }

    /** Builds the model for a finished session, off the EDT. */
    public void openSession(SessionSource opened) {
        openSession(opened, false);
    }

    /**
     * Builds the model for a newly opened session, off the EDT.
     *
     * @param liveCapture true while this session is a capture still being
     *     appended to — what turns on the in-flight target band and the
     *     wall-clock right edge. The finished session that replaces the live
     *     one arrives through {@link #openSession(SessionSource)} and turns
     *     both off.
     */
    public void openSession(SessionSource opened, boolean liveCapture) {
        closeSession();
        this.source = opened;
        this.live = liveCapture;
        long wanted = ++generation;
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-timeline");
            thread.setDaemon(true);
            return thread;
        });
        worker = executor;
        windowRequests = new LatestRequestQueue<>();
        scheduleBuild(executor, opened, wanted, true);
        startTicker();
    }

    /**
     * Starts the controller's own clock, ticking at {@link #liveRebuildIntervalMicros}
     * for as long as this session is open. Every tick just calls {@link #refreshLive}
     * back on the EDT — the same call a BES progress tick makes — so a quiet
     * stretch of a build with no progress event still gets rebuilt, and the
     * shared throttle in {@link #refreshLive} is what keeps a progress tick and
     * a timer tick landing close together from doing the work twice.
     */
    private void startTicker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-timeline-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker = scheduler;
        long periodMillis = Math.max(1, liveRebuildIntervalMicros / 1_000);
        scheduler.scheduleWithFixedDelay(() -> SwingUtilities.invokeLater(this::refreshLive),
                periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Lets go of the session. */
    public void closeSession() {
        closeSessionAsync();
    }

    /** Detaches immediately and completes after this session's timeline reads have stopped. */
    public CompletionStage<Void> closeSessionAsync() {
        generation++;
        detailRevision++;
        ExecutorService executor = worker;
        worker = null;
        LatestRequestQueue<WindowRequest> requests = windowRequests;
        windowRequests = null;
        if (requests != null) {
            requests.close();
        }
        source = null;
        live = false;
        builtGrouping = null;
        builtSort = null;
        ScheduledExecutorService scheduler = ticker;
        ticker = null;
        view.showEmpty("No session is open.");
        if (executor == null && scheduler == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ViewClose.runAsync("bbv-timeline-close", () -> {
            if (executor != null) {
                executor.shutdownNow();
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            try {
                if (executor != null) {
                    executor.awaitTermination(5, TimeUnit.SECONDS);
                }
                if (scheduler != null) {
                    scheduler.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
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
        if (now - lastLiveRebuildMicros < liveRebuildIntervalMicros) {
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
        scheduleBuild(executor, open, generation, false);
    }

    /**
     * Schedules one full model build on the worker.
     *
     * <p>The grouping and sort are read from the view here, on the EDT, and
     * carried into the worker as values — the worker never touches a Swing
     * component. They are remembered as what the model was built with, so
     * {@link #refreshWindow} can tell a plain pan (window refetch) from a
     * regroup (full rebuild).
     *
     * @param initial whether a failure should blank the view; a failed live
     *     refresh keeps showing the last good model instead
     */
    private void scheduleBuild(
            ExecutorService executor, SessionSource opened, long wanted, boolean initial) {
        LaneGrouping.By by = view.grouping();
        LaneGrouping.SortBy sortBy = view.sortBy();
        boolean liveNow = live;
        builtGrouping = by;
        builtSort = sortBy;
        executor.execute(() -> {
            Built built;
            try {
                built = build(opened, by, sortBy, liveNow);
            } catch (RuntimeException | SQLException failure) {
                if (initial) {
                    log.debug("no timeline for this session", failure);
                    SwingUtilities.invokeLater(() -> view.showEmpty(
                            "This session's timeline could not be built."));
                } else {
                    log.debug("live timeline refresh failed", failure);
                }
                return;
            } finally {
                rebuildInFlight = false;
            }
            SwingUtilities.invokeLater(() -> {
                if (wanted != generation) {
                    return;
                }
                if (built.model == null) {
                    if (initial) {
                        view.showEmpty(built.why);
                    }
                } else {
                    view.setModel(built.model);
                    refreshWindow();
                }
            });
        });
    }

    /**
     * Fetches the exact spans for whatever range the view is showing — or,
     * when the user has changed the grouping since the model was built,
     * rebuilds the whole model first, because a window keyed by one grouping
     * painted against lanes from another puts every span in no lane at all.
     */
    private void refreshWindow() {
        ExecutorService executor = worker;
        LatestRequestQueue<WindowRequest> requests = windowRequests;
        SessionSource open = source;
        if (executor == null || requests == null || open == null) {
            return;
        }
        if (builtGrouping != view.grouping() || builtSort != view.sortBy()) {
            scheduleBuild(executor, open, generation, false);
            return;
        }
        var range = view.visibleRange();
        if (range.isEmpty()) {
            return;
        }
        long from = range.get()[0];
        long to = range.get()[1];
        LaneGrouping.By by = view.grouping();
        long wanted = generation;
        WindowRequest request = new WindowRequest(open, from, to, by, wanted);
        if (requests.offer(request)) {
            scheduleWindowRead(executor, requests);
        }
    }

    private void scheduleWindowRead(
            ExecutorService executor, LatestRequestQueue<WindowRequest> requests) {
        try {
            executor.execute(() -> readLatestWindow(executor, requests));
        } catch (RejectedExecutionException stopped) {
            requests.close();
        }
    }

    /**
     * Reads one request, then yields the single worker before scheduling the
     * latest replacement. This bounds gesture work without starving an action
     * detail request or a model rebuild already queued on the same worker.
     */
    private void readLatestWindow(
            ExecutorService executor, LatestRequestQueue<WindowRequest> requests) {
        LatestRequestQueue.Entry<WindowRequest> entry = requests.take();
        if (entry == null) {
            return;
        }
        WindowRequest request = entry.value();
        try {
            SpanWindow built = spansIn(
                    request.source(), request.from(), request.to(), request.grouping());
            SwingUtilities.invokeLater(() -> {
                if (request.sessionGeneration() == generation
                        && requests == windowRequests
                        && requests.isLatest(entry.revision())) {
                    view.setWindow(built);
                }
            });
        } catch (RuntimeException failure) {
            log.debug("fetching timeline spans", failure);
        } finally {
            if (requests.finish()) {
                scheduleWindowRead(executor, requests);
            }
        }
    }

    private record WindowRequest(
            SessionSource source,
            long from,
            long to,
            LaneGrouping.By grouping,
            long sessionGeneration) {}

    /**
     * A bounded latest-wins handoff: one value may be running and one may be
     * waiting. A newer waiting value replaces the older one rather than
     * growing the executor's queue with stale viewport reads.
     */
    static final class LatestRequestQueue<T> {

        record Entry<T>(long revision, T value) {}

        private Entry<T> pending;
        private long latestRevision;
        private boolean running;
        private boolean closed;

        /** Returns true when the caller must schedule the reader. */
        synchronized boolean offer(T value) {
            if (closed) {
                return false;
            }
            pending = new Entry<>(++latestRevision, value);
            if (running) {
                return false;
            }
            running = true;
            return true;
        }

        synchronized Entry<T> take() {
            if (closed) {
                running = false;
                return null;
            }
            Entry<T> value = pending;
            pending = null;
            if (value == null) {
                running = false;
            }
            return value;
        }

        /** Returns true when a replacement must be scheduled. */
        synchronized boolean finish() {
            if (closed || pending == null) {
                running = false;
                return false;
            }
            return true;
        }

        synchronized boolean isLatest(long revision) {
            return !closed && revision == latestRevision;
        }

        synchronized int pendingCount() {
            return pending == null ? 0 : 1;
        }

        synchronized void close() {
            closed = true;
            pending = null;
            running = false;
        }
    }

    /**
     * Fetches one clicked action's details for the side inspector, off the
     * EDT, and hands the view a {@link SpanDetails} — plain strings and refs,
     * nothing that can block.
     */
    void fetchDetails(long actionId) {
        ExecutorService executor = worker;
        SessionSource open = source;
        if (executor == null || open == null) {
            return;
        }
        long wanted = generation;
        long wantedDetail = ++detailRevision;
        try {
            executor.execute(() -> {
                SpanDetails details;
                try (var reader = open.openEntityReader()) {
                    Optional<ActionRow> row = reader.action(actionId);
                    if (row.isEmpty()) {
                        details = new SpanDetails(
                                "Action " + actionId,
                                List.of("This action is no longer in the session."),
                                List.of());
                    } else {
                        details = detailsOf(row.get());
                    }
                } catch (Exception failure) {
                    log.debug("fetching action details for the timeline inspector", failure);
                    return;
                }
                SpanDetails toShow = details;
                SwingUtilities.invokeLater(() -> {
                    if (wanted == generation && wantedDetail == detailRevision) {
                        view.showInspector(toShow);
                    }
                });
            });
        } catch (RejectedExecutionException stopped) {
            // Session close won the race. The revision was still advanced, so
            // an older detail callback already waiting on the EDT stays stale.
        }
    }

    /**
     * One action as the inspector shows it: every fact something reported,
     * and no line at all for a fact nothing did — a missing duration is a
     * missing line, never a zero.
     */
    static SpanDetails detailsOf(ActionRow row) {
        List<String> lines = new ArrayList<>();
        lines.add("Action " + row.id()
                + row.mnemonic().map(m -> " (" + m + ")").orElse("")
                + " — " + row.outcome());
        lines.add("Primary output: " + row.primaryOutput());
        row.label().ifPresent(label -> lines.add("Target: " + label));
        if (row.durationMicros().isPresent()) {
            lines.add(String.format(java.util.Locale.ROOT,
                    "Duration: %.3f s", row.durationMicros().getAsLong() / 1_000_000.0));
        } else {
            row.durationUnknownReason().ifPresent(
                    why -> lines.add("Duration unknown: " + why));
        }
        row.execution().runner().ifPresent(runner -> lines.add("Ran via: " + runner));
        row.execution().cacheHit().ifPresent(hit ->
                lines.add(hit ? "Cache hit" : "Executed (cache miss)"));
        row.spawnExitCode().ifPresent(code -> lines.add("Spawn exit code: " + code));
        row.failureCategory().ifPresent(category -> lines.add("Failure: " + category));
        row.failureMessage().ifPresent(message -> lines.add(message));

        List<EntityRef> refs = new ArrayList<>();
        refs.add(new EntityRef.ActionId(row.id()));
        row.label().ifPresent(label -> refs.add(new EntityRef.TargetLabel(label)));
        row.bepEventId().ifPresent(eventId -> refs.add(new EntityRef.EventId(eventId)));

        String title = row.label().orElse(row.primaryOutput());
        return new SpanDetails(title, lines, refs);
    }

    // ------------------------------------------------------------- the queries

    private record Built(TimelineModel model, String why) {}

    private static Built build(
            SessionSource opened, LaneGrouping.By by, LaneGrouping.SortBy sortBy, boolean live)
            throws SQLException {
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
            List<TimelineModel.Lane> lanes = lanes(connection, by);
            return new Built(new TimelineModel(
                    index,
                    LaneGrouping.sort(lanes, sortBy, Map.of()),
                    List.of(),
                    spans.categoryNames(),
                    spans.skippedForNoTime(),
                    count(connection, "SELECT count(*) FROM action_attempts"
                            + " WHERE cache_hit IS NOT NULL"),
                    count(connection, "SELECT count(*) FROM action_attempts"
                            + " WHERE runner IS NOT NULL AND runner <> ''"),
                    live ? liveBand(connection) : TimelineModel.LiveBand.EMPTY), null);
        }
    }

    /** In-flight means configured, and nothing completed or aborted it yet. */
    private static final String IN_FLIGHT_WHERE =
            " WHERE t.outcome = 'CONFIGURED'"
                    + " AND NOT EXISTS (SELECT 1 FROM configured_targets ct"
                    + "   WHERE ct.target_id = t.id"
                    + "   AND ct.outcome IN ('BUILT', 'FAILED', 'ABORTED'))";

    /**
     * What is in flight right now, for the live band.
     *
     * <p>BEP target events carry no timestamp of their own, so a target's
     * position is the wall-clock instant its {@code TargetConfigured} event
     * was received — {@code bep_events.receive_micros} through the target's
     * {@code bep_event_id}, which {@code EntityWriter} already records. The
     * events layer captured the signal; this only reads it. A target whose
     * event has no receive time is counted, stated, and drawn nowhere.
     */
    private static TimelineModel.LiveBand liveBand(Connection connection) throws SQLException {
        long total = 0;
        long withoutTime = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*), coalesce(sum(CASE WHEN e.receive_micros IS NULL"
                                + " THEN 1 ELSE 0 END), 0)"
                                + " FROM targets t"
                                + " LEFT JOIN bep_events e ON e.id = t.bep_event_id"
                                + IN_FLIGHT_WHERE);
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                total = rows.getLong(1);
                withoutTime = rows.getLong(2);
            }
        }
        List<TimelineModel.LiveBand.InFlight> inFlight = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT l.value, e.receive_micros"
                                + " FROM targets t"
                                + " JOIN labels l ON l.id = t.label_id"
                                + " JOIN bep_events e ON e.id = t.bep_event_id"
                                + IN_FLIGHT_WHERE
                                + " AND e.receive_micros IS NOT NULL"
                                + " ORDER BY e.receive_micros"
                                + " LIMIT " + SpanWindow.MAX_SPANS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                inFlight.add(new TimelineModel.LiveBand.InFlight(
                        rows.getString(1), rows.getLong(2)));
            }
        }
        return new TimelineModel.LiveBand(true, inFlight, total, withoutTime);
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

    /**
     * The SQL expression producing a span's lane key under one grouping —
     * the same value {@link #lanes} groups by, so the window's keys join the
     * model's lanes exactly. This join is the zoom-shuffle fix: the painter
     * places a span by this key, never by the span's index in the fetch.
     *
     * <p>The attempt-based groupings (runner, cache) key an action by its one
     * attached attempt; an action with none or several honestly keys to
     * 'unknown', the same lane {@link #lanes} gives work nothing reported on.
     */
    private static String laneKeyExpression(LaneGrouping.By by) {
        String attemptCount =
                "(SELECT count(*) FROM action_attempts t WHERE t.action_id = a.id)";
        return switch (by) {
            case MNEMONIC -> "coalesce("
                    + "(SELECT m.value FROM mnemonics m WHERE m.id = a.mnemonic_id), 'unknown')";
            case TARGET -> "coalesce("
                    + "(SELECT l.value FROM labels l WHERE l.id = a.label_id), 'unknown')";
            case PACKAGE -> "coalesce((SELECT substr(l.value, 1,"
                    + " CASE WHEN instr(l.value, ':') > 0 THEN instr(l.value, ':') - 1"
                    + " ELSE length(l.value) END)"
                    + " FROM labels l WHERE l.id = a.label_id), 'unknown')";
            case EXECUTION_PLATFORM -> "coalesce((SELECT d.execution_platform"
                    + " FROM declared_actions d WHERE d.action_id = a.id), 'unknown')";
            case RUNNER, THREAD -> "CASE WHEN " + attemptCount + " = 1"
                    + " THEN coalesce((SELECT t.runner FROM action_attempts t"
                    + "   WHERE t.action_id = a.id), 'unknown')"
                    + " ELSE 'unknown' END";
            case CACHE_RESULT -> "CASE WHEN " + attemptCount + " = 1"
                    + " THEN coalesce((SELECT CASE WHEN t.cache_hit = 1 THEN 'cache hit'"
                    + "   WHEN t.cache_hit = 0 THEN 'executed' END"
                    + "   FROM action_attempts t WHERE t.action_id = a.id), 'unknown')"
                    + " ELSE 'unknown' END";
            case NONE -> "''";
        };
    }

    private static SpanWindow spansIn(
            SessionSource opened, long from, long to, LaneGrouping.By by) {
        try (Connection connection = opened.openTimelineConnection()) {
            SpanWindow.Builder builder = SpanWindow.builder(from, to);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.id, a.start_micros, a.end_micros, a.outcome, "
                            + laneKeyExpression(by) + " FROM actions a"
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
                        builder.add(rows.getLong(2), rows.getLong(3), flags,
                                rows.getLong(1), rows.getString(5));
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
