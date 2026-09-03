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

  private final long[] offsets;
  private final int[] targets;
  private final int nodeCount;
  private final long[] visitedWords;
  private int[] frontier = new int[0];
  private int[] next = new int[0];

  public Bfs(CsrGraph graph) {
    this.offsets = graph.rawOffsets();
    this.targets = graph.rawTargets();
    this.nodeCount = (int) graph.nodeCount();
    this.visitedWords = new long[(nodeCount + 63) >>> 6];
  }

  /** See {@link #run(int, long, int, IntConsumer)}. */
  public long run(int source, long maxNodes, int maxDepth) {
    return run(source, maxNodes, maxDepth, null);
  }

  /**
   * Runs BFS from {@code source}, visiting at most {@code maxNodes} nodes (the source counts) and
   * expanding at most {@code maxDepth} levels ({@code maxDepth == 0} visits only the source).
   * Returns the number of nodes visited. {@code visitOrder}, when non-null, receives each node in
   * discovery order.
   */
  public long run(int source, long maxNodes, int maxDepth, IntConsumer visitOrder) {
    if (source < 0 || source >= nodeCount) {
      throw new IllegalArgumentException(
          "source " + source + " out of range [0, " + nodeCount + ")");
    }
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth " + maxDepth);
    }
    if (maxNodes <= 0) {
      return 0;
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

    while (frontierSize > 0 && depth < maxDepth && visited < budget) {
      int nextSize = 0;
      for (int i = 0; i < frontierSize; i++) {
        int node = frontier[i];
        long end = offsets[node + 1];
        for (long e = offsets[node]; e < end; e++) {
          int t = targets[(int) e];
          if (!isVisited(t)) {
            markVisited(t);
            if (visitOrder != null) {
              visitOrder.accept(t);
            }
            next[nextSize++] = t;
            if (++visited == budget) {
              return visited;
            }
          }
        }
      }
      int[] swap = frontier;
      frontier = next;
      next = swap;
      frontierSize = nextSize;
      depth++;
    }
    return visited;
  }

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
