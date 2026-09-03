package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.format.journal.JournalFrame;
import com.holtherndon.bazelviz.format.journal.JournalReader;
import com.holtherndon.bazelviz.format.journal.JournalReaderConfig;
import com.holtherndon.bazelviz.format.journal.JournalSegments;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Fetches the raw bytes behind one {@code bep_events} row.
 *
 * <p>This is the other half of raw-first (ADR-004). Storing {@code raw_segment}/{@code
 * raw_offset}/{@code raw_length} is only worth anything if the bytes can be got back, and this is
 * what the "inspect raw protobuf" pane calls, what a future reindex would read from, and what the
 * round-trip test uses to prove that every stored offset points at the payload it claims to.
 *
 * <p>Every read goes through {@link JournalReader}, so the frame's CRC is verified before its
 * payload is returned. A row whose offset points at damaged or non-frame bytes therefore fails
 * loudly here rather than yielding plausible garbage to a decoder.
 *
 * <p>Opens and closes a channel per call. That is the right trade for inspecting one selected
 * event; a bulk reindex should scan segments in order with {@link JournalReader} directly instead
 * of calling this per row.
 *
 * <p>Blocking I/O: never call it on the Swing EDT.
 */
public final class JournalPayloadReader {

  private final Path journalDirectory;
  private final JournalReaderConfig config;

  public JournalPayloadReader(Path journalDirectory) {
    this(journalDirectory, JournalReaderConfig.defaults());
  }

  public JournalPayloadReader(Path journalDirectory, JournalReaderConfig config) {
    this.journalDirectory = Objects.requireNonNull(journalDirectory, "journalDirectory");
    this.config = Objects.requireNonNull(config, "config").withReadPayloads(true);
  }

  /** For a managed session, the reader over its {@code raw/} directory. */
  public static JournalPayloadReader forSession(ManagedSessionLayout layout) {
    return new JournalPayloadReader(layout.rawDirectory());
  }

  /** For a managed session directory on disk. */
  public static JournalPayloadReader forSession(Path sessionRoot) {
    return forSession(ManagedSessionLayout.at(sessionRoot));
  }

  /**
   * The payload bytes at {@code location}, verbatim.
   *
   * @throws IOException if the segment is missing, the offset is not a frame boundary, the frame
   *     fails its checksum, or the frame's payload length disagrees with the stored one. All four
   *     mean the row and the journal disagree, which must be reported, never papered over
   */
  public byte[] read(RawLocation location) throws IOException {
    Objects.requireNonNull(location, "location");
    Path segment = JournalSegments.segmentFile(journalDirectory, location.segment());
    long frameOffset = location.offset();
    if (frameOffset < JournalFormat.SEGMENT_HEADER_BYTES) {
      throw new IOException(
          "raw offset "
              + frameOffset
              + " lies inside the header of segment "
              + location.segment()
              + "; frames start at "
              + JournalFormat.SEGMENT_HEADER_BYTES);
    }
    try (JournalReader reader = JournalReader.open(segment, frameOffset, config)) {
      JournalFrame frame = reader.next();
      if (frame == null) {
        throw new IOException(
            "no journal frame at segment "
                + location.segment()
                + " offset "
                + frameOffset
                + ": "
                + reader.result().status()
                + " ("
                + reader.result().detail()
                + ")");
      }
      if (frame.frameOffset() != frameOffset) {
        throw new IOException(
            "journal frame at segment "
                + location.segment()
                + " starts at "
                + frame.frameOffset()
                + ", not the recorded "
                + frameOffset);
      }
      byte[] payload = frame.requirePayload();
      if (payload.length != location.length()) {
        throw new IOException(
            "journal frame at segment "
                + location.segment()
                + " offset "
                + frameOffset
                + " holds "
                + payload.length
                + " payload bytes, but the event"
                + " row records "
                + location.length());
      }
      return payload;
    }
  }

  /** The frame — header included — at {@code location}, checksum verified. */
  public JournalFrame readFrame(RawLocation location) throws IOException {
    Objects.requireNonNull(location, "location");
    Path segment = JournalSegments.segmentFile(journalDirectory, location.segment());
    try (JournalReader reader = JournalReader.open(segment, location.offset(), config)) {
      JournalFrame frame = reader.next();
      if (frame == null) {
        throw new IOException(
            "no journal frame at segment "
                + location.segment()
                + " offset "
                + location.offset()
                + ": "
                + reader.result().detail());
      }
      return frame;
    }
  }
}
