package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The 32-byte header at the front of every journal segment, decoded.
 *
 * <p>{@link JournalFormat} owns the byte layout; this type owns reading and writing it against a
 * channel, so the writer, the reader and recovery all agree on what a valid segment looks like
 * before a single frame is touched.
 *
 * @param formatVersion journal format version the segment was written with
 * @param segmentIndex the segment's own index, which must match its file name
 * @param sessionUuid the session the segment belongs to
 */
public record SegmentHeader(int formatVersion, int segmentIndex, UUID sessionUuid) {

  public SegmentHeader {
    Objects.requireNonNull(sessionUuid, "sessionUuid");
  }

  public static SegmentHeader current(int segmentIndex, UUID sessionUuid) {
    return new SegmentHeader(JournalFormat.FORMAT_VERSION, segmentIndex, sessionUuid);
  }

  /** Encodes this header into a fresh little-endian buffer, ready to write. */
  public ByteBuffer encode() {
    if (formatVersion != JournalFormat.FORMAT_VERSION) {
      throw new IllegalStateException(
          "refusing to write format version "
              + formatVersion
              + "; this build writes "
              + JournalFormat.FORMAT_VERSION);
    }
    ByteBuffer buffer = JournalFormat.allocate(JournalFormat.SEGMENT_HEADER_BYTES);
    JournalFormat.writeSegmentHeader(
        buffer,
        segmentIndex,
        sessionUuid.getMostSignificantBits(),
        sessionUuid.getLeastSignificantBits());
    return buffer.flip();
  }

  /**
   * Reads and validates the header at the start of {@code channel}.
   *
   * @throws IOException if the segment is shorter than a header, does not start with the journal
   *     magic, or declares a format version this build cannot read. All three mean the file is not
   *     a journal segment this build may append to or interpret, which is a hard stop rather than a
   *     recoverable tail defect.
   */
  public static SegmentHeader read(FileChannel channel) throws IOException {
    ByteBuffer buffer = JournalFormat.allocate(JournalFormat.SEGMENT_HEADER_BYTES);
    while (buffer.hasRemaining()) {
      // buffer.position() is both the destination cursor and the absolute
      // file offset here, because the header starts at byte 0.
      int read = channel.read(buffer, buffer.position());
      if (read < 0) {
        throw new IOException(
            "segment is shorter than its "
                + JournalFormat.SEGMENT_HEADER_BYTES
                + "-byte header: only "
                + buffer.position()
                + " bytes present");
      }
    }
    buffer.flip();

    byte[] magic = new byte[JournalFormat.MAGIC.length];
    buffer.get(magic);
    if (!Arrays.equals(magic, JournalFormat.MAGIC)) {
      throw new IOException("not a journal segment: magic mismatch");
    }
    int version = buffer.getInt();
    if (version != JournalFormat.FORMAT_VERSION) {
      throw new IOException(
          "journal format version "
              + version
              + " is not readable by this build (expected "
              + JournalFormat.FORMAT_VERSION
              + ")");
    }
    int index = buffer.getInt();
    long high = buffer.getLong();
    long low = buffer.getLong();
    return new SegmentHeader(version, index, new UUID(high, low));
  }

  /**
   * Fails when this header does not describe the segment it was expected to. A mismatched session
   * UUID means two sessions are sharing a directory, which would interleave one build's raw bytes
   * into another's journal.
   */
  public void requireMatches(int expectedSegmentIndex, UUID expectedSessionUuid)
      throws IOException {
    if (segmentIndex != expectedSegmentIndex) {
      throw new IOException(
          "segment file names index "
              + expectedSegmentIndex
              + " but its header says "
              + segmentIndex);
    }
    if (!sessionUuid.equals(expectedSessionUuid)) {
      throw new IOException(
          "segment belongs to session " + sessionUuid + ", not " + expectedSessionUuid);
    }
  }
}
