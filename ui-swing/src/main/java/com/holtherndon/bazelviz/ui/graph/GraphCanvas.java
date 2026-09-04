package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.ui.theme.CanvasAccessibility;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The graph, drawn.
 *
 * <h2>What plan 17.7 asks of the rendering, and where each answer is</h2>
 *
 * <ul>
 *   <li><em>Antialias selectively</em> — on for nodes and labels when the view is still, off
 *       entirely while panning and at far zoom, where a four-pixel dot gains nothing from it.
 *   <li><em>Disable expensive detail while actively panning</em> — labels and, above a budget,
 *       edges. Both return the moment the drag ends. This is the one place the canvas draws less
 *       than it knows, and it is transient and self-correcting rather than a claim about the graph.
 *   <li><em>Draw labels only above scale thresholds</em> — {@link Detail}, and within a band a
 *       label is skipped when it would paint over one already painted: screen-space collision with
 *       a deterministic priority (selected, then hovered, then higher weight, then lower node
 *       index), so the labels that survive are the same ones every frame. The skip is a
 *       rendering-density decision like the bands themselves — the count is {@link
 *       #declutteredLabelCount()}, and the first selected label always paints because nothing
 *       outranks it.
 *   <li><em>Use spatial hit testing</em> — {@link GraphSpatialIndex}, so a click costs the cells
 *       near the pointer rather than the whole graph.
 *   <li><em>Never perform layout on the EDT</em> — the canvas cannot: it is handed a finished
 *       {@link GraphModel} and has no way to make one.
 *   <li><em>Never query SQLite from paintComponent</em> — it holds no connection, reader or
 *       service, which {@code GraphPaintIsolationTest} checks by reflection rather than by review.
 * </ul>
 */
public final class GraphCanvas extends JComponent {

  private static final long serialVersionUID = 1L;

  /** Node radius in world units. The layouts space nodes 44 apart. */
  private static final double NODE_RADIUS = 9;

  /** How close a click must land, in pixels, to select a node. */
  private static final int HIT_RADIUS_PIXELS = 12;

  /**
   * Pixels a press-on-node must move before it becomes a node drag.
   *
   * <p>Without it, the one-pixel jitter inside an ordinary click-select writes a permanent
   * sub-pixel offset: {@code hasDragOffsets()} flips true, "Reset positions" arms, and hit testing
   * takes the filtered path — all for a move nobody made on purpose.
   */
  private static final int NODE_DRAG_THRESHOLD_PIXELS = 3;

  /** Margin left around a fitted graph. */
  private static final double FIT_MARGIN = 40;

  /**
   * The most of the viewport width Fit will reserve for label text.
   *
   * <p>Fit accounts for the labels visible at the resulting zoom, but a dense graph of long names
   * could reserve everything and zoom the nodes to nothing. Past this fraction the reservation is
   * capped and {@link #hiddenDetail()} says so, rather than either lying about the text fitting or
   * vanishing the graph.
   */
  static final double MAX_LABEL_FIT_FRACTION = 0.5;

  /** Arrowhead wing length in pixels; drawn only in the near band. */
  private static final double ARROW_LENGTH_PIXELS = 6;

  /**
   * Edges shorter than this on screen get no arrowhead.
   *
   * <p>An arrow longer than its edge points at nothing legible; the edge is still drawn, and
   * zooming in restores the head.
   */
  private static final double MIN_ARROW_EDGE_PIXELS = 14;

  /**
   * Edges drawn while a drag is in progress.
   *
   * <p>Above this, edges are dropped for the duration of the drag only. Plan 17.7 sanctions exactly
   * this; the graph is unchanged and the edges come back on release.
   */
  private static final int PANNING_EDGE_BUDGET = 20_000;

  /**
   * Edges drawn at far zoom.
   *
   * <p>Plan 13.6's far band calls for aggregate edge thickness rather than individual edges, and
   * there is a measured reason: two hundred thousand hairlines over a fitted view take hundreds of
   * milliseconds to draw and resolve to a grey smear that shows nothing. Above this count the far
   * band draws nodes only — and {@link #hiddenDetail()} says so, so the view can tell the user
   * rather than letting them believe the graph has no edges.
   */
  private static final int FAR_EDGE_BUDGET = 30_000;

  /** Primary hierarchy branches retained per horizontal pixel at far zoom. */
  static final int FAR_HIERARCHY_BRANCHES_PER_PIXEL = 2;

  /** Hoisted out of the paint loop, where they were an allocation per node. */
  private static final BasicStroke THIN = new BasicStroke(1f);

  private static final BasicStroke OUTLINE = new BasicStroke(2f);

  /** Dashed cross-links stay distinguishable from the hierarchy's branches. */
  private static final BasicStroke CROSS_LINK =
      new BasicStroke(
          1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {4f, 4f}, 0f);

  /**
   * One stroke per weight bucket, thinnest first.
   *
   * <p>The thinnest is {@link #THIN} itself, so a drawing whose weights are all unknown — or whose
   * weight has no spread — paints exactly as it did before weights existed. A fixed array rather
   * than computed widths keeps stroke changes to a handful per frame instead of one per edge.
   */
  private static final BasicStroke[] EDGE_STROKES = {
    THIN, new BasicStroke(1.75f), new BasicStroke(2.5f), new BasicStroke(3.25f),
  };

  private GraphModel model = GraphModel.empty();
  private GraphTransform transform = GraphTransform.identity();
  private GraphEdgeDisplay edgeDisplay = GraphEdgeDisplay.DECLUTTERED;

  /** Selected layout positions, in the order they were selected. */
  private final Set<Integer> selection = new LinkedHashSet<>();

  /** Cross-links revealed by the current selection, computed only when it changes. */
  private int revealedCrossLinks;

  private int hover = -1;
  private boolean panning;

  /**
   * True when a model arrived before the component had a size.
   *
   * <p>{@link #setModel} fits the new graph to the window, and on the first session opened the
   * window has not been laid out yet — so the fit is computed against a width of one pixel and the
   * graph appears at an absurd zoom. The fit is retried on the first paint that has real bounds,
   * which is always before anything reaches the screen.
   */
  private boolean fitPending;

  private Point dragOrigin;
  private Point marqueeStart;
  private Rectangle marquee;

  /**
   * The view-layer drag overlay: world-space displacement per layout position, for nodes the user
   * has dragged.
   *
   * <p>The one mutable statement about positions this class is allowed: {@code GraphLayout.Result},
   * the spatial index and the cached {@code Rendered} are shared with the layout cache and are
   * never written. Offsets survive {@link #restyle} — a weight change shares positions by design —
   * and are cleared by {@link #setModel}, which is where every new layout arrives, and by {@link
   * #resetDragOffsets}.
   */
  private final Map<Integer, double[]> dragOffsets = new HashMap<>();

  /** The layout position armed for dragging by a press, or -1. */
  private int nodeDrag = -1;

  private Point nodeDragPoint;

  /** True once an armed press has moved past the threshold. */
  private boolean nodeDragging;

  /** Labels skipped for overlap in the last paint; the declutter's count. */
  private int declutteredLabels;

  /** The positions whose labels the last paint drew, in paint order. */
  private final List<Integer> paintedLabels = new ArrayList<>();

  /** Arrowheads the last paint drew; zero outside the near band. */
  private int arrowsInLastPaint;

  /** Hierarchy branches and cross-links actually drawn in the last paint. */
  private int primaryEdgesInLastPaint;

  private int crossLinksInLastPaint;

  /** True this frame when edges get direction arrowheads. */
  private boolean arrowsThisFrame;

  /** The node radius in pixels this frame, for pulling arrow tips back. */
  private double arrowRadiusPixels;

  /** The cap sentence from the last fit, or empty; cleared by user pan/zoom. */
  private String fitLabelNote = "";

  private Consumer<int[]> selectionListener = positions -> {};
  private Runnable viewChangedListener = () -> {};
  private IntConsumer focusListener = position -> {};

  private static final String KEYBOARD_HELP =
      "Use the arrow keys to move between nodes, Enter to focus the selected node, "
          + "plus or minus to zoom, 0 to fit, and Escape to clear the selection.";

  public GraphCanvas() {
    setOpaque(true);
    refreshTheme();
    CanvasAccessibility.configure(this, "Dependency graph canvas", KEYBOARD_HELP);
    setPreferredSize(new Dimension(600, 400));
    installKeyboardActions();
    Mouse mouse = new Mouse();
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addMouseWheelListener(mouse);
  }

  @Override
  public AccessibleContext getAccessibleContext() {
    if (accessibleContext == null) {
      accessibleContext = new AccessibleGraphCanvas();
    }
    return accessibleContext;
  }

  /** Standard Swing accessible peer for the custom-painted graph. */
  protected final class AccessibleGraphCanvas extends AccessibleJComponent {
    private static final long serialVersionUID = 1L;
  }

  @Override
  public void updateUI() {
    super.updateUI();
    refreshTheme();
    repaint();
  }

  private void refreshTheme() {
    Color background = UIManager.getColor("Panel.background");
    setBackground(background == null ? Color.WHITE : background);
  }

  /** Replaces what is drawn and fits it to the window. */
  public void setModel(GraphModel model) {
    this.model = model == null ? GraphModel.empty() : model;
    selection.clear();
    revealedCrossLinks = 0;
    hover = -1;
    // A new layout means new positions; offsets against the old ones
    // would displace unrelated nodes. Every new query, focus, source,
    // layout kind and refresh arrives here, so this is the reset point.
    dragOffsets.clear();
    nodeDrag = -1;
    nodeDragging = false;
    fitToView();
    updateAccessibleDescription();
    repaint();
  }

  /**
   * Swaps in a restyled model of the <em>same layout</em>, keeping the camera and the selection.
   *
   * <p>The weight selector's half of {@link #setModel}: the positions have not moved, so refitting
   * the view or dropping the selection would punish the user for changing what the colours mean.
   * The caller guarantees the two models share a layout; selection positions stay valid because
   * positions are layout indices.
   */
  public void restyle(GraphModel model) {
    this.model = model == null ? GraphModel.empty() : model;
    updateAccessibleDescription();
    repaint();
  }

  public GraphModel model() {
    return model;
  }

  public GraphTransform transform() {
    return transform;
  }

  /** Changes dependency detail without extracting or laying out again. */
  public void setEdgeDisplay(GraphEdgeDisplay display) {
    GraphEdgeDisplay next = display == null ? GraphEdgeDisplay.DECLUTTERED : display;
    if (next == edgeDisplay) {
      return;
    }
    edgeDisplay = next;
    viewChangedListener.run();
    repaint();
  }

  public GraphEdgeDisplay edgeDisplay() {
    return edgeDisplay;
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
   * <p>Fired by a double-click. The canvas cannot refocus itself — it holds no service and cannot
   * query — so it reports the wish and the panel redraws the graph around that node. A listener,
   * like the other two, because {@code GraphPaintIsolationTest} forbids this class anything
   * stronger.
   */
  public void onFocusRequested(IntConsumer listener) {
    this.focusListener = listener == null ? position -> {} : listener;
  }

  /**
   * Frames the whole drawing; plan 17.7's "fit".
   *
   * <p>Label-aware: the fit reserves room for the label text visible at the resulting zoom — all
   * labels in the near band, the selection's in the medium band, none in the far band — so "Fit"
   * shows the nodes <em>and</em> what they are called. Text does not scale with the world, so the
   * reservation is taken out of the viewport before the scale is chosen, clamped so it can never
   * drop the view into a coarser band, and capped at {@link #MAX_LABEL_FIT_FRACTION} of the window;
   * a cap that bites is reported through {@link #hiddenDetail()} rather than either zooming a dense
   * graph to nothing or pretending the text fits.
   */
  public void fitToView() {
    if (getWidth() <= 0 || getHeight() <= 0) {
      // Nothing to fit into yet. Remember to, rather than fitting to one
      // pixel and calling it done.
      fitPending = true;
      return;
    }
    fitPending = false;
    fitLabelNote = "";
    Optional<double[]> bounds = fitBounds();
    if (bounds.isEmpty()) {
      setTransform(GraphTransform.identity());
      return;
    }
    setTransform(labelAwareFit(bounds.get()));
  }

  /** The layout's bounds, stretched to cover any dragged nodes. */
  private Optional<double[]> fitBounds() {
    Optional<double[]> bounds = model.layout().bounds();
    if (bounds.isEmpty() || dragOffsets.isEmpty()) {
      return bounds;
    }
    double[] box = bounds.get().clone();
    GraphLayout.Result layout = model.layout();
    for (Entry<Integer, double[]> dragged : dragOffsets.entrySet()) {
      int position = dragged.getKey();
      if (position >= layout.size()) {
        continue;
      }
      double x = layout.xAt(position) + dragged.getValue()[0];
      double y = layout.yAt(position) + dragged.getValue()[1];
      box[0] = Math.min(box[0], x);
      box[1] = Math.min(box[1], y);
      box[2] = Math.max(box[2], x);
      box[3] = Math.max(box[3], y);
    }
    return Optional.of(box);
  }

  private GraphTransform labelAwareFit(double[] bounds) {
    FontMetrics metrics = getFontMetrics(labelFont());
    GraphTransform plain = GraphTransform.fit(bounds, getWidth(), getHeight(), FIT_MARGIN);
    Detail band = Detail.forScale(plain.scale());
    int widest = widestVisibleLabel(metrics, band);
    if (widest <= 0) {
      // The zoom Fit lands at paints no labels — the far band, or the
      // medium band with nothing selected — so there is nothing to
      // reserve for and nothing to report.
      return plain;
    }
    double nodeRadius = Math.max(2.5, NODE_RADIUS * plain.scale());
    double nodeExtent =
        model.layout().kind() == GraphLayout.Kind.HIERARCHY ? nodeRadius * 1.3 : nodeRadius;
    double wanted = widest + nodeExtent + 4;
    double usableWidth = getWidth() - 2 * FIT_MARGIN;
    // Two caps, both honest. The fraction cap stops a dense graph of long
    // names zooming to nothing; the band cap stops the reservation
    // pushing the zoom into a coarser band where the labels it reserved
    // for would not paint at all — a Fit that traded the text for room
    // for the text would be absurd.
    double cap = Math.max(1, usableWidth * MAX_LABEL_FIT_FRACTION);
    double worldWidth = Math.max(1e-6, bounds[2] - bounds[0]);
    double bandCap = usableWidth - band.floorScale() * worldWidth;
    double reserve = Math.min(wanted, Math.min(cap, Math.max(0, bandCap)));
    boolean capped = reserve < wanted;
    GraphTransform reserved =
        GraphTransform.fit(
            bounds,
            getWidth(),
            getHeight(),
            FIT_MARGIN,
            reserve,
            visibleLabelHeight(metrics, band));
    if (Detail.forScale(reserved.scale()) != band) {
      // The vertical reservation squeezed the scale below the band
      // floor after all — only possible hard against the boundary. The
      // plain fit paints strictly more text, so keep it and admit the
      // labels were not made room for.
      fitLabelNote =
          "Fit could not reserve room for the labels at this"
              + " window size, so some text may run past the edges.";
      return plain;
    }
    fitLabelNote =
        capped
            ? "The longest visible label is wider than the space Fit"
                + " reserves for text (capped at half the window),"
                + " so some labels run past the right edge."
            : "";
    return reserved;
  }

  /**
   * The font labels paint in.
   *
   * <p>A component that has never been added to a container has no font of its own, and a fit can
   * run before the first paint — so this falls back to the toolkit default the paint's {@code
   * Graphics} would use anyway.
   */
  Font labelFont() {
    Font font = getFont();
    return font != null ? font : new Font(Font.DIALOG, Font.PLAIN, 12);
  }

  /**
   * The widest label the given band would paint, in pixels; 0 when none.
   *
   * <p>Near paints every node's label that wins its space; medium paints the selection's only; far
   * paints none. Measuring the widest candidate is an over-estimate of what the declutter will keep
   * — which errs on the side of showing text, and the cap bounds the cost of erring.
   */
  private int widestVisibleLabel(FontMetrics metrics, Detail detail) {
    if (detail == Detail.FAR) {
      return 0;
    }
    int widest = 0;
    if (detail == Detail.MEDIUM) {
      for (int position : selection) {
        widest = Math.max(widest, labelWidth(metrics, position));
      }
      return widest;
    }
    for (int position = 0; position < model.size(); position++) {
      widest = Math.max(widest, labelWidth(metrics, position));
    }
    return widest;
  }

  private int labelWidth(FontMetrics metrics, int position) {
    int width = metrics.stringWidth(model.displayLabelAt(position));
    return model
        .ownerLabelAt(position)
        .map(owner -> Math.max(width, metrics.stringWidth("Target: " + owner)))
        .orElse(width);
  }

  private int visibleLabelHeight(FontMetrics metrics, Detail detail) {
    if (detail == Detail.FAR) {
      return 0;
    }
    if (detail == Detail.MEDIUM) {
      for (int position : selection) {
        if (model.ownerLabelAt(position).isPresent()) {
          return metrics.getHeight() * 2;
        }
      }
      return metrics.getHeight();
    }
    for (int position = 0; position < model.size(); position++) {
      if (model.ownerLabelAt(position).isPresent()) {
        return metrics.getHeight() * 2;
      }
    }
    return metrics.getHeight();
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
    notifySelectionChanged();
    repaint();
  }

  private void notifySelectionChanged() {
    revealedCrossLinks = countRevealedCrossLinks();
    updateAccessibleDescription();
    selectionListener.accept(selectedPositions());
  }

  private void installKeyboardActions() {
    CanvasAccessibility.bind(
        this,
        "graph-previous-node",
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
        () -> moveKeyboardSelection(-1));
    CanvasAccessibility.bind(
        this,
        "graph-previous-node-up",
        KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
        () -> moveKeyboardSelection(-1));
    CanvasAccessibility.bind(
        this,
        "graph-next-node",
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
        () -> moveKeyboardSelection(1));
    CanvasAccessibility.bind(
        this,
        "graph-next-node-down",
        KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
        () -> moveKeyboardSelection(1));
    CanvasAccessibility.bind(
        this,
        "graph-first-node",
        KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0),
        () -> selectKeyboardPosition(0));
    CanvasAccessibility.bind(
        this,
        "graph-last-node",
        KeyStroke.getKeyStroke(KeyEvent.VK_END, 0),
        () -> selectKeyboardPosition(model.size() - 1));
    CanvasAccessibility.bind(
        this,
        "graph-focus-node",
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
        this::focusKeyboardSelection);
    CanvasAccessibility.bind(
        this,
        "graph-clear-selection",
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        () -> select(-1));
    CanvasAccessibility.bind(
        this,
        "graph-zoom-in",
        KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK),
        () -> zoomFromKeyboard(1.25));
    CanvasAccessibility.bind(
        this,
        "graph-zoom-in-keypad",
        KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0),
        () -> zoomFromKeyboard(1.25));
    CanvasAccessibility.bind(
        this,
        "graph-zoom-out",
        KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0),
        () -> zoomFromKeyboard(1.0 / 1.25));
    CanvasAccessibility.bind(
        this,
        "graph-zoom-out-keypad",
        KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0),
        () -> zoomFromKeyboard(1.0 / 1.25));
    CanvasAccessibility.bind(
        this, "graph-fit", KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), this::fitToView);
  }

  private void moveKeyboardSelection(int delta) {
    if (model.size() == 0) {
      return;
    }
    int current = selection.size() == 1 ? selection.iterator().next() : (delta > 0 ? -1 : 0);
    select(Math.floorMod(current + delta, model.size()));
  }

  private void selectKeyboardPosition(int position) {
    if (model.size() > 0) {
      select(position);
    }
  }

  private void focusKeyboardSelection() {
    if (selection.size() == 1) {
      focusListener.accept(selection.iterator().next());
    }
  }

  private void zoomFromKeyboard(double factor) {
    fitLabelNote = "";
    setTransform(transform.zoomedAround(getWidth() / 2.0, getHeight() / 2.0, factor));
  }

  private void updateAccessibleDescription() {
    String state;
    if (model.size() == 0) {
      state = "No graph nodes are shown. ";
    } else if (selection.size() == 1) {
      state =
          "Showing "
              + model.size()
              + " graph nodes. Selected "
              + model.canvasLabelAt(selection.iterator().next())
              + ". ";
    } else if (selection.isEmpty()) {
      state = "Showing " + model.size() + " graph nodes; none is selected. ";
    } else {
      state = "Showing " + model.size() + " graph nodes; " + selection.size() + " are selected. ";
    }
    CanvasAccessibility.describe(this, state + KEYBOARD_HELP);
  }

  private int countRevealedCrossLinks() {
    if (model.layout().kind() != GraphLayout.Kind.HIERARCHY || selection.size() != 1) {
      return 0;
    }
    return model.crossLinksTouching(selection);
  }

  /** Replaces the selection with several positions, for marquee behavior tests. */
  void selectPositionsForTesting(int... positions) {
    selection.clear();
    for (int position : positions) {
      if (position >= 0 && position < model.size()) {
        selection.add(position);
      }
    }
    notifySelectionChanged();
    repaint();
  }

  /**
   * Zooms about the centre of the component.
   *
   * <p>Named for what it is. Interactive zoom is anchored to the pointer, so this exists only so a
   * test can reach a detail band without synthesising wheel events.
   */
  void zoomForTesting(double factor) {
    // A zoom is a zoom: the fit's cap note describes the fitted view, so
    // it clears here exactly as it does for a wheel zoom.
    fitLabelNote = "";
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

    /**
     * The smallest scale still inside this band.
     *
     * <p>The label-aware fit clamps its reservation here, so making room for text can never drop
     * the view into a coarser band where that text would not paint. The far band's floor is the
     * zoom floor itself.
     */
    double floorScale() {
      return switch (this) {
        case FAR -> GraphTransform.MIN_SCALE;
        case MEDIUM -> 0.15;
        case NEAR -> 0.6;
      };
    }
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    int previousDeclutteredLabels = declutteredLabels;
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
          smooth ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
      g.setRenderingHint(
          RenderingHints.KEY_RENDERING,
          panning ? RenderingHints.VALUE_RENDER_SPEED : RenderingHints.VALUE_RENDER_QUALITY);

      double[] world = transform.visibleWorld(getWidth(), getHeight());
      // Arrowheads only where an edge is individually distinguishable:
      // the near band, and not mid-drag, where detail is suspended
      // anyway. At medium zoom nodes sit pixels apart and a head per
      // edge is a smear; at far zoom edges are aggregate weight.
      arrowsThisFrame = detail == Detail.NEAR && !panning;
      arrowRadiusPixels = Math.max(2.5, NODE_RADIUS * transform.scale());
      arrowsInLastPaint = 0;
      primaryEdgesInLastPaint = 0;
      crossLinksInLastPaint = 0;
      declutteredLabels = 0;
      paintedLabels.clear();
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
    if (previousDeclutteredLabels != declutteredLabels) {
      int paintedCount = declutteredLabels;
      SwingUtilities.invokeLater(
          () -> {
            if (declutteredLabels == paintedCount) {
              viewChangedListener.run();
            }
          });
    }
  }

  private void paintEdges(Graphics2D g, double[] world, Detail detail) {
    if (edgesAreHidden(detail)) {
      return;
    }
    int[][] edges = model.edgePositions();
    int count = edges[0].length;
    GraphLayout.Result layout = model.layout();

    // Colour set once for the bulk rather than once per edge: at two
    // hundred thousand edges the state changes cost more than the lines.
    // Thickness is the weight's edge encoding, drawn one bucket per pass
    // so the stroke changes a handful of times per frame; an unweighted
    // drawing has one bucket and pays for exactly one pass, as before.
    // Highlighted edges are a final pass, and only when there is a
    // selection to highlight.
    if (model.layout().kind() == GraphLayout.Kind.HIERARCHY) {
      paintHierarchyEdges(g, world, detail);
      return;
    }
    g.setColor(GraphColours.edge());
    int lastBucket = model.maxEdgeBucket();
    for (int bucket = 0; bucket <= lastBucket; bucket++) {
      g.setStroke(EDGE_STROKES[bucket]);
      for (int e = 0; e < count; e++) {
        if (model.edgeBucketAt(e) != bucket) {
          continue;
        }
        int from = edges[0][e];
        int to = edges[1][e];
        if (!selection.isEmpty() && (selection.contains(from) || selection.contains(to))) {
          continue;
        }
        drawEdge(g, layout, world, from, to, false);
      }
    }
    if (selection.isEmpty()) {
      return;
    }
    g.setStroke(THIN);
    g.setColor(GraphColours.EDGE_HIGHLIGHTED);
    for (int e = 0; e < count; e++) {
      int from = edges[0][e];
      int to = edges[1][e];
      if (selection.contains(from) || selection.contains(to)) {
        drawEdge(g, layout, world, from, to, false);
      }
    }
  }

  /**
   * Primary parent links as orthogonal branches, shared/cycle links as subdued dashed lines. The
   * latter are filtered by the explicit edge-detail setting; the model still contains every
   * dependency.
   */
  private void paintHierarchyEdges(Graphics2D g, double[] world, Detail detail) {
    int[][] edges = model.edgePositions();
    int count = edges[0].length;
    GraphLayout.Result layout = model.layout();
    boolean hideFarCrossLinks = hierarchyCrossLinksAreHiddenAtFar(detail);
    if (detail == Detail.FAR) {
      paintHierarchyOverview(g, world, hideFarCrossLinks);
      return;
    }

    g.setColor(GraphColours.secondaryEdge());
    g.setStroke(CROSS_LINK);
    for (int edge = 0; edge < count; edge++) {
      if (model.isHierarchyEdge(edge)
          || (hideFarCrossLinks && !touchesSelection(edges[0][edge], edges[1][edge]))
          || !edgeVisible(edge)
          || touchesSelection(edges[0][edge], edges[1][edge])) {
        continue;
      }
      if (drawEdge(g, layout, world, edges[0][edge], edges[1][edge], false)) {
        crossLinksInLastPaint++;
      }
    }

    g.setColor(GraphColours.edge());
    int lastBucket = model.maxEdgeBucket();
    for (int bucket = 0; bucket <= lastBucket; bucket++) {
      g.setStroke(EDGE_STROKES[bucket]);
      for (int edge = 0; edge < count; edge++) {
        if (!model.isHierarchyEdge(edge)
            || model.edgeBucketAt(edge) != bucket
            || touchesSelection(edges[0][edge], edges[1][edge])) {
          continue;
        }
        if (drawEdge(g, layout, world, edges[0][edge], edges[1][edge], true)) {
          primaryEdgesInLastPaint++;
        }
      }
    }

    if (selection.isEmpty()) {
      return;
    }
    g.setStroke(THIN);
    g.setColor(GraphColours.EDGE_HIGHLIGHTED);
    for (int edge = 0; edge < count; edge++) {
      int from = edges[0][edge];
      int to = edges[1][edge];
      if (touchesSelection(from, to) && edgeVisible(edge)) {
        if (drawEdge(g, layout, world, from, to, model.isHierarchyEdge(edge))) {
          if (model.isHierarchyEdge(edge)) {
            primaryEdgesInLastPaint++;
          } else {
            crossLinksInLastPaint++;
          }
        }
      }
    }
  }

  /** A bounded, evenly distributed hierarchy backbone for the far overview. */
  private void paintHierarchyOverview(Graphics2D g, double[] world, boolean hideFarCrossLinks) {
    int[][] edges = model.edgePositions();
    GraphLayout.Result layout = model.layout();
    int primaryTotal = model.hierarchyEdgeCount();
    int primaryBudget = farHierarchyBranchBudget();

    g.setStroke(THIN);
    g.setColor(GraphColours.edge());
    int primarySamples = Math.min(primaryTotal, primaryBudget);
    for (int sample = 0; sample < primarySamples; sample++) {
      int ordinal = evenlyDistributedOrdinal(sample, primaryTotal, primarySamples);
      int edge = model.hierarchyEdgeAt(ordinal);
      if (drawEdge(g, layout, world, edges[0][edge], edges[1][edge], false)) {
        primaryEdgesInLastPaint++;
      }
    }

    boolean revealOne = selection.size() == 1;
    if (!hideFarCrossLinks && edgeDisplay == GraphEdgeDisplay.ALL) {
      g.setStroke(CROSS_LINK);
      g.setColor(GraphColours.secondaryEdge());
      for (int ordinal = 0; ordinal < model.crossLinkCount(); ordinal++) {
        int edge = model.crossLinkEdgeAt(ordinal);
        if (!edgeVisible(edge) || (revealOne && touchesSelection(edges[0][edge], edges[1][edge]))) {
          continue;
        }
        if (drawEdge(g, layout, world, edges[0][edge], edges[1][edge], false)) {
          crossLinksInLastPaint++;
        }
      }
    }

    // One selected node reveals its cross-links. A marquee can select
    // thousands of nodes, so it must not turn the bounded overview back
    // into an all-edge paint by accident.
    if (!revealOne) {
      return;
    }
    int selected = selection.iterator().next();
    int incident = model.crossLinkDegreeAt(selected);
    boolean bounded = edgeDisplay == GraphEdgeDisplay.DECLUTTERED || hideFarCrossLinks;
    int budget = bounded ? farHierarchyBranchBudget() : incident;
    g.setStroke(THIN);
    g.setColor(GraphColours.EDGE_HIGHLIGHTED);
    int samples = Math.min(incident, budget);
    for (int sample = 0; sample < samples; sample++) {
      int ordinal = evenlyDistributedOrdinal(sample, incident, samples);
      int edge = model.incidentCrossLinkAt(selected, ordinal);
      int from = edges[0][edge];
      int to = edges[1][edge];
      if (edgeVisible(edge) && drawEdge(g, layout, world, from, to, false)) {
        crossLinksInLastPaint++;
      }
    }
  }

  /** The centre ordinal of one deterministic, evenly divided sample bucket. */
  private static int evenlyDistributedOrdinal(int sample, int total, int samples) {
    return (int) Math.min(total - 1L, ((2L * sample + 1) * total) / (2L * samples));
  }

  private int farHierarchyBranchBudget() {
    return Math.max(1, getWidth() * FAR_HIERARCHY_BRANCHES_PER_PIXEL);
  }

  private int farHierarchyBranchesHidden() {
    if (model.layout().kind() != GraphLayout.Kind.HIERARCHY || detail() != Detail.FAR) {
      return 0;
    }
    return Math.max(0, model.hierarchyEdgeCount() - farHierarchyBranchBudget());
  }

  /** Selected cross-links simplified by the same far-view line budget. */
  private int farSelectedCrossLinksHidden() {
    if (model.layout().kind() != GraphLayout.Kind.HIERARCHY
        || detail() != Detail.FAR
        || selection.size() != 1
        || (edgeDisplay == GraphEdgeDisplay.ALL && !hierarchyCrossLinksAreHiddenAtFar(detail()))) {
      return 0;
    }
    return Math.max(0, revealedCrossLinks - farHierarchyBranchBudget());
  }

  private boolean touchesSelection(int from, int to) {
    return selection.contains(from) || selection.contains(to);
  }

  private boolean drawEdge(
      Graphics2D g,
      GraphLayout.Result layout,
      double[] world,
      int from,
      int to,
      boolean orthogonal) {
    double x1 = nodeX(layout, from);
    double y1 = nodeY(layout, from);
    double x2 = nodeX(layout, to);
    double y2 = nodeY(layout, to);
    // Cheap reject: an edge whose bounding box misses the viewport cannot
    // cross it.
    if (Math.max(x1, x2) < world[0]
        || Math.min(x1, x2) > world[2]
        || Math.max(y1, y2) < world[1]
        || Math.min(y1, y2) > world[3]) {
      return false;
    }
    double sx1 = transform.screenX(x1);
    double sy1 = transform.screenY(y1);
    double sx2 = transform.screenX(x2);
    double sy2 = transform.screenY(y2);
    if (orthogonal) {
      int screenX1 = (int) Math.round(sx1);
      int screenY1 = (int) Math.round(sy1);
      int screenX2 = (int) Math.round(sx2);
      int screenY2 = (int) Math.round(sy2);
      int branchY = (int) Math.round((sy1 + sy2) / 2.0);
      g.drawLine(screenX1, screenY1, screenX1, branchY);
      g.drawLine(screenX1, branchY, screenX2, branchY);
      g.drawLine(screenX2, branchY, screenX2, screenY2);
      if (arrowsThisFrame) {
        drawArrowhead(g, screenX2, branchY, screenX2, screenY2);
      }
      return true;
    }
    g.drawLine(
        (int) Math.round(sx1), (int) Math.round(sy1),
        (int) Math.round(sx2), (int) Math.round(sy2));
    if (arrowsThisFrame) {
      drawArrowhead(g, sx1, sy1, sx2, sy2);
    }
    return true;
  }

  /**
   * The direction arrowhead: producer to consumer, which is how edges are stored — {@code from} is
   * the target, {@code to} is its rdep — so the head sits at the consumer end, pulled back to the
   * node's rim.
   */
  private void drawArrowhead(Graphics2D g, double sx1, double sy1, double sx2, double sy2) {
    double[] tip = arrowTip(sx1, sy1, sx2, sy2, arrowRadiusPixels + 1);
    if (tip == null) {
      return;
    }
    double ux = tip[2];
    double uy = tip[3];
    double backX = tip[0] - ux * ARROW_LENGTH_PIXELS;
    double backY = tip[1] - uy * ARROW_LENGTH_PIXELS;
    // Half the head's length again as its half-width reads as an arrow at
    // any stroke this canvas uses.
    double wing = ARROW_LENGTH_PIXELS / 2;
    int tipX = (int) Math.round(tip[0]);
    int tipY = (int) Math.round(tip[1]);
    g.drawLine(
        tipX, tipY, (int) Math.round(backX - uy * wing), (int) Math.round(backY + ux * wing));
    g.drawLine(
        tipX, tipY, (int) Math.round(backX + uy * wing), (int) Math.round(backY - ux * wing));
    arrowsInLastPaint++;
  }

  /**
   * Where an edge's arrowhead tip sits, in screen pixels.
   *
   * <p>Pure geometry, extracted so the direction — the tip belongs at the {@code (x2, y2)} consumer
   * end, pulled back by {@code pullback} so it is not buried under the node — is testable without
   * reading pixels.
   *
   * @return {@code {tipX, tipY, unitX, unitY}}, or null when the edge is too short on screen for a
   *     head to be legible
   */
  static double[] arrowTip(double x1, double y1, double x2, double y2, double pullback) {
    double dx = x2 - x1;
    double dy = y2 - y1;
    double length = Math.hypot(dx, dy);
    if (length < MIN_ARROW_EDGE_PIXELS || length < pullback + ARROW_LENGTH_PIXELS) {
      return null;
    }
    double ux = dx / length;
    double uy = dy / length;
    return new double[] {x2 - ux * pullback, y2 - uy * pullback, ux, uy};
  }

  /** A node's world x, drag overlay included. */
  private double nodeX(GraphLayout.Result layout, int position) {
    if (dragOffsets.isEmpty()) {
      return layout.xAt(position);
    }
    double[] offset = dragOffsets.get(position);
    return offset == null ? layout.xAt(position) : layout.xAt(position) + offset[0];
  }

  /** A node's world y, drag overlay included. */
  private double nodeY(GraphLayout.Result layout, int position) {
    if (dragOffsets.isEmpty()) {
      return layout.yAt(position);
    }
    double[] offset = dragOffsets.get(position);
    return offset == null ? layout.yAt(position) : layout.yAt(position) + offset[1];
  }

  private boolean edgesAreHidden(Detail detail) {
    int count = model.edgePositions()[0].length;
    return (panning && count > PANNING_EDGE_BUDGET)
        || (model.layout().kind() != GraphLayout.Kind.HIERARCHY
            && detail == Detail.FAR
            && count > FAR_EDGE_BUDGET);
  }

  private boolean hierarchyCrossLinksAreHiddenAtFar(Detail detail) {
    return model.layout().kind() == GraphLayout.Kind.HIERARCHY
        && detail == Detail.FAR
        && model.crossLinkCount() > FAR_EDGE_BUDGET;
  }

  /**
   * What this frame is not drawing, for the view to say out loud.
   *
   * <p>Plan 13.6's "never claim the omitted nodes do not exist", applied to the one thing the
   * canvas legitimately omits. Nothing about the graph has changed; only this frame is simpler, and
   * zooming in restores it.
   */
  public Optional<String> hiddenDetail() {
    StringBuilder text = new StringBuilder();
    if (edgesAreHidden(detail())) {
      int count = model.edgePositions()[0].length;
      if (panning) {
        text.append(count).append(" dependencies are temporarily hidden while moving the graph.");
      } else {
        text.append(count).append(" dependencies are not drawn at this zoom. Zoom in to see them.");
      }
    } else if (edgeDisplay == GraphEdgeDisplay.ALL && hierarchyCrossLinksAreHiddenAtFar(detail())) {
      int revealedAtFar = Math.min(revealedCrossLinks, farHierarchyBranchBudget());
      int hidden = Math.max(0, model.crossLinkCount() - revealedAtFar);
      if (hidden > 0) {
        text.append(hidden)
            .append(hidden == 1 ? " cross-link is" : " cross-links are")
            .append(
                " not drawn at this zoom; the hierarchy branches remain visible."
                    + " Zoom in to see every dependency.");
      }
    } else {
      int hiddenCrossLinks = hiddenCrossLinkCount();
      if (hiddenCrossLinks > 0) {
        text.append(hiddenCrossLinks)
            .append(
                hiddenCrossLinks == 1
                    ? " additional dependency is hidden"
                    : " additional dependencies are hidden")
            .append(
                " by Decluttered edges. Select a node to reveal its links or"
                    + " choose All dependencies.");
      }
    }
    if (edgeDisplay == GraphEdgeDisplay.DECLUTTERED) {
      int hiddenSelected = farSelectedCrossLinksHidden();
      if (hiddenSelected > 0) {
        appendHiddenDetail(
            text,
            hiddenSelected
                + (hiddenSelected == 1 ? " selected cross-link is" : " selected cross-links are")
                + " not drawn individually at overview scale."
                + " Zoom in to restore every selected link.");
      }
    }
    int hiddenPrimaryBranches = farHierarchyBranchesHidden();
    if (hiddenPrimaryBranches > 0) {
      int total = model.hierarchyEdgeCount();
      int drawn = total - hiddenPrimaryBranches;
      appendHiddenDetail(
          text,
          hiddenPrimaryBranches
              + " of "
              + total
              + " primary branches are not drawn individually at overview scale; "
              + drawn
              + " evenly spaced branches show the overall shape."
              + " Zoom in to restore every branch.");
    }
    if (detail() == Detail.FAR && model.size() > 0) {
      String noun = model.nodeNoun();
      appendHiddenDetail(
          text,
          noun.equals("action")
              ? "Select an action to see its name and target, or zoom in to draw labels."
              : "Select a " + noun + " to see its label, or zoom in to draw labels.");
    } else if (declutteredLabels > 0) {
      appendHiddenDetail(
          text,
          declutteredLabels
              + (declutteredLabels == 1 ? " node label is hidden" : " node labels are hidden")
              + " to prevent overlap. Zoom in or select a node to reveal it.");
    }
    if (!fitLabelNote.isEmpty()) {
      appendHiddenDetail(text, fitLabelNote);
    }
    return text.length() == 0 ? Optional.empty() : Optional.of(text.toString());
  }

  private static void appendHiddenDetail(StringBuilder text, String detail) {
    if (text.length() > 0) {
      text.append("  ");
    }
    text.append(detail);
  }

  /** Cross-links currently omitted by the explicit decluttering setting. */
  int hiddenCrossLinkCount() {
    if (model.layout().kind() != GraphLayout.Kind.HIERARCHY
        || edgeDisplay == GraphEdgeDisplay.ALL) {
      return 0;
    }
    return Math.max(0, model.crossLinkCount() - revealedCrossLinks);
  }

  private boolean edgeVisible(int edge) {
    if (model.layout().kind() != GraphLayout.Kind.HIERARCHY
        || edgeDisplay == GraphEdgeDisplay.ALL
        || model.isHierarchyEdge(edge)) {
      return true;
    }
    int[][] edges = model.edgePositions();
    return selection.size() == 1 && touchesSelection(edges[0][edge], edges[1][edge]);
  }

  private void paintNodes(Graphics2D g, double[] world, Detail detail) {
    double base = Math.max(detail == Detail.FAR ? 1.5 : 2.5, NODE_RADIUS * transform.scale());
    GraphLayout.Result layout = model.layout();
    // The index answers with layout positions; a dragged node may have
    // left the rectangle its indexed position is in, or entered a view
    // its indexed position is outside, so dragged nodes are painted
    // separately and unconditionally. There are at most a handful.
    model
        .index()
        .forEachInRect(
            world[0] - NODE_RADIUS,
            world[1] - NODE_RADIUS,
            world[2] + NODE_RADIUS,
            world[3] + NODE_RADIUS,
            position -> {
              if (!dragOffsets.isEmpty() && dragOffsets.containsKey(position)) {
                return;
              }
              paintNode(g, layout, position, base);
            });
    for (int position : dragOffsets.keySet()) {
      if (position < model.size()) {
        paintNode(g, layout, position, base);
      }
    }
  }

  private void paintNode(Graphics2D g, GraphLayout.Result layout, int position, double base) {
    // The weight's node encoding: a per-node multiplier over the
    // zoom-derived base, so weights change relative size and zoom still
    // changes absolute size.
    double radius = base * model.radiusScaleAt(position);
    int diameter = (int) Math.round(radius * 2);
    int cx = (int) Math.round(transform.screenX(nodeX(layout, position)));
    int cy = (int) Math.round(transform.screenY(nodeY(layout, position)));
    g.setColor(model.colourAt(position));
    boolean hierarchy = layout.kind() == GraphLayout.Kind.HIERARCHY;
    if (hierarchy) {
      int width = (int) Math.round(radius * 2.6);
      int height = (int) Math.round(radius * 1.8);
      g.fillRoundRect(cx - width / 2, cy - height / 2, width, height, 6, 6);
    } else {
      g.fillOval(cx - (int) Math.round(radius), cy - (int) Math.round(radius), diameter, diameter);
    }
    if (selection.contains(position) || position == hover) {
      g.setColor(
          position == hover && !selection.contains(position)
              ? GraphColours.HOVER
              : GraphColours.SELECTION);
      g.setStroke(OUTLINE);
      if (hierarchy) {
        int width = (int) Math.round(radius * 2.6);
        int height = (int) Math.round(radius * 1.8);
        g.drawRoundRect(cx - width / 2 - 2, cy - height / 2 - 2, width + 4, height + 4, 8, 8);
      } else {
        g.drawOval(
            cx - (int) Math.round(radius) - 2,
            cy - (int) Math.round(radius) - 2,
            diameter + 4,
            diameter + 4);
      }
    }
  }

  /**
   * Labels, decluttered: text never paints over text.
   *
   * <p>Candidates are gathered, ordered by a deterministic priority — selected first, then hovered,
   * then higher weight, then lower node index — and painted only where no already-painted label's
   * box overlaps. The same frame therefore always keeps the same labels, and the first selected
   * label always paints because nothing outranks it. What was skipped is counted in {@link
   * #declutteredLabelCount()}: a rendering-density decision like the zoom bands, never a claim that
   * the skipped nodes are nameless.
   */
  private void paintLabels(Graphics2D g, double[] world, Detail detail) {
    g.setColor(GraphColours.label());
    FontMetrics metrics = g.getFontMetrics();
    int lineHeight = metrics.getHeight();
    double radius = Math.max(2.5, NODE_RADIUS * transform.scale());
    double nodeExtent = model.layout().kind() == GraphLayout.Kind.HIERARCHY ? radius * 1.3 : radius;
    GraphLayout.Result layout = model.layout();

    List<Integer> candidates = new ArrayList<>();
    model
        .index()
        .forEachInRect(
            world[0],
            world[1],
            world[2],
            world[3],
            position -> {
              if (!dragOffsets.isEmpty() && dragOffsets.containsKey(position)) {
                return;
              }
              if (labelWanted(position, detail)) {
                candidates.add(position);
              }
            });
    for (int position : dragOffsets.keySet()) {
      if (position < model.size()
          && labelWanted(position, detail)
          && nodeX(layout, position) >= world[0]
          && nodeX(layout, position) <= world[2]
          && nodeY(layout, position) >= world[1]
          && nodeY(layout, position) <= world[3]) {
        candidates.add(position);
      }
    }
    candidates.sort(this::labelPriority);

    List<Rectangle> placed = new ArrayList<>();
    for (int position : candidates) {
      String label = model.displayLabelAt(position);
      Optional<String> owner = model.ownerLabelAt(position);
      int x = (int) Math.round(transform.screenX(nodeX(layout, position)) + nodeExtent + 4);
      int y =
          (int)
              Math.round(
                  transform.screenY(nodeY(layout, position))
                      - (owner.isPresent() ? lineHeight / 4.0 : -lineHeight / 4.0));
      int width = metrics.stringWidth(label);
      if (owner.isPresent()) {
        width = Math.max(width, metrics.stringWidth("Target: " + owner.orElseThrow()));
      }
      Rectangle box =
          new Rectangle(
              x, y - metrics.getAscent(), width, owner.isPresent() ? lineHeight * 2 : lineHeight);
      boolean overlaps = false;
      for (Rectangle other : placed) {
        if (other.intersects(box)) {
          overlaps = true;
          break;
        }
      }
      if (overlaps) {
        declutteredLabels++;
        continue;
      }
      placed.add(box);
      paintedLabels.add(position);
      g.drawString(label, x, y);
      owner.ifPresent(target -> g.drawString("Target: " + target, x, y + lineHeight));
    }
  }

  /** Whether this band labels this node at all, before any collision. */
  private boolean labelWanted(int position, Detail detail) {
    // In the medium band, only what the user has pointed at or picked
    // out: labelling everything at that scale is an unreadable wall,
    // which is what the threshold is for.
    return detail != Detail.MEDIUM || selection.contains(position) || position == hover;
  }

  /**
   * The declutter's order: selected, then hovered, then heavier, then lower node index — with the
   * layout position as a final tiebreak so the order is total and the frame deterministic.
   */
  private int labelPriority(int a, int b) {
    boolean selectedA = selection.contains(a);
    boolean selectedB = selection.contains(b);
    if (selectedA != selectedB) {
      return selectedA ? -1 : 1;
    }
    if ((a == hover) != (b == hover)) {
      return a == hover ? -1 : 1;
    }
    long weightA = model.weightAt(a).orElse(-1);
    long weightB = model.weightAt(b).orElse(-1);
    if (weightA != weightB) {
      return Long.compare(weightB, weightA);
    }
    int byNode = Integer.compare(model.nodeAt(a), model.nodeAt(b));
    return byNode != 0 ? byNode : Integer.compare(a, b);
  }

  /** Labels the last paint skipped to avoid painting text over text. */
  public int declutteredLabelCount() {
    return declutteredLabels;
  }

  /** The positions whose labels the last paint drew, in paint order. */
  List<Integer> paintedLabelPositionsForTesting() {
    return List.copyOf(paintedLabels);
  }

  /** Arrowheads the last paint drew; zero outside the near band. */
  int arrowsDrawnForTesting() {
    return arrowsInLastPaint;
  }

  int primaryEdgesDrawnForTesting() {
    return primaryEdgesInLastPaint;
  }

  int crossLinksDrawnForTesting() {
    return crossLinksInLastPaint;
  }

  /** Hit test at a screen point; empty when nothing is close enough. */
  public OptionalInt positionAt(int screenX, int screenY) {
    double visibleNodeRadius =
        NODE_RADIUS
            * GraphModel.MAX_RADIUS_SCALE
            * (model.layout().kind() == GraphLayout.Kind.HIERARCHY ? 1.6 : 1.0);
    double radius = Math.max(HIT_RADIUS_PIXELS / transform.scale(), visibleNodeRadius);
    double worldX = transform.worldX(screenX);
    double worldY = transform.worldY(screenY);
    if (dragOffsets.isEmpty()) {
      return model.index().nearest(worldX, worldY, radius);
    }
    // Dragged nodes are where the user put them, not where the index has
    // them: the index — shared, never mutated — answers for everything
    // else, and the overlay's handful are tested at their displaced
    // coordinates.
    OptionalInt indexed =
        model
            .index()
            .nearest(worldX, worldY, radius, position -> !dragOffsets.containsKey(position));
    int best = indexed.orElse(-1);
    double bestDistance = Double.MAX_VALUE;
    if (best >= 0) {
      double dx = model.layout().xAt(best) - worldX;
      double dy = model.layout().yAt(best) - worldY;
      bestDistance = dx * dx + dy * dy;
    }
    GraphLayout.Result layout = model.layout();
    for (int position : dragOffsets.keySet()) {
      if (position >= model.size()) {
        continue;
      }
      double dx = nodeX(layout, position) - worldX;
      double dy = nodeY(layout, position) - worldY;
      double distance = dx * dx + dy * dy;
      if (distance > radius * radius) {
        continue;
      }
      if (distance < bestDistance || (distance == bestDistance && position < best)) {
        bestDistance = distance;
        best = position;
      }
    }
    return best < 0 ? OptionalInt.empty() : OptionalInt.of(best);
  }

  /**
   * Forgets every dragged position; the "Reset positions" action.
   *
   * <p>Explicit, where {@link #setModel} is implicit: a new layout resets the overlay because the
   * offsets describe positions that no longer exist, and this resets it because the user asked.
   */
  public void resetDragOffsets() {
    if (dragOffsets.isEmpty()) {
      return;
    }
    dragOffsets.clear();
    nodeDrag = -1;
    nodeDragging = false;
    repaint();
  }

  /** True when any node has been dragged off its laid-out position. */
  public boolean hasDragOffsets() {
    return !dragOffsets.isEmpty();
  }

  /** A node's world-space drag displacement, or null when it has none. */
  double[] dragOffsetForTesting(int position) {
    double[] offset = dragOffsets.get(position);
    return offset == null ? null : offset.clone();
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
        notifySelectionChanged();
        // The branch point between panning and node dragging: a press
        // on a node arms a node drag, a press on empty canvas (below)
        // arms a pan. The drag moves a view-layer overlay only; the
        // layout, the index and the cached rendering never move.
        nodeDrag = hit.getAsInt();
        nodeDragPoint = event.getPoint();
        repaint();
        return;
      }
      if (!event.isControlDown() && !event.isMetaDown() && !selection.isEmpty()) {
        selection.clear();
        notifySelectionChanged();
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
      if (nodeDrag >= 0 && nodeDragPoint != null) {
        if (!nodeDragging) {
          if (event.getPoint().distance(nodeDragPoint) < NODE_DRAG_THRESHOLD_PIXELS) {
            // The jitter inside an ordinary click. Writing an
            // offset here would arm "Reset positions" for a move
            // nobody made on purpose.
            return;
          }
          // The anchor is still the press point, so the first real
          // drag event applies the whole distance moved — the
          // threshold delays the decision, it does not eat pixels.
          nodeDragging = true;
        }
        double[] offset = dragOffsets.computeIfAbsent(nodeDrag, position -> new double[2]);
        offset[0] += (event.getX() - nodeDragPoint.x) / transform.scale();
        offset[1] += (event.getY() - nodeDragPoint.y) / transform.scale();
        nodeDragPoint = event.getPoint();
        repaint();
        return;
      }
      if (dragOrigin == null) {
        return;
      }
      panning = true;
      fitLabelNote = "";
      setTransform(
          transform.pannedByPixels(event.getX() - dragOrigin.x, event.getY() - dragOrigin.y));
      dragOrigin = event.getPoint();
    }

    @Override
    public void mouseReleased(MouseEvent event) {
      if (marquee != null) {
        double left = transform.worldX(marquee.x);
        double top = transform.worldY(marquee.y);
        double right = transform.worldX(marquee.x + marquee.width);
        double bottom = transform.worldY(marquee.y + marquee.height);
        selection.clear();
        for (int position : model.index().within(left, top, right, bottom)) {
          // The index holds laid-out positions; a dragged node is
          // selected by where it is now, below, not where it was.
          if (dragOffsets.isEmpty() || !dragOffsets.containsKey(position)) {
            selection.add(position);
          }
        }
        GraphLayout.Result layout = model.layout();
        for (int position : dragOffsets.keySet()) {
          if (position < model.size()
              && nodeX(layout, position) >= left
              && nodeX(layout, position) <= right
              && nodeY(layout, position) >= top
              && nodeY(layout, position) <= bottom) {
            selection.add(position);
          }
        }
        notifySelectionChanged();
      }
      marquee = null;
      marqueeStart = null;
      dragOrigin = null;
      nodeDrag = -1;
      nodeDragPoint = null;
      nodeDragging = false;
      if (panning) {
        panning = false;
        // The detail suppressed during the drag comes back here.
        viewChangedListener.run();
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
      positionAt(event.getX(), event.getY()).ifPresent(position -> focusListener.accept(position));
    }

    @Override
    public void mouseMoved(MouseEvent event) {
      int was = hover;
      hover = positionAt(event.getX(), event.getY()).orElse(-1);
      if (hover != was) {
        setToolTipText(hover < 0 ? null : PlainText.tooltip(model.canvasLabelAt(hover)));
        repaint();
      }
    }

    @Override
    public void mouseExited(MouseEvent event) {
      if (hover < 0) {
        return;
      }
      hover = -1;
      setToolTipText(null);
      repaint();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent event) {
      // Zoom around the pointer, not the centre: a user scrolling over a
      // node means "closer to that one".
      double factor = Math.pow(1.1, -event.getPreciseWheelRotation());
      fitLabelNote = "";
      setTransform(transform.zoomedAround(event.getX(), event.getY(), factor));
    }
  }
}
