package com.holtherndon.bazelviz.analysis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Positions for a subgraph's nodes, in world coordinates.
 *
 * <h2>Four layouts, none of them force-directed</h2>
 *
 * <p>Plan 13.7 names layered for dependency subgraphs, radial for
 * neighbourhoods, linear for critical paths and grid for cluster summaries, and
 * ends "do not run force-directed layout on an unbounded action graph". There is
 * none here at all: every layout below is a single linear pass, so the answer to
 * "how long will this take" is the node count rather than a convergence
 * criterion.
 *
 * <h2>Linear, not merely non-iterative</h2>
 *
 * <p>Layering by repeated edge relaxation is easier to write and is O(V·E) in
 * the worst case; at the 50,000-node limit of {@link GraphExtract} that is not a
 * layout, it is a hang. Both traversals here build a local adjacency index once
 * and then run in O(V+E) — Kahn's algorithm for layering, a queue-based
 * breadth-first walk for rings.
 *
 * <h2>Deterministic</h2>
 *
 * <p>Plan 13.7 again: "deterministic stable positioning where possible". The
 * same subgraph laid out twice produces identical coordinates — no random seeds,
 * no hash iteration order, no clock. A graph that rearranged itself when nothing
 * changed could not be compared against yesterday's screenshot, and worse, would
 * make a user think something had.
 *
 * <h2>Cancellable, because plan 24 says so</h2>
 *
 * <p>Every layout checks a flag as it goes and returns {@link Result#cancelled}
 * rather than a partial placement. A user who changes the query while fifty
 * thousand nodes are being placed should not wait for the answer to a question
 * they have stopped asking, and should certainly not be shown half of it.
 */
public final class GraphLayout {

    private GraphLayout() {}

    /** Horizontal spacing between layers, in world units. */
    private static final double LAYER_GAP = 220;

    /** Vertical spacing between nodes within a layer. */
    private static final double NODE_GAP = 44;

    /** How often to look at the cancellation flag, in nodes. */
    private static final int CANCEL_CHECK_INTERVAL = 4_096;

    /**
     * A layered left-to-right placement: layer by longest path from a root.
     *
     * <p>Longest path rather than shortest, so every edge points strictly
     * rightwards. Shortest-path layering puts a node one layer after its
     * <em>earliest</em> predecessor, which leaves the edges from its later
     * predecessors pointing backwards — and a dependency arrow pointing the
     * wrong way is a worse drawing than an untidy one.
     *
     * <p>Kahn's algorithm, so it is one pass over nodes and one over edges. A
     * subgraph that is not a DAG — which the action graph should never be, but
     * a bad import could produce — leaves its cycle unprocessed rather than
     * looping; those nodes are placed in a final layer of their own.
     */
    public static Result layered(GraphExtract.Result extract, AtomicBoolean cancelled) {
        List<Integer> nodes = extract.nodes();
        if (nodes.isEmpty()) {
            return Result.empty(Kind.LAYERED);
        }
        Adjacency adjacency = Adjacency.directed(extract);
        int count = nodes.size();
        int[] layer = new int[count];
        int[] remaining = adjacency.inDegree.clone();

        int[] queue = new int[count];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < count; i++) {
            if (remaining[i] == 0) {
                queue[tail++] = i;
            }
        }
        int processed = 0;
        while (head < tail) {
            if ((processed & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.LAYERED);
            }
            int node = queue[head++];
            processed++;
            for (int e = adjacency.offsets[node]; e < adjacency.offsets[node + 1]; e++) {
                int next = adjacency.targets[e];
                if (layer[next] < layer[node] + 1) {
                    layer[next] = layer[node] + 1;
                }
                if (--remaining[next] == 0) {
                    queue[tail++] = next;
                }
            }
        }

        int deepest = 0;
        for (int value : layer) {
            deepest = Math.max(deepest, value);
        }
        if (processed < count) {
            // Whatever is left is in a cycle. Park it past everything that was
            // placed properly, so the drawing is odd rather than wrong.
            for (int i = 0; i < count; i++) {
                if (remaining[i] > 0) {
                    layer[i] = deepest + 1;
                }
            }
        }

        Map<Integer, Integer> filled = new HashMap<>();
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            int row = filled.merge(layer[i], 1, Integer::sum) - 1;
            x[i] = layer[i] * LAYER_GAP;
            y[i] = row * NODE_GAP;
        }
        return new Result(Kind.LAYERED, nodes, x, y, false);
    }

    /**
     * Concentric rings around the first node.
     *
     * <p>For a neighbourhood, where the question is "what is near this" and
     * distance from the centre is the answer. Ring membership is graph distance
     * ignoring edge direction — a neighbourhood's rings are about closeness, not
     * about which way the dependency points. Position within a ring is discovery
     * order, which the traversal already made deterministic.
     *
     * <p>Nodes the walk never reaches — an extraction can contain them, since
     * {@link GraphExtract#neighbourhood} merges two traversals — land in a final
     * outer ring rather than on top of the centre.
     */
    public static Result radial(GraphExtract.Result extract, AtomicBoolean cancelled) {
        List<Integer> nodes = extract.nodes();
        if (nodes.isEmpty()) {
            return Result.empty(Kind.RADIAL);
        }
        Adjacency adjacency = Adjacency.undirected(extract);
        int count = nodes.size();
        int[] ring = new int[count];
        Arrays.fill(ring, -1);
        ring[0] = 0;

        int[] queue = new int[count];
        int head = 0;
        int tail = 0;
        queue[tail++] = 0;
        while (head < tail) {
            if ((head & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.RADIAL);
            }
            int node = queue[head++];
            for (int e = adjacency.offsets[node]; e < adjacency.offsets[node + 1]; e++) {
                int next = adjacency.targets[e];
                if (ring[next] < 0) {
                    ring[next] = ring[node] + 1;
                    queue[tail++] = next;
                }
            }
        }

        int outermost = 0;
        for (int value : ring) {
            outermost = Math.max(outermost, value);
        }
        for (int i = 0; i < count; i++) {
            if (ring[i] < 0) {
                ring[i] = outermost + 1;
            }
        }

        int[] ringSizes = new int[outermost + 2];
        for (int value : ring) {
            ringSizes[value]++;
        }
        int[] placed = new int[ringSizes.length];
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            int r = ring[i];
            int slot = placed[r]++;
            double radius = r * LAYER_GAP;
            double angle = 2 * Math.PI * slot / ringSizes[r];
            x[i] = radius * Math.cos(angle);
            y[i] = radius * Math.sin(angle);
        }
        return new Result(Kind.RADIAL, nodes, x, y, false);
    }

    /**
     * A straight line, in the order given.
     *
     * <p>For a path — plan 13.5's "path between two actions" and "critical
     * path". The order is the path's, not the graph's, which is the whole point:
     * a critical path drawn in node-id order would be a scatter of dots.
     */
    public static Result linear(GraphExtract.Result extract, AtomicBoolean cancelled) {
        List<Integer> nodes = extract.nodes();
        double[] x = new double[nodes.size()];
        double[] y = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.LINEAR);
            }
            x[i] = i * LAYER_GAP;
            y[i] = 0;
        }
        return new Result(Kind.LINEAR, nodes, x, y, false);
    }

    /**
     * A square-ish grid, in the order given.
     *
     * <p>For cluster summaries, where there are no edges worth routing and the
     * question is "how many and how big". Deliberately boring: a grid is the
     * layout that adds no meaning of its own, which is the right choice when the
     * meaning is entirely in the sizes and the labels.
     */
    public static Result grid(GraphExtract.Result extract, AtomicBoolean cancelled) {
        List<Integer> nodes = extract.nodes();
        if (nodes.isEmpty()) {
            return Result.empty(Kind.GRID);
        }
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(nodes.size())));
        double[] x = new double[nodes.size()];
        double[] y = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.GRID);
            }
            x[i] = (i % columns) * NODE_GAP * 2;
            y[i] = (i / columns) * NODE_GAP * 2;
        }
        return new Result(Kind.GRID, nodes, x, y, false);
    }

    /** The layout a display mode gets, absent a user choice. */
    public static Kind defaultFor(GraphExtract.Mode mode) {
        return switch (mode) {
            case DEPENDENCIES, DEPENDENTS, WHOLE -> Kind.LAYERED;
            case NEIGHBOURHOOD -> Kind.RADIAL;
            case PATH, CRITICAL_PATH -> Kind.LINEAR;
            case CLUSTERS -> Kind.GRID;
        };
    }

    /** Runs the named layout. */
    public static Result run(Kind kind, GraphExtract.Result extract, AtomicBoolean cancelled) {
        return switch (kind) {
            case LAYERED -> layered(extract, cancelled);
            case RADIAL -> radial(extract, cancelled);
            case LINEAR -> linear(extract, cancelled);
            case GRID -> grid(extract, cancelled);
        };
    }

    /**
     * The extraction's edges as a CSR index over local positions.
     *
     * <p>An extraction names nodes by their graph-wide index; a layout works in
     * positions within the extraction. Building the translation once is what
     * keeps both traversals linear.
     */
    private record Adjacency(int[] offsets, int[] targets, int[] inDegree) {

        static Adjacency directed(GraphExtract.Result extract) {
            return build(extract, false);
        }

        static Adjacency undirected(GraphExtract.Result extract) {
            return build(extract, true);
        }

        private static Adjacency build(GraphExtract.Result extract, boolean bothWays) {
            List<Integer> nodes = extract.nodes();
            int count = nodes.size();
            Map<Integer, Integer> position = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                position.put(nodes.get(i), i);
            }

            int[] outDegree = new int[count];
            int[] inDegree = new int[count];
            int edgeCount = 0;
            for (GraphExtract.Edge edge : extract.edges()) {
                Integer from = position.get(edge.from());
                Integer to = position.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                outDegree[from]++;
                inDegree[to]++;
                edgeCount++;
                if (bothWays) {
                    outDegree[to]++;
                    edgeCount++;
                }
            }

            int[] offsets = new int[count + 1];
            for (int i = 0; i < count; i++) {
                offsets[i + 1] = offsets[i] + outDegree[i];
            }
            int[] targets = new int[edgeCount];
            int[] cursor = offsets.clone();
            for (GraphExtract.Edge edge : extract.edges()) {
                Integer from = position.get(edge.from());
                Integer to = position.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                targets[cursor[from]++] = to;
                if (bothWays) {
                    targets[cursor[to]++] = from;
                }
            }
            return new Adjacency(offsets, targets, inDegree);
        }
    }

    /** Which layout produced a placement. */
    public enum Kind {
        LAYERED("Layered"),
        RADIAL("Radial"),
        LINEAR("Linear"),
        GRID("Grid");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * Where each node goes, in world coordinates.
     *
     * <p>Parallel primitive arrays rather than a list of points: fifty thousand
     * nodes would otherwise be fifty thousand objects, and the canvas reads
     * these inside its paint loop.
     *
     * <p>A class rather than a record, which it was until the Phase 7 audit.
     * A record with array components gets an {@code equals} that compares those
     * arrays by reference — so two identical layouts compare unequal, which is
     * exactly the claim the determinism test exists to make — and mandates
     * public accessors that hand the arrays out. Copying them defensively on
     * every call is fifty thousand doubles for a caller who wanted one, and not
     * copying them lets a caller move a node. Neither is right, so neither is
     * offered: {@link #xAt} and {@link #yAt} are the only way in.
     */
    public static final class Result {

        private final Kind kind;
        private final List<Integer> nodes;
        private final double[] x;
        private final double[] y;
        private final boolean cancelled;

        Result(Kind kind, List<Integer> nodes, double[] x, double[] y, boolean cancelled) {
            this.kind = kind;
            this.nodes = List.copyOf(nodes);
            this.x = x;
            this.y = y;
            this.cancelled = cancelled;
        }

        /** A placement of nothing — for a graph that could not be read at all. */
        public static Result empty(Kind kind) {
            return new Result(kind, List.of(), new double[0], new double[0], false);
        }

        static Result cancelled(Kind kind) {
            return new Result(kind, List.of(), new double[0], new double[0], true);
        }

        public Kind kind() {
            return kind;
        }

        /** Graph-wide node indices, positionally aligned with the coordinates. */
        public List<Integer> nodes() {
            return nodes;
        }

        /** True when the layout stopped early, in which case it placed nothing. */
        public boolean cancelled() {
            return cancelled;
        }

        public double xAt(int i) {
            return x[i];
        }

        public double yAt(int i) {
            return y[i];
        }

        public int size() {
            return nodes.size();
        }

        /** The bounding box as min-x, min-y, max-x, max-y; empty when nothing is placed. */
        public Optional<double[]> bounds() {
            if (nodes.isEmpty()) {
                return Optional.empty();
            }
            double minX = x[0];
            double maxX = x[0];
            double minY = y[0];
            double maxY = y[0];
            for (int i = 1; i < x.length; i++) {
                minX = Math.min(minX, x[i]);
                maxX = Math.max(maxX, x[i]);
                minY = Math.min(minY, y[i]);
                maxY = Math.max(maxY, y[i]);
            }
            return Optional.of(new double[] {minX, minY, maxX, maxY});
        }
    }
}
