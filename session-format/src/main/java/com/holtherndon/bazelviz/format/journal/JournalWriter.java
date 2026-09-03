package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.FrameHeader;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends raw payloads to the segmented journal (ADR-004, plan 9.3).
 *
 * <p>The journal is the recovery source of truth, so this class does exactly one thing to the bytes
 * it is given: it frames them. Payloads are written verbatim, never re-serialized, never trimmed,
 * and a payload larger than the configured maximum is rejected at the call site rather than
 * shortened.
 *
 * <h2>Durability</h2>
 *
 * The balanced default from plan 9.3: frames are staged in one buffer and handed to the OS when it
 * fills, or when {@link #flush()} is called; there is no {@code fsync} per event. {@link #force()}
 * is the durability barrier and is called on rotation and on {@link #close()}. A crash therefore
 * loses at most the frames still in the buffer, and {@link JournalRecovery} finds the last intact
 * frame afterwards. Anything acknowledged to a BES peer must be acknowledged only after {@link
 * #append} returns, not before.
 *
 * <h2>Threading</h2>
 *
 * Not thread-safe, deliberately. One journal has one writer thread — the pipeline stage between the
 * bounded receive queue and the decoder (plan 9.3) — and a lock here would serialize nothing that
 * is not already serialized while hiding a design error if a second thread ever appeared.
 * Concurrent use is a programming error, not a supported mode.
 *
 * <h2>Failure</h2>
 *
 * A write failure (disk full, plan 21.2) marks the writer permanently failed: every later {@link
 * #append} throws instead of pretending the frame landed, so the capture is visibly incomplete
 * rather than quietly short.
 *
 * <p>Do not interrupt the writer thread to cancel a capture. {@link FileChannel} closes itself on
 * interruption, so an interrupt lands as a write failure and ends the journal early; cancellation
 * (plan 8.7) must stop feeding the writer and then close it, which is what preserves the frames
 * already captured.
 */
public final class JournalWriter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(JournalWriter.class);

  private final Path directory;
  private final UUID sessionUuid;
  private final JournalWriterConfig config;

  /** Staging buffer; the "group flushes" mechanism from plan 9.3. */
  private final ByteBuffer buffer;

  /** 27 bytes, reused for frames too large to stage. */
  private final ByteBuffer headerScratch;

  /** 4 bytes, reused for the trailing CRC of frames too large to stage. */
  private final ByteBuffer crcScratch;

  private FileChannel channel;
  private int segmentIndex;

  /** Logical size of the current segment: bytes on the channel plus bytes staged. */
  private long segmentSize;

  private long framesWritten;
  private long lastSequence;
  private boolean haveSequence;
  private boolean failed;
  private boolean closed;

  private JournalWriter(Path directory, UUID sessionUuid, JournalWriterConfig config) {
    this.directory = directory;
    this.sessionUuid = sessionUuid;
    this.config = config;
    this.buffer = JournalFormat.allocate(config.bufferBytes());
    this.headerScratch = JournalFormat.allocate(JournalFormat.FRAME_HEADER_BYTES);
    this.crcScratch = JournalFormat.allocate(JournalFormat.FRAME_CRC_BYTES);
  }

  /**
   * Starts a new journal in {@code directory}, which must contain no segments.
   *
   * @throws IOException if the directory already holds journal segments; appending to an existing
   *     journal is {@link #resume}, and doing it by accident would mix two captures into one file
   */
  public static JournalWriter create(Path directory, UUID sessionUuid, JournalWriterConfig config)
      throws IOException {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(sessionUuid, "sessionUuid");
    Objects.requireNonNull(config, "config");
    Files.createDirectories(directory);
    List<Integer> existing = JournalSegments.listSegmentIndexes(directory);
    if (!existing.isEmpty()) {
      throw new IOException(
          "journal directory already has "
              + existing.size()
              + " segment(s); use resume() to append to it: "
              + directory);
    }
    JournalWriter writer = new JournalWriter(directory, sessionUuid, config);
    return started(writer, () -> writer.createSegment(0));
  }

  /**
   * Reopens an existing journal and appends after its last segment's current end. Run {@link
   * JournalRecovery} first after an unclean shutdown: this method trusts the file length it finds,
   * and appending after a torn tail would bury the damage behind valid frames.
   */
  public static JournalWriter resume(Path directory, UUID sessionUuid, JournalWriterConfig config)
      throws IOException {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(sessionUuid, "sessionUuid");
    Objects.requireNonNull(config, "config");
    Files.createDirectories(directory);
    OptionalInt highest = JournalSegments.highestSegmentIndex(directory);
    JournalWriter writer = new JournalWriter(directory, sessionUuid, config);
    return started(
        writer,
        () -> {
          if (highest.isEmpty()) {
            writer.createSegment(0);
          } else {
            writer.openExistingSegment(highest.getAsInt());
          }
        });
  }

  /** Opens the first segment, closing any half-opened channel if that fails. */
  private static JournalWriter started(JournalWriter writer, IoAction open) throws IOException {
    try {
      open.run();
      return writer;
    } catch (IOException failure) {
      if (writer.channel != null) {
        try {
          writer.channel.close();
        } catch (IOException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
  }

  @FunctionalInterface
  private interface IoAction {
    void run() throws IOException;
  }

  /**
   * Appends one payload as a frame.
   *
   * @param payload the bytes to journal, copied verbatim
   * @return where the frame landed, for {@code bep_events.raw_*} provenance
   * @throws IllegalArgumentException if the payload is larger than the configured maximum, or the
   *     stream ordinal does not fit the format's 16 bits. Neither is truncated to fit.
   * @throws IOException if the write fails; the writer is then permanently failed and the capture
   *     must be marked incomplete
   */
  public JournalLocation append(
      SourceKind sourceKind,
      int streamOrdinal,
      long sequence,
      long receiveMicros,
      byte[] payload,
      int payloadOffset,
      int payloadLength)
      throws IOException {
    Objects.requireNonNull(sourceKind, "sourceKind");
    Objects.requireNonNull(payload, "payload");
    Objects.checkFromIndexSize(payloadOffset, payloadLength, payload.length);
    ensureUsable();
    if (payloadLength > config.maxPayloadBytes()) {
      throw new IllegalArgumentException(
          "payload of "
              + payloadLength
              + " bytes exceeds the configured maximum of "
              + config.maxPayloadBytes()
              + " bytes; raise the limit explicitly rather than journaling a partial payload");
    }
    if (streamOrdinal < 0 || streamOrdinal > 0xFFFF) {
      throw new IllegalArgumentException(
          "streamOrdinal must fit 16 unsigned bits, got " + streamOrdinal);
    }

    FrameHeader header =
        new FrameHeader(payloadLength, sourceKind, streamOrdinal, sequence, receiveMicros);
    int totalBytes = header.totalFrameBytes();

    // Rotate at frame boundaries only, and never rotate an empty segment:
    // a frame larger than segmentBytes gets a segment to itself rather than
    // being split across two.
    if (segmentSize > JournalFormat.SEGMENT_HEADER_BYTES
        && segmentSize + totalBytes > config.segmentBytes()) {
      rotate();
    }

    long frameOffset = segmentSize;
    try {
      if (totalBytes > buffer.remaining()) {
        drainBuffer();
      }
      if (totalBytes <= buffer.remaining()) {
        JournalFormat.writeFrame(buffer, header, payload, payloadOffset, payloadLength);
      } else {
        writeUnstagedFrame(header, payload, payloadOffset, payloadLength);
      }
    } catch (IOException | RuntimeException failure) {
      // Including runtime failures: if frame sizing were ever wrong, the
      // staging buffer would hold half a frame, and continuing would splice
      // that half-frame into the middle of otherwise valid data.
      markFailed();
      throw failure;
    }

    segmentSize += totalBytes;
    framesWritten++;
    lastSequence = sequence;
    haveSequence = true;
    return new JournalLocation(segmentIndex, frameOffset, payloadLength);
  }

  /** Convenience for a whole array. */
  public JournalLocation append(
      SourceKind sourceKind, int streamOrdinal, long sequence, long receiveMicros, byte[] payload)
      throws IOException {
    Objects.requireNonNull(payload, "payload");
    return append(sourceKind, streamOrdinal, sequence, receiveMicros, payload, 0, payload.length);
  }

  /**
   * Where the next frame will be written. This is the position to checkpoint: every frame before it
   * has been handed to {@link #append}, though not necessarily forced to disk — recovery reconciles
   * the difference.
   */
  public JournalPosition position() {
    return new JournalPosition(segmentIndex, segmentSize);
  }

  public int currentSegmentIndex() {
    return segmentIndex;
  }

  /** Logical size of the current segment, including bytes still staged in the buffer. */
  public long currentSegmentSize() {
    return segmentSize;
  }

  public long framesWritten() {
    return framesWritten;
  }

  /**
   * The sequence number of the most recently appended frame, or empty when nothing has been
   * appended by this writer. Empty means "not known", not zero — zero is a legal sequence number
   * (plan 11.4).
   */
  public OptionalLong lastSequence() {
    return haveSequence ? OptionalLong.of(lastSequence) : OptionalLong.empty();
  }

  public Path currentSegmentFile() {
    return JournalSegments.segmentFile(directory, segmentIndex);
  }

  public UUID sessionUuid() {
    return sessionUuid;
  }

  public JournalWriterConfig config() {
    return config;
  }

  /** True once a write has failed. The journal is then closed to further appends. */
  public boolean isFailed() {
    return failed;
  }

  /**
   * Hands staged bytes to the operating system. Not a durability barrier: the data is in the page
   * cache, so it survives this process dying but not the machine dying. Cheap enough to call on a
   * timer during live capture.
   */
  public void flush() throws IOException {
    ensureUsable();
    try {
      drainBuffer();
    } catch (IOException failure) {
      markFailed();
      throw failure;
    }
  }

  /**
   * Flushes and {@code fsync}s. This is the durability barrier from plan 9.3: called on rotation
   * and on graceful close, and available to a stricter durability setting that is willing to pay
   * for it per batch.
   */
  public void force() throws IOException {
    ensureUsable();
    try {
      drainBuffer();
      channel.force(true);
    } catch (IOException failure) {
      markFailed();
      throw failure;
    }
  }

  /**
   * Flushes, forces, and closes. Idempotent. After a failed write the buffer is abandoned rather
   * than retried: the frames in it were never reported as durable, and retrying a write onto a full
   * disk only produces a second failure that masks the first.
   */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (!failed) {
        drainBuffer();
        channel.force(true);
      }
    } finally {
      channel.close();
    }
  }

  // ---- internals ----------------------------------------------------------

  /**
   * Writes a frame larger than the staging buffer straight to the channel. The CRC is accumulated
   * over the header and the caller's payload without copying the payload anywhere, so a 64 MiB
   * event costs no extra 64 MiB.
   */
  private void writeUnstagedFrame(FrameHeader header, byte[] payload, int offset, int length)
      throws IOException {
    headerScratch.clear();
    headerScratch.put(JournalFormat.FRAME_MAGIC);
    headerScratch.putInt(length);
    headerScratch.put((byte) header.sourceKind().ordinal());
    headerScratch.putShort((short) header.streamOrdinal());
    headerScratch.putLong(header.sequence());
    headerScratch.putLong(header.receiveMicros());
    headerScratch.flip();

    CRC32C crc = new CRC32C();
    crc.update(headerScratch.duplicate());
    crc.update(payload, offset, length);

    crcScratch.clear();
    crcScratch.putInt((int) crc.getValue());
    crcScratch.flip();

    writeFully(headerScratch);
    writeFully(ByteBuffer.wrap(payload, offset, length));
    writeFully(crcScratch);
  }

  private void drainBuffer() throws IOException {
    if (buffer.position() == 0) {
      return;
    }
    buffer.flip();
    writeFully(buffer);
    buffer.clear();
  }

  private void writeFully(ByteBuffer source) throws IOException {
    while (source.hasRemaining()) {
      int written = channel.write(source);
      if (written <= 0 && source.hasRemaining()) {
        throw new IOException(
            "journal channel accepted no bytes; "
                + source.remaining()
                + " byte(s) of frame data could not be written");
      }
    }
  }

  private void rotate() throws IOException {
    try {
      drainBuffer();
      // Force before the next segment exists. Without this the presence of
      // segment N+1 would not imply segment N's bytes reached the disk, and
      // recovery could find a torn segment followed by a complete one.
      channel.force(true);
      channel.close();
    } catch (IOException failure) {
      markFailed();
      throw failure;
    }
    int next = segmentIndex + 1;
    log.debug(
        "rotating journal: segment {} reached {} bytes, opening segment {}",
        segmentIndex,
        segmentSize,
        next);
    createSegment(next);
  }

  private void createSegment(int index) throws IOException {
    Path file = JournalSegments.segmentFile(directory, index);
    try {
      channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
      ByteBuffer header = SegmentHeader.current(index, sessionUuid).encode();
      writeFully(header);
      // The header is forced immediately so a segment file that exists is
      // always identifiable, even if the process dies before any frame.
      channel.force(true);
    } catch (IOException failure) {
      markFailed();
      throw failure;
    }
    segmentIndex = index;
    segmentSize = JournalFormat.SEGMENT_HEADER_BYTES;
    buffer.clear();
  }

  private void openExistingSegment(int index) throws IOException {
    Path file = JournalSegments.segmentFile(directory, index);
    channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.READ);
    long size = channel.size();
    if (size == 0) {
      // A segment file created just before a crash, or trimmed to nothing
      // by recovery. Give it its header rather than refusing to resume.
      ByteBuffer header = SegmentHeader.current(index, sessionUuid).encode();
      writeFully(header);
      channel.force(true);
      size = JournalFormat.SEGMENT_HEADER_BYTES;
    } else {
      SegmentHeader.read(channel).requireMatches(index, sessionUuid);
    }
    channel.position(size);
    segmentIndex = index;
    segmentSize = size;
    buffer.clear();
  }

  private void ensureUsable() {
    if (closed) {
      throw new IllegalStateException("journal writer is closed");
    }
    if (failed) {
      throw new IllegalStateException(
          "journal writer failed on an earlier write; this capture is incomplete");
    }
  }

  private void markFailed() {
    failed = true;
    // Staged bytes are dropped, not retried: they were never durable and
    // were never acknowledged. The loss is reported through isFailed().
    buffer.clear();
  }
}
