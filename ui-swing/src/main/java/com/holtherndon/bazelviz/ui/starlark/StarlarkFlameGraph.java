package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import com.holtherndon.bazelviz.ui.theme.CanvasAccessibility;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToolTip;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeListener;

/**
 * A bounded, zoom-by-focus icicle view of Starlark call contexts.
 *
 * <p>The component paints only the immutable slice supplied by the reader. Contexts narrower than
 * two pixels are combined into a hatched aggregate cell for that parent and depth; they are never
 * silently erased. A separate notice states when the reader's node budget omitted rows entirely.
 */
public final class StarlarkFlameGraph extends JComponent {

  private static final long serialVersionUID = 1L;
  private Function<OptionalLong, String> valueFormatter = EntityFormat::duration;

  public void setValueFormatter(Function<OptionalLong, String> formatter) {
    valueFormatter = formatter;
    repaint();
  }

  private static final int OUTER_GAP = 10;
  private static final int NOTICE_HEIGHT = 48;
  private static final int ROW_HEIGHT = 28;
  private static final int CELL_GAP = 1;
  private static final double MIN_CELL_WIDTH = 2.0;

  private StarlarkProfileReader.FlameSlice slice =
      new StarlarkProfileReader.FlameSlice(
          OptionalLong.empty(), 0, 0, OptionalLong.empty(), List.of());
  private List<NodeCell> nodeCells = List.of();
  private List<AggregateCell> aggregateCells = List.of();
  private LongConsumer focusListener = ignored -> {};
  private Consumer<StarlarkProfileReader.SourceLocation> sourceListener = ignored -> {};
  private final PassthroughToolTip hoverToolTip = new PassthroughToolTip();
  private final ChangeListener viewportChangeListener = event -> clearHover();
  private StarlarkProfileReader.FlameNode selected;
  private long hoveredNodeId = -1;
  private JViewport hoverViewport;

  private static final String KEYBOARD_HELP =
      "Use the arrow keys to move between visible call contexts, Enter to focus, "
          + "O to open source, and Escape to clear the selection.";

  public StarlarkFlameGraph() {
    setOpaque(true);
    setLayout(null);
    CanvasAccessibility.configure(
        this, "Profile flame graph", "No call contexts are shown. " + KEYBOARD_HELP);
    installKeyboardActions();
    PlainText.disableHtml(hoverToolTip);
    hoverToolTip.setComponent(this);
    hoverToolTip.setVisible(false);
    add(hoverToolTip);
    MouseAdapter mouse =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent event) {
            requestFocusInWindow();
            hideHoverToolTip();
            handlePopup(event);
          }

          @Override
          public void mouseReleased(MouseEvent event) {
            handlePopup(event);
          }

          @Override
          public void mouseClicked(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event)) {
              return;
            }
            nodeAt(event.getX(), event.getY())
                .ifPresent(
                    node -> {
                      selected = node;
                      updateAccessibleDescription();
                      repaint();
                      if (event.getClickCount() == 2) {
                        focusListener.accept(node.id());
                      }
                    });
          }

          @Override
          public void mouseMoved(MouseEvent event) {
            updateHover(event);
          }

          @Override
          public void mouseExited(MouseEvent event) {
            clearHover();
          }
        };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addHierarchyListener(
        event -> {
          if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) {
            updateHoverViewport();
          }
        });
  }

  @Override
  public void addNotify() {
    super.addNotify();
    updateHoverViewport();
  }

  @Override
  public void removeNotify() {
    attachHoverViewport(null);
    super.removeNotify();
  }

  @Override
  public AccessibleContext getAccessibleContext() {
    if (accessibleContext == null) {
      accessibleContext = new AccessibleStarlarkFlameGraph();
    }
    return accessibleContext;
  }

  /** Gives the custom-painted component the standard JComponent accessibility contract. */
  protected final class AccessibleStarlarkFlameGraph extends AccessibleJComponent {
    private static final long serialVersionUID = 1L;
  }

  /** Replaces the shown immutable hierarchy slice. EDT only. */
  public void setSlice(StarlarkProfileReader.FlameSlice next) {
    slice = Objects.requireNonNull(next, "next");
    selected = null;
    hoveredNodeId = -1;
    hideHoverToolTip();
    updatePreferredHeight();
    updateAccessibleDescription();
    revalidate();
    repaint();
  }

  public void onFocus(LongConsumer listener) {
    focusListener = Objects.requireNonNull(listener, "listener");
  }

  public void onOpenSource(Consumer<StarlarkProfileReader.SourceLocation> listener) {
    sourceListener = Objects.requireNonNull(listener, "listener");
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    Graphics2D canvas = (Graphics2D) graphics.create();
    try {
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      canvas.setColor(background());
      canvas.fillRect(0, 0, getWidth(), getHeight());

      Layout layout = layout(getWidth());
      nodeCells = layout.nodes();
      aggregateCells = layout.aggregates();
      drawNotice(canvas, layout);
      if (slice.nodes().isEmpty()) {
        canvas.setColor(foreground());
        canvas.drawString(
            "No sampled call contexts were recorded.", OUTER_GAP, NOTICE_HEIGHT + ROW_HEIGHT);
        return;
      }
      for (NodeCell cell : nodeCells) {
        drawNode(canvas, cell);
      }
      for (AggregateCell cell : aggregateCells) {
        drawAggregate(canvas, cell);
      }
    } finally {
      canvas.dispose();
    }
  }

  private void drawNotice(Graphics2D canvas, Layout layout) {
    canvas.setColor(foreground());
    String loaded = loadedNotice();
    canvas.drawString(loaded, OUTER_GAP, 17);
    String interaction = interactionNotice(layout);
    canvas.drawString(interaction, OUTER_GAP, 36);
  }

  private void drawNode(Graphics2D canvas, NodeCell cell) {
    Rectangle2D.Double bounds = cell.bounds();
    StarlarkProfileReader.FlameNode node = cell.node();
    Color fill = colourFor(node);
    canvas.setColor(fill);
    canvas.fill(bounds);
    boolean selectedNode = node.equals(selected);
    boolean hoveredNode = node.id() == hoveredNodeId;
    canvas.setColor(selectedNode || hoveredNode ? selectionColour() : borderColour());
    canvas.setStroke(new BasicStroke(selectedNode ? 2f : hoveredNode ? 1.75f : 1f));
    canvas.draw(bounds);

    if (bounds.width < 18) {
      return;
    }
    String label = node.function();
    FontMetrics metrics = canvas.getFontMetrics();
    int available = Math.max(0, (int) bounds.width - 8);
    label = clip(label, metrics, available);
    if (label.isEmpty()) {
      return;
    }
    Shape previousClip = canvas.getClip();
    canvas.clip(bounds);
    canvas.setColor(contrastingText(fill));
    int baseline = (int) bounds.y + (ROW_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2;
    canvas.drawString(label, (int) bounds.x + 4, baseline);
    canvas.setClip(previousClip);
  }

  private void drawAggregate(Graphics2D canvas, AggregateCell cell) {
    Rectangle2D.Double bounds = cell.bounds();
    Color fill = aggregateColour();
    canvas.setColor(fill);
    canvas.fill(bounds);
    canvas.setColor(borderColour());
    canvas.draw(bounds);
    for (int x = (int) bounds.x - ROW_HEIGHT; x < bounds.x + bounds.width; x += 7) {
      canvas.drawLine(x, (int) (bounds.y + bounds.height), x + ROW_HEIGHT, (int) bounds.y);
    }
    if (bounds.width >= 48) {
      canvas.setColor(contrastingText(fill));
      String label =
          clip(
              cell.count() + " smaller",
              canvas.getFontMetrics(),
              Math.max(0, (int) bounds.width - 8));
      canvas.drawString(
          label,
          (int) bounds.x + 4,
          (int) bounds.y
              + (ROW_HEIGHT
                      + canvas.getFontMetrics().getAscent()
                      - canvas.getFontMetrics().getDescent())
                  / 2);
    }
  }

  private void handlePopup(MouseEvent event) {
    if (!event.isPopupTrigger()) {
      return;
    }
    Optional<StarlarkProfileReader.FlameNode> hit = nodeAt(event.getX(), event.getY());
    if (hit.isEmpty()) {
      return;
    }
    selected = hit.orElseThrow();
    updateAccessibleDescription();
    repaint();
    JPopupMenu menu = new JPopupMenu();
    JMenuItem focus = new JMenuItem("Focus call context");
    focus.addActionListener(ignored -> focusListener.accept(selected.id()));
    menu.add(focus);
    JMenuItem source = new JMenuItem("Open source file…");
    source.setEnabled(selected.source().isPresent());
    source.addActionListener(ignored -> selected.source().ifPresent(sourceListener));
    menu.add(source);
    menu.show(this, event.getX(), event.getY());
  }

  private void installKeyboardActions() {
    CanvasAccessibility.bind(
        this,
        "starlark-flame-previous",
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
        () -> moveKeyboardSelection(-1));
    CanvasAccessibility.bind(
        this,
        "starlark-flame-previous-up",
        KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
        () -> moveKeyboardSelection(-1));
    CanvasAccessibility.bind(
        this,
        "starlark-flame-next",
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
        () -> moveKeyboardSelection(1));
    CanvasAccessibility.bind(
        this,
        "starlark-flame-next-down",
        KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0),
        () -> moveKeyboardSelection(1));
    CanvasAccessibility.bind(
        this,
        "starlark-flame-focus",
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
        this::focusKeyboardSelection);
    CanvasAccessibility.bind(
        this,
        "starlark-flame-open-source",
        KeyStroke.getKeyStroke(KeyEvent.VK_O, 0),
        this::openKeyboardSelection);
    CanvasAccessibility.bind(
        this,
        "starlark-flame-clear",
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        this::clearKeyboardSelection);
  }

  private void moveKeyboardSelection(int delta) {
    List<NodeCell> visible = layout(Math.max(1, getWidth())).nodes();
    if (visible.isEmpty()) {
      return;
    }
    int current = -1;
    if (selected != null) {
      for (int index = 0; index < visible.size(); index++) {
        if (visible.get(index).node().id() == selected.id()) {
          current = index;
          break;
        }
      }
    }
    if (current < 0 && delta < 0) {
      current = 0;
    }
    NodeCell next = visible.get(Math.floorMod(current + delta, visible.size()));
    selected = next.node();
    scrollRectToVisible(next.bounds().getBounds());
    updateAccessibleDescription();
    repaint();
  }

  private void focusKeyboardSelection() {
    if (selected != null) {
      focusListener.accept(selected.id());
    }
  }

  private void openKeyboardSelection() {
    if (selected != null) {
      selected.source().ifPresent(sourceListener);
    }
  }

  private void clearKeyboardSelection() {
    if (selected != null) {
      selected = null;
      updateAccessibleDescription();
      repaint();
    }
  }

  private void updateAccessibleDescription() {
    String state;
    if (slice.nodes().isEmpty()) {
      state = "No call contexts are shown. ";
    } else if (selected != null) {
      state =
          "Showing "
              + slice.nodes().size()
              + " call contexts. Selected "
              + selected.function()
              + ". ";
    } else {
      state = "Showing " + slice.nodes().size() + " call contexts; none is selected. ";
    }
    CanvasAccessibility.describe(this, state + KEYBOARD_HELP);
  }

  @Override
  public String getToolTipText(MouseEvent event) {
    Optional<StarlarkProfileReader.FlameNode> hit = nodeAt(event.getX(), event.getY());
    if (hit.isPresent()) {
      return nodeToolTipText(hit.orElseThrow());
    }
    for (AggregateCell cell : aggregateCells) {
      if (cell.bounds().contains(event.getPoint())) {
        return aggregateToolTipText(cell);
      }
    }
    return null;
  }

  private void updateHover(MouseEvent event) {
    long previousNodeId = hoveredNodeId;
    Optional<StarlarkProfileReader.FlameNode> node = nodeAt(event.getX(), event.getY());
    hoveredNodeId = node.map(StarlarkProfileReader.FlameNode::id).orElse(-1L);
    String text = getToolTipText(event);
    if (text == null) {
      hideHoverToolTip();
    } else {
      showHoverToolTip(event, text);
    }
    if (previousNodeId != hoveredNodeId) {
      repaint();
    }
  }

  private String nodeToolTipText(StarlarkProfileReader.FlameNode node) {
    return node.function()
        + " — cumulative "
        + valueFormatter.apply(node.inclusiveCpuMicros())
        + ", self "
        + valueFormatter.apply(node.selfCpuMicros());
  }

  private String aggregateToolTipText(AggregateCell cell) {
    return cell.count()
        + " contexts combined for display — "
        + valueFormatter.apply(cell.cpuMicros());
  }

  private void showHoverToolTip(MouseEvent event, String text) {
    hoverToolTip.setTipText(text);
    Rectangle visible = getVisibleRect();
    if (visible.isEmpty()) {
      visible = new Rectangle(0, 0, getWidth(), getHeight());
    }
    Dimension preferred = hoverToolTip.getPreferredSize();
    int width = Math.min(preferred.width, Math.max(1, visible.width - 8));
    int height = Math.min(preferred.height, Math.max(1, visible.height - 8));
    int x = event.getX() + 14;
    if (x + width > visible.x + visible.width - 4) {
      x = event.getX() - width - 14;
    }
    int minimumX = visible.x + 4;
    int maximumX = Math.max(minimumX, visible.x + visible.width - width - 4);
    x = Math.max(minimumX, Math.min(x, maximumX));
    int y = event.getY() + 18;
    if (y + height > visible.y + visible.height - 4) {
      y = event.getY() - height - 8;
    }
    int minimumY = visible.y + 4;
    int maximumY = Math.max(minimumY, visible.y + visible.height - height - 4);
    y = Math.max(minimumY, Math.min(y, maximumY));
    hoverToolTip.setBounds(x, y, width, height);
    hoverToolTip.setVisible(true);
    hoverToolTip.repaint();
  }

  private void hideHoverToolTip() {
    hoverToolTip.setVisible(false);
  }

  private void clearHover() {
    if (hoveredNodeId == -1 && !hoverToolTip.isVisible()) {
      return;
    }
    hoveredNodeId = -1;
    hideHoverToolTip();
    repaint();
  }

  private void updateHoverViewport() {
    attachHoverViewport(
        (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, StarlarkFlameGraph.this));
  }

  private void attachHoverViewport(JViewport next) {
    if (hoverViewport == next) {
      return;
    }
    if (hoverViewport != null) {
      hoverViewport.removeChangeListener(viewportChangeListener);
    }
    hoverViewport = next;
    if (hoverViewport != null) {
      hoverViewport.addChangeListener(viewportChangeListener);
    }
    clearHover();
  }

  private Optional<StarlarkProfileReader.FlameNode> nodeAt(int x, int y) {
    for (int i = nodeCells.size() - 1; i >= 0; i--) {
      NodeCell cell = nodeCells.get(i);
      if (cell.bounds().contains(x, y)) {
        return Optional.of(cell.node());
      }
    }
    return Optional.empty();
  }

  private Layout layout(int componentWidth) {
    int width = Math.max(0, componentWidth - OUTER_GAP * 2);
    if (width == 0 || slice.nodes().isEmpty()) {
      return new Layout(List.of(), List.of(), 0);
    }
    Map<Long, StarlarkProfileReader.FlameNode> byId = new LinkedHashMap<>();
    Map<Long, List<StarlarkProfileReader.FlameNode>> children = new HashMap<>();
    for (StarlarkProfileReader.FlameNode node : slice.nodes()) {
      byId.put(node.id(), node);
    }
    List<StarlarkProfileReader.FlameNode> roots = new ArrayList<>();
    for (StarlarkProfileReader.FlameNode node : slice.nodes()) {
      if (node.parentId().isEmpty() || !byId.containsKey(node.parentId().getAsLong())) {
        roots.add(node);
      } else {
        children
            .computeIfAbsent(node.parentId().getAsLong(), ignored -> new ArrayList<>())
            .add(node);
      }
    }
    roots.sort(nodeOrder());
    children.values().forEach(values -> values.sort(nodeOrder()));
    int baseDepth = roots.stream().mapToInt(StarlarkProfileReader.FlameNode::depth).min().orElse(0);

    double rootWeight = roots.stream().mapToDouble(StarlarkFlameGraph::weight).sum();
    if (!(rootWeight > 0)) {
      rootWeight = roots.size();
    }
    List<NodeCell> cells = new ArrayList<>();
    List<AggregateCell> aggregates = new ArrayList<>();
    ArrayDeque<NodeCell> queue = new ArrayDeque<>();
    double cursor = OUTER_GAP;
    for (int i = 0; i < roots.size(); i++) {
      StarlarkProfileReader.FlameNode root = roots.get(i);
      double cellWidth =
          i == roots.size() - 1 ? OUTER_GAP + width - cursor : width * weight(root) / rootWeight;
      NodeCell cell = new NodeCell(root, bounds(cursor, root.depth() - baseDepth, cellWidth));
      cells.add(cell);
      queue.add(cell);
      cursor += cellWidth;
    }

    long collapsed = 0;
    while (!queue.isEmpty()) {
      NodeCell parent = queue.removeFirst();
      List<StarlarkProfileReader.FlameNode> descendants =
          children.getOrDefault(parent.node().id(), List.of());
      if (descendants.isEmpty()) {
        continue;
      }
      double parentWeight = weight(parent.node());
      double childWeight = descendants.stream().mapToDouble(StarlarkFlameGraph::weight).sum();
      double denominator = parentWeight > 0 ? Math.max(parentWeight, childWeight) : childWeight;
      if (!(denominator > 0)) {
        denominator = descendants.size();
      }
      List<ChildWidth> visible = new ArrayList<>();
      List<ChildWidth> tiny = new ArrayList<>();
      for (StarlarkProfileReader.FlameNode child : descendants) {
        double childWidth = parent.bounds().width * weight(child) / denominator;
        ChildWidth measured = new ChildWidth(child, childWidth);
        (childWidth < MIN_CELL_WIDTH ? tiny : visible).add(measured);
      }
      double childCursor = parent.bounds().x;
      for (ChildWidth child : visible) {
        NodeCell cell =
            new NodeCell(
                child.node(), bounds(childCursor, child.node().depth() - baseDepth, child.width()));
        cells.add(cell);
        queue.add(cell);
        childCursor += child.width();
      }
      if (!tiny.isEmpty()) {
        double aggregateWidth = tiny.stream().mapToDouble(ChildWidth::width).sum();
        if (aggregateWidth > 0) {
          OptionalLong cpu = sumKnown(tiny);
          long aggregateCount =
              tiny.stream().mapToLong(child -> subtreeSize(child.node(), children)).sum();
          double parentRight = parent.bounds().x + parent.bounds().width;
          double paintedWidth = Math.max(MIN_CELL_WIDTH, aggregateWidth);
          double paintedX =
              Math.max(parent.bounds().x, Math.min(childCursor, parentRight - paintedWidth));
          aggregates.add(
              new AggregateCell(
                  parent.node().depth() + 1 - baseDepth,
                  aggregateCount,
                  cpu,
                  bounds(paintedX, parent.node().depth() + 1 - baseDepth, paintedWidth)));
          collapsed += aggregateCount;
        }
      }
    }
    return new Layout(List.copyOf(cells), List.copyOf(aggregates), collapsed);
  }

  private static Comparator<StarlarkProfileReader.FlameNode> nodeOrder() {
    return Comparator.comparingLong(
            (StarlarkProfileReader.FlameNode node) -> node.inclusiveCpuMicros().orElse(0))
        .reversed()
        .thenComparingLong(StarlarkProfileReader.FlameNode::id);
  }

  private static double weight(StarlarkProfileReader.FlameNode node) {
    return Math.max(1d, node.inclusiveCpuMicros().orElse(1));
  }

  private static Rectangle2D.Double bounds(double x, int depth, double width) {
    return new Rectangle2D.Double(
        x + CELL_GAP / 2.0,
        NOTICE_HEIGHT + depth * ROW_HEIGHT,
        Math.max(0, width - CELL_GAP),
        ROW_HEIGHT - CELL_GAP);
  }

  private static OptionalLong sumKnown(List<ChildWidth> children) {
    long total = 0;
    for (ChildWidth child : children) {
      if (child.node().inclusiveCpuMicros().isEmpty()) {
        return OptionalLong.empty();
      }
      try {
        total = Math.addExact(total, child.node().inclusiveCpuMicros().getAsLong());
      } catch (ArithmeticException overflow) {
        return OptionalLong.empty();
      }
    }
    return OptionalLong.of(total);
  }

  private static long subtreeSize(
      StarlarkProfileReader.FlameNode root,
      Map<Long, List<StarlarkProfileReader.FlameNode>> children) {
    long count = 0;
    ArrayDeque<StarlarkProfileReader.FlameNode> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      StarlarkProfileReader.FlameNode node = pending.removeFirst();
      count++;
      pending.addAll(children.getOrDefault(node.id(), List.of()));
    }
    return count;
  }

  private void updatePreferredHeight() {
    int minDepth =
        slice.nodes().stream().mapToInt(StarlarkProfileReader.FlameNode::depth).min().orElse(0);
    int maxDepth =
        slice.nodes().stream()
            .mapToInt(StarlarkProfileReader.FlameNode::depth)
            .max()
            .orElse(minDepth);
    setPreferredSize(
        new Dimension(720, NOTICE_HEIGHT + (maxDepth - minDepth + 1) * ROW_HEIGHT + OUTER_GAP));
  }

  private Color colourFor(StarlarkProfileReader.FlameNode node) {
    int hash = node.function().hashCode();
    float hue = Math.floorMod(hash, 360) / 360f;
    Color base = Color.getHSBColor(hue, 0.50f, isDark() ? 0.72f : 0.88f);
    return base;
  }

  private boolean isDark() {
    Color background = background();
    return background.getRed() + background.getGreen() + background.getBlue() < 384;
  }

  private Color background() {
    Color colour = UIManager.getColor("Panel.background");
    return colour == null ? Color.WHITE : colour;
  }

  private Color foreground() {
    Color colour = UIManager.getColor("Label.foreground");
    return colour == null ? Color.DARK_GRAY : colour;
  }

  private Color borderColour() {
    Color colour = UIManager.getColor("Component.borderColor");
    return colour == null ? foreground().darker() : colour;
  }

  private Color selectionColour() {
    Color colour = UIManager.getColor("Component.focusColor");
    return colour == null ? new Color(20, 90, 180) : colour;
  }

  private Color aggregateColour() {
    Color colour = UIManager.getColor("Label.disabledForeground");
    return colour == null ? Color.GRAY : colour;
  }

  private static Color contrastingText(Color background) {
    double luminance =
        0.2126 * background.getRed()
            + 0.7152 * background.getGreen()
            + 0.0722 * background.getBlue();
    return luminance < 140 ? Color.WHITE : Color.BLACK;
  }

  private static String clip(String text, FontMetrics metrics, int width) {
    if (metrics.stringWidth(text) <= width) {
      return text;
    }
    String ellipsis = "…";
    int available = width - metrics.stringWidth(ellipsis);
    if (available <= 0) {
      return "";
    }
    int low = 0;
    int high = text.length();
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (metrics.stringWidth(text.substring(0, middle)) <= available) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return text.substring(0, low) + ellipsis;
  }

  /** Deterministic layout hook for headless tests. */
  Layout layoutForTest(int width) {
    return layout(width);
  }

  String loadedNoticeForTest() {
    return loadedNotice();
  }

  String interactionNoticeForTest(int width) {
    return interactionNotice(layout(width));
  }

  boolean hoverToolTipVisibleForTest() {
    return hoverToolTip.isVisible();
  }

  String hoverToolTipTextForTest() {
    return hoverToolTip.getTipText();
  }

  Rectangle hoverToolTipBoundsForTest() {
    return hoverToolTip.getBounds();
  }

  long hoveredNodeIdForTest() {
    return hoveredNodeId;
  }

  long selectedNodeIdForTest() {
    return selected == null ? -1 : selected.id();
  }

  private String loadedNotice() {
    return slice.omittedNodeCount() == 0
        ? "Showing all " + slice.totalNodeCount() + " call contexts in this scope."
        : "Showing "
            + slice.nodes().size()
            + " of "
            + slice.totalNodeCount()
            + " call contexts; "
            + slice.omittedNodeCount()
            + " are outside the current read limit.";
  }

  private static String interactionNotice(Layout layout) {
    return layout.collapsedContexts() == 0
        ? "Double-click a context to focus it; Reset returns to all roots."
        : layout.collapsedContexts()
            + " sub-pixel contexts are combined into "
            + layout.aggregates().size()
            + " hatched aggregate bars. Focus a parent to reveal them.";
  }

  record NodeCell(StarlarkProfileReader.FlameNode node, Rectangle2D.Double bounds) {}

  record AggregateCell(int depth, long count, OptionalLong cpuMicros, Rectangle2D.Double bounds) {}

  record Layout(List<NodeCell> nodes, List<AggregateCell> aggregates, long collapsedContexts) {
    Layout {
      nodes = List.copyOf(nodes);
      aggregates = List.copyOf(aggregates);
    }
  }

  /** Lets mouse motion keep reaching the flame graph while this child paints above it. */
  private static final class PassthroughToolTip extends JToolTip {

    private static final long serialVersionUID = 1L;

    @Override
    public boolean contains(int x, int y) {
      return false;
    }
  }

  private record ChildWidth(StarlarkProfileReader.FlameNode node, double width) {}
}
