package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.graph.Bfs;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.ArrayList;
import java.util.List;

/**
 * A bounded piece of a graph, and the truth about what it left out.
 *
 * <h2>Every extraction is bounded, and every extraction says so</h2>
 *
 * <p>Plan 13.6: above the detailed-layout limit, switch to cluster mode, show
 * exact total counts, offer to raise the limit, and <em>never claim the omitted
 * nodes do not exist</em>. That last clause is why {@link Result} carries the
 * totals it drew from alongside what it returned — a caller cannot render this
 * without having been handed the number it is not showing.
 *
 * <p>Plan 13.3 forbids computing a transitive closure, so every traversal here
 * takes a node budget and reports whether it hit it. A neighbourhood that ran
 * out of budget is not a neighbourhood that ended.
 */
public final class GraphExtract {

    private GraphExtract() {}

    /**
     * The default beyond which detail is not attempted.
     *
     * <p>Plan 13.6 suggests around 50,000 nodes and 200,000 edges. Configurable
     * because the right number depends on the machine, and a default because a
     * user should not have to choose one before seeing anything.
     */
    public static final int DEFAULT_NODE_LIMIT = 50_000;

    public static final int DEFAULT_EDGE_LIMIT = 200_000;

    /**
     * What {@code source} needs: everything reachable over the <em>reverse</em>
     * index, to a depth and a budget.
     *
     * <p>Plan 13.5's "dependencies" mode. The forward index is
     * producer-to-consumer, so the things a node <em>depends on</em> are behind
     * it, not ahead of it — this used to walk the forward index and answered
     * "what needs this" under the name "dependencies", which inverted both the
     * trees and the rooted canvas modes.
     */
    public static Result dependencies(
            CsrGraph reverse, int source, int maxDepth, int nodeLimit) {
        return traverse(reverse, source, maxDepth, nodeLimit,
                Direction.REVERSE, Mode.DEPENDENCIES);
    }

    /**
     * What needs {@code source}: everything reachable over the forward,
     * producer-to-consumer index. Plan 13.5's "reverse dependencies" mode.
     */
    public static Result dependents(
            CsrGraph forward, int source, int maxDepth, int nodeLimit) {
        return traverse(forward, source, maxDepth, nodeLimit,
                Direction.FORWARD, Mode.DEPENDENTS);
    }

    /**
     * The neighbourhood around a node: both directions, to a shallow depth.
     *
     * <p>Plan 13.5's "selected action neighborhood", and the mode a user
     * reaches for most. Both directions because "what does this need and what
     * needs this" is one question in practice.
     */
    public static Result neighbourhood(
            CsrGraph forward, CsrGraph reverse, int source, int maxDepth, int nodeLimit) {
        Result out = traverse(
                forward, source, maxDepth, nodeLimit, Direction.FORWARD, Mode.DEPENDENTS);
        Result back = traverse(
                reverse, source, maxDepth, nodeLimit, Direction.REVERSE, Mode.DEPENDENCIES);

        List<Integer> merged = new ArrayList<>(out.nodes());
        for (int node : back.nodes()) {
            if (!merged.contains(node)) {
                merged.add(node);
            }
        }
        List<Edge> edges = new ArrayList<>(out.edges());
        for (Edge edge : back.edges()) {
            if (!edges.contains(edge)) {
                edges.add(edge);
            }
        }
        return new Result(
                Mode.NEIGHBOURHOOD, merged, edges,
                out.totalNodes(), out.totalEdges(),
                out.hitLimit() || back.hitLimit(), nodeLimit);
    }

    /**
     * The nodes on a path, as a subgraph.
     *
     * <p>Plan 13.5's "path between two nodes" and "critical path" both land
     * here: a path is a list of nodes and the edges between consecutive ones.
     */
    public static Result path(CsrGraph forward, List<Integer> nodes, Mode mode) {
        return path(forward, nodes, mode, DEFAULT_NODE_LIMIT, DEFAULT_EDGE_LIMIT);
    }

    /**
     * The nodes on a path, refusing the whole drawing before allocating it when
     * it exceeds either detailed-drawing budget.
     *
     * <p>A clipped path is not a path, so this never returns a prefix and calls
     * it complete. The exception reports the exact requested size for the UI.
     */
    public static Result path(
            CsrGraph forward,
            List<Integer> nodes,
            Mode mode,
            int nodeLimit,
            int edgeLimit) {
        requirePathWithinLimits(nodes.size(), mode, nodeLimit, edgeLimit);
        List<Edge> edges = new ArrayList<>(Math.max(0, nodes.size() - 1));
        for (int i = 0; i + 1 < nodes.size(); i++) {
            edges.add(new Edge(nodes.get(i), nodes.get(i + 1)));
        }
        return new Result(
                mode, List.copyOf(nodes), edges,
                forward.nodeCount(), forward.edgeCount(), false, nodes.size());
    }

    /** Validates an explicit path before any node or edge list is copied. */
    public static void requirePathWithinLimits(
            int requestedNodes, Mode mode, int nodeLimit, int edgeLimit) {
        long requestedEdges = Math.max(0L, (long) requestedNodes - 1L);
        if (requestedNodes <= nodeLimit && requestedEdges <= edgeLimit) {
            return;
        }
        String subject = mode == Mode.CRITICAL_PATH
                ? "The critical path" : "The requested path";
        throw new PathLimitExceededException(
                subject + " has " + requestedNodes + " actions and " + requestedEdges
                        + " dependencies, exceeding the drawing budget of " + nodeLimit
                        + " actions and " + edgeLimit + " dependencies. Nothing was drawn;"
                        + " raise the Node or Edge budget and try again.",
                requestedNodes, requestedEdges, nodeLimit, edgeLimit);
    }

    /**
     * The whole graph, when it fits.
     *
     * <p>Returns an empty extraction marked {@code hitLimit} when it does not,
     * rather than a truncated one: a "whole graph" that silently showed the
     * first fifty thousand nodes would be the most misleading view in the
     * application.
     */
    public static Result whole(CsrGraph forward, int nodeLimit, int edgeLimit) {
        long nodes = forward.nodeCount();
        long edges = forward.edgeCount();
        if (nodes > nodeLimit || edges > edgeLimit) {
            return new Result(Mode.WHOLE, List.of(), List.of(), nodes, edges, true, nodeLimit);
        }
        List<Integer> all = new ArrayList<>((int) nodes);
        List<Edge> allEdges = new ArrayList<>((int) edges);
        for (int node = 0; node < nodes; node++) {
            all.add(node);
            int from = node;
            forward.forEachNeighbor(node, to -> allEdges.add(new Edge(from, to)));
        }
        return new Result(Mode.WHOLE, all, allEdges, nodes, edges, false, nodeLimit);
    }

    private enum Direction { FORWARD, REVERSE }

    private static Result traverse(
            CsrGraph graph, int source, int maxDepth, int nodeLimit,
            Direction direction, Mode mode) {
        int nodeCount = Math.toIntExact(graph.nodeCount());
        if (source < 0 || source >= nodeCount) {
            throw new IndexOutOfBoundsException(
                    "node " + source + " is outside a graph of " + nodeCount);
        }
        List<Integer> visited = new ArrayList<>();
        // Bfs already enforces both budgets; this collects what it visited.
        long reached = new Bfs(graph).run(source, nodeLimit, maxDepth, visited::add);

        boolean[] inside = new boolean[nodeCount];
        for (int node : visited) {
            inside[node] = true;
        }
        List<Edge> edges = new ArrayList<>();
        for (int node : visited) {
            int from = node;
            graph.forEachNeighbor(node, to -> {
                if (inside[to]) {
                    // Reversed back to producer-to-consumer, so a subgraph
                    // drawn from a reverse traversal has its arrows the right
                    // way round.
                    edges.add(direction == Direction.FORWARD
                            ? new Edge(from, to) : new Edge(to, from));
                }
            });
        }
        boolean hitLimit = reached >= nodeLimit;
        return new Result(
                mode, visited, edges, graph.nodeCount(), graph.edgeCount(), hitLimit, nodeLimit);
    }

    /** Which of plan 13.5's display modes produced an extraction. */
    public enum Mode {
        DEPENDENCIES("Dependencies"),
        DEPENDENTS("Reverse dependencies"),
        NEIGHBOURHOOD("Neighbourhood"),
        PATH("Path between two actions"),
        CRITICAL_PATH("Critical path"),
        WHOLE("Whole build"),
        CLUSTERS("Clusters");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /**
         * {@link #displayName()}, naming the nodes for what they are.
         *
         * <p>Only {@code PATH} carries a noun; "Path between two actions"
         * over the label graph would misname both endpoints.
         *
         * @param noun what one node is — {@code "action"} or {@code "target"}
         */
        public String displayName(String noun) {
            return this == PATH ? "Path between two " + noun + "s" : displayName;
        }
    }

    /** One directed edge, in producer-to-consumer order. */
    public record Edge(int from, int to) {}

    /** An explicit path was refused intact because it exceeded a drawing budget. */
    public static final class PathLimitExceededException extends IllegalArgumentException {

        private final int requestedNodes;
        private final long requestedEdges;

        private PathLimitExceededException(
                String message,
                int requestedNodes,
                long requestedEdges,
                int nodeLimit,
                int edgeLimit) {
            super(message);
            this.requestedNodes = requestedNodes;
            this.requestedEdges = requestedEdges;
        }

        public int requestedNodes() {
            return requestedNodes;
        }

        public long requestedEdges() {
            return requestedEdges;
        }
    }

    /**
     * What an extraction returned, and what it did not.
     *
     * @param totalNodes the whole graph's node count, whatever this extraction
     *     shows. Plan 13.6: exact totals remain visible.
     * @param hitLimit whether the traversal stopped because it ran out of
     *     budget rather than because it ran out of graph
     */
    public record Result(
            Mode mode,
            List<Integer> nodes,
            List<Edge> edges,
            long totalNodes,
            long totalEdges,
            boolean hitLimit,
            int nodeLimit) {

        public Result {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        /** True when this is the whole of what the query asked for. */
        public boolean isComplete() {
            return !hitLimit;
        }

        /**
         * What the view must say alongside the drawing.
         *
         * <p>Plan 13.6: never claim the omitted nodes do not exist. An
         * extraction that stopped early says how much it is showing of what,
         * and an extraction that did not still names the totals — because a
         * neighbourhood of nine drawn from a graph of ninety thousand is a
         * different picture from a build with nine actions.
         */
        public String describe() {
            return describe("action");
        }

        /**
         * {@link #describe()}, naming the nodes for what they are.
         *
         * <p>The extraction is graph-agnostic but the sentence is not: a
         * label-graph view that called its nodes "actions" would misstate what
         * is on screen, which is the kind of small wrong that makes every
         * other number suspect.
         *
         * @param noun what one node is — {@code "action"} or {@code "target"}
         */
        public String describe(String noun) {
            if (mode == Mode.WHOLE && hitLimit) {
                return "This build has " + totalNodes + " " + noun + "s and " + totalEdges
                        + " dependencies, which is more than the " + nodeLimit
                        + "-" + noun + " limit for a detailed drawing. Nothing is hidden — raise"
                        + " the limit, narrow the filter, or switch to the cluster view.";
            }
            StringBuilder text = new StringBuilder();
            text.append(mode.displayName(noun)).append(": ").append(nodes.size())
                    .append(nodes.size() == 1 ? " " + noun : " " + noun + "s")
                    .append(" and ").append(edges.size())
                    .append(edges.size() == 1 ? " dependency" : " dependencies");
            text.append(", from a graph of ").append(totalNodes).append('.');
            if (hitLimit) {
                text.append(" The search stopped at its ").append(nodeLimit)
                        .append("-").append(noun)
                        .append(" budget, so there is more beyond what is drawn.");
            }
            return text.toString();
        }

    }
}
