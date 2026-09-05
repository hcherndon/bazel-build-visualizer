package com.holtherndon.bazelviz.graph;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Budget-limited breadth-first traversal over a {@link CsrGraph}. Direction is whatever the
 * supplied graph encodes: hand it the forward graph for downstream reachability, the {@link
 * CsrBuilder#reverse reversed} graph for upstream.
 *
 * <p>State is two {@code int[]} frontier buffers plus a {@code long[]} visited bitset, reused
 * across runs so repeated traversals (e.g. per-selection impact queries) allocate nothing after
 * warmup. Instances are not thread-safe.
 */
public final class Bfs {

  /** Conservative visited-bitset and two-frontier peak for one bounded traversal. */
  public static long peakBytes(long nodeCount, long visitBudget) {
    if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE - 1L || visitBudget < 0) {
      throw new IllegalArgumentException(
          "unsupported breadth-first search size " + nodeCount + "/" + visitBudget);
    }
    long frontierNodes = Math.min(nodeCount, visitBudget);
    long visitedWords = (nodeCount + 63L) >>> 6;
    return Math.addExact(
        512,
        Math.addExact(
            Math.multiplyExact(visitedWords, Long.BYTES),
            Math.multiplyExact(frontierNodes, 2L * Integer.BYTES)));
  }

  private final CsrGraph graph;
  private final int nodeCount;
  private final long[] visitedWords;
  private int[] frontier = new int[0];
  private int[] next = new int[0];

  public Bfs(CsrGraph graph) {
    this.graph = graph;
    this.nodeCount = Math.toIntExact(graph.nodeCount());
    this.visitedWords = new long[Math.toIntExact(((long) nodeCount + 63L) >>> 6)];
  }

  /** See {@link #run(int, long, int, IntConsumer)}. */
  public long run(int source, long maxNodes, int maxDepth) {
    return runWithStatus(source, maxNodes, maxDepth, null).visited();
  }

  /**
   * Runs BFS from {@code source}, visiting at most {@code maxNodes} nodes (the source counts) and
   * expanding at most {@code maxDepth} levels ({@code maxDepth == 0} visits only the source).
   * Returns the number of nodes visited. {@code visitOrder}, when non-null, receives each node in
   * discovery order.
   */
  public long run(int source, long maxNodes, int maxDepth, IntConsumer visitOrder) {
    return runWithStatus(source, maxNodes, maxDepth, visitOrder).visited();
  }

  /**
   * Runs the traversal and states whether an undiscovered node proved the node budget too small.
   */
  public RunResult runWithStatus(int source, long maxNodes, int maxDepth, IntConsumer visitOrder) {
    if (source < 0 || source >= nodeCount) {
      throw new IllegalArgumentException(
          "source " + source + " out of range [0, " + nodeCount + ")");
    }
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth " + maxDepth);
    }
    if (maxNodes <= 0) {
      return new RunResult(0, true);
    }
    long budget = Math.min(maxNodes, nodeCount);
    Arrays.fill(visitedWords, 0L);
    ensureFrontierCapacity((int) budget);

    markVisited(source);
    if (visitOrder != null) {
      visitOrder.accept(source);
    }
    long visited = 1;
    frontier[0] = source;
    int frontierSize = 1;
    int depth = 0;

    while (frontierSize > 0 && depth < maxDepth) {
      int nextSize = 0;
      for (int i = 0; i < frontierSize; i++) {
        int node = frontier[i];
        long end = graph.neighborsEnd(node);
        for (long e = graph.neighborsBegin(node); e < end; e++) {
          int t = graph.neighborAt(e);
          if (!isVisited(t)) {
            if (visited == budget) {
              return new RunResult(visited, true);
            }
            markVisited(t);
            if (visitOrder != null) {
              visitOrder.accept(t);
            }
            next[nextSize++] = t;
            visited++;
          }
        }
      }
      int[] swap = frontier;
      frontier = next;
      next = swap;
      frontierSize = nextSize;
      depth++;
    }
    return new RunResult(visited, false);
  }

  /** Exact traversal count plus whether a further reachable node hit the budget boundary. */
  public record RunResult(long visited, boolean budgetReached) {}

  private void ensureFrontierCapacity(int capacity) {
    if (frontier.length < capacity) {
      frontier = new int[capacity];
      next = new int[capacity];
    }
  }

  private boolean isVisited(int node) {
    return (visitedWords[node >>> 6] & (1L << node)) != 0;
  }

  private void markVisited(int node) {
    visitedWords[node >>> 6] |= 1L << node;
  }
}
