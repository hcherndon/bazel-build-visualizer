package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
                        com.holtherndon.bazelviz.core.graph.GraphKind.DECLARED_ACTIONS, 1_000, 1_000),
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
                        com.holtherndon.bazelviz.core.graph.GraphKind.DECLARED_ACTIONS,
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
    @DisplayName("a model set before the window has a size is fitted once it does")
    void fitIsDeferredUntilThereIsSomethingToFitInto() {
        GraphCanvas canvas = new GraphCanvas();
        // No size yet: exactly the state on the first session opened, before
        // the window has been laid out.
        canvas.setModel(modelOf(30, allTimed(30)));
        GraphTransform beforeLayout = canvas.transform();

        canvas.setSize(800, 600);
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }

        // Fitting to a one-pixel window would have produced an absurd zoom and
        // the user would have seen it.
        assertThat(canvas.transform()).isNotEqualTo(beforeLayout);
        GraphModel model = canvas.model();
        for (int i = 0; i < model.size(); i++) {
            assertThat(canvas.transform().screenX(model.layout().xAt(i)))
                    .isBetween(-1.0, 801.0);
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

    // ------------------------------------------------------------- plumbing

    private static void paintOnce(GraphCanvas canvas) {
        BufferedImage image = new BufferedImage(
                Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            canvas.paint(g);
        } finally {
            g.dispose();
        }
    }

    private static void mouse(GraphCanvas canvas, int id, int x, int y) {
        canvas.dispatchEvent(new java.awt.event.MouseEvent(
                canvas, id, System.currentTimeMillis(), 0, x, y, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
    }

    private static int screenXOf(GraphCanvas canvas, int position) {
        return (int) Math.round(
                canvas.transform().screenX(canvas.model().layout().xAt(position)));
    }

    private static int screenYOf(GraphCanvas canvas, int position) {
        return (int) Math.round(
                canvas.transform().screenY(canvas.model().layout().yAt(position)));
    }

    // ------------------------------------------------------- label declutter

    /** Labels wide enough that neighbours at the near band must collide. */
    private static GraphModel longLabelled(int nodes) {
        String[] labels = new String[nodes];
        for (int i = 0; i < nodes; i++) {
            labels[i] = "//declutter/averylongpackagename:target_number_" + i;
        }
        return GraphModel.of(rendered(nodes), labels, allTimed(nodes));
    }

    @Test
    @DisplayName("labels never paint over labels, and the skipped ones are counted")
    void labelsAreDecluttered() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(longLabelled(6));
        assertThat(canvas.detail()).isEqualTo(GraphCanvas.Detail.NEAR);

        paintOnce(canvas);

        // Labels far wider than the gap between nodes cannot all fit; some
        // must be skipped, and the skip is counted rather than silent.
        assertThat(canvas.paintedLabelPositionsForTesting()).isNotEmpty();
        assertThat(canvas.declutteredLabelCount()).isPositive();
        assertThat(canvas.paintedLabelPositionsForTesting().size()
                + canvas.declutteredLabelCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("the declutter keeps the same labels every frame")
    void declutterIsDeterministic() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(longLabelled(6));

        paintOnce(canvas);
        java.util.List<Integer> first = canvas.paintedLabelPositionsForTesting();
        int skipped = canvas.declutteredLabelCount();
        assertThat(skipped).isPositive();
        for (int frame = 0; frame < 5; frame++) {
            paintOnce(canvas);
            assertThat(canvas.paintedLabelPositionsForTesting()).isEqualTo(first);
            assertThat(canvas.declutteredLabelCount()).isEqualTo(skipped);
        }
    }

    @Test
    @DisplayName("a selected node's label always paints, and paints first")
    void selectionOutranksTheDeclutter() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(longLabelled(6));

        paintOnce(canvas);
        // Pick a node whose label the declutter skipped, then select it: the
        // selection must win its space back.
        int skippedPosition = -1;
        for (int position = 0; position < 6; position++) {
            if (!canvas.paintedLabelPositionsForTesting().contains(position)) {
                skippedPosition = position;
                break;
            }
        }
        assertThat(skippedPosition).isNotNegative();

        canvas.select(skippedPosition);
        paintOnce(canvas);

        assertThat(canvas.paintedLabelPositionsForTesting().get(0))
                .isEqualTo(skippedPosition);
    }

    @Test
    @DisplayName("the medium band labels the selection and nothing else")
    void mediumBandLabelsOnlyTheSelection() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(15, allTimed(15)));
        assertThat(canvas.detail()).isEqualTo(GraphCanvas.Detail.MEDIUM);

        paintOnce(canvas);
        assertThat(canvas.paintedLabelPositionsForTesting()).isEmpty();

        canvas.select(7);
        paintOnce(canvas);
        assertThat(canvas.paintedLabelPositionsForTesting()).containsExactly(7);
    }

    // ------------------------------------------------------ label-aware fit

    @Test
    @DisplayName("fit leaves room for the labels visible at the fitted zoom")
    void fitReservesLabelRoom() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(5, allTimed(5)));
        assertThat(canvas.detail()).isEqualTo(GraphCanvas.Detail.NEAR);

        java.awt.FontMetrics metrics = canvas.getFontMetrics(canvas.labelFont());
        double radius = Math.max(2.5, 9 * canvas.transform().scale());
        GraphModel model = canvas.model();
        for (int i = 0; i < model.size(); i++) {
            double labelRight = canvas.transform().screenX(model.layout().xAt(i))
                    + radius + 4 + metrics.stringWidth(model.displayLabelAt(i));
            // Node geometry alone would push the last column's text off the
            // window; the label-aware fit must not.
            assertThat(labelRight)
                    .as("label %d ends on screen", i)
                    .isLessThanOrEqualTo(800);
        }
        assertThat(canvas.hiddenDetail()).isEmpty();
    }

    @Test
    @DisplayName("a label wider than the reservation cap is admitted, not absorbed")
    void fitLabelCapIsReported() {
        String[] labels = new String[3];
        labels[0] = "//very:long" + "x".repeat(400);
        labels[1] = "//pkg:b";
        labels[2] = "//pkg:c";
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(GraphModel.of(rendered(3), labels, allTimed(3)));

        // The cap kept the drawing usable...
        assertThat(canvas.transform().scale()).isGreaterThan(0.6);
        // ...and the canvas says the text does not all fit, rather than
        // either zooming to nothing or silently clipping.
        assertThat(canvas.hiddenDetail())
                .isPresent()
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("capped at half the window");
    }

    // --------------------------------------------------------- node dragging

    @Test
    @DisplayName("dragging a node moves it, in the view only, and hit testing follows")
    void draggingMovesANode() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(10, allTimed(10)));
        GraphTransform before = canvas.transform();
        int sx = screenXOf(canvas, 4);
        int sy = screenYOf(canvas, 4);

        mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, sx, sy);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED, sx + 30, sy + 18);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, sx + 30, sy + 18);

        // The camera did not move: this was a node drag, not a pan.
        assertThat(canvas.transform()).isEqualTo(before);
        double[] offset = canvas.dragOffsetForTesting(4);
        assertThat(offset).isNotNull();
        assertThat(offset[0]).isCloseTo(30 / before.scale(), within(1e-6));
        assertThat(offset[1]).isCloseTo(18 / before.scale(), within(1e-6));
        // Hit testing respects the overlay: the node is where the user put
        // it, and its old spot is empty canvas.
        assertThat(canvas.positionAt(sx + 30, sy + 18)).hasValue(4);
        assertThat(canvas.positionAt(sx, sy)).isEmpty();
        // The layout and the spatial index never moved; only the view-layer
        // overlay did. The shared index still answers with the laid-out
        // position, which is exactly why positionAt must filter it.
        assertThat(canvas.model().index().nearest(
                before.worldX(sx), before.worldY(sy), 12 / before.scale()))
                .hasValue(4);
    }

    @Test
    @DisplayName("a press on empty canvas still pans")
    void emptyPressStillPans() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(10, allTimed(10)));
        GraphTransform before = canvas.transform();

        mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, 780, 580);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED, 700, 500);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, 700, 500);

        assertThat(canvas.transform()).isNotEqualTo(before);
        assertThat(canvas.hasDragOffsets()).isFalse();
    }

    @Test
    @DisplayName("dragged positions survive a restyle and reset on a new layout")
    void dragOffsetsSurviveRestyleAndResetOnRelayout() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(10, allTimed(10)));
        int sx = screenXOf(canvas, 4);
        int sy = screenYOf(canvas, 4);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, sx, sy);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED, sx + 30, sy + 18);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, sx + 30, sy + 18);
        assertThat(canvas.hasDragOffsets()).isTrue();

        // A weight change restyles the same layout: positions are shared by
        // design, so the user's arrangement stays.
        canvas.restyle(canvas.model().withDurationWeight());
        assertThat(canvas.dragOffsetForTesting(4)).isNotNull();

        // A new model is a new layout: offsets against the old positions
        // would displace unrelated nodes.
        canvas.setModel(modelOf(10, allTimed(10)));
        assertThat(canvas.hasDragOffsets()).isFalse();
    }

    @Test
    @DisplayName("the reset action puts every dragged node back explicitly")
    void resetPositionsClearsTheOverlay() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(10, allTimed(10)));
        int sx = screenXOf(canvas, 4);
        int sy = screenYOf(canvas, 4);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_PRESSED, sx, sy);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_DRAGGED, sx + 40, sy);
        mouse(canvas, java.awt.event.MouseEvent.MOUSE_RELEASED, sx + 40, sy);
        assertThat(canvas.positionAt(sx, sy)).isEmpty();

        canvas.resetDragOffsets();

        assertThat(canvas.hasDragOffsets()).isFalse();
        assertThat(canvas.positionAt(sx, sy)).hasValue(4);
    }

    // ------------------------------------------------------ direction arrows

    @Test
    @DisplayName("arrowheads point producer to consumer, and only where edges are distinct")
    void arrowsFollowTheStoredDirection() {
        GraphCanvas canvas = new GraphCanvas();
        canvas.setSize(800, 600);
        canvas.setModel(modelOf(5, allTimed(5)));
        assertThat(canvas.detail()).isEqualTo(GraphCanvas.Detail.NEAR);

        paintOnce(canvas);
        // A five-node chain has four edges; each visible, distinct edge gets
        // its head.
        assertThat(canvas.arrowsDrawnForTesting()).isEqualTo(4);

        // Zoomed out, edges stop being individually distinguishable and the
        // heads go away rather than smearing.
        canvas.zoomForTesting(0.3);
        assertThat(canvas.detail()).isNotEqualTo(GraphCanvas.Detail.NEAR);
        paintOnce(canvas);
        assertThat(canvas.arrowsDrawnForTesting()).isZero();
    }

    @Test
    @DisplayName("the arrow tip sits at the consumer end, pulled back to the node's rim")
    void arrowTipGeometry() {
        double[] tip = GraphCanvas.arrowTip(0, 0, 100, 0, 10);

        assertThat(tip).isNotNull();
        // The edge is stored producer to consumer, so the head belongs at
        // (100, 0), the consumer, ten pixels short of its centre.
        assertThat(tip[0]).isEqualTo(90);
        assertThat(tip[1]).isEqualTo(0);

        // An edge too short on screen for a legible head gets none.
        assertThat(GraphCanvas.arrowTip(0, 0, 10, 0, 5)).isNull();
    }
}
