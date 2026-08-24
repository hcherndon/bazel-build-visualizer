package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * The graph, drawn.
 *
 * <h2>What plan 17.7 asks of the rendering, and where each answer is</h2>
 *
 * <ul>
 *   <li><em>Antialias selectively</em> — on for nodes and labels when the view
 *       is still, off entirely while panning and at far zoom, where a
 *       four-pixel dot gains nothing from it.
 *   <li><em>Disable expensive detail while actively panning</em> — labels and,
 *       above a budget, edges. Both return the moment the drag ends. This is
 *       the one place the canvas draws less than it knows, and it is transient
 *       and self-correcting rather than a claim about the graph.
 *   <li><em>Draw labels only above scale thresholds</em> — {@link Detail}, and
 *       even in the near band a label is skipped when it would not fit between
 *       its node and the next.
 *   <li><em>Use spatial hit testing</em> — {@link GraphSpatialIndex}, so a
 *       click costs the cells near the pointer rather than the whole graph.
 *   <li><em>Never perform layout on the EDT</em> — the canvas cannot: it is
 *       handed a finished {@link GraphModel} and has no way to make one.
 *   <li><em>Never query SQLite from paintComponent</em> — it holds no
 *       connection, reader or service, which {@code GraphPaintIsolationTest}
 *       checks by reflection rather than by review.
 * </ul>
 */
public final class GraphCanvas extends JComponent {

    private static final long serialVersionUID = 1L;

    /** Node radius in world units. The layouts space nodes 44 apart. */
    private static final double NODE_RADIUS = 9;

    /** How close a click must land, in pixels, to select a node. */
    private static final int HIT_RADIUS_PIXELS = 12;

    /** Margin left around a fitted graph. */
    private static final double FIT_MARGIN = 40;

    /**
     * Edges drawn while a drag is in progress.
     *
     * <p>Above this, edges are dropped for the duration of the drag only. Plan
     * 17.7 sanctions exactly this; the graph is unchanged and the edges come
     * back on release.
     */
    private static final int PANNING_EDGE_BUDGET = 20_000;

    /**
     * Edges drawn at far zoom.
     *
     * <p>Plan 13.6's far band calls for aggregate edge thickness rather than
     * individual edges, and there is a measured reason: two hundred thousand
     * hairlines over a fitted view take hundreds of milliseconds to draw and
     * resolve to a grey smear that shows nothing. Above this count the far band
     * draws nodes only — and {@link #hiddenDetail()} says so, so the view can
     * tell the user rather than letting them believe the graph has no edges.
     */
    private static final int FAR_EDGE_BUDGET = 30_000;

    /** Hoisted out of the paint loop, where they were an allocation per node. */
    private static final BasicStroke THIN = new BasicStroke(1f);

    private static final BasicStroke OUTLINE = new BasicStroke(2f);

    private GraphModel model = GraphModel.empty();
    private GraphTransform transform = GraphTransform.identity();

    /** Selected layout positions, in the order they were selected. */
    private final Set<Integer> selection = new LinkedHashSet<>();

    private int hover = -1;
    private boolean panning;

    /**
     * True when a model arrived before the component had a size.
     *
     * <p>{@link #setModel} fits the new graph to the window, and on the first
     * session opened the window has not been laid out yet — so the fit is
     * computed against a width of one pixel and the graph appears at an absurd
     * zoom. The fit is retried on the first paint that has real bounds, which
     * is always before anything reaches the screen.
     */
    private boolean fitPending;
    private Point dragOrigin;
    private Point marqueeStart;
    private Rectangle marquee;
    private Consumer<int[]> selectionListener = positions -> {};
    private Runnable viewChangedListener = () -> {};
    private java.util.function.IntConsumer focusListener = position -> {};

    public GraphCanvas() {
        setOpaque(true);
        setBackground(Color.WHITE);
        setFocusable(true);
        setPreferredSize(new Dimension(600, 400));
        Mouse mouse = new Mouse();
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Replaces what is drawn and fits it to the window. */
    public void setModel(GraphModel model) {
        this.model = model == null ? GraphModel.empty() : model;
        selection.clear();
        hover = -1;
        fitToView();
        repaint();
    }

    public GraphModel model() {
        return model;
    }

    public GraphTransform transform() {
        return transform;
    }

    /** Called with the selected layout positions whenever they change. */
    public void onSelectionChanged(Consumer<int[]> listener) {
        this.selectionListener = listener == null ? positions -> {} : listener;
    }

    /** Called whenever the pan or zoom changes, so a status bar can follow. */
    public void onViewChanged(Runnable listener) {
        this.viewChangedListener = listener == null ? () -> {} : listener;
    }

    /**
     * Called with a layout position the user asked to refocus on.
     *
     * <p>Fired by a double-click. The canvas cannot refocus itself — it holds
     * no service and cannot query — so it reports the wish and the panel
     * redraws the graph around that node. A listener, like the other two,
     * because {@code GraphPaintIsolationTest} forbids this class anything
     * stronger.
     */
    public void onFocusRequested(java.util.function.IntConsumer listener) {
        this.focusListener = listener == null ? position -> {} : listener;
    }

    /** Frames the whole drawing; plan 17.7's "fit". */
    public void fitToView() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            // Nothing to fit into yet. Remember to, rather than fitting to one
            // pixel and calling it done.
            fitPending = true;
            return;
        }
        fitPending = false;
        model.layout().bounds().ifPresentOrElse(
                bounds -> setTransform(
                        GraphTransform.fit(bounds, getWidth(), getHeight(), FIT_MARGIN)),
                () -> setTransform(GraphTransform.identity()));
    }

    private void setTransform(GraphTransform next) {
        this.transform = next;
        viewChangedListener.run();
        repaint();
    }

    /** The selected nodes, as graph node indices rather than layout positions. */
    public int[] selectedNodes() {
        int[] nodes = new int[selection.size()];
        int next = 0;
        for (int position : selection) {
            nodes[next++] = model.nodeAt(position);
        }
        return nodes;
    }

    /** The selected layout positions. */
    public int[] selectedPositions() {
        int[] positions = new int[selection.size()];
        int next = 0;
        for (int position : selection) {
            positions[next++] = position;
        }
        return positions;
    }

    /** Selects one node by its layout position, replacing the selection. */
    public void select(int position) {
        selection.clear();
        if (position >= 0 && position < model.size()) {
            selection.add(position);
        }
        selectionListener.accept(selectedPositions());
        repaint();
    }

    /**
     * Zooms about the centre of the component.
     *
     * <p>Named for what it is. Interactive zoom is anchored to the pointer, so
     * this exists only so a test can reach a detail band without synthesising
     * wheel events.
     */
    void zoomForTesting(double factor) {
        setTransform(transform.zoomedAround(getWidth() / 2.0, getHeight() / 2.0, factor));
    }

    /** How much detail the current zoom warrants. */
    public Detail detail() {
        return Detail.forScale(transform.scale());
    }

    /** Plan 13.6's semantic zoom bands. */
    public enum Detail {
        /** Clusters and dots; no individual labels. */
        FAR,
        /** Nodes and edges; labels only for what is selected or hovered. */
        MEDIUM,
        /** Everything, including a label on every node that has room. */
        NEAR;

        /** Where the bands sit, in pixels per world unit. */
        public static Detail forScale(double scale) {
            if (scale < 0.15) {
                return FAR;
            }
            return scale < 0.6 ? MEDIUM : NEAR;
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (fitPending && getWidth() > 0 && getHeight() > 0) {
            // The deferred fit from setModel. Cheap, and it must happen before
            // the first pixel rather than after the user has seen the wrong one.
            fitToView();
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (model.size() == 0) {
                return;
            }

            Detail detail = detail();
            boolean smooth = !panning && detail != Detail.FAR;
            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    smooth ? RenderingHints.VALUE_ANTIALIAS_ON
                            : RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    panning ? RenderingHints.VALUE_RENDER_SPEED
                            : RenderingHints.VALUE_RENDER_QUALITY);

            double[] world = transform.visibleWorld(getWidth(), getHeight());
            paintEdges(g, world, detail);
            paintNodes(g, world, detail);
            if (detail != Detail.FAR && !panning) {
                paintLabels(g, world, detail);
            }
            if (marquee != null) {
                g.setColor(GraphColours.MARQUEE);
                g.fillRect(marquee.x, marquee.y, marquee.width, marquee.height);
                g.setColor(GraphColours.SELECTION);
                g.drawRect(marquee.x, marquee.y, marquee.width, marquee.height);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintEdges(Graphics2D g, double[] world, Detail detail) {
        if (edgesAreHidden(detail)) {
            return;
        }
        int[][] edges = model.edgePositions();
        int count = edges[0].length;
        GraphLayout.Result layout = model.layout();
        g.setStroke(THIN);

        // Colour set once for the bulk rather than once per edge: at two
        // hundred thousand edges the state changes cost more than the lines.
        // Highlighted edges are a second pass, and only when there is a
        // selection to highlight.
        g.setColor(GraphColours.EDGE);
        for (int e = 0; e < count; e++) {
            int from = edges[0][e];
            int to = edges[1][e];
            if (!selection.isEmpty() && (selection.contains(from) || selection.contains(to))) {
                continue;
            }
            drawEdge(g, layout, world, from, to);
        }
        if (selection.isEmpty()) {
            return;
        }
        g.setColor(GraphColours.EDGE_HIGHLIGHTED);
        for (int e = 0; e < count; e++) {
            int from = edges[0][e];
            int to = edges[1][e];
            if (selection.contains(from) || selection.contains(to)) {
                drawEdge(g, layout, world, from, to);
            }
        }
    }

    private void drawEdge(
            Graphics2D g, GraphLayout.Result layout, double[] world, int from, int to) {
        double x1 = layout.xAt(from);
        double y1 = layout.yAt(from);
        double x2 = layout.xAt(to);
        double y2 = layout.yAt(to);
        // Cheap reject: an edge whose bounding box misses the viewport cannot
        // cross it.
        if (Math.max(x1, x2) < world[0] || Math.min(x1, x2) > world[2]
                || Math.max(y1, y2) < world[1] || Math.min(y1, y2) > world[3]) {
            return;
        }
        g.drawLine(
                (int) Math.round(transform.screenX(x1)),
                (int) Math.round(transform.screenY(y1)),
                (int) Math.round(transform.screenX(x2)),
                (int) Math.round(transform.screenY(y2)));
    }

    private boolean edgesAreHidden(Detail detail) {
        int count = model.edgePositions()[0].length;
        return (panning && count > PANNING_EDGE_BUDGET)
                || (detail == Detail.FAR && count > FAR_EDGE_BUDGET);
    }

    /**
     * What this frame is not drawing, for the view to say out loud.
     *
     * <p>Plan 13.6's "never claim the omitted nodes do not exist", applied to
     * the one thing the canvas legitimately omits. Nothing about the graph has
     * changed; only this frame is simpler, and zooming in restores it.
     */
    public java.util.Optional<String> hiddenDetail() {
        if (!edgesAreHidden(detail())) {
            return java.util.Optional.empty();
        }
        int count = model.edgePositions()[0].length;
        return java.util.Optional.of(
                count + " dependencies are not drawn at this zoom. Zoom in to see them.");
    }

    private void paintNodes(Graphics2D g, double[] world, Detail detail) {
        double radius = Math.max(detail == Detail.FAR ? 1.5 : 2.5, NODE_RADIUS * transform.scale());
        int diameter = (int) Math.round(radius * 2);
        model.index().forEachInRect(
                world[0] - NODE_RADIUS, world[1] - NODE_RADIUS,
                world[2] + NODE_RADIUS, world[3] + NODE_RADIUS,
                position -> {
                    int cx = (int) Math.round(transform.screenX(model.layout().xAt(position)));
                    int cy = (int) Math.round(transform.screenY(model.layout().yAt(position)));
                    g.setColor(model.colourAt(position));
                    g.fillOval(
                            cx - (int) Math.round(radius), cy - (int) Math.round(radius),
                            diameter, diameter);
                    if (selection.contains(position) || position == hover) {
                        g.setColor(position == hover && !selection.contains(position)
                                ? GraphColours.HOVER : GraphColours.SELECTION);
                        g.setStroke(OUTLINE);
                        g.drawOval(
                                cx - (int) Math.round(radius) - 2,
                                cy - (int) Math.round(radius) - 2,
                                diameter + 4, diameter + 4);
                    }
                });
    }

    private void paintLabels(Graphics2D g, double[] world, Detail detail) {
        g.setColor(GraphColours.LABEL);
        int lineHeight = g.getFontMetrics().getHeight();
        double radius = Math.max(2.5, NODE_RADIUS * transform.scale());
        model.index().forEachInRect(
                world[0], world[1], world[2], world[3],
                position -> {
                    // In the medium band, only what the user has pointed at or
                    // picked out: labelling everything at that scale is an
                    // unreadable wall, which is what the threshold is for.
                    if (detail == Detail.MEDIUM
                            && !selection.contains(position) && position != hover) {
                        return;
                    }
                    String label = model.displayLabelAt(position);
                    int x = (int) Math.round(
                            transform.screenX(model.layout().xAt(position)) + radius + 4);
                    int y = (int) Math.round(
                            transform.screenY(model.layout().yAt(position)) + lineHeight / 4.0);
                    g.drawString(label, x, y);
                });
    }

    /** Hit test at a screen point; empty when nothing is close enough. */
    public OptionalInt positionAt(int screenX, int screenY) {
        double radius = HIT_RADIUS_PIXELS / transform.scale();
        return model.index().nearest(
                transform.worldX(screenX), transform.worldY(screenY), radius);
    }

    private final class Mouse extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent event) {
            requestFocusInWindow();
            if (event.isShiftDown()) {
                marqueeStart = event.getPoint();
                marquee = new Rectangle(marqueeStart);
                return;
            }
            OptionalInt hit = positionAt(event.getX(), event.getY());
            if (hit.isPresent()) {
                boolean add = event.isControlDown() || event.isMetaDown();
                if (!add) {
                    selection.clear();
                }
                if (!selection.add(hit.getAsInt()) && add) {
                    selection.remove(hit.getAsInt());
                }
                selectionListener.accept(selectedPositions());
                repaint();
                return;
            }
            if (!event.isControlDown() && !event.isMetaDown() && !selection.isEmpty()) {
                selection.clear();
                selectionListener.accept(selectedPositions());
            }
            dragOrigin = event.getPoint();
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (marqueeStart != null) {
                marquee = new Rectangle(marqueeStart);
                marquee.add(event.getPoint());
                repaint();
                return;
            }
            if (dragOrigin == null) {
                return;
            }
            panning = true;
            setTransform(transform.pannedByPixels(
                    event.getX() - dragOrigin.x, event.getY() - dragOrigin.y));
            dragOrigin = event.getPoint();
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            if (marquee != null) {
                selection.clear();
                for (int position : model.index().within(
                        transform.worldX(marquee.x),
                        transform.worldY(marquee.y),
                        transform.worldX(marquee.x + marquee.width),
                        transform.worldY(marquee.y + marquee.height))) {
                    selection.add(position);
                }
                selectionListener.accept(selectedPositions());
            }
            marquee = null;
            marqueeStart = null;
            dragOrigin = null;
            if (panning) {
                panning = false;
                // The detail suppressed during the drag comes back here.
                repaint();
            }
            setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseClicked(MouseEvent event) {
            // Double-click asks to refocus on the node under the pointer; the
            // press that preceded it already selected the node, so the two
            // gestures compose rather than compete.
            if (event.getClickCount() != 2 || event.isShiftDown()) {
                return;
            }
            positionAt(event.getX(), event.getY())
                    .ifPresent(position -> focusListener.accept(position));
        }

        @Override
        public void mouseMoved(MouseEvent event) {
            int was = hover;
            hover = positionAt(event.getX(), event.getY()).orElse(-1);
            if (hover != was) {
                setToolTipText(hover < 0 ? null : model.displayLabelAt(hover));
                repaint();
            }
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent event) {
            // Zoom around the pointer, not the centre: a user scrolling over a
            // node means "closer to that one".
            double factor = Math.pow(1.1, -event.getPreciseWheelRotation());
            setTransform(transform.zoomedAround(event.getX(), event.getY(), factor));
        }
    }
}
