package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.graph.GraphColours;
import com.holtherndon.bazelviz.ui.graph.GraphTransform;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
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
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToolTip;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Interactive, pprof-style drawing of a bounded Starlark caller-to-callee graph. */
public final class StarlarkCallGraph extends JComponent {

  private static final long serialVersionUID = 1L;
  private static final double FIT_MARGIN = 30;
  private static final double EDGE_LABEL_SCALE = 0.58;
  private static final double ARROW_LENGTH = 8;
  private static final double ARROW_HALF_WIDTH = 4;
  private static final int NODE_DRAG_THRESHOLD_PIXELS = 3;

  private StarlarkCallGraphLayout.Layout layout =
      StarlarkCallGraphLayout.layout(
          new StarlarkProfileReader.DirectedCallGraph(
              0, 0, 0, 0, OptionalLong.empty(), List.of(), List.of()));
  private GraphTransform transform = GraphTransform.identity();
  private Consumer<StarlarkProfileReader.CallGraphNode> selectionListener = ignored -> {};
  private Consumer<StarlarkProfileReader.SourceLocation> sourceListener = ignored -> {};
  private final PassthroughToolTip nodeToolTip = new PassthroughToolTip();
  private long selectedFunctionId = -1;
  private long hoveredFunctionId = -1;
  private boolean edgeLabels = true;
  private boolean fitPending;
  private boolean panning;
  private Point dragPoint;
  private final Map<Long, double[]> dragOffsets = new HashMap<>();
  private long draggedFunctionId = -1;
  private Point nodePressPoint;
  private double nodeStartOffsetX;
  private double nodeStartOffsetY;
  private boolean nodeDragging;

  private static final String KEYBOARD_HELP =
      "Use the arrow keys to move between functions, Enter to focus, O to open source, "
          + "plus or minus to zoom, and 0 to fit the graph.";

  public StarlarkCallGraph() {
    setOpaque(true);
    setLayout(null);
    setPreferredSize(new Dimension(760, 500));
    CanvasAccessibility.configure(
        this, "Starlark directed call graph", "No functions are shown. " + KEYBOARD_HELP);
    installKeyboardActions();
    PlainText.disableHtml(nodeToolTip);
    nodeToolTip.setComponent(this);
    nodeToolTip.setVisible(false);
    add(nodeToolTip);
    MouseHandler mouse = new MouseHandler();
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addMouseWheelListener(mouse);
    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent event) {
            hideNodeToolTip();
            if (fitPending && getWidth() > 0 && getHeight() > 0) {
              fitToView();
            }
          }
        });
    refreshTheme();
  }

  @Override
  public void updateUI() {
    super.updateUI();
    refreshTheme();
    repaint();
  }

  private void refreshTheme() {
    Color panel = UIManager.getColor("Panel.background");
    setBackground(panel == null ? Color.WHITE : panel);
  }

  @Override
  public AccessibleContext getAccessibleContext() {
    if (accessibleContext == null) {
      accessibleContext = new AccessibleStarlarkCallGraph();
    }
    return accessibleContext;
  }

  /** Gives the painted graph the normal Swing component accessibility contract. */
  protected final class AccessibleStarlarkCallGraph extends AccessibleJComponent {
    private static final long serialVersionUID = 1L;
  }

  /** Replaces the already-computed immutable layout. EDT only. */
  public void setLayoutModel(StarlarkCallGraphLayout.Layout next) {
    layout = Objects.requireNonNull(next, "next");
    dragOffsets.clear();
    if (!layout.containsFunction(selectedFunctionId)) {
      selectedFunctionId = -1;
    }
    hoveredFunctionId = -1;
    hideNodeToolTip();
    fitToView();
    updateAccessibleDescription();
    repaint();
  }

  public void onSelection(Consumer<StarlarkProfileReader.CallGraphNode> listener) {
    selectionListener = Objects.requireNonNull(listener, "listener");
  }

  public void onOpenSource(Consumer<StarlarkProfileReader.SourceLocation> listener) {
    sourceListener = Objects.requireNonNull(listener, "listener");
  }

  public void setEdgeLabels(boolean shown) {
    edgeLabels = shown;
    repaint();
  }

  public void fitToView() {
    hideNodeToolTip();
    if (getWidth() <= 0 || getHeight() <= 0) {
      fitPending = true;
      return;
    }
    fitPending = false;
    transform = GraphTransform.fit(fitBounds(), getWidth(), getHeight(), FIT_MARGIN);
    repaint();
  }

  /** Restores every dragged node to the deterministic layout position. */
  public void resetMovedNodes() {
    if (dragOffsets.isEmpty()) {
      return;
    }
    dragOffsets.clear();
    repaint();
  }

  public boolean hasMovedNodes() {
    return !dragOffsets.isEmpty();
  }

  private double[] fitBounds() {
    double[] bounds = layout.bounds();
    for (StarlarkCallGraphLayout.NodeBox box : layout.nodes()) {
      Rectangle2D.Double moved = boundsFor(box);
      bounds[0] = Math.min(bounds[0], moved.getMinX());
      bounds[1] = Math.min(bounds[1], moved.getMinY());
      bounds[2] = Math.max(bounds[2], moved.getMaxX());
      bounds[3] = Math.max(bounds[3], moved.getMaxY());
    }
    return bounds;
  }

  /** Selects a function without reporting a new user selection to the host. */
  public boolean selectFunction(long functionId, boolean focus) {
    Optional<StarlarkCallGraphLayout.NodeBox> box = boxFor(functionId);
    if (box.isEmpty()) {
      return false;
    }
    selectedFunctionId = functionId;
    if (focus) {
      focus(box.orElseThrow());
    }
    updateAccessibleDescription();
    repaint();
    return true;
  }

  public boolean containsFunction(long functionId) {
    return layout.containsFunction(functionId);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    if (fitPending && getWidth() > 0 && getHeight() > 0) {
      fitToView();
    }
    Graphics2D canvas = (Graphics2D) graphics.create();
    try {
      canvas.setColor(getBackground());
      canvas.fillRect(0, 0, getWidth(), getHeight());
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      if (layout.nodes().isEmpty()) {
        drawEmpty(canvas);
        return;
      }
      for (StarlarkCallGraphLayout.EdgePath edge : layout.edges()) {
        if (!isSelectedEdge(edge)) {
          drawEdge(canvas, edge, false);
        }
      }
      for (StarlarkCallGraphLayout.EdgePath edge : layout.edges()) {
        if (isSelectedEdge(edge)) {
          drawEdge(canvas, edge, true);
        }
      }
      for (StarlarkCallGraphLayout.NodeBox box : layout.nodes()) {
        drawNode(canvas, box);
      }
    } finally {
      canvas.dispose();
    }
  }

  private void drawEmpty(Graphics2D canvas) {
    String message = "No attributed Starlark call relationships were recorded.";
    FontMetrics metrics = canvas.getFontMetrics();
    canvas.setColor(GraphColours.label());
    canvas.drawString(
        message,
        Math.max(10, (getWidth() - metrics.stringWidth(message)) / 2),
        Math.max(metrics.getAscent() + 10, getHeight() / 2));
  }

  private void drawEdge(
      Graphics2D canvas, StarlarkCallGraphLayout.EdgePath edge, boolean selected) {
    StarlarkCallGraphLayout.NodeBox caller = layout.nodes().get(edge.callerPosition());
    StarlarkCallGraphLayout.NodeBox callee = layout.nodes().get(edge.calleePosition());
    EdgeGeometry geometry = edgeGeometry(caller, callee);
    double fraction =
        edge.edge().cpuMicros().isPresent() && layout.maxEdgeCpuMicros() > 0
            ? (double) edge.edge().cpuMicros().getAsLong() / layout.maxEdgeCpuMicros()
            : 0;
    Color colour =
        selected
            ? GraphColours.EDGE_HIGHLIGHTED
            : withAlpha(
                edge.edge().cpuMicros().isPresent()
                    ? GraphColours.heat(fraction)
                    : GraphColours.UNKNOWN,
                145);
    float width = selected ? 2.5f : (float) (1.0 + 4.0 * Math.sqrt(fraction));
    canvas.setColor(colour);
    canvas.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    canvas.draw(geometry.path());
    drawArrow(
        canvas,
        geometry.arrowFromX(),
        geometry.arrowFromY(),
        geometry.endX(),
        geometry.endY(),
        colour,
        width);
    if (edgeLabels && (transform.scale() >= EDGE_LABEL_SCALE || selected)) {
      drawEdgeLabel(canvas, edge.edge().cpuMicros(), geometry.labelX(), geometry.labelY());
    }
  }

  private EdgeGeometry edgeGeometry(
      StarlarkCallGraphLayout.NodeBox caller, StarlarkCallGraphLayout.NodeBox callee) {
    double callerX = transform.screenX(centerX(caller));
    double callerY = transform.screenY(centerY(caller));
    double calleeX = transform.screenX(centerX(callee));
    double calleeY = transform.screenY(centerY(callee));
    double callerHalfWidth = caller.width() * transform.scale() / 2;
    double callerHalfHeight = caller.height() * transform.scale() / 2;
    double calleeHalfWidth = callee.width() * transform.scale() / 2;
    double calleeHalfHeight = callee.height() * transform.scale() / 2;
    Path2D.Double path = new Path2D.Double();
    if (caller.node().functionId() == callee.node().functionId()
        || calleeY <= callerY + callerHalfHeight) {
      double startX = callerX + callerHalfWidth;
      double startY = callerY;
      double endX = calleeX + calleeHalfWidth;
      double endY = calleeY;
      double loopX = Math.max(startX, endX) + Math.max(30, 58 * transform.scale());
      path.moveTo(startX, startY);
      path.curveTo(loopX, startY, loopX, endY, endX, endY);
      return new EdgeGeometry(path, loopX, endY, endX, endY, loopX + 4, (startY + endY) / 2);
    }
    double startY = callerY + callerHalfHeight;
    double endY = calleeY - calleeHalfHeight;
    double middleY = (startY + endY) / 2;
    path.moveTo(callerX, startY);
    path.curveTo(callerX, middleY, calleeX, middleY, calleeX, endY);
    return new EdgeGeometry(
        path, calleeX, middleY, calleeX, endY, (callerX + calleeX) / 2 + 4, middleY - 4);
  }

  private void drawArrow(
      Graphics2D canvas,
      double fromX,
      double fromY,
      double endX,
      double endY,
      Color colour,
      float strokeWidth) {
    double dx = endX - fromX;
    double dy = endY - fromY;
    double length = Math.hypot(dx, dy);
    if (length < ARROW_LENGTH + 2) {
      return;
    }
    double ux = dx / length;
    double uy = dy / length;
    double backX = endX - ux * ARROW_LENGTH;
    double backY = endY - uy * ARROW_LENGTH;
    Path2D.Double head = new Path2D.Double();
    head.moveTo(endX, endY);
    head.lineTo(backX - uy * ARROW_HALF_WIDTH, backY + ux * ARROW_HALF_WIDTH);
    head.lineTo(backX + uy * ARROW_HALF_WIDTH, backY - ux * ARROW_HALF_WIDTH);
    head.closePath();
    canvas.setColor(colour);
    canvas.fill(head);
    canvas.setStroke(new BasicStroke(strokeWidth));
    canvas.draw(head);
  }

  private void drawEdgeLabel(Graphics2D canvas, OptionalLong cpuMicros, double x, double y) {
    String text = EntityFormat.duration(cpuMicros);
    Font font = baseFont().deriveFont(Math.max(10f, baseFont().getSize2D() - 1f));
    canvas.setFont(font);
    FontMetrics metrics = canvas.getFontMetrics();
    int width = metrics.stringWidth(text) + 8;
    int height = metrics.getHeight() + 2;
    int left = (int) Math.round(x - width / 2.0);
    int top = (int) Math.round(y - metrics.getAscent());
    canvas.setColor(withAlpha(getBackground(), 225));
    canvas.fillRoundRect(left, top, width, height, 7, 7);
    canvas.setColor(GraphColours.label());
    canvas.drawString(text, left + 4, top + metrics.getAscent());
  }

  private void drawNode(Graphics2D canvas, StarlarkCallGraphLayout.NodeBox box) {
    Rectangle2D.Double worldBounds = boundsFor(box);
    double x = transform.screenX(worldBounds.x);
    double y = transform.screenY(worldBounds.y);
    double width = box.width() * transform.scale();
    double height = box.height() * transform.scale();
    Shape shape =
        new RoundRectangle2D.Double(
            x, y, width, height, Math.min(14, width / 5), Math.min(14, height / 5));
    boolean selected = box.node().functionId() == selectedFunctionId;
    boolean hovered = box.node().functionId() == hoveredFunctionId;
    Color heat = nodeHeat(box.node());
    Color fill = blend(getBackground(), heat, isDark() ? 0.38 : 0.20);
    canvas.setColor(fill);
    canvas.fill(shape);
    canvas.setColor(selected ? GraphColours.SELECTION : hovered ? GraphColours.HOVER : heat);
    canvas.setStroke(new BasicStroke(selected ? 3f : hovered ? 2f : 1.5f));
    canvas.draw(shape);

    if (!showsName(box)) {
      return;
    }
    Shape previousClip = canvas.getClip();
    canvas.clip(shape);
    canvas.setColor(GraphColours.label());
    int left = (int) Math.round(x) + 8;
    int available = Math.max(0, (int) Math.round(width) - 16);
    int baseline = (int) Math.round(y) + 16;
    Font plain = baseFont().deriveFont(Font.PLAIN, Math.max(10f, baseFont().getSize2D() - 1f));
    Font bold = baseFont().deriveFont(Font.BOLD);
    boolean showSource = width >= 138 && height >= 76;
    boolean showSelf = width >= 82 && height >= 48;
    boolean showCumulative = width >= 72 && height >= 32;
    if (showSource) {
      canvas.setFont(plain);
      String source =
          box.node()
              .source()
              .map(
                  location ->
                      location.path()
                          + (location.line().isPresent() ? ":" + location.line().getAsInt() : ""))
              .orElse("Source unavailable");
      canvas.drawString(clip(source, canvas.getFontMetrics(), available), left, baseline);
      baseline += canvas.getFontMetrics().getHeight() + 1;
    }
    canvas.setFont(bold);
    canvas.drawString(
        clip(box.node().function(), canvas.getFontMetrics(), available), left, baseline);
    baseline += canvas.getFontMetrics().getHeight() + 1;
    canvas.setFont(plain);
    if (showSelf) {
      canvas.drawString(
          clip(
              "Self " + durationAndPercent(box.node().selfCpuMicros()),
              canvas.getFontMetrics(),
              available),
          left,
          baseline);
      baseline += canvas.getFontMetrics().getHeight();
    }
    if (showCumulative) {
      canvas.drawString(
          clip(
              "Cumulative " + durationAndPercent(box.node().cumulativeCpuMicros()),
              canvas.getFontMetrics(),
              available),
          left,
          baseline);
    }
    canvas.setClip(previousClip);
  }

  private String durationAndPercent(OptionalLong value) {
    if (value.isEmpty()
        || layout.graph().totalCpuMicros().isEmpty()
        || layout.graph().totalCpuMicros().getAsLong() <= 0) {
      return EntityFormat.duration(value);
    }
    return EntityFormat.duration(value)
        + " (%.1f%%)"
            .formatted(100.0 * value.getAsLong() / layout.graph().totalCpuMicros().getAsLong());
  }

  private boolean showsName(StarlarkCallGraphLayout.NodeBox box) {
    return box.width() * transform.scale() >= 54 && box.height() * transform.scale() >= 18;
  }

  private Color nodeHeat(StarlarkProfileReader.CallGraphNode node) {
    if (node.cumulativeCpuMicros().isEmpty()
        || layout.graph().totalCpuMicros().isEmpty()
        || layout.graph().totalCpuMicros().getAsLong() <= 0) {
      return GraphColours.UNKNOWN;
    }
    return GraphColours.heat(
        (double) node.cumulativeCpuMicros().getAsLong()
            / layout.graph().totalCpuMicros().getAsLong());
  }

  private boolean isSelectedEdge(StarlarkCallGraphLayout.EdgePath edge) {
    return selectedFunctionId > 0
        && (edge.edge().callerFunctionId() == selectedFunctionId
            || edge.edge().calleeFunctionId() == selectedFunctionId);
  }

  private Optional<StarlarkCallGraphLayout.NodeBox> boxAt(int screenX, int screenY) {
    double worldX = transform.worldX(screenX);
    double worldY = transform.worldY(screenY);
    for (int index = layout.nodes().size() - 1; index >= 0; index--) {
      StarlarkCallGraphLayout.NodeBox box = layout.nodes().get(index);
      if (boundsFor(box).contains(worldX, worldY)) {
        return Optional.of(box);
      }
    }
    return Optional.empty();
  }

  private Optional<StarlarkCallGraphLayout.NodeBox> boxFor(long functionId) {
    return layout.nodes().stream().filter(box -> box.node().functionId() == functionId).findFirst();
  }

  private void selectFromPointer(StarlarkCallGraphLayout.NodeBox box) {
    boolean changed = selectedFunctionId != box.node().functionId();
    selectedFunctionId = box.node().functionId();
    if (changed) {
      selectionListener.accept(box.node());
    }
    updateAccessibleDescription();
    repaint();
  }

  private void installKeyboardActions() {
    CanvasAccessibility.bind(
        this,
        "starlark-call-previous",
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
        () -> moveKeyboardSelection(-1));
    CanvasAccessibility.bind(
        this,
        "starlark-call-previous-up",
        KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
        () -> moveKeyboardSelection(-1));
    CanvasAccessibility.bind(
        this,
        "starlark-call-next",
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
        () -> moveKeyboardSelection(1));
    CanvasAccessibility.bind(
        this,
        "starlark-call-next-down",
        KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
        () -> moveKeyboardSelection(1));
    CanvasAccessibility.bind(
        this,
        "starlark-call-focus",
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
        this::focusKeyboardSelection);
    CanvasAccessibility.bind(
        this,
        "starlark-call-open-source",
        KeyStroke.getKeyStroke(KeyEvent.VK_O, 0),
        this::openKeyboardSelection);
    CanvasAccessibility.bind(
        this,
        "starlark-call-zoom-in",
        KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK),
        () -> zoomFromKeyboard(1.25));
    CanvasAccessibility.bind(
        this,
        "starlark-call-zoom-in-keypad",
        KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0),
        () -> zoomFromKeyboard(1.25));
    CanvasAccessibility.bind(
        this,
        "starlark-call-zoom-out",
        KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0),
        () -> zoomFromKeyboard(1.0 / 1.25));
    CanvasAccessibility.bind(
        this,
        "starlark-call-zoom-out-keypad",
        KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0),
        () -> zoomFromKeyboard(1.0 / 1.25));
    CanvasAccessibility.bind(
        this, "starlark-call-fit", KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), this::fitToView);
  }

  private void moveKeyboardSelection(int delta) {
    if (layout.nodes().isEmpty()) {
      return;
    }
    int current = -1;
    for (int index = 0; index < layout.nodes().size(); index++) {
      if (layout.nodes().get(index).node().functionId() == selectedFunctionId) {
        current = index;
        break;
      }
    }
    if (current < 0 && delta < 0) {
      current = 0;
    }
    selectFromPointer(layout.nodes().get(Math.floorMod(current + delta, layout.nodes().size())));
  }

  private void focusKeyboardSelection() {
    boxFor(selectedFunctionId).ifPresent(this::focus);
  }

  private void openKeyboardSelection() {
    boxFor(selectedFunctionId).flatMap(box -> box.node().source()).ifPresent(sourceListener);
  }

  private void zoomFromKeyboard(double factor) {
    transform = transform.zoomedAround(getWidth() / 2.0, getHeight() / 2.0, factor);
    fitPending = false;
    repaint();
  }

  private void updateAccessibleDescription() {
    String state;
    Optional<StarlarkCallGraphLayout.NodeBox> selected = boxFor(selectedFunctionId);
    if (layout.nodes().isEmpty()) {
      state = "No functions are shown. ";
    } else if (selected.isPresent()) {
      state =
          "Showing "
              + layout.nodes().size()
              + " functions. Selected "
              + selected.orElseThrow().node().function()
              + ". ";
    } else {
      state = "Showing " + layout.nodes().size() + " functions; none is selected. ";
    }
    CanvasAccessibility.describe(this, state + KEYBOARD_HELP);
  }

  private void focus(StarlarkCallGraphLayout.NodeBox box) {
    double scale = Math.max(transform.scale(), 1.0);
    transform =
        new GraphTransform(
            centerX(box) - getWidth() / (2.0 * scale),
            centerY(box) - getHeight() / (2.0 * scale),
            scale);
    repaint();
  }

  private double centerX(StarlarkCallGraphLayout.NodeBox box) {
    double[] offset = dragOffsets.get(box.node().functionId());
    return box.centerX() + (offset == null ? 0 : offset[0]);
  }

  private double centerY(StarlarkCallGraphLayout.NodeBox box) {
    double[] offset = dragOffsets.get(box.node().functionId());
    return box.centerY() + (offset == null ? 0 : offset[1]);
  }

  private Rectangle2D.Double boundsFor(StarlarkCallGraphLayout.NodeBox box) {
    return new Rectangle2D.Double(
        centerX(box) - box.width() / 2, centerY(box) - box.height() / 2, box.width(), box.height());
  }

  private void showPopup(MouseEvent event, StarlarkCallGraphLayout.NodeBox box) {
    hideNodeToolTip();
    selectFromPointer(box);
    JPopupMenu menu = new JPopupMenu();
    JMenuItem focus = new JMenuItem("Focus function");
    focus.addActionListener(ignored -> focus(box));
    menu.add(focus);
    JMenuItem source = new JMenuItem("Open source file…");
    source.setEnabled(box.node().source().isPresent());
    source.addActionListener(ignored -> box.node().source().ifPresent(sourceListener));
    menu.add(source);
    menu.show(this, event.getX(), event.getY());
  }

  @Override
  public String getToolTipText(MouseEvent event) {
    return boxAt(event.getX(), event.getY()).map(box -> nodeToolTipText(box.node())).orElse(null);
  }

  private static String nodeToolTipText(StarlarkProfileReader.CallGraphNode node) {
    return node.function()
        + " — self "
        + EntityFormat.duration(node.selfCpuMicros())
        + ", cumulative "
        + EntityFormat.duration(node.cumulativeCpuMicros());
  }

  private void showNodeToolTip(MouseEvent event, StarlarkProfileReader.CallGraphNode node) {
    nodeToolTip.setTipText(nodeToolTipText(node));
    Dimension preferred = nodeToolTip.getPreferredSize();
    int width = Math.min(preferred.width, Math.max(1, getWidth() - 8));
    int height = Math.min(preferred.height, Math.max(1, getHeight() - 8));
    int x = event.getX() + 14;
    if (x + width > getWidth() - 4) {
      x = event.getX() - width - 14;
    }
    x = Math.max(4, Math.min(x, Math.max(4, getWidth() - width - 4)));
    int y = event.getY() + 18;
    if (y + height > getHeight() - 4) {
      y = event.getY() - height - 8;
    }
    y = Math.max(4, Math.min(y, Math.max(4, getHeight() - height - 4)));
    nodeToolTip.setBounds(x, y, width, height);
    nodeToolTip.setVisible(true);
    nodeToolTip.repaint();
  }

  private void hideNodeToolTip() {
    nodeToolTip.setVisible(false);
  }

  private Font baseFont() {
    Font font = getFont();
    return font == null ? new Font(Font.DIALOG, Font.PLAIN, 12) : font;
  }

  private boolean isDark() {
    Color background = getBackground();
    return background.getRed() + background.getGreen() + background.getBlue() < 384;
  }

  private static Color blend(Color from, Color to, double amount) {
    double value = Math.max(0, Math.min(1, amount));
    return new Color(
        (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * value),
        (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * value),
        (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * value));
  }

  private static Color withAlpha(Color colour, int alpha) {
    return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
  }

  private static String clip(String value, FontMetrics metrics, int available) {
    if (available <= 0 || value.isEmpty()) {
      return "";
    }
    if (metrics.stringWidth(value) <= available) {
      return value;
    }
    String ellipsis = "…";
    int end = value.length();
    while (end > 0 && metrics.stringWidth(value.substring(0, end) + ellipsis) > available) {
      end--;
    }
    return end == 0 ? "" : value.substring(0, end) + ellipsis;
  }

  private final class MouseHandler extends MouseAdapter {
    private boolean popupShown;

    @Override
    public void mousePressed(MouseEvent event) {
      requestFocusInWindow();
      hideNodeToolTip();
      popupShown = false;
      if (maybePopup(event)) {
        return;
      }
      if (!SwingUtilities.isLeftMouseButton(event)) {
        return;
      }
      Optional<StarlarkCallGraphLayout.NodeBox> hit = boxAt(event.getX(), event.getY());
      if (hit.isPresent()) {
        StarlarkCallGraphLayout.NodeBox box = hit.orElseThrow();
        selectFromPointer(box);
        draggedFunctionId = box.node().functionId();
        nodePressPoint = event.getPoint();
        double[] startingOffset = dragOffsets.get(draggedFunctionId);
        nodeStartOffsetX = startingOffset == null ? 0 : startingOffset[0];
        nodeStartOffsetY = startingOffset == null ? 0 : startingOffset[1];
        nodeDragging = false;
      } else {
        panning = true;
        dragPoint = event.getPoint();
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
      }
    }

    @Override
    public void mouseReleased(MouseEvent event) {
      maybePopup(event);
      panning = false;
      dragPoint = null;
      draggedFunctionId = -1;
      nodePressPoint = null;
      nodeDragging = false;
      setCursor(
          hoveredFunctionId > 0
              ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
              : Cursor.getDefaultCursor());
    }

    @Override
    public void mouseDragged(MouseEvent event) {
      hideNodeToolTip();
      if (draggedFunctionId > 0 && nodePressPoint != null) {
        double dx = event.getX() - nodePressPoint.x;
        double dy = event.getY() - nodePressPoint.y;
        if (!nodeDragging && Math.hypot(dx, dy) < NODE_DRAG_THRESHOLD_PIXELS) {
          return;
        }
        nodeDragging = true;
        double movedX = nodeStartOffsetX + dx / transform.scale();
        double movedY = nodeStartOffsetY + dy / transform.scale();
        if (Math.abs(movedX) < 1e-9 && Math.abs(movedY) < 1e-9) {
          dragOffsets.remove(draggedFunctionId);
        } else {
          dragOffsets.put(draggedFunctionId, new double[] {movedX, movedY});
        }
        hoveredFunctionId = draggedFunctionId;
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        repaint();
        return;
      }
      if (!panning || dragPoint == null) {
        return;
      }
      int dx = event.getX() - dragPoint.x;
      int dy = event.getY() - dragPoint.y;
      transform = transform.pannedByPixels(dx, dy);
      dragPoint = event.getPoint();
      repaint();
    }

    @Override
    public void mouseClicked(MouseEvent event) {
      if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
        boxAt(event.getX(), event.getY()).ifPresent(StarlarkCallGraph.this::focus);
      }
    }

    @Override
    public void mouseMoved(MouseEvent event) {
      long prior = hoveredFunctionId;
      Optional<StarlarkCallGraphLayout.NodeBox> hit = boxAt(event.getX(), event.getY());
      hoveredFunctionId = hit.map(box -> box.node().functionId()).orElse(-1L);
      if (hit.isPresent()) {
        showNodeToolTip(event, hit.orElseThrow().node());
      } else {
        hideNodeToolTip();
      }
      setCursor(
          hoveredFunctionId > 0
              ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
              : Cursor.getDefaultCursor());
      if (prior != hoveredFunctionId) {
        repaint();
      }
    }

    @Override
    public void mouseExited(MouseEvent event) {
      if (panning || nodeDragging) {
        return;
      }
      hoveredFunctionId = -1;
      hideNodeToolTip();
      setCursor(Cursor.getDefaultCursor());
      repaint();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent event) {
      hideNodeToolTip();
      double factor = Math.pow(1.1, -event.getPreciseWheelRotation());
      transform = transform.zoomedAround(event.getX(), event.getY(), factor);
      fitPending = false;
      repaint();
    }

    private boolean maybePopup(MouseEvent event) {
      if (!event.isPopupTrigger() || popupShown) {
        return false;
      }
      Optional<StarlarkCallGraphLayout.NodeBox> hit = boxAt(event.getX(), event.getY());
      if (hit.isEmpty()) {
        return false;
      }
      popupShown = true;
      showPopup(event, hit.orElseThrow());
      return true;
    }
  }

  private record EdgeGeometry(
      Path2D.Double path,
      double arrowFromX,
      double arrowFromY,
      double endX,
      double endY,
      double labelX,
      double labelY) {}

  /** Lets pointer events keep reaching the graph while this child paints above it. */
  private static final class PassthroughToolTip extends JToolTip {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean contains(int x, int y) {
      return false;
    }
  }

  // Package-local, read-only test hooks. They perform no profile work.
  StarlarkCallGraphLayout.Layout layoutForTest() {
    return layout;
  }

  long selectedFunctionIdForTest() {
    return selectedFunctionId;
  }

  GraphTransform transformForTest() {
    return transform;
  }

  int movedNodeCountForTest() {
    return dragOffsets.size();
  }

  Rectangle2D.Double boundsForTest(long functionId) {
    return boxFor(functionId).map(this::boundsFor).orElse(null);
  }

  boolean showsNameForTest(long functionId) {
    return boxFor(functionId).map(this::showsName).orElse(false);
  }

  boolean nodeToolTipVisibleForTest() {
    return nodeToolTip.isVisible();
  }

  String nodeToolTipTextForTest() {
    return nodeToolTip.getTipText();
  }
}
