package com.holtherndon.bazelviz.ui.starlark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class StarlarkCallGraphTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  void laysOutCallersAboveSharedCalleesWithoutDuplicatingFunctions() {
    StarlarkProfileReader.DirectedCallGraph graph =
        graph(
            List.of(node(1, "root"), node(2, "left"), node(3, "right"), node(4, "shared")),
            List.of(edge(1, 2), edge(1, 3), edge(2, 4), edge(3, 4)));

    StarlarkCallGraphLayout.Layout layout = StarlarkCallGraphLayout.layout(graph);
    Map<Long, StarlarkCallGraphLayout.NodeBox> boxes =
        layout.nodes().stream()
            .collect(Collectors.toMap(box -> box.node().functionId(), Function.identity()));

    assertThat(layout.nodes()).hasSize(4);
    assertThat(boxes.get(1L).centerY()).isLessThan(boxes.get(2L).centerY());
    assertThat(boxes.get(1L).centerY()).isLessThan(boxes.get(3L).centerY());
    assertThat(boxes.get(2L).centerY()).isLessThan(boxes.get(4L).centerY());
    assertThat(boxes.get(3L).centerY()).isLessThan(boxes.get(4L).centerY());
    assertThat(layout.edges()).hasSize(4);
    assertThat(layout.bounds()[2]).isGreaterThan(layout.bounds()[0]);
    assertThat(layout.bounds()[3]).isGreaterThan(layout.bounds()[1]);
  }

  @Test
  void cyclicFunctionsRemainFiniteAndDistinct() {
    StarlarkCallGraphLayout.Layout layout =
        StarlarkCallGraphLayout.layout(
            graph(List.of(node(1, "one"), node(2, "two")), List.of(edge(1, 2), edge(2, 1))));

    assertThat(layout.nodes()).hasSize(2);
    assertThat(layout.nodes().get(0).bounds()).isNotEqualTo(layout.nodes().get(1).bounds());
    assertThat(layout.nodes())
        .allSatisfy(
            box -> {
              assertThat(box.centerX()).isFinite();
              assertThat(box.centerY()).isFinite();
            });
  }

  @Test
  void defaultsToSelfCpuSizingAndSupportsOtherWeightChoices() {
    List<StarlarkProfileReader.CallGraphNode> nodes =
        List.of(node(1, "self_hot", 9_000, 9_100), node(2, "cumulative_hot", 100, 10_000));
    StarlarkProfileReader.DirectedCallGraph graph = graph(nodes, List.of(edge(1, 2)));

    StarlarkCallGraphLayout.Layout self = StarlarkCallGraphLayout.layout(graph);
    StarlarkCallGraphLayout.Layout cumulative =
        StarlarkCallGraphLayout.layout(graph, StarlarkCallGraphLayout.NodeWeight.CUMULATIVE_CPU);
    StarlarkCallGraphLayout.Layout uniform =
        StarlarkCallGraphLayout.layout(graph, StarlarkCallGraphLayout.NodeWeight.UNIFORM);

    assertThat(self.nodeWeight()).isEqualTo(StarlarkCallGraphLayout.NodeWeight.SELF_CPU);
    assertThat(self.nodes().get(0).width()).isGreaterThan(self.nodes().get(1).width());
    assertThat(cumulative.nodes().get(1).width()).isGreaterThan(cumulative.nodes().get(0).width());
    assertThat(uniform.nodes().get(0).width()).isEqualTo(uniform.nodes().get(1).width());
    assertThat(uniform.nodes().get(0).height()).isEqualTo(uniform.nodes().get(1).height());
  }

  @Test
  void anUnknownWeightDoesNotLookLikeMeasuredZero() {
    StarlarkProfileReader.CallGraphNode unknown =
        new StarlarkProfileReader.CallGraphNode(
            1,
            "unknown",
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.of(1_000),
            OptionalLong.empty(),
            OptionalLong.of(1),
            OptionalLong.of(1));
    StarlarkProfileReader.CallGraphNode zero = node(2, "zero", 0, 1_000);

    StarlarkCallGraphLayout.Layout layout =
        StarlarkCallGraphLayout.layout(graph(List.of(unknown, zero), List.of()));

    assertThat(layout.nodes().get(0).width()).isNotEqualTo(layout.nodes().get(1).width());
    assertThat(layout.nodes().get(0).height()).isNotEqualTo(layout.nodes().get(1).height());
  }

  @Test
  void wideRootRanksWrapSoEveryDefaultViewNodeCanRenderItsName() throws Exception {
    List<StarlarkProfileReader.CallGraphNode> nodes = new ArrayList<>();
    for (int id = 1; id <= 80; id++) {
      nodes.add(node(id, "function_" + id, 1_000, 1_000));
    }
    StarlarkProfileReader.DirectedCallGraph graph = graph(nodes, List.of());

    SwingUtilities.invokeAndWait(
        () -> {
          StarlarkCallGraph canvas = new StarlarkCallGraph();
          canvas.setSize(1_000, 650);
          canvas.setLayoutModel(StarlarkCallGraphLayout.layout(graph));

          assertThat(
                  canvas.layoutForTest().nodes().stream()
                      .map(StarlarkCallGraphLayout.NodeBox::centerY)
                      .distinct()
                      .count())
              .isGreaterThan(1);
          assertThat(canvas.layoutForTest().nodes())
              .allSatisfy(
                  box -> assertThat(canvas.showsNameForTest(box.node().functionId())).isTrue());
        });
  }

  @Test
  void selectingAPaintedNodeReportsTheExactFunctionAndPaintingIsDataFree() throws Exception {
    StarlarkProfileReader.DirectedCallGraph graph =
        graph(List.of(node(1, "caller"), node(2, "callee")), List.of(edge(1, 2)));
    AtomicReference<StarlarkProfileReader.CallGraphNode> selected = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          StarlarkCallGraph canvas = new StarlarkCallGraph();
          canvas.setSize(900, 600);
          canvas.onSelection(selected::set);
          canvas.setLayoutModel(StarlarkCallGraphLayout.layout(graph));
          StarlarkCallGraphLayout.NodeBox box = canvas.layoutForTest().nodes().get(0);
          Rectangle2D.Double original = canvas.boundsForTest(box.node().functionId());
          int x = (int) Math.round(canvas.transformForTest().screenX(box.centerX()));
          int y = (int) Math.round(canvas.transformForTest().screenY(box.centerY()));
          canvas.dispatchEvent(
              new MouseEvent(
                  canvas,
                  MouseEvent.MOUSE_PRESSED,
                  System.currentTimeMillis(),
                  0,
                  x,
                  y,
                  1,
                  false,
                  MouseEvent.BUTTON1));
          canvas.dispatchEvent(
              new MouseEvent(
                  canvas,
                  MouseEvent.MOUSE_DRAGGED,
                  System.currentTimeMillis(),
                  MouseEvent.BUTTON1_DOWN_MASK,
                  x + 80,
                  y + 45,
                  0,
                  false,
                  MouseEvent.NOBUTTON));
          canvas.dispatchEvent(
              new MouseEvent(
                  canvas,
                  MouseEvent.MOUSE_RELEASED,
                  System.currentTimeMillis(),
                  0,
                  x + 80,
                  y + 45,
                  1,
                  false,
                  MouseEvent.BUTTON1));
          BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB);
          canvas.paint(image.createGraphics());
          assertThat(canvas.selectedFunctionIdForTest()).isEqualTo(box.node().functionId());
          assertThat(canvas.movedNodeCountForTest()).isEqualTo(1);
          assertThat(canvas.boundsForTest(box.node().functionId()).x).isGreaterThan(original.x);
          assertThat(canvas.boundsForTest(box.node().functionId()).y).isGreaterThan(original.y);
          canvas.resetMovedNodes();
          assertThat(canvas.movedNodeCountForTest()).isZero();
          assertThat(canvas.boundsForTest(box.node().functionId())).isEqualTo(original);
        });

    assertThat(selected.get()).isNotNull();
    assertThat(selected.get().function()).isEqualTo("caller");
  }

  @Test
  void nodeTooltipAppearsOnTheFirstHoverWithoutSourceNoise() throws Exception {
    StarlarkProfileReader.CallGraphNode node =
        new StarlarkProfileReader.CallGraphNode(
            1,
            "compile_rules",
            Optional.of(
                new StarlarkProfileReader.SourceLocation("rules/compile.bzl", OptionalInt.of(42))),
            OptionalLong.of(1_250_000),
            OptionalLong.of(2_500_000),
            OptionalLong.of(125),
            OptionalLong.of(250),
            OptionalLong.of(1));

    SwingUtilities.invokeAndWait(
        () -> {
          StarlarkCallGraph canvas = new StarlarkCallGraph();
          canvas.setSize(900, 600);
          canvas.setLayoutModel(StarlarkCallGraphLayout.layout(graph(List.of(node), List.of())));
          StarlarkCallGraphLayout.NodeBox box = canvas.layoutForTest().nodes().get(0);
          int x = (int) Math.round(canvas.transformForTest().screenX(box.centerX()));
          int y = (int) Math.round(canvas.transformForTest().screenY(box.centerY()));
          MouseEvent hover =
              new MouseEvent(
                  canvas,
                  MouseEvent.MOUSE_MOVED,
                  System.currentTimeMillis(),
                  0,
                  x,
                  y,
                  0,
                  false,
                  MouseEvent.NOBUTTON);

          canvas.dispatchEvent(hover);

          assertThat(canvas.nodeToolTipVisibleForTest()).isTrue();
          assertThat(canvas.nodeToolTipTextForTest())
              .contains("compile_rules")
              .contains("self 1.25 s")
              .contains("cumulative 2.50 s")
              .doesNotContain("rules/compile.bzl")
              .doesNotContain(":42");
          assertThat(canvas.getToolTipText(hover)).isEqualTo(canvas.nodeToolTipTextForTest());
        });
  }

  @Test
  void rejectsAnArrowWhoseEndpointIsOutsideTheVisibleProjection() {
    assertThatThrownBy(
            () ->
                new StarlarkProfileReader.DirectedCallGraph(
                    1,
                    0,
                    1,
                    0,
                    OptionalLong.of(1_000),
                    List.of(node(1, "visible")),
                    List.of(edge(1, 2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint");
  }

  private static StarlarkProfileReader.DirectedCallGraph graph(
      List<StarlarkProfileReader.CallGraphNode> nodes,
      List<StarlarkProfileReader.DirectedCallEdge> edges) {
    return new StarlarkProfileReader.DirectedCallGraph(
        nodes.size(), 0, edges.size(), 0, OptionalLong.of(10_000), nodes, edges);
  }

  private static StarlarkProfileReader.CallGraphNode node(long id, String function) {
    return node(id, function, 1_000, 5_000);
  }

  private static StarlarkProfileReader.CallGraphNode node(
      long id, String function, long selfCpuMicros, long cumulativeCpuMicros) {
    return new StarlarkProfileReader.CallGraphNode(
        id,
        function,
        Optional.of(StarlarkProfileReader.SourceLocation.file("rules/" + function + ".bzl")),
        OptionalLong.of(selfCpuMicros),
        OptionalLong.of(cumulativeCpuMicros),
        OptionalLong.of(1),
        OptionalLong.of(5),
        OptionalLong.of(1));
  }

  private static StarlarkProfileReader.DirectedCallEdge edge(long caller, long callee) {
    return new StarlarkProfileReader.DirectedCallEdge(
        caller, callee, OptionalLong.of(1_000), OptionalLong.of(1));
  }
}
