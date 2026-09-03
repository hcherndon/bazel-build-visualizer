package com.holtherndon.bazelviz.storage.metrics;

import com.holtherndon.bazelviz.analysis.ActionMetrics;
import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.FindingInputs;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One read of the whole metric catalog, taken at one instant.
 *
 * <h2>Why it travels as one object</h2>
 *
 * <p>Every number here came from the same scan, so the aggregate tables add up to the invocation
 * counts and the concurrency sweep describes the same actions the duration distribution does.
 * Handing them out separately would let a view show a cache-hit rate from one instant beside an
 * action count from another, which during a live capture is not hypothetical — the counts move
 * several times a second.
 *
 * @param durationSource which measurement fed every duration here, and therefore the concurrency
 *     sweep and the derived critical path too
 * @param spans retained so per-action start and completion concurrency can be answered without a
 *     second sweep; at Tier 3 this is two longs per timed action and is the largest thing in this
 *     record
 * @param candidates the extremes worth examining — the slowest, the largest, the most queued — and
 *     nothing in the middle, because a finding names a handful and a rule that needed all five
 *     million could not run
 * @param criticalPathActions the heaviest links of the derived chain, resolved to executed actions
 *     as far as the graph's mapping allows
 */
public record SessionMetrics(
    CriticalPath.DurationSource durationSource,
    InvocationMetrics invocation,
    Map<GroupAggregate.Dimension, GroupAggregate.Table> aggregates,
    ConcurrencySweep.Spans spans,
    ConcurrencySweep.Result concurrency,
    List<ActionMetrics> candidates,
    List<ActionMetrics> criticalPathActions) {

  public SessionMetrics {
    Objects.requireNonNull(durationSource, "durationSource");
    Objects.requireNonNull(invocation, "invocation");
    aggregates = Map.copyOf(aggregates);
    Objects.requireNonNull(spans, "spans");
    Objects.requireNonNull(concurrency, "concurrency");
    candidates = List.copyOf(candidates);
    criticalPathActions = List.copyOf(criticalPathActions);
  }

  /**
   * Everything the finding rules need, with the low-parallelism windows computed against this
   * session's own typical concurrency.
   *
   * <p>Assembled here rather than by the caller so the threshold the windows were found with is the
   * one the findings state. A caller that computed windows itself could pass a different divisor
   * than the rules print.
   */
  public FindingInputs findingInputs(FindingThresholds thresholds) {
    Objects.requireNonNull(thresholds, "thresholds");
    List<ConcurrencySweep.Window> windows = List.of();
    OptionalLong typical = typicalConcurrency();
    if (typical.isPresent()) {
      int threshold = (int) Math.max(1, typical.getAsLong() / thresholds.lowParallelismDivisor());
      windows = spans.windowsBelow(threshold, thresholds.lowParallelismMinimumMicros());
    }
    return new FindingInputs(
        invocation, aggregates, windows, candidates, criticalPathActions, thresholds);
  }

  /** One aggregate table, when it was asked for. */
  public Optional<GroupAggregate.Table> aggregate(GroupAggregate.Dimension dimension) {
    return Optional.ofNullable(aggregates.get(dimension));
  }

  // Start and completion concurrency are attached to each candidate action
  // by MetricQueries while it has the spans in hand; a second pair of
  // accessors here would be a second definition of "immediately before
  // completion" for the same number.

  /**
   * The typical concurrency this session ran at, for finding thresholds.
   *
   * <p>The average across the stretches something was running, rounded down. Plan 16.1 defines a
   * low-parallelism window as "significantly fewer active actions than the session's typical
   * concurrency", and this is that baseline: it excludes the idle gaps, so a build with one long
   * stall does not lower its own bar for noticing the stall.
   */
  public OptionalLong typicalConcurrency() {
    return concurrency.averageActiveWhileBusy().isPresent()
        ? OptionalLong.of((long) Math.floor(concurrency.averageActiveWhileBusy().orElseThrow()))
        : OptionalLong.empty();
  }
}
