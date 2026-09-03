package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where nodes go, and the two properties that matter more than tidiness.
 *
 * <p>Every dependency arrow must point forward, and the same graph laid out twice must land in the
 * same place. A drawing that rearranges itself when nothing changed makes a user think something
 * did.
 */
final class GraphLayoutTest {

  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  /** A diamond: 0 feeds 1 and 2, both feed 3. */
  private static GraphExtract.Result diamond() {
    CsrGraph forward =
        CsrBuilder.build(
            4,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(0, 2);
              visitor.edge(1, 3);
              visitor.edge(2, 3);
            });
    return GraphExtract.whole(forward, 100, 100);
  }

  @Test
  @DisplayName("every dependency arrow points forward, including the long way round")
  void layeredKeepsEdgesForward() {
    GraphExtract.Result extract = diamond();

    GraphLayout.Result layout = GraphLayout.layered(extract, RUNNING);

    // Longest-path layering, so node 3 sits after BOTH its predecessors.
    // Shortest-path layering would put it one after node 1 and leave the
    // edge from node 2 pointing backwards.
    for (GraphExtract.Edge edge : extract.edges()) {
      int from = extract.nodes().indexOf(edge.from());
      int to = extract.nodes().indexOf(edge.to());
      assertThat(layout.xAt(to))
          .as("edge %d -> %d must point right", edge.from(), edge.to())
          .isGreaterThan(layout.xAt(from));
    }
  }

  @Test
  @DisplayName("a chain lays out one node per layer")
  void chainsAreOnePerLayer() {
    CsrGraph chain =
        CsrBuilder.build(
            5,
            visitor -> {
              for (int i = 0; i + 1 < 5; i++) {
                visitor.edge(i, i + 1);
              }
            });

    GraphLayout.Result layout = GraphLayout.layered(GraphExtract.whole(chain, 100, 100), RUNNING);

    for (int i = 1; i < layout.size(); i++) {
      assertThat(layout.xAt(i)).isGreaterThan(layout.xAt(i - 1));
    }
  }

  @Test
  @DisplayName("the same graph laid out twice lands in exactly the same place")
  void layoutsAreDeterministic() {
    GraphLayout.Result first = GraphLayout.layered(diamond(), RUNNING);
    GraphLayout.Result second = GraphLayout.layered(diamond(), RUNNING);

    // Plan 13.7: deterministic stable positioning. No seeds, no clock, no
    // iteration-order dependence.
    assertThat(second.size()).isEqualTo(first.size());
    for (int i = 0; i < first.size(); i++) {
      assertThat(second.xAt(i)).as("x[%d]", i).isEqualTo(first.xAt(i));
      assertThat(second.yAt(i)).as("y[%d]", i).isEqualTo(first.yAt(i));
    }
  }

  @Test
  @DisplayName("the hierarchy centres a parent over non-overlapping child subtrees")
  void hierarchyCentresParentsOverChildren() {
    GraphLayout.Result layout = GraphLayout.hierarchy(diamond(), RUNNING);

    assertThat(layout.kind()).isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(layout.size()).isEqualTo(4);
    assertThat(layout.parentAt(0)).isEqualTo(-1);
    assertThat(layout.parentAt(1)).isEqualTo(0);
    assertThat(layout.parentAt(2)).isEqualTo(0);
    // Node 3 is shared. First deterministic discovery owns it; the other
    // real edge remains a cross-link rather than duplicating the node.
    assertThat(layout.parentAt(3)).isEqualTo(1);
    assertThat(layout.xAt(1)).isNotEqualTo(layout.xAt(2));
    assertThat(layout.xAt(0)).isEqualTo((layout.xAt(1) + layout.xAt(2)) / 2.0);
    assertThat(layout.yAt(1)).isGreaterThan(layout.yAt(0));
    assertThat(layout.yAt(3)).isGreaterThan(layout.yAt(1));
  }

  @Test
  @DisplayName("a rooted dependency hierarchy starts at the selected consumer")
  void dependencyHierarchyStartsAtTheSelection() {
    CsrGraph forward =
        CsrBuilder.build(
            4,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 2);
              visitor.edge(2, 3);
            });
    GraphExtract.Result dependencies =
        GraphExtract.dependencies(CsrBuilder.reverse(forward), 3, 8, 100);

    GraphLayout.Result layout = GraphLayout.hierarchy(dependencies, RUNNING);

    assertThat(layout.nodes().getFirst()).isEqualTo(3);
    assertThat(layout.parentAt(0)).isEqualTo(-1);
    for (int position = 1; position < layout.size(); position++) {
      assertThat(layout.yAt(position)).isGreaterThan(layout.yAt(position - 1));
    }
  }

  @Test
  @DisplayName("reverse-dependency and neighbourhood hierarchies branch from the selection")
  void rootedHierarchyDirectionMatchesTheScope() {
    CsrGraph forward =
        CsrBuilder.build(
            3,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 2);
            });

    GraphLayout.Result dependents =
        GraphLayout.hierarchy(GraphExtract.dependents(forward, 0, 8, 100), RUNNING);
    assertThat(dependents.nodes()).containsExactly(0, 1, 2);
    assertThat(dependents.parentAt(1)).isEqualTo(0);
    assertThat(dependents.parentAt(2)).isEqualTo(1);

    GraphLayout.Result neighbourhood =
        GraphLayout.hierarchy(
            GraphExtract.neighbourhood(forward, CsrBuilder.reverse(forward), 1, 8, 100), RUNNING);
    assertThat(neighbourhood.nodes().getFirst()).isEqualTo(1);
    assertThat(neighbourhood.parentAt(1)).isEqualTo(0);
    assertThat(neighbourhood.parentAt(2)).isEqualTo(0);
  }

  @Test
  @DisplayName("cycles, disconnected components and isolated nodes all get a stable place")
  void hierarchyHandlesCyclesAndDisconnectedComponents() {
    CsrGraph graph =
        CsrBuilder.build(
            6,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(1, 2);
              visitor.edge(2, 0);
              visitor.edge(3, 4);
            });
    GraphExtract.Result extract = GraphExtract.whole(graph, 100, 100);

    GraphLayout.Result first = GraphLayout.hierarchy(extract, RUNNING);
    GraphLayout.Result second = GraphLayout.hierarchy(extract, RUNNING);

    assertThat(first.size()).isEqualTo(6);
    int roots = 0;
    Set<String> coordinates = new HashSet<>();
    for (int position = 0; position < first.size(); position++) {
      if (first.parentAt(position) < 0) {
        roots++;
      }
      coordinates.add(first.xAt(position) + ":" + first.yAt(position));
      assertThat(second.xAt(position)).isEqualTo(first.xAt(position));
      assertThat(second.yAt(position)).isEqualTo(first.yAt(position));
      assertThat(second.parentAt(position)).isEqualTo(first.parentAt(position));
    }
    assertThat(roots).isEqualTo(3);
    assertThat(coordinates).hasSize(6);
  }

  @Test
  @DisplayName("fifty thousand isolated roots still fit inside the supported world scale")
  void isolatedHierarchyDoesNotSpendBlankSlotsBetweenRoots() {
    int count = 50_000;
    List<Integer> nodes = IntStream.range(0, count).boxed().toList();
    GraphExtract.Result isolated =
        new GraphExtract.Result(GraphExtract.Mode.WHOLE, nodes, List.of(), count, 0, false, count);

    GraphLayout.Result layout = GraphLayout.hierarchy(isolated, RUNNING);

    double[] bounds = layout.bounds().orElseThrow();
    assertThat(layout.size()).isEqualTo(count);
    // GraphTransform's minimum scale is 1e-4; below 5M world units this
    // remains under 500 px and can fit in a 600px canvas with margins.
    assertThat(bounds[2] - bounds[0]).isLessThan(5_000_000);
  }

  @Test
  @DisplayName("a cancelled hierarchy returns no partial forest")
  void hierarchyCancellationIsClean() {
    AtomicBoolean cancelled = new AtomicBoolean(true);

    GraphLayout.Result layout = GraphLayout.hierarchy(diamond(), cancelled);

    assertThat(layout.cancelled()).isTrue();
    assertThat(layout.size()).isZero();
  }

  @Test
  @DisplayName("a radial layout puts the centre at the origin and the rest around it")
  void radialRingsByDistance() {
    CsrGraph star =
        CsrBuilder.build(
            5,
            visitor -> {
              visitor.edge(0, 1);
              visitor.edge(0, 2);
              visitor.edge(1, 3);
              visitor.edge(2, 4);
            });

    GraphLayout.Result layout = GraphLayout.radial(GraphExtract.whole(star, 100, 100), RUNNING);

    assertThat(layout.xAt(0)).isZero();
    assertThat(layout.yAt(0)).isZero();
    // Node 3 is two hops out and must sit further from the centre than
    // node 1, which is one.
    double near = Math.hypot(layout.xAt(1), layout.yAt(1));
    double far = Math.hypot(layout.xAt(3), layout.yAt(3));
    assertThat(far).isGreaterThan(near);
  }

  @Test
  @DisplayName("a linear layout follows the path's order, not the node ids")
  void linearFollowsThePath() {
    CsrGraph graph = CsrBuilder.build(10, visitor -> visitor.edge(0, 9));
    GraphExtract.Result path =
        GraphExtract.path(graph, List.of(7, 2, 9), GraphExtract.Mode.CRITICAL_PATH);

    GraphLayout.Result layout = GraphLayout.linear(path, RUNNING);

    // A critical path drawn in node-id order would be a scatter of dots.
    assertThat(layout.xAt(0)).isLessThan(layout.xAt(1));
    assertThat(layout.xAt(1)).isLessThan(layout.xAt(2));
    for (int i = 0; i < layout.size(); i++) {
      assertThat(layout.yAt(i)).isZero();
    }
  }

  @Test
  @DisplayName("a grid is square-ish and fills row by row")
  void gridFillsRows() {
    CsrGraph nine = CsrBuilder.build(9, visitor -> {});

    GraphLayout.Result layout = GraphLayout.grid(GraphExtract.whole(nine, 100, 100), RUNNING);

    // Three columns for nine nodes.
    assertThat(layout.xAt(0)).isZero();
    assertThat(layout.yAt(0)).isZero();
    assertThat(layout.yAt(3)).isGreaterThan(layout.yAt(2));
    assertThat(layout.xAt(3)).isZero();
  }

  @Test
  @DisplayName("a cancelled layout returns nothing rather than a half-placement")
  void cancellationIsClean() {
    CsrGraph big =
        CsrBuilder.build(
            5_000,
            visitor -> {
              for (int i = 0; i + 1 < 5_000; i++) {
                visitor.edge(i, i + 1);
              }
            });
    AtomicBoolean cancelled = new AtomicBoolean(true);

    GraphLayout.Result layout =
        GraphLayout.layered(GraphExtract.whole(big, 100_000, 100_000), cancelled);

    // Half a layout drawn is worse than none: it looks like an answer.
    assertThat(layout.cancelled()).isTrue();
    assertThat(layout.nodes()).isEmpty();
    assertThat(layout.bounds()).isEmpty();
  }

  @Test
  @DisplayName("every mode has a sensible default layout")
  void modesHaveDefaults() {
    assertThat(GraphLayout.defaultFor(GraphExtract.Mode.NEIGHBOURHOOD))
        .isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(GraphLayout.defaultFor(GraphExtract.Mode.CRITICAL_PATH))
        .isEqualTo(GraphLayout.Kind.LINEAR);
    assertThat(GraphLayout.defaultFor(GraphExtract.Mode.DEPENDENCIES))
        .isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(GraphLayout.defaultFor(GraphExtract.Mode.DEPENDENTS))
        .isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(GraphLayout.defaultFor(GraphExtract.Mode.WHOLE))
        .isEqualTo(GraphLayout.Kind.HIERARCHY);
    assertThat(GraphLayout.defaultFor(GraphExtract.Mode.CLUSTERS)).isEqualTo(GraphLayout.Kind.GRID);
    // And every mode has one, so a new display mode cannot arrive without
    // somebody deciding how it is drawn.
    for (GraphExtract.Mode mode : GraphExtract.Mode.values()) {
      assertThat(GraphLayout.defaultFor(mode)).as("%s", mode).isNotNull();
    }
  }

  @Test
  @DisplayName("an empty extraction lays out to nothing without failing")
  void emptyExtractions() {
    GraphExtract.Result none =
        GraphExtract.path(CsrBuilder.build(1, v -> {}), List.of(), GraphExtract.Mode.PATH);

    for (GraphLayout.Kind kind : GraphLayout.Kind.values()) {
      GraphLayout.Result layout = GraphLayout.run(kind, none, RUNNING);
      assertThat(layout.size()).as("%s", kind).isZero();
      assertThat(layout.cancelled()).isFalse();
    }
  }

  @Test
  @DisplayName("the bounding box covers every placed node")
  void boundsCoverEverything() {
    GraphLayout.Result layout = GraphLayout.layered(diamond(), RUNNING);

    double[] box = layout.bounds().orElseThrow();
    for (int i = 0; i < layout.size(); i++) {
      assertThat(layout.xAt(i)).isBetween(box[0], box[2]);
      assertThat(layout.yAt(i)).isBetween(box[1], box[3]);
    }
  }

  @Test
  @DisplayName("the coordinates cannot be reached in bulk, so they cannot be moved")
  void coordinatesAreNotHandedOut() {
    // The audit's reason for making Result a class: a public double[]
    // accessor either copies fifty thousand doubles for a caller who wanted
    // one, or lets that caller move a node.
    for (Method method : GraphLayout.Result.class.getMethods()) {
      if (method.getDeclaringClass() != GraphLayout.Result.class) {
        continue;
      }
      assertThat(method.getReturnType()).as("%s", method.getName()).isNotEqualTo(double[].class);
    }
  }
}
