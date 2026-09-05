package com.holtherndon.bazelviz.storage.metrics;

import com.holtherndon.bazelviz.analysis.ActionMetrics;
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
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.GraphResourceBudget;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.graph.GraphSessionResources;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * The metrics read path: streams the session once and hands every row to the aggregators in {@code
 * analysis-core}.
 *
 * <h2>Which timing source, decided by measuring</h2>
 *
 * <p>Two sources report how long an action took, and they disagree about more than the number. The
 * build event stream publishes no action timestamps at all on Bazel 6.5.0 and 7.6.1; on 8.4.1 it
 * publishes {@code endTime == startTime} for every action, a five-second sleep included; on 9.2.0
 * roughly a third of action events carry none. The execution log reports a real elapsed time per
 * subprocess, but only for the actions that spawned one — a minority, because most actions run
 * inside the Bazel server and never do.
 *
 * <p>So {@link #bestDurationSource()} counts what each source actually covers in <em>this</em>
 * session and picks the larger, and every metric built from a duration carries which one it used.
 * Choosing from the Bazel version would be wrong for a session captured with different flags, and
 * defaulting to the event stream would silently produce a build of instantaneous actions on 8.4.1.
 *
 * <h2>One pass, one row per action</h2>
 *
 * <p>{@link #collect} answers the whole dashboard from a single scan. Aggregating in SQL would be
 * six queries with six {@code GROUP BY} clauses and the interesting decisions — what "the runner"
 * of an action with two differently-run spawns is, whether an unrecorded cache state counts as a
 * miss — buried in {@code CASE} expressions. Instead one query returns one row per action with
 * everything those decisions need, and the folding happens in Java where it can be read and tested.
 *
 * <p>There is deliberately no second public entry point that answers part of the same question. A
 * {@code coverage()} or {@code aggregate()} that ran its own scan would be a second definition of
 * the same numbers, free to drift from this one.
 *
 * <h2>Threading</h2>
 *
 * <p>Wraps one JDBC connection, so one instance belongs to one thread, and every method blocks.
 * None of them may be called on the Swing EDT (rule 8).
 */
public final class MetricQueries implements AutoCloseable {

  /** Groups an aggregation returns before the rest are summarised away. */
  public static final int DEFAULT_GROUP_LIMIT = 40;

  /**
   * Actions kept per criterion for the findings to examine.
   *
   * <p>The findings need the extremes — the slowest, the largest, the most queued — and nothing in
   * the middle. Keeping the top few per criterion in a bounded heap during the scan that was
   * happening anyway means the rules run over a few hundred actions rather than five million,
   * without a second pass and without retaining the build.
   */
  public static final int DEFAULT_CANDIDATE_LIMIT = 25;

  private static final String ACTION_ROWS_HEAD = "SELECT act.id, m.value, l.value, act.outcome, ";

  /** Only rows belonging to the current, fully successful execution-log import are evidence. */
  private static final String SUCCEEDED_EXECUTION_LOG_TASK =
      "(SELECT id FROM enrichment_tasks" + " WHERE kind = 'EXECUTION_LOG' AND state = 'SUCCEEDED')";

  private static final String ACTION_ROWS_TAIL =
      " MIN(att.runner), MAX(att.runner), COUNT(att.runner), COUNT(att.id),"
          + " SUM(CASE WHEN att.cache_hit = 1 THEN 1 ELSE 0 END),"
          + " SUM(CASE WHEN att.cache_hit = 0 THEN 1 ELSE 0 END),"
          + " COUNT(att.cache_hit),"
          + completeAttemptSum("input_bytes")
          + ", COUNT(att.input_bytes),"
          + completeAttemptSum("queue_micros")
          + ","
          + completeAttemptSum("setup_micros")
          + ","
          + completeAttemptSum("execution_wall_micros")
          + ","
          + completeAttemptSum("network_micros")
          + ","
          + completeAttemptSum("upload_micros")
          + ","
          + completeAttemptSum("fetch_micros")
          + ","
          + completeAttemptSum("input_files")
          + ","
          // Any attempt carrying the declaration marks the action; these
          // are Bazel's own words about the spawn, not a reading of the
          // runner name.
          + " MAX(CASE WHEN att.cacheable = 0 THEN 1 ELSE 0 END),"
          + " MAX(CASE WHEN att.remotable = 0 THEN 1 ELSE 0 END)"
          + ", act.primary_output, act.start_micros, act.end_micros"
          + " FROM actions act"
          + " LEFT JOIN mnemonics m ON m.id = act.mnemonic_id"
          + " LEFT JOIN labels l ON l.id = act.label_id"
          + " LEFT JOIN action_attempts att ON att.action_id = act.id"
          + " AND att.task_id = "
          + SUCCEEDED_EXECUTION_LOG_TASK;

  private static final String GROUP_BY_ACTION = " GROUP BY act.id";

  /**
   * Wall duration from the build event stream, and only when the pair is usable.
   *
   * <p>{@code end > start} rather than {@code end >= start}: on Bazel 8.4.1 every action reports
   * the two as equal, and calling that a duration of zero would fill a distribution with actions
   * that appear to have taken no time. Those count as unavailable, which is what they are — while
   * the span is still handed to the sweep, which reports them as instantaneous rather than losing
   * them.
   */
  private static final String BEP_DURATION =
      "CASE WHEN act.start_micros IS NOT NULL AND act.end_micros IS NOT NULL"
          + " AND act.end_micros > act.start_micros"
          + " THEN act.end_micros - act.start_micros END,"
          + " act.start_micros, act.end_micros,";

  /**
   * Subprocess time from the execution log, summed over the action's attempts, with the span its
   * attempts occupied.
   *
   * <p>The sum is not the same quantity as a wall duration and is deliberately not named as if it
   * were: an action that ran twice under the dynamic strategy consumed two subprocesses' worth of
   * time and held the wall clock for less. {@link MetricSeries#name()} carries the distinction onto
   * the screen.
   */
  private static final String ATTEMPT_DURATION =
      "CASE WHEN COUNT(att.id) > 0"
          + " AND COUNT(att.total_micros) = COUNT(att.id)"
          + " AND MIN(att.total_micros) >= 0"
          + " THEN SUM(att.total_micros) END,"
          + " CASE WHEN COUNT(att.id) > 0"
          + " AND COUNT(att.start_micros) = COUNT(att.id)"
          + " THEN MIN(att.start_micros) END,"
          + " CASE WHEN COUNT(att.id) > 0"
          + " AND COUNT(att.start_micros) = COUNT(att.id)"
          + " AND COUNT(att.total_micros) = COUNT(att.id)"
          + " AND MIN(att.total_micros) >= 0"
          + " THEN MAX(att.start_micros + att.total_micros) END,";

  /** Three absent timing columns for a caller explicitly requesting no duration source. */
  private static final String NO_DURATION = "NULL, NULL, NULL,";

  private static String completeAttemptSum(String column) {
    return " CASE WHEN COUNT(att.id) > 0 AND COUNT(att."
        + column
        + ") = COUNT(att.id)"
        + " THEN SUM(att."
        + column
        + ") END";
  }

  private final Connection connection;
  private final Optional<GraphQueries> graph;
  private ProfileTrust cachedProfileTrust;
  private BazelComponentSummary cachedBazelComponentValidation;

  /** Reads the metrics that need no dependency graph. */
  public MetricQueries(Connection connection) {
    this(connection, null);
  }

  /**
   * Reads every metric, the derived critical path included.
   *
   * @param graph the session's graph reader, or null when no graph was imported. This takes
   *     ownership of it: {@link #close()} closes it, so the caller must not hand over one that
   *     another thread is also using.
   */
  public MetricQueries(Connection connection, GraphQueries graph) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.graph = Optional.ofNullable(graph);
  }

  /**
   * Which duration source covers more of this session, measured rather than assumed.
   *
   * <p>Ties go to the execution log, because its number is an elapsed time for a subprocess that
   * really ran, while the event stream's is whatever Bazel chose to stamp on the event.
   */
  public CriticalPath.DurationSource bestDurationSource() throws SQLException {
    String sql =
        "SELECT (SELECT COUNT(*) FROM actions WHERE start_micros IS NOT NULL"
            + "   AND end_micros IS NOT NULL AND end_micros > start_micros),"
            + " (SELECT COUNT(*) FROM (SELECT action_id FROM action_attempts"
            + "   WHERE action_id IS NOT NULL AND task_id = "
            + SUCCEEDED_EXECUTION_LOG_TASK
            + "   GROUP BY action_id HAVING COUNT(total_micros) = COUNT(*)"
            + "   AND MIN(total_micros) >= 0))";
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
      int groupLimit,
      int candidateLimit) {

    public Request {
      Objects.requireNonNull(durationSource, "durationSource");
      dimensions = Set.copyOf(dimensions);
      if (groupLimit < 1) {
        throw new IllegalArgumentException("a limit below one returns nothing: " + groupLimit);
      }
      if (candidateLimit < 1) {
        throw new IllegalArgumentException("a limit below one returns nothing: " + candidateLimit);
      }
    }

    /** Every dimension the schema can group by, at the default limits. */
    public static Request everything(CriticalPath.DurationSource source) {
      return new Request(
          source,
          Set.of(GroupAggregate.Dimension.values()),
          DEFAULT_GROUP_LIMIT,
          DEFAULT_CANDIDATE_LIMIT);
    }
  }

  /**
   * Reads the whole metric catalog in one scan of the actions.
   *
   * <p>The scan is the expensive part and everything else is a handful of scalar queries, so a
   * caller that wants the dashboard asks once and gets numbers that are all about the same read.
   */
  public SessionMetrics collect(Request request) throws SQLException {
    Map<GroupAggregate.Dimension, Map<String, GroupBuilder>> builders =
        new EnumMap<>(GroupAggregate.Dimension.class);
    for (GroupAggregate.Dimension dimension : request.dimensions()) {
      builders.put(dimension, new LinkedHashMap<>());
    }
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    WorkTally tally = new WorkTally();
    // Read first, so an action that is only interesting for the size of
    // what it produced still becomes a candidate during the one scan.
    Map<Long, OutputTotals> outputTotals = topOutputs(request.candidateLimit());
    Candidates collector = new Candidates(request.candidateLimit(), outputTotals);

    forEachAction(
        request.durationSource(),
        row -> {
          tally.add(row);
          collector.offer(row);
          if (row.startMicros().isPresent()
              && row.endMicros().isPresent()
              && row.endMicros().getAsLong() >= row.startMicros().getAsLong()) {
            spans.add(row.startMicros().getAsLong(), row.endMicros().getAsLong());
          } else {
            spans.addUntimed();
          }
          for (Map.Entry<GroupAggregate.Dimension, Map<String, GroupBuilder>> entry :
              builders.entrySet()) {
            String key = row.keyFor(entry.getKey());
            entry
                .getValue()
                .computeIfAbsent(
                    key == null ? "" : key,
                    ignored -> new GroupBuilder(entry.getKey(), key, request.durationSource()))
                .add(row);
          }
        });

    Map<GroupAggregate.Dimension, GroupAggregate.Table> tables =
        new EnumMap<>(GroupAggregate.Dimension.class);
    for (Map.Entry<GroupAggregate.Dimension, Map<String, GroupBuilder>> entry :
        builders.entrySet()) {
      tables.put(
          entry.getKey(),
          table(entry.getKey(), entry.getValue().values(), request.groupLimit(), tally.actions));
    }

    ConcurrencySweep.Result sweep = ConcurrencySweep.sweep(spans);
    InvocationMetrics invocation = invocation(request.durationSource(), tally, sweep);
    Optional<CriticalPath.Result> derived = invocation.criticalPaths().derived();
    List<ActionMetrics> candidates = enrich(collector.finish(outputTotals), derived, spans);
    List<ActionMetrics> onPath =
        enrich(
            criticalPathActions(derived, request.candidateLimit(), request.durationSource()),
            derived,
            spans);
    return new SessionMetrics(
        request.durationSource(), invocation, tables, spans, sweep, candidates, onPath);
  }

  private static GroupAggregate.Table table(
      GroupAggregate.Dimension dimension,
      Collection<GroupBuilder> builders,
      int limit,
      long totalActions) {
    List<GroupAggregate> all = new ArrayList<>(builders.size());
    for (GroupBuilder builder : builders) {
      all.add(builder.build());
    }
    all.sort(
        Comparator.comparingLong(
                (GroupAggregate group) -> group.duration().observedSum().orElse(-1))
            .reversed()
            .thenComparing(Comparator.comparingLong(GroupAggregate::actions).reversed())
            .thenComparing(GroupAggregate::displayKey));
    List<GroupAggregate> shown = all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    return new GroupAggregate.Table(dimension, shown, all.size(), totalActions);
  }

  private void forEachAction(CriticalPath.DurationSource source, ActionRowVisitor visitor)
      throws SQLException {
    String durationExpression =
        switch (source) {
          case BEP_ACTION -> BEP_DURATION;
          case EXECUTION_ATTEMPT -> ATTEMPT_DURATION;
          case NONE -> NO_DURATION;
        };
    try (Statement statement = connection.createStatement()) {
      statement.setFetchSize(4_096);
      try (ResultSet rows =
          statement.executeQuery(
              ACTION_ROWS_HEAD + durationExpression + ACTION_ROWS_TAIL + GROUP_BY_ACTION)) {
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
    OptionalLong queue = number(rows, 17);
    OptionalLong setup = number(rows, 18);
    OptionalLong execution = number(rows, 19);
    OptionalLong network = number(rows, 20);
    OptionalLong upload = number(rows, 21);
    OptionalLong fetch = number(rows, 22);
    OptionalLong inputFiles = number(rows, 23);
    boolean notCacheable = rows.getInt(24) == 1;
    boolean notRemotable = rows.getInt(25) == 1;
    String primaryOutput = rows.getString(26);
    OptionalLong bepStart = number(rows, 27);
    OptionalLong bepEnd = number(rows, 28);
    OptionalLong bepDuration =
        bepStart.isPresent() && bepEnd.isPresent() && bepEnd.getAsLong() > bepStart.getAsLong()
            ? OptionalLong.of(bepEnd.getAsLong() - bepStart.getAsLong())
            : OptionalLong.empty();

    // The detailed components are subprocess measurements. Pairing them
    // with a BEP action-wall denominator would manufacture fractions and
    // an "unaccounted" remainder from two different quantities.
    if (source != CriticalPath.DurationSource.EXECUTION_ATTEMPT) {
      queue = OptionalLong.empty();
      setup = OptionalLong.empty();
      execution = OptionalLong.empty();
      network = OptionalLong.empty();
      upload = OptionalLong.empty();
      fetch = OptionalLong.empty();
    }

    // An action whose spawns ran under different runners has no single
    // runner, and neither has one whose spawns did not all report theirs.
    // Both come back as unrecorded rather than as the one name that
    // happened to sort first (docs/exec-log-and-profile.md, K3).
    String runner =
        attempts > 0 && runnersReported == attempts && Objects.equals(minRunner, maxRunner)
            ? minRunner
            : null;

    CacheState cacheState;
    if (cacheKnown == 0) {
      cacheState = CacheState.NOT_REPORTED;
    } else if (cacheMisses > 0) {
      // One executed attempt proves the action was not wholly served by
      // cache even if a sibling attempt omitted its cache field.
      cacheState = CacheState.MISS;
    } else if (cacheKnown != attempts) {
      // A partial set of cache-hit declarations cannot establish that
      // every attempt was a hit.
      cacheState = CacheState.NOT_REPORTED;
    } else {
      cacheState = CacheState.HIT;
    }
    return new ActionRow(
        id,
        mnemonic,
        label,
        outcome,
        duration,
        start,
        end,
        runner,
        attempts,
        cacheState,
        sizedAttempts == 0 ? OptionalLong.empty() : inputBytes,
        queue,
        setup,
        execution,
        network,
        upload,
        fetch,
        inputFiles,
        notCacheable,
        notRemotable,
        source,
        primaryOutput,
        bepDuration);
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
     * Neither. An action with no execution-log record is not a miss, and that difference is what
     * stands between a real cache problem and one invented by an enrichment that never ran.
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
   * @param durationMicros under {@link CriticalPath.DurationSource#BEP_ACTION} the action's wall
   *     duration; under {@link CriticalPath.DurationSource#EXECUTION_ATTEMPT} the subprocess time
   *     its attempts consumed, which is a different quantity
   * @param startMicros where the action sits on the clock under the chosen source, absent when that
   *     source did not say
   * @param runner the runner string Bazel wrote, or null when there was no attempt or the attempts
   *     disagreed
   * @param inputBytes bytes its attempts reported reading, absent when none reported any
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
      OptionalLong queueMicros,
      OptionalLong setupMicros,
      OptionalLong executionMicros,
      OptionalLong networkMicros,
      OptionalLong uploadMicros,
      OptionalLong fetchMicros,
      OptionalLong inputFiles,
      boolean declaredNotCacheable,
      boolean declaredNotRemotable,
      CriticalPath.DurationSource durationSource,
      String primaryOutput,
      OptionalLong bepDurationMicros) {

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
    private long notCacheable;
    private long notRemotable;

    GroupBuilder(
        GroupAggregate.Dimension dimension, String key, CriticalPath.DurationSource source) {
      this.dimension = dimension;
      this.key = key;
      this.duration =
          MetricSeries.builder(
              durationName(source), MetricSeries.Units.MICROSECONDS, sourceOf(source));
      this.inputBytes =
          MetricSeries.builder(
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
      if (row.declaredNotCacheable()) {
        notCacheable++;
      }
      if (row.declaredNotRemotable()) {
        notRemotable++;
      }
    }

    GroupAggregate build() {
      return new GroupAggregate(
          dimension,
          key,
          actions,
          duration.build(),
          inputBytes.build(),
          cacheHits,
          cacheMisses,
          cacheUnknown,
          notCacheable,
          notRemotable);
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
    private long bepTimed;
    private ActionRow longestBepAction;
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
      if (row.bepDurationMicros().isPresent()) {
        bepTimed++;
        if (longestBepAction == null
            || row.bepDurationMicros().getAsLong()
                > longestBepAction.bepDurationMicros().orElseThrow()
            || (row.bepDurationMicros().getAsLong()
                    == longestBepAction.bepDurationMicros().orElseThrow()
                && row.id() < longestBepAction.id())) {
          longestBepAction = row;
        }
      }
      if (row.runner() != null) {
        runnerKnown++;
        runners.merge(row.runner(), 1L, Long::sum);
      }
      if (row.inputBytes().isPresent()) {
        inputBytesKnown++;
        if (inputBytesExact) {
          try {
            knownInputBytes = Math.addExact(knownInputBytes, row.inputBytes().getAsLong());
          } catch (ArithmeticException overflow) {
            inputBytesExact = false;
          }
        }
      }
    }

    List<InvocationMetrics.Work.RunnerCount> runnerCounts() {
      return runners.entrySet().stream()
          .map(entry -> new InvocationMetrics.Work.RunnerCount(entry.getKey(), entry.getValue()))
          .sorted(
              Comparator.comparingLong(InvocationMetrics.Work.RunnerCount::actions)
                  .reversed()
                  .thenComparing(InvocationMetrics.Work.RunnerCount::runner))
          .toList();
    }

    Optional<CriticalPaths.ObservedActionLowerBound> observedActionLowerBound() {
      if (longestBepAction == null) {
        return Optional.empty();
      }
      ActionRow action = longestBepAction;
      return Optional.of(
          new CriticalPaths.ObservedActionLowerBound(
              action.id(),
              action.primaryOutput(),
              Optional.ofNullable(action.label()),
              Optional.ofNullable(action.mnemonic()),
              action.bepDurationMicros().orElseThrow(),
              bepTimed,
              actions));
    }
  }

  private InvocationMetrics invocation(
      CriticalPath.DurationSource source, WorkTally tally, ConcurrencySweep.Result sweep)
      throws SQLException {
    InvocationMetrics.Timing timing = readTiming();
    InvocationMetrics.Tests tests = readTests();
    InvocationMetrics.Ingest ingest = readIngest();
    Outputs outputs = readOutputs();
    CriticalPaths criticalPaths = readCriticalPaths(source, tally.observedActionLowerBound());

    InvocationMetrics.Work work =
        new InvocationMetrics.Work(
            tally.actions,
            tally.attempts,
            tally.succeeded,
            tally.failed,
            tally.otherOutcome,
            tally.cacheHits,
            tally.cacheMisses,
            tally.cacheUnknown,
            tally.runnerCounts());

    InvocationMetrics.Bytes bytes =
        new InvocationMetrics.Bytes(
            tally.inputBytesExact
                ? Measured.of(tally.knownInputBytes, DataSource.EXECUTION_LOG)
                : Measured.unknown(
                    DataSource.EXECUTION_LOG,
                    Completeness.UNKNOWN,
                    "the total overflowed a 64-bit count"),
            tally.actions - tally.inputBytesKnown,
            outputs.knownBytes(),
            outputs.withoutSize());

    Coverage.Report coverage =
        new Coverage.Report(
            List.of(
                Coverage.of(
                    "Timing coverage",
                    tally.timed,
                    tally.actions,
                    sourceOf(source),
                    timingCoverageReason(source)),
                Coverage.of(
                    "Runner coverage",
                    tally.runnerKnown,
                    tally.actions,
                    DataSource.EXECUTION_LOG,
                    "an action that never spawned a subprocess has no execution-log record,"
                        + " and one whose spawns ran under different runners has no single"
                        + " runner"),
                Coverage.of(
                    "Cache-state coverage",
                    tally.cacheHits + tally.cacheMisses,
                    tally.actions,
                    DataSource.EXECUTION_LOG,
                    "the same actions that have no execution-log record"),
                Coverage.of(
                    "Input-size coverage",
                    tally.inputBytesKnown,
                    tally.actions,
                    DataSource.EXECUTION_LOG,
                    "input sizes come from the execution log, one record per spawn"),
                Coverage.of(
                    "Output-size coverage",
                    outputs.sized(),
                    outputs.artifacts(),
                    DataSource.BEP,
                    "a file that only ever existed on a remote executor has no local size"),
                actionGraphCorrelation(tally.actions),
                actionGraphCompleteness(tally.actions),
                targetGraphCoverage(),
                correlationCoverage()));

    Optional<ConcurrencySweep.Result> observedConcurrency =
        sweep.sweptSpans() == 0 ? Optional.empty() : Optional.of(sweep);
    return new InvocationMetrics(
        timing, work, bytes, observedConcurrency, criticalPaths, tests, ingest, coverage);
  }

  private static String timingCoverageReason(CriticalPath.DurationSource source) {
    return switch (source) {
      case BEP_ACTION ->
          "Bazel does not publish action timestamps on every version, and"
              + " an action reporting the same start and end instant has no duration to read";
      case EXECUTION_ATTEMPT ->
          "most actions run inside the Bazel server and never spawn a"
              + " subprocess, so no execution-log record exists for them";
      case NONE -> "neither the build event stream nor an execution log timed this build";
    };
  }

  private InvocationMetrics.Timing readTiming() throws SQLException {
    OptionalLong started = OptionalLong.empty();
    OptionalLong finished = OptionalLong.empty();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT started_micros, finished_micros FROM build_invocation WHERE singleton = 1");
        ResultSet rows = statement.executeQuery()) {
      if (rows.next()) {
        started = number(rows, 1);
        finished = number(rows, 2);
      }
    }
    OptionalLong wall =
        started.isPresent() && finished.isPresent()
            ? OptionalLong.of(finished.getAsLong() - started.getAsLong())
            : OptionalLong.empty();

    OptionalLong firstReceive = OptionalLong.empty();
    try (PreparedStatement statement =
            connection.prepareStatement("SELECT MIN(receive_micros) FROM bep_events");
        ResultSet rows = statement.executeQuery()) {
      if (rows.next()) {
        firstReceive = number(rows, 1);
      }
    }
    OptionalLong toFirstEvent =
        started.isPresent() && firstReceive.isPresent()
            ? OptionalLong.of(firstReceive.getAsLong() - started.getAsLong())
            : OptionalLong.empty();

    List<InvocationMetrics.Timing.Phase> phases = new ArrayList<>();
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT ordinal, name, start_micros, end_micros, end_is_derived"
                    + " FROM build_phases ORDER BY ordinal");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        phases.add(
            new InvocationMetrics.Timing.Phase(
                rows.getInt(1),
                rows.getString(2),
                rows.getLong(3),
                number(rows, 4),
                rows.getInt(5) != 0));
      }
    }

    return new InvocationMetrics.Timing(
        measured(
            wall,
            DataSource.BEP,
            "the build event stream carries no finish, so the build did not report"
                + " one — an interrupted capture has no BuildFinished"),
        measured(
            toFirstEvent,
            DataSource.BES_ENVELOPE,
            "either the build never reported a start or no event was received"),
        phases);
  }

  private InvocationMetrics.Tests readTests() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
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
    try (PreparedStatement statement =
            connection.prepareStatement(
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
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT SUM(CASE WHEN action_id IS NOT NULL THEN 1 ELSE 0 END),"
                    + " SUM(CASE WHEN action_id IS NULL THEN 1 ELSE 0 END)"
                    + " FROM action_attempts WHERE task_id = "
                    + SUCCEEDED_EXECUTION_LOG_TASK);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      correlated = rows.getLong(1);
      unresolved = rows.getLong(2);
    }
    return new InvocationMetrics.Ingest(
        raw,
        undecodable,
        notAttempted,
        measured(
            lag,
            DataSource.BES_ENVELOPE,
            "no event carried its own timestamp, so there is nothing to compare a"
                + " receipt time against"),
        correlated,
        unresolved);
  }

  private record Outputs(Measured<Long> knownBytes, long artifacts, long sized) {

    long withoutSize() {
      return artifacts - sized;
    }
  }

  private Outputs readOutputs() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*), COUNT(size_bytes), SUM(size_bytes) FROM artifacts"
                    + " WHERE is_directory = 0");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      long artifacts = rows.getLong(1);
      long sized = rows.getLong(2);
      OptionalLong total = number(rows, 3);
      return new Outputs(
          measured(
              total, DataSource.BEP, "no artifact reported a size, so there is no total to report"),
          artifacts,
          sized);
    }
  }

  /**
   * Both critical paths, read from their own sources and left apart.
   *
   * <p>The derived one needs a graph and a weight per node, so it is absent whenever no graph was
   * imported. That absence is the honest answer and not a reason to promote Bazel's number into the
   * empty slot.
   */
  private CriticalPaths readCriticalPaths(
      CriticalPath.DurationSource source,
      Optional<CriticalPaths.ObservedActionLowerBound> observedActionLowerBound)
      throws SQLException {
    OptionalLong bazelMicros = OptionalLong.empty();
    DataSource bazelSource = DataSource.BEP;
    String bepUnavailableReason = null;
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT critical_path_micros FROM build_metrics WHERE singleton = 1");
        ResultSet rows = statement.executeQuery()) {
      if (rows.next()) {
        bazelMicros = number(rows, 1);
        if (bazelMicros.isPresent() && bazelMicros.getAsLong() < 0) {
          bazelMicros = OptionalLong.empty();
          bepUnavailableReason = "BuildMetrics reported a negative critical-path duration";
        }
      }
    }
    ProfileTrust profileTrust = profileTrust();
    BazelComponentSummary componentSummary =
        profileTrust.trusted()
            ? bazelComponentSummary(bazelMicros.isEmpty())
            : BazelComponentSummary.empty();
    String profileUnavailableReason = null;
    if (bazelMicros.isEmpty() && componentSummary.totalMicros().isPresent()) {
      // Bazel publishes criticalPathTime in BuildMetrics only from 9.2.0,
      // but writes the components into the profile on every version. The
      // sum of the components is Bazel's own answer either way; it is
      // still Bazel's number and not ours.
      bazelMicros = componentSummary.totalMicros();
      bazelSource = DataSource.PROFILE;
    }

    if (componentSummary.problem().isPresent()) {
      profileUnavailableReason = componentSummary.problem().orElseThrow();
    } else if (bazelMicros.isEmpty()) {
      if (!profileTrust.trusted()) {
        profileUnavailableReason = profileTrust.reason();
      } else if (componentSummary.count() == 0) {
        profileUnavailableReason =
            "the trace profile contained no critical-path components, and"
                + " BuildMetrics carries a critical-path time only from"
                + " Bazel 9.2.0";
      }
    }

    String unavailableReason = joinReasons(bepUnavailableReason, profileUnavailableReason);
    Measured<Long> bazel =
        bazelMicros.isPresent()
            ? Measured.of(bazelMicros.getAsLong(), bazelSource)
            : Measured.unknown(
                DataSource.PROFILE,
                Completeness.UNAVAILABLE,
                unavailableReason == null
                    ? "neither BuildMetrics nor the trace profile reported a usable"
                        + " critical-path duration"
                    : unavailableReason);
    if (bazelMicros.isPresent()
        && bazelSource == DataSource.BEP
        && profileTrust.recorded()
        && !profileTrust.trusted()) {
      bazel = bazel.warn("profile component breakdown withheld: " + profileTrust.reason());
    } else if (bazelMicros.isPresent()
        && bazelSource == DataSource.BEP
        && componentSummary.problem().isPresent()) {
      bazel =
          bazel.warn(
              "profile component total unavailable: " + componentSummary.problem().orElseThrow());
    }

    DerivedPath derived = derivedCriticalPath(source);
    Optional<CriticalPaths.ObservedActionLowerBound> observedFallback =
        derived.result().filter(path -> path.outcome() == CriticalPath.Outcome.COMPUTED).isPresent()
            ? Optional.empty()
            : observedActionLowerBound;
    return new CriticalPaths(
        bazel,
        List.of(),
        componentSummary.count(),
        derived.result(),
        derived.unavailableReason(),
        observedFallback);
  }

  /**
   * One bounded page of Bazel's own critical-path components.
   *
   * <p>Rows are exposed only when the current PROFILE enrichment succeeded and its build id matches
   * this session. A retained row from a failed retry or another build is not a component of this
   * invocation. Invalid negative values are withheld rather than failing a lazy page.
   */
  public List<CriticalPaths.BazelComponent> bazelCriticalPathComponents(
      long firstOrdinal, int limit) throws SQLException {
    if (firstOrdinal < 0) {
      throw new IllegalArgumentException("first ordinal must be nonnegative: " + firstOrdinal);
    }
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive: " + limit);
    }
    if (!profileTrust().trusted()) {
      return List.of();
    }
    BazelComponentSummary summary = bazelComponentSummary(false);
    if (!summary.pageable()) {
      return List.of();
    }
    List<CriticalPaths.BazelComponent> components = new ArrayList<>(limit);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT ordinal, description, duration_micros FROM bazel_critical_path"
                + " WHERE ordinal >= ? ORDER BY ordinal LIMIT ?")) {
      statement.setLong(1, firstOrdinal);
      statement.setInt(2, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          components.add(
              new CriticalPaths.BazelComponent(rows.getInt(1), rows.getString(2), number(rows, 3)));
        }
      }
    }
    return List.copyOf(components);
  }

  /** Counts and validates profile components without retaining their descriptions. */
  private BazelComponentSummary bazelComponentSummary(boolean totalNeeded) throws SQLException {
    if (!totalNeeded && cachedBazelComponentValidation != null) {
      return cachedBazelComponentValidation;
    }
    BazelComponentSummary summary = readBazelComponentSummary(totalNeeded);
    cachedBazelComponentValidation = summary;
    return summary;
  }

  private BazelComponentSummary readBazelComponentSummary(boolean totalNeeded) throws SQLException {
    long count;
    long durations;
    OptionalLong minimumDuration;
    OptionalLong minimumOrdinal;
    OptionalLong maximumOrdinal;
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*), COUNT(duration_micros), MIN(duration_micros),"
                    + " MIN(ordinal), MAX(ordinal) FROM bazel_critical_path");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      count = rows.getLong(1);
      durations = rows.getLong(2);
      minimumDuration = number(rows, 3);
      minimumOrdinal = number(rows, 4);
      maximumOrdinal = number(rows, 5);
    }
    if (minimumOrdinal.isPresent()
        && (minimumOrdinal.getAsLong() < 0 || maximumOrdinal.orElseThrow() > Integer.MAX_VALUE)) {
      return BazelComponentSummary.invalid(
          count, "the trace profile reported an invalid critical-path component ordinal");
    }
    if (count > 0
        && (minimumOrdinal.orElseThrow() != 0 || maximumOrdinal.orElseThrow() != count - 1)) {
      return BazelComponentSummary.invalid(
          count, "the trace profile's critical-path component ordinals are not contiguous");
    }
    if (minimumDuration.isPresent() && minimumDuration.getAsLong() < 0) {
      return BazelComponentSummary.invalid(
          count, "the trace profile reported a negative critical-path component duration");
    }
    if (durations != count) {
      return BazelComponentSummary.incomplete(
          count, "the trace profile's critical-path component durations are incomplete");
    }
    if (!totalNeeded || count == 0) {
      return BazelComponentSummary.valid(count, OptionalLong.empty());
    }
    long total = 0;
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT duration_micros FROM bazel_critical_path ORDER BY ordinal");
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        try {
          total = Math.addExact(total, rows.getLong(1));
        } catch (ArithmeticException overflow) {
          return BazelComponentSummary.invalid(
              count,
              "the trace profile's critical-path component total overflowed"
                  + " a 64-bit duration");
        }
      }
    }
    return BazelComponentSummary.valid(count, OptionalLong.of(total));
  }

  private record BazelComponentSummary(
      long count, OptionalLong totalMicros, Optional<String> problem, boolean pageable) {

    static BazelComponentSummary empty() {
      return valid(0, OptionalLong.empty());
    }

    static BazelComponentSummary valid(long count, OptionalLong total) {
      return new BazelComponentSummary(count, total, Optional.empty(), true);
    }

    static BazelComponentSummary incomplete(long count, String problem) {
      return new BazelComponentSummary(count, OptionalLong.empty(), Optional.of(problem), true);
    }

    static BazelComponentSummary invalid(long count, String problem) {
      return new BazelComponentSummary(count, OptionalLong.empty(), Optional.of(problem), false);
    }
  }

  private DerivedPath derivedCriticalPath(CriticalPath.DurationSource source) throws SQLException {
    if (graph.isEmpty()) {
      return DerivedPath.unavailable("no imported action graph is available");
    }
    if (source == CriticalPath.DurationSource.NONE) {
      return DerivedPath.unavailable(
          "neither BEP actions nor execution-log attempts provided usable durations");
    }
    GraphQueries queries = graph.orElseThrow();
    Optional<GraphQueries.GraphSource> declaredSource =
        queries.sources().stream()
            .filter(candidate -> candidate.kind().equals("DECLARED_ACTIONS"))
            .findFirst();
    // A retained index can outlive a failed or mismatched re-import. It is
    // still a real graph, but it is not evidence about this invocation.
    // Withhold the derived path rather than compare stale dependencies to
    // Bazel's current schedule as if both described the same build.
    if (declaredSource.isEmpty()) {
      return DerivedPath.unavailable("no declared action-graph source was recorded");
    }
    GraphQueries.GraphSource recorded = declaredSource.orElseThrow();
    if (!recorded.isTrustworthy()) {
      String reason;
      if (!recorded.state().equals("SUCCEEDED")) {
        reason =
            "the declared action-graph import state is "
                + recorded.state()
                + recorded.error().map(error -> ": " + error).orElse("");
      } else if (!recorded.targetScope().permitsExactClaim()) {
        reason =
            recorded.targetScopeProblem().orElse("the action graph's target scope is unverified");
      } else if (!recorded.configurationMatch().permitsExactClaim()) {
        reason =
            recorded
                .mismatchDetail()
                .filter(detail -> !detail.isBlank())
                .orElse(
                    "the action graph's configuration match is "
                        + recorded.configurationMatch().name().toLowerCase());
      } else {
        reason =
            recorded
                .actionGraphCompletenessProblem()
                .orElse("the action graph's structural completeness is unverified");
      }
      return DerivedPath.unavailable(reason);
    }
    try {
      Optional<DerivedPath> result =
          queries.withIndexDescriptor(
              EdgeDerivation.DECLARED,
              true,
              (descriptor, graphIndex) ->
                  admittedCriticalPath(queries, descriptor, graphIndex, source));
      return result.orElseGet(
          () -> DerivedPath.unavailable("no declared action-graph index was built"));
    } catch (GraphResourceBudget.RefusedException refused) {
      return DerivedPath.unavailable(
          "the dependency-path computation was refused by the graph resource budget: "
              + readableMessage(refused));
    } catch (GraphSessionResources.SessionChangedException changed) {
      return DerivedPath.unavailable(readableMessage(changed));
    } catch (IOException unreadable) {
      // A memory-mapped index that will not open is a session problem,
      // not a metrics problem: every other number here is still correct,
      // so the derived path is absent and CriticalPaths says so rather
      // than the whole dashboard failing.
      return DerivedPath.unavailable(
          "the declared action-graph index could not be read: " + readableMessage(unreadable));
    }
  }

  private static DerivedPath admittedCriticalPath(
      GraphQueries queries,
      CsrFile.Descriptor descriptor,
      CsrGraph graphIndex,
      CriticalPath.DurationSource source)
      throws SQLException, IOException {
    String cacheSlot = "critical-path:" + source;
    String generationKey =
        descriptor.path() + ":" + Long.toUnsignedString(descriptor.header().checksum());
    Optional<DerivedPath> cached =
        queries.cachedSessionResult(cacheSlot, generationKey, DerivedPath.class);
    if (cached.isPresent()) {
      return cached.orElseThrow();
    }
    long retainedBytes = CriticalPath.retainedBytes(graphIndex.nodeCount());
    long scratchBytes =
        Math.subtractExact(CriticalPath.peakBytes(graphIndex.nodeCount()), retainedBytes);
    List<GraphResourceBudget.Reservation> reservations =
        queries
            .resourceBudget()
            .reserveAll(
                List.of(
                    new GraphResourceBudget.Request(retainedBytes, "retained critical-path result"),
                    new GraphResourceBudget.Request(scratchBytes, "critical-path scratch")));
    GraphResourceBudget.Reservation retained = reservations.get(0);
    try (GraphResourceBudget.Reservation scratch = reservations.get(1)) {
      long[] durations =
          queries.durationsByNodeIndex(
              source == CriticalPath.DurationSource.EXECUTION_ATTEMPT,
              CriticalPath.UNKNOWN_DURATION);
      if (graphIndex.nodeCount() != durations.length) {
        return DerivedPath.unavailable(
            "the declared action-graph index describes "
                + graphIndex.nodeCount()
                + " nodes, but the current graph has "
                + durations.length
                + "; the index is stale");
      }
      try {
        DerivedPath result =
            DerivedPath.available(CriticalPath.compute(graphIndex, durations, source));
        result =
            queries.retainSessionResult(
                cacheSlot, generationKey, DerivedPath.class, result, retained);
        retained = null;
        return result;
      } catch (IllegalArgumentException invalidDuration) {
        return DerivedPath.unavailable(
            "the dependency-path durations are invalid: " + readableMessage(invalidDuration));
      } catch (ArithmeticException overflow) {
        return DerivedPath.unavailable(
            "the dependency-path duration arithmetic overflowed: " + readableMessage(overflow));
      }
    } finally {
      if (retained != null) {
        retained.close();
      }
    }
  }

  private ProfileTrust profileTrust() throws SQLException {
    if (cachedProfileTrust == null) {
      cachedProfileTrust = readProfileTrust();
    }
    return cachedProfileTrust;
  }

  private ProfileTrust readProfileTrust() throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT et.state, pm.build_id_matches FROM enrichment_tasks et"
                + " LEFT JOIN profile_metadata pm ON pm.task_id = et.id"
                + " WHERE et.kind = 'PROFILE'")) {
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return ProfileTrust.untrusted(
              false,
              "no current trace-profile import was recorded, and BuildMetrics"
                  + " carries a critical-path time only from Bazel 9.2.0");
        }
        String state = rows.getString(1);
        if (!"SUCCEEDED".equals(state)) {
          return ProfileTrust.untrusted(true, "the current trace-profile import state is " + state);
        }
        int matches = rows.getInt(2);
        if (rows.wasNull()) {
          return ProfileTrust.untrusted(
              true, "the trace profile's build identity could not be verified");
        }
        if (matches != 1) {
          return ProfileTrust.untrusted(true, "the trace profile belongs to a different build");
        }
        return new ProfileTrust(true, true, "");
      }
    }
  }

  private static String joinReasons(String first, String second) {
    if (first == null) {
      return second;
    }
    if (second == null) {
      return first;
    }
    return first + "; " + second;
  }

  private record ProfileTrust(boolean recorded, boolean trusted, String reason) {

    static ProfileTrust untrusted(boolean recorded, String reason) {
      return new ProfileTrust(recorded, false, reason);
    }
  }

  private static String readableMessage(Throwable failure) {
    return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
  }

  private record DerivedPath(
      Optional<CriticalPath.Result> result, Optional<String> unavailableReason) {

    static DerivedPath available(CriticalPath.Result result) {
      return new DerivedPath(Optional.of(result), Optional.empty());
    }

    static DerivedPath unavailable(String reason) {
      return new DerivedPath(Optional.empty(), Optional.of(reason));
    }
  }

  private Coverage actionGraphCorrelation(long actions) throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT gs.state, gs.error_excerpt, COUNT(da.id), COUNT(da.action_id)"
                    + " FROM graph_sources gs"
                    + " LEFT JOIN declared_actions da ON da.source_id = gs.id"
                    + " WHERE gs.kind = 'DECLARED_ACTIONS' GROUP BY gs.id");
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return Coverage.unavailable(
            "Action-graph correlation",
            actions,
            DataSource.AQUERY,
            "no aquery output was imported for this session");
      }
      String state = rows.getString(1);
      String error = rows.getString(2);
      long declared = rows.getLong(3);
      long correlated = rows.getLong(4);
      if (!"SUCCEEDED".equals(state)) {
        return Coverage.unavailable(
            "Action-graph correlation",
            actions,
            DataSource.AQUERY,
            "the current aquery import state is "
                + state
                + (error == null || error.isBlank() ? "" : ": " + error));
      }
      if (declared == 0) {
        return Coverage.unavailable(
            "Action-graph correlation",
            actions,
            DataSource.AQUERY,
            "no aquery output was imported for this session");
      }
      return Coverage.of(
          "Action-graph correlation",
          correlated,
          declared,
          DataSource.AQUERY,
          "an action the graph declares and this invocation did not execute has nothing"
              + " to correlate with, which a cache hit produces by design");
    }
  }

  /**
   * Whether the imported action graph retained every dependency-bearing reference, kept distinct
   * from execution correlation. A cached action can lower correlation without making the graph
   * incomplete.
   */
  private Coverage actionGraphCompleteness(long actions) throws SQLException {
    String name = "Action-graph completeness";
    if (graph.isEmpty()) {
      return Coverage.unavailable(
          name, actions, DataSource.AQUERY, "no aquery output was imported for this session");
    }
    Optional<GraphQueries.GraphSource> source =
        graph.orElseThrow().sources().stream()
            .filter(candidate -> candidate.kind().equals("DECLARED_ACTIONS"))
            .findFirst();
    if (source.isEmpty()) {
      return Coverage.unavailable(
          name, actions, DataSource.AQUERY, "no declared action-graph source was recorded");
    }
    GraphQueries.GraphSource recorded = source.orElseThrow();
    long declared = recorded.declaredActions().orElse(actions);
    if (!recorded.state().equals("SUCCEEDED")) {
      return Coverage.unavailable(
          name,
          declared,
          DataSource.AQUERY,
          "the aquery import state is "
              + recorded.state()
              + recorded.error().map(error -> ": " + error).orElse(""));
    }
    Optional<String> problem = recorded.actionGraphCompletenessProblem();
    if (problem.isPresent()) {
      return Coverage.unavailable(name, declared, DataSource.AQUERY, problem.orElseThrow());
    }
    return Coverage.of(
        name,
        declared,
        declared,
        DataSource.AQUERY,
        "every artifact path and depset reference resolved");
  }

  private Coverage targetGraphCoverage() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM configured_target_nodes),"
                    + " (SELECT COUNT(*) FROM configured_targets)");
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      long nodes = rows.getLong(1);
      long configured = rows.getLong(2);
      if (nodes == 0) {
        return Coverage.unavailable(
            "Target-graph coverage",
            configured,
            DataSource.CQUERY,
            "no cquery output was imported for this session");
      }
      return Coverage.of(
          "Target-graph coverage",
          Math.min(nodes, configured),
          configured,
          DataSource.CQUERY,
          "cquery describes the targets it was asked about, which need not be every"
              + " target this invocation completed");
    }
  }

  private Coverage correlationCoverage() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*), SUM(CASE WHEN action_id IS NOT NULL THEN 1 ELSE 0 END)"
                    + " FROM action_attempts WHERE task_id = "
                    + SUCCEEDED_EXECUTION_LOG_TASK);
        ResultSet rows = statement.executeQuery()) {
      rows.next();
      long attempts = rows.getLong(1);
      long matched = rows.getLong(2);
      if (attempts == 0) {
        Optional<String> state = enrichmentTaskState("EXECUTION_LOG");
        String reason =
            state.isEmpty()
                ? "no execution log was imported for this session"
                : state.filter("SUCCEEDED"::equals).isPresent()
                    ? "the successful execution log contained no attempts"
                    : "the current execution-log import state is " + state.orElseThrow();
        return Coverage.unavailable("Correlation coverage", 0, DataSource.EXECUTION_LOG, reason);
      }
      return Coverage.of(
          "Correlation coverage",
          matched,
          attempts,
          DataSource.EXECUTION_LOG,
          "a spawn whose action the build event stream never published cannot be"
              + " matched to one, which is the normal result without"
              + " --build_event_publish_all_actions");
    }
  }

  private Optional<String> enrichmentTaskState(String kind) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT state FROM enrichment_tasks WHERE kind = ?")) {
      statement.setString(1, kind);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
      }
    }
  }

  /**
   * The actions worth examining, kept in bounded heaps during the scan.
   *
   * <p>One heap per criterion the rules care about. An action can be in several; the union is what
   * {@link #finish} returns, and it is at most {@code limit} times the number of criteria however
   * large the build is.
   */
  private static final class Candidates {

    private final List<TopN> heaps = new ArrayList<>();
    private final Map<Long, ActionRow> union = new LinkedHashMap<>();

    Candidates(int limit, Map<Long, OutputTotals> outputs) {
      heaps.add(new TopN(limit, row -> row.durationMicros().orElse(0)));
      heaps.add(new TopN(limit, ActionRow::attempts));
      heaps.add(new TopN(limit, row -> row.inputBytes().orElse(0)));
      heaps.add(new TopN(limit, row -> row.queueMicros().orElse(0)));
      heaps.add(
          new TopN(
              limit,
              row ->
                  row.networkMicros().orElse(0)
                      + row.uploadMicros().orElse(0)
                      + row.fetchMicros().orElse(0)));
      heaps.add(
          new TopN(
              limit,
              row -> {
                OutputTotals totals = outputs.get(row.id());
                return totals == null ? 0 : totals.bytes();
              }));
    }

    void offer(ActionRow row) {
      for (TopN heap : heaps) {
        heap.offer(row);
      }
    }

    List<ActionMetrics> finish(Map<Long, OutputTotals> outputs) {
      for (TopN heap : heaps) {
        for (ActionRow row : heap.rows()) {
          union.putIfAbsent(row.id(), row);
        }
      }
      List<ActionMetrics> metrics = new ArrayList<>(union.size());
      for (ActionRow row : union.values()) {
        metrics.add(toMetrics(row, outputs));
      }
      return List.copyOf(metrics);
    }
  }

  /** The highest-scoring {@code capacity} rows seen, by one measure. */
  private static final class TopN {

    private final int capacity;
    private final ToLongFunction<ActionRow> score;
    private final PriorityQueue<ActionRow> heap;

    TopN(int capacity, ToLongFunction<ActionRow> score) {
      this.capacity = capacity;
      this.score = score;
      this.heap =
          new PriorityQueue<>(
              capacity + 1,
              Comparator.comparingLong(score)
                  .thenComparing(Comparator.comparingLong(ActionRow::id).reversed()));
    }

    void offer(ActionRow row) {
      // A score of zero is "did not do this at all", and a heap full of
      // those would push out the actions the rule is looking for.
      if (score.applyAsLong(row) <= 0) {
        return;
      }
      heap.add(row);
      if (heap.size() > capacity) {
        heap.poll();
      }
    }

    List<ActionRow> rows() {
      return List.copyOf(heap);
    }
  }

  /** One action as the metric catalog sees it, without its graph properties. */
  private static ActionMetrics toMetrics(ActionRow row, Map<Long, OutputTotals> outputBytes) {
    OutputTotals outputs = outputBytes.get(row.id());
    return new ActionMetrics(
        row.id(),
        row.label(),
        row.mnemonic(),
        row.runner(),
        row.outcome(),
        row.startMicros(),
        row.endMicros(),
        row.durationMicros(),
        row.queueMicros(),
        row.setupMicros(),
        row.executionMicros(),
        row.networkMicros(),
        row.uploadMicros(),
        row.fetchMicros(),
        row.inputBytes(),
        row.inputFiles(),
        outputs == null ? OptionalLong.empty() : OptionalLong.of(outputs.bytes()),
        outputs == null ? OptionalLong.empty() : OptionalLong.of(outputs.files()),
        row.attempts(),
        switch (row.cacheState()) {
          case HIT -> ActionMetrics.CacheState.HIT;
          case MISS -> ActionMetrics.CacheState.MISS;
          case NOT_REPORTED -> ActionMetrics.CacheState.NOT_REPORTED;
        },
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        /* onDerivedCriticalPath= */ false,
        OptionalLong.empty(),
        OptionalLong.empty());
  }

  /**
   * The actions that produced the most bytes, from a bounded query.
   *
   * <p>Output size needs a join through the attempt's outputs to the artifacts, which would
   * multiply the rows of the main scan and break every sum in it. So it is its own query, ordered
   * and limited, and its results are merged into the candidates during the scan.
   */
  private Map<Long, OutputTotals> topOutputs(int limit) throws SQLException {
    Map<Long, OutputTotals> bytes = new LinkedHashMap<>();
    String sql =
        "SELECT att.action_id, SUM(art.size_bytes) AS total, COUNT(*)"
            + " FROM action_attempts att"
            + " JOIN attempt_outputs ao ON ao.attempt_id = att.id AND ao.produced = 1"
            + " JOIN artifacts art ON art.id = ao.artifact_id AND art.is_directory = 0"
            + " WHERE att.action_id IS NOT NULL AND art.size_bytes IS NOT NULL"
            + " AND att.task_id = "
            + SUCCEEDED_EXECUTION_LOG_TASK
            + " GROUP BY att.action_id ORDER BY total DESC LIMIT ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          bytes.put(rows.getLong(1), new OutputTotals(rows.getLong(2), rows.getLong(3)));
        }
      }
    }
    return bytes;
  }

  /**
   * What one action produced, over the outputs whose size was recorded.
   *
   * <p>{@code files} counts those same outputs and not every output the action declared, so it is a
   * floor rather than the output count — which is why {@link ActionMetrics#outputFiles()} is
   * optional and absent for every action this bounded query did not reach.
   */
  private record OutputTotals(long bytes, long files) {}

  /**
   * The heaviest actions on the derived critical path, resolved to records.
   *
   * <p>The path can be thousands of nodes long, and a finding names a handful. The weights used to
   * compute the path are already in hand, so the heaviest nodes are picked from those before
   * anything is looked up — which bounds both the lookups and the detail query.
   */
  private List<ActionMetrics> criticalPathActions(
      Optional<CriticalPath.Result> derived, int limit, CriticalPath.DurationSource durationSource)
      throws SQLException {
    if (derived.isEmpty()
        || graph.isEmpty()
        || derived.orElseThrow().outcome() != CriticalPath.Outcome.COMPUTED) {
      return List.of();
    }
    CriticalPath.Result path = derived.orElseThrow();
    if (limit < 1) {
      return List.of();
    }
    Comparator<PathAction> worstFirst =
        Comparator.comparingLong(PathAction::weightMicros)
            .thenComparing(Comparator.comparingInt(PathAction::nodeIndex).reversed());
    Comparator<PathAction> bestFirst =
        Comparator.comparingLong(PathAction::weightMicros)
            .reversed()
            .thenComparingInt(PathAction::nodeIndex);
    PriorityQueue<PathAction> heaviest = new PriorityQueue<>(limit, worstFirst);
    graph
        .orElseThrow()
        .forEachActionIdByNodeIndex(
            (node, actionId) -> {
              if (node < 0 || node >= path.scheduledNodes() || !path.isOnPath(node)) {
                return;
              }
              PathAction candidate =
                  new PathAction(
                      node, actionId, path.earliestFinishAt(node) - path.earliestStartAt(node));
              if (heaviest.size() < limit) {
                heaviest.offer(candidate);
              } else if (bestFirst.compare(candidate, heaviest.peek()) < 0) {
                heaviest.poll();
                heaviest.offer(candidate);
              }
            });
    if (heaviest.isEmpty()) {
      return List.of();
    }
    List<PathAction> ranked = new ArrayList<>(heaviest);
    ranked.sort(bestFirst);
    Map<Long, Integer> nodeByAction = new LinkedHashMap<>();
    for (PathAction candidate : ranked) {
      nodeByAction.putIfAbsent(candidate.actionId(), candidate.nodeIndex());
    }
    List<ActionMetrics> details = detail(nodeByAction.keySet(), durationSource);
    Map<Long, ActionMetrics> byAction =
        details.stream().collect(Collectors.toMap(ActionMetrics::actionId, action -> action));
    // detail() is free to return rows in SQLite's preferred order. Restore
    // the path-weight order chosen above so consumers do not accidentally
    // rank aggregate subprocess work as if it were a dependency weight.
    return nodeByAction.keySet().stream().map(byAction::get).filter(Objects::nonNull).toList();
  }

  /** One bounded candidate while selecting resolved actions from a dependency path. */
  private record PathAction(int nodeIndex, long actionId, long weightMicros) {}

  /** Re-reads a bounded set of actions with the full per-action detail. */
  private List<ActionMetrics> detail(
      Collection<Long> ids, CriticalPath.DurationSource durationSource) throws SQLException {
    if (ids.isEmpty()) {
      return List.of();
    }
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < ids.size(); i++) {
      placeholders.append(i == 0 ? "?" : ",?");
    }
    String durationColumns =
        switch (durationSource) {
          case BEP_ACTION -> BEP_DURATION;
          // ActionMetrics always describes the whole action. Keep its
          // execution-log fields aggregated across all attempts; the exact
          // conservative node weight remains on CriticalPath.Result and the
          // Critical Path table labels it separately.
          case EXECUTION_ATTEMPT -> ATTEMPT_DURATION;
          case NONE ->
              throw new IllegalArgumentException(
                  "a dependency path cannot use an absent duration source");
        };
    String sql =
        ACTION_ROWS_HEAD
            + durationColumns
            + ACTION_ROWS_TAIL
            + " WHERE act.id IN ("
            + placeholders
            + ")"
            + GROUP_BY_ACTION;
    List<ActionMetrics> out = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (long id : ids) {
        statement.setLong(index++, id);
      }
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          out.add(toMetrics(readRow(rows, durationSource), Map.of()));
        }
      }
    }
    return out;
  }

  /**
   * Attaches everything that is not in the actions table: the graph's fan-in and fan-out, the
   * derived schedule's slack, and the concurrency around the action.
   *
   * <p>Bounded to the candidate set, which is why it can afford a lookup per action. Anything
   * unavailable stays unavailable — a session with no graph gets actions whose fan-out is unknown,
   * not actions with no consumers.
   */
  private List<ActionMetrics> enrich(
      List<ActionMetrics> actions,
      Optional<CriticalPath.Result> derived,
      ConcurrencySweep.Spans spans)
      throws SQLException {
    if (actions.isEmpty()) {
      return actions;
    }
    if (graph.isPresent()) {
      try {
        GraphQueries queries = graph.orElseThrow();
        boolean sourceTrustworthy =
            queries.sources().stream()
                .filter(source -> source.kind().equals("DECLARED_ACTIONS"))
                .anyMatch(GraphQueries.GraphSource::isTrustworthy);
        if (sourceTrustworthy) {
          Optional<List<ActionMetrics>> enriched =
              queries.withIndexPair(
                  EdgeDerivation.DECLARED,
                  (forward, reverse) -> enrich(actions, derived, spans, queries, forward, reverse));
          if (enriched.isPresent()) {
            return enriched.orElseThrow();
          }
        }
      } catch (IOException unreadable) {
        // Fan-in and fan-out remain unknown. The rest of each action is still usable.
      }
    }
    return enrich(actions, derived, spans, graph.orElse(null), null, null);
  }

  private static List<ActionMetrics> enrich(
      List<ActionMetrics> actions,
      Optional<CriticalPath.Result> derived,
      ConcurrencySweep.Spans spans,
      GraphQueries queries,
      CsrGraph forward,
      CsrGraph reverse)
      throws SQLException {
    List<ActionMetrics> out = new ArrayList<>(actions.size());
    for (ActionMetrics action : actions) {
      OptionalLong consumers = OptionalLong.empty();
      OptionalLong dependencies = OptionalLong.empty();
      OptionalLong slack = OptionalLong.empty();
      boolean onPath = false;
      if (queries != null) {
        OptionalLong node = queries.nodeForAction(action.actionId());
        if (node.isPresent()) {
          int index = Math.toIntExact(node.getAsLong());
          if (forward != null && reverse != null) {
            consumers = OptionalLong.of(forward.degree(index));
            dependencies = OptionalLong.of(reverse.degree(index));
          }
          if (derived.isPresent()
              && derived.orElseThrow().outcome() == CriticalPath.Outcome.COMPUTED
              && index < derived.orElseThrow().scheduledNodes()) {
            slack = OptionalLong.of(derived.orElseThrow().slackAt(index));
            onPath = derived.orElseThrow().isOnPath(index);
          }
        }
      }
      out.add(
          new ActionMetrics(
              action.actionId(),
              action.label(),
              action.mnemonic(),
              action.runner(),
              action.outcome(),
              action.startMicros(),
              action.endMicros(),
              action.durationMicros(),
              action.queueMicros(),
              action.setupMicros(),
              action.executionMicros(),
              action.networkMicros(),
              action.uploadMicros(),
              action.fetchMicros(),
              action.inputBytes(),
              action.inputFiles(),
              action.outputBytes(),
              action.outputFiles(),
              action.attempts(),
              action.cacheState(),
              dependencies,
              consumers,
              slack,
              onPath,
              action.startMicros().isPresent()
                  ? OptionalLong.of(spans.activeAt(action.startMicros().getAsLong()))
                  : OptionalLong.empty(),
              action.endMicros().isPresent()
                  ? OptionalLong.of(spans.activeAt(action.endMicros().getAsLong() - 1))
                  : OptionalLong.empty()));
    }
    return List.copyOf(out);
  }

  /** The name a duration series goes under, which says what was measured. */
  private static String durationName(CriticalPath.DurationSource source) {
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

  private static Measured<Long> measured(OptionalLong value, DataSource source, String whyMissing) {
    return value.isPresent()
        ? Measured.of(value.getAsLong(), source)
        : Measured.unknown(source, Completeness.UNAVAILABLE, whyMissing);
  }

  /** Closes the connection and the graph reader this was given. */
  @Override
  public void close() throws SQLException {
    try {
      if (graph.isPresent()) {
        graph.orElseThrow().close();
      }
    } finally {
      connection.close();
    }
  }
}
