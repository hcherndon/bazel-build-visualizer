package com.holtherndon.bazelviz.graph;

import java.util.function.IntConsumer;

/**
 * Immutable compressed-sparse-row adjacency. Storage is exactly two primitive arrays: {@code
 * offsets} (length nodeCount + 1) and {@code targets} (length edgeCount); the neighbors of node
 * {@code u} are {@code targets[offsets[u] .. offsets[u+1])}. No per-node or per-edge objects exist
 * anywhere in this class.
 *
 * <p>Phase 0 constraint: {@code nodeCount} and {@code edgeCount} must each fit in an {@code int}
 * because Java arrays are int-indexed. The largest planned fixture (TIER3: 5M nodes, 100M edges)
 * fits comfortably. Offsets are kept as {@code long[]} and the slice API speaks {@code long} edge
 * indices so callers survive a future widening of {@code targets} to a segmented structure without
 * signature changes.
 */
public final class CsrGraph {

  private final long[] offsets;
  private final int[] targets;

  /**
   * Trusted-array constructor; {@link CsrBuilder} is the intended entry point. Validates structural
   * invariants (one linear pass over each array) and takes ownership of both arrays without
   * copying.
   */
  CsrGraph(long[] offsets, int[] targets) {
    if (offsets.length == 0 || offsets[0] != 0) {
      throw new IllegalArgumentException("offsets must start with 0 and have length nodeCount + 1");
    }
    for (int i = 0; i + 1 < offsets.length; i++) {
      if (offsets[i] > offsets[i + 1]) {
        throw new IllegalArgumentException("offsets must be non-decreasing; violated at " + i);
      }
    }
    if (offsets[offsets.length - 1] != targets.length) {
      throw new IllegalArgumentException(
          "offsets[nodeCount] = "
              + offsets[offsets.length - 1]
              + " does not match targets.length = "
              + targets.length);
    }
    int nodeCount = offsets.length - 1;
    for (int i = 0; i < targets.length; i++) {
      if (targets[i] < 0 || targets[i] >= nodeCount) {
        throw new IllegalArgumentException(
            "target " + targets[i] + " at edge " + i + " out of range [0, " + nodeCount + ")");
      }
    }
    this.offsets = offsets;
    this.targets = targets;
  }

  public long nodeCount() {
    return offsets.length - 1;
  }

  public long edgeCount() {
    return targets.length;
  }

  public int degree(int node) {
    return (int) (offsets[node + 1] - offsets[node]);
  }

  public void forEachNeighbor(int node, IntConsumer consumer) {
    long end = offsets[node + 1];
    for (long e = offsets[node]; e < end; e++) {
      consumer.accept(targets[(int) e]);
    }
  }

  /** First edge index of {@code node}'s neighbor slice; pair with {@link #neighborsEnd}. */
  public long neighborsBegin(int node) {
    return offsets[node];
  }

  /** Exclusive end of {@code node}'s neighbor slice in edge-index space. */
  public long neighborsEnd(int node) {
    return offsets[node + 1];
  }

  /** Target node of the edge at {@code edgeIndex} (valid within any neighbor slice). */
  public int neighborAt(long edgeIndex) {
    if (edgeIndex < 0 || edgeIndex >= targets.length) {
      throw new IndexOutOfBoundsException("edge index " + edgeIndex + " of " + targets.length);
    }
    return targets[(int) edgeIndex];
  }

  /** Retained bytes of the two backing arrays (excludes object headers). */
  public long retainedArrayBytes() {
    return 8L * offsets.length + 4L * targets.length;
  }

  // Same-package hot-loop access (Bfs, CsrBuilder.reverse). Callers must not mutate.
  long[] rawOffsets() {
    return offsets;
  }

  int[] rawTargets() {
    return targets;
  }
}
