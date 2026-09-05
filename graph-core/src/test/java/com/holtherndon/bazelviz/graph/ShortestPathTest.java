package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Paths between actions, and the three answers a search can give.
 *
 * <p>The third — "I ran out of budget" — is the one that matters. It is not "there is no path", and
 * a UI that showed it as one would state a fact the search never established.
 */
final class ShortestPathTest {

  /** 0 -> 1 -> 2 -> 3, with a shortcut 0 -> 3 and an isolated node 4. */
  private static ShortestPath chainWithShortcut() {
    CsrGraph forward =
        CsrBuilder.build(
            5,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 2);
              visitor.edge(2, 3);
              visitor.edge(0, 3);
            });
    return new ShortestPath(forward, CsrBuilder.reverse(forward));
  }

  /** A long chain 0 -> 1 -> ... -> n-1. */
  private static ShortestPath chain(int nodes) {
    CsrGraph forward =
        CsrBuilder.build(
            nodes,
            visitor -> {
              for (int i = 0; i + 1 < nodes; i++) {
                visitor.edge(i, i + 1);
              }
            });
    return new ShortestPath(forward, CsrBuilder.reverse(forward));
  }

  @Test
  @DisplayName("the shortest of several paths is the one returned")
  void shortestWins() {
    ShortestPath.Result result = chainWithShortcut().find(0, 3, 100);

    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.FOUND);
    // The shortcut, not the three-step chain.
    assertThat(result.path()).containsExactly(0, 3);
    assertThat(result.length()).isEqualTo(1);
  }

  @Test
  @DisplayName("a path through the middle is assembled in order, end to end")
  void pathIsInOrder() {
    ShortestPath.Result result = chain(6).find(0, 5, 100);

    // Both halves of a bidirectional search have to be stitched the right
    // way round; a reversed half is a path that looks plausible and is
    // wrong.
    assertThat(result.path()).containsExactly(0, 1, 2, 3, 4, 5);
    assertThat(result.length()).isEqualTo(5);
  }

  @Test
  @DisplayName("a node reaches itself in zero steps")
  void selfPath() {
    ShortestPath.Result result = chain(4).find(2, 2, 100);

    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.FOUND);
    assertThat(result.path()).containsExactly(2);
    assertThat(result.length()).isZero();
  }

  @Test
  @DisplayName("direction matters: a dependency is not a reverse dependency")
  void edgesAreDirected() {
    assertThat(chain(4).find(0, 3, 100).outcome()).isEqualTo(ShortestPath.Outcome.FOUND);
    // Nothing flows backwards along a build's dependency edges.
    assertThat(chain(4).find(3, 0, 100).outcome()).isEqualTo(ShortestPath.Outcome.NO_PATH);
  }

  @Test
  @DisplayName("an unreachable node is reported as unconnected, with a sentence")
  void unreachableIsNoPath() {
    ShortestPath.Result result = chainWithShortcut().find(0, 4, 100);

    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.NO_PATH);
    assertThat(result.path()).isEmpty();
    assertThat(result.describe()).contains("Nothing connects them");
  }

  @Test
  @DisplayName("running out of budget is not an answer that there is no path")
  void budgetExhaustionIsNotNoPath() {
    // A path exists, and the search is not allowed to walk far enough.
    ShortestPath.Result result = chain(500).find(0, 499, 4);

    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.BUDGET_EXHAUSTED);
    assertThat(result.path()).isEmpty();
    assertThat(result.describe()).contains("is unknown").doesNotContain("Nothing connects them");
    // And the same search with room finds it.
    assertThat(chain(500).find(0, 499, 10_000).outcome()).isEqualTo(ShortestPath.Outcome.FOUND);
  }

  @Test
  @DisplayName("a high-fanout node stops at the exact discovery budget")
  void highFanoutCannotMaterializePastBudget() {
    int nodes = 20_002;
    CsrGraph forward =
        CsrBuilder.build(
            nodes,
            visitor -> {
              for (int target = 1; target < nodes - 1; target++) {
                visitor.edge(0, target);
              }
            });

    ShortestPath.Result result =
        new ShortestPath(forward, CsrBuilder.reverse(forward)).find(0, nodes - 1, 17);

    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.BUDGET_EXHAUSTED);
    assertThat(result.nodesVisited()).isEqualTo(17);
  }

  @Test
  void zeroBudgetCannotEvenClaimASelfPath() {
    ShortestPath.Result result = chain(4).find(2, 2, 0);
    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.BUDGET_EXHAUSTED);
    assertThat(result.nodesVisited()).isZero();
  }

  @Test
  @DisplayName("meeting in the middle visits far fewer nodes than one-directional search")
  void bidirectionalIsCheaper() {
    // A wide tree: node 0 has 200 children, each with 200 children.
    int width = 200;
    int nodes = 1 + width + width * width;
    CsrGraph forward =
        CsrBuilder.build(
            nodes,
            visitor -> {
              for (int i = 0; i < width; i++) {
                visitor.edge(0, 1 + i);
                for (int j = 0; j < width; j++) {
                  visitor.edge(1 + i, 1 + width + i * width + j);
                }
              }
            });
    ShortestPath search = new ShortestPath(forward, CsrBuilder.reverse(forward));

    ShortestPath.Result result = search.find(0, nodes - 1, nodes);

    assertThat(result.outcome()).isEqualTo(ShortestPath.Outcome.FOUND);
    assertThat(result.length()).isEqualTo(2);
    // Searching only forwards would expand all 200 children and then all
    // 40,000 grandchildren. Meeting in the middle expands the target's one
    // parent instead.
    assertThat(result.nodesVisited()).isLessThan(width * 2L);
  }

  @Test
  @DisplayName("mismatched indexes are refused rather than silently searched")
  void mismatchedIndexesAreRefused() {
    CsrGraph small = CsrBuilder.build(3, visitor -> visitor.edge(0, 1));
    CsrGraph large = CsrBuilder.build(9, visitor -> visitor.edge(0, 1));

    // A forward and reverse index of different graphs would produce paths
    // that exist in neither.
    assertThatThrownBy(() -> new ShortestPath(small, large))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different graphs");
  }

  @Test
  @DisplayName("a node outside the graph is an error, not an empty answer")
  void outOfRangeIsAnError() {
    assertThatThrownBy(() -> chain(4).find(0, 9, 100))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  @DisplayName("the returned path cannot be mutated through the result")
  void resultIsDefensive() {
    ShortestPath.Result result = chain(4).find(0, 3, 100);
    int[] first = result.path();
    first[0] = 99;

    assertThat(result.path()).containsExactly(0, 1, 2, 3);
  }
}
