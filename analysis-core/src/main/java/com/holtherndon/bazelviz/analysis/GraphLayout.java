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
 * <h2>Five layouts, none of them force-directed</h2>
 *
 * <p>Plan 13.7 names layered for dependency subgraphs, radial for
 * neighbourhoods, linear for critical paths and grid for cluster summaries, and
 * ends "do not run force-directed layout on an unbounded action graph". There is
 * none here at all: every layout below uses bounded linear passes, so the
 * answer to "how long will this take" is the graph size rather than a
 * convergence criterion.
 *
 * <h2>Linear, not merely non-iterative</h2>
 *
 * <p>Layering by repeated edge relaxation is easier to write and is O(V·E) in
 * the worst case; at the 50,000-node limit of {@link GraphExtract} that is not a
 * layout, it is a hang. The graph traversals here build a local adjacency index
 * once and then run in O(V+E) — Kahn's algorithm for layering and queue-based
 * breadth-first walks for hierarchy and rings.
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

    /** Horizontal room for one leaf in the dependency hierarchy. */
    private static final double HIERARCHY_LEAF_GAP = 100;

    /** Vertical room between parent and child levels in the hierarchy. */
    private static final double HIERARCHY_LEVEL_GAP = 64;

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
        Adjacency adjacency = Adjacency.directed(extract, cancelled);
        if (adjacency == null) {
            return Result.cancelled(Kind.LAYERED);
        }
        int count = nodes.size();
        int[] layer = new int[count];
        int[] remaining = adjacency.inDegree.clone();

        int[] queue = new int[count];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.LAYERED);
            }
            if (remaining[i] == 0) {
                queue[tail++] = i;
            }
        }
        int processed = 0;
        int work = 0;
        while (head < tail) {
            if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.LAYERED);
            }
            int node = queue[head++];
            processed++;
            for (int e = adjacency.offsets[node]; e < adjacency.offsets[node + 1]; e++) {
                if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return Result.cancelled(Kind.LAYERED);
                }
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
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.LAYERED);
            }
            deepest = Math.max(deepest, layer[i]);
        }
        if (processed < count) {
            // Whatever is left is in a cycle. Park it past everything that was
            // placed properly, so the drawing is odd rather than wrong.
            for (int i = 0; i < count; i++) {
                if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return Result.cancelled(Kind.LAYERED);
                }
                if (remaining[i] > 0) {
                    layer[i] = deepest + 1;
                }
            }
        }

        Map<Integer, Integer> filled = new HashMap<>();
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.LAYERED);
            }
            int row = filled.merge(layer[i], 1, Integer::sum) - 1;
            x[i] = layer[i] * LAYER_GAP;
            y[i] = row * NODE_GAP;
        }
        return new Result(Kind.LAYERED, nodes, x, y, false);
    }

    /**
     * A top-to-bottom dependency hierarchy over a deterministic spanning forest.
     *
     * <p>A dependency DAG is not a tree: a generated artifact can feed several
     * consumers and an action can need several producers. This layout does not
     * duplicate nodes or pretend otherwise. First discovery chooses one primary
     * parent for placement; every other real dependency remains a cross-link for
     * the renderer to disclose and draw on demand.
     *
     * <p>Rooted views start from their selected node. Dependencies walk towards
     * producers, reverse dependencies walk towards consumers, and a neighbourhood
     * walks both ways. Whole-build and cluster views start from zero-indegree
     * nodes. Any nodes left by a disconnected component or cycle are seeded in
     * stable node order, so the algorithm always terminates and places every
     * node exactly once.
     *
     * <p>Child subtrees receive contiguous leaf spans and each parent is centred
     * over its span. The implementation is iterative and O(V+E): primitive
     * layout arrays, no recursive stack and no object per edge.
     */
    public static Result hierarchy(GraphExtract.Result extract, AtomicBoolean cancelled) {
        List<Integer> nodes = extract.nodes();
        if (nodes.isEmpty()) {
            return Result.empty(Kind.HIERARCHY);
        }
        if (cancelled.get()) {
            return Result.cancelled(Kind.HIERARCHY);
        }

        int count = nodes.size();
        Adjacency traversal = switch (extract.mode()) {
            case DEPENDENCIES -> Adjacency.reversed(extract, cancelled);
            case NEIGHBOURHOOD -> Adjacency.undirected(extract, cancelled);
            case DEPENDENTS, WHOLE, PATH, CRITICAL_PATH, CLUSTERS ->
                    Adjacency.directed(extract, cancelled);
        };
        if (traversal == null) {
            return Result.cancelled(Kind.HIERARCHY);
        }

        int[] parent = new int[count];
        Arrays.fill(parent, -1);
        int[] depth = new int[count];
        boolean[] seen = new boolean[count];
        int[] discovery = new int[count];
        int[] queue = new int[count];
        int discovered = 0;

        // Rooted extracts put the selected node first. Whole graphs instead
        // start at their natural producer roots, in stable local order.
        if (extract.mode() == GraphExtract.Mode.WHOLE
                || extract.mode() == GraphExtract.Mode.CLUSTERS) {
            for (int node = 0; node < count; node++) {
                if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return Result.cancelled(Kind.HIERARCHY);
                }
                if (traversal.inDegree[node] == 0 && !seen[node]) {
                    int added = discoverTree(
                            node, traversal, seen, parent, depth, discovery, discovered,
                            queue, cancelled);
                    if (added < 0) {
                        return Result.cancelled(Kind.HIERARCHY);
                    }
                    discovered += added;
                }
            }
        } else {
            int added = discoverTree(
                    0, traversal, seen, parent, depth, discovery, discovered,
                    queue, cancelled);
            if (added < 0) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            discovered += added;
        }

        // Disconnected components and source cycles have no reachable natural
        // root. The smallest unvisited local position becomes one; discovery
        // still owns each node once, so a closing cycle edge is a cross-link.
        for (int node = 0; node < count; node++) {
            if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            if (seen[node]) {
                continue;
            }
            int added = discoverTree(
                    node, traversal, seen, parent, depth, discovery, discovered,
                    queue, cancelled);
            if (added < 0) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            discovered += added;
        }

        int[] childCount = new int[count];
        for (int node = 0; node < count; node++) {
            if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            if (parent[node] >= 0) {
                childCount[parent[node]]++;
            }
        }
        int[] childOffsets = new int[count + 1];
        for (int node = 0; node < count; node++) {
            if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            childOffsets[node + 1] = childOffsets[node] + childCount[node];
        }
        int[] children = new int[childOffsets[count]];
        int[] childCursor = childOffsets.clone();
        for (int node = 0; node < count; node++) {
            if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            if (parent[node] >= 0) {
                children[childCursor[parent[node]]++] = node;
            }
        }

        // Discovery is parent-before-child. Its reverse is therefore a valid
        // postorder for accumulating each subtree's leaf width.
        int[] leaves = new int[count];
        for (int at = discovered - 1; at >= 0; at--) {
            int node = discovery[at];
            if (childCount[node] == 0) {
                leaves[node] = 1;
            }
            if (parent[node] >= 0) {
                leaves[parent[node]] += leaves[node];
            }
            if ((at & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
        }

        double[] spanStart = new double[count];
        double nextComponent = 0;
        for (int at = 0; at < discovered; at++) {
            if ((at & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            int node = discovery[at];
            if (parent[node] < 0) {
                spanStart[node] = nextComponent;
                nextComponent += leaves[node];
            }
        }
        double[] x = new double[count];
        double[] y = new double[count];
        for (int at = 0; at < discovered; at++) {
            if ((at & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.HIERARCHY);
            }
            int node = discovery[at];
            double childStart = spanStart[node];
            for (int edge = childOffsets[node]; edge < childOffsets[node + 1]; edge++) {
                if ((edge & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return Result.cancelled(Kind.HIERARCHY);
                }
                int child = children[edge];
                spanStart[child] = childStart;
                childStart += leaves[child];
            }
            x[node] = (spanStart[node] + leaves[node] / 2.0) * HIERARCHY_LEAF_GAP;
            y[node] = depth[node] * HIERARCHY_LEVEL_GAP;
        }
        return new Result(Kind.HIERARCHY, nodes, x, y, parent, false);
    }

    /**
     * Breadth-first first-discovery ownership for one hierarchy component.
     *
     * @return nodes added, or -1 when cancellation was requested
     */
    private static int discoverTree(
            int root,
            Adjacency adjacency,
            boolean[] seen,
            int[] parent,
            int[] depth,
            int[] discovery,
            int discoveryStart,
            int[] queue,
            AtomicBoolean cancelled) {
        int head = 0;
        int tail = 0;
        seen[root] = true;
        queue[tail++] = root;
        int added = 0;
        int work = 0;
        while (head < tail) {
            if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return -1;
            }
            int node = queue[head++];
            discovery[discoveryStart + added++] = node;
            for (int edge = adjacency.offsets[node]; edge < adjacency.offsets[node + 1]; edge++) {
                if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return -1;
                }
                int next = adjacency.targets[edge];
                if (seen[next]) {
                    continue;
                }
                seen[next] = true;
                parent[next] = node;
                depth[next] = depth[node] + 1;
                queue[tail++] = next;
            }
        }
        return added;
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
        Adjacency adjacency = Adjacency.undirected(extract, cancelled);
        if (adjacency == null) {
            return Result.cancelled(Kind.RADIAL);
        }
        int count = nodes.size();
        int[] ring = new int[count];
        Arrays.fill(ring, -1);
        ring[0] = 0;

        int[] queue = new int[count];
        int head = 0;
        int tail = 0;
        queue[tail++] = 0;
        int work = 0;
        while (head < tail) {
            if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.RADIAL);
            }
            int node = queue[head++];
            for (int e = adjacency.offsets[node]; e < adjacency.offsets[node + 1]; e++) {
                if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return Result.cancelled(Kind.RADIAL);
                }
                int next = adjacency.targets[e];
                if (ring[next] < 0) {
                    ring[next] = ring[node] + 1;
                    queue[tail++] = next;
                }
            }
        }

        int outermost = 0;
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.RADIAL);
            }
            outermost = Math.max(outermost, ring[i]);
        }
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.RADIAL);
            }
            if (ring[i] < 0) {
                ring[i] = outermost + 1;
            }
        }

        int[] ringSizes = new int[outermost + 2];
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.RADIAL);
            }
            ringSizes[ring[i]]++;
        }
        int[] placed = new int[ringSizes.length];
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(Kind.RADIAL);
            }
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
            case DEPENDENCIES, DEPENDENTS, NEIGHBOURHOOD, WHOLE -> Kind.HIERARCHY;
            case PATH, CRITICAL_PATH -> Kind.LINEAR;
            case CLUSTERS -> Kind.GRID;
        };
    }

    /** Runs the named layout. */
    public static Result run(Kind kind, GraphExtract.Result extract, AtomicBoolean cancelled) {
        return switch (kind) {
            case HIERARCHY -> hierarchy(extract, cancelled);
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

        static Adjacency directed(GraphExtract.Result extract, AtomicBoolean cancelled) {
            return build(extract, false, false, cancelled);
        }

        static Adjacency reversed(GraphExtract.Result extract, AtomicBoolean cancelled) {
            return build(extract, true, false, cancelled);
        }

        static Adjacency undirected(GraphExtract.Result extract, AtomicBoolean cancelled) {
            return build(extract, false, true, cancelled);
        }

        private static Adjacency build(
                GraphExtract.Result extract,
                boolean reverseEdges,
                boolean bothWays,
                AtomicBoolean cancelled) {
            List<Integer> nodes = extract.nodes();
            int count = nodes.size();
            Map<Integer, Integer> position = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return null;
                }
                position.put(nodes.get(i), i);
            }

            int[] outDegree = new int[count];
            int[] inDegree = new int[count];
            int edgeCount = 0;
            int work = 0;
            for (GraphExtract.Edge edge : extract.edges()) {
                if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return null;
                }
                Integer from = position.get(reverseEdges ? edge.to() : edge.from());
                Integer to = position.get(reverseEdges ? edge.from() : edge.to());
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
                if ((i & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return null;
                }
                offsets[i + 1] = offsets[i] + outDegree[i];
            }
            int[] targets = new int[edgeCount];
            int[] cursor = offsets.clone();
            work = 0;
            for (GraphExtract.Edge edge : extract.edges()) {
                if ((work++ & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                    return null;
                }
                Integer from = position.get(reverseEdges ? edge.to() : edge.from());
                Integer to = position.get(reverseEdges ? edge.from() : edge.to());
                if (from == null || to == null) {
                    continue;
                }
                targets[cursor[from]++] = to;
                if (bothWays) {
                    targets[cursor[to]++] = from;
                }
            }
            if (cancelled.get()) {
                return null;
            }
            return new Adjacency(offsets, targets, inDegree);
        }
    }

    /** Which layout produced a placement. */
    public enum Kind {
        HIERARCHY(
                "Dependency hierarchy",
                "Top-down primary dependency branches; shared and cyclic links stay as cross-links."),
        LAYERED("Layered", "Longest-path columns with every node in its dependency layer."),
        RADIAL("Radial", "Concentric rings by distance from the selected node."),
        LINEAR("Linear", "One left-to-right line, intended for paths."),
        GRID("Grid", "Even rows and columns, intended for grouped summaries.");

        private final String displayName;
        private final String description;

        Kind(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
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
        private final int[] parent;
        private final boolean cancelled;

        Result(Kind kind, List<Integer> nodes, double[] x, double[] y, boolean cancelled) {
            this(kind, nodes, x, y, noParents(nodes.size()), cancelled);
        }

        Result(
                Kind kind,
                List<Integer> nodes,
                double[] x,
                double[] y,
                int[] parent,
                boolean cancelled) {
            this.kind = kind;
            this.nodes = List.copyOf(nodes);
            this.x = x;
            this.y = y;
            this.parent = parent;
            this.cancelled = cancelled;
        }

        private static int[] noParents(int count) {
            int[] parents = new int[count];
            Arrays.fill(parents, -1);
            return parents;
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

        /**
         * This node's primary hierarchy parent as a layout position, or -1.
         * Other layouts have no primary-parent claim and return -1 everywhere.
         */
        public int parentAt(int position) {
            return parent[position];
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
