package com.holtherndon.bazelviz.testsupport.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Test;

final class SyntheticRawEventsTest {

  private static final long CANONICAL_SEED = 42;

  @Test
  void frameCountMatchesTierEventCount() {
    for (SyntheticScale scale : SyntheticScale.values()) {
      SyntheticRawEvents events = new SyntheticRawEvents(scale, CANONICAL_SEED);
      assertThat(events.frameCount()).isEqualTo(scale.eventCount());
    }
  }

  @Test
  void framesAreDeterministicPerSeedAndDifferAcrossSeeds() {
    SyntheticRawEvents a = new SyntheticRawEvents(SyntheticScale.TIER1, CANONICAL_SEED);
    SyntheticRawEvents b = new SyntheticRawEvents(SyntheticScale.TIER1, CANONICAL_SEED);
    SyntheticRawEvents other = new SyntheticRawEvents(SyntheticScale.TIER1, CANONICAL_SEED + 1);
    byte[] bufA = new byte[SyntheticRawEvents.MAX_FRAME_BYTES];
    byte[] bufB = new byte[SyntheticRawEvents.MAX_FRAME_BYTES];
    boolean anySeedDifference = false;
    for (long index : sampledIndices(a.frameCount(), 500)) {
      int lenA = a.frameAt(index, bufA);
      int lenB = b.frameAt(index, bufB);
      assertThat(lenA).isEqualTo(lenB);
      assertThat(Arrays.equals(bufA, 0, lenA, bufB, 0, lenB))
          .as("frame %d payload must be identical for identical seeds", index)
          .isTrue();
      int lenOther = other.frameAt(index, bufB);
      if (lenOther != lenA || !Arrays.equals(bufA, 0, lenA, bufB, 0, lenOther)) {
        anySeedDifference = true;
      }
    }
    assertThat(anySeedDifference).as("a different seed must change the frames").isTrue();
  }

  @Test
  void frameLengthsStayWithinBoundsAndSkewSmall() {
    SyntheticRawEvents events = new SyntheticRawEvents(SyntheticScale.TIER1, CANONICAL_SEED);
    byte[] buf = new byte[SyntheticRawEvents.MAX_FRAME_BYTES];
    long lengthSum = 0;
    int samples = 10_000;
    long[] indices = sampledIndices(events.frameCount(), samples);
    for (long index : indices) {
      int len = events.frameAt(index, buf);
      assertThat(len)
          .isGreaterThanOrEqualTo(SyntheticRawEvents.MIN_FRAME_BYTES)
          .isLessThanOrEqualTo(SyntheticRawEvents.MAX_FRAME_BYTES);
      lengthSum += len;
    }
    double meanLength = (double) lengthSum / samples;
    double midpoint =
        (SyntheticRawEvents.MIN_FRAME_BYTES + SyntheticRawEvents.MAX_FRAME_BYTES) / 2.0;
    assertThat(meanLength).as("skewed small: mean below the range midpoint").isLessThan(midpoint);
  }

  @Test
  void totalBytesEstimateTracksObservedMeanFrameLength() {
    SyntheticRawEvents events = new SyntheticRawEvents(SyntheticScale.TIER1, CANONICAL_SEED);
    byte[] buf = new byte[SyntheticRawEvents.MAX_FRAME_BYTES];
    int samples = 10_000;
    long lengthSum = 0;
    for (long index : sampledIndices(events.frameCount(), samples)) {
      lengthSum += events.frameAt(index, buf);
    }
    double observedMean = (double) lengthSum / samples;
    double estimatedMean = (double) events.totalBytesEstimate() / events.frameCount();
    assertThat(estimatedMean).isCloseTo(observedMean, Percentage.withPercentage(10));
  }

  @Test
  void frameAtRejectsBadIndicesAndSmallBuffers() {
    SyntheticRawEvents events = new SyntheticRawEvents(SyntheticScale.TIER1, CANONICAL_SEED);
    byte[] buf = new byte[SyntheticRawEvents.MAX_FRAME_BYTES];
    assertThatThrownBy(() -> events.frameAt(-1, buf)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> events.frameAt(events.frameCount(), buf))
        .isInstanceOf(IndexOutOfBoundsException.class);
    // A frame longer than 63 bytes always exists at the floor, so an empty
    // buffer can never fit any frame.
    assertThatThrownBy(() -> events.frameAt(0, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Evenly strided indices across [0, count), always including the last index. */
  private static long[] sampledIndices(long count, int samples) {
    long[] indices = new long[samples];
    long stride = Math.max(1, count / samples);
    for (int i = 0; i < samples; i++) {
      indices[i] = Math.min(count - 1, i * stride);
    }
    indices[samples - 1] = count - 1;
    return indices;
  }
}
