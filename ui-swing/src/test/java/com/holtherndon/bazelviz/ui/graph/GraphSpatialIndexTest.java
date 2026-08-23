package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Culling and hit testing, checked against the brute-force answer.
 *
 * <p>A spatial index is only worth having if it agrees exactly with the loop it
 * replaces. Several tests below compute the answer both ways and compare, which
 * catches the off-by-one at a cell boundary that a hand-picked expectation
 * would sail past.
 */
final class GraphSpatialIndexTest {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static GraphLayout.Result laidOut(int nodes) {
        CsrGraph chain = CsrBuilder.build(nodes, visitor -> {
            for (int i = 0; i + 1 < nodes; i++) {
                visitor.edge(i, i + 1);
            }
        });
        return GraphLayout.layered(
                GraphExtract.whole(chain, nodes + 1, nodes + 1), RUNNING);
    }

    /** A grid of points, which exercises both axes of the index. */
    private static GraphLayout.Result gridOf(int nodes) {
        CsrGraph loose = CsrBuilder.build(nodes, visitor -> { });
        return GraphLayout.grid(GraphExtract.whole(loose, nodes + 1, nodes + 1), RUNNING);
    }

    private static List<Integer> bruteForce(
            GraphLayout.Result layout, double left, double top, double right, double bottom) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < layout.size(); i++) {
            if (layout.xAt(i) >= left && layout.xAt(i) <= right
                    && layout.yAt(i) >= top && layout.yAt(i) <= bottom) {
                found.add(i);
            }
        }
        return found;
    }

    @Test
    @DisplayName("a rectangle query agrees exactly with scanning every node")
    void cullingMatchesBruteForce() {
        GraphLayout.Result layout = gridOf(400);
        GraphSpatialIndex index = GraphSpatialIndex.of(layout);

        // Several rectangles, including ones that start and end mid-cell.
        double[][] rectangles = {
            {0, 0, 100, 100}, {-50, -50, 5_000, 5_000}, {137.5, 61.2, 402.9, 313.7},
            {1_000_000, 1_000_000, 1_000_001, 1_000_001}, {0, 0, 0, 0},
        };
        for (double[] rectangle : rectangles) {
            int[] fromIndex =
                    index.within(rectangle[0], rectangle[1], rectangle[2], rectangle[3]);
            List<Integer> expected = bruteForce(
                    layout, rectangle[0], rectangle[1], rectangle[2], rectangle[3]);
            assertThat(java.util.Arrays.stream(fromIndex).boxed().sorted().toList())
                    .as("rectangle %s", java.util.Arrays.toString(rectangle))
                    .isEqualTo(expected.stream().sorted().toList());
        }
    }

    @Test
    @DisplayName("hit testing finds the nearest node and nothing beyond the radius")
    void hitTesting() {
        GraphLayout.Result layout = gridOf(100);
        GraphSpatialIndex index = GraphSpatialIndex.of(layout);

        // Just off node 0, well inside a generous radius.
        assertThat(index.nearest(layout.xAt(0) + 3, layout.yAt(0) + 3, 40))
                .hasValue(0);
        // Far from everything: a click on empty canvas deselects rather than
        // grabbing whatever happened to be closest.
        assertThat(index.nearest(1_000_000, 1_000_000, 40)).isEmpty();
    }

    @Test
    @DisplayName("hit testing is deterministic when several nodes are equidistant")
    void tiesAreBrokenTheSameWayEveryTime() {
        // A layered chain puts nodes on an exact lattice, so equidistant
        // candidates are the normal case rather than a contrivance.
        GraphSpatialIndex index = GraphSpatialIndex.of(laidOut(50));

        java.util.OptionalInt first = index.nearest(110, 0, 500);
        for (int attempt = 0; attempt < 20; attempt++) {
            assertThat(index.nearest(110, 0, 500)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("a linear layout has no height and still indexes")
    void zeroHeightLayouts() {
        GraphExtract.Result path = GraphExtract.path(
                CsrBuilder.build(10, visitor -> { }),
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
                GraphExtract.Mode.CRITICAL_PATH);
        GraphLayout.Result linear = GraphLayout.linear(path, RUNNING);

        GraphSpatialIndex index = GraphSpatialIndex.of(linear);

        // A critical path is one of the most-used views, so its degenerate
        // bounding box is the normal case, not an edge case.
        assertThat(index.size()).isEqualTo(10);
        assertThat(index.nearest(linear.xAt(4), 0, 10)).hasValue(4);
        assertThat(index.within(-1, -1, linear.xAt(9) + 1, 1)).hasSize(10);
    }

    @Test
    @DisplayName("a single node indexes into a single cell")
    void oneNode() {
        GraphLayout.Result one = gridOf(1);

        GraphSpatialIndex index = GraphSpatialIndex.of(one);

        assertThat(index.cellCount()).isEqualTo(1);
        assertThat(index.nearest(0, 0, 1)).hasValue(0);
    }

    @Test
    @DisplayName("an empty layout answers everything with nothing")
    void emptyLayout() {
        GraphSpatialIndex index = GraphSpatialIndex.of(GraphLayout.Result.empty(
                GraphLayout.Kind.LAYERED));

        assertThat(index.size()).isZero();
        assertThat(index.within(-1e9, -1e9, 1e9, 1e9)).isEmpty();
        assertThat(index.nearest(0, 0, 1_000)).isEmpty();
    }

    @Test
    @DisplayName("the grid stays proportional to the node count, not the coordinates")
    void gridSizeFollowsTheNodeCount() {
        // Two layouts of the same size but very different extents must produce
        // comparably sized grids -- an index whose cell count followed world
        // coordinates would allocate wildly for a large graph.
        GraphSpatialIndex small = GraphSpatialIndex.of(gridOf(400));
        GraphSpatialIndex chain = GraphSpatialIndex.of(laidOut(400));

        assertThat(small.cellCount()).isLessThanOrEqualTo(400);
        assertThat(chain.cellCount()).isLessThanOrEqualTo(400);
    }

    @Test
    @DisplayName("every node is reachable, so culling can never lose one")
    void everyNodeIsIndexed() {
        for (int count : new int[] {1, 2, 7, 64, 999}) {
            GraphLayout.Result layout = gridOf(count);
            GraphSpatialIndex index = GraphSpatialIndex.of(layout);
            double[] box = layout.bounds().orElseThrow();

            // The whole bounding box must return every node. A node lost to a
            // rounding error at a cell edge would simply stop being drawn.
            assertThat(index.within(box[0], box[1], box[2], box[3]))
                    .as("%d nodes", count)
                    .hasSize(count);
        }
    }
}
