package com.holtherndon.bazelviz.testsupport.synthetic;

/**
 * Deterministic synthetic action-dependency edges forming a DAG: every edge points from a producer
 * with a smaller index to a consumer with a larger index, so cycle-free ordering is guaranteed by
 * construction. Edges are streamed, never materialized here; CSR construction owns materialization.
 */
public final class SyntheticEdges {

  @FunctionalInterface
  public interface EdgeConsumer {
    void accept(long producerIndex, long consumerIndex);
  }

  private final SyntheticScale scale;
  private final long seed;

  public SyntheticEdges(SyntheticScale scale, long seed) {
    this.scale = scale;
    this.seed = seed;
  }

  public long edgeCount() {
    long perConsumer = edgesPerConsumer();
    // Consumer 0 has no candidates; consumers with index < perConsumer contribute fewer.
    long total = 0;
    long n = scale.actionCount();
    for (long i = 1; i < Math.min(n, perConsumer); i++) {
      total += i;
    }
    if (n > perConsumer) {
      total += (n - Math.max(1, perConsumer)) * perConsumer;
    }
    return total;
  }

  /** Streams every edge in consumer order. Deterministic for a fixed (scale, seed). */
  public void forEachEdge(EdgeConsumer consumer) {
    long n = scale.actionCount();
    long perConsumer = edgesPerConsumer();
    for (long i = 1; i < n; i++) {
      long k = Math.min(i, perConsumer);
      for (long e = 0; e < k; e++) {
        long h = SyntheticActionGenerator.mix(seed ^ SyntheticActionGenerator.mix(i * 31 + e));
        // Bias producers toward recent indices to mimic layered build graphs.
        long back = 1 + Math.floorMod(h, Math.min(i, 4096));
        consumer.accept(i - back, i);
      }
    }
  }

  private long edgesPerConsumer() {
    return Math.max(1, scale.edgeCount() / Math.max(1, scale.actionCount()));
  }
}
