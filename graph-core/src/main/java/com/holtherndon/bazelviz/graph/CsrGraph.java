package com.holtherndon.bazelviz.graph;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Immutable compressed-sparse-row adjacency over either admitted heap arrays or read-only mapped
 * file segments.
 *
 * <p>The logical layout is always two flat primitive arrays: {@code offsets[nodeCount + 1]} and
 * {@code targets[edgeCount]}. A mapped graph reads those primitives in place; it never copies the
 * file into heap arrays. Mapped graphs are closeable and must remain inside their owner's scoped
 * lease.
 */
public final class CsrGraph implements AutoCloseable {

  private final Storage storage;

  /**
   * Trusted-array constructor; {@link CsrBuilder} is the intended heap entry point. Takes ownership
   * without copying after a linear structural validation.
   */
  CsrGraph(long[] offsets, int[] targets) {
    this(new HeapStorage(offsets, targets), true);
  }

  private CsrGraph(Storage storage, boolean validate) {
    this.storage = Objects.requireNonNull(storage, "storage");
    if (validate) {
      validateStructure(storage);
    }
  }

  static CsrGraph mapped(
      long nodeCount,
      long edgeCount,
      long bodyBytes,
      long segmentBytes,
      MemorySegment[] segments,
      Arena arena) {
    return new CsrGraph(
        new MappedStorage(
            nodeCount, edgeCount, bodyBytes, segmentBytes, segments, Objects.requireNonNull(arena)),
        false);
  }

  public long nodeCount() {
    return storage.nodeCount();
  }

  public long edgeCount() {
    return storage.edgeCount();
  }

  public int degree(int node) {
    checkNode(node);
    return Math.toIntExact(storage.offset(node + 1L) - storage.offset(node));
  }

  public void forEachNeighbor(int node, IntConsumer consumer) {
    Objects.requireNonNull(consumer, "consumer");
    checkNode(node);
    long end = storage.offset(node + 1L);
    for (long edge = storage.offset(node); edge < end; edge++) {
      consumer.accept(storage.target(edge));
    }
  }

  /** First edge index of {@code node}'s neighbor slice; pair with {@link #neighborsEnd}. */
  public long neighborsBegin(int node) {
    checkNode(node);
    return storage.offset(node);
  }

  /** Exclusive end of {@code node}'s neighbor slice in edge-index space. */
  public long neighborsEnd(int node) {
    checkNode(node);
    return storage.offset(node + 1L);
  }

  /** Target node of the edge at {@code edgeIndex} (valid within any neighbor slice). */
  public int neighborAt(long edgeIndex) {
    if (edgeIndex < 0 || edgeIndex >= edgeCount()) {
      throw new IndexOutOfBoundsException("edge index " + edgeIndex + " of " + edgeCount());
    }
    return storage.target(edgeIndex);
  }

  /** Same-package structural access, including the final offset of an empty graph. */
  long offsetAt(long index) {
    if (index < 0 || index > nodeCount()) {
      throw new IndexOutOfBoundsException("offset index " + index + " of " + (nodeCount() + 1));
    }
    return storage.offset(index);
  }

  /** Heap bytes retained by the two primitive arrays; zero for a mapped graph. */
  public long retainedArrayBytes() {
    return storage.retainedArrayBytes();
  }

  /** Bytes held in read-only mappings; zero for a heap graph. */
  public long mappedBytes() {
    return storage.mappedBytes();
  }

  /** Whether this graph reads directly from a mapped CSR body. */
  public boolean isMapped() {
    return storage.mappedBytes() != 0;
  }

  @Override
  public void close() {
    storage.close();
  }

  private void checkNode(int node) {
    if (node < 0 || node >= nodeCount()) {
      throw new IndexOutOfBoundsException("node " + node + " of " + nodeCount());
    }
  }

  private static void validateStructure(Storage storage) {
    long nodes = storage.nodeCount();
    long edges = storage.edgeCount();
    if (nodes < 0 || nodes > Integer.MAX_VALUE - 1L) {
      throw new IllegalArgumentException("node count " + nodes + " is outside the supported range");
    }
    if (storage.offset(0) != 0) {
      throw new IllegalArgumentException("offsets must start with 0");
    }
    long previous = 0;
    for (long node = 0; node < nodes; node++) {
      long next = storage.offset(node + 1L);
      if (next < previous || next > edges) {
        throw new IllegalArgumentException("offsets must be non-decreasing; violated at " + node);
      }
      previous = next;
    }
    if (previous != edges) {
      throw new IllegalArgumentException(
          "offsets[nodeCount] = " + previous + " does not match edge count = " + edges);
    }
    for (long edge = 0; edge < edges; edge++) {
      int target = storage.target(edge);
      if (target < 0 || target >= nodes) {
        throw new IllegalArgumentException(
            "target " + target + " at edge " + edge + " out of range [0, " + nodes + ")");
      }
    }
  }

  private interface Storage {
    long nodeCount();

    long edgeCount();

    long offset(long index);

    int target(long index);

    long retainedArrayBytes();

    long mappedBytes();

    void close();
  }

  /** The small/test construction path. No object is retained per node or edge. */
  private static final class HeapStorage implements Storage {
    private final long[] offsets;
    private final int[] targets;

    HeapStorage(long[] offsets, int[] targets) {
      this.offsets = Objects.requireNonNull(offsets, "offsets");
      this.targets = Objects.requireNonNull(targets, "targets");
      if (offsets.length == 0) {
        throw new IllegalArgumentException("offsets must have length nodeCount + 1");
      }
    }

    @Override
    public long nodeCount() {
      return offsets.length - 1L;
    }

    @Override
    public long edgeCount() {
      return targets.length;
    }

    @Override
    public long offset(long index) {
      return offsets[Math.toIntExact(index)];
    }

    @Override
    public int target(long index) {
      return targets[Math.toIntExact(index)];
    }

    @Override
    public long retainedArrayBytes() {
      return Math.addExact(
          Math.multiplyExact((long) offsets.length, Long.BYTES),
          Math.multiplyExact((long) targets.length, Integer.BYTES));
    }

    @Override
    public long mappedBytes() {
      return 0;
    }

    @Override
    public void close() {}
  }

  /** Segment metadata is bounded by file size, not by edge count. */
  private static final class MappedStorage implements Storage {
    private static final ValueLayout.OfLong LONG =
        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt INT =
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final long nodeCount;
    private final long edgeCount;
    private final long offsetsBytes;
    private final long bodyBytes;
    private final long segmentBytes;
    private final MemorySegment[] segments;
    private final Arena arena;

    MappedStorage(
        long nodeCount,
        long edgeCount,
        long bodyBytes,
        long segmentBytes,
        MemorySegment[] segments,
        Arena arena) {
      this.nodeCount = nodeCount;
      this.edgeCount = edgeCount;
      this.offsetsBytes = Math.multiplyExact(Math.addExact(nodeCount, 1L), Long.BYTES);
      this.bodyBytes = bodyBytes;
      this.segmentBytes = segmentBytes;
      this.segments = Objects.requireNonNull(segments, "segments");
      this.arena = arena;
    }

    @Override
    public long nodeCount() {
      return nodeCount;
    }

    @Override
    public long edgeCount() {
      return edgeCount;
    }

    @Override
    public long offset(long index) {
      return getLong(Math.multiplyExact(index, Long.BYTES));
    }

    @Override
    public int target(long index) {
      return getInt(Math.addExact(offsetsBytes, Math.multiplyExact(index, Integer.BYTES)));
    }

    private long getLong(long byteOffset) {
      int segment = Math.toIntExact(byteOffset / segmentBytes);
      long local = byteOffset % segmentBytes;
      return segments[segment].get(LONG, local);
    }

    private int getInt(long byteOffset) {
      int segment = Math.toIntExact(byteOffset / segmentBytes);
      long local = byteOffset % segmentBytes;
      return segments[segment].get(INT, local);
    }

    @Override
    public long retainedArrayBytes() {
      return 0;
    }

    @Override
    public long mappedBytes() {
      return bodyBytes;
    }

    @Override
    public void close() {
      if (arena.scope().isAlive()) {
        arena.close();
      }
    }
  }
}
