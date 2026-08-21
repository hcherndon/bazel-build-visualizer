package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BfsTest {

    // 0 -> {1, 2}, 1 -> {3}, 2 -> {3}, 3 -> {4}; 5 is disconnected.
    private static CsrGraph diamond() {
        return CsrBuilder.build(6, CsrGraphTest.stream(new int[][] {
            {0, 1}, {0, 2}, {1, 3}, {2, 3}, {3, 4},
        }));
    }

    private static List<Integer> visitOrder(CsrGraph g, int source, long maxNodes, int maxDepth) {
        List<Integer> order = new ArrayList<>();
        new Bfs(g).run(source, maxNodes, maxDepth, order::add);
        return order;
    }

    @Test
    void visitsInBreadthFirstOrder() {
        assertThat(visitOrder(diamond(), 0, Long.MAX_VALUE, Integer.MAX_VALUE))
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void returnsVisitedCount() {
        assertThat(new Bfs(diamond()).run(0, Long.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(5);
    }

    @Test
    void nodeBudgetStopsTraversal() {
        assertThat(new Bfs(diamond()).run(0, 3, Integer.MAX_VALUE)).isEqualTo(3);
        assertThat(visitOrder(diamond(), 0, 3, Integer.MAX_VALUE)).containsExactly(0, 1, 2);
        assertThat(new Bfs(diamond()).run(0, 1, Integer.MAX_VALUE)).isEqualTo(1);
    }

    @Test
    void depthLimitStopsTraversal() {
        assertThat(visitOrder(diamond(), 0, Long.MAX_VALUE, 0)).containsExactly(0);
        assertThat(visitOrder(diamond(), 0, Long.MAX_VALUE, 1)).containsExactly(0, 1, 2);
        assertThat(visitOrder(diamond(), 0, Long.MAX_VALUE, 2)).containsExactly(0, 1, 2, 3);
    }

    @Test
    void disconnectedSourceVisitsOnlyItself() {
        assertThat(visitOrder(diamond(), 5, Long.MAX_VALUE, Integer.MAX_VALUE)).containsExactly(5);
    }

    @Test
    void diamondJoinIsVisitedOnce() {
        // Node 3 is reachable via both 1 and 2; it must appear exactly once.
        List<Integer> order = visitOrder(diamond(), 0, Long.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(order).doesNotHaveDuplicates();
    }

    @Test
    void directionFollowsSuppliedGraph() {
        CsrGraph rev = CsrBuilder.reverse(diamond());
        assertThat(visitOrder(rev, 4, Long.MAX_VALUE, Integer.MAX_VALUE))
                .containsExactly(4, 3, 1, 2, 0);
    }

    @Test
    void reusedInstanceResetsBetweenRuns() {
        Bfs bfs = new Bfs(diamond());
        assertThat(bfs.run(0, Long.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(5);
        assertThat(bfs.run(0, Long.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(5);
        assertThat(bfs.run(5, Long.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(1);
    }

    @Test
    void zeroBudgetVisitsNothing() {
        assertThat(new Bfs(diamond()).run(0, 0, Integer.MAX_VALUE)).isZero();
    }

    @Test
    void rejectsInvalidArguments() {
        Bfs bfs = new Bfs(diamond());
        assertThatThrownBy(() -> bfs.run(6, 10, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bfs.run(-1, 10, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bfs.run(0, 10, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
