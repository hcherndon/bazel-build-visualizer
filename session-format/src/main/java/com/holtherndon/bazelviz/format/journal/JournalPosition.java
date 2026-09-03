package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;

/**
 * A point in the segmented journal: a segment index plus a byte offset inside that segment.
 * Positions always name a frame boundary — either the start of a frame or the end of the last
 * complete frame — never a byte inside a frame.
 *
 * <p>Ordering is lexicographic over (segment, offset), which is also the order bytes were appended,
 * because segments are only ever created in increasing index order and are never rewritten.
 */
public record JournalPosition(int segmentIndex, long byteOffset)
    implements Comparable<JournalPosition> {

  public JournalPosition {
    if (segmentIndex < 0) {
      throw new IllegalArgumentException("segmentIndex must be >= 0, got " + segmentIndex);
    }
    if (byteOffset < JournalFormat.SEGMENT_HEADER_BYTES) {
      throw new IllegalArgumentException(
          "byteOffset must be at least the segment header ("
              + JournalFormat.SEGMENT_HEADER_BYTES
              + "), got "
              + byteOffset);
    }
  }

  /** The position of the first frame of a segment, immediately after its header. */
  public static JournalPosition startOfSegment(int segmentIndex) {
    return new JournalPosition(segmentIndex, JournalFormat.SEGMENT_HEADER_BYTES);
  }

  @Override
  public int compareTo(JournalPosition other) {
    int bySegment = Integer.compare(segmentIndex, other.segmentIndex);
    return bySegment != 0 ? bySegment : Long.compare(byteOffset, other.byteOffset);
  }

  @Override
  public String toString() {
    return "segment " + segmentIndex + " @ " + byteOffset;
  }
}
