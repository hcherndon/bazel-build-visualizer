package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CsrGraphTest {

  /** Edges as {from, to} pairs; the canonical tiny fixture used across tests. */
  private static final int[][] DIAMOND = {
    {0, 1}, {0, 2}, {1, 3}, {2, 3}, {3, 4},
  };

  static EdgeStream stream(int[][] edges) {
    return visitor -> {
      for (int[] e : edges) {
        visitor.edge(e[0], e[1]);
      }
    };
  }

  static List<Integer> neighbors(CsrGraph g, int node) {
    List<Integer> out = new ArrayList<>();
    g.forEachNeighbor(node, out::add);
    return out;
  }

  @Test
  void adjacencyMatchesEdgeList() {
    CsrGraph g = CsrBuilder.build(5, stream(DIAMOND));
    assertThat(g.nodeCount()).isEqualTo(5);
    assertThat(g.edgeCount()).isEqualTo(5);
    assertThat(neighbors(g, 0)).containsExactly(1, 2);
    assertThat(neighbors(g, 1)).containsExactly(3);
    assertThat(neighbors(g, 2)).containsExactly(3);
    assertThat(neighbors(g, 3)).containsExactly(4);
    assertThat(neighbors(g, 4)).isEmpty();
  }

  @Test
  void degreeSumsEqualEdgeCount() {
    CsrGraph g = CsrBuilder.build(5, stream(DIAMOND));
    long sum = 0;
    for (int node = 0; node < g.nodeCount(); node++) {
      sum += g.degree(node);
    }
    assertThat(sum).isEqualTo(g.edgeCount());
  }

  @Test
  void sliceAccessMatchesForEachNeighbor() {
    CsrGraph g = CsrBuilder.build(5, stream(DIAMOND));
    for (int node = 0; node < g.nodeCount(); node++) {
      List<Integer> viaSlice = new ArrayList<>();
      for (long e = g.neighborsBegin(node); e < g.neighborsEnd(node); e++) {
        viaSlice.add(g.neighborAt(e));
      }
      assertThat(viaSlice).isEqualTo(neighbors(g, node));
      assertThat(g.neighborsEnd(node) - g.neighborsBegin(node)).isEqualTo(g.degree(node));
    }
  }

  @Test
  void parallelEdgesArePreserved() {
    CsrGraph g = CsrBuilder.build(3, stream(new int[][] {{0, 1}, {0, 1}, {0, 2}}));
    assertThat(g.edgeCount()).isEqualTo(3);
    assertThat(neighbors(g, 0)).containsExactly(1, 1, 2);
  }

  @Test
  void emptyGraph() {
    CsrGraph g = CsrBuilder.build(0, visitor -> {});
    assertThat(g.nodeCount()).isZero();
    assertThat(g.edgeCount()).isZero();
  }

  @Test
  void nodesWithoutEdges() {
    CsrGraph g = CsrBuilder.build(4, visitor -> {});
    assertThat(g.edgeCount()).isZero();
    for (int node = 0; node < 4; node++) {
      assertThat(g.degree(node)).isZero();
      assertThat(neighbors(g, node)).isEmpty();
    }
  }

  @Test
  void neighborAtRejectsOutOfRangeEdgeIndex() {
    CsrGraph g = CsrBuilder.build(2, stream(new int[][] {{0, 1}}));
    assertThatThrownBy(() -> g.neighborAt(1)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> g.neighborAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
  }
}
