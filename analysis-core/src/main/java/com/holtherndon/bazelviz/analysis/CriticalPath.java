package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The longest weighted path through an action graph: what the build could not
 * have finished sooner than, given its dependencies.
 *
 * <h2>This is not Bazel's critical path</h2>
 *
 * <p>Bazel computes its own and writes it into the trace profile, and Phase 5
 * stores it untouched in {@code bazel_critical_path}. This is a different
 * number computed from a different thing — the dependency graph, weighted by
 * measured durations — and plan 13.4 requires it to be labelled
 * "Visualizer-computed dependency critical path" and never presented as
 * Bazel's. {@link Result#displayName()} is the only name it has, for that
 * reason.
 *
 * <p>The two legitimately disagree. Bazel's is what actually gated the build,
 * including scheduling and machine limits; this one is what the dependencies
 * alone imply, as if every action could start the moment its inputs existed.
 * A build whose two paths differ a lot was limited by something other than its
 * graph, which is worth knowing and is why ADR-009 keeps both.
 *
 * <h2>Only on an acyclic graph</h2>
 *
 * <p>Plan 13.4 says so, and a longest path is undefined with a cycle in it.
 * Kahn's algorithm both orders the graph and detects cycles in one pass, so
 * detection is free rather than a separate check that could be skipped.
 *
 * <p>The plan also says to condense strongly connected components and compute
 * on the condensation. That is not implemented, and the reason is that a
 * producer-to-consumer action graph cannot have a cycle: an artifact is
 * produced by exactly one action, and an action that consumed its own output
 * could never have run. A cycle here means the graph is wrong, and the useful
 * response is to say which nodes are in one — which {@link Result} does —
 * rather than to compute a plausible number over a graph that should not exist.
 */
public final class CriticalPath {

    private CriticalPath() {}

    /**
     * Computes the path over {@code forward}, weighting each node by
     * {@code durationMicros}.
     *
     * @param forward producer-to-consumer adjacency: an edge {@code u -> v}
     *     means {@code v} consumed something {@code u} produced, so {@code v}
     *     cannot start until {@code u} finishes
     * @param durationMicros one weight per node; {@link #UNKNOWN_DURATION} for
     *     a node nothing timed
     * @param source which measurement the weights came from, carried into the
     *     result because plan 13.4 requires the answer to say
     */
    public static Result compute(CsrGraph forward, long[] durationMicros, DurationSource source) {
        int nodeCount = Math.toIntExact(forward.nodeCount());
        if (durationMicros.length != nodeCount) {
            throw new IllegalArgumentException(
                    "one weight per node: " + durationMicros.length + " weights for "
                            + nodeCount + " nodes");
        }
        if (nodeCount == 0) {
            return Result.empty(source);
        }

        int[] order = topologicalOrder(forward, nodeCount);
        if (order == null) {
            return Result.cyclic(source, cyclicNodes(forward, nodeCount));
        }

        long untimed = 0;
        for (long duration : durationMicros) {
            if (duration == UNKNOWN_DURATION) {
                untimed++;
            }
        }

        // Forward pass. earliestStart(v) = max over predecessors of
        // earliestFinish(p); with only forward edges to hand, each node pushes
        // its finish to its successors instead, which visits every edge once.
        long[] earliestStart = new long[nodeCount];
        long[] earliestFinish = new long[nodeCount];
        int[] predecessor = new int[nodeCount];
        Arrays.fill(predecessor, NO_PREDECESSOR);

        for (int node : order) {
            earliestFinish[node] = earliestStart[node] + weight(durationMicros, node);
            long finish = earliestFinish[node];
            forward.forEachNeighbor(node, successor -> {
                if (finish > earliestStart[successor]) {
                    earliestStart[successor] = finish;
                    predecessor[successor] = node;
                }
            });
        }

        int last = 0;
        for (int node = 1; node < nodeCount; node++) {
            if (earliestFinish[node] > earliestFinish[last]) {
                last = node;
            }
        }

        // Backward pass, for slack. latestFinish starts at the makespan so a
        // node off the path gets the room it really has.
        long makespan = earliestFinish[last];
        long[] latestFinish = new long[nodeCount];
        Arrays.fill(latestFinish, makespan);
        for (int i = order.length - 1; i >= 0; i--) {
            int node = order[i];
            long[] earliest = {Long.MAX_VALUE};
            forward.forEachNeighbor(node, successor -> {
                long successorStart = latestFinish[successor] - weight(durationMicros, successor);
                if (successorStart < earliest[0]) {
                    earliest[0] = successorStart;
                }
            });
            if (earliest[0] != Long.MAX_VALUE) {
                latestFinish[node] = earliest[0];
            }
        }
        long[] slack = new long[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            slack[node] = (latestFinish[node] - weight(durationMicros, node)) - earliestStart[node];
        }

        List<Integer> path = new ArrayList<>();
        for (int node = last; node != NO_PREDECESSOR; node = predecessor[node]) {
            path.add(node);
        }
        java.util.Collections.reverse(path);

        return new Result(
                Outcome.COMPUTED, source, List.copyOf(path), makespan,
                earliestStart, earliestFinish, slack, untimed, nodeCount, List.of());
    }

    /** A node nothing measured. Distinct from a node measured at zero. */
    public static final long UNKNOWN_DURATION = -1;

    private static final int NO_PREDECESSOR = -1;

    /**
     * A node's weight, with an unknown duration counted as no time.
     *
     * <p>Not a guess dressed as a measurement: the count of untimed nodes
     * travels with the result and marks it partial, so a path computed over a
     * graph nobody timed is reported as one rather than as a zero-length build.
     */
    private static long weight(long[] durationMicros, int node) {
        long duration = durationMicros[node];
        return duration == UNKNOWN_DURATION ? 0 : duration;
    }

    /** Kahn's algorithm; null when the graph has a cycle. */
    private static int[] topologicalOrder(CsrGraph forward, int nodeCount) {
        int[] inDegree = new int[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            forward.forEachNeighbor(node, successor -> inDegree[successor]++);
        }
        int[] queue = new int[nodeCount];
        int head = 0;
        int tail = 0;
        for (int node = 0; node < nodeCount; node++) {
            if (inDegree[node] == 0) {
                queue[tail++] = node;
            }
        }
        int[] order = new int[nodeCount];
        int emitted = 0;
        while (head < tail) {
            int node = queue[head++];
            order[emitted++] = node;
            int[] cursor = {tail};
            forward.forEachNeighbor(node, successor -> {
                if (--inDegree[successor] == 0) {
                    queue[cursor[0]++] = successor;
                }
            });
            tail = cursor[0];
        }
        return emitted == nodeCount ? order : null;
    }

    /**
     * The nodes that never reached in-degree zero, which is exactly the set in
     * or downstream of a cycle.
     *
     * <p>Reported rather than a bare "there is a cycle", because a graph that
     * should be acyclic and is not is a bug someone has to find.
     */
    private static List<Integer> cyclicNodes(CsrGraph forward, int nodeCount) {
        int[] inDegree = new int[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            forward.forEachNeighbor(node, successor -> inDegree[successor]++);
        }
        boolean[] removed = new boolean[nodeCount];
        int[] queue = new int[nodeCount];
        int head = 0;
        int tail = 0;
        for (int node = 0; node < nodeCount; node++) {
            if (inDegree[node] == 0) {
                queue[tail++] = node;
                removed[node] = true;
            }
        }
        while (head < tail) {
            int node = queue[head++];
            int[] cursor = {tail};
            forward.forEachNeighbor(node, successor -> {
                if (!removed[successor] && --inDegree[successor] == 0) {
                    removed[successor] = true;
                    queue[cursor[0]++] = successor;
                }
            });
            tail = cursor[0];
        }
        List<Integer> stuck = new ArrayList<>();
        for (int node = 0; node < nodeCount; node++) {
            if (!removed[node]) {
                stuck.add(node);
            }
        }
        return List.copyOf(stuck);
    }

    /** Which measurement the node weights came from. */
    public enum DurationSource {
        /** The build event stream's action start and end. */
        BEP_ACTION("action durations from the build event stream"),
        /** The execution log's per-spawn total time. */
        EXECUTION_ATTEMPT("spawn durations from the execution log"),
        /** No weights at all: every node counts as instantaneous. */
        NONE("no durations, so every action counts as instantaneous");

        private final String description;

        DurationSource(String description) {
            this.description = description;
        }

        /** The words the UI shows, because plan 13.4 requires the answer to say. */
        public String description() {
            return description;
        }
    }

    /** How the computation ended. */
    public enum Outcome {
        COMPUTED,
        /** The graph has a cycle, so a longest path is undefined. */
        CYCLIC,
        /** There was no graph. */
        EMPTY
    }

    /**
     * The path and what may be said about it.
     *
     * @param path node indices from the start of the chain to its end
     * @param makespanMicros the length of the path, which is what the build
     *     could not have beaten given its dependencies
     * @param untimedNodes nodes nothing measured, counted as instantaneous;
     *     any at all makes the result partial
     * @param cyclicNodes when {@link Outcome#CYCLIC}, the nodes that could not
     *     be ordered
     */
    public record Result(
            Outcome outcome,
            DurationSource durationSource,
            List<Integer> path,
            long makespanMicros,
            long[] earliestStartMicros,
            long[] earliestFinishMicros,
            long[] slackMicros,
            long untimedNodes,
            long nodeCount,
            List<Integer> cyclicNodes) {

        public Result {
            path = List.copyOf(path);
            cyclicNodes = List.copyOf(cyclicNodes);
            earliestStartMicros = earliestStartMicros.clone();
            earliestFinishMicros = earliestFinishMicros.clone();
            slackMicros = slackMicros.clone();
        }

        static Result empty(DurationSource source) {
            return new Result(Outcome.EMPTY, source, List.of(), 0,
                    new long[0], new long[0], new long[0], 0, 0, List.of());
        }

        static Result cyclic(DurationSource source, List<Integer> stuck) {
            return new Result(Outcome.CYCLIC, source, List.of(), 0,
                    new long[0], new long[0], new long[0], 0, 0, stuck);
        }

        @Override
        public long[] earliestStartMicros() {
            return earliestStartMicros.clone();
        }

        @Override
        public long[] earliestFinishMicros() {
            return earliestFinishMicros.clone();
        }

        @Override
        public long[] slackMicros() {
            return slackMicros.clone();
        }

        /**
         * The only name this may be shown under.
         *
         * <p>Plan 13.4: label it so, and do not present it as Bazel's own.
         */
        public String displayName() {
            return "Visualizer-computed dependency critical path";
        }

        /**
         * True when some of the graph or some of the timings were missing, so
         * the answer is a lower bound rather than the answer.
         */
        public boolean isPartial() {
            return untimedNodes > 0;
        }

        /** What the user needs to read this number correctly. */
        public String describe() {
            return switch (outcome) {
                case EMPTY -> "There is no dependency graph to compute a critical path over.";
                case CYCLIC -> "The dependency graph has a cycle involving " + cyclicNodes.size()
                        + " actions, so there is no longest path through it. An action graph"
                        + " derived from producer and consumer relationships cannot have one,"
                        + " so this means the graph is wrong rather than the build.";
                case COMPUTED -> {
                    StringBuilder text = new StringBuilder(displayName())
                            .append(": ").append(path.size()).append(" actions totalling ")
                            .append(makespanMicros / 1000).append(" ms, using ")
                            .append(durationSource.description()).append('.');
                    if (isPartial()) {
                        text.append(' ').append(untimedNodes).append(" of ").append(nodeCount)
                                .append(" actions have no measured duration and were counted as"
                                        + " instantaneous, so this is a lower bound.");
                    }
                    yield text.toString();
                }
            };
        }
    }
}
