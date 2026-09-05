package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The weight computations, on a graph small enough to count by hand.
 *
 * <p>The diamond {@code 0 → 1 → 3, 0 → 2 → 3} (producer-to-consumer) is the fixture because it has
 * everything the counts must get right: overlapping reachability (3 is reachable from 0 both ways,
 * and must be counted once), a source, a sink, and asymmetric degrees.
 *
 * <p>The other half of these tests is the honesty machinery: a budget that trips must leave {@code
 * UNKNOWN} behind it and say it was reached, because plan 13.3 forbids a transitive closure and a
 * traversal that cannot give up visibly would be one in disguise.
 */
final class GraphWeightsTest {

  /** The diamond, producer-to-consumer. */
  private static CsrGraph diamond() {
    return CsrBuilder.build(
        4,
        visitor -> {
          visitor.edge(0, 1);
          visitor.edge(0, 2);
          visitor.edge(1, 3);
          visitor.edge(2, 3);
        });
  }

  private static List<GraphExtract.Edge> diamondEdges() {
    return List.of(
        new GraphExtract.Edge(0, 1), new GraphExtract.Edge(0, 2),
        new GraphExtract.Edge(1, 3), new GraphExtract.Edge(2, 3));
  }

  @Test
  @DisplayName("immediate degrees are the CSR degrees, in node-list order")
  void immediateDegreesAreCsrDegrees() {
    CsrGraph forward = diamond();

    GraphWeights.Result dependents = GraphWeights.immediateDegrees(forward, List.of(0, 1, 2, 3));
    GraphWeights.Result dependencies =
        GraphWeights.immediateDegrees(CsrBuilder.reverse(forward), List.of(0, 1, 2, 3));

    assertThat(dependents.values()).containsExactly(2, 1, 1, 0);
    assertThat(dependencies.values()).containsExactly(0, 1, 1, 2);
    assertThat(dependents.truncated()).isFalse();
    assertThat(dependents.unknownCount()).isZero();
  }

  @Test
  @DisplayName("subgraph transitive counts are exact, and count the diamond's far corner once")
  void subgraphTransitiveCountsAreExact() {
    // Dependents: 0 reaches 1, 2 and 3 — and 3 exactly once, though two
    // paths lead there. That deduplication is the whole reason a DP over
    // path counts would be wrong and a reachability walk is right.
    GraphWeights.Result dependents =
        GraphWeights.subgraphTransitiveCounts(
            List.of(0, 1, 2, 3),
            diamondEdges(),
            true,
            GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET);
    GraphWeights.Result dependencies =
        GraphWeights.subgraphTransitiveCounts(
            List.of(0, 1, 2, 3),
            diamondEdges(),
            false,
            GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET);

    assertThat(dependents.values()).containsExactly(3, 1, 1, 0);
    assertThat(dependencies.values()).containsExactly(0, 1, 1, 3);
    assertThat(dependents.truncated()).isFalse();
    assertThat(dependents.note()).isEmpty();
  }

  @Test
  @DisplayName("the counts are scoped to the drawn subgraph, not the whole graph")
  void countsAreSubgraphScoped() {
    // Only 0, 1 and 3 are drawn. Node 2 exists in the graph but not on
    // screen, so 3's dependency count is 2 (via node 1), not 3 — exact
    // for what is drawn, silent about the rest, which is the deal the
    // legend states.
    List<GraphExtract.Edge> drawn =
        List.of(new GraphExtract.Edge(0, 1), new GraphExtract.Edge(1, 3));

    GraphWeights.Result dependencies =
        GraphWeights.subgraphTransitiveCounts(
            List.of(0, 1, 3), drawn, false, GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET);

    assertThat(dependencies.values()).containsExactly(0, 1, 2);
  }

  @Test
  @DisplayName("a tripped work budget leaves UNKNOWN behind it, never zero")
  void workBudgetLeavesUnknown() {
    GraphWeights.Result counted =
        GraphWeights.subgraphTransitiveCounts(List.of(0, 1, 2, 3), diamondEdges(), true, 1);

    // The first traversal could not finish inside the exact budget, so even
    // its partial reachability stays UNKNOWN rather than looking complete.
    assertThat(counted.values()[0]).isEqualTo(GraphWeights.UNKNOWN);
    assertThat(counted.values()[1]).isEqualTo(GraphWeights.UNKNOWN);
    assertThat(counted.values()[2]).isEqualTo(GraphWeights.UNKNOWN);
    assertThat(counted.values()[3]).isEqualTo(GraphWeights.UNKNOWN);
    assertThat(counted.truncated()).isTrue();
    assertThat(counted.note()).contains("budget");
  }

  @Test
  @DisplayName("the subgraph work boundary admits N and refuses N minus one exactly")
  void subgraphWorkBoundaryIsExact() {
    List<Integer> nodes = List.of(0, 1, 2, 3);

    GraphWeights.Result exact =
        GraphWeights.subgraphTransitiveCounts(nodes, List.of(), true, nodes.size());
    GraphWeights.Result shortByOne =
        GraphWeights.subgraphTransitiveCounts(nodes, List.of(), true, nodes.size() - 1L);

    assertThat(exact.values()).containsExactly(0, 0, 0, 0);
    assertThat(exact.truncated()).isFalse();
    assertThat(shortByOne.values()).containsExactly(0, 0, 0, GraphWeights.UNKNOWN);
    assertThat(shortByOne.truncated()).isTrue();
  }

  @Test
  @DisplayName("a whole-graph count within budget is exact and says so")
  void globalCountWithinBudget() {
    GraphWeights.BudgetedCount counted = GraphWeights.globalTransitiveCount(diamond(), 0, 100);

    assertThat(counted.count()).isEqualTo(3);
    assertThat(counted.budgetReached()).isFalse();
    assertThat(counted.describe()).isEqualTo("3");
  }

  @Test
  @DisplayName("a whole-graph count that hits its budget reports a lower bound")
  void globalCountHittingBudget() {
    GraphWeights.BudgetedCount counted = GraphWeights.globalTransitiveCount(diamond(), 0, 2);

    // Two nodes visited, the source included: one dependent found and the
    // traversal stopped, so all that may be claimed is "at least one".
    assertThat(counted.count()).isEqualTo(1);
    assertThat(counted.budgetReached()).isTrue();
    assertThat(counted.describe()).isEqualTo("≥" + 1 + " (budget reached)");
  }

  @Test
  @DisplayName("a count that visits the whole graph is complete, whatever the budget says")
  void visitingEverythingIsComplete() {
    // Budget of exactly the node count: the traversal saw everything, so
    // "budget reached" would claim there might be more when there is not.
    GraphWeights.BudgetedCount counted = GraphWeights.globalTransitiveCount(diamond(), 0, 4);

    assertThat(counted.count()).isEqualTo(3);
    assertThat(counted.budgetReached()).isFalse();
  }

  @Test
  @DisplayName("a disconnected component exactly equal to the budget is still complete")
  void exactBudgetForDisconnectedComponentIsComplete() {
    CsrGraph isolated = CsrBuilder.build(2, visitor -> {});

    GraphWeights.BudgetedCount counted = GraphWeights.globalTransitiveCount(isolated, 0, 1);

    assertThat(counted.count()).isZero();
    assertThat(counted.budgetReached()).isFalse();
    assertThat(counted.describe()).isEqualTo("0");
  }

  @Test
  @DisplayName("unavailable weights are all UNKNOWN with the reason attached")
  void unavailableCarriesItsReason() {
    GraphWeights.Result unavailable = GraphWeights.unavailable(3, "no index");

    assertThat(unavailable.values())
        .containsExactly(GraphWeights.UNKNOWN, GraphWeights.UNKNOWN, GraphWeights.UNKNOWN);
    assertThat(unavailable.unknownCount()).isEqualTo(3);
    assertThat(unavailable.note()).isEqualTo("no index");
  }
}
