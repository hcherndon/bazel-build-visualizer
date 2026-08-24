package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a weight becomes pixels: radius, thickness and colour, and what an
 * unknown value must never become.
 *
 * <p>Rule 11 has a visual form here: a node whose weight was not computed is
 * grey at base size — not the smallest, coldest node on screen, which is what
 * treating unknown as zero would draw.
 */
final class GraphWeightEncodingTest {

    /** A three-node chain 0 → 1 → 2, laid out linearly. */
    private static GraphModel chainModel() {
        GraphExtract.Result extract = new GraphExtract.Result(
                GraphExtract.Mode.NEIGHBOURHOOD,
                List.of(0, 1, 2),
                List.of(new GraphExtract.Edge(0, 1), new GraphExtract.Edge(1, 2)),
                3, 2, false, 100);
        GraphLayout.Result layout =
                GraphLayout.run(GraphLayout.Kind.LINEAR, extract, new AtomicBoolean(false));
        GraphLayoutService.Rendered rendered = new GraphLayoutService.Rendered(
                null, extract, layout, null, "three nodes");
        return GraphModel.of(
                rendered,
                new String[] {"//a:zero", "//a:one", "//a:two"},
                new long[] {
                    GraphModel.UNKNOWN_DURATION,
                    GraphModel.UNKNOWN_DURATION,
                    GraphModel.UNKNOWN_DURATION,
                });
    }

    private static int positionOf(GraphModel model, int node) {
        for (int i = 0; i < model.size(); i++) {
            if (model.nodeAt(i) == node) {
                return i;
            }
        }
        throw new AssertionError("node " + node + " is not drawn");
    }

    @Test
    @DisplayName("the selected weight drives radius and colour; positions do not move")
    void weightDrivesRadiusAndColour() {
        GraphModel base = chainModel();
        GraphModel weighted = base.withWeights(
                GraphWeight.IMMEDIATE_DEPS, Map.of(0, 0L, 2, 4L), false, "");

        int zero = positionOf(weighted, 0);
        int two = positionOf(weighted, 2);

        // Same layout, different clothes: the weight restyles, never re-lays.
        assertThat(weighted.layout()).isSameAs(base.layout());
        assertThat(weighted.weight()).isEqualTo(GraphWeight.IMMEDIATE_DEPS);

        // The smallest known weight sits at the radius floor, the largest at
        // the ceiling, and the colour ramp runs cold to hot between them.
        assertThat(weighted.radiusScaleAt(zero)).isEqualTo(GraphModel.MIN_RADIUS_SCALE);
        assertThat(weighted.radiusScaleAt(two)).isEqualTo(GraphModel.MAX_RADIUS_SCALE);
        assertThat(weighted.colourAt(zero)).isEqualTo(GraphColours.heat(0));
        assertThat(weighted.colourAt(two)).isEqualTo(GraphColours.heat(1));
        assertThat(weighted.maxWeight()).hasValue(4);
    }

    @Test
    @DisplayName("an unknown weight is grey at base size, never the smallest coldest node")
    void unknownIsNotZero() {
        GraphModel weighted = chainModel().withWeights(
                GraphWeight.IMMEDIATE_DEPS, Map.of(0, 0L, 2, 4L), false, "");

        int one = positionOf(weighted, 1);

        assertThat(weighted.weightAt(one)).isEmpty();
        assertThat(weighted.colourAt(one)).isEqualTo(GraphColours.UNKNOWN);
        assertThat(weighted.radiusScaleAt(one)).isEqualTo(1.0);
        assertThat(weighted.unweightedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("edge thickness follows the known endpoints' weights")
    void edgeThicknessFollowsWeights() {
        GraphModel weighted = chainModel().withWeights(
                GraphWeight.IMMEDIATE_DEPS, Map.of(0, 0L, 2, 4L), false, "");

        // Edge 0→1: one known endpoint at the bottom of the scale — thinnest.
        // Edge 1→2: one known endpoint at the top — thickest.
        assertThat(weighted.edgeBucketAt(0)).isZero();
        assertThat(weighted.edgeBucketAt(1)).isEqualTo(GraphModel.EDGE_BUCKETS - 1);
        assertThat(weighted.maxEdgeBucket()).isEqualTo(GraphModel.EDGE_BUCKETS - 1);
    }

    @Test
    @DisplayName("with no weight spread every edge stays in the thin bucket")
    void noSpreadMeansOnePass() {
        GraphModel unweighted = chainModel();

        // All durations unknown: max weight is zero, so every bucket is the
        // base one and the canvas pays for a single edge pass, exactly as it
        // did before weights existed.
        assertThat(unweighted.maxEdgeBucket()).isZero();
        assertThat(unweighted.radiusScaleAt(0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("switching back to duration restores the duration encoding")
    void durationRoundTrips() {
        GraphModel weighted = chainModel().withWeights(
                GraphWeight.IMMEDIATE_DEPS, Map.of(0, 0L, 2, 4L), false, "");

        GraphModel back = weighted.withDurationWeight();

        assertThat(back.weight()).isEqualTo(GraphWeight.DURATION);
        // Nothing here was timed, so the round trip lands on all-unknown.
        assertThat(back.unweightedCount()).isEqualTo(back.size());
        assertThat(back.layout()).isSameAs(weighted.layout());
    }
}
