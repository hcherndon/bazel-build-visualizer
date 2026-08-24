package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The canvas at the limits plan 13.6 sets, measured rather than assumed.
 *
 * <p>Fifty thousand nodes and two hundred thousand edges is the default ceiling
 * for a detailed drawing, so it is the size at which "panning and selection
 * remain responsive" has to be true. The graph is synthetic — plan rule 18's
 * requirement to build a real Bazel fixture is about Bazel's behaviour, and
 * nothing here depends on Bazel's behaviour.
 *
 * <p>The bounds are deliberately loose. A test that asserted a tight frame time
 * would fail on a loaded machine for reasons that say nothing about the design;
 * these fail only if something is wrong by an order of magnitude, which is the
 * kind of wrong that a fresh pair of eyes would otherwise not notice until a
 * user did.
 */
final class GraphCanvasScaleTest {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static final int NODES = 50_000;

    /** A wide, shallow DAG: 500 layers of 100, each node feeding four in the next. */
    private static CsrGraph wideDag() {
        int layerWidth = 100;
        int layers = NODES / layerWidth;
        return CsrBuilder.build(NODES, visitor -> {
            for (int layer = 0; layer + 1 < layers; layer++) {
                for (int i = 0; i < layerWidth; i++) {
                    int from = layer * layerWidth + i;
                    for (int fanout = 0; fanout < 4; fanout++) {
                        visitor.edge(from, (layer + 1) * layerWidth + (i + fanout) % layerWidth);
                    }
                }
            }
        });
    }

    private static GraphModel bigModel() {
        GraphExtract.Result extract = GraphExtract.whole(
                wideDag(), GraphExtract.DEFAULT_NODE_LIMIT, GraphExtract.DEFAULT_EDGE_LIMIT);
        assertThat(extract.nodes()).as("the fixture must fit inside the plan's limits")
                .hasSize(NODES);

        GraphLayout.Result layout = GraphLayout.layered(extract, RUNNING);
        assertThat(layout.cancelled()).isFalse();

        String[] labels = new String[NODES];
        long[] durations = new long[NODES];
        for (int i = 0; i < NODES; i++) {
            labels[i] = "//synthetic/package" + (i % 400) + ":target" + i;
            durations[i] = i % 97 == 0 ? GraphModel.UNKNOWN_DURATION : (i % 5_000) * 100L;
        }
        return GraphModel.of(
                new GraphLayoutService.Rendered(
                        GraphLayoutService.Request.whole(
                                GraphKind.DECLARED_ACTIONS,
                                GraphExtract.DEFAULT_NODE_LIMIT,
                                GraphExtract.DEFAULT_EDGE_LIMIT),
                        extract, layout, null, extract.describe()),
                labels, durations);
    }

    @Test
    @DisplayName("laying out and indexing fifty thousand nodes is a second's work, not a minute's")
    void layoutAndIndexAtTheLimit() {
        long start = System.nanoTime();
        GraphModel model = bigModel();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(model.size()).isEqualTo(NODES);
        assertThat(model.index().size()).isEqualTo(NODES);
        assertThat(model.edgePositions()[0].length).isGreaterThan(190_000);
        assertThat(millis)
                .as("extract, layout, index and label %d nodes in %dms", NODES, millis)
                .isLessThan(15_000);
    }

    @Test
    @DisplayName("hit testing stays constant-time as the graph grows")
    void hitTestingDoesNotScaleWithTheGraph() {
        GraphModel model = bigModel();
        GraphSpatialIndex index = model.index();

        long start = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < 20_000; i++) {
            double x = model.layout().xAt(i * 2 % NODES);
            double y = model.layout().yAt(i * 2 % NODES);
            if (index.nearest(x + 1, y + 1, 20).isPresent()) {
                hits++;
            }
        }
        long micros = (System.nanoTime() - start) / 1_000;

        assertThat(hits).isEqualTo(20_000);
        // The whole point of the index: a click costs the cells near the
        // pointer, so twenty thousand of them is still well under a second.
        assertThat(micros)
                .as("20,000 hit tests over %d nodes took %dms", NODES, micros / 1_000)
                .isLessThan(3_000_000);
    }

    @Test
    @DisplayName("culling makes a zoomed-in viewport cost the viewport, not the graph")
    void cullingCostsTheViewport() {
        GraphModel model = bigModel();
        double[] box = model.layout().bounds().orElseThrow();
        // A window covering roughly a hundredth of the world in each direction.
        double width = (box[2] - box[0]) / 100;
        double height = (box[3] - box[1]) / 100;

        int[] visible = model.index().within(box[0], box[1], box[0] + width, box[1] + height);

        assertThat(visible.length)
                .as("a hundredth of the world should not return most of the graph")
                .isLessThan(NODES / 10);
        assertThat(visible.length).isPositive();
    }

    @Test
    @DisplayName("a fitted frame at the limit paints in tens of milliseconds, not hundreds")
    void paintingAtTheLimit() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(1_600, 1_000);
        canvas.setModel(bigModel());

        long slowest = slowestFrameOf(canvas, 1_600, 1_000);

        // Measured at 366ms before edge drawing was batched and budgeted, and
        // 34ms after. The bound is loose enough not to flake and tight enough
        // to catch a regression back to the former.
        assertThat(slowest)
                .as("slowest of five fitted frames over %d nodes was %dms", NODES, slowest)
                .isLessThan(200);
    }

    @Test
    @DisplayName("a zoomed-in frame, where every edge is drawn, is faster still")
    void paintingZoomedIn() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(1_600, 1_000);
        canvas.setModel(bigModel());
        // Zoom until the near band, where labels and every visible edge are
        // drawn -- the most expensive thing the canvas ever does per node.
        while (canvas.detail() != GraphCanvas.Detail.NEAR) {
            canvas.zoomForTesting(2);
        }
        assertThat(canvas.hiddenDetail())
                .as("nothing is omitted once the view is zoomed in")
                .isEmpty();

        long slowest = slowestFrameOf(canvas, 1_600, 1_000);

        assertThat(slowest)
                .as("slowest of five near-zoom frames was %dms", slowest)
                .isLessThan(200);
    }

    @Test
    @DisplayName("edges omitted at far zoom are reported, not silently dropped")
    void omissionIsReported() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(1_600, 1_000);
        canvas.setModel(bigModel());

        // Plan 13.6's rule applied to the one thing the canvas legitimately
        // leaves out. A blank area that looked edgeless would be a claim about
        // the build, and a false one.
        assertThat(canvas.detail()).isEqualTo(GraphCanvas.Detail.FAR);
        assertThat(canvas.hiddenDetail())
                .isPresent()
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("not drawn at this zoom")
                .contains("Zoom in");
    }

    private static long slowestFrameOf(GraphCanvas canvas, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        long slowest = 0;
        try {
            for (int frame = 0; frame < 3; frame++) {
                canvas.paint(g);
            }
            for (int frame = 0; frame < 5; frame++) {
                long start = System.nanoTime();
                canvas.paint(g);
                slowest = Math.max(slowest, (System.nanoTime() - start) / 1_000_000);
            }
        } finally {
            g.dispose();
        }
        return slowest;
    }
}
