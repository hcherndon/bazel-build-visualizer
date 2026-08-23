package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where nodes go, and the two properties that matter more than tidiness.
 *
 * <p>Every dependency arrow must point forward, and the same graph laid out
 * twice must land in the same place. A drawing that rearranges itself when
 * nothing changed makes a user think something did.
 */
final class GraphLayoutTest {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** A diamond: 0 feeds 1 and 2, both feed 3. */
    private static GraphExtract.Result diamond() {
        CsrGraph forward = CsrBuilder.build(4, visitor -> {
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
        CsrGraph chain = CsrBuilder.build(5, visitor -> {
            for (int i = 0; i + 1 < 5; i++) {
                visitor.edge(i, i + 1);
            }
        });

        GraphLayout.Result layout =
                GraphLayout.layered(GraphExtract.whole(chain, 100, 100), RUNNING);

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
    @DisplayName("a radial layout puts the centre at the origin and the rest around it")
    void radialRingsByDistance() {
        CsrGraph star = CsrBuilder.build(5, visitor -> {
            visitor.edge(0, 1);
            visitor.edge(0, 2);
            visitor.edge(1, 3);
            visitor.edge(2, 4);
        });

        GraphLayout.Result layout =
                GraphLayout.radial(GraphExtract.whole(star, 100, 100), RUNNING);

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
        CsrGraph nine = CsrBuilder.build(9, visitor -> { });

        GraphLayout.Result layout =
                GraphLayout.grid(GraphExtract.whole(nine, 100, 100), RUNNING);

        // Three columns for nine nodes.
        assertThat(layout.xAt(0)).isZero();
        assertThat(layout.yAt(0)).isZero();
        assertThat(layout.yAt(3)).isGreaterThan(layout.yAt(2));
        assertThat(layout.xAt(3)).isZero();
    }

    @Test
    @DisplayName("a cancelled layout returns nothing rather than a half-placement")
    void cancellationIsClean() {
        CsrGraph big = CsrBuilder.build(5_000, visitor -> {
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
                .isEqualTo(GraphLayout.Kind.RADIAL);
        assertThat(GraphLayout.defaultFor(GraphExtract.Mode.CRITICAL_PATH))
                .isEqualTo(GraphLayout.Kind.LINEAR);
        assertThat(GraphLayout.defaultFor(GraphExtract.Mode.DEPENDENCIES))
                .isEqualTo(GraphLayout.Kind.LAYERED);
        assertThat(GraphLayout.defaultFor(GraphExtract.Mode.CLUSTERS))
                .isEqualTo(GraphLayout.Kind.GRID);
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
                GraphExtract.path(CsrBuilder.build(1, v -> { }), List.of(), GraphExtract.Mode.PATH);

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
        for (java.lang.reflect.Method method : GraphLayout.Result.class.getMethods()) {
            if (method.getDeclaringClass() != GraphLayout.Result.class) {
                continue;
            }
            assertThat(method.getReturnType())
                    .as("%s", method.getName())
                    .isNotEqualTo(double[].class);
        }
    }
}
