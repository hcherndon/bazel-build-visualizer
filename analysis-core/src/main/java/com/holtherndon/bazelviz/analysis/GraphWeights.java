package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.graph.Bfs;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Per-node weights for a drawn subgraph: degrees, transitive counts, and the honesty flags that
 * travel with them.
 *
 * <h2>No transitive closure, ever</h2>
 *
 * <p>Plan 13.3 forbids computing one, and docs/graph-model.md repeats it. So the transitive counts
 * here come in exactly two bounded shapes: an <em>exact</em> count over the currently extracted
 * subgraph — cheap because the extraction is already bounded by {@link GraphExtract}'s limits, and
 * truthful about what is on screen — and a <em>budgeted</em> whole-graph BFS for one node at a
 * time, which reports "≥N (budget reached)" when it gives up rather than passing a partial count
 * off as a total.
 *
 * <h2>Unknown is not zero</h2>
 *
 * <p>A node whose weight could not be computed carries {@link #UNKNOWN}, never zero: "this has no
 * dependencies" and "the budget ran out before this node" are opposite claims that would collide on
 * a colour ramp.
 */
public final class GraphWeights {

  private GraphWeights() {}

  /** The weight array's sentinel for "this was not computed". */
  public static final long UNKNOWN = -1;

  /**
   * Nodes one whole-graph transitive count may visit before it gives up.
   *
   * <p>The same order as {@code TreeView.PATH_BUDGET}, and for the same reason: a traversal over
   * the full CSR index must be able to stop, and stopping is reported as "≥N (budget reached)"
   * rather than as an answer.
   */
  public static final long GLOBAL_TRANSITIVE_NODE_BUDGET = 200_000;

  /**
   * Total traversal steps the exact subgraph counts may spend, summed over every node of the
   * drawing.
   *
   * <p>Exact per-node counts cost a BFS per node, which is quadratic-ish in the drawing. For the
   * neighbourhoods people actually draw this never bites; for a whole-build extract at the
   * 50,000-node ceiling it stops the computation from taking half a minute. Nodes past the budget
   * are {@link #UNKNOWN} and {@link Result#truncated()} says so — a run that gave up is never
   * dressed as one that finished.
   */
  public static final long SUBGRAPH_TRANSITIVE_WORK_BUDGET = 20_000_000;

  /**
   * One weight per input node, in input order, plus what was not computed.
   *
   * @param values aligned with the node list the computation was given; {@link #UNKNOWN} where
   *     nothing could be computed
   * @param truncated true when a budget stopped the computation before every node had an answer —
   *     the unknown values then include nodes that do have a weight, just not one this run could
   *     afford
   * @param note a sentence for the legend when something is missing, or empty when nothing is
   */
  public record Result(long[] values, boolean truncated, String note) {

    /** How many of the values are {@link #UNKNOWN}. */
    public int unknownCount() {
      int count = 0;
      for (long value : values) {
        if (value == UNKNOWN) {
          count++;
        }
      }
      return count;
    }
  }

  /** Every node unknown, with the reason on the record. */
  public static Result unavailable(int size, String note) {
    long[] values = new long[size];
    Arrays.fill(values, UNKNOWN);
    return new Result(values, false, note);
  }

  /**
   * Each node's direct degree in {@code graph} — O(1) per node off the CSR offsets.
   *
   * <p>Hand this the reverse index for immediate dependencies, the forward (producer-to-consumer)
   * index for immediate dependents.
   */
  public static Result immediateDegrees(CsrGraph graph, List<Integer> nodes) {
    long[] values = new long[nodes.size()];
    long nodeCount = graph.nodeCount();
    for (int i = 0; i < nodes.size(); i++) {
      int node = nodes.get(i);
      values[i] = node >= 0 && node < nodeCount ? graph.degree(node) : UNKNOWN;
    }
    return new Result(values, false, "");
  }

  /**
   * Exact transitive counts over the extracted subgraph only.
   *
   * <p>For each node: how many <em>other</em> drawn nodes are reachable over the drawn edges. Exact
   * for what is on screen, and silent about the rest of the graph on purpose — the whole-graph
   * answer is {@link #globalTransitiveCount} and it is budgeted.
   *
   * @param nodes the drawn nodes; values align with this list
   * @param edges the drawn edges, in producer-to-consumer order
   * @param forwards true to follow edges producer-to-consumer (transitive dependents), false to
   *     walk them backwards (transitive dependencies)
   * @param workBudget total traversal steps across all nodes; nodes not reached before it runs out
   *     are {@link #UNKNOWN} and the result is marked truncated
   */
  public static Result subgraphTransitiveCounts(
      List<Integer> nodes, List<GraphExtract.Edge> edges, boolean forwards, long workBudget) {
    int size = nodes.size();
    long[] values = new long[size];
    Arrays.fill(values, UNKNOWN);
    if (size == 0) {
      return new Result(values, false, "");
    }

    // Node ids to positions, then a CSR-shaped adjacency over positions.
    HashMap<Integer, Integer> position = new HashMap<>(size * 2);
    for (int i = 0; i < size; i++) {
      position.put(nodes.get(i), i);
    }
    int[] degree = new int[size];
    int kept = 0;
    for (GraphExtract.Edge edge : edges) {
      Integer from = position.get(forwards ? edge.from() : edge.to());
      Integer to = position.get(forwards ? edge.to() : edge.from());
      if (from != null && to != null) {
        degree[from]++;
        kept++;
      }
    }
    int[] offsets = new int[size + 1];
    for (int i = 0; i < size; i++) {
      offsets[i + 1] = offsets[i] + degree[i];
    }
    int[] targets = new int[kept];
    int[] filled = new int[size];
    for (GraphExtract.Edge edge : edges) {
      Integer from = position.get(forwards ? edge.from() : edge.to());
      Integer to = position.get(forwards ? edge.to() : edge.from());
      if (from != null && to != null) {
        targets[offsets[from] + filled[from]++] = to;
      }
    }

    // One BFS per position, all charged to one shared budget.
    boolean[] seen = new boolean[size];
    int[] queue = new int[size];
    long work = 0;
    boolean truncated = false;
    for (int start = 0; start < size; start++) {
      if (work >= workBudget) {
        truncated = true;
        break;
      }
      Arrays.fill(seen, false);
      seen[start] = true;
      queue[0] = start;
      int head = 0;
      int tail = 1;
      long reached = 0;
      while (head < tail) {
        int node = queue[head++];
        work++;
        for (int e = offsets[node]; e < offsets[node + 1]; e++) {
          work++;
          int next = targets[e];
          if (!seen[next]) {
            seen[next] = true;
            queue[tail++] = next;
            reached++;
          }
        }
      }
      values[start] = reached;
    }
    return new Result(
        values,
        truncated,
        truncated
            ? "The exact on-screen counts stopped at their work budget;"
                + " nodes past it are grey, not zero."
            : "");
  }

  /** How one whole-graph transitive count ended: a count, and whether the budget ended it. */
  public record BudgetedCount(long count, boolean budgetReached) {

    /** The words a view shows; "≥N (budget reached)" is never plain N. */
    public String describe() {
      return budgetReached ? "≥" + count + " (budget reached)" : String.valueOf(count);
    }
  }

  /**
   * A budgeted whole-graph transitive count for one node.
   *
   * <p>Hand this the reverse index for transitive dependencies, the forward index for transitive
   * dependents. The count excludes the node itself.
   *
   * @param budget nodes the traversal may visit, the source included; when it is reached the answer
   *     is a lower bound and says so
   */
  public static BudgetedCount globalTransitiveCount(CsrGraph graph, int node, long budget) {
    long visited = new Bfs(graph).run(node, budget, Integer.MAX_VALUE);
    boolean reached = visited >= budget && visited < graph.nodeCount();
    return new BudgetedCount(Math.max(0, visited - 1), reached);
  }
}
