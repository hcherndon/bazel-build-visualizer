package com.holtherndon.bazelviz.analysis;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the rules need, and nothing they have to go and fetch.
 *
 * <h2>Why the rules take a record instead of a database</h2>
 *
 * <p>A rule that could query would be a rule whose cost depends on what it decides to ask, and
 * thirteen of them running against a Tier 3 session would be thirteen unpredictable scans.
 * Everything here comes from the single scan {@code MetricQueries.collect} already performs plus a
 * handful of bounded top-N queries, so running every rule is arithmetic over data already in memory
 * — which is also what lets every rule be tested with a literal.
 *
 * @param candidates a bounded set of actions worth examining, the top few by each of the criteria
 *     the rules care about. Not every action: a rule that needed all five million would be a rule
 *     that cannot run on the sessions this application exists for.
 * @param criticalPathActions a bounded set of the derived path's heaviest resolved actions, ordered
 *     by descending dependency-node path weight; unmatched graph nodes are absent
 * @param lowParallelismWindows stretches where few actions were running, computed against this
 *     session's own typical concurrency
 */
public record FindingInputs(
    InvocationMetrics invocation,
    Map<GroupAggregate.Dimension, GroupAggregate.Table> aggregates,
    List<ConcurrencySweep.Window> lowParallelismWindows,
    List<ActionMetrics> candidates,
    List<ActionMetrics> criticalPathActions,
    FindingThresholds thresholds) {

  public FindingInputs {
    Objects.requireNonNull(invocation, "invocation");
    aggregates = Map.copyOf(aggregates);
    lowParallelismWindows = List.copyOf(lowParallelismWindows);
    candidates = List.copyOf(candidates);
    criticalPathActions = List.copyOf(criticalPathActions);
    Objects.requireNonNull(thresholds, "thresholds");
  }

  /** One aggregate table, or an empty one when that dimension was not asked for. */
  public GroupAggregate.Table table(GroupAggregate.Dimension dimension) {
    return aggregates.getOrDefault(dimension, new GroupAggregate.Table(dimension, List.of(), 0, 0));
  }
}
