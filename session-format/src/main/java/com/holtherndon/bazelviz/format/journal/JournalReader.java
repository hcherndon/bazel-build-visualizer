package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.FrameHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * Reads frames back out of one journal segment, verifying every one.
 *
 * <p>The reader stops at the first frame it cannot trust and says exactly why and exactly where
 * ({@link JournalScanStatus}, {@link #stopOffset()}). It never discards frames it already verified
 * because a later one is damaged: everything returned before the stop is real data, which is the
 * whole point of per-frame checksums (plan 21.3).
 *
 * <h2>Bounded memory</h2>
 *
 * The reader holds one scratch buffer of {@link JournalReaderConfig#bufferBytes()} regardless of
 * how large the segment is, and never memory-maps or slurps the file. When payloads are being read,
 * one array per frame is allocated, bounded by {@link JournalReaderConfig#maxPayloadBytes()} — a
 * per-frame cost, not a per-file one. With {@link JournalReaderConfig#readPayloads()} false, used
 * by recovery, payloads stream through the scratch buffer into the checksum and nothing frame-sized
 * is allocated at all.
 *
 * <h2>Threading</h2>
 *
 * Not thread-safe: it holds a scan cursor. Concurrent readers open their own instances, which is
 * safe because segments are append-only and already-written bytes are never rewritten.
 */
public final class JournalReader implements AutoCloseable {

  private final Path file;
  private final FileChannel channel;
  private final JournalReaderConfig config;
  private final SegmentHeader segmentHeader;
  private final long fileSize;
  private final long startOffset;

  private final ByteBuffer headerBuffer;
  private final ByteBuffer crcBuffer;
  private final ByteBuffer scratch;

  private long nextOffset;
  private long framesRead;
  private long lastSequence;
  private boolean haveSequence;

  private JournalScanStatus status;
  private long stopOffset;
  private String detail = "";

  private JournalReader(
      Path file,
      FileChannel channel,
      JournalReaderConfig config,
      SegmentHeader segmentHeader,
      long fileSize,
      long startOffset) {
    this.file = file;
    this.channel = channel;
    this.config = config;
    this.segmentHeader = segmentHeader;
    this.fileSize = fileSize;
    this.startOffset = startOffset;
    this.nextOffset = startOffset;
    this.headerBuffer = JournalFormat.allocate(JournalFormat.FRAME_HEADER_BYTES);
    this.crcBuffer = JournalFormat.allocate(JournalFormat.FRAME_CRC_BYTES);
    this.scratch = JournalFormat.allocate(config.bufferBytes());
  }

  /** Opens a segment and scans from its first frame. */
  public static JournalReader open(Path segmentFile, JournalReaderConfig config)
      throws IOException {
    return open(segmentFile, JournalFormat.SEGMENT_HEADER_BYTES, config);
  }

  /**
   * Opens a segment and scans from {@code startOffset}, which must be a frame boundary — normally
   * one taken from a checkpoint or from an earlier scan.
   *
   * @throws IOException if the file is not a journal segment this build can read, or if {@code
   *     startOffset} lies past the end of the file, which means the checkpoint is ahead of the data
   *     and the caller must decide how far back to rescan rather than have the reader guess
   */
  public static JournalReader open(Path segmentFile, long startOffset, JournalReaderConfig config)
      throws IOException {
    Objects.requireNonNull(segmentFile, "segmentFile");
    Objects.requireNonNull(config, "config");
    if (startOffset < JournalFormat.SEGMENT_HEADER_BYTES) {
      throw new IllegalArgumentException(
          "startOffset "
              + startOffset
              + " is inside the segment header; frames start at "
              + JournalFormat.SEGMENT_HEADER_BYTES);
    }
    FileChannel channel = FileChannel.open(segmentFile, StandardOpenOption.READ);
    try {
      SegmentHeader header = SegmentHeader.read(channel);
      OptionalInt fromName =
          JournalSegments.parseSegmentIndex(segmentFile.getFileName().toString());
      if (fromName.isPresent() && fromName.getAsInt() != header.segmentIndex()) {
        throw new IOException(
            "segment file "
                + segmentFile.getFileName()
                + " carries header index "
                + header.segmentIndex());
      }
      long size = channel.size();
      if (startOffset > size) {
        throw new IOException(
            "startOffset "
                + startOffset
                + " is past the end of "
                + segmentFile.getFileName()
                + " ("
                + size
                + " bytes)");
      }
      return new JournalReader(segmentFile, channel, config, header, size, startOffset);
    } catch (IOException | RuntimeException failure) {
      try {
        channel.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /**
   * The next verified frame, or {@code null} when the scan has stopped — call {@link #status()} to
   * find out whether that was a clean end or a defect.
   */
  public JournalFrame next() throws IOException {
    if (status != null) {
      return null;
    }
    long frameOffset = nextOffset;
    long available = fileSize - frameOffset;

    if (available == 0) {
      return stop(
          JournalScanStatus.OK,
          frameOffset,
          "segment ends on a frame boundary after " + framesRead + " frame(s)");
    }
    if (available < JournalFormat.FRAME_HEADER_BYTES) {
      return stop(
          JournalScanStatus.TRUNCATED_TAIL,
          frameOffset,
          "only "
              + available
              + " of "
              + JournalFormat.FRAME_HEADER_BYTES
              + " frame-header bytes present");
    }

    headerBuffer.clear();
    readFully(headerBuffer, frameOffset);
    headerBuffer.flip();

    for (int i = 0; i < JournalFormat.FRAME_MAGIC.length; i++) {
      if (headerBuffer.get(i) != JournalFormat.FRAME_MAGIC[i]) {
        return stop(
            JournalScanStatus.BAD_MAGIC,
            frameOffset,
            "expected frame magic "
                + JournalFormat.FRAME_MAGIC_STRING
                + " at offset "
                + frameOffset);
      }
    }

    int payloadLength = headerBuffer.getInt(JournalFormat.FRAME_MAGIC.length);
    if (payloadLength < 0 || payloadLength > config.maxPayloadBytes()) {
      return stop(
          JournalScanStatus.LENGTH_OUT_OF_BOUNDS,
          frameOffset,
          "frame declares a payload of "
              + payloadLength
              + " bytes, outside [0, "
              + config.maxPayloadBytes()
              + "]");
    }

    long totalBytes =
        (long) JournalFormat.FRAME_HEADER_BYTES + payloadLength + JournalFormat.FRAME_CRC_BYTES;
    if (available < totalBytes) {
      return stop(
          JournalScanStatus.TRUNCATED_TAIL,
          frameOffset,
          "frame needs " + totalBytes + " bytes but only " + available + " remain in the segment");
    }

    CRC32C crc = new CRC32C();
    crc.update(headerBuffer.duplicate().position(0).limit(JournalFormat.FRAME_HEADER_BYTES));

    long payloadOffset = frameOffset + JournalFormat.FRAME_HEADER_BYTES;
    byte[] payload = null;
    if (config.readPayloads()) {
      payload = new byte[payloadLength];
      readFully(ByteBuffer.wrap(payload), payloadOffset);
      crc.update(payload, 0, payloadLength);
    } else {
      checksumWithoutReading(crc, payloadOffset, payloadLength);
    }

    crcBuffer.clear();
    readFully(crcBuffer, payloadOffset + payloadLength);
    int storedCrc = crcBuffer.getInt(0);
    if (storedCrc != (int) crc.getValue()) {
      return stop(
          JournalScanStatus.CRC_MISMATCH,
          frameOffset,
          "frame checksum mismatch: stored "
              + Integer.toHexString(storedCrc)
              + ", computed "
              + Integer.toHexString((int) crc.getValue()));
    }

    FrameHeader header;
    try {
      header = JournalFormat.readFrameHeader(headerBuffer.position(0), config.maxPayloadBytes());
    } catch (IllegalArgumentException undecodable) {
      // The bytes are intact — the CRC just proved it — so this is a frame
      // this build cannot interpret, not damage. Recovery must not truncate it.
      return stop(
          JournalScanStatus.UNSUPPORTED_SOURCE_KIND,
          frameOffset,
          "intact frame this build cannot interpret: " + undecodable.getMessage());
    }

    nextOffset = frameOffset + totalBytes;
    framesRead++;
    lastSequence = header.sequence();
    haveSequence = true;
    return new JournalFrame(segmentHeader.segmentIndex(), frameOffset, header, payload);
  }

  /**
   * Drains the scan and reports the result. Frames are handed to {@code consumer} as they are
   * verified, so a caller that stops early still keeps every frame it was given.
   */
  public SegmentScan scan(FrameConsumer consumer) throws IOException {
    Objects.requireNonNull(consumer, "consumer");
    JournalFrame frame;
    while ((frame = next()) != null) {
      consumer.accept(frame);
    }
    return result();
  }

  /** Drains the scan without looking at the frames, verifying every checksum. */
  public SegmentScan verify() throws IOException {
    return scan(frame -> {});
  }

  /**
   * The finished result of the scan.
   *
   * @throws IllegalStateException while the scan is still running — there is no honest status to
   *     report yet, and defaulting to OK would let a half-read segment look complete
   */
  public SegmentScan result() {
    if (status == null) {
      throw new IllegalStateException(
          "scan has not finished: "
              + framesRead
              + " frame(s) read, next offset "
              + nextOffset
              + "; drain next() before asking for a result");
    }
    return new SegmentScan(
        segmentHeader.segmentIndex(),
        startOffset,
        stopOffset,
        fileSize,
        framesRead,
        lastSequence(),
        status,
        detail);
  }

  /** Why the scan stopped, or empty while it is still running. */
  public Optional<JournalScanStatus> status() {
    return Optional.ofNullable(status);
  }

  /** The offset the scan stopped at, or empty while it is still running. */
  public OptionalLong stopOffset() {
    return status == null ? OptionalLong.empty() : OptionalLong.of(stopOffset);
  }

  /** Offset of the next frame to be read. */
  public long nextFrameOffset() {
    return nextOffset;
  }

  public long framesRead() {
    return framesRead;
  }

  /** Sequence of the last verified frame; empty when none was read (never zero-as-unknown). */
  public OptionalLong lastSequence() {
    return haveSequence ? OptionalLong.of(lastSequence) : OptionalLong.empty();
  }

  public int segmentIndex() {
    return segmentHeader.segmentIndex();
  }

  public UUID sessionUuid() {
    return segmentHeader.sessionUuid();
  }

  public Path segmentFile() {
    return file;
  }

  public long segmentFileSize() {
    return fileSize;
  }

  /** Capacity of the one buffer whose size does not depend on the file. */
  public int scratchBufferCapacity() {
    return scratch.capacity();
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }

  @FunctionalInterface
  public interface FrameConsumer {
    void accept(JournalFrame frame) throws IOException;
  }

  // ---- internals ----------------------------------------------------------

  /** Streams payload bytes through the scratch buffer into the checksum. */
  private void checksumWithoutReading(CRC32C crc, long offset, int length) throws IOException {
    long position = offset;
    int remaining = length;
    while (remaining > 0) {
      scratch.clear();
      scratch.limit(Math.min(scratch.capacity(), remaining));
      readFully(scratch, position);
      scratch.flip();
      crc.update(scratch);
      position += scratch.limit();
      remaining -= scratch.limit();
    }
  }

  private void readFully(ByteBuffer destination, long position) throws IOException {
    long at = position;
    while (destination.hasRemaining()) {
      int read = channel.read(destination, at);
      if (read < 0) {
        // The caller already checked the byte count against the file
        // size, so hitting EOF here means the file shrank mid-scan.
        throw new IOException(
            "journal segment "
                + file.getFileName()
                + " ended at "
                + at
                + " while reading; was it truncated concurrently?");
      }
      at += read;
    }
  }

  private JournalFrame stop(JournalScanStatus reason, long offset, String message) {
    this.status = reason;
    this.stopOffset = offset;
    this.detail = message;
    return null;
  }
}
