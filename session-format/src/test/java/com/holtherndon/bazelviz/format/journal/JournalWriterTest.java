package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalWriterTest {

  private static final JournalWriterConfig SMALL_BUFFER =
      new JournalWriterConfig(1L << 20, 4096, 64);

  @TempDir Path directory;

  @Test
  void framesRoundTripByteIdentically() throws IOException {
    List<byte[]> payloads =
        JournalFixture.writeFrames(
            directory, SMALL_BUFFER, 50, i -> JournalFixture.payload(10 + i * 7, i));

    List<JournalFrame> frames =
        JournalFixture.readAll(JournalFixture.segment(directory, 0), SMALL_BUFFER.readerConfig());

    assertThat(frames).hasSize(50);
    for (int i = 0; i < 50; i++) {
      JournalFrame frame = frames.get(i);
      assertThat(frame.requirePayload())
          .as("payload %d must come back byte-identical", i)
          .isEqualTo(payloads.get(i));
      assertThat(frame.header().sequence()).isEqualTo(1000L + i);
      assertThat(frame.header().streamOrdinal()).isEqualTo(i % 3);
      assertThat(frame.header().receiveMicros()).isEqualTo(1_700_000_000_000_000L + i);
      assertThat(frame.header().sourceKind()).isEqualTo(SourceKind.BEP_BINARY);
    }
  }

  @Test
  void zeroLengthAndMaximumSizedPayloadsAreBothLegal() throws IOException {
    JournalWriterConfig config = new JournalWriterConfig(1L << 24, 4096, 512);
    byte[] empty = new byte[0];
    byte[] maximum = JournalFixture.payload(config.maxPayloadBytes(), 99);

    try (JournalWriter writer = JournalWriter.create(directory, JournalFixture.SESSION, config)) {
      writer.append(SourceKind.BES_ENVELOPE, 0, 0L, 1L, empty);
      writer.append(SourceKind.BEP_JSON_RECORD, 1, 1L, 2L, maximum);
      writer.append(SourceKind.BEP_BINARY, 2, 2L, 3L, empty);
    }

    List<JournalFrame> frames =
        JournalFixture.readAll(JournalFixture.segment(directory, 0), config.readerConfig());

    assertThat(frames).hasSize(3);
    assertThat(frames.get(0).requirePayload()).isEmpty();
    assertThat(frames.get(0).header().payloadLength()).isZero();
    assertThat(frames.get(1).requirePayload()).isEqualTo(maximum);
    assertThat(frames.get(2).requirePayload()).isEmpty();
  }

  @Test
  void payloadLargerThanTheConfiguredMaximumIsRejectedRatherThanTruncated() throws IOException {
    JournalWriterConfig config = new JournalWriterConfig(1L << 20, 128, 512);
    try (JournalWriter writer = JournalWriter.create(directory, JournalFixture.SESSION, config)) {
      byte[] tooBig = JournalFixture.payload(129, 1);

      assertThatThrownBy(() -> writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, tooBig))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("129")
          .hasMessageContaining("128");

      // The rejected frame must leave no trace: nothing partial, nothing counted.
      assertThat(writer.framesWritten()).isZero();
      assertThat(writer.position().byteOffset()).isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);
    }
  }

  @Test
  void streamOrdinalOutsideSixteenBitsIsRejected() throws IOException {
    try (JournalWriter writer =
        JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      assertThatThrownBy(() -> writer.append(SourceKind.BEP_BINARY, 0x10000, 1L, 1L, new byte[1]))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("16");
      assertThatThrownBy(() -> writer.append(SourceKind.BEP_BINARY, -1, 1L, 1L, new byte[1]))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void appendReportsWhereTheFrameLanded() throws IOException {
    try (JournalWriter writer =
        JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      JournalLocation first = writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, new byte[10]);
      JournalLocation second = writer.append(SourceKind.BEP_BINARY, 0, 2L, 2L, new byte[20]);

      assertThat(first.segmentIndex()).isZero();
      assertThat(first.frameOffset()).isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);
      assertThat(first.payloadLength()).isEqualTo(10);
      assertThat(first.payloadOffset())
          .isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES + JournalFormat.FRAME_HEADER_BYTES);
      assertThat(second.frameOffset()).isEqualTo(first.end().byteOffset());
      assertThat(writer.position()).isEqualTo(second.end());
      assertThat(writer.lastSequence()).hasValue(2L);
    }
  }

  @Test
  void lastSequenceIsAbsentRatherThanZeroBeforeAnythingIsWritten() throws IOException {
    try (JournalWriter writer =
        JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      assertThat(writer.lastSequence()).isEmpty();
      writer.append(SourceKind.BEP_BINARY, 0, 0L, 1L, new byte[1]);
      // Sequence zero is a real sequence, so it must read as present-and-zero.
      assertThat(writer.lastSequence()).hasValue(0L);
    }
  }

  @Test
  void framesLargerThanTheStagingBufferAreWrittenCorrectly() throws IOException {
    // 64-byte buffer, 3 KiB payloads: every frame takes the unstaged path.
    JournalWriterConfig config = new JournalWriterConfig(1L << 20, 8192, 64);
    List<byte[]> payloads =
        JournalFixture.writeFrames(directory, config, 5, i -> JournalFixture.payload(3072, i));

    List<JournalFrame> frames =
        JournalFixture.readAll(JournalFixture.segment(directory, 0), config.readerConfig());

    assertThat(frames).hasSize(5);
    for (int i = 0; i < 5; i++) {
      assertThat(frames.get(i).requirePayload()).isEqualTo(payloads.get(i));
    }
  }

  @Test
  void payloadSliceIsJournaledWithoutTheSurroundingBytes() throws IOException {
    byte[] backing = JournalFixture.payload(100, 5);
    try (JournalWriter writer =
        JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, backing, 10, 30);
    }

    List<JournalFrame> frames =
        JournalFixture.readAll(JournalFixture.segment(directory, 0), SMALL_BUFFER.readerConfig());

    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).requirePayload()).isEqualTo(Arrays.copyOfRange(backing, 10, 40));
  }

  @Test
  void createRefusesToWriteIntoAJournalThatAlreadyHasSegments() throws IOException {
    JournalFixture.writeFrames(directory, SMALL_BUFFER, 2, i -> new byte[4]);

    assertThatThrownBy(() -> JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("resume");
  }

  @Test
  void resumeAppendsAfterTheExistingFrames() throws IOException {
    JournalFixture.writeFrames(directory, SMALL_BUFFER, 3, i -> JournalFixture.payload(16, i));

    try (JournalWriter writer =
        JournalWriter.resume(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      assertThat(writer.currentSegmentIndex()).isZero();
      writer.append(SourceKind.BEP_BINARY, 0, 4000L, 1L, JournalFixture.payload(16, 99));
    }

    List<JournalFrame> frames =
        JournalFixture.readAll(JournalFixture.segment(directory, 0), SMALL_BUFFER.readerConfig());
    assertThat(frames).hasSize(4);
    assertThat(frames.get(3).header().sequence()).isEqualTo(4000L);
    assertThat(frames.get(3).requirePayload()).isEqualTo(JournalFixture.payload(16, 99));
  }

  @Test
  void resumeRefusesAJournalBelongingToAnotherSession() throws IOException {
    JournalFixture.writeFrames(directory, SMALL_BUFFER, 1, i -> new byte[4]);

    assertThatThrownBy(
            () -> JournalWriter.resume(directory, JournalFixture.OTHER_SESSION, SMALL_BUFFER))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("belongs to session");
  }

  @Test
  void resumeGivesAHeaderlessSegmentItsHeader() throws IOException {
    JournalFixture.writeFrames(directory, SMALL_BUFFER, 1, i -> new byte[4]);
    Path segment = JournalFixture.segment(directory, 0);
    // A crash between creating the file and writing its header.
    JournalFixture.truncateFile(segment, 0);

    try (JournalWriter writer =
        JournalWriter.resume(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      writer.append(SourceKind.BEP_BINARY, 0, 7L, 1L, JournalFixture.payload(8, 3));
    }

    List<JournalFrame> frames = JournalFixture.readAll(segment, SMALL_BUFFER.readerConfig());
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).header().sequence()).isEqualTo(7L);
  }

  @Test
  void segmentHeaderIsWrittenBeforeAnyFrame() throws IOException {
    try (JournalWriter writer =
        JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      assertThat(writer.position().byteOffset()).isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);
      // Nothing has been appended, yet the header is already durable so the
      // file is identifiable even if the process dies right here.
      assertThat(Files.size(writer.currentSegmentFile()))
          .isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);
    }
  }

  @Test
  void flushMakesFramesVisibleWithoutClosing() throws IOException {
    try (JournalWriter writer =
        JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER)) {
      writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, JournalFixture.payload(8, 1));
      writer.flush();

      SegmentScan scan =
          JournalFixture.scan(writer.currentSegmentFile(), SMALL_BUFFER.readerConfig());
      assertThat(scan.framesRead()).isEqualTo(1);
      assertThat(scan.status()).isEqualTo(JournalScanStatus.OK);
    }
  }

  @Test
  void closeIsIdempotentAndBlocksFurtherAppends() throws IOException {
    JournalWriter writer = JournalWriter.create(directory, JournalFixture.SESSION, SMALL_BUFFER);
    writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, new byte[4]);
    writer.close();
    writer.close();

    assertThatThrownBy(() -> writer.append(SourceKind.BEP_BINARY, 0, 2L, 2L, new byte[4]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void writerConfigRejectsUnusableLimits() {
    assertThatThrownBy(() -> new JournalWriterConfig(8L, 1024, 1024))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("segmentBytes");
    assertThatThrownBy(() -> new JournalWriterConfig(1L << 20, 1024, 4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bufferBytes");
    assertThatThrownBy(
            () ->
                new JournalWriterConfig(
                    1L << 20, JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES + 1, 1024))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ceiling");
  }
}
