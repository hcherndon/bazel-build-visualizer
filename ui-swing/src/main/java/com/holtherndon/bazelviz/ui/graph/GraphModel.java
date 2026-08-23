package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import java.awt.Color;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Everything the canvas paints from, and nothing it could query.
 *
 * <h2>The split plan 17.7 requires</h2>
 *
 * <p>"Never query SQLite from {@code paintComponent}." The way that stays true
 * is not vigilance but reachability: this class holds arrays and strings, has no
 * connection, no reader and no service, and {@code GraphPaintIsolationTest}
 * fails if that ever changes. The timeline is built the same way and for the
 * same reason.
 *
 * <h2>Labels are resolved once, off the event thread</h2>
 *
 * <p>A label per drawn node, fetched by the controller in one query rather than
 * one query per node during a paint. Fifty thousand short strings is a few
 * megabytes, which is the cheaper end of the trade against a paint that touches
 * the database at all.
 *
 * <h2>Unknown duration is not zero duration</h2>
 *
 * <p>Rule 11. A node the session never timed — every action a cached build did
 * not run — is coloured as unknown rather than as instant, because "this action
 * took no time" and "nothing measured this action" look identical on a heat
 * scale and mean opposite things.
 */
public final class GraphModel {

    /** The duration array's sentinel for "nothing measured this". */
    public static final long UNKNOWN_DURATION = -1;

    private final GraphLayoutService.Rendered rendered;
    private final String[] labels;
    private final long[] durations;
    private final GraphSpatialIndex index;
    private final long slowestDuration;

    /**
     * Edge endpoints as layout positions: {@code [0]} from, {@code [1]} to.
     *
     * <p>Computed once here rather than in the canvas, because the canvas would
     * be computing it inside {@code paintComponent}. Translating node ids to
     * positions needs a map over every node, and building one per frame is the
     * kind of cost that only shows up as a graph that feels heavy to drag.
     */
    private final int[][] edgePositions;

    /**
     * @param labels one per drawn node, positionally aligned with the layout;
     *     null where the session never learned a name
     * @param durations one per drawn node, {@link #UNKNOWN_DURATION} where
     *     nothing timed it
     */
    private GraphModel(
            GraphLayoutService.Rendered rendered,
            String[] labels,
            long[] durations,
            GraphSpatialIndex index,
            long slowestDuration,
            int[][] edgePositions) {
        this.rendered = rendered;
        this.labels = labels;
        this.durations = durations;
        this.index = index;
        this.slowestDuration = slowestDuration;
        this.edgePositions = edgePositions;
    }

    /**
     * Prepares a drawing.
     *
     * @param labelByNodeIndex the whole session's labels, indexed by graph node
     *     index; a cluster view passes null and is named from its clustering
     * @param durationByNodeIndex the whole session's durations, indexed the
     *     same way, using {@link #UNKNOWN_DURATION} for what was never timed
     */
    public static GraphModel of(
            GraphLayoutService.Rendered rendered,
            String[] labelByNodeIndex,
            long[] durationByNodeIndex) {
        Objects.requireNonNull(rendered, "rendered");
        List<Integer> nodes = rendered.layout().nodes();
        String[] labels = new String[nodes.size()];
        long[] durations = new long[nodes.size()];
        long slowest = 0;

        for (int i = 0; i < nodes.size(); i++) {
            int node = nodes.get(i);
            if (rendered.isCluster()) {
                // Cluster ordinals are not node indices. Looking one up in the
                // session's arrays would label a group with an unrelated
                // action, so a cluster view never touches them.
                GraphClustering.Cluster cluster =
                        rendered.clustering().clusters().get(node);
                labels[i] = cluster.displayName() + "  (" + cluster.nodeCount() + ")";
                durations[i] = UNKNOWN_DURATION;
            } else {
                labels[i] = labelByNodeIndex != null && node < labelByNodeIndex.length
                        ? labelByNodeIndex[node] : null;
                long duration = durationByNodeIndex != null && node < durationByNodeIndex.length
                        ? durationByNodeIndex[node] : UNKNOWN_DURATION;
                durations[i] = duration;
                slowest = Math.max(slowest, duration);
            }
        }
        return new GraphModel(
                rendered, labels, durations, GraphSpatialIndex.of(rendered.layout()),
                slowest, edgesAsPositions(rendered));
    }

    /** An empty drawing, for before a session is open. */
    public static GraphModel empty() {
        GraphLayoutService.Rendered nothing = new GraphLayoutService.Rendered(
                null,
                new GraphExtract.Result(
                        GraphExtract.Mode.NEIGHBOURHOOD, List.of(), List.of(), 0, 0, false, 0),
                GraphLayout.Result.empty(GraphLayout.Kind.LAYERED),
                null,
                "");
        return new GraphModel(
                nothing, new String[0], new long[0],
                GraphSpatialIndex.of(nothing.layout()), 0, new int[][] {new int[0], new int[0]});
    }

    public GraphLayout.Result layout() {
        return rendered.layout();
    }

    public GraphExtract.Result extract() {
        return rendered.extract();
    }

    public GraphSpatialIndex index() {
        return this.index;
    }

    public boolean isCluster() {
        return rendered.isCluster();
    }

    /** The sentence shown beside the drawing; plan 13.6 requires it always. */
    public String description() {
        return rendered.description();
    }

    public int size() {
        return labels.length;
    }

    /** The layout position of a node, or its cluster ordinal in a cluster view. */
    public int nodeAt(int position) {
        return rendered.layout().nodes().get(position);
    }

    /** The label of a drawn node, or null when the session never learned one. */
    public String labelAt(int position) {
        return labels[position];
    }

    /** What a label reads as when there is none; plan 11.4 in one place. */
    public String displayLabelAt(int position) {
        String label = labels[position];
        return label == null ? "(name not recorded)" : label;
    }

    /** How long a node took, or empty when nothing measured it. */
    public OptionalLong durationAt(int position) {
        long duration = durations[position];
        return duration == UNKNOWN_DURATION ? OptionalLong.empty() : OptionalLong.of(duration);
    }

    /**
     * The colour of a node.
     *
     * <p>A heat scale over the slowest thing drawn, so the scale means something
     * relative to what is on screen rather than to an absolute nobody chose. A
     * node nothing timed gets the unknown colour, which is neither end of the
     * scale.
     */
    public Color colourAt(int position) {
        long duration = durations[position];
        if (duration == UNKNOWN_DURATION) {
            return GraphColours.UNKNOWN;
        }
        if (slowestDuration <= 0) {
            return GraphColours.heat(0);
        }
        return GraphColours.heat((double) duration / slowestDuration);
    }

    /** The slowest drawn node, or empty when nothing here was timed. */
    public OptionalLong slowestDuration() {
        return slowestDuration <= 0 ? OptionalLong.empty() : OptionalLong.of(slowestDuration);
    }

    /** How many drawn nodes nothing timed; what the legend has to admit to. */
    public int untimedCount() {
        int count = 0;
        for (long duration : durations) {
            if (duration == UNKNOWN_DURATION) {
                count++;
            }
        }
        return count;
    }

    /**
     * The edges to draw, as layout positions rather than node ids.
     *
     * <p>The returned arrays are the model's own and must not be written to.
     * They are not copied because the canvas reads them once per frame and a
     * defensive copy per frame is exactly the allocation this precomputation
     * exists to remove.
     */
    public int[][] edgePositions() {
        return edgePositions;
    }

    private static int[][] edgesAsPositions(GraphLayoutService.Rendered rendered) {
        List<Integer> nodes = rendered.layout().nodes();
        java.util.Map<Integer, Integer> position = new java.util.HashMap<>(nodes.size() * 2);
        for (int i = 0; i < nodes.size(); i++) {
            position.put(nodes.get(i), i);
        }
        List<GraphExtract.Edge> edges = rendered.extract().edges();
        int[] from = new int[edges.size()];
        int[] to = new int[edges.size()];
        int next = 0;
        for (GraphExtract.Edge edge : edges) {
            Integer start = position.get(edge.from());
            Integer end = position.get(edge.to());
            if (start == null || end == null) {
                continue;
            }
            from[next] = start;
            to[next] = end;
            next++;
        }
        if (next == edges.size()) {
            return new int[][] {from, to};
        }
        return new int[][] {
            java.util.Arrays.copyOf(from, next), java.util.Arrays.copyOf(to, next),
        };
    }
}
