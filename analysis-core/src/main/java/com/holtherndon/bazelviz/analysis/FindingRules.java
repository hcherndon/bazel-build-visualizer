package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.analysis.Finding.Confidence;
import com.holtherndon.bazelviz.analysis.Finding.Evidence;
import com.holtherndon.bazelviz.analysis.Finding.Link;
import com.holtherndon.bazelviz.analysis.Finding.MetricValue;
import com.holtherndon.bazelviz.analysis.Finding.Severity;
import com.holtherndon.bazelviz.core.measure.Measured;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.Function;

/**
 * Plan 16.1's finding rules, each one arithmetic over data already collected.
 *
 * <h2>What a rule is allowed to say</h2>
 *
 * <p>Every rule here reports a measurement and a threshold, and stops. None of them says why the
 * measurement is what it is, because a build that ran once cannot support that: the queue-dominated
 * rule can say that queue time was most of an attempt, and plan 16.1's own text for it says "do not
 * assume the cause is Bazel rather than remote infrastructure". {@link FindingLanguage} enforces
 * that at construction, so a rule that drifted into diagnosis would fail rather than ship.
 *
 * <h2>Severity and confidence are different questions</h2>
 *
 * <p>Severity is how much of the build the finding is about; confidence is how well the data
 * supports it. A rule fed by a source that covered a third of the actions produces a low-confidence
 * finding however large the effect it found, and a reader shown a single combined number could not
 * tell those apart.
 *
 * <h2>Rules that are not here</h2>
 *
 * <p>Every rule in plan 16.1 is implemented. Where the plan's wording invites a guess, the rule is
 * grounded in something Bazel actually said instead: the "non-cacheable or local-only
 * concentration" rule reads Bazel's own {@code cacheable} and {@code remotable} declarations from
 * the execution log rather than deciding what a runner string like {@code darwin-sandbox} implies,
 * because {@code spawn.proto} constrains that field to nothing and says it varies under the dynamic
 * strategy.
 */
public final class FindingRules {

  private FindingRules() {}

  /** How many actions or groups one finding names as evidence. */
  private static final int EVIDENCE_LIMIT = 5;

  /** Runs every rule and returns what they found, worst first. */
  public static List<Finding> run(FindingInputs inputs) {
    List<Finding> findings = new ArrayList<>();
    findings.addAll(longCriticalChain(inputs));
    findings.addAll(lowParallelismWindows(inputs));
    findings.addAll(slowTail(inputs));
    findings.addAll(manyTinyActions(inputs));
    findings.addAll(cacheMissConcentration(inputs));
    findings.addAll(nonCacheableConcentration(inputs));
    findings.addAll(highFanOut(inputs));
    findings.addAll(highInputVolume(inputs));
    findings.addAll(highOutputVolume(inputs));
    findings.addAll(queueDominated(inputs));
    findings.addAll(transferDominated(inputs));
    findings.addAll(repeatedAttempts(inputs));
    findings.addAll(graphMismatch(inputs));
    findings.sort(
        Comparator.comparing((Finding finding) -> finding.severity().ordinal())
            .reversed()
            .thenComparing(
                Comparator.comparingInt((Finding finding) -> finding.confidence().ordinal())
                    .reversed())
            .thenComparing(Finding::title));
    return List.copyOf(findings);
  }

  // --- rules ------------------------------------------------------------

  /** Plan 16.1: a long visualizer-computed dependency critical path. */
  private static List<Finding> longCriticalChain(FindingInputs inputs) {
    Optional<CriticalPath.Result> derived = inputs.invocation().criticalPaths().derived();
    if (derived.isEmpty() || derived.orElseThrow().outcome() != CriticalPath.Outcome.COMPUTED) {
      return List.of();
    }
    CriticalPath.Result path = derived.orElseThrow();
    OptionalLong wall = toOptionalLong(inputs.invocation().timing().totalWallMicros());
    if (wall.isEmpty() || wall.getAsLong() <= 0) {
      return List.of();
    }
    double share = (double) path.makespanMicros() / wall.getAsLong();
    if (share < inputs.thresholds().criticalPathShare()) {
      return List.of();
    }
    boolean structurallyComplete =
        actionGraphCompleteness(inputs).filter(Coverage::isComplete).isPresent();

    // MetricQueries already orders these by dependency-node path weight.
    // Do not re-rank execution-log sessions by aggregate subprocess work:
    // raced attempts are useful work data but are not elapsed path weight.
    List<ActionMetrics> contributors =
        inputs.criticalPathActions().stream().limit(EVIDENCE_LIMIT).toList();
    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : contributors) {
      // Plan 16.1 asks a chain finding to show slack. Membership already
      // came from the selected path; zero slack alone could also name an
      // equally long branch that was not selected.
      String timing =
          path.durationSource() == CriticalPath.DurationSource.EXECUTION_ATTEMPT
              ? "aggregate subprocess work " + MetricFormat.duration(action.durationMicros())
              : "action duration " + MetricFormat.duration(action.durationMicros());
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              action.mnemonic()
                  + ", "
                  + timing
                  + ", slack "
                  + MetricFormat.duration(action.slackMicros())));
    }
    if (evidence.isEmpty()) {
      // The path exists but none of its nodes could be resolved to an
      // executed action, which happens when the graph describes actions
      // this invocation did not run. The path itself is still the
      // supporting record.
      evidence.add(
          Evidence.group(
              path.displayName(), path.path().size() + " graph nodes, " + path.describe()));
    }

    return List.of(
        new Finding(
            "long-critical-chain",
            "The dependency chain accounts for "
                + MetricFormat.percent(share)
                + " of the build's wall time",
            share >= 0.8 ? Severity.HIGH : Severity.MEDIUM,
            path.isPartial() || !structurallyComplete ? Confidence.LOW : Confidence.HIGH,
            evidence,
            List.of(
                new MetricValue(
                    path.displayName(),
                    MetricFormat.duration(path.makespanMicros()),
                    "computed here from the imported action graph, weighted by "
                        + path.durationSource().pathWeightDescription()),
                new MetricValue(
                    "Actions on the chain",
                    MetricFormat.count(path.path().size()),
                    "computed here"),
                new MetricValue(
                    "Build wall time",
                    MetricFormat.duration(wall.getAsLong()),
                    "build event stream"),
                // The path is withheld unless every artifact path was
                // resolved. Correlation is a different measurement:
                // cached actions legitimately have no executed row.
                new MetricValue(
                    "Action-graph completeness",
                    actionGraphCompleteness(inputs)
                        .filter(Coverage::isComplete)
                        .map(ignored -> "confirmed")
                        .orElse(MetricFormat.UNKNOWN),
                    actionGraphCompleteness(inputs)
                        .map(Coverage::describe)
                        .orElse("no structural-completeness measurement" + " was recorded"))),
            "at least "
                + MetricFormat.percent(inputs.thresholds().criticalPathShare())
                + " of wall time",
            "A chain this long means dependency-linked actions cannot overlap, regardless of"
                + " available parallelism. Their individual durations can still change;"
                + " investigate the longest steps separately.",
            "This chain is what the dependency graph implies, not what Bazel scheduled — the"
                + " two are separate numbers and are shown separately."
                + (path.isPartial()
                    ? " "
                        + path.untimedNodes()
                        + " of "
                        + path.nodeCount()
                        + " actions in the graph have no measured duration and"
                        + " counted as instantaneous, so the chain is a lower"
                        + " bound."
                    : ""),
            "Open the graph on the longest link and look at what it waits for.",
            List.of(
                Link.derivedCriticalPath("Draw the dependency chain"),
                Link.to(Link.View.TIMELINE, "See where the chain sat on the clock")),
            false));
  }

  /** Plan 16.1: periods with significantly fewer active actions than usual. */
  private static List<Finding> lowParallelismWindows(FindingInputs inputs) {
    if (inputs.lowParallelismWindows().isEmpty()) {
      return List.of();
    }
    Optional<ConcurrencySweep.Result> sweep = inputs.invocation().concurrency();
    if (sweep.isEmpty()) {
      return List.of();
    }
    ConcurrencySweep.Result concurrency = sweep.orElseThrow();
    List<ConcurrencySweep.Window> worst =
        inputs.lowParallelismWindows().stream()
            .sorted(Comparator.comparingLong(ConcurrencySweep.Window::durationMicros).reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();
    // The sweep's own histogram, not a sum over the reported windows: a
    // window shorter than the minimum is filtered out of the list and is
    // still time the build spent below the threshold.
    int threshold = worst.getFirst().peakActive() + 1;
    long idleTotal = concurrency.microsBelow(threshold);
    double share =
        concurrency.windowMicros() > 0 ? (double) idleTotal / concurrency.windowMicros() : 0;

    List<Evidence> evidence = new ArrayList<>();
    for (ConcurrencySweep.Window window : worst) {
      evidence.add(
          Evidence.window(
              window.startMicros(),
              MetricFormat.duration(window.durationMicros())
                  + " from "
                  + MetricFormat.duration(window.startMicros() - concurrency.windowStartMicros())
                  + " in",
              "at most " + window.peakActive() + " actions running"));
    }

    return List.of(
        new Finding(
            "low-parallelism-window",
            MetricFormat.count(inputs.lowParallelismWindows().size())
                + " stretches ran below the build's usual concurrency, "
                + MetricFormat.percent(share)
                + " of the elapsed time",
            share >= 0.3 ? Severity.HIGH : Severity.MEDIUM,
            concurrency.isPartial() ? Confidence.LOW : Confidence.HIGH,
            evidence,
            List.of(
                new MetricValue(
                    "Peak concurrency",
                    MetricFormat.count(concurrency.peakActive()),
                    "computed here from observed action spans"),
                new MetricValue(
                    "Average while busy",
                    MetricFormat.ratio(concurrency.averageActiveWhileBusy()),
                    "computed here from observed action spans"),
                new MetricValue(
                    "Time below the threshold", MetricFormat.duration(idleTotal), "computed here")),
            "below the session's typical concurrency divided by "
                + inputs.thresholds().lowParallelismDivisor()
                + ", lasting at least "
                + MetricFormat.duration(inputs.thresholds().lowParallelismMinimumMicros()),
            "A build that spends this long with few actions running may be waiting on a"
                + " dependency chain rather than on the machine; the graph is where to"
                + " see which one.",
            "Concurrency here is measured from action spans, so an action nothing timed is"
                + " not counted as running. "
                + (concurrency.untimedSpans() > 0
                    ? concurrency.untimedSpans()
                        + " actions have no observed timing,"
                        + " so some of these stretches may have been busier than"
                        + " they look."
                    : "Every action in this build was timed."),
            "Open the timeline at the longest stretch and see what was still running.",
            List.of(
                Link.to(Link.View.TIMELINE, "Open the timeline at these stretches"),
                Link.to(Link.View.GRAPH, "Look for the chain that was blocking")),
            false));
  }

  /** Plan 16.1: a few late actions that extend completion. */
  private static List<Finding> slowTail(FindingInputs inputs) {
    Optional<ConcurrencySweep.Result> sweep = inputs.invocation().concurrency();
    if (sweep.isEmpty() || inputs.lowParallelismWindows().isEmpty()) {
      return List.of();
    }
    ConcurrencySweep.Result concurrency = sweep.orElseThrow();
    Optional<ConcurrencySweep.Window> trailing =
        inputs.lowParallelismWindows().stream()
            .filter(window -> window.endMicros() == concurrency.windowEndMicros())
            .findFirst();
    if (trailing.isEmpty() || concurrency.windowMicros() <= 0) {
      return List.of();
    }
    ConcurrencySweep.Window tail = trailing.orElseThrow();
    double share = (double) tail.durationMicros() / concurrency.windowMicros();
    if (share < 0.05) {
      return List.of();
    }
    List<ActionMetrics> stragglers =
        inputs.candidates().stream()
            .filter(
                action ->
                    action.endMicros().isPresent()
                        && action.endMicros().getAsLong() > tail.startMicros())
            .sorted(
                Comparator.comparingLong(
                        (ActionMetrics action) -> action.durationMicros().orElse(0))
                    .reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();

    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : stragglers) {
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              action.mnemonic()
                  + ", "
                  + MetricFormat.duration(action.durationMicros())
                  + ", finished in the tail"));
    }
    if (evidence.isEmpty()) {
      evidence.add(
          Evidence.window(
              tail.startMicros(),
              "the last " + MetricFormat.duration(tail.durationMicros()),
              "at most "
                  + tail.peakActive()
                  + " actions running, and none of the"
                  + " candidates examined ends inside it"));
    }

    List<Finding.Link> links = new ArrayList<>();
    links.add(Link.to(Link.View.TIMELINE, "See the tail on the timeline"));
    if (!stragglers.isEmpty()) {
      // The action itself, not a re-sorted table: the rule already worked
      // out which one finished last, and sending the reader back to redo
      // that would be handing over the question rather than the answer.
      links.add(
          Link.focused(
              Link.View.ACTIONS,
              "Open the action that finished last",
              stragglers.getFirst().actionId()));
    }
    return List.of(
        new Finding(
            "slow-tail",
            "The last "
                + MetricFormat.duration(tail.durationMicros())
                + " of the build ran "
                + tail.peakActive()
                + " actions or fewer",
            share >= 0.2 ? Severity.HIGH : Severity.MEDIUM,
            concurrency.isPartial() ? Confidence.LOW : Confidence.MEDIUM,
            evidence,
            List.of(
                new MetricValue(
                    "Tail length", MetricFormat.duration(tail.durationMicros()), "computed here"),
                new MetricValue(
                    "Share of elapsed time", MetricFormat.percent(share), "computed here"),
                new MetricValue(
                    "Actions running in the tail",
                    MetricFormat.count(tail.peakActive()),
                    "computed here")),
            "a trailing stretch of at least 5% of elapsed time below the concurrency"
                + " threshold",
            "While a handful of actions finish, the rest of the machine may be idle;"
                + " shortening or splitting the last ones is where a tail like this"
                + " is worth investigating.",
            "An action with no observed timing is not counted as running, so a tail can"
                + " look emptier than it was.",
            "Look at what was still running while the rest of the machine was idle.",
            links,
            false));
  }

  /** Plan 16.1: a large count of short actions whose aggregate overhead is material. */
  private static List<Finding> manyTinyActions(FindingInputs inputs) {
    List<Finding> findings = new ArrayList<>();
    for (GroupAggregate group : inputs.table(GroupAggregate.Dimension.MNEMONIC).groups()) {
      if (group.actions() < inputs.thresholds().tinyActionCount()) {
        continue;
      }
      Optional<QuantileSketch.Bounds> median = group.duration().median();
      if (median.isEmpty()
          || median.orElseThrow().high() > inputs.thresholds().tinyActionMicros()) {
        continue;
      }
      findings.add(
          new Finding(
              "many-tiny-actions",
              MetricFormat.count(group.actions())
                  + " "
                  + group.displayKey()
                  + " actions each finish in under "
                  + MetricFormat.duration(inputs.thresholds().tinyActionMicros()),
              Severity.LOW,
              confidenceFrom(group.duration()),
              List.of(Evidence.group(group.displayKey(), group.describe())),
              List.of(
                  new MetricValue(
                      "Actions", MetricFormat.count(group.actions()), "counted in this session"),
                  MetricValue.from(group.duration(), "median " + MetricFormat.bounds(median)),
                  new MetricValue(
                      "Observed work",
                      MetricFormat.duration(group.duration().observedSum()),
                      group.duration().describe())),
              "at least "
                  + MetricFormat.count(inputs.thresholds().tinyActionCount())
                  + " actions with a median under "
                  + MetricFormat.duration(inputs.thresholds().tinyActionMicros()),
              "Per-action overhead — scheduling, sandbox setup, cache lookups — is paid"
                  + " once per action, so a large population of short ones may cost"
                  + " more in overhead than in work.",
              "The durations here are what was measured of each action, which does not"
                  + " include whatever Bazel spent deciding to run it. The overhead"
                  + " this finding is about is therefore not in the numbers shown.",
              "Look at whether these can be batched into fewer, larger actions.",
              List.of(
                  Link.mnemonic(
                      Link.View.ACTIONS,
                      "The " + group.displayKey() + " actions",
                      group.displayKey())),
              false));
    }
    return findings;
  }

  /** Plan 16.1: groups with a high miss rate, gated on cache-state coverage. */
  private static List<Finding> cacheMissConcentration(FindingInputs inputs) {
    List<Finding> findings = new ArrayList<>();
    for (GroupAggregate group : inputs.table(GroupAggregate.Dimension.MNEMONIC).groups()) {
      long known = group.cacheHits() + group.cacheMisses();
      if (known < inputs.thresholds().minimumGroupActions()) {
        continue;
      }
      double coverage = (double) known / group.actions();
      if (coverage < inputs.thresholds().cacheCoverageFloor()) {
        continue;
      }
      double missRate = (double) group.cacheMisses() / known;
      if (missRate <= inputs.thresholds().cacheMissRate()) {
        continue;
      }
      findings.add(
          new Finding(
              "cache-miss-concentration",
              group.displayKey()
                  + " missed the cache on "
                  + MetricFormat.percent(missRate)
                  + " of the actions that reported",
              missRate >= 0.9 ? Severity.MEDIUM : Severity.LOW,
              coverage >= 0.95 ? Confidence.HIGH : Confidence.MEDIUM,
              List.of(
                  Evidence.group(group.displayKey(), group.describe()),
                  Evidence.coverage(group.cacheCoverage())),
              List.of(
                  new MetricValue(
                      "Cache misses",
                      MetricFormat.count(group.cacheMisses())
                          + " of "
                          + MetricFormat.count(known)
                          + " reporting",
                      "execution log"),
                  new MetricValue(
                      "Cache-state coverage",
                      MetricFormat.percent(coverage),
                      group.cacheCoverage().describe()),
                  new MetricValue(
                      "Observed work",
                      MetricFormat.duration(group.duration().observedSum()),
                      group.duration().describe())),
              "a miss rate above "
                  + MetricFormat.percent(inputs.thresholds().cacheMissRate())
                  + " over at least "
                  + MetricFormat.count(inputs.thresholds().minimumGroupActions())
                  + " actions reporting a cache state, with at least "
                  + MetricFormat.percent(inputs.thresholds().cacheCoverageFloor())
                  + " coverage",
              "Actions that miss the cache do their work again; a group that misses this"
                  + " often may be one whose inputs change more than expected, which"
                  + " is worth investigating before anything is changed.",
              "The rate is over the actions that reported a cache state, never over all"
                  + " of them: an action with no execution-log record is not a miss."
                  + " A first build of a clean workspace misses everything by design.",
              "Compare the action keys of two runs of one of these actions to see what"
                  + " changed between them.",
              List.of(
                  Link.mnemonic(
                      Link.View.ACTIONS,
                      "The " + group.displayKey() + " actions that missed",
                      group.displayKey())),
              false));
    }
    return findings;
  }

  /** Plan 16.1: mnemonics that prevent expected cache or remote behaviour. */
  private static List<Finding> nonCacheableConcentration(FindingInputs inputs) {
    List<Finding> findings = new ArrayList<>();
    for (GroupAggregate group : inputs.table(GroupAggregate.Dimension.MNEMONIC).groups()) {
      if (group.actions() < inputs.thresholds().minimumGroupActions()) {
        continue;
      }
      long blocked = Math.max(group.declaredNotCacheable(), group.declaredNotRemotable());
      if (blocked < group.actions() / 2) {
        continue;
      }
      findings.add(
          new Finding(
              "non-cacheable-concentration",
              "Bazel marked "
                  + MetricFormat.count(blocked)
                  + " of "
                  + MetricFormat.count(group.actions())
                  + " "
                  + group.displayKey()
                  + " actions as uncacheable or unable to run remotely",
              Severity.LOW,
              Confidence.HIGH,
              List.of(Evidence.group(group.displayKey(), group.describe())),
              List.of(
                  new MetricValue(
                      "Declared uncacheable",
                      MetricFormat.count(group.declaredNotCacheable()),
                      "execution log, Bazel's own declaration"),
                  new MetricValue(
                      "Declared not remotable",
                      MetricFormat.count(group.declaredNotRemotable()),
                      "execution log, Bazel's own declaration"),
                  new MetricValue(
                      "Actions", MetricFormat.count(group.actions()), "counted in this session")),
              "at least half a group of "
                  + MetricFormat.count(inputs.thresholds().minimumGroupActions())
                  + " actions or more carrying either declaration",
              "These actions cannot benefit from the cache or from remote execution"
                  + " however the build is configured, so they may account for work"
                  + " that a remote setup was expected to remove.",
              "This is Bazel's own declaration read verbatim, not an inference from a"
                  + " runner name. A rule that sets these deliberately — because its"
                  + " output is genuinely not reproducible — is behaving correctly.",
              "Find which rule sets execution requirements on these actions.",
              List.of(
                  Link.mnemonic(
                      Link.View.ACTIONS,
                      "The " + group.displayKey() + " actions",
                      group.displayKey())),
              false));
    }
    return findings;
  }

  /** Plan 16.1: an action with many direct or transitive consumers. */
  private static List<Finding> highFanOut(FindingInputs inputs) {
    List<ActionMetrics> wide =
        inputs.candidates().stream()
            .filter(
                action -> action.directConsumers().orElse(0) >= inputs.thresholds().highFanOut())
            .sorted(
                Comparator.comparingLong(
                        (ActionMetrics action) -> action.directConsumers().orElse(0))
                    .reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();
    if (wide.isEmpty()) {
      return List.of();
    }
    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : wide) {
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              MetricFormat.count(action.directConsumers().orElse(0))
                  + " actions consume its outputs"));
    }
    ActionMetrics widest = wide.getFirst();
    return List.of(
        new Finding(
            "high-fan-out",
            MetricFormat.count(widest.directConsumers().orElse(0))
                + " actions depend directly on "
                + widest.displayLabel(),
            Severity.MEDIUM,
            Confidence.HIGH,
            evidence,
            List.of(
                new MetricValue(
                    "Direct consumers",
                    MetricFormat.count(widest.directConsumers().orElse(0)),
                    "imported action graph"),
                new MetricValue(
                    "Direct dependencies",
                    MetricFormat.count(widest.directDependencies()),
                    "imported action graph"),
                new MetricValue(
                    "On the derived critical path",
                    widest.onDerivedCriticalPath() ? "yes" : "no",
                    "computed here from the imported action graph"),
                new MetricValue(
                    "Its own duration",
                    MetricFormat.duration(widest.durationMicros()),
                    "the session's duration source")),
            "at least "
                + MetricFormat.count(inputs.thresholds().highFanOut())
                + " direct consumers",
            "Everything downstream waits for this action, so making it faster or making it"
                + " cache may unblock substantial work — which is worth investigating"
                + " before assuming it would.",
            "Direct consumers, not transitive: the number of actions eventually affected is"
                + " larger and is not computed here, because a complete transitive"
                + " closure is exactly what this application does not build. Fan-out is"
                + " a property of the graph, and says nothing on its own about how long"
                + " anything waited.",
            "Open this action in the graph and look at what it feeds.",
            List.of(Link.focused(Link.View.GRAPH, "Show what depends on it", widest.actionId())),
            false));
  }

  /** Plan 16.1: actions with large known unique inputs. */
  private static List<Finding> highInputVolume(FindingInputs inputs) {
    return volumeFinding(
        inputs,
        "high-input-volume",
        action -> action.inputBytes(),
        inputs.thresholds().highInputBytes(),
        "reads",
        "Input bytes",
        "Large inputs may mean time spent staging or uploading them before any work"
            + " starts; the timing breakdown for one of these actions is where to"
            + " look next.",
        "Only inputs whose size the execution log reported are counted, so this is a"
            + " lower bound and an action with unmeasured inputs can be larger than"
            + " it appears.");
  }

  /** Plan 16.1: expensive output production or transfer candidates. */
  private static List<Finding> highOutputVolume(FindingInputs inputs) {
    return volumeFinding(
        inputs,
        "high-output-volume",
        action -> action.outputBytes(),
        inputs.thresholds().highOutputBytes(),
        "produces",
        "Output bytes",
        "Large outputs may cost time to write and to upload, and every consumer pays to"
            + " fetch them; whether that matters here is worth investigating.",
        "Only outputs with a recorded size are counted, and a file that only ever"
            + " existed on a remote executor has none, so this is a lower bound.");
  }

  private static List<Finding> volumeFinding(
      FindingInputs inputs,
      String ruleId,
      Function<ActionMetrics, OptionalLong> measure,
      long threshold,
      String verb,
      String metricName,
      String why,
      String caveat) {
    List<ActionMetrics> heavy =
        inputs.candidates().stream()
            .filter(action -> measure.apply(action).orElse(0) >= threshold)
            .sorted(
                Comparator.comparingLong((ActionMetrics action) -> measure.apply(action).orElse(0))
                    .reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();
    if (heavy.isEmpty()) {
      return List.of();
    }
    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : heavy) {
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              action.mnemonic() + ", " + verb + " " + MetricFormat.bytes(measure.apply(action))));
    }
    ActionMetrics heaviest = heavy.getFirst();
    return List.of(
        new Finding(
            ruleId,
            heaviest.displayLabel()
                + " "
                + verb
                + " "
                + MetricFormat.bytes(measure.apply(heaviest)),
            Severity.LOW,
            Confidence.MEDIUM,
            evidence,
            List.of(
                new MetricValue(
                    metricName, MetricFormat.bytes(measure.apply(heaviest)), "execution log"),
                new MetricValue(
                    "Its own duration",
                    MetricFormat.duration(heaviest.durationMicros()),
                    "the session's duration source")),
            "at least " + MetricFormat.bytes(threshold),
            why,
            caveat,
            "Open the action and compare its transfer time against its execution time.",
            List.of(Link.focused(Link.View.ACTIONS, "Open this action", heaviest.actionId())),
            false));
  }

  /** Plan 16.1: queue time as a large fraction of total attempt time. */
  private static List<Finding> queueDominated(FindingInputs inputs) {
    List<ActionMetrics> waiting =
        inputs.candidates().stream()
            .filter(
                action ->
                    action.queueFraction().orElse(0)
                        >= inputs.thresholds().queueDominatedFraction())
            .sorted(
                Comparator.comparingDouble(
                        (ActionMetrics action) -> action.queueFraction().orElse(0))
                    .reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();
    if (waiting.isEmpty()) {
      return List.of();
    }
    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : waiting) {
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              MetricFormat.percent(action.queueFraction())
                  + " of "
                  + MetricFormat.duration(action.durationMicros())
                  + " was queue time"));
    }
    ActionMetrics worst = waiting.getFirst();
    return List.of(
        new Finding(
            "queue-dominated",
            MetricFormat.count(waiting.size())
                + " actions spent most of their time queued,"
                + " up to "
                + MetricFormat.percent(worst.queueFraction()),
            Severity.MEDIUM,
            Confidence.HIGH,
            evidence,
            List.of(
                new MetricValue(
                    "Queue time", MetricFormat.duration(worst.queueMicros()), "execution log"),
                new MetricValue(
                    "Attempt time", MetricFormat.duration(worst.durationMicros()), "execution log"),
                new MetricValue(
                    "Runner",
                    worst.runner() == null ? MetricFormat.UNKNOWN : worst.runner(),
                    "execution log, Bazel's own string"),
                // Plan 15.1's start concurrency: how loaded the build
                // was when this action began waiting.
                new MetricValue(
                    "Actions running when it started",
                    MetricFormat.count(worst.startConcurrency()),
                    "computed here from observed action spans"),
                new MetricValue(
                    "Actions running as it finished",
                    MetricFormat.count(worst.completionConcurrency()),
                    "computed here from observed action spans")),
            "queue time at or above "
                + MetricFormat.percent(inputs.thresholds().queueDominatedFraction())
                + " of the attempt",
            "An action that waits longer than it works may be limited by whatever it was"
                + " waiting for rather than by the work itself.",
            "Queue time is measured by Bazel and reported per spawn. It may be a remote"
                + " executor's queue, a local jobs limit, or a worker pool, and this"
                + " measurement does not distinguish between them.",
            "Compare the queue time of these actions against the runner each of them used.",
            List.of(Link.focused(Link.View.ACTIONS, "Open the worst one", worst.actionId())),
            false));
  }

  /** Plan 16.1: upload, download or network time dominating execution. */
  private static List<Finding> transferDominated(FindingInputs inputs) {
    List<ActionMetrics> moving =
        inputs.candidates().stream()
            .filter(
                action ->
                    action.transferFraction().orElse(0)
                        >= inputs.thresholds().transferDominatedFraction())
            .sorted(
                Comparator.comparingDouble(
                        (ActionMetrics action) -> action.transferFraction().orElse(0))
                    .reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();
    if (moving.isEmpty()) {
      return List.of();
    }
    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : moving) {
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              MetricFormat.percent(action.transferFraction())
                  + " of its time was network, upload or fetch"));
    }
    ActionMetrics worst = moving.getFirst();
    return List.of(
        new Finding(
            "transfer-dominated",
            MetricFormat.count(moving.size())
                + " actions spent most of their time moving"
                + " bytes rather than running",
            Severity.MEDIUM,
            Confidence.HIGH,
            evidence,
            List.of(
                new MetricValue(
                    "Network time", MetricFormat.duration(worst.networkMicros()), "execution log"),
                new MetricValue(
                    "Upload time", MetricFormat.duration(worst.uploadMicros()), "execution log"),
                new MetricValue(
                    "Fetch time", MetricFormat.duration(worst.fetchMicros()), "execution log"),
                new MetricValue(
                    "Attempt time", MetricFormat.duration(worst.durationMicros()), "execution log"),
                new MetricValue(
                    "Unaccounted",
                    MetricFormat.duration(worst.unaccountedMicros()),
                    "the difference between Bazel's total and its components,"
                        + " which it measures separately")),
            "network, upload and fetch together at or above "
                + MetricFormat.percent(inputs.thresholds().transferDominatedFraction())
                + " of the attempt",
            "Actions that move more than they compute may be limited by the link to the"
                + " remote cache or executor rather than by the work.",
            "Bazel measures the components separately from the total, so they do not sum to"
                + " it and the difference is unaccounted rather than execution time. An"
                + " action that ran locally reports no transfer times at all, and is"
                + " absent here rather than at zero.",
            "Compare these actions' input and output sizes against their transfer times.",
            List.of(Link.focused(Link.View.ACTIONS, "Open the worst one", worst.actionId())),
            false));
  }

  /** Plan 16.1: retries, local/remote races, or repeated failures. */
  private static List<Finding> repeatedAttempts(FindingInputs inputs) {
    List<ActionMetrics> repeated =
        inputs.candidates().stream()
            .filter(action -> action.attempts() >= inputs.thresholds().repeatedAttempts())
            .sorted(Comparator.comparingLong(ActionMetrics::attempts).reversed())
            .limit(EVIDENCE_LIMIT)
            .toList();
    if (repeated.isEmpty()) {
      return List.of();
    }
    List<Evidence> evidence = new ArrayList<>();
    for (ActionMetrics action : repeated) {
      evidence.add(
          Evidence.action(
              action.actionId(),
              action.displayLabel(),
              action.attempts()
                  + " attempts, runner "
                  + (action.runner() == null ? "not the same for all of them" : action.runner())));
    }
    ActionMetrics most = repeated.getFirst();
    return List.of(
        new Finding(
            "repeated-attempts",
            MetricFormat.count(repeated.size())
                + " actions ran more than once, up to "
                + most.attempts()
                + " times",
            Severity.LOW,
            Confidence.HIGH,
            evidence,
            List.of(
                new MetricValue(
                    "Attempts",
                    MetricFormat.count(most.attempts()),
                    "execution log, one record per spawn"),
                new MetricValue(
                    "Attempts beyond the first",
                    MetricFormat.count(most.retries()),
                    "execution log, one record per spawn"),
                new MetricValue(
                    "Its own duration",
                    MetricFormat.duration(most.durationMicros()),
                    "execution log")),
            "at least " + inputs.thresholds().repeatedAttempts() + " attempts on one action",
            "Repeated spawns may be retries after a failure, or they may be the dynamic"
                + " strategy racing a local against a remote one; the runners of each"
                + " attempt are what tells them apart.",
            "Two attempts is not the same as one retry. Under the dynamic strategy both"
                + " spawns are recorded and one is cancelled, and Bazel's own"
                + " attemptCount field means something different again.",
            "Open the attempts of one of these and compare their runners and exit codes.",
            List.of(Link.focused(Link.View.ACTIONS, "Open its attempts", most.actionId())),
            false));
  }

  /** Plan 16.1: the action graph cannot be reliably correlated with execution. */
  private static List<Finding> graphMismatch(FindingInputs inputs) {
    Optional<Coverage> correlation =
        inputs.invocation().coverage().find("Action-graph correlation");
    if (correlation.isEmpty()) {
      return List.of();
    }
    Coverage graph = correlation.orElseThrow();
    if (graph.total() == 0 || graph.covered() == 0) {
      // No graph at all is not a mismatch; it is an enrichment that was
      // not asked for, and the coverage panel already says so.
      return List.of();
    }
    OptionalDouble fraction = graph.fraction();
    if (fraction.isEmpty()
        || fraction.getAsDouble() >= inputs.thresholds().graphCorrelationFloor()) {
      return List.of();
    }
    return List.of(
        new Finding(
            "graph-mismatch",
            "Only "
                + MetricFormat.percent(fraction)
                + " of the imported action graph matches an executed action",
            Severity.HIGH,
            Confidence.HIGH,
            List.of(Evidence.coverage(graph)),
            List.of(
                new MetricValue(
                    "Action-graph correlation", MetricFormat.percent(fraction), graph.describe()),
                new MetricValue("Graph actions", MetricFormat.count(graph.total()), "aquery"),
                new MetricValue(
                    "Matched to an executed action",
                    MetricFormat.count(graph.covered()),
                    "computed here")),
            "below "
                + MetricFormat.percent(inputs.thresholds().graphCorrelationFloor())
                + " of graph actions correlated",
            "Anything computed from the graph — the dependency chain, fan-out, slack — may"
                + " describe actions this invocation did not run, so those numbers are"
                + " worth treating as being about the graph rather than about this"
                + " build.",
            "A gap here is expected and not necessarily wrong: an action the graph declares"
                + " and the build served from cache never executed, so it has nothing to"
                + " correlate with. A configuration mismatch between the invocation and"
                + " the aquery would produce the same shortfall for a different reason.",
            "Check whether the aquery ran with the same configuration as the build.",
            List.of(Link.to(Link.View.COVERAGE, "See what each source covered")),
            false));
  }

  // --- helpers ----------------------------------------------------------

  private static Optional<Coverage> actionGraphCompleteness(FindingInputs inputs) {
    return inputs.invocation().coverage().find("Action-graph completeness");
  }

  private static Confidence confidenceFrom(MetricSeries series) {
    double coverage = series.coverage().orElse(0);
    if (coverage >= 0.95 && series.completeness().isComplete()) {
      return Confidence.HIGH;
    }
    return coverage >= 0.5 ? Confidence.MEDIUM : Confidence.LOW;
  }

  private static OptionalLong toOptionalLong(Measured<Long> measured) {
    return measured.value().map(OptionalLong::of).orElseGet(OptionalLong::empty);
  }
}
