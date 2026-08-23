package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The far-zoom view: one box per package, target or mnemonic.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Plan 13.6: above the detailed-layout limit, "switch to cluster mode, show
 * exact total counts, offer to raise the limit". {@link GraphExtract#whole}
 * refuses a graph that will not fit rather than truncating it; this is what the
 * user gets instead. A build of two million actions has perhaps a few thousand
 * packages and a few dozen mnemonics, so the same build that cannot be drawn
 * node by node is legible as soon as the question becomes "which package".
 *
 * <h2>It is a real aggregation, not a sample</h2>
 *
 * <p>Rule 12 forbids silently sampling or dropping. Every node lands in exactly
 * one cluster and every edge is counted — inside a cluster or between two — so
 * the cluster counts sum to the graph's node count and the edge weights sum to
 * its edge count. The tests assert both sums, because an aggregation whose parts
 * do not add up to the whole is the kind of wrong that looks right.
 *
 * <h2>Unknown is a cluster, not a blank</h2>
 *
 * <p>Plan 11.4: unknown values must be visibly unknown. A node whose label or
 * mnemonic the import never learned goes into a cluster that says so, rather
 * than into an empty-string cluster that reads as a real package named nothing.
 */
public final class GraphClustering {

    private GraphClustering() {}

    /**
     * The default beyond which even clusters are too many to draw.
     *
     * <p>Deliberately far below {@link GraphExtract#DEFAULT_NODE_LIMIT}: a
     * cluster box carries a name and a count, so it needs room that a dot does
     * not.
     */
    public static final int DEFAULT_CLUSTER_LIMIT = 2_000;

    /** How often to look at the cancellation flag, in nodes or edges. */
    private static final int CANCEL_CHECK_INTERVAL = 65_536;

    /** What the nodes are grouped by. */
    public enum By {
        /** The Bazel package a target lives in: {@code //src/main/java}. */
        PACKAGE("Package"),
        /** The full target label: {@code //src/main/java:lib}. */
        TARGET("Target"),
        /** The action's mnemonic: {@code Javac}, {@code CppCompile}. */
        MNEMONIC("Mnemonic");

        private final String displayName;

        By(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * The package part of a Bazel label, or null when there is not one.
     *
     * <p>{@code //src/main:lib} is package {@code //src/main}. A label with no
     * colon — which is legal shorthand, {@code //src/main} meaning
     * {@code //src/main:main} — is already its own package. An external
     * repository's {@code @rules_java//java:defs} keeps its repository prefix,
     * because {@code //java} in two repositories are two different packages and
     * merging them would produce a cluster that does not exist.
     */
    public static String packageOf(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        int colon = label.lastIndexOf(':');
        if (colon < 0) {
            return label;
        }
        return label.substring(0, colon);
    }

    /**
     * Groups every node and aggregates every edge.
     *
     * <p>One pass over the nodes and one over the edges, so the cost is the
     * graph's size and nothing else. Cluster ordinals follow the keys' sort
     * order, with the unknown cluster last, so the same graph clusters into the
     * same picture every time.
     *
     * @param keyByNode one key per node index, aligned with the graph; null or
     *     blank means the import never learned it, and lands in the unknown
     *     cluster
     * @param maxClusters refuse rather than truncate above this many clusters
     */
    public static Result cluster(
            CsrGraph forward,
            String[] keyByNode,
            By by,
            int maxClusters,
            AtomicBoolean cancelled) {
        int nodeCount = Math.toIntExact(forward.nodeCount());
        if (keyByNode.length < nodeCount) {
            throw new IllegalArgumentException(
                    "keys cover " + keyByNode.length + " nodes but the graph has " + nodeCount);
        }

        // Sorted so ordinals are stable; the unknown cluster is appended after,
        // so it is always last however the real keys sort.
        Map<String, Integer> ordinals = new TreeMap<>();
        boolean anyUnknown = false;
        for (int node = 0; node < nodeCount; node++) {
            if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(by, maxClusters);
            }
            String key = keyByNode[node];
            if (key == null || key.isBlank()) {
                anyUnknown = true;
            } else {
                ordinals.putIfAbsent(key, 0);
            }
        }
        int next = 0;
        for (Map.Entry<String, Integer> entry : ordinals.entrySet()) {
            entry.setValue(next++);
        }
        // Only when there is something unknown. An always-present empty
        // "(name not recorded)" box would be a permanent piece of furniture
        // suggesting a gap that is not there.
        int unknownOrdinal = anyUnknown ? next : -1;

        int clusterCount = next + (anyUnknown ? 1 : 0);
        if (clusterCount > maxClusters) {
            return new Result(
                    by, List.of(), List.of(),
                    forward.nodeCount(), forward.edgeCount(),
                    clusterCount, true, maxClusters, false);
        }
        if (clusterCount == 0) {
            return new Result(
                    by, List.of(), List.of(),
                    forward.nodeCount(), forward.edgeCount(), 0, false, maxClusters, false);
        }

        int[] clusterOf = new int[nodeCount];
        int[] nodesPer = new int[clusterCount];
        for (int node = 0; node < nodeCount; node++) {
            String key = keyByNode[node];
            int ordinal = key == null || key.isBlank()
                    ? unknownOrdinal
                    : ordinals.get(key);
            clusterOf[node] = ordinal;
            nodesPer[ordinal]++;
        }

        long[] internal = new long[clusterCount];
        Map<Long, Long> between = new HashMap<>();
        for (int node = 0; node < nodeCount; node++) {
            if ((node & (CANCEL_CHECK_INTERVAL - 1)) == 0 && cancelled.get()) {
                return Result.cancelled(by, maxClusters);
            }
            int from = clusterOf[node];
            forward.forEachNeighbor(node, to -> {
                int target = clusterOf[to];
                if (target == from) {
                    internal[from]++;
                } else {
                    // Packed rather than a record key: an edge map over a
                    // five-million-edge graph is the one place in this class
                    // where the allocation would show.
                    between.merge(((long) from << 32) | (target & 0xffffffffL), 1L, Long::sum);
                }
            });
        }

        List<Cluster> clusters = new ArrayList<>(clusterCount);
        String[] names = new String[clusterCount];
        for (Map.Entry<String, Integer> entry : ordinals.entrySet()) {
            names[entry.getValue()] = entry.getKey();
        }
        for (int i = 0; i < clusterCount; i++) {
            clusters.add(new Cluster(i, names[i], nodesPer[i], internal[i]));
        }

        List<ClusterEdge> edges = new ArrayList<>(between.size());
        for (Map.Entry<Long, Long> entry : between.entrySet()) {
            long packed = entry.getKey();
            edges.add(new ClusterEdge(
                    (int) (packed >>> 32), (int) (packed & 0xffffffffL), entry.getValue()));
        }
        // HashMap iteration order is not a promise, and plan 13.7 wants
        // deterministic drawings.
        edges.sort((a, b) -> a.from() != b.from()
                ? Integer.compare(a.from(), b.from())
                : Integer.compare(a.to(), b.to()));

        return new Result(
                by, clusters, edges,
                forward.nodeCount(), forward.edgeCount(),
                clusterCount, false, maxClusters, false);
    }

    /**
     * One box in the far-zoom view.
     *
     * @param key the package, label or mnemonic, or null for the cluster of
     *     nodes whose key was never learned
     * @param internalEdges dependencies that begin and end inside this cluster,
     *     which is what makes a package look self-contained or not
     */
    public record Cluster(int ordinal, String key, int nodeCount, long internalEdges) {

        /** True for the cluster of nodes the import could not name. */
        public boolean isUnknown() {
            return key == null;
        }

        /** Plan 11.4: unknown has to read as unknown, not as an empty name. */
        public String displayName() {
            return key == null ? "(name not recorded)" : key;
        }
    }

    /** An aggregated dependency, weighted by how many real edges it stands for. */
    public record ClusterEdge(int from, int to, long weight) {}

    /**
     * A whole graph, grouped.
     *
     * @param clusterCount how many clusters the grouping produced, whether or
     *     not they fitted
     * @param hitLimit true when there were more clusters than the limit, in
     *     which case nothing is returned rather than some of it
     * @param cancelled true when the grouping stopped early, in which case it
     *     grouped nothing at all
     */
    public record Result(
            By by,
            List<Cluster> clusters,
            List<ClusterEdge> edges,
            long totalNodes,
            long totalEdges,
            int clusterCount,
            boolean hitLimit,
            int maxClusters,
            boolean cancelled) {

        public Result {
            clusters = List.copyOf(clusters);
            edges = List.copyOf(edges);
        }

        static Result cancelled(By by, int maxClusters) {
            return new Result(by, List.of(), List.of(), 0, 0, 0, false, maxClusters, true);
        }

        /**
         * The clusters as a subgraph, so a layout and a canvas can draw them.
         *
         * <p>The node ids are cluster <em>ordinals</em>, not graph node indices.
         * Anything that needs a name for one must ask this result, not the
         * session's node lookups — the two numbering schemes are unrelated and
         * a mixed-up lookup would label a box with an arbitrary action.
         */
        public GraphExtract.Result asExtract() {
            List<Integer> nodes = new ArrayList<>(clusters.size());
            for (Cluster cluster : clusters) {
                nodes.add(cluster.ordinal());
            }
            List<GraphExtract.Edge> subgraph = new ArrayList<>(edges.size());
            for (ClusterEdge edge : edges) {
                subgraph.add(new GraphExtract.Edge(edge.from(), edge.to()));
            }
            return new GraphExtract.Result(
                    GraphExtract.Mode.CLUSTERS, nodes, subgraph,
                    totalNodes, totalEdges, hitLimit, maxClusters);
        }

        /** What the view must say alongside the boxes. */
        public String describe() {
            if (hitLimit) {
                return "Grouping " + totalNodes + " actions by " + by.displayName().toLowerCase()
                        + " gives " + clusterCount + " groups, more than the " + maxClusters
                        + " this view draws. Nothing is hidden — group by something coarser,"
                        + " narrow the filter, or raise the limit.";
            }
            return by.displayName() + ": " + clusters.size()
                    + (clusters.size() == 1 ? " group" : " groups")
                    + " covering all " + totalNodes + " actions and "
                    + totalEdges + " dependencies.";
        }

        /**
         * The nodes accounted for, which must equal {@link #totalNodes}.
         *
         * <p>Exposed rather than merely asserted in a test: a caller that draws
         * these numbers should be able to check the arithmetic it is drawing.
         */
        public long clusteredNodes() {
            long sum = 0;
            for (Cluster cluster : clusters) {
                sum += cluster.nodeCount();
            }
            return sum;
        }

        /** The edges accounted for, internal plus between, equal to {@link #totalEdges}. */
        public long clusteredEdges() {
            long sum = 0;
            for (Cluster cluster : clusters) {
                sum += cluster.internalEdges();
            }
            for (ClusterEdge edge : edges) {
                sum += edge.weight();
            }
            return sum;
        }
    }
}
