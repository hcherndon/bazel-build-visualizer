package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRotationTest {

  @TempDir Path directory;

  @Test
  void writingPastTheSegmentSizeStartsANewSegment() throws IOException {
    int payloadSize = 100;
    int frameSize = JournalFixture.frameBytes(payloadSize);
    // Room for exactly four frames after the segment header.
    long segmentBytes = JournalFormat.SEGMENT_HEADER_BYTES + 4L * frameSize;
    JournalWriterConfig config = new JournalWriterConfig(segmentBytes, 4096, 256);

    JournalFixture.writeFrames(directory, config, 10, i -> JournalFixture.payload(payloadSize, i));

    assertThat(JournalSegments.listSegmentIndexes(directory)).containsExactly(0, 1, 2);
    assertThat(Files.size(JournalFixture.segment(directory, 0))).isEqualTo(segmentBytes);
    assertThat(Files.size(JournalFixture.segment(directory, 1))).isEqualTo(segmentBytes);
    assertThat(Files.size(JournalFixture.segment(directory, 2)))
        .isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES + 2L * frameSize);
  }

  @Test
  void everySegmentEndsOnAFrameBoundarySoNoFrameSpansTwoFiles() throws IOException {
    // Segment size deliberately not a multiple of the frame size.
    JournalWriterConfig config = new JournalWriterConfig(777L, 4096, 64);
    List<byte[]> payloads =
        JournalFixture.writeFrames(directory, config, 40, i -> JournalFixture.payload(50 + i, i));

    List<byte[]> readBack = new ArrayList<>();
    for (int index : JournalSegments.listSegmentIndexes(directory)) {
      Path file = JournalFixture.segment(directory, index);
      try (JournalReader reader = JournalReader.open(file, config.readerConfig())) {
        JournalFrame frame;
        while ((frame = reader.next()) != null) {
          readBack.add(frame.requirePayload());
        }
        SegmentScan scan = reader.result();
        // A frame split across a rotation would show up here as a torn tail.
        assertThat(scan.status())
            .as("segment %d must end exactly on a frame boundary", index)
            .isEqualTo(JournalScanStatus.OK);
        assertThat(scan.endOffset()).isEqualTo(Files.size(file));
        assertThat(Files.size(file)).isLessThanOrEqualTo(config.segmentBytes());
      }
    }

    assertThat(readBack).hasSize(payloads.size());
    for (int i = 0; i < payloads.size(); i++) {
      assertThat(readBack.get(i)).isEqualTo(payloads.get(i));
    }
  }

  @Test
  void aFrameLargerThanTheSegmentSizeGetsASegmentToItselfRatherThanBeingSplit() throws IOException {
    int hugePayload = 2000;
    JournalWriterConfig config = new JournalWriterConfig(600L, 4096, 128);

    try (JournalWriter writer = JournalWriter.create(directory, JournalFixture.SESSION, config)) {
      writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, JournalFixture.payload(100, 1));
      writer.append(SourceKind.BEP_BINARY, 0, 2L, 2L, JournalFixture.payload(hugePayload, 2));
      writer.append(SourceKind.BEP_BINARY, 0, 3L, 3L, JournalFixture.payload(100, 3));
    }

    List<Integer> segments = JournalSegments.listSegmentIndexes(directory);
    assertThat(segments).containsExactly(0, 1, 2);

    // The oversized frame is whole, in its own segment, over the nominal size.
    List<JournalFrame> middle =
        JournalFixture.readAll(JournalFixture.segment(directory, 1), config.readerConfig());
    assertThat(middle).hasSize(1);
    assertThat(middle.get(0).header().payloadLength()).isEqualTo(hugePayload);
    assertThat(Files.size(JournalFixture.segment(directory, 1)))
        .isGreaterThan(config.segmentBytes());
  }

  @Test
  void locationsReportTheSegmentTheFrameActuallyLandedIn() throws IOException {
    int payloadSize = 64;
    int frameSize = JournalFixture.frameBytes(payloadSize);
    long segmentBytes = JournalFormat.SEGMENT_HEADER_BYTES + 2L * frameSize;
    JournalWriterConfig config = new JournalWriterConfig(segmentBytes, 4096, 128);

    List<JournalLocation> locations = new ArrayList<>();
    try (JournalWriter writer = JournalWriter.create(directory, JournalFixture.SESSION, config)) {
      for (int i = 0; i < 5; i++) {
        locations.add(
            writer.append(SourceKind.BEP_BINARY, 0, i, i, JournalFixture.payload(payloadSize, i)));
      }
    }

    assertThat(locations.get(0).segmentIndex()).isZero();
    assertThat(locations.get(1).segmentIndex()).isZero();
    assertThat(locations.get(2).segmentIndex()).isEqualTo(1);
    assertThat(locations.get(3).segmentIndex()).isEqualTo(1);
    assertThat(locations.get(4).segmentIndex()).isEqualTo(2);
    assertThat(locations.get(2).frameOffset()).isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);

    // Each location must point at the exact bytes of its own frame.
    for (JournalLocation location : locations) {
      Path file = JournalFixture.segment(directory, location.segmentIndex());
      try (JournalReader reader =
          JournalReader.open(file, location.frameOffset(), config.readerConfig())) {
        JournalFrame frame = reader.next();
        assertThat(frame).isNotNull();
        assertThat(frame.frameOffset()).isEqualTo(location.frameOffset());
        assertThat(frame.header().payloadLength()).isEqualTo(location.payloadLength());
      }
    }
  }

  @Test
  void rotationCarriesTheSessionIdentityIntoEverySegment() throws IOException {
    JournalWriterConfig config = new JournalWriterConfig(200L, 4096, 64);
    JournalFixture.writeFrames(directory, config, 12, i -> JournalFixture.payload(40, i));

    List<Integer> segments = JournalSegments.listSegmentIndexes(directory);
    assertThat(segments).hasSizeGreaterThan(2);
    for (int index : segments) {
      try (JournalReader reader =
          JournalReader.open(JournalFixture.segment(directory, index), config.readerConfig())) {
        assertThat(reader.sessionUuid()).isEqualTo(JournalFixture.SESSION);
        assertThat(reader.segmentIndex()).isEqualTo(index);
      }
    }
  }
}
