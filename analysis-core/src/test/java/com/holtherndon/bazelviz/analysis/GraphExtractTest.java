package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an extraction shows, and what it admits it is not showing.
 *
 * <p>Plan 13.6's last clause is the one these tests are about: never claim the omitted nodes do not
 * exist. Every result carries the totals it came from, so a caller cannot draw one without having
 * been handed the number it is leaving out.
 */
final class GraphExtractTest {

  /** A chain 0 → 1 → 2 → 3 → 4, so depth and direction both matter. */
  private static CsrGraph chain(int nodes) {
    return CsrBuilder.build(
        nodes,
        visitor -> {
          for (int i = 0; i + 1 < nodes; i++) {
            visitor.edge(i, i + 1);
          }
        });
  }

  private static CsrGraph reverseOf(CsrGraph forward) {
    return CsrBuilder.reverse(forward);
  }

  @Test
  @DisplayName("dependencies are what a node needs, and the depth is honoured")
  void dependenciesRespectDepth() {
    CsrGraph forward = chain(5);

    // Node 4 is the end of the chain, so everything before it is a
    // dependency; the walk runs over the reverse index.
    GraphExtract.Result depth1 = GraphExtract.dependencies(reverseOf(forward), 4, 1, 100);
    GraphExtract.Result depth3 = GraphExtract.dependencies(reverseOf(forward), 4, 3, 100);

    assertThat(depth1.nodes()).containsExactlyInAnyOrder(4, 3);
    assertThat(depth3.nodes()).containsExactlyInAnyOrder(4, 3, 2, 1);
    assertThat(depth1.mode()).isEqualTo(GraphExtract.Mode.DEPENDENCIES);
  }

  @Test
  @DisplayName("a dependency walk runs backward, and its arrows still point forward")
  void dependenciesKeepEdgeDirection() {
    CsrGraph forward = chain(4);

    GraphExtract.Result needs = GraphExtract.dependencies(reverseOf(forward), 3, 3, 100);

    assertThat(needs.nodes()).contains(3, 2, 1, 0);
    // Traversed backwards; drawn forwards. An arrow pointing the way the
    // traversal walked would say a consumer produces its producer.
    assertThat(needs.edges()).contains(new GraphExtract.Edge(0, 1));
    assertThat(needs.edges()).doesNotContain(new GraphExtract.Edge(1, 0));
  }

  @Test
  @DisplayName("dependents are what needs a node, walked over the forward index")
  void dependentsReachForward() {
    CsrGraph forward = chain(4);

    GraphExtract.Result neededBy = GraphExtract.dependents(forward, 0, 3, 100);

    // Everything downstream of the chain's head needs it. Before the
    // direction fix this answer wore the "dependencies" name, and a leaf
    // compile appeared to depend on the linker.
    assertThat(neededBy.nodes()).contains(0, 1, 2, 3);
    assertThat(neededBy.mode()).isEqualTo(GraphExtract.Mode.DEPENDENTS);
    assertThat(neededBy.edges()).contains(new GraphExtract.Edge(0, 1));
    assertThat(neededBy.edges()).doesNotContain(new GraphExtract.Edge(1, 0));
  }

  @Test
  @DisplayName("a neighbourhood reaches both ways from one node")
  void neighbourhoodGoesBothWays() {
    CsrGraph forward = chain(5);

    GraphExtract.Result around = GraphExtract.neighbourhood(forward, reverseOf(forward), 2, 1, 100);

    // "What does this need and what needs this" is one question.
    assertThat(around.nodes()).contains(1, 2, 3);
    assertThat(around.mode()).isEqualTo(GraphExtract.Mode.NEIGHBOURHOOD);
  }

  @Test
  @DisplayName("a traversal that runs out of budget says so")
  void budgetExhaustionIsReported() {
    GraphExtract.Result limited = GraphExtract.dependencies(chain(1_000), 0, 999, 5);

    assertThat(limited.hitLimit()).isTrue();
    assertThat(limited.isComplete()).isFalse();
    // Plan 13.3 forbids a transitive closure, so a traversal has to be able
    // to stop -- and stopping must not read as having finished.
    assertThat(limited.describe()).contains("there is more beyond what is drawn");
  }

  @Test
  @DisplayName("a traversal that finished does not claim it was truncated")
  void completeTraversalsSayNothingAlarming() {
    GraphExtract.Result whole = GraphExtract.dependencies(chain(4), 0, 10, 100);

    assertThat(whole.hitLimit()).isFalse();
    assertThat(whole.describe()).doesNotContain("stopped at");
  }

  @Test
  @DisplayName("every result names the totals it came from, drawn or not")
  void totalsAreAlwaysVisible() {
    GraphExtract.Result small = GraphExtract.dependencies(chain(90_000), 0, 2, 100);

    // A neighbourhood of three from a graph of ninety thousand is a
    // different picture from a build with three actions, and the sentence
    // has to distinguish them.
    assertThat(small.totalNodes()).isEqualTo(90_000);
    assertThat(small.describe()).contains("from a graph of 90000");
  }

  @Test
  @DisplayName("the whole graph is refused rather than truncated when it does not fit")
  void wholeGraphIsAllOrNothing() {
    GraphExtract.Result tooBig = GraphExtract.whole(chain(1_000), 100, 100);

    // A "whole graph" quietly showing the first hundred nodes would be the
    // most misleading view in the application.
    assertThat(tooBig.nodes()).isEmpty();
    assertThat(tooBig.hitLimit()).isTrue();
    assertThat(tooBig.describe())
        .contains("Nothing is hidden")
        .contains("raise")
        .contains("1000 actions");
  }

  @Test
  @DisplayName("a graph that fits is returned whole")
  void wholeGraphFits() {
    GraphExtract.Result all = GraphExtract.whole(chain(10), 100, 100);

    assertThat(all.nodes()).hasSize(10);
    assertThat(all.edges()).hasSize(9);
    assertThat(all.isComplete()).isTrue();
  }

  @Test
  @DisplayName("a path becomes a subgraph of consecutive edges")
  void pathsBecomeSubgraphs() {
    GraphExtract.Result path =
        GraphExtract.path(chain(10), List.of(0, 4, 7), GraphExtract.Mode.CRITICAL_PATH);

    assertThat(path.nodes()).containsExactly(0, 4, 7);
    assertThat(path.edges())
        .containsExactly(new GraphExtract.Edge(0, 4), new GraphExtract.Edge(4, 7));
    assertThat(path.describe()).startsWith("Critical path:");
  }

  @Test
  @DisplayName("an oversized path is refused intact before its edges are allocated")
  void oversizedPathsAreRefusedWithExactCounts() {
    assertThatThrownBy(
            () ->
                GraphExtract.path(
                    chain(10), List.of(0, 1, 2, 3), GraphExtract.Mode.CRITICAL_PATH, 3, 20))
        .isInstanceOf(GraphExtract.PathLimitExceededException.class)
        .hasMessageContaining("4 actions and 3 dependencies")
        .hasMessageContaining("budget of 3 actions and 20 dependencies")
        .hasMessageContaining("Nothing was drawn");

    assertThatThrownBy(
            () -> GraphExtract.path(chain(10), List.of(0, 1, 2, 3), GraphExtract.Mode.PATH, 10, 2))
        .isInstanceOf(GraphExtract.PathLimitExceededException.class)
        .hasMessageContaining("4 actions and 3 dependencies")
        .hasMessageContaining("budget of 10 actions and 2 dependencies");
  }

  @Test
  @DisplayName("an empty path is a legal, empty subgraph")
  void emptyPaths() {
    GraphExtract.Result none = GraphExtract.path(chain(4), List.of(), GraphExtract.Mode.PATH);

    assertThat(none.nodes()).isEmpty();
    assertThat(none.edges()).isEmpty();
  }

  @Test
  @DisplayName("a node outside the graph is an error, not an empty extraction")
  void outOfRangeIsAnError() {
    assertThatThrownBy(() -> GraphExtract.dependencies(chain(4), 99, 1, 10))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  @DisplayName("every mode has a name, and none renders as an enum constant")
  void everyModeIsWorded() {
    for (GraphExtract.Mode mode : GraphExtract.Mode.values()) {
      assertThat(mode.displayName()).as("%s", mode).isNotBlank().doesNotContain("_");
    }
  }

  @Test
  @DisplayName("edges inside an extraction connect only nodes inside it")
  void edgesStayInsideTheExtraction() {
    GraphExtract.Result partial = GraphExtract.dependencies(chain(20), 0, 3, 100);

    // An edge to a node that was not extracted would draw an arrow to
    // nothing, or worse, to whatever occupied that index.
    for (GraphExtract.Edge edge : partial.edges()) {
      assertThat(partial.nodes()).contains(edge.from(), edge.to());
    }
  }
}
