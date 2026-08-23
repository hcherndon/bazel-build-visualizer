package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The longest weighted path, and the four things it must not claim.
 *
 * <p>It is not Bazel's critical path, it is not exact when durations are
 * missing, it is undefined on a cyclic graph, and it is not a build's actual
 * elapsed time. Each has a test.
 */
final class CriticalPathTest {

    /**
     * A diamond: 0 feeds 1 and 2, both feed 3.
     *
     * <pre>
     *      1 (30)
     *     /      \
     * 0 (10)      3 (5)
     *     \      /
     *      2 (100)
     * </pre>
     *
     * The long way round is 0 → 2 → 3 = 115.
     */
    private static CsrGraph diamond() {
        return CsrBuilder.build(4, visitor -> {
            visitor.edge(0, 1);
            visitor.edge(0, 2);
            visitor.edge(1, 3);
            visitor.edge(2, 3);
        });
    }

    private static final long[] DIAMOND_WEIGHTS = {10, 30, 100, 5};

    @Test
    @DisplayName("the longest way round is the path, not the shortest or the first")
    void longestPathWins() {
        CriticalPath.Result result = CriticalPath.compute(
                diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

        assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.COMPUTED);
        assertThat(result.path()).containsExactly(0, 2, 3);
        assertThat(result.makespanMicros()).isEqualTo(115);
    }

    @Test
    @DisplayName("a node off the path has slack, and one on it has none")
    void slackIsComputed() {
        CriticalPath.Result result = CriticalPath.compute(
                diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

        // Node 1 could start 70 µs later without delaying anything: it takes 30
        // where the parallel branch takes 100.
        assertThat(result.slackAt(1)).isEqualTo(70);
        assertThat(result.slackAt(0)).isZero();
        assertThat(result.slackAt(2)).isZero();
        assertThat(result.slackAt(3)).isZero();
        // Slack of zero is exactly what being on the path means.
        assertThat(result.isOnPath(1)).isFalse();
        assertThat(result.isOnPath(0)).isTrue();
        assertThat(result.isOnPath(2)).isTrue();
        assertThat(result.isOnPath(3)).isTrue();
    }

    @Test
    @DisplayName("earliest start respects every predecessor, not just one")
    void earliestStartWaitsForTheSlowestInput() {
        CriticalPath.Result result = CriticalPath.compute(
                diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

        // Node 3 waits for node 2 (finishing at 110), not node 1 (at 40).
        assertThat(result.earliestStartAt(3)).isEqualTo(110);
        assertThat(result.earliestFinishAt(3)).isEqualTo(115);
    }

    @Test
    @DisplayName("an untimed action counts as instantaneous and makes the answer a lower bound")
    void untimedNodesMakeItPartial() {
        long[] weights = {10, CriticalPath.UNKNOWN_DURATION, 100, 5};

        CriticalPath.Result result = CriticalPath.compute(
                diamond(), weights, CriticalPath.DurationSource.BEP_ACTION);

        assertThat(result.untimedNodes()).isEqualTo(1);
        assertThat(result.isPartial()).isTrue();
        // The number is still produced -- a partial answer beats none -- and it
        // says it is a lower bound rather than presenting itself as the answer.
        assertThat(result.describe()).contains("lower bound").contains("instantaneous");
    }

    @Test
    @DisplayName("a fully timed graph is not partial")
    void completeGraphsAreNotPartial() {
        CriticalPath.Result result = CriticalPath.compute(
                diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

        assertThat(result.isPartial()).isFalse();
        assertThat(result.describe()).doesNotContain("lower bound");
    }

    @Test
    @DisplayName("a cycle produces no path, and names the actions in it")
    void cyclesAreRefusedAndReported() {
        CsrGraph cyclic = CsrBuilder.build(3, visitor -> {
            visitor.edge(0, 1);
            visitor.edge(1, 2);
            visitor.edge(2, 0);
        });

        CriticalPath.Result result = CriticalPath.compute(
                cyclic, new long[] {1, 1, 1}, CriticalPath.DurationSource.BEP_ACTION);

        assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.CYCLIC);
        assertThat(result.path()).isEmpty();
        assertThat(result.cyclicNodes()).containsExactlyInAnyOrder(0, 1, 2);
        // A producer-to-consumer graph cannot have a cycle, so this means the
        // graph is wrong rather than the build -- and the message says so.
        assertThat(result.describe()).contains("the graph is wrong rather than the build");
    }

    @Test
    @DisplayName("the answer says which measurement it used")
    void durationSourceIsCarried() {
        assertThat(CriticalPath.compute(diamond(), DIAMOND_WEIGHTS,
                        CriticalPath.DurationSource.EXECUTION_ATTEMPT).describe())
                .contains("spawn durations from the execution log");
        assertThat(CriticalPath.compute(diamond(), DIAMOND_WEIGHTS,
                        CriticalPath.DurationSource.BEP_ACTION).describe())
                .contains("action durations from the build event stream");
    }

    @Test
    @DisplayName("it is never called Bazel's critical path")
    void itIsNamedForWhatItIs() {
        CriticalPath.Result result = CriticalPath.compute(
                diamond(), DIAMOND_WEIGHTS, CriticalPath.DurationSource.BEP_ACTION);

        // Plan 13.4. Bazel writes its own into the profile and Phase 5 stores
        // it untouched; the two legitimately disagree, and presenting this one
        // as Bazel's would make the disagreement invisible.
        assertThat(result.displayName()).isEqualTo("Visualizer-computed dependency critical path");
        assertThat(result.describe()).contains("Visualizer-computed");
    }

    @Test
    @DisplayName("an empty graph says there is nothing to compute")
    void emptyGraphs() {
        CriticalPath.Result result = CriticalPath.compute(
                CsrBuilder.build(0, visitor -> { }), new long[0],
                CriticalPath.DurationSource.NONE);

        assertThat(result.outcome()).isEqualTo(CriticalPath.Outcome.EMPTY);
        assertThat(result.describe()).contains("no dependency graph");
    }

    @Test
    @DisplayName("a graph with no edges has a path of one action")
    void disconnectedGraphs() {
        CsrGraph isolated = CsrBuilder.build(3, visitor -> { });

        CriticalPath.Result result = CriticalPath.compute(
                isolated, new long[] {5, 50, 5}, CriticalPath.DurationSource.BEP_ACTION);

        // Nothing depends on anything, so the longest chain is the slowest
        // single action -- which is the right answer and not a degenerate one.
        assertThat(result.path()).containsExactly(1);
        assertThat(result.makespanMicros()).isEqualTo(50);
    }

    @Test
    @DisplayName("a weight per node is required, and a mismatch is refused")
    void weightsMustMatchTheGraph() {
        assertThatThrownBy(() -> CriticalPath.compute(
                        diamond(), new long[] {1, 2}, CriticalPath.DurationSource.BEP_ACTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one weight per node");
    }

    @Test
    @DisplayName("the schedule cannot be reached in bulk, so it cannot be rewritten")
    void resultIsDefensive() {
        // The Phase 8 replacement for a defensive-copy test. An accessor
        // returning long[] over five million nodes has to choose between
        // cloning forty megabytes and handing out the live schedule; there is
        // no third option, so the accessor does not exist.
        for (java.lang.reflect.Method method : CriticalPath.Result.class.getMethods()) {
            if (method.getDeclaringClass() != CriticalPath.Result.class) {
                continue;
            }
            assertThat(method.getReturnType())
                    .as("%s", method.getName())
                    .isNotEqualTo(long[].class);
        }
    }

    @Test
    @DisplayName("a long chain is ordered correctly, not merely plausibly")
    void longChains() {
        int nodes = 1_000;
        CsrGraph chain = CsrBuilder.build(nodes, visitor -> {
            for (int i = 0; i + 1 < nodes; i++) {
                visitor.edge(i, i + 1);
            }
        });
        long[] weights = new long[nodes];
        java.util.Arrays.fill(weights, 3);

        CriticalPath.Result result = CriticalPath.compute(
                chain, weights, CriticalPath.DurationSource.BEP_ACTION);

        assertThat(result.path()).hasSize(nodes);
        assertThat(result.makespanMicros()).isEqualTo(3L * nodes);
        assertThat(result.path().getFirst()).isZero();
        assertThat(result.path().getLast()).isEqualTo(nodes - 1);
    }
}
