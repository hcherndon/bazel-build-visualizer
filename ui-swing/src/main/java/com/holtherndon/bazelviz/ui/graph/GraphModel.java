package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Everything the canvas paints from, and nothing it could query.
 *
 * <h2>The split plan 17.7 requires</h2>
 *
 * <p>"Never query SQLite from {@code paintComponent}." The way that stays true is not vigilance but
 * reachability: this class holds arrays and strings, has no connection, no reader and no service,
 * and {@code GraphPaintIsolationTest} fails if that ever changes. The timeline is built the same
 * way and for the same reason.
 *
 * <h2>Labels are resolved once, off the event thread</h2>
 *
 * <p>A label per drawn node, fetched by the controller in one query rather than one query per node
 * during a paint. Fifty thousand short strings is a few megabytes, which is the cheaper end of the
 * trade against a paint that touches the database at all.
 *
 * <h2>Unknown duration is not zero duration</h2>
 *
 * <p>Rule 11. A node the session never timed — every action a cached build did not run — is
 * coloured as unknown rather than as instant, because "this action took no time" and "nothing
 * measured this action" look identical on a heat scale and mean opposite things.
 */
public final class GraphModel {

  /** The duration array's sentinel for "nothing measured this". */
  public static final long UNKNOWN_DURATION = -1;

  /** The floor and span of the weight-driven node-radius multiplier. */
  static final double MIN_RADIUS_SCALE = 0.65;

  static final double MAX_RADIUS_SCALE = 2.0;

  /** How many edge-thickness steps the canvas draws. */
  static final int EDGE_BUCKETS = 4;

  private final GraphLayoutService.Rendered rendered;
  private final String[] labels;
  private final String[] ownerLabels;
  private final String[] canvasLabels;
  private final long[] durations;
  private final GraphSpatialIndex index;
  private final long slowestDuration;

  /**
   * The selected weight and its per-position values.
   *
   * <p>The weight drives colour, node radius and edge thickness — and only those. Positions belong
   * to the layout, so swapping the weight swaps these arrays and nothing else; {@code
   * GraphWeight.UNKNOWN}-style absent values use {@link #UNKNOWN_DURATION}'s sentinel value, and
   * are drawn grey at base size rather than as the smallest, coldest thing.
   */
  private final GraphWeight weight;

  private final long[] weightValues;
  private final long maxWeight;
  private final boolean weightTruncated;
  private final String weightNote;
  private final double[] radiusScales;
  private final byte[] edgeBuckets;
  private final int maxEdgeBucket;

  /**
   * Edge endpoints as layout positions: {@code [0]} from, {@code [1]} to.
   *
   * <p>Computed once here rather than in the canvas, because the canvas would be computing it
   * inside {@code paintComponent}. Translating node ids to positions needs a map over every node,
   * and building one per frame is the kind of cost that only shows up as a graph that feels heavy
   * to drag.
   */
  private final int[][] edgePositions;

  private final HierarchyInfo hierarchy;

  /**
   * @param labels one per drawn node, positionally aligned with the layout; null where the session
   *     never learned a name
   * @param durations one per drawn node, {@link #UNKNOWN_DURATION} where nothing timed it
   */
  private GraphModel(
      GraphLayoutService.Rendered rendered,
      String[] labels,
      String[] ownerLabels,
      String[] canvasLabels,
      long[] durations,
      GraphSpatialIndex index,
      long slowestDuration,
      int[][] edgePositions,
      HierarchyInfo hierarchy,
      GraphWeight weight,
      long[] weightValues,
      boolean weightTruncated,
      String weightNote) {
    this.rendered = rendered;
    this.labels = labels;
    this.ownerLabels = ownerLabels;
    this.canvasLabels = canvasLabels;
    this.durations = durations;
    this.index = index;
    this.slowestDuration = slowestDuration;
    this.edgePositions = edgePositions;
    this.hierarchy = hierarchy;
    this.weight = weight;
    this.weightValues = weightValues;
    long max = 0;
    for (long value : weightValues) {
      max = Math.max(max, value);
    }
    this.maxWeight = max;
    this.weightTruncated = weightTruncated;
    this.weightNote = weightNote == null ? "" : weightNote;
    this.radiusScales = radiusScalesFor(weightValues, max);
    this.edgeBuckets = edgeBucketsFor(edgePositions, weightValues, max);
    int highest = 0;
    for (byte bucket : this.edgeBuckets) {
      highest = Math.max(highest, bucket);
    }
    this.maxEdgeBucket = highest;
  }

  /**
   * Prepares a drawing.
   *
   * @param labelByNodeIndex the whole session's labels, indexed by graph node index; a cluster view
   *     passes null and is named from its clustering
   * @param durationByNodeIndex the whole session's durations, indexed the same way, using {@link
   *     #UNKNOWN_DURATION} for what was never timed
   */
  public static GraphModel of(
      GraphLayoutService.Rendered rendered, String[] labelByNodeIndex, long[] durationByNodeIndex) {
    return of(rendered, labelByNodeIndex, null, durationByNodeIndex);
  }

  /**
   * Prepares a drawing with an action name and its owning target kept distinct.
   *
   * <p>The action name tells sibling actions apart; the owner answers which target the node belongs
   * to. The configured-target graph passes the same label for both, which is collapsed rather than
   * repeated.
   */
  public static GraphModel of(
      GraphLayoutService.Rendered rendered,
      String[] labelByNodeIndex,
      String[] ownerByNodeIndex,
      long[] durationByNodeIndex) {
    Objects.requireNonNull(rendered, "rendered");
    List<Integer> nodes = rendered.layout().nodes();
    String[] labels = new String[nodes.size()];
    String[] owners = new String[nodes.size()];
    String[] canvasLabels = new String[nodes.size()];
    long[] durations = new long[nodes.size()];
    long slowest = 0;

    for (int i = 0; i < nodes.size(); i++) {
      int node = nodes.get(i);
      if (rendered.isCluster()) {
        // Cluster ordinals are not node indices. Looking one up in the
        // session's arrays would label a group with an unrelated
        // action, so a cluster view never touches them.
        GraphClustering.Cluster cluster = rendered.clustering().clusters().get(node);
        labels[i] = cluster.displayName() + "  (" + cluster.nodeCount() + ")";
        canvasLabels[i] = labels[i];
        durations[i] = UNKNOWN_DURATION;
      } else {
        labels[i] =
            labelByNodeIndex != null && node < labelByNodeIndex.length
                ? labelByNodeIndex[node]
                : null;
        owners[i] =
            ownerByNodeIndex != null && node < ownerByNodeIndex.length
                ? ownerByNodeIndex[node]
                : null;
        canvasLabels[i] = canvasLabel(labels[i], owners[i]);
        long duration =
            durationByNodeIndex != null && node < durationByNodeIndex.length
                ? durationByNodeIndex[node]
                : UNKNOWN_DURATION;
        durations[i] = duration;
        slowest = Math.max(slowest, duration);
      }
    }
    int[][] edges = edgesAsPositions(rendered);
    HierarchyInfo hierarchy = hierarchyInfo(rendered.layout(), rendered.extract().mode(), edges);
    return new GraphModel(
        rendered,
        labels,
        owners,
        canvasLabels,
        durations,
        GraphSpatialIndex.of(rendered.layout()),
        slowest,
        edges,
        hierarchy,
        GraphWeight.DURATION,
        durations,
        false,
        "");
  }

  /**
   * The same drawing restyled by another weight, without touching the layout.
   *
   * <p>Positions, labels, durations, the spatial index and the edge arrays are shared with this
   * model — the weight drives visual encoding only, so re-selecting a weight is a re-render and
   * never a re-layout.
   *
   * @param valueByNode the weight per graph node index; nodes absent from the map are unknown,
   *     drawn grey at base size, never as zero
   */
  public GraphModel withWeights(
      GraphWeight newWeight, Map<Integer, Long> valueByNode, boolean truncated, String note) {
    List<Integer> nodes = rendered.layout().nodes();
    long[] values = new long[nodes.size()];
    Arrays.fill(values, UNKNOWN_DURATION);
    if (!isCluster()) {
      for (int i = 0; i < nodes.size(); i++) {
        Long value = valueByNode.get(nodes.get(i));
        if (value != null && value >= 0) {
          values[i] = value;
        }
      }
    }
    return new GraphModel(
        rendered,
        labels,
        ownerLabels,
        canvasLabels,
        durations,
        index,
        slowestDuration,
        edgePositions,
        hierarchy,
        newWeight,
        values,
        truncated,
        note);
  }

  /** Back to the duration encoding, sharing everything but the weight. */
  public GraphModel withDurationWeight() {
    return new GraphModel(
        rendered,
        labels,
        ownerLabels,
        canvasLabels,
        durations,
        index,
        slowestDuration,
        edgePositions,
        hierarchy,
        GraphWeight.DURATION,
        durations,
        false,
        "");
  }

  /** An empty drawing, for before a session is open. */
  public static GraphModel empty() {
    GraphLayoutService.Rendered nothing =
        new GraphLayoutService.Rendered(
            null,
            new GraphExtract.Result(
                GraphExtract.Mode.NEIGHBOURHOOD, List.of(), List.of(), 0, 0, 0, 0, false, false),
            GraphLayout.Result.empty(GraphLayout.Kind.HIERARCHY),
            null,
            "");
    return new GraphModel(
        nothing,
        new String[0],
        new String[0],
        new String[0],
        new long[0],
        GraphSpatialIndex.of(nothing.layout()),
        0,
        new int[][] {new int[0], new int[0]},
        HierarchyInfo.empty(0, 0),
        GraphWeight.DURATION,
        new long[0],
        false,
        "");
  }

  public GraphLayout.Result layout() {
    return rendered.layout();
  }

  /** What one node is in user-facing copy. */
  public String nodeNoun() {
    return rendered.request() == null
        ? "node"
        : GraphLayoutService.nounFor(rendered.request().graph());
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

  /** The grouping behind a cluster view, or null when this is a node view. */
  public GraphClustering.Result clustering() {
    return rendered.clustering();
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

  /** What a label reads as when there is none; plan 11.4 in one place. */
  public String displayLabelAt(int position) {
    String label = labels[position];
    return label == null ? "(name not recorded)" : label;
  }

  /**
   * The label painted and shown in the tooltip, including target ownership when it adds information
   * beyond the action's own name.
   */
  public String canvasLabelAt(int position) {
    String label = canvasLabels[position];
    return label == null ? "(name and target not recorded)" : label;
  }

  /** The owning target, when it is known and distinct from the node name. */
  public Optional<String> ownerLabelAt(int position) {
    String owner = ownerLabels[position];
    return owner == null || owner.equals(labels[position]) ? Optional.empty() : Optional.of(owner);
  }

  /** How long a node took, or empty when nothing measured it. */
  public OptionalLong durationAt(int position) {
    long duration = durations[position];
    return duration == UNKNOWN_DURATION ? OptionalLong.empty() : OptionalLong.of(duration);
  }

  /**
   * The colour of a node.
   *
   * <p>A heat scale over the largest weight drawn, so the scale means something relative to what is
   * on screen rather than to an absolute nobody chose. A node with no known weight gets the unknown
   * colour, which is neither end of the scale.
   */
  public Color colourAt(int position) {
    long value = weightValues[position];
    if (value == UNKNOWN_DURATION) {
      return GraphColours.UNKNOWN;
    }
    if (maxWeight <= 0) {
      return GraphColours.heat(0);
    }
    return GraphColours.heat((double) value / maxWeight);
  }

  /** The selected weight this model is encoded by. */
  public GraphWeight weight() {
    return weight;
  }

  /** A node's weight value, or empty when nothing computed one. */
  public OptionalLong weightAt(int position) {
    long value = weightValues[position];
    return value == UNKNOWN_DURATION ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /** The largest drawn weight, or empty when nothing here has one. */
  public OptionalLong maxWeight() {
    return maxWeight <= 0 ? OptionalLong.empty() : OptionalLong.of(maxWeight);
  }

  /** How many drawn nodes have no weight value; the legend has to admit it. */
  public int unweightedCount() {
    int count = 0;
    for (long value : weightValues) {
      if (value == UNKNOWN_DURATION) {
        count++;
      }
    }
    return count;
  }

  /** True when a budget stopped the weight computation before every node. */
  public boolean weightTruncated() {
    return weightTruncated;
  }

  /** The weight computation's own sentence for the legend, or empty. */
  public String weightNote() {
    return weightNote;
  }

  /**
   * The weight-driven node-radius multiplier, in [{@link #MIN_RADIUS_SCALE}, {@link
   * #MAX_RADIUS_SCALE}].
   *
   * <p>Square-root scaled so a node twice the weight reads as visibly, not absurdly, bigger.
   * Unknown weights sit at 1.0 — base size — because a node the computation could not reach must
   * not be drawn as the smallest thing on screen.
   */
  public double radiusScaleAt(int position) {
    return radiusScales[position];
  }

  /**
   * An edge's thickness bucket, {@code 0} (thin) to {@code EDGE_BUCKETS - 1}.
   *
   * <p>Buckets rather than continuous widths so the canvas pays for a handful of stroke changes per
   * frame instead of one per edge.
   */
  public int edgeBucketAt(int edge) {
    return edgeBuckets[edge];
  }

  /**
   * The highest bucket any edge uses; the canvas draws one pass per bucket up to this, so an
   * unweighted drawing costs exactly one pass, as before.
   */
  public int maxEdgeBucket() {
    return maxEdgeBucket;
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
   * <p>The returned arrays are the model's own and must not be written to. They are not copied
   * because the canvas reads them once per frame and a defensive copy per frame is exactly the
   * allocation this precomputation exists to remove.
   */
  public int[][] edgePositions() {
    return edgePositions;
  }

  /** True when this dependency is one of the hierarchy's primary branches. */
  public boolean isHierarchyEdge(int edge) {
    return hierarchy.primary()[edge];
  }

  /** Primary branches in the hierarchy; every other real edge is a cross-link. */
  public int hierarchyEdgeCount() {
    return hierarchy.primaryCount();
  }

  public int crossLinkCount() {
    return hierarchy.crossCount();
  }

  /** The edge-array position of one primary branch, without exposing its index array. */
  int hierarchyEdgeAt(int ordinal) {
    return hierarchy.primaryEdges()[ordinal];
  }

  /** The edge-array position of one cross-link, without an all-edge scan. */
  int crossLinkEdgeAt(int ordinal) {
    return hierarchy.crossEdges()[ordinal];
  }

  /** Cross-links incident to one position; used to reveal a single selected node. */
  int crossLinkDegreeAt(int position) {
    int[] offsets = hierarchy.crossOffsets();
    if (position < 0 || position + 1 >= offsets.length) {
      return 0;
    }
    return offsets[position + 1] - offsets[position];
  }

  /** One incident cross-link's edge-array position. */
  int incidentCrossLinkAt(int position, int ordinal) {
    return hierarchy.incidentCrossEdges()[hierarchy.crossOffsets()[position] + ordinal];
  }

  /** Exact cross-links touching at least one selected node, without an all-edge scan. */
  int crossLinksTouching(Set<Integer> selected) {
    if (selected.isEmpty() || hierarchy.crossCount() == 0) {
      return 0;
    }
    int revealed = 0;
    int[] offsets = hierarchy.crossOffsets();
    int[] incident = hierarchy.incidentCrossEdges();
    for (int position : selected) {
      if (position < 0 || position + 1 >= offsets.length) {
        continue;
      }
      for (int at = offsets[position]; at < offsets[position + 1]; at++) {
        int edge = incident[at];
        int from = edgePositions[0][edge];
        int to = edgePositions[1][edge];
        int other = from == position ? to : from;
        if (other == position || !selected.contains(other) || position < other) {
          revealed++;
        }
      }
    }
    return revealed;
  }

  private static String canvasLabel(String label, String owner) {
    if (owner == null) {
      return label;
    }
    if (owner.equals(label)) {
      return label;
    }
    return (label == null ? "(name not recorded)" : label) + "  ·  target " + owner;
  }

  private static double[] radiusScalesFor(long[] values, long max) {
    double[] scales = new double[values.length];
    Arrays.fill(scales, 1.0);
    if (max <= 0) {
      return scales;
    }
    for (int i = 0; i < values.length; i++) {
      if (values[i] != UNKNOWN_DURATION) {
        scales[i] =
            MIN_RADIUS_SCALE
                + (MAX_RADIUS_SCALE - MIN_RADIUS_SCALE) * Math.sqrt((double) values[i] / max);
      }
    }
    return scales;
  }

  /**
   * One thickness bucket per edge, from the mean of its known endpoints' weight fractions. Edges
   * whose endpoints are both unknown stay in the thinnest bucket, which draws exactly as every edge
   * drew before weights existed.
   */
  private static byte[] edgeBucketsFor(int[][] edgePositions, long[] values, long max) {
    byte[] buckets = new byte[edgePositions[0].length];
    if (max <= 0) {
      return buckets;
    }
    for (int e = 0; e < buckets.length; e++) {
      long from = values[edgePositions[0][e]];
      long to = values[edgePositions[1][e]];
      double sum = 0;
      int known = 0;
      if (from != UNKNOWN_DURATION) {
        sum += (double) from / max;
        known++;
      }
      if (to != UNKNOWN_DURATION) {
        sum += (double) to / max;
        known++;
      }
      if (known == 0) {
        continue;
      }
      double fraction = sum / known;
      buckets[e] = (byte) Math.min(EDGE_BUCKETS - 1, (int) (fraction * EDGE_BUCKETS));
    }
    return buckets;
  }

  private static int[][] edgesAsPositions(GraphLayoutService.Rendered rendered) {
    List<Integer> nodes = rendered.layout().nodes();
    Map<Integer, Integer> position = new HashMap<>(nodes.size() * 2);
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
      Arrays.copyOf(from, next), Arrays.copyOf(to, next),
    };
  }

  private static HierarchyInfo hierarchyInfo(
      GraphLayout.Result layout, GraphExtract.Mode mode, int[][] edges) {
    if (layout.kind() != GraphLayout.Kind.HIERARCHY) {
      return HierarchyInfo.empty(layout.size(), edges[0].length);
    }
    boolean[] primary = new boolean[edges[0].length];
    boolean[] claimedChild = new boolean[layout.size()];
    int primaryCount = 0;
    int[] crossDegree = new int[layout.size()];
    for (int edge = 0; edge < primary.length; edge++) {
      int from = edges[0][edge];
      int to = edges[1][edge];
      int child = primaryChild(layout, mode, from, to);
      primary[edge] = child >= 0 && !claimedChild[child];
      if (primary[edge]) {
        claimedChild[child] = true;
        primaryCount++;
      } else {
        crossDegree[from]++;
        if (to != from) {
          crossDegree[to]++;
        }
      }
    }
    int[] offsets = new int[layout.size() + 1];
    for (int node = 0; node < layout.size(); node++) {
      offsets[node + 1] = offsets[node] + crossDegree[node];
    }
    int[] incident = new int[offsets[layout.size()]];
    int[] cursor = offsets.clone();
    for (int edge = 0; edge < primary.length; edge++) {
      if (primary[edge]) {
        continue;
      }
      int from = edges[0][edge];
      int to = edges[1][edge];
      incident[cursor[from]++] = edge;
      if (to != from) {
        incident[cursor[to]++] = edge;
      }
    }
    int[] primaryEdges = new int[primaryCount];
    int[] crossEdges = new int[primary.length - primaryCount];
    int primaryAt = 0;
    int crossAt = 0;
    for (int edge = 0; edge < primary.length; edge++) {
      if (primary[edge]) {
        primaryEdges[primaryAt++] = edge;
      } else {
        crossEdges[crossAt++] = edge;
      }
    }
    return new HierarchyInfo(
        primary,
        primaryEdges,
        crossEdges,
        primaryCount,
        primary.length - primaryCount,
        offsets,
        incident);
  }

  /**
   * The child this real directed edge discovered, or -1 when it is not that parent/child branch.
   * Only one edge may claim a child, so reciprocal and parallel edges remain explicit cross-links
   * rather than duplicate branches.
   */
  private static int primaryChild(
      GraphLayout.Result layout, GraphExtract.Mode mode, int from, int to) {
    return switch (mode) {
      // Dependency traversal reverses producer→consumer edges: the
      // producer child points to its consumer parent in the real graph.
      case DEPENDENCIES -> layout.parentAt(from) == to ? from : -1;
      // Neighbourhood placement deliberately ignores direction.
      case NEIGHBOURHOOD -> {
        if (layout.parentAt(to) == from) {
          yield to;
        }
        yield layout.parentAt(from) == to ? from : -1;
      }
      // These traversals follow the stored producer→consumer direction.
      case DEPENDENTS, WHOLE, PATH, CRITICAL_PATH, CLUSTERS ->
          layout.parentAt(to) == from ? to : -1;
    };
  }

  private record HierarchyInfo(
      boolean[] primary,
      int[] primaryEdges,
      int[] crossEdges,
      int primaryCount,
      int crossCount,
      int[] crossOffsets,
      int[] incidentCrossEdges) {

    private static HierarchyInfo empty(int nodes, int edges) {
      return new HierarchyInfo(
          new boolean[edges], new int[0], new int[0], 0, 0, new int[nodes + 1], new int[0]);
    }
  }
}
