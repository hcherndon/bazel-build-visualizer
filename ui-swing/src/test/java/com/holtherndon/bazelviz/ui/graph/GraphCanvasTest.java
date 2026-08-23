package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the canvas draws, and what it refuses to claim.
 *
 * <p>Painting is exercised against an offscreen image rather than a window, so
 * these run headless — which is also how the checks that matter are stated:
 * about the model the canvas is handed, not about pixels.
 */
final class GraphCanvasTest {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static CsrGraph chain(int nodes) {
        return CsrBuilder.build(nodes, visitor -> {
            for (int i = 0; i + 1 < nodes; i++) {
                visitor.edge(i, i + 1);
            }
        });
    }

    private static GraphLayoutService.Rendered rendered(int nodes) {
        GraphExtract.Result extract = GraphExtract.whole(chain(nodes), 1_000, 1_000);
        return new GraphLayoutService.Rendered(
                GraphLayoutService.Request.whole(
                        com.holtherndon.bazelviz.core.graph.EdgeDerivation.DECLARED, 1_000, 1_000),
                extract,
                GraphLayout.layered(extract, RUNNING),
                null,
                extract.describe());
    }

    private static GraphModel modelOf(int nodes, long[] durations) {
        String[] labels = new String[nodes];
        for (int i = 0; i < nodes; i++) {
            labels[i] = "//pkg:target" + i;
        }
        return GraphModel.of(rendered(nodes), labels, durations);
    }

    private static long[] allTimed(int nodes) {
        long[] durations = new long[nodes];
        for (int i = 0; i < nodes; i++) {
            durations[i] = (i + 1) * 1_000L;
        }
        return durations;
    }

    @Test
    @DisplayName("an untimed node is coloured unknown, not as if it were instant")
    void untimedIsNotInstant() {
        long[] durations = allTimed(5);
        durations[2] = GraphModel.UNKNOWN_DURATION;

        GraphModel model = modelOf(5, durations);

        // Rule 11. "Took no time" and "nothing measured this" look identical on
        // a heat ramp and mean opposite things.
        assertThat(model.colourAt(2)).isEqualTo(GraphColours.UNKNOWN);
        assertThat(model.colourAt(0)).isNotEqualTo(GraphColours.UNKNOWN);
        assertThat(model.durationAt(2)).isEmpty();
        assertThat(model.durationAt(0)).hasValue(1_000L);
        assertThat(model.untimedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a graph nothing timed reports no slowest rather than a slowest of zero")
    void nothingTimedAtAll() {
        long[] none = new long[4];
        java.util.Arrays.fill(none, GraphModel.UNKNOWN_DURATION);

        GraphModel model = modelOf(4, none);

        assertThat(model.slowestDuration()).isEmpty();
        assertThat(model.untimedCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("a node with no label reads as unnamed rather than as blank")
    void unnamedNodes() {
        String[] labels = new String[3];
        labels[0] = "//pkg:known";

        GraphModel model = GraphModel.of(rendered(3), labels, allTimed(3));

        assertThat(model.labelAt(1)).isNull();
        assertThat(model.displayLabelAt(1)).isEqualTo("(name not recorded)");
        assertThat(model.displayLabelAt(0)).isEqualTo("//pkg:known");
    }

    @Test
    @DisplayName("a cluster view is named from its clustering, not from node lookups")
    void clusterLabelsComeFromTheClustering() {
        String[] keys = {"//a", "//a", "//b", "//b", "//b"};
        GraphClustering.Result clustering = GraphClustering.cluster(
                chain(5), keys, GraphClustering.By.PACKAGE, 100, RUNNING);
        GraphExtract.Result extract = clustering.asExtract();
        GraphLayoutService.Rendered clustered = new GraphLayoutService.Rendered(
                GraphLayoutService.Request.clustered(
                        com.holtherndon.bazelviz.core.graph.EdgeDerivation.DECLARED,
                        GraphClustering.By.PACKAGE),
                extract,
                GraphLayout.grid(extract, RUNNING),
                clustering,
                clustering.describe());

        // Deliberately passing session arrays that would produce nonsense if
        // they were consulted: cluster ordinals are not node indices.
        GraphModel model = GraphModel.of(
                clustered, new String[] {"WRONG", "ALSO WRONG"}, new long[] {99, 98});

        assertThat(model.isCluster()).isTrue();
        assertThat(model.displayLabelAt(0)).isEqualTo("//a  (2)");
        assertThat(model.displayLabelAt(1)).isEqualTo("//b  (3)");
        assertThat(model.durationAt(0)).isEmpty();
    }

    @Test
    @DisplayName("the model carries the sentence the view must show")
    void descriptionSurvives() {
        assertThat(modelOf(5, allTimed(5)).description()).contains("from a graph of 5");
    }

    @Test
    @DisplayName("edges are translated to layout positions once, and the same array comes back")
    void edgePositionsArePrecomputed() {
        GraphModel model = modelOf(6, allTimed(6));

        int[][] first = model.edgePositions();
        assertThat(model.edgePositions()).isSameAs(first);
        assertThat(first[0]).hasSize(5);
        // Producer before consumer, as positions into the layout.
        for (int e = 0; e < first[0].length; e++) {
            assertThat(model.layout().xAt(first[0][e]))
                    .isLessThan(model.layout().xAt(first[1][e]));
        }
    }

    @Test
    @DisplayName("the canvas paints a graph without touching anything it does not hold")
    void paintingWorks() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(400, 300);
        canvas.setModel(modelOf(40, allTimed(40)));

        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }

        // Something was drawn: at least one pixel differs from the background.
        boolean anythingDrawn = false;
        for (int x = 0; x < 400 && !anythingDrawn; x++) {
            for (int y = 0; y < 300; y++) {
                if ((image.getRGB(x, y) & 0x00FFFFFF) != 0x00FFFFFF) {
                    anythingDrawn = true;
                    break;
                }
            }
        }
        assertThat(anythingDrawn).isTrue();
    }

    @Test
    @DisplayName("an empty canvas paints without failing")
    void paintingNothing() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(200, 100);

        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }
        assertThat(canvas.model().size()).isZero();
    }

    @Test
    @DisplayName("fitting frames the whole graph inside the window")
    void fittingFramesEverything() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(30, allTimed(30)));

        canvas.fitToView();

        GraphTransform transform = canvas.transform();
        GraphModel model = canvas.model();
        for (int i = 0; i < model.size(); i++) {
            assertThat(transform.screenX(model.layout().xAt(i))).isBetween(-1.0, 801.0);
            assertThat(transform.screenY(model.layout().yAt(i))).isBetween(-1.0, 601.0);
        }
    }

    @Test
    @DisplayName("hit testing through the canvas finds the node under a screen point")
    void hitTesting() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(20, allTimed(20)));

        GraphTransform transform = canvas.transform();
        int screenX = (int) Math.round(transform.screenX(canvas.model().layout().xAt(7)));
        int screenY = (int) Math.round(transform.screenY(canvas.model().layout().yAt(7)));

        assertThat(canvas.positionAt(screenX, screenY)).hasValue(7);
    }

    @Test
    @DisplayName("selection reports graph node indices, not layout positions")
    void selectionSpeaksInNodeIds() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        GraphExtract.Result extract = GraphExtract.dependents(
                CsrBuilder.reverse(chain(10)), 9, 4, 100);
        canvas.setModel(GraphModel.of(
                new GraphLayoutService.Rendered(
                        null, extract, GraphLayout.layered(extract, RUNNING), null,
                        extract.describe()),
                null, null));

        canvas.select(0);

        // Position 0 of a reverse traversal from node 9 is node 9, not node 0.
        // Confusing the two is how a UI ends up inspecting the wrong action.
        assertThat(canvas.selectedPositions()).containsExactly(0);
        assertThat(canvas.selectedNodes()).containsExactly(extract.nodes().get(0));
        assertThat(extract.nodes().get(0)).isEqualTo(9);
    }

    @Test
    @DisplayName("selection changes are reported to whoever is listening")
    void selectionIsObservable() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(400, 300);
        canvas.setModel(modelOf(10, allTimed(10)));
        int[][] seen = new int[1][];
        canvas.onSelectionChanged(positions -> seen[0] = positions);

        canvas.select(4);

        assertThat(seen[0]).containsExactly(4);
    }

    @Test
    @DisplayName("setting a new model clears the old selection")
    void modelChangeClearsSelection() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(400, 300);
        canvas.setModel(modelOf(10, allTimed(10)));
        canvas.select(3);

        canvas.setModel(modelOf(4, allTimed(4)));

        // A selection carried across models would point at a node in a graph
        // that is no longer on screen.
        assertThat(canvas.selectedPositions()).isEmpty();
    }
}
