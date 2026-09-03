package com.holtherndon.bazelviz.testsupport.synthetic;

/**
 * Deterministic generator of synthetic length-delimited binary frames standing in for raw BEP
 * journal payloads in journal read/write benchmarks. Frame {@code index} is a pure O(1) function of
 * {@code (seed, index)}: the payload bytes come from a SplitMix64 chain and the length from a
 * cubic-skewed distribution over [{@value #MIN_FRAME_BYTES}, {@value #MAX_FRAME_BYTES}] bytes,
 * skewed small like real BEP progress/action events.
 *
 * <p>{@link #frameAt(long, byte[])} allocates nothing beyond the caller's buffer, so benchmarks can
 * pump millions of frames without GC pressure.
 */
public final class SyntheticRawEvents {

  public static final int MIN_FRAME_BYTES = 64;
  public static final int MAX_FRAME_BYTES = 2048;

  private final SyntheticScale scale;
  private final long seed;

  public SyntheticRawEvents(SyntheticScale scale, long seed) {
    this.scale = scale;
    this.seed = seed;
  }

  public SyntheticScale scale() {
    return scale;
  }

  /** Number of frames, one per synthetic event at this scale. */
  public long frameCount() {
    return scale.eventCount();
  }

  /**
   * Writes the payload of frame {@code index} into {@code dest} starting at offset 0 and returns
   * its length in bytes, always within [{@value #MIN_FRAME_BYTES}, {@value #MAX_FRAME_BYTES}]. Size
   * buffers to {@link #MAX_FRAME_BYTES} so any frame fits.
   *
   * @throws IndexOutOfBoundsException if {@code index} is outside [0, frameCount())
   * @throws IllegalArgumentException if {@code dest} is smaller than the frame
   */
  public int frameAt(long index, byte[] dest) {
    if (index < 0 || index >= frameCount()) {
      throw new IndexOutOfBoundsException("frame index " + index + " of " + frameCount());
    }
    long h = SyntheticActionGenerator.mix(seed ^ SyntheticActionGenerator.mix(index));
    int length = sampleLength(h);
    if (dest.length < length) {
      throw new IllegalArgumentException(
          "dest holds "
              + dest.length
              + " bytes but frame "
              + index
              + " needs "
              + length
              + "; size buffers to MAX_FRAME_BYTES");
    }
    long word = h;
    for (int i = 0; i < length; i++) {
      if ((i & 7) == 0) {
        word = SyntheticActionGenerator.mix(word);
      }
      dest[i] = (byte) (word >>> ((i & 7) << 3));
    }
    return length;
  }

  /**
   * Expected total payload bytes across all frames. Closed form, not a scan: the cubic skew has
   * E[u^3] = 1/4, so the expected frame length is MIN + span/4 (~560 bytes).
   */
  public long totalBytesEstimate() {
    long expectedFrameBytes = MIN_FRAME_BYTES + (MAX_FRAME_BYTES - MIN_FRAME_BYTES) / 4;
    return frameCount() * expectedFrameBytes;
  }

  /** Cubic-skewed length: most frames sit near the 64-byte floor, few near 2 KiB. */
  private static int sampleLength(long hash) {
    double u = (hash >>> 11) * 0x1.0p-53; // uniform [0,1)
    double skewed = u * u * u;
    int span = MAX_FRAME_BYTES - MIN_FRAME_BYTES;
    return MIN_FRAME_BYTES + Math.min(span, (int) (skewed * (span + 1)));
  }
}
