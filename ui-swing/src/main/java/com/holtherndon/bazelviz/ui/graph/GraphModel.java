package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphClustering;
import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.graph.GraphResourceBudget;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
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
public final class GraphModel implements AutoCloseable {

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
  private final long[] actionIds;
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
  private final boolean anyKnownWeight;
  private final boolean anyTimedDuration;
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
  private final SharedModelCharge retainedCharge;
  private boolean closed;

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
      long[] actionIds,
      GraphSpatialIndex index,
      long slowestDuration,
      int[][] edgePositions,
      HierarchyInfo hierarchy,
      GraphWeight weight,
      long[] weightValues,
      boolean weightTruncated,
      String weightNote,
      GraphResourceBudget.Reservation retainedCharge) {
    this.rendered = rendered;
    this.labels = labels;
    this.ownerLabels = ownerLabels;
    this.canvasLabels = canvasLabels;
    this.durations = durations;
    this.actionIds = actionIds;
    this.index = index;
    this.slowestDuration = slowestDuration;
    this.edgePositions = edgePositions;
    this.hierarchy = hierarchy;
    this.weight = weight;
    this.weightValues = weightValues;
    long max = 0;
    boolean knownWeight = false;
    for (long value : weightValues) {
      if (value != UNKNOWN_DURATION) {
        knownWeight = true;
        max = Math.max(max, value);
      }
    }
    this.maxWeight = max;
    this.anyKnownWeight = knownWeight;
    boolean timedDuration = false;
    for (long duration : durations) {
      timedDuration |= duration != UNKNOWN_DURATION;
    }
    this.anyTimedDuration = timedDuration;
    this.weightTruncated = weightTruncated;
    this.weightNote = weightNote == null ? "" : weightNote;
    this.retainedCharge = retainedCharge == null ? null : new SharedModelCharge(retainedCharge);
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

  /** Builds from metadata already aligned to the bounded extraction, never to the whole graph. */
  static GraphModel ofAligned(
      GraphLayoutService.Rendered rendered, GraphQueries.NodeMetadata metadata) {
    Objects.requireNonNull(rendered, "rendered");
    Objects.requireNonNull(metadata, "metadata");
    ModelAdmission admission = null;
    try {
      admission = admitAligned(rendered, metadata.displayLabels(), metadata.ownerLabels());
      GraphModel model =
          build(
              rendered,
              metadata.displayLabels(),
              metadata.ownerLabels(),
              metadata.durations(),
              metadata.actionIds(),
              true,
              admission.retained());
      admission.transfer();
      return model;
    } catch (RuntimeException failure) {
      rendered.close();
      throw failure;
    } finally {
      if (admission != null) {
        admission.close();
      }
      metadata.close();
    }
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
    ModelAdmission admission = null;
    try {
      admission = admit(rendered, labelByNodeIndex, ownerByNodeIndex);
      GraphModel model =
          build(
              rendered,
              labelByNodeIndex,
              ownerByNodeIndex,
              durationByNodeIndex,
              null,
              false,
              admission.retained());
      admission.transfer();
      return model;
    } catch (RuntimeException failure) {
      rendered.close();
      throw failure;
    } finally {
      if (admission != null) {
        admission.close();
      }
    }
  }

  private static GraphModel build(
      GraphLayoutService.Rendered rendered,
      String[] labelByNodeIndex,
      String[] ownerByNodeIndex,
      long[] durationByNodeIndex,
      long[] actionIdByNodeIndex,
      boolean aligned,
      GraphResourceBudget.Reservation retainedCharge) {
    List<Integer> nodes = rendered.layout().nodes();
    String[] labels = new String[nodes.size()];
    String[] owners = new String[nodes.size()];
    String[] canvasLabels = new String[nodes.size()];
    long[] durations = new long[nodes.size()];
    long[] actionIds = new long[nodes.size()];
    Arrays.fill(actionIds, -1);
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
        int metadataIndex = aligned ? i : node;
        labels[i] =
            labelByNodeIndex != null && metadataIndex < labelByNodeIndex.length
                ? labelByNodeIndex[metadataIndex]
                : null;
        owners[i] =
            ownerByNodeIndex != null && metadataIndex < ownerByNodeIndex.length
                ? ownerByNodeIndex[metadataIndex]
                : null;
        canvasLabels[i] = canvasLabel(labels[i], owners[i]);
        long duration =
            durationByNodeIndex != null && metadataIndex < durationByNodeIndex.length
                ? durationByNodeIndex[metadataIndex]
                : UNKNOWN_DURATION;
        durations[i] = duration;
        slowest = Math.max(slowest, duration);
        if (actionIdByNodeIndex != null && metadataIndex < actionIdByNodeIndex.length) {
          actionIds[i] = actionIdByNodeIndex[metadataIndex];
        }
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
        actionIds,
        GraphSpatialIndex.of(rendered.layout()),
        slowest,
        edges,
        hierarchy,
        GraphWeight.DURATION,
        durations,
        false,
        "",
        retainedCharge);
  }

  /**
   * The same drawing restyled by another weight, without touching the layout.
   *
   * <p>Positions, labels, durations, the spatial index and the edge arrays are shared with this
   * model — the weight drives visual encoding only, so re-selecting a weight is a re-render and
   * never a re-layout.
   *
   * @param alignedValues the weight per rendered position; unknown entries are drawn grey at base
   *     size, never as zero
   */
  public GraphModel withWeights(
      GraphWeight newWeight, long[] alignedValues, boolean truncated, String note) {
    GraphLayoutService.Rendered retainedRendering = rendered.retain();
    ModelAdmission admission = null;
    try {
      admission =
          admitRestyle(
              retainedRendering,
              size(),
              edgePositions[0].length,
              retainedStringBytes(labels, ownerLabels, canvasLabels));
      List<Integer> nodes = rendered.layout().nodes();
      if (alignedValues.length != nodes.size()) {
        throw new IllegalArgumentException(
            "weight values must align with the rendered node positions");
      }
      long[] values = new long[nodes.size()];
      if (!isCluster()) {
        System.arraycopy(alignedValues, 0, values, 0, values.length);
      } else {
        Arrays.fill(values, UNKNOWN_DURATION);
      }
      GraphModel model =
          new GraphModel(
              retainedRendering,
              labels,
              ownerLabels,
              canvasLabels,
              durations,
              actionIds,
              index,
              slowestDuration,
              edgePositions,
              hierarchy,
              newWeight,
              values,
              truncated,
              note,
              admission.retained());
      admission.transfer();
      return model;
    } catch (RuntimeException failure) {
      retainedRendering.close();
      throw failure;
    } finally {
      if (admission != null) {
        admission.close();
      }
    }
  }

  /** Back to the duration encoding, sharing everything but the weight. */
  public GraphModel withDurationWeight() {
    GraphLayoutService.Rendered retainedRendering = rendered.retain();
    ModelAdmission admission = null;
    try {
      admission =
          admitRestyle(
              retainedRendering,
              size(),
              edgePositions[0].length,
              retainedStringBytes(labels, ownerLabels, canvasLabels));
      GraphModel model =
          new GraphModel(
              retainedRendering,
              labels,
              ownerLabels,
              canvasLabels,
              durations,
              actionIds,
              index,
              slowestDuration,
              edgePositions,
              hierarchy,
              GraphWeight.DURATION,
              durations,
              false,
              "",
              admission.retained());
      admission.transfer();
      return model;
    } catch (RuntimeException failure) {
      retainedRendering.close();
      throw failure;
    } finally {
      if (admission != null) {
        admission.close();
      }
    }
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
        new long[0],
        GraphSpatialIndex.of(nothing.layout()),
        0,
        new int[][] {new int[0], new int[0]},
        HierarchyInfo.empty(0, 0),
        GraphWeight.DURATION,
        new long[0],
        false,
        "",
        null);
  }

  private static ModelAdmission admit(
      GraphLayoutService.Rendered rendered, String[] labels, String[] owners) {
    GraphResourceBudget budget = rendered.budget();
    if (budget == null) {
      return ModelAdmission.none();
    }
    long nodes = rendered.layout().size();
    long edges = rendered.extract().edges().size();
    long stringBytes = metadataStringBytes(rendered, labels, owners);
    long retained = estimateModelBytes(8_192, nodes, 160, edges, 64, stringBytes);
    long scratch = estimateModelBytes(8_192, nodes, 160, edges, 48, 0);
    try {
      List<GraphResourceBudget.Reservation> reservations =
          budget.reserveAll(
              List.of(
                  new GraphResourceBudget.Request(
                      retained, "retained graph model and spatial index"),
                  new GraphResourceBudget.Request(scratch, "graph model preparation scratch")));
      return new ModelAdmission(reservations.get(0), reservations.get(1));
    } catch (GraphResourceBudget.RefusedException refused) {
      throw new ModelRefusedException(refused.getMessage(), refused);
    }
  }

  private static ModelAdmission admitAligned(
      GraphLayoutService.Rendered rendered, String[] labels, String[] owners) {
    GraphResourceBudget budget = rendered.budget();
    if (budget == null) {
      return ModelAdmission.none();
    }
    long nodes = rendered.layout().size();
    long edges = rendered.extract().edges().size();
    long retained =
        estimateModelBytes(
            8_192, nodes, 160, edges, 64, alignedMetadataStringBytes(labels, owners));
    long scratch = estimateModelBytes(8_192, nodes, 160, edges, 48, 0);
    try {
      List<GraphResourceBudget.Reservation> reservations =
          budget.reserveAll(
              List.of(
                  new GraphResourceBudget.Request(
                      retained, "retained graph model and spatial index"),
                  new GraphResourceBudget.Request(scratch, "graph model preparation scratch")));
      return new ModelAdmission(reservations.get(0), reservations.get(1));
    } catch (GraphResourceBudget.RefusedException refused) {
      throw new ModelRefusedException(refused.getMessage(), refused);
    }
  }

  private static ModelAdmission admitRestyle(
      GraphLayoutService.Rendered rendered, long nodes, long edges, long stringBytes) {
    GraphResourceBudget budget = rendered.budget();
    if (budget == null) {
      return ModelAdmission.none();
    }
    // Restyled models share the original arrays. Charge their full conservative retained size so
    // replacing and closing the original model cannot make still-live shared arrays unaccounted.
    long retained = estimateModelBytes(8_192, nodes, 160, edges, 64, stringBytes);
    long scratch = estimateModelBytes(1_024, nodes, 16, edges, 1, 0);
    try {
      List<GraphResourceBudget.Reservation> reservations =
          budget.reserveAll(
              List.of(
                  new GraphResourceBudget.Request(retained, "retained graph weight rendering"),
                  new GraphResourceBudget.Request(scratch, "graph weight rendering scratch")));
      return new ModelAdmission(reservations.get(0), reservations.get(1));
    } catch (GraphResourceBudget.RefusedException refused) {
      throw new ModelRefusedException(refused.getMessage(), refused);
    }
  }

  private static long metadataStringBytes(
      GraphLayoutService.Rendered rendered, String[] labels, String[] owners) {
    long bytes = 0;
    for (int position = 0; position < rendered.layout().size(); position++) {
      int node = rendered.layout().nodes().get(position);
      if (rendered.isCluster()) {
        GraphClustering.Cluster cluster = rendered.clustering().clusters().get(node);
        bytes =
            addStringLengthBytes(
                bytes, cluster.displayName().length() + 4L + decimalDigits(cluster.nodeCount()));
      } else {
        String label = null;
        String owner = null;
        if (labels != null && node >= 0 && node < labels.length) {
          label = labels[node];
          bytes = addStringBytes(bytes, label);
        }
        if (owners != null && node >= 0 && node < owners.length) {
          owner = owners[node];
          bytes = addStringBytes(bytes, owner);
        }
        if (owner != null && !owner.equals(label)) {
          int labelLength = label == null ? "(name not recorded)".length() : label.length();
          bytes =
              addStringLengthBytes(
                  bytes, labelLength + "  ·  target ".length() + (long) owner.length());
        }
      }
    }
    return bytes;
  }

  private static long retainedStringBytes(String[]... arrays) {
    long bytes = 0;
    for (String[] values : arrays) {
      for (String value : values) {
        bytes = addStringBytes(bytes, value);
      }
    }
    return bytes;
  }

  private static long alignedMetadataStringBytes(String[] labels, String[] owners) {
    long bytes = retainedStringBytes(labels, owners);
    for (int index = 0; index < labels.length; index++) {
      String label = labels[index];
      String owner = owners[index];
      if (owner != null && !owner.equals(label)) {
        int labelLength = label == null ? "(name not recorded)".length() : label.length();
        bytes =
            addStringLengthBytes(
                bytes, labelLength + "  ·  target ".length() + (long) owner.length());
      }
    }
    return bytes;
  }

  private static long addStringBytes(long total, String value) {
    if (value == null) {
      return total;
    }
    return addStringLengthBytes(total, value.length());
  }

  private static long addStringLengthBytes(long total, long characters) {
    try {
      return Math.addExact(total, Math.addExact(48, Math.multiplyExact(characters, 2L)));
    } catch (ArithmeticException overflow) {
      throw new ModelRefusedException(
          "Graph model strings are too large to account safely.", overflow);
    }
  }

  private static int decimalDigits(int value) {
    return Integer.toString(value).length();
  }

  private static long estimateModelBytes(
      long fixed, long nodes, long nodeBytes, long edges, long edgeBytes, long stringBytes) {
    try {
      return Math.addExact(
          Math.addExact(fixed, stringBytes),
          Math.addExact(
              Math.multiplyExact(nodes, nodeBytes), Math.multiplyExact(edges, edgeBytes)));
    } catch (ArithmeticException overflow) {
      throw new ModelRefusedException("Graph model is too large to account safely.", overflow);
    }
  }

  /** Keeps this model and its rendering charged while queued asynchronous work reads them. */
  synchronized Lease lease() {
    if (closed) {
      throw new IllegalStateException("graph model is closed");
    }
    GraphLayoutService.Rendered rendering = rendered.retain();
    if (retainedCharge != null) {
      retainedCharge.retain();
    }
    return new Lease(this, rendering, retainedCharge);
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    rendered.close();
    if (retainedCharge != null) {
      retainedCharge.release();
    }
  }

  /** A separately closeable reference to one immutable model. */
  static final class Lease implements AutoCloseable {
    private final GraphModel model;
    private final GraphLayoutService.Rendered rendering;
    private final SharedModelCharge charge;
    private boolean closed;

    private Lease(
        GraphModel model, GraphLayoutService.Rendered rendering, SharedModelCharge charge) {
      this.model = model;
      this.rendering = rendering;
      this.charge = charge;
    }

    GraphModel model() {
      if (closed) {
        throw new IllegalStateException("graph model lease is closed");
      }
      return model;
    }

    @Override
    public synchronized void close() {
      if (closed) {
        return;
      }
      closed = true;
      rendering.close();
      if (charge != null) {
        charge.release();
      }
    }
  }

  private static final class SharedModelCharge {
    private final GraphResourceBudget.Reservation reservation;
    private int references = 1;

    private SharedModelCharge(GraphResourceBudget.Reservation reservation) {
      this.reservation = reservation;
    }

    synchronized void retain() {
      if (references == 0) {
        throw new IllegalStateException("graph model charge is released");
      }
      references++;
    }

    synchronized void release() {
      if (references <= 0) {
        throw new IllegalStateException("graph model charge released more than once");
      }
      references--;
      if (references == 0) {
        reservation.close();
      }
    }
  }

  /** Visible refusal raised before model or spatial arrays are allocated. */
  public static final class ModelRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ModelRefusedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class ModelAdmission implements AutoCloseable {
    private GraphResourceBudget.Reservation retained;
    private final GraphResourceBudget.Reservation scratch;

    ModelAdmission(
        GraphResourceBudget.Reservation retained, GraphResourceBudget.Reservation scratch) {
      this.retained = retained;
      this.scratch = scratch;
    }

    static ModelAdmission none() {
      return new ModelAdmission(null, null);
    }

    GraphResourceBudget.Reservation retained() {
      return retained;
    }

    void transfer() {
      retained = null;
    }

    @Override
    public void close() {
      if (scratch != null) {
        scratch.close();
      }
      if (retained != null) {
        retained.close();
      }
    }
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

  /** Target label for entity navigation, whether it is also the node's display name or not. */
  public Optional<String> targetLabelAt(int position) {
    String owner = ownerLabels[position];
    String label = owner == null ? labels[position] : owner;
    return label == null || label.isBlank() ? Optional.empty() : Optional.of(label);
  }

  /** How long a node took, or empty when nothing measured it. */
  public OptionalLong durationAt(int position) {
    long duration = durations[position];
    return duration == UNKNOWN_DURATION ? OptionalLong.empty() : OptionalLong.of(duration);
  }

  /** Executed action behind a drawn action node, when correlation recorded one. */
  public OptionalLong actionIdAt(int position) {
    long actionId = actionIds[position];
    return actionId < 0 ? OptionalLong.empty() : OptionalLong.of(actionId);
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
    return anyKnownWeight ? OptionalLong.of(maxWeight) : OptionalLong.empty();
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
    return anyTimedDuration ? OptionalLong.of(slowestDuration) : OptionalLong.empty();
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
