package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reader's contract under damage: every frame verified before the damage is still returned, the
 * stop reason is specific, and the offset is exact.
 */
class JournalReaderFailureTest {

  private static final int PAYLOAD_SIZE = 60;
  private static final int FRAME_SIZE = JournalFixture.frameBytes(PAYLOAD_SIZE);
  private static final long FIRST_FRAME = JournalFormat.SEGMENT_HEADER_BYTES;
  private static final long SECOND_FRAME = FIRST_FRAME + FRAME_SIZE;
  private static final long THIRD_FRAME = SECOND_FRAME + FRAME_SIZE;

  private static final JournalWriterConfig CONFIG = new JournalWriterConfig(1L << 20, 4096, 256);
  private static final JournalReaderConfig READER = CONFIG.readerConfig();

  @TempDir Path root;

  private Path threeFrameSegment(String name) throws IOException {
    Path directory = root.resolve(name);
    Files.createDirectories(directory);
    JournalFixture.writeFrames(directory, CONFIG, 3, i -> JournalFixture.payload(PAYLOAD_SIZE, i));
    return JournalFixture.segment(directory, 0);
  }

  // ---- truncation ---------------------------------------------------------

  @Test
  void aCutInTheMiddleOfAFrameHeaderReportsATruncatedTailAtThatFrame() throws IOException {
    Path segment = threeFrameSegment("mid-header");
    JournalFixture.truncateFile(segment, THIRD_FRAME + 10);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.framesRead()).isEqualTo(2);
    assertThat(scan.status()).isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
    assertThat(scan.endOffset()).isEqualTo(THIRD_FRAME);
    assertThat(scan.trailingBytes()).isEqualTo(10);
    assertThat(scan.detail()).contains("frame-header bytes");
  }

  @Test
  void aCutInTheMiddleOfAPayloadReportsATruncatedTailAtThatFrame() throws IOException {
    Path segment = threeFrameSegment("mid-payload");
    JournalFixture.truncateFile(segment, THIRD_FRAME + JournalFormat.FRAME_HEADER_BYTES + 20);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.framesRead()).isEqualTo(2);
    assertThat(scan.status()).isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
    assertThat(scan.endOffset()).isEqualTo(THIRD_FRAME);
    assertThat(scan.lastSequence()).hasValue(1001L);
  }

  @Test
  void aCutInTheMiddleOfTheTrailingCrcReportsATruncatedTailAtThatFrame() throws IOException {
    Path segment = threeFrameSegment("mid-crc");
    JournalFixture.truncateFile(
        segment, THIRD_FRAME + JournalFormat.FRAME_HEADER_BYTES + PAYLOAD_SIZE + 2);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.framesRead()).isEqualTo(2);
    assertThat(scan.status()).isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
    assertThat(scan.endOffset()).isEqualTo(THIRD_FRAME);
    assertThat(scan.trailingBytes()).isEqualTo(JournalFormat.FRAME_HEADER_BYTES + PAYLOAD_SIZE + 2);
  }

  @Test
  void everyCutOffsetInsideTheLastFrameIsReportedAtTheSamePlace() throws IOException {
    // Exhaustive: whatever byte the crash landed on, the answer is the same.
    for (int cut = 1; cut < FRAME_SIZE; cut++) {
      Path segment = threeFrameSegment("sweep-" + cut);
      JournalFixture.truncateFile(segment, THIRD_FRAME + cut);

      SegmentScan scan = JournalFixture.scan(segment, READER);

      assertThat(scan.status())
          .as("cut %d bytes into the third frame", cut)
          .isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
      assertThat(scan.endOffset()).isEqualTo(THIRD_FRAME);
      assertThat(scan.framesRead()).isEqualTo(2);
    }
  }

  @Test
  void aSegmentHoldingOnlyItsHeaderIsCleanAndEmpty() throws IOException {
    Path segment = threeFrameSegment("header-only");
    JournalFixture.truncateFile(segment, JournalFormat.SEGMENT_HEADER_BYTES);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.OK);
    assertThat(scan.framesRead()).isZero();
    assertThat(scan.lastSequence()).isEmpty();
    assertThat(scan.endOffset()).isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);
  }

  // ---- corruption ---------------------------------------------------------

  @Test
  void aFlippedPayloadByteFailsThatFrameAndKeepsTheEarlierOnes() throws IOException {
    Path segment = threeFrameSegment("payload-flip");
    JournalFixture.flipByte(segment, SECOND_FRAME + JournalFormat.FRAME_HEADER_BYTES + 5);

    List<JournalFrame> frames = new ArrayList<>();
    SegmentScan scan;
    try (JournalReader reader = JournalReader.open(segment, READER)) {
      JournalFrame frame;
      while ((frame = reader.next()) != null) {
        frames.add(frame);
      }
      scan = reader.result();
    }

    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).requirePayload())
        .as("a later bad frame must not invalidate an earlier good one")
        .isEqualTo(JournalFixture.payload(PAYLOAD_SIZE, 0));
    assertThat(scan.status()).isEqualTo(JournalScanStatus.CRC_MISMATCH);
    assertThat(scan.endOffset()).isEqualTo(SECOND_FRAME);
    assertThat(scan.detail()).contains("checksum mismatch");
  }

  @Test
  void aFlippedHeaderByteAlsoFailsTheChecksum() throws IOException {
    Path segment = threeFrameSegment("header-flip");
    // The sequence-number field, which the CRC covers along with the payload.
    JournalFixture.flipByte(segment, SECOND_FRAME + 11);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.CRC_MISMATCH);
    assertThat(scan.endOffset()).isEqualTo(SECOND_FRAME);
    assertThat(scan.framesRead()).isEqualTo(1);
  }

  @Test
  void aBrokenFrameMagicStopsTheScanWithBadMagic() throws IOException {
    Path segment = threeFrameSegment("magic");
    JournalFixture.flipByte(segment, SECOND_FRAME + 1);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.BAD_MAGIC);
    assertThat(scan.endOffset()).isEqualTo(SECOND_FRAME);
    assertThat(scan.framesRead()).isEqualTo(1);
  }

  @Test
  void anAbsurdDeclaredLengthIsRejectedWithoutAllocatingIt() throws IOException {
    Path segment = threeFrameSegment("length");
    writeInt(segment, SECOND_FRAME + JournalFormat.FRAME_MAGIC.length, Integer.MAX_VALUE);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.LENGTH_OUT_OF_BOUNDS);
    assertThat(scan.endOffset()).isEqualTo(SECOND_FRAME);
    assertThat(scan.detail()).contains(String.valueOf(Integer.MAX_VALUE));
  }

  @Test
  void aNegativeDeclaredLengthIsRejected() throws IOException {
    Path segment = threeFrameSegment("negative-length");
    writeInt(segment, SECOND_FRAME + JournalFormat.FRAME_MAGIC.length, -8);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.LENGTH_OUT_OF_BOUNDS);
    assertThat(scan.endOffset()).isEqualTo(SECOND_FRAME);
  }

  @Test
  void aPlausibleButWrongLengthFailsOnTheChecksumRatherThanBeingBelieved() throws IOException {
    Path segment = threeFrameSegment("shortened-length");
    writeInt(segment, SECOND_FRAME + JournalFormat.FRAME_MAGIC.length, PAYLOAD_SIZE - 8);

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.CRC_MISMATCH);
    assertThat(scan.endOffset()).isEqualTo(SECOND_FRAME);
  }

  @Test
  void garbageAppendedAfterTheLastFrameIsReportedNotSkipped() throws IOException {
    Path segment = threeFrameSegment("garbage-tail");
    JournalFixture.appendBytes(
        segment, new byte[] {'n', 'o', 't', ' ', 'a', ' ', 'f', 'r', 'a', 'm', 'e'});

    SegmentScan scan = JournalFixture.scan(segment, READER);

    // Fewer bytes than a frame header, so it reads as a torn tail.
    assertThat(scan.status()).isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
    assertThat(scan.framesRead()).isEqualTo(3);
    assertThat(scan.endOffset()).isEqualTo(THIRD_FRAME + FRAME_SIZE);
    assertThat(scan.trailingBytes()).isEqualTo(11);
  }

  @Test
  void aFullFrameOfGarbageAfterTheLastFrameReportsBadMagic() throws IOException {
    Path segment = threeFrameSegment("garbage-frame");
    JournalFixture.appendBytes(segment, JournalFixture.payload(FRAME_SIZE * 2, 77));

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.BAD_MAGIC);
    assertThat(scan.framesRead()).isEqualTo(3);
    assertThat(scan.endOffset()).isEqualTo(THIRD_FRAME + FRAME_SIZE);
  }

  // ---- frames this build cannot interpret ---------------------------------

  @Test
  void anIntactFrameWithAnUnknownSourceKindIsReportedRatherThanTreatedAsDamage()
      throws IOException {
    Path segment = threeFrameSegment("future-kind");
    // A source kind from a hypothetical later release, with a correct CRC.
    byte[] fromTheFuture =
        JournalFixture.handBuiltFrame(9, 0, 5000L, 1L, JournalFixture.payload(16, 3));
    JournalFixture.appendBytes(segment, fromTheFuture);
    long fourthFrame = THIRD_FRAME + FRAME_SIZE;

    SegmentScan scan = JournalFixture.scan(segment, READER);

    assertThat(scan.status()).isEqualTo(JournalScanStatus.UNSUPPORTED_SOURCE_KIND);
    assertThat(scan.framesRead()).isEqualTo(3);
    assertThat(scan.endOffset()).isEqualTo(fourthFrame);
    // Crucially: these bytes are intact, so recovery must never remove them.
    assertThat(scan.status().isTruncatable()).isFalse();
    assertThat(scan.hasTruncatableTail()).isFalse();
  }

  // ---- reader configuration ----------------------------------------------

  @Test
  void aStartOffsetPastTheEndOfTheSegmentIsRefused() throws IOException {
    Path segment = threeFrameSegment("past-end");
    long size = Files.size(segment);

    assertThatThrownBy(() -> JournalReader.open(segment, size + 1, READER))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("past the end");
  }

  @Test
  void aStartOffsetInsideTheSegmentHeaderIsRefused() throws IOException {
    Path segment = threeFrameSegment("in-header");

    assertThatThrownBy(() -> JournalReader.open(segment, 8, READER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("segment header");
  }

  @Test
  void scanningFromALaterFrameSkipsTheEarlierOnesWithoutReadingThem() throws IOException {
    Path segment = threeFrameSegment("resume-offset");

    try (JournalReader reader = JournalReader.open(segment, THIRD_FRAME, READER)) {
      JournalFrame frame = reader.next();
      assertThat(frame).isNotNull();
      assertThat(frame.header().sequence()).isEqualTo(1002L);
      assertThat(reader.next()).isNull();
      assertThat(reader.result().framesRead()).isEqualTo(1);
      assertThat(reader.result().startOffset()).isEqualTo(THIRD_FRAME);
    }
  }

  @Test
  void aFileThatIsNotAJournalSegmentIsRefusedOnOpen() throws IOException {
    Path notASegment = root.resolve(JournalFormat.segmentFileName(0));
    Files.write(notASegment, JournalFixture.payload(200, 4));

    assertThatThrownBy(() -> JournalReader.open(notASegment, READER))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("magic");
  }

  @Test
  void aSegmentFromAnUnreadableFormatVersionIsRefusedRatherThanMisread() throws IOException {
    Path segment = threeFrameSegment("future-version");
    writeInt(segment, JournalFormat.MAGIC.length, 99);

    assertThatThrownBy(() -> JournalReader.open(segment, READER))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("format version 99");
  }

  @Test
  void resultIsRefusedWhileTheScanIsStillRunning() throws IOException {
    Path segment = threeFrameSegment("incomplete-scan");

    try (JournalReader reader = JournalReader.open(segment, READER)) {
      reader.next();
      assertThatThrownBy(reader::result)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("has not finished");
      assertThat(reader.status()).isEmpty();
      assertThat(reader.stopOffset()).isEmpty();
    }
  }

  private static void writeInt(Path file, long offset, int value) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = JournalFormat.allocate(4);
      buffer.putInt(value).flip();
      channel.write(buffer, offset);
    }
  }
}
