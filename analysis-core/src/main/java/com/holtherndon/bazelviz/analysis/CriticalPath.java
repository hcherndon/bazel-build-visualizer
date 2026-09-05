package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.CancellationException;

/**
 * The longest weighted path through an action graph: what the build could not have finished sooner
 * than, given its dependencies.
 *
 * <h2>This is not Bazel's critical path</h2>
 *
 * <p>Bazel computes its own and writes it into the trace profile, and Phase 5 stores it untouched
 * in {@code bazel_critical_path}. This is a different number computed from a different thing — the
 * dependency graph, weighted by measured durations — and plan 13.4 requires it to be labeled
 * "Visualizer-computed dependency critical path" and never presented as Bazel's. {@link
 * Result#displayName()} is the only name it has, for that reason.
 *
 * <p>The two legitimately disagree. Bazel's is what actually gated the build, including scheduling
 * and machine limits; this one is what the dependencies alone imply, as if every action could start
 * the moment its inputs existed. A build whose two paths differ a lot was limited by something
 * other than its graph, which is worth knowing and is why ADR-009 keeps both.
 *
 * <h2>Only on an acyclic graph</h2>
 *
 * <p>Plan 13.4 says so, and a longest path is undefined with a cycle in it. Kahn's algorithm both
 * orders the graph and detects cycles in one pass, so detection is free rather than a separate
 * check that could be skipped.
 *
 * <p>The plan also says to condense strongly connected components and compute on the condensation.
 * That is not implemented, and the reason is that a producer-to-consumer action graph cannot have a
 * cycle: an artifact is produced by exactly one action, and an action that consumed its own output
 * could never have run. A cycle here means the graph is wrong, and the useful response is to say
 * which nodes are in one — which {@link Result} does — rather than to compute a plausible number
 * over a graph that should not exist.
 */
public final class CriticalPath {

  private static final long RETAINED_BYTES_PER_NODE = 32;
  // Durations, topology, schedule, predecessor/depth, path and both bit sets can overlap. Their
  // primitive payload is about 56.25 bytes/node; 64 leaves per-node room for alignment.
  private static final long PEAK_BYTES_PER_NODE = 64;
  private static final long ESTIMATE_OVERHEAD_BYTES = 1_024;

  private CriticalPath() {}

  /** Conservative retained size of the largest computed result, including its compact bit sets. */
  public static long retainedBytes(long nodeCount) {
    return estimate(nodeCount, RETAINED_BYTES_PER_NODE, ESTIMATE_OVERHEAD_BYTES / 2);
  }

  /** Conservative peak size including input durations and every simultaneous work array. */
  public static long peakBytes(long nodeCount) {
    return estimate(nodeCount, PEAK_BYTES_PER_NODE, ESTIMATE_OVERHEAD_BYTES);
  }

  private static long estimate(long nodeCount, long bytesPerNode, long overhead) {
    if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE - 1L) {
      throw new IllegalArgumentException("unsupported critical-path node count " + nodeCount);
    }
    return Math.addExact(Math.multiplyExact(nodeCount, bytesPerNode), overhead);
  }

  /**
   * Computes the path over {@code forward}, weighting each node by {@code durationMicros}.
   *
   * @param forward producer-to-consumer adjacency: an edge {@code u -> v} means {@code v} consumed
   *     something {@code u} produced, so {@code v} cannot start until {@code u} finishes
   * @param durationMicros one weight per node; {@link #UNKNOWN_DURATION} for a node nothing timed
   * @param source which measurement the weights came from, carried into the result because plan
   *     13.4 requires the answer to say
   */
  public static Result compute(CsrGraph forward, long[] durationMicros, DurationSource source) {
    Objects.requireNonNull(forward, "forward");
    Objects.requireNonNull(durationMicros, "durationMicros");
    Objects.requireNonNull(source, "source");
    int nodeCount = Math.toIntExact(forward.nodeCount());
    checkCancelled(0);
    if (durationMicros.length != nodeCount) {
      throw new IllegalArgumentException(
          "one weight per node: " + durationMicros.length + " weights for " + nodeCount + " nodes");
    }
    if (nodeCount == 0) {
      return Result.empty(source);
    }
    if (source == DurationSource.NONE) {
      throw new IllegalArgumentException(
          "DurationSource.NONE cannot weight a non-empty dependency graph");
    }

    for (int node = 0; node < nodeCount; node++) {
      checkCancelled(node);
      long duration = durationMicros[node];
      if (duration < 0 && duration != UNKNOWN_DURATION) {
        throw new IllegalArgumentException(
            "duration for node " + node + " must be nonnegative or UNKNOWN_DURATION");
      }
    }

    Topology topology = topologicalOrder(forward, nodeCount);
    if (!topology.isAcyclic()) {
      return Result.cyclic(source, nodeCount, topology.unorderedNodes());
    }
    int[] order = topology.order();

    BitSet untimedNodes = new BitSet(nodeCount);
    for (int node = 0; node < nodeCount; node++) {
      checkCancelled(node);
      if (durationMicros[node] == UNKNOWN_DURATION) {
        untimedNodes.set(node);
      }
    }

    // Forward pass. earliestStart(v) = max over predecessors of
    // earliestFinish(p); with only forward edges to hand, each node pushes
    // its finish to its successors instead, which visits every edge once.
    long[] earliestStart = new long[nodeCount];
    long[] earliestFinish = new long[nodeCount];
    int[] predecessor = new int[nodeCount];
    int[] pathDepth = new int[nodeCount];
    Arrays.fill(predecessor, NO_PREDECESSOR);
    Arrays.fill(pathDepth, 1);

    for (int orderIndex = 0; orderIndex < order.length; orderIndex++) {
      checkCancelled(orderIndex);
      int node = order[orderIndex];
      earliestFinish[node] = Math.addExact(earliestStart[node], weight(durationMicros, node));
      long finish = earliestFinish[node];
      int candidateDepth = Math.addExact(pathDepth[node], 1);
      long edgeEnd = forward.neighborsEnd(node);
      for (long edge = forward.neighborsBegin(node); edge < edgeEnd; edge++) {
        checkCancelled(edge);
        int successor = forward.neighborAt(edge);
        int currentPredecessor = predecessor[successor];
        if (finish > earliestStart[successor]
            || (finish == earliestStart[successor]
                && (candidateDepth > pathDepth[successor]
                    || (candidateDepth == pathDepth[successor]
                        && (currentPredecessor == NO_PREDECESSOR || node < currentPredecessor))))) {
          earliestStart[successor] = finish;
          predecessor[successor] = node;
          pathDepth[successor] = candidateDepth;
        }
      }
    }

    int last = 0;
    for (int node = 1; node < nodeCount; node++) {
      checkCancelled(node);
      if (earliestFinish[node] > earliestFinish[last]
          || (earliestFinish[node] == earliestFinish[last] && pathDepth[node] > pathDepth[last])) {
        last = node;
      }
    }

    // Backward pass, for slack. latestFinish starts at the makespan so a
    // node off the path gets the room it really has.
    long makespan = earliestFinish[last];
    long[] latestFinish = new long[nodeCount];
    Arrays.fill(latestFinish, makespan);
    for (int i = order.length - 1; i >= 0; i--) {
      checkCancelled(i);
      int node = order[i];
      long earliest = Long.MAX_VALUE;
      long edgeEnd = forward.neighborsEnd(node);
      for (long edge = forward.neighborsBegin(node); edge < edgeEnd; edge++) {
        checkCancelled(edge);
        int successor = forward.neighborAt(edge);
        long successorStart =
            Math.subtractExact(latestFinish[successor], weight(durationMicros, successor));
        if (successorStart < earliest) {
          earliest = successorStart;
        }
      }
      if (earliest != Long.MAX_VALUE) {
        latestFinish[node] = earliest;
      }
    }
    long[] slack = new long[nodeCount];
    for (int node = 0; node < nodeCount; node++) {
      checkCancelled(node);
      slack[node] =
          Math.subtractExact(
              Math.subtractExact(latestFinish[node], weight(durationMicros, node)),
              earliestStart[node]);
    }

    int[] path = new int[pathDepth[last]];
    int pathIndex = path.length - 1;
    for (int node = last; node != NO_PREDECESSOR; node = predecessor[node]) {
      checkCancelled(pathIndex);
      path[pathIndex--] = node;
    }

    return new Result(
        Outcome.COMPUTED,
        source,
        path,
        makespan,
        earliestStart,
        earliestFinish,
        slack,
        untimedNodes,
        nodeCount,
        new int[0]);
  }

  /** A node nothing measured. Distinct from a node measured at zero. */
  public static final long UNKNOWN_DURATION = -1;

  private static final int NO_PREDECESSOR = -1;

  /**
   * A node's weight, with an unknown duration counted as no time.
   *
   * <p>Not a guess dressed as a measurement: the count of untimed nodes travels with the result and
   * marks it partial, so a path computed over a graph nobody timed is reported as one rather than
   * as a zero-length build.
   */
  private static long weight(long[] durationMicros, int node) {
    long duration = durationMicros[node];
    return duration == UNKNOWN_DURATION ? 0 : duration;
  }

  private static void checkCancelled(long progress) {
    if ((progress & 4_095L) == 0 && Thread.currentThread().isInterrupted()) {
      throw new CancellationException("critical-path computation was cancelled");
    }
  }

  /** Kahn's algorithm, retaining its residual nodes when the graph has a cycle. */
  private static Topology topologicalOrder(CsrGraph forward, int nodeCount) {
    int[] inDegree = new int[nodeCount];
    for (int node = 0; node < nodeCount; node++) {
      checkCancelled(node);
      long edgeEnd = forward.neighborsEnd(node);
      for (long edge = forward.neighborsBegin(node); edge < edgeEnd; edge++) {
        checkCancelled(edge);
        inDegree[forward.neighborAt(edge)]++;
      }
    }
    int[] queue = new int[nodeCount];
    int head = 0;
    int tail = 0;
    for (int node = 0; node < nodeCount; node++) {
      checkCancelled(node);
      if (inDegree[node] == 0) {
        queue[tail++] = node;
      }
    }
    while (head < tail) {
      checkCancelled(head);
      int node = queue[head++];
      long edgeEnd = forward.neighborsEnd(node);
      for (long edge = forward.neighborsBegin(node); edge < edgeEnd; edge++) {
        checkCancelled(edge);
        int successor = forward.neighborAt(edge);
        if (--inDegree[successor] == 0) {
          queue[tail++] = successor;
        }
      }
    }
    if (tail == nodeCount) {
      // The queue is already the topological order, so keep it instead
      // of allocating and filling a duplicate O(V) array.
      return new Topology(queue, new int[0]);
    }
    // At Kahn termination, every node with residual in-degree is in or
    // downstream of a cycle. Retain only primitive ids and reuse this
    // first pass instead of rerunning the whole algorithm on bad input.
    int[] stuck = new int[nodeCount - tail];
    int stuckIndex = 0;
    for (int node = 0; node < nodeCount; node++) {
      checkCancelled(node);
      if (inDegree[node] > 0) {
        stuck[stuckIndex++] = node;
      }
    }
    return new Topology(null, stuck);
  }

  private record Topology(int[] order, int[] unorderedNodes) {
    boolean isAcyclic() {
      return order != null;
    }
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

    /** The precise node-weight interpretation used by the dependency path. */
    public String pathWeightDescription() {
      return switch (this) {
        case BEP_ACTION -> description;
        case EXECUTION_ATTEMPT ->
            "shortest recorded spawn duration per action from the execution log";
        case NONE -> description;
      };
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
   * <h2>Why this is a class and not a record</h2>
   *
   * <p>It holds three arrays and two compact bit sets with an entry per node, and a Tier 3 session
   * has five million of them. A record would mandate public accessors returning those mutable
   * structures, which leaves two options and no third: hand out the live data so any caller can
   * rewrite the schedule, or clone tens of megabytes for a caller that wanted one action's slack or
   * timing presence. The Phase 7 audit reached the same conclusion about {@link GraphLayout.Result}
   * for the same reason. Per-node accessors cost nothing and cannot be misused.
   */
  public static final class Result {

    private final Outcome outcome;
    private final DurationSource durationSource;
    private final List<Integer> path;
    private final long makespanMicros;
    private final long[] earliestStartMicros;
    private final long[] earliestFinishMicros;
    private final long[] slackMicros;
    private final BitSet untimedNodesByIndex;
    private final BitSet selectedPathNodesByIndex;
    private final long untimedNodes;
    private final long nodeCount;
    private final List<Integer> unorderedNodes;

    private Result(
        Outcome outcome,
        DurationSource durationSource,
        int[] pathNodes,
        long makespanMicros,
        long[] earliestStartMicros,
        long[] earliestFinishMicros,
        long[] slackMicros,
        BitSet untimedNodesByIndex,
        long nodeCount,
        int[] unorderedNodeIndices) {
      this.outcome = outcome;
      this.durationSource = durationSource;
      this.path = new IntArrayListView(pathNodes);
      this.makespanMicros = makespanMicros;
      this.earliestStartMicros = earliestStartMicros;
      this.earliestFinishMicros = earliestFinishMicros;
      this.slackMicros = slackMicros;
      this.untimedNodesByIndex = untimedNodesByIndex;
      this.selectedPathNodesByIndex = new BitSet(slackMicros.length);
      for (int position = 0; position < pathNodes.length; position++) {
        checkCancelled(position);
        this.selectedPathNodesByIndex.set(pathNodes[position]);
      }
      this.untimedNodes = untimedNodesByIndex.cardinality();
      this.nodeCount = nodeCount;
      this.unorderedNodes = new IntArrayListView(unorderedNodeIndices);
    }

    static Result empty(DurationSource source) {
      return new Result(
          Outcome.EMPTY,
          source,
          new int[0],
          0,
          new long[0],
          new long[0],
          new long[0],
          new BitSet(),
          0,
          new int[0]);
    }

    static Result cyclic(DurationSource source, long nodeCount, int[] stuck) {
      return new Result(
          Outcome.CYCLIC,
          source,
          new int[0],
          0,
          new long[0],
          new long[0],
          new long[0],
          new BitSet(),
          nodeCount,
          stuck);
    }

    /** How the computation ended. */
    public Outcome outcome() {
      return outcome;
    }

    /** Which measurement the node weights came from. */
    public DurationSource durationSource() {
      return durationSource;
    }

    /** Node indices from the start of the chain to its end. */
    public List<Integer> path() {
      return path;
    }

    /** The length of the path: what the build could not have beaten given its dependencies. */
    public long makespanMicros() {
      return makespanMicros;
    }

    /** Nodes nothing measured, counted as instantaneous. */
    public long untimedNodes() {
      return untimedNodes;
    }

    /**
     * True when nothing measured this node's duration.
     *
     * <p>This is distinct from a measured duration of zero. The compact bit set keeps that
     * distinction available per node without retaining a second {@code long[]} for Tier 3 graphs.
     */
    public boolean isUntimedAt(int node) {
      Objects.checkIndex(node, scheduledNodes());
      return untimedNodesByIndex.get(node);
    }

    /** Nodes in the graph this was computed over. */
    public long nodeCount() {
      return nodeCount;
    }

    /**
     * When {@link Outcome#CYCLIC}, nodes that could not be topologically ordered.
     *
     * <p>This includes both cycle members and nodes downstream of a cycle; Kahn's algorithm cannot
     * distinguish those sets by itself.
     */
    public List<Integer> unorderedNodes() {
      return unorderedNodes;
    }

    /**
     * Compatibility alias for {@link #unorderedNodes()}.
     *
     * @deprecated the returned nodes may be downstream of a cycle rather than members of the cycle
     *     itself
     */
    @Deprecated
    public List<Integer> cyclicNodes() {
      return unorderedNodes;
    }

    /**
     * The earliest one action could have started, given its dependencies.
     *
     * <p>Not when it did start: this is an idealized offset from the dependency schedule's own
     * zero. It cannot be subtracted from an absolute observed timestamp unless the caller first
     * puts both on a proven common origin, and any remaining gap is not causal evidence.
     */
    public long earliestStartAt(int node) {
      return earliestStartMicros[node];
    }

    /** The earliest one action could have finished. */
    public long earliestFinishAt(int node) {
      return earliestFinishMicros[node];
    }

    /**
     * How much later one action could have started without making the build longer.
     *
     * <p>Zero for every action on any equally longest branch. Membership in the one selected,
     * tie-broken chain is tracked separately by {@link #isOnPath(int)}. Plan 13.4 requires complete
     * timing and a complete graph for slack to mean what it says, so a caller showing it must show
     * {@link #isPartial()} beside it.
     */
    public long slackAt(int node) {
      return slackMicros[node];
    }

    /**
     * True when {@code node} lies on the one tie-broken path returned by {@link #path()}.
     *
     * <p>A zero-slack node can lie on another equally long branch and still be absent from the
     * selected path, so membership cannot be inferred from slack alone.
     */
    public boolean isOnPath(int node) {
      if (outcome != Outcome.COMPUTED) {
        return false;
      }
      Objects.checkIndex(node, scheduledNodes());
      return selectedPathNodesByIndex.get(node);
    }

    /** How many nodes carry a schedule. Zero unless the outcome is computed. */
    public int scheduledNodes() {
      return slackMicros.length;
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
     * True when some node timings were missing, so the answer is a lower bound rather than the
     * answer.
     *
     * <p>This result cannot prove that the imported graph itself was complete. Callers must verify
     * graph-source completeness separately.
     */
    public boolean isPartial() {
      return untimedNodes > 0;
    }

    /** What the user needs to read this number correctly. */
    public String describe() {
      return switch (outcome) {
        case EMPTY -> "There is no dependency graph to compute a critical path over.";
        case CYCLIC ->
            "The dependency graph contains a cycle, so "
                + unorderedNodes.size()
                + " of "
                + nodeCount
                + " actions could not be ordered; those actions are in or downstream"
                + " of the cycle. There is no longest path through it. An action graph"
                + " derived from producer and consumer relationships cannot have one,"
                + " so this means the graph is wrong rather than the build.";
        case COMPUTED -> {
          StringBuilder text =
              new StringBuilder(displayName())
                  .append(": ")
                  .append(path.size())
                  .append(" actions totalling ")
                  .append(MetricFormat.duration(makespanMicros))
                  .append(", using ")
                  .append(durationSource.pathWeightDescription())
                  .append('.');
          if (isPartial()) {
            text.append(' ')
                .append(untimedNodes)
                .append(" of ")
                .append(nodeCount)
                .append(
                    " actions have no measured duration and were counted as"
                        + " instantaneous, so this is a lower bound.");
          }
          yield text.toString();
        }
      };
    }
  }

  /** Read-only {@link List} compatibility over primitive path storage. */
  private static final class IntArrayListView extends AbstractList<Integer>
      implements RandomAccess {

    private final int[] values;

    private IntArrayListView(int[] values) {
      this.values = Objects.requireNonNull(values, "values");
    }

    @Override
    public Integer get(int index) {
      return values[Objects.checkIndex(index, values.length)];
    }

    @Override
    public int size() {
      return values.length;
    }
  }
}
