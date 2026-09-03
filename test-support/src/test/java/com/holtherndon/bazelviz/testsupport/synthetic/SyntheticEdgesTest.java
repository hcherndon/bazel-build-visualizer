package com.holtherndon.bazelviz.testsupport.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Test;

final class SyntheticEdgesTest {

  private static final long CANONICAL_SEED = 42;

  /** Sentinel for bounding forEachEdge work through the public API only. */
  private static final class StopIteration extends RuntimeException {
    StopIteration() {
      super(null, null, false, false);
    }
  }

  @Test
  void edgesFormDagOnTier1Prefix() {
    SyntheticEdges edges = new SyntheticEdges(SyntheticScale.TIER1, CANONICAL_SEED);
    long[] visited = {0};
    try {
      edges.forEachEdge(
          (producer, consumer) -> {
            if (consumer >= 50_000) {
              throw new StopIteration();
            }
            if (producer < 0 || producer >= consumer) {
              fail("bad edge %d -> %d", producer, consumer);
            }
            visited[0]++;
          });
    } catch (StopIteration expected) {
      // Bounded the scan; everything before the sentinel was checked.
    }
    assertThat(visited[0]).as("prefix must contain edges").isGreaterThan(100_000);
  }

  @Test
  void fullTier1StreamCountEqualsEdgeCount() {
    SyntheticEdges edges = new SyntheticEdges(SyntheticScale.TIER1, CANONICAL_SEED);
    long[] count = {0};
    edges.forEachEdge((producer, consumer) -> count[0]++);
    assertThat(count[0]).isEqualTo(edges.edgeCount());
    // Rounding actionCount into per-consumer degree may drop a few edges
    // versus the nominal tier figure, but never more than a percent.
    assertThat(edges.edgeCount())
        .isLessThanOrEqualTo(SyntheticScale.TIER1.edgeCount())
        .isGreaterThan(SyntheticScale.TIER1.edgeCount() * 99 / 100);
  }

  @Test
  void sameSeedYieldsIdenticalEdgesAndDifferentSeedDiffers() {
    long[] first = collectPrefix(new SyntheticEdges(SyntheticScale.TIER1, CANONICAL_SEED), 10_000);
    long[] second = collectPrefix(new SyntheticEdges(SyntheticScale.TIER1, CANONICAL_SEED), 10_000);
    long[] otherSeed =
        collectPrefix(new SyntheticEdges(SyntheticScale.TIER1, CANONICAL_SEED + 1), 10_000);
    assertThat(first).isEqualTo(second);
    assertThat(first).isNotEqualTo(otherSeed);
  }

  @Test
  void edgeCountIsPureMetadata() {
    // edgeCount() must not stream anything; it should be instant even at TIER3.
    SyntheticEdges edges = new SyntheticEdges(SyntheticScale.TIER3, CANONICAL_SEED);
    assertThatCode(edges::edgeCount).doesNotThrowAnyException();
    assertThat(edges.edgeCount()).isGreaterThan(0);
  }

  /** First {@code limit} edges as (producer, consumer) pairs, flattened. */
  private static long[] collectPrefix(SyntheticEdges edges, int limit) {
    long[] pairs = new long[limit * 2];
    int[] cursor = {0};
    try {
      edges.forEachEdge(
          (producer, consumer) -> {
            if (cursor[0] == pairs.length) {
              throw new StopIteration();
            }
            pairs[cursor[0]++] = producer;
            pairs[cursor[0]++] = consumer;
          });
    } catch (StopIteration expected) {
      // Collected exactly `limit` edges.
    }
    assertThat(cursor[0]).isEqualTo(pairs.length);
    return pairs;
  }
}
