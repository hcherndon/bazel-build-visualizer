package com.holtherndon.bazelviz.ui.starlark;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import java.awt.AWTKeyStroke;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class StarlarkFlameGraphTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  void focusedDepthStartsAtTheTopAndTinySubtreesBecomeOneVisibleAggregate() throws Exception {
    StarlarkProfileReader.SourceLocation source =
        StarlarkProfileReader.SourceLocation.file("rules.bzl");
    List<StarlarkProfileReader.FlameNode> nodes =
        List.of(
            node(1, OptionalLong.empty(), 7, "root", 1_000, source),
            node(2, OptionalLong.of(1), 8, "large", 999, source),
            node(3, OptionalLong.of(1), 8, "tiny", 1, source),
            node(4, OptionalLong.of(3), 9, "tiny child", 1, source));
    StarlarkProfileReader.FlameSlice slice =
        new StarlarkProfileReader.FlameSlice(
            OptionalLong.of(1), 4, 0, OptionalLong.of(1_000), nodes);
    StarlarkFlameGraph graph = onEdt(StarlarkFlameGraph::new);

    StarlarkFlameGraph.Layout layout =
        onEdt(
            () -> {
              graph.setSlice(slice);
              return graph.layoutForTest(300);
            });

    assertThat(layout.nodes())
        .extracting(cell -> cell.node().function())
        .containsExactly("root", "large");
    assertThat(layout.nodes().getFirst().bounds().y).isEqualTo(48.0);
    assertThat(layout.aggregates())
        .singleElement()
        .satisfies(
            aggregate -> {
              assertThat(aggregate.count()).isEqualTo(2);
              assertThat(aggregate.cpuMicros()).hasValue(1);
              assertThat(aggregate.bounds().width).isPositive();
            });
    assertThat(layout.collapsedContexts()).isEqualTo(2);
    assertThat(onEdt(() -> graph.interactionNoticeForTest(300)))
        .contains("2 sub-pixel contexts")
        .contains("hatched aggregate");
  }

  @Test
  void readLimitOmissionsAreNamedWithExactCounts() throws Exception {
    StarlarkFlameGraph graph = onEdt(StarlarkFlameGraph::new);
    StarlarkProfileReader.FlameSlice slice =
        new StarlarkProfileReader.FlameSlice(
            OptionalLong.empty(),
            10,
            8,
            OptionalLong.of(2_000_000),
            FakeStarlarkProfileReader.flame().nodes());

    onEdt(
        () -> {
          graph.setSlice(slice);
          return null;
        });

    assertThat(onEdt(graph::loadedNoticeForTest))
        .contains("Showing 2 of 10")
        .contains("8 are outside the current read limit");
  }

  @Test
  void nodeHoverImmediatelyShowsCompactCpuDetails() throws Exception {
    StarlarkProfileReader.FlameNode node =
        node(
            7,
            OptionalLong.empty(),
            0,
            "compile_rules",
            2_500_000,
            StarlarkProfileReader.SourceLocation.file("rules/compile.bzl"));
    StarlarkFlameGraph graph = onEdt(StarlarkFlameGraph::new);

    onEdt(
        () -> {
          graph.setSize(700, 180);
          graph.setSlice(
              new StarlarkProfileReader.FlameSlice(
                  OptionalLong.empty(), 1, 0, OptionalLong.of(2_500_000), List.of(node)));
          BufferedImage image = new BufferedImage(700, 180, BufferedImage.TYPE_INT_ARGB);
          graph.paint(image.createGraphics());
          StarlarkFlameGraph.NodeCell cell = graph.layoutForTest(700).nodes().getFirst();
          int x = (int) Math.round(cell.bounds().getCenterX());
          int y = (int) Math.round(cell.bounds().getCenterY());
          MouseEvent hover =
              new MouseEvent(
                  graph,
                  MouseEvent.MOUSE_MOVED,
                  System.currentTimeMillis(),
                  0,
                  x,
                  y,
                  0,
                  false,
                  MouseEvent.NOBUTTON);

          graph.dispatchEvent(hover);

          assertThat(graph.hoverToolTipVisibleForTest()).isTrue();
          assertThat(graph.hoveredNodeIdForTest()).isEqualTo(7);
          assertThat(graph.hoverToolTipTextForTest())
              .contains("compile_rules")
              .contains("cumulative 2.50 s")
              .contains("self 2.50 s")
              .doesNotContain("rules/compile.bzl");
          assertThat(graph.getToolTipText(hover)).isEqualTo(graph.hoverToolTipTextForTest());
          return null;
        });
  }

  @Test
  void hoverDetailsStayInsideAndCloseWhenAScrolledViewportMoves() throws Exception {
    ArrayList<StarlarkProfileReader.FlameNode> nodes = new ArrayList<>();
    for (int depth = 0; depth < 9; depth++) {
      nodes.add(
          node(
              depth + 1,
              depth == 0 ? OptionalLong.empty() : OptionalLong.of(depth),
              depth,
              "function_" + depth,
              2_500_000,
              StarlarkProfileReader.SourceLocation.file("rules/compile.bzl")));
    }
    StarlarkFlameGraph graph = onEdt(StarlarkFlameGraph::new);

    onEdt(
        () -> {
          graph.setSize(700, 340);
          graph.setSlice(
              new StarlarkProfileReader.FlameSlice(
                  OptionalLong.empty(), nodes.size(), 0, OptionalLong.of(2_500_000), nodes));
          JViewport viewport = new JViewport();
          viewport.setExtentSize(new Dimension(240, 120));
          viewport.setView(graph);
          viewport.setViewPosition(new Point(420, 170));
          BufferedImage image = new BufferedImage(700, 340, BufferedImage.TYPE_INT_ARGB);
          graph.paint(image.createGraphics());
          StarlarkFlameGraph.NodeCell cell = graph.layoutForTest(700).nodes().get(5);
          MouseEvent hover =
              new MouseEvent(
                  graph,
                  MouseEvent.MOUSE_MOVED,
                  System.currentTimeMillis(),
                  0,
                  640,
                  (int) Math.round(cell.bounds().getCenterY()),
                  0,
                  false,
                  MouseEvent.NOBUTTON);

          graph.dispatchEvent(hover);

          Rectangle visible = graph.getVisibleRect();
          assertThat(graph.hoverToolTipVisibleForTest()).isTrue();
          assertThat(visible.contains(graph.hoverToolTipBoundsForTest())).isTrue();

          viewport.setViewPosition(new Point(300, 100));
          assertThat(graph.hoverToolTipVisibleForTest()).isFalse();
          return null;
        });
  }

  @Test
  void keyboardNavigationSelectsContextsAndOffersTheMouseActions() throws Exception {
    StarlarkProfileReader.SourceLocation source =
        StarlarkProfileReader.SourceLocation.file("rules/compile.bzl");
    StarlarkProfileReader.FlameNode root = node(1, OptionalLong.empty(), 0, "root", 2_000, source);
    StarlarkProfileReader.FlameNode child = node(2, OptionalLong.of(1), 1, "child", 1_000, source);
    AtomicReference<Long> focused = new AtomicReference<>();
    AtomicReference<StarlarkProfileReader.SourceLocation> opened = new AtomicReference<>();
    StarlarkFlameGraph graph = onEdt(StarlarkFlameGraph::new);

    onEdt(
        () -> {
          graph.setSize(700, 180);
          graph.onFocus(focused::set);
          graph.onOpenSource(opened::set);
          graph.setSlice(
              new StarlarkProfileReader.FlameSlice(
                  OptionalLong.empty(), 2, 0, OptionalLong.of(2_000), List.of(root, child)));

          assertThat(graph.isFocusable()).isTrue();
          assertThat(graph.getBorder()).isNotNull();
          assertThat(graph.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS))
              .contains(AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, 0));
          assertThat(graph.getAccessibleContext().getAccessibleName())
              .isEqualTo("Profile flame graph");
          assertThat(graph.getAccessibleContext().getAccessibleDescription())
              .contains("Showing 2 call contexts")
              .contains("arrow keys");

          invokeKey(graph, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
          assertThat(graph.selectedNodeIdForTest()).isEqualTo(1);
          invokeKey(graph, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
          assertThat(graph.selectedNodeIdForTest()).isEqualTo(2);
          invokeKey(graph, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
          invokeKey(graph, KeyStroke.getKeyStroke(KeyEvent.VK_O, 0));
          invokeKey(graph, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
          assertThat(graph.selectedNodeIdForTest()).isEqualTo(-1);
          return null;
        });

    assertThat(focused).hasValue(2L);
    assertThat(opened).hasValue(source);
  }

  private static StarlarkProfileReader.FlameNode node(
      long id,
      OptionalLong parent,
      int depth,
      String name,
      long cpu,
      StarlarkProfileReader.SourceLocation source) {
    return new StarlarkProfileReader.FlameNode(
        id,
        parent,
        depth,
        name,
        Optional.of(source),
        OptionalLong.of(cpu),
        OptionalLong.of(cpu),
        OptionalLong.of(1));
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable problem) {
            failure.set(problem);
          }
        });
    if (failure.get() instanceof Exception exception) {
      throw exception;
    }
    if (failure.get() instanceof Error error) {
      throw error;
    }
    return value.get();
  }

  private static void invokeKey(JComponent component, KeyStroke stroke) {
    Object key = component.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
    assertThat(key).as("action bound to %s", stroke).isNotNull();
    Action action = component.getActionMap().get(key);
    assertThat(action).as("action installed for %s", stroke).isNotNull();
    action.actionPerformed(
        new ActionEvent(component, ActionEvent.ACTION_PERFORMED, String.valueOf(key)));
  }
}
