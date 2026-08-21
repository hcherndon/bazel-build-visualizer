package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class CsrBuilderTest {

    @Test
    void rejectsOutOfRangeNodes() {
        assertThatThrownBy(() -> CsrBuilder.build(2, CsrGraphTest.stream(new int[][] {{0, 2}})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsrBuilder.build(2, CsrGraphTest.stream(new int[][] {{-1, 0}})))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonReplayableStream() {
        // Delivers one extra edge on every replay: pass 2 disagrees with pass 1.
        int[] calls = new int[1];
        EdgeStream growing = visitor -> {
            calls[0]++;
            for (int i = 0; i < calls[0]; i++) {
                visitor.edge(0, 1);
            }
        };
        assertThatThrownBy(() -> CsrBuilder.build(2, growing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not replayable");
    }

    @Test
    void reverseTransposesEdges() {
        CsrGraph g = CsrBuilder.build(5, CsrGraphTest.stream(new int[][] {
            {0, 1}, {0, 2}, {1, 3}, {2, 3}, {3, 4},
        }));
        CsrGraph rev = CsrBuilder.reverse(g);
        assertThat(rev.nodeCount()).isEqualTo(5);
        assertThat(rev.edgeCount()).isEqualTo(5);
        assertThat(CsrGraphTest.neighbors(rev, 0)).isEmpty();
        assertThat(CsrGraphTest.neighbors(rev, 1)).containsExactly(0);
        assertThat(CsrGraphTest.neighbors(rev, 2)).containsExactly(0);
        assertThat(CsrGraphTest.neighbors(rev, 3)).containsExactly(1, 2);
        assertThat(CsrGraphTest.neighbors(rev, 4)).containsExactly(3);
    }

    @Test
    void reverseOfReverseHasSameAdjacency() {
        // Neighbor order within a node may legitimately differ (reverse sorts
        // lists ascending), so compare per-node neighbor multisets.
        CsrGraph g = CsrBuilder.build(6, CsrGraphTest.stream(new int[][] {
            {0, 3}, {0, 1}, {1, 4}, {2, 4}, {4, 5}, {0, 1}, {5, 0},
        }));
        CsrGraph back = CsrBuilder.reverse(CsrBuilder.reverse(g));
        assertThat(back.nodeCount()).isEqualTo(g.nodeCount());
        assertThat(back.edgeCount()).isEqualTo(g.edgeCount());
        for (int node = 0; node < g.nodeCount(); node++) {
            List<Integer> expected = CsrGraphTest.neighbors(g, node);
            assertThat(CsrGraphTest.neighbors(back, node))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void reverseOfEmptyGraph() {
        CsrGraph rev = CsrBuilder.reverse(CsrBuilder.build(0, visitor -> {}));
        assertThat(rev.nodeCount()).isZero();
        assertThat(rev.edgeCount()).isZero();
    }

    @Test
    void reversePreservesParallelEdges() {
        CsrGraph g = CsrBuilder.build(2, CsrGraphTest.stream(new int[][] {{0, 1}, {0, 1}}));
        CsrGraph rev = CsrBuilder.reverse(g);
        assertThat(CsrGraphTest.neighbors(rev, 1)).containsExactly(0, 0);
    }
}
