package com.holtherndon.bazelviz.storage.metrics;

import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import com.holtherndon.bazelviz.analysis.MetricSeries;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The metrics read path: streams the session once and hands every row to the
 * aggregators in {@code analysis-core}.
 *
 * <h2>Which timing source, decided by measuring</h2>
 *
 * <p>Two sources report how long an action took, and they disagree about more
 * than the number. The build event stream publishes no action timestamps at all
 * on Bazel 6.5.0 and 7.6.1; on 8.4.1 it publishes {@code endTime == startTime}
 * for every action, a five-second sleep included; on 9.2.0 roughly a third of
 * action events carry none. The execution log reports a real elapsed time per
 * subprocess, but only for the actions that spawned one — a minority, because
 * most actions run inside the Bazel server and never do.
 *
 * <p>So {@link #bestDurationSource()} counts what each source actually covers
 * in <em>this</em> session and picks the larger, and every metric built from a
 * duration carries which one it used. Choosing from the Bazel version would be
 * wrong for a session captured with different flags, and defaulting to the
 * event stream would silently produce a build of instantaneous actions on
 * 8.4.1.
 *
 * <h2>One pass, one row per action</h2>
 *
 * <p>{@link #collect} answers the whole dashboard from a single scan.
 * Aggregating in SQL would be six queries with six {@code GROUP BY} clauses and
 * the interesting decisions — what "the runner" of an action with two
 * differently-run spawns is, whether an unrecorded cache state counts as a miss
 * — buried in {@code CASE} expressions. Instead one query returns one row per
 * action with everything those decisions need, and the folding happens in Java
 * where it can be read and tested.
 *
 * <p>There is deliberately no second public entry point that answers part of
 * the same question. A {@code coverage()} or {@code aggregate()} that ran its
 * own scan would be a second definition of the same numbers, free to drift from
 * this one.
 *
 * <h2>Threading</h2>
 *
 * <p>Wraps one JDBC connection, so one instance belongs to one thread, and
 * every method blocks. None of them may be called on the Swing EDT (rule 8).
 */
public final class MetricQueries implements AutoCloseable {

    /** Groups an aggregation returns before the rest are summarised away. */
    public static final int DEFAULT_GROUP_LIMIT = 40;

    private static final String ACTION_ROWS_HEAD =
            "SELECT act.id, m.value, l.value, act.outcome, ";

    private static final String ACTION_ROWS_TAIL =
            " MIN(att.runner), MAX(att.runner), COUNT(att.runner), COUNT(att.id),"
                    + " SUM(CASE WHEN att.cache_hit = 1 THEN 1 ELSE 0 END),"
                    + " SUM(CASE WHEN att.cache_hit = 0 THEN 1 ELSE 0 END),"
                    + " COUNT(att.cache_hit), SUM(att.input_bytes), COUNT(att.input_bytes)"
                    + " FROM actions act"
                    + " LEFT JOIN mnemonics m ON m.id = act.mnemonic_id"
                    + " LEFT JOIN labels l ON l.id = act.label_id"
                    + " LEFT JOIN action_attempts att ON att.action_id = act.id"
                    + " GROUP BY act.id";

    /**
     * Wall duration from the build event stream, and only when the pair is
     * usable.
     *
     * <p>{@code end > start} rather than {@code end >= start}: on Bazel 8.4.1
     * every action reports the two as equal, and calling that a duration of
     * zero would fill a distribution with actions that appear to have taken no
     * time. Those count as unavailable, which is what they are — while the span
     * is still handed to the sweep, which reports them as instantaneous rather
     * than losing them.
     */
    private static final String BEP_DURATION =
            "CASE WHEN act.start_micros IS NOT NULL AND act.end_micros IS NOT NULL"
                    + " AND act.end_micros > act.start_micros"
                    + " THEN act.end_micros - act.start_micros END,"
                    + " act.start_micros, act.end_micros,";

    /**
     * Subprocess time from the execution log, summed over the action's
     * attempts, with the span its attempts occupied.
     *
     * <p>The sum is not the same quantity as a wall duration and is
     * deliberately not named as if it were: an action that ran twice under the
     * dynamic strategy consumed two subprocesses' worth of time and held the
     * wall clock for less. {@link MetricSeries#name()} carries the distinction
     * onto the screen.
     */
    private static final String ATTEMPT_DURATION =
            "SUM(att.total_micros), MIN(att.start_micros),"
                    + " MAX(att.start_micros + att.total_micros),";

    private final Connection connection;
    private final Optional<GraphQueries> graph;

    /** Reads the metrics that need no dependency graph. */
    public MetricQueries(Connection connection) {
        this(connection, null);
    }

    /**
     * Reads every metric, the derived critical path included.
     *
     * @param graph the session's graph reader, or null when no graph was
     *     imported; it is not closed by {@link #close()}, because it was opened
     *     by somebody else and is shared
     */
    public MetricQueries(Connection connection, GraphQueries graph) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.graph = Optional.ofNullable(graph);
    }

    /**
     * Which duration source covers more of this session, measured rather than
     * assumed.
     *
     * <p>Ties go to the execution log, because its number is an elapsed time
     * for a subprocess that really ran, while the event stream's is whatever
     * Bazel chose to stamp on the event.
     */
    public CriticalPath.DurationSource bestDurationSource() throws SQLException {
        String sql = "SELECT (SELECT COUNT(*) FROM actions WHERE start_micros IS NOT NULL"
                + "   AND end_micros IS NOT NULL AND end_micros > start_micros),"
                + " (SELECT COUNT(DISTINCT action_id) FROM action_attempts"
                + "   WHERE action_id IS NOT NULL AND total_micros IS NOT NULL)";
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            long fromEvents = rows.getLong(1);
            long fromLog = rows.getLong(2);
            if (fromEvents == 0 && fromLog == 0) {
                return CriticalPath.DurationSource.NONE;
            }
            return fromLog >= fromEvents
                    ? CriticalPath.DurationSource.EXECUTION_ATTEMPT
                    : CriticalPath.DurationSource.BEP_ACTION;
        }
    }

    /** What to collect. */
    public record Request(
            CriticalPath.DurationSource durationSource,
            Set<GroupAggregate.Dimension> dimensions,
            int groupLimit) {

        public Request {
            Objects.requireNonNull(durationSource, "durationSource");
            dimensions = Set.copyOf(dimensions);
            if (groupLimit < 1) {
                throw new IllegalArgumentException("a limit below one returns nothing: "
                        + groupLimit);
            }
        }

        /** Every dimension the schema can group by, at the default limit. */
        public static Request everything(CriticalPath.DurationSource source) {
            return new Request(
                    source,
                    Set.of(GroupAggregate.Dimension.values()),
                    DEFAULT_GROUP_LIMIT);
        }
    }

    /**
     * Reads the whole metric catalog in one scan of the actions.
     *
     * <p>The scan is the expensive part and everything else is a handful of
     * scalar queries, so a caller that wants the dashboard asks once and gets
     * numbers that are all about the same read.
     */
    public SessionMetrics collect(Request request) throws SQLException {
        Map<GroupAggregate.Dimension, Map<String, GroupBuilder>> builders =
                new EnumMap<>(GroupAggregate.Dimension.class);
        for (GroupAggregate.Dimension dimension : request.dimensions()) {
            builders.put(dimension, new LinkedHashMap<>());
        }
        ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
        WorkTally tally = new WorkTally();

        forEachAction(request.durationSource(), row -> {
            tally.add(row);
            if (row.startMicros().isPresent() && row.endMicros().isPresent()
                    && row.endMicros().getAsLong() >= row.startMicros().getAsLong()) {
                spans.add(row.startMicros().getAsLong(), row.endMicros().getAsLong());
            } else {
                spans.addUntimed();
            }
            for (Map.Entry<GroupAggregate.Dimension, Map<String, GroupBuilder>> entry
                    : builders.entrySet()) {
                String key = row.keyFor(entry.getKey());
                entry.getValue()
                        .computeIfAbsent(key == null ? "" : key,
                                ignored -> new GroupBuilder(
                                        entry.getKey(), key, request.durationSource()))
                        .add(row);
            }
        });

        Map<GroupAggregate.Dimension, GroupAggregate.Table> tables =
                new EnumMap<>(GroupAggregate.Dimension.class);
        for (Map.Entry<GroupAggregate.Dimension, Map<String, GroupBuilder>> entry
                : builders.entrySet()) {
            tables.put(entry.getKey(), table(entry.getKey(), entry.getValue().values(),
                    request.groupLimit(), tally.actions));
        }

        ConcurrencySweep.Result sweep = ConcurrencySweep.sweep(spans);
        InvocationMetrics invocation = invocation(request.durationSource(), tally, sweep);
        return new SessionMetrics(request.durationSource(), invocation, tables, spans, sweep);
    }

    private static GroupAggregate.Table table(
            GroupAggregate.Dimension dimension,
            java.util.Collection<GroupBuilder> builders,
            int limit,
            long totalActions) {
        List<GroupAggregate> all = new ArrayList<>(builders.size());
        for (GroupBuilder builder : builders) {
            all.add(builder.build());
        }
        all.sort(Comparator
                .comparingLong((GroupAggregate group) -> group.duration().observedSum().orElse(-1))
                .reversed()
                .thenComparing(Comparator.comparingLong(GroupAggregate::actions).reversed())
                .thenComparing(GroupAggregate::displayKey));
        List<GroupAggregate> shown = all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
        return new GroupAggregate.Table(dimension, shown, all.size(), totalActions);
    }

    private void forEachAction(CriticalPath.DurationSource source, ActionRowVisitor visitor)
            throws SQLException {
        String durationExpression = source == CriticalPath.DurationSource.BEP_ACTION
                ? BEP_DURATION
                : ATTEMPT_DURATION;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(4_096);
            try (ResultSet rows = statement.executeQuery(
                    ACTION_ROWS_HEAD + durationExpression + ACTION_ROWS_TAIL)) {
                while (rows.next()) {
                    visitor.row(readRow(rows, source));
                }
            }
        }
    }

    private static ActionRow readRow(ResultSet rows, CriticalPath.DurationSource source)
            throws SQLException {
        long id = rows.getLong(1);
        String mnemonic = rows.getString(2);
        String label = rows.getString(3);
        String outcome = rows.getString(4);
        OptionalLong duration = number(rows, 5);
        OptionalLong start = number(rows, 6);
        OptionalLong end = number(rows, 7);
        String minRunner = rows.getString(8);
        String maxRunner = rows.getString(9);
        long runnersReported = rows.getLong(10);
        long attempts = rows.getLong(11);
        long cacheHits = rows.getLong(12);
        long cacheMisses = rows.getLong(13);
        long cacheKnown = rows.getLong(14);
        OptionalLong inputBytes = number(rows, 15);
        long sizedAttempts = rows.getLong(16);

        // An action whose spawns ran under different runners has no single
        // runner, and neither has one whose spawns did not all report theirs.
        // Both come back as unrecorded rather than as the one name that
        // happened to sort first (docs/exec-log-and-profile.md, K3).
        String runner = attempts > 0 && runnersReported == attempts
                && Objects.equals(minRunner, maxRunner)
                ? minRunner
                : null;

        CacheState cacheState;
        if (cacheKnown == 0) {
            cacheState = CacheState.NOT_REPORTED;
        } else if (cacheMisses > 0) {
            cacheState = CacheState.MISS;
        } else {
            cacheState = CacheState.HIT;
        }
        return new ActionRow(
                id, mnemonic, label, outcome, duration, start, end, runner, attempts,
                cacheState, sizedAttempts == 0 ? OptionalLong.empty() : inputBytes, source);
    }

    /** Receives one action at a time, in whatever order the database returns them. */
    @FunctionalInterface
    private interface ActionRowVisitor {
        void row(ActionRow row) throws SQLException;
    }

    /** Whether an action's spawns were served from a cache. */
    public enum CacheState {
        HIT("Hit"),
        MISS("Miss"),
        /**
         * Neither. An action with no execution-log record is not a miss, and
         * that difference is what stands between a real cache problem and one
         * invented by an enrichment that never ran.
         */
        NOT_REPORTED("Not reported");

        private final String displayName;

        CacheState(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * One action, as the metrics see it.
     *
     * @param durationMicros under {@link CriticalPath.DurationSource#BEP_ACTION}
     *     the action's wall duration; under
     *     {@link CriticalPath.DurationSource#EXECUTION_ATTEMPT} the subprocess
     *     time its attempts consumed, which is a different quantity
     * @param startMicros where the action sits on the clock under the chosen
     *     source, absent when that source did not say
     * @param runner the runner string Bazel wrote, or null when there was no
     *     attempt or the attempts disagreed
     * @param inputBytes bytes its attempts reported reading, absent when none
     *     reported any
     */
    public record ActionRow(
            long id,
            String mnemonic,
            String label,
            String outcome,
            OptionalLong durationMicros,
            OptionalLong startMicros,
            OptionalLong endMicros,
            String runner,
            long attempts,
            CacheState cacheState,
            OptionalLong inputBytes,
            CriticalPath.DurationSource durationSource) {

        /** The key this action falls under for one aggregate dimension. */
        public String keyFor(GroupAggregate.Dimension dimension) {
            return switch (dimension) {
                case MNEMONIC -> mnemonic;
                case TARGET -> label;
                case PACKAGE -> GraphClustering.packageOf(label);
                case RUNNER -> runner;
                case CACHE_STATE -> cacheState.displayName();
                case STATUS -> outcome;
            };
        }
    }

    /** Accumulates one group's sketches and counts. */
    private static final class GroupBuilder {

        private final GroupAggregate.Dimension dimension;
        private final String key;
        private final MetricSeries.Builder duration;
        private final MetricSeries.Builder inputBytes;
        private long actions;
        private long cacheHits;
        private long cacheMisses;
        private long cacheUnknown;

        GroupBuilder(GroupAggregate.Dimension dimension, String key,
                CriticalPath.DurationSource source) {
            this.dimension = dimension;
            this.key = key;
            this.duration = MetricSeries.builder(
                    durationName(source), MetricSeries.Units.MICROSECONDS, sourceOf(source));
            this.inputBytes = MetricSeries.builder(
                    "Known input bytes", MetricSeries.Units.BYTES, DataSource.EXECUTION_LOG);
        }

        void add(ActionRow row) {
            actions++;
            duration.observe(row.durationMicros());
            inputBytes.observe(row.inputBytes());
            switch (row.cacheState()) {
                case HIT -> cacheHits++;
                case MISS -> cacheMisses++;
                case NOT_REPORTED -> cacheUnknown++;
            }
        }

        GroupAggregate build() {
            return new GroupAggregate(
                    dimension, key, actions, duration.build(), inputBytes.build(),
                    cacheHits, cacheMisses, cacheUnknown);
        }
    }

    /** Running totals over every action, for the invocation-level metrics. */
    private static final class WorkTally {

        private long actions;
        private long attempts;
        private long succeeded;
        private long failed;
        private long otherOutcome;
        private long cacheHits;
        private long cacheMisses;
        private long cacheUnknown;
        private long timed;
        private long runnerKnown;
        private long inputBytesKnown;
        private long knownInputBytes;
        private boolean inputBytesExact = true;
        private final Map<String, Long> runners = new LinkedHashMap<>();

        void add(ActionRow row) {
            actions++;
            attempts += row.attempts();
            switch (row.outcome() == null ? "" : row.outcome()) {
                case "SUCCESS" -> succeeded++;
                case "FAILED" -> failed++;
                default -> otherOutcome++;
            }
            switch (row.cacheState()) {
                case HIT -> cacheHits++;
                case MISS -> cacheMisses++;
                case NOT_REPORTED -> cacheUnknown++;
            }
            if (row.durationMicros().isPresent()) {
                timed++;
            }
            if (row.runner() != null) {
                runnerKnown++;
                runners.merge(row.runner(), 1L, Long::sum);
            }
            if (row.inputBytes().isPresent()) {
                inputBytesKnown++;
                if (inputBytesExact) {
                    try {
                        knownInputBytes =
                                Math.addExact(knownInputBytes, row.inputBytes().getAsLong());
                    } catch (ArithmeticException overflow) {
                        inputBytesExact = false;
                    }
                }
            }
        }

        List<InvocationMetrics.Work.RunnerCount> runnerCounts() {
            return runners.entrySet().stream()
                    .map(entry -> new InvocationMetrics.Work.RunnerCount(
                            entry.getKey(), entry.getValue()))
                    .sorted(Comparator
                            .comparingLong(InvocationMetrics.Work.RunnerCount::actions).reversed()
                            .thenComparing(InvocationMetrics.Work.RunnerCount::runner))
                    .toList();
        }
    }

    private InvocationMetrics invocation(
            CriticalPath.DurationSource source, WorkTally tally, ConcurrencySweep.Result sweep)
            throws SQLException {
        InvocationMetrics.Timing timing = readTiming();
        InvocationMetrics.Tests tests = readTests();
        InvocationMetrics.Ingest ingest = readIngest();
        Outputs outputs = readOutputs();
        CriticalPaths criticalPaths = readCriticalPaths(source);

        InvocationMetrics.Work work = new InvocationMetrics.Work(
                tally.actions, tally.attempts, tally.succeeded, tally.failed, tally.otherOutcome,
                tally.cacheHits, tally.cacheMisses, tally.cacheUnknown, tally.runnerCounts());

        InvocationMetrics.Bytes bytes = new InvocationMetrics.Bytes(
                tally.inputBytesExact
                        ? Measured.of(tally.knownInputBytes, DataSource.EXECUTION_LOG)
                        : Measured.unknown(DataSource.EXECUTION_LOG, Completeness.UNKNOWN,
                                "the total overflowed a 64-bit count"),
                tally.actions - tally.inputBytesKnown,
                outputs.knownBytes(),
                outputs.withoutSize());

        Coverage.Report coverage = new Coverage.Report(List.of(
                Coverage.of("Timing coverage", tally.timed, tally.actions, sourceOf(source),
                        timingCoverageReason(source)),
                Coverage.of("Runner coverage", tally.runnerKnown, tally.actions,
                        DataSource.EXECUTION_LOG,
                        "an action that never spawned a subprocess has no execution-log record,"
                                + " and one whose spawns ran under different runners has no single"
                                + " runner"),
                Coverage.of("Cache-state coverage", tally.cacheHits + tally.cacheMisses,
                        tally.actions, DataSource.EXECUTION_LOG,
                        "the same actions that have no execution-log record"),
                Coverage.of("Input-size coverage", tally.inputBytesKnown, tally.actions,
                        DataSource.EXECUTION_LOG,
                        "input sizes come from the execution log, one record per spawn"),
                Coverage.of("Output-size coverage", outputs.sized(), outputs.artifacts(),
                        DataSource.BEP,
                        "a file that only ever existed on a remote executor has no local size"),
                graphCoverage(tally.actions),
                targetGraphCoverage(),
                correlationCoverage()));

        return new InvocationMetrics(
                timing, work, bytes, Optional.of(sweep), criticalPaths, tests, ingest, coverage);
    }

    private static String timingCoverageReason(CriticalPath.DurationSource source) {
        return switch (source) {
            case BEP_ACTION -> "Bazel does not publish action timestamps on every version, and"
                    + " an action reporting the same start and end instant has no duration to read";
            case EXECUTION_ATTEMPT -> "most actions run inside the Bazel server and never spawn a"
                    + " subprocess, so no execution-log record exists for them";
            case NONE -> "neither the build event stream nor an execution log timed this build";
        };
    }

    private InvocationMetrics.Timing readTiming() throws SQLException {
        OptionalLong started = OptionalLong.empty();
        OptionalLong finished = OptionalLong.empty();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT started_micros, finished_micros FROM build_invocation WHERE singleton = 1");
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                started = number(rows, 1);
                finished = number(rows, 2);
            }
        }
        OptionalLong wall = started.isPresent() && finished.isPresent()
                ? OptionalLong.of(finished.getAsLong() - started.getAsLong())
                : OptionalLong.empty();

        OptionalLong firstReceive = OptionalLong.empty();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT MIN(receive_micros) FROM bep_events");
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                firstReceive = number(rows, 1);
            }
        }
        OptionalLong toFirstEvent = started.isPresent() && firstReceive.isPresent()
                ? OptionalLong.of(firstReceive.getAsLong() - started.getAsLong())
                : OptionalLong.empty();

        List<InvocationMetrics.Timing.Phase> phases = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ordinal, name, start_micros, end_micros, end_is_derived"
                        + " FROM build_phases ORDER BY ordinal");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                phases.add(new InvocationMetrics.Timing.Phase(
                        rows.getInt(1), rows.getString(2), rows.getLong(3),
                        number(rows, 4), rows.getInt(5) != 0));
            }
        }

        return new InvocationMetrics.Timing(
                measured(wall, DataSource.BEP,
                        "the build event stream carries no finish, so the build did not report"
                                + " one — an interrupted capture has no BuildFinished"),
                measured(toFirstEvent, DataSource.BES_ENVELOPE,
                        "either the build never reported a start or no event was received"),
                phases);
    }

    private InvocationMetrics.Tests readTests() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*),"
                        + " SUM(CASE WHEN overall_status NOT IN ('PASSED', 'SKIPPED')"
                        + "   THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN overall_status = 'FLAKY' THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN total_num_cached > 0 THEN 1 ELSE 0 END) FROM tests");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return new InvocationMetrics.Tests(
                    rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4));
        }
    }

    private InvocationMetrics.Ingest readIngest() throws SQLException {
        long raw;
        long undecodable;
        long notAttempted;
        OptionalLong lag;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*),"
                        + " SUM(CASE WHEN decode_status = 'FAILED' THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN decode_status = 'NOT_ATTEMPTED' THEN 1 ELSE 0 END),"
                        + " AVG(receive_micros - event_micros) FROM bep_events");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            raw = rows.getLong(1);
            undecodable = rows.getLong(2);
            notAttempted = rows.getLong(3);
            double average = rows.getDouble(4);
            lag = rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(Math.round(average));
        }
        long correlated;
        long unresolved;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SUM(CASE WHEN action_id IS NOT NULL THEN 1 ELSE 0 END),"
                        + " SUM(CASE WHEN action_id IS NULL THEN 1 ELSE 0 END)"
                        + " FROM action_attempts");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            correlated = rows.getLong(1);
            unresolved = rows.getLong(2);
        }
        return new InvocationMetrics.Ingest(
                raw, undecodable, notAttempted,
                measured(lag, DataSource.BES_ENVELOPE,
                        "no event carried its own timestamp, so there is nothing to compare a"
                                + " receipt time against"),
                correlated, unresolved);
    }

    private record Outputs(Measured<Long> knownBytes, long artifacts, long sized) {

        long withoutSize() {
            return artifacts - sized;
        }
    }

    private Outputs readOutputs() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), COUNT(size_bytes), SUM(size_bytes) FROM artifacts"
                        + " WHERE is_directory = 0");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            long artifacts = rows.getLong(1);
            long sized = rows.getLong(2);
            OptionalLong total = number(rows, 3);
            return new Outputs(
                    measured(total, DataSource.BEP,
                            "no artifact reported a size, so there is no total to report"),
                    artifacts, sized);
        }
    }

    /**
     * Both critical paths, read from their own sources and left apart.
     *
     * <p>The derived one needs a graph and a weight per node, so it is absent
     * whenever no graph was imported. That absence is the honest answer and not
     * a reason to promote Bazel's number into the empty slot.
     */
    private CriticalPaths readCriticalPaths(CriticalPath.DurationSource source)
            throws SQLException {
        OptionalLong bazelMicros = OptionalLong.empty();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT critical_path_micros FROM build_metrics WHERE singleton = 1");
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                bazelMicros = number(rows, 1);
            }
        }
        List<CriticalPaths.BazelComponent> components = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ordinal, description, duration_micros FROM bazel_critical_path"
                        + " ORDER BY ordinal");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                components.add(new CriticalPaths.BazelComponent(
                        rows.getInt(1), rows.getString(2), number(rows, 3)));
            }
        }
        if (bazelMicros.isEmpty() && !components.isEmpty()) {
            // Bazel publishes criticalPathTime in BuildMetrics only from 9.2.0,
            // but writes the components into the profile on every version. The
            // sum of the components is Bazel's own answer either way; it is
            // still Bazel's number and not ours.
            long total = 0;
            boolean complete = true;
            for (CriticalPaths.BazelComponent component : components) {
                if (component.durationMicros().isEmpty()) {
                    complete = false;
                    break;
                }
                total += component.durationMicros().getAsLong();
            }
            if (complete) {
                bazelMicros = OptionalLong.of(total);
            }
        }

        Measured<Long> bazel = bazelMicros.isPresent()
                ? Measured.of(bazelMicros.getAsLong(), DataSource.PROFILE)
                : Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE,
                        "no trace profile was imported, and BuildMetrics carries a critical-path"
                                + " time only from Bazel 9.2.0");

        return new CriticalPaths(bazel, components, derivedCriticalPath(source));
    }

    private Optional<CriticalPath.Result> derivedCriticalPath(CriticalPath.DurationSource source)
            throws SQLException {
        if (graph.isEmpty() || source == CriticalPath.DurationSource.NONE) {
            return Optional.empty();
        }
        GraphQueries queries = graph.orElseThrow();
        Optional<CsrGraph> forward;
        try {
            forward = queries.forwardIndex(EdgeDerivation.DECLARED);
        } catch (IOException unreadable) {
            // A memory-mapped index that will not open is a session problem,
            // not a metrics problem: every other number here is still correct,
            // so the derived path is absent and CriticalPaths says so rather
            // than the whole dashboard failing.
            return Optional.empty();
        }
        if (forward.isEmpty()) {
            return Optional.empty();
        }
        long[] durations = queries.durationsByNodeIndex(
                source == CriticalPath.DurationSource.EXECUTION_ATTEMPT,
                CriticalPath.UNKNOWN_DURATION);
        return Optional.of(CriticalPath.compute(forward.orElseThrow(), durations, source));
    }

    private Coverage graphCoverage(long actions) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), COUNT(action_id) FROM declared_actions");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            long declared = rows.getLong(1);
            long correlated = rows.getLong(2);
            if (declared == 0) {
                return Coverage.unavailable("Action-graph coverage", actions, DataSource.AQUERY,
                        "no aquery output was imported for this session");
            }
            return Coverage.of("Action-graph coverage", correlated, declared, DataSource.AQUERY,
                    "an action the graph declares and this invocation did not execute has nothing"
                            + " to correlate with, which a cache hit produces by design");
        }
    }

    private Coverage targetGraphCoverage() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM configured_target_nodes),"
                        + " (SELECT COUNT(*) FROM configured_targets)");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            long nodes = rows.getLong(1);
            long configured = rows.getLong(2);
            if (nodes == 0) {
                return Coverage.unavailable("Target-graph coverage", configured, DataSource.CQUERY,
                        "no cquery output was imported for this session");
            }
            return Coverage.of("Target-graph coverage", Math.min(nodes, configured), configured,
                    DataSource.CQUERY,
                    "cquery describes the targets it was asked about, which need not be every"
                            + " target this invocation completed");
        }
    }

    private Coverage correlationCoverage() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*), SUM(CASE WHEN action_id IS NOT NULL THEN 1 ELSE 0 END)"
                        + " FROM action_attempts");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            long attempts = rows.getLong(1);
            long matched = rows.getLong(2);
            if (attempts == 0) {
                return Coverage.unavailable("Correlation coverage", 0, DataSource.EXECUTION_LOG,
                        "no execution log was imported for this session");
            }
            return Coverage.of("Correlation coverage", matched, attempts, DataSource.EXECUTION_LOG,
                    "a spawn whose action the build event stream never published cannot be"
                            + " matched to one, which is the normal result without"
                            + " --build_event_publish_all_actions");
        }
    }

    /** The name a duration series goes under, which says what was measured. */
    public static String durationName(CriticalPath.DurationSource source) {
        return switch (source) {
            case BEP_ACTION -> "Action wall duration";
            case EXECUTION_ATTEMPT -> "Subprocess time";
            case NONE -> "Duration";
        };
    }

    private static DataSource sourceOf(CriticalPath.DurationSource source) {
        return switch (source) {
            case BEP_ACTION -> DataSource.BEP;
            case EXECUTION_ATTEMPT -> DataSource.EXECUTION_LOG;
            case NONE -> DataSource.DERIVED;
        };
    }

    private static OptionalLong number(ResultSet rows, int index) throws SQLException {
        long value = rows.getLong(index);
        return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static Measured<Long> measured(
            OptionalLong value, DataSource source, String whyMissing) {
        return value.isPresent()
                ? Measured.of(value.getAsLong(), source)
                : Measured.unknown(source, Completeness.UNAVAILABLE, whyMissing);
    }

    /** Closes the connection this was given, and not the shared graph reader. */
    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
