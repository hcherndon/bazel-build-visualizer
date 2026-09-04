package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.ui.theme.AppTheme;
import com.holtherndon.bazelviz.ui.theme.Themes;
import java.awt.AWTKeyStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the canvas draws, and what it refuses to claim.
 *
 * <p>Painting is exercised against an offscreen image rather than a window, so these run headless —
 * which is also how the checks that matter are stated: about the model the canvas is handed, not
 * about pixels.
 */
final class GraphCanvasTest {

  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  private static CsrGraph chain(int nodes) {
    return CsrBuilder.build(
        nodes,
        visitor -> {
          for (int i = 0; i + 1 < nodes; i++) {
            visitor.edge(i, i + 1);
          }
        });
  }

  private static GraphLayoutService.Rendered rendered(int nodes) {
    GraphExtract.Result extract = GraphExtract.whole(chain(nodes), 1_000, 1_000);
    return new GraphLayoutService.Rendered(
        GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 1_000, 1_000),
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

  private static GraphModel hierarchyDiamond() {
    CsrGraph graph =
        CsrBuilder.build(
            4,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(0, 2);
              visitor.edge(1, 3);
              visitor.edge(2, 3);
            });
    GraphExtract.Result extract = GraphExtract.whole(graph, 100, 100);
    GraphLayout.Result layout = GraphLayout.hierarchy(extract, RUNNING);
    return GraphModel.of(
        new GraphLayoutService.Rendered(
            GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 100, 100),
            extract,
            layout,
            null,
            extract.describe()),
        new String[] {"Genrule — a", "Javac — b", "Javac — c", "Link — out"},
        new String[] {"//pkg:a", "//pkg:b", "//pkg:c", "//pkg:out"},
        allTimed(4));
  }

  private static GraphModel hierarchyWithManyCrossLinks(int branches) {
    int sink = branches + 1;
    CsrGraph graph =
        CsrBuilder.build(
            sink + 1,
            visitor -> {
              for (int child = 1; child <= branches; child++) {
                visitor.edge(0, child);
                visitor.edge(child, sink);
              }
            });
    GraphExtract.Result extract = GraphExtract.whole(graph, sink + 1, branches * 2);
    GraphLayout.Result layout = GraphLayout.hierarchy(extract, RUNNING);
    return GraphModel.of(
        new GraphLayoutService.Rendered(
            GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, sink + 1, branches * 2),
            extract,
            layout,
            null,
            extract.describe()),
        null,
        null,
        allTimed(sink + 1));
  }

  private static long[] allTimed(int nodes) {
    long[] durations = new long[nodes];
    for (int i = 0; i < nodes; i++) {
      durations[i] = (i + 1) * 1_000L;
    }
    return durations;
  }

  @Test
  @DisplayName("canvas background, labels and edges follow both light and dark themes")
  void graphPaletteFollowsTheTheme() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            Color previousBackground = null;
            for (AppTheme theme : new AppTheme[] {AppTheme.LIGHT, AppTheme.DARK}) {
              Themes.install(theme);
              GraphCanvas canvas = new GraphCanvas();
              Color background = canvas.getBackground();
              Color label = GraphColours.label();
              Color edge = GraphColours.edge();

              assertThat(background).isEqualTo(UIManager.getColor("Panel.background"));
              assertThat(contrast(label, background)).isGreaterThanOrEqualTo(4.5);
              assertThat(edge.getRed()).isEqualTo(label.getRed());
              assertThat(edge.getGreen()).isEqualTo(label.getGreen());
              assertThat(edge.getBlue()).isEqualTo(label.getBlue());
              assertThat(edge.getAlpha()).isEqualTo(0x40);
              if (previousBackground != null) {
                assertThat(background).isNotEqualTo(previousBackground);
              }
              previousBackground = background;
            }
          } finally {
            Themes.installDefault();
          }
        });
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
    Arrays.fill(none, GraphModel.UNKNOWN_DURATION);

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
  @DisplayName("an action name and its owning target are both visible without conflation")
  void targetOwnershipIsKeptWithTheActionName() {
    GraphModel model =
        GraphModel.of(
            rendered(3),
            new String[] {"Javac — Foo.class", "Link — app", null},
            new String[] {"//java:foo", "//app:app", "//unknown:owner"},
            allTimed(3));

    assertThat(model.displayLabelAt(0)).isEqualTo("Javac — Foo.class");
    assertThat(model.ownerLabelAt(0)).contains("//java:foo");
    assertThat(model.canvasLabelAt(0)).isEqualTo("Javac — Foo.class  ·  target //java:foo");
    assertThat(model.canvasLabelAt(2)).isEqualTo("(name not recorded)  ·  target //unknown:owner");

    // A configured-target node passes its label as both fields. It is one
    // target, not an action with an owner, so the text is not repeated.
    GraphModel targets =
        GraphModel.of(
            rendered(3),
            new String[] {"//java:foo", "//app:app", "//lib:x"},
            new String[] {"//java:foo", "//app:app", "//lib:x"},
            allTimed(3));
    assertThat(targets.canvasLabelAt(0)).isEqualTo("//java:foo");
    assertThat(targets.ownerLabelAt(0)).isEmpty();
  }

  @Test
  @DisplayName("far help describes configured-target nodes as targets, not actions")
  void farHelpUsesTheGraphNodeKind() {
    GraphExtract.Result extract = GraphExtract.whole(chain(3), 10, 10);
    GraphModel targets =
        GraphModel.of(
            new GraphLayoutService.Rendered(
                GraphLayoutService.Request.whole(GraphKind.CONFIGURED_TARGETS, 10, 10),
                extract,
                GraphLayout.hierarchy(extract, RUNNING),
                null,
                extract.describe("target")),
            new String[] {"//pkg:a", "//pkg:b", "//pkg:c"},
            new String[] {"//pkg:a", "//pkg:b", "//pkg:c"},
            allTimed(3));
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(400, 300);
    canvas.setModel(targets);
    canvas.zoomForTesting(0.01);

    assertThat(canvas.hiddenDetail())
        .isPresent()
        .get(InstanceOfAssertFactories.STRING)
        .contains("Select a target to see its label")
        .doesNotContain("action and target");
  }

  @Test
  @DisplayName("a cluster view is named from its clustering, not from node lookups")
  void clusterLabelsComeFromTheClustering() {
    String[] keys = {"//a", "//a", "//b", "//b", "//b"};
    GraphClustering.Result clustering =
        GraphClustering.cluster(chain(5), keys, GraphClustering.By.PACKAGE, 100, RUNNING);
    GraphExtract.Result extract = clustering.asExtract();
    GraphLayoutService.Rendered clustered =
        new GraphLayoutService.Rendered(
            GraphLayoutService.Request.clustered(
                GraphKind.DECLARED_ACTIONS, GraphClustering.By.PACKAGE),
            extract,
            GraphLayout.grid(extract, RUNNING),
            clustering,
            clustering.describe());

    // Deliberately passing session arrays that would produce nonsense if
    // they were consulted: cluster ordinals are not node indices.
    GraphModel model =
        GraphModel.of(clustered, new String[] {"WRONG", "ALSO WRONG"}, new long[] {99, 98});

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
      assertThat(model.layout().xAt(first[0][e])).isLessThan(model.layout().xAt(first[1][e]));
    }
  }

  @Test
  @DisplayName("the hierarchy keeps every edge but declutters shared links explicitly")
  void hierarchyCrossLinksAreExplicitAndSelectable() {
    GraphModel model = hierarchyDiamond();
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    canvas.setModel(model);

    assertThat(model.edgePositions()[0]).hasSize(4);
    assertThat(model.hierarchyEdgeCount()).isEqualTo(3);
    assertThat(model.crossLinkCount()).isEqualTo(1);
    assertThat(model.crossLinksTouching(Set.of(0))).isZero();
    assertThat(model.crossLinksTouching(Set.of(2))).isEqualTo(1);
    assertThat(model.crossLinksTouching(Set.of(2, 3))).isEqualTo(1);
    assertThat(canvas.edgeDisplay()).isEqualTo(GraphEdgeDisplay.DECLUTTERED);
    assertThat(canvas.hiddenCrossLinkCount()).isEqualTo(1);
    assertThat(canvas.hiddenDetail())
        .isPresent()
        .get(InstanceOfAssertFactories.STRING)
        .contains("1 additional dependency is hidden")
        .contains("All dependencies");
    paintOnce(canvas);
    assertThat(canvas.primaryEdgesDrawnForTesting()).isEqualTo(3);
    assertThat(canvas.crossLinksDrawnForTesting()).isZero();

    // Node 2 owns the diamond's non-primary 2→3 link. Selecting it brings
    // that exact link back without changing the layout or the model.
    canvas.select(2);
    assertThat(canvas.hiddenCrossLinkCount()).isZero();
    assertThat(canvas.hiddenDetail()).isEmpty();
    paintOnce(canvas);
    assertThat(canvas.primaryEdgesDrawnForTesting()).isEqualTo(3);
    assertThat(canvas.crossLinksDrawnForTesting()).isEqualTo(1);

    // A marquee selection must not reveal an unbounded union of links.
    canvas.selectPositionsForTesting(2, 3);
    assertThat(canvas.hiddenCrossLinkCount()).isEqualTo(1);
    paintOnce(canvas);
    assertThat(canvas.crossLinksDrawnForTesting()).isZero();

    canvas.select(-1);
    canvas.setEdgeDisplay(GraphEdgeDisplay.ALL);
    assertThat(canvas.hiddenCrossLinkCount()).isZero();
    assertThat(canvas.model().edgePositions()[0]).hasSize(4);
    paintOnce(canvas);
    assertThat(canvas.primaryEdgesDrawnForTesting()).isEqualTo(3);
    assertThat(canvas.crossLinksDrawnForTesting()).isEqualTo(1);
  }

  @Test
  @DisplayName("a reciprocal cycle contributes one branch and one closing cross-link")
  void reciprocalCycleDoesNotDuplicateTheTreeBranch() {
    CsrGraph graph =
        CsrBuilder.build(
            2,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 0);
            });
    GraphExtract.Result extract = GraphExtract.whole(graph, 10, 10);
    GraphModel model =
        GraphModel.of(
            new GraphLayoutService.Rendered(
                GraphLayoutService.Request.whole(GraphKind.DECLARED_ACTIONS, 10, 10),
                extract,
                GraphLayout.hierarchy(extract, RUNNING),
                null,
                extract.describe()),
            null,
            null,
            allTimed(2));

    assertThat(model.hierarchyEdgeCount()).isEqualTo(1);
    assertThat(model.crossLinkCount()).isEqualTo(1);
    assertThat(model.isHierarchyEdge(0)).isNotEqualTo(model.isHierarchyEdge(1));
  }

  @Test
  @DisplayName("dependency mode recognises reversed traversal branches in real edge direction")
  void dependencyHierarchyClassifiesProducerToConsumerEdges() {
    CsrGraph forward = chain(3);
    GraphExtract.Result extract = GraphExtract.dependencies(CsrBuilder.reverse(forward), 2, 3, 10);
    GraphModel model =
        GraphModel.of(
            new GraphLayoutService.Rendered(
                GraphLayoutService.Request.around(
                    GraphKind.DECLARED_ACTIONS, GraphExtract.Mode.DEPENDENCIES, 2, 3),
                extract,
                GraphLayout.hierarchy(extract, RUNNING),
                null,
                extract.describe()),
            null,
            null,
            allTimed(3));

    assertThat(model.hierarchyEdgeCount()).isEqualTo(2);
    assertThat(model.crossLinkCount()).isZero();
  }

  @Test
  @DisplayName("one high-degree selection remains bounded at hierarchy overview scale")
  void selectedCrossLinksStayBoundedAtFarZoom() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(160, 120);
    GraphModel model = hierarchyWithManyCrossLinks(1_000);
    canvas.setModel(model);
    canvas.select(model.size() - 1);

    paintOnce(canvas);

    int lineBudget = canvas.getWidth() * GraphCanvas.FAR_HIERARCHY_BRANCHES_PER_PIXEL;
    assertThat(canvas.crossLinksDrawnForTesting()).isLessThanOrEqualTo(lineBudget);
    assertThat(canvas.hiddenDetail())
        .isPresent()
        .get(InstanceOfAssertFactories.STRING)
        .contains("selected cross-links are not drawn individually")
        .contains("Zoom in to restore every selected link");
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
      assertThat(canvas.transform().screenX(model.layout().xAt(i))).isBetween(-1.0, 801.0);
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
  @DisplayName("the visible width of a zoomed hierarchy node remains clickable")
  void hierarchyCardHitAreaMatchesItsPaintedShape() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    canvas.setModel(hierarchyDiamond());
    canvas.zoomForTesting(4);

    int position = 0;
    int screenX = screenXOf(canvas, position);
    int screenY = screenYOf(canvas, position);
    int insideRightEdge =
        (int)
            Math.floor(
                0.9
                    * 1.3
                    * 9
                    * canvas.model().radiusScaleAt(position)
                    * canvas.transform().scale());

    assertThat(insideRightEdge).isGreaterThan(12);
    assertThat(canvas.positionAt(screenX + insideRightEdge, screenY)).hasValue(position);
  }

  @Test
  @DisplayName("selection reports graph node indices, not layout positions")
  void selectionSpeaksInNodeIds() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    GraphExtract.Result extract = GraphExtract.dependents(CsrBuilder.reverse(chain(10)), 9, 4, 100);
    canvas.setModel(
        GraphModel.of(
            new GraphLayoutService.Rendered(
                null, extract, GraphLayout.layered(extract, RUNNING), null, extract.describe()),
            null,
            null));

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
    BufferedImage image =
        new BufferedImage(
            Math.max(1, canvas.getWidth()),
            Math.max(1, canvas.getHeight()),
            BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      canvas.paint(g);
    } finally {
      g.dispose();
    }
  }

  private static void mouse(GraphCanvas canvas, int id, int x, int y) {
    canvas.dispatchEvent(
        new MouseEvent(
            canvas, id, System.currentTimeMillis(), 0, x, y, 1, false, MouseEvent.BUTTON1));
  }

  private static int screenXOf(GraphCanvas canvas, int position) {
    return (int) Math.round(canvas.transform().screenX(canvas.model().layout().xAt(position)));
  }

  private static int screenYOf(GraphCanvas canvas, int position) {
    return (int) Math.round(canvas.transform().screenY(canvas.model().layout().yAt(position)));
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
    assertThat(canvas.paintedLabelPositionsForTesting().size() + canvas.declutteredLabelCount())
        .isEqualTo(6);
    assertThat(canvas.hiddenDetail())
        .isPresent()
        .get(InstanceOfAssertFactories.STRING)
        .contains(canvas.declutteredLabelCount() + " node labels are hidden")
        .contains("Zoom in or select a node");
  }

  @Test
  @DisplayName("the declutter keeps the same labels every frame")
  void declutterIsDeterministic() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    canvas.setModel(longLabelled(6));

    paintOnce(canvas);
    List<Integer> first = canvas.paintedLabelPositionsForTesting();
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

    assertThat(canvas.paintedLabelPositionsForTesting().get(0)).isEqualTo(skippedPosition);
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

    FontMetrics metrics = canvas.getFontMetrics(canvas.labelFont());
    double radius = Math.max(2.5, 9 * canvas.transform().scale());
    GraphModel model = canvas.model();
    for (int i = 0; i < model.size(); i++) {
      double labelRight =
          canvas.transform().screenX(model.layout().xAt(i))
              + radius
              + 4
              + metrics.stringWidth(model.displayLabelAt(i));
      // Node geometry alone would push the last column's text off the
      // window; the label-aware fit must not.
      assertThat(labelRight).as("label %d ends on screen", i).isLessThanOrEqualTo(800);
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
        .get(InstanceOfAssertFactories.STRING)
        .contains("capped at half the window");
  }

  @Test
  @DisplayName("the graph exposes its state and core navigation without a pointer")
  void keyboardNavigationIsAccessible() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    canvas.setModel(modelOf(3, allTimed(3)));
    AtomicInteger focused = new AtomicInteger(-1);
    canvas.onFocusRequested(focused::set);

    assertThat(canvas.isFocusable()).isTrue();
    assertThat(canvas.getBorder()).isNotNull();
    assertThat(canvas.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS))
        .contains(AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, 0));
    assertThat(canvas.getAccessibleContext().getAccessibleName())
        .isEqualTo("Dependency graph canvas");
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .contains("Showing 3 graph nodes")
        .contains("arrow keys")
        .contains("Enter");

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
    assertThat(canvas.selectedPositions()).containsExactly(0);
    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
    assertThat(canvas.selectedPositions()).containsExactly(1);
    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
    assertThat(focused).hasValue(1);

    double beforeZoom = canvas.transform().scale();
    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK));
    assertThat(canvas.transform().scale()).isGreaterThan(beforeZoom);
    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
    assertThat(canvas.selectedPositions()).isEmpty();
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .contains("none is selected");
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

    mouse(canvas, MouseEvent.MOUSE_PRESSED, sx, sy);
    mouse(canvas, MouseEvent.MOUSE_DRAGGED, sx + 30, sy + 18);
    mouse(canvas, MouseEvent.MOUSE_RELEASED, sx + 30, sy + 18);

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
    assertThat(
            canvas
                .model()
                .index()
                .nearest(before.worldX(sx), before.worldY(sy), 12 / before.scale()))
        .hasValue(4);
  }

  @Test
  @DisplayName("the jitter inside an ordinary click does not become a drag")
  void clickJitterIsNotADrag() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    canvas.setModel(modelOf(10, allTimed(10)));
    int sx = screenXOf(canvas, 4);
    int sy = screenYOf(canvas, 4);

    mouse(canvas, MouseEvent.MOUSE_PRESSED, sx, sy);
    mouse(canvas, MouseEvent.MOUSE_DRAGGED, sx + 1, sy + 1);
    mouse(canvas, MouseEvent.MOUSE_RELEASED, sx + 1, sy + 1);

    // A one-pixel wobble is a click, not a rearrangement: no offset is
    // written, "Reset positions" stays unarmed, and the select stands.
    assertThat(canvas.hasDragOffsets()).isFalse();
    assertThat(canvas.selectedPositions()).containsExactly(4);
    assertThat(canvas.positionAt(sx, sy)).hasValue(4);
  }

  @Test
  @DisplayName("a press on empty canvas still pans")
  void emptyPressStillPans() {
    GraphCanvas canvas = new GraphCanvas();
    canvas.setSize(800, 600);
    canvas.setModel(modelOf(10, allTimed(10)));
    GraphTransform before = canvas.transform();

    mouse(canvas, MouseEvent.MOUSE_PRESSED, 780, 580);
    mouse(canvas, MouseEvent.MOUSE_DRAGGED, 700, 500);
    mouse(canvas, MouseEvent.MOUSE_RELEASED, 700, 500);

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
    mouse(canvas, MouseEvent.MOUSE_PRESSED, sx, sy);
    mouse(canvas, MouseEvent.MOUSE_DRAGGED, sx + 30, sy + 18);
    mouse(canvas, MouseEvent.MOUSE_RELEASED, sx + 30, sy + 18);
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
    mouse(canvas, MouseEvent.MOUSE_PRESSED, sx, sy);
    mouse(canvas, MouseEvent.MOUSE_DRAGGED, sx + 40, sy);
    mouse(canvas, MouseEvent.MOUSE_RELEASED, sx + 40, sy);
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

  private static double contrast(Color first, Color second) {
    double light = Math.max(luminance(first), luminance(second));
    double dark = Math.min(luminance(first), luminance(second));
    return (light + 0.05) / (dark + 0.05);
  }

  private static double luminance(Color color) {
    return 0.2126 * channel(color.getRed())
        + 0.7152 * channel(color.getGreen())
        + 0.0722 * channel(color.getBlue());
  }

  private static double channel(int value) {
    double normalized = value / 255.0;
    return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
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
