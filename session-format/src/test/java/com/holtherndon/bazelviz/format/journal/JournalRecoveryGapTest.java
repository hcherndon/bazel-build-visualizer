package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for two ways recovery used to misreport a journal's state. Both were found by
 * audit, and both made recovery claim something untrue — once that a mutilated journal was
 * complete, once that an intact one was truncated.
 */
class JournalRecoveryGapTest {

  @TempDir Path directory;

  private static final UUID SESSION = UUID.fromString("0f0f0f0f-0000-4000-8000-00000f0f0f0f");

  private void writeFrames(int count, int payloadBytes, long segmentBytes) throws IOException {
    JournalWriterConfig config = JournalWriterConfig.defaults().withSegmentBytes(segmentBytes);
    try (JournalWriter writer = JournalWriter.create(directory, SESSION, config)) {
      byte[] payload = new byte[payloadBytes];
      for (int i = 0; i < count; i++) {
        Arrays.fill(payload, (byte) ('a' + (i % 26)));
        writer.append(
            SourceKind.BEP_BINARY, 0, i, 1_700_000_000_000_000L + i, payload, 0, payload.length);
      }
    }
  }

  @Test
  void losingSegmentsFromTheFrontIsReportedRatherThanCalledComplete() throws IOException {
    // Small segments so the frames rotate into several files.
    writeFrames(8, 512, JournalFormat.SEGMENT_HEADER_BYTES + 3 * (512 + 31));
    List<Integer> before = JournalSegments.listSegmentIndexes(directory);
    assertThat(before).as("test needs several segments to delete one").hasSizeGreaterThan(2);

    Files.delete(JournalSegments.segmentFile(directory, before.get(0)));

    RecoveryReport report =
        JournalRecovery.inspect(directory, Optional.empty(), JournalReaderConfig.verifyOnly());

    assertThat(report.missingSegments())
        .as("a journal always starts at segment 0, so a missing front is loss")
        .contains(before.get(0));
    assertThat(report.completeness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
    assertThat(report.isClean())
        .as("half the capture is gone; the report must not say it ends cleanly")
        .isFalse();
  }

  @Test
  void anEmptyTrailingSegmentIsACleanEndAndRecoveryConverges() throws IOException {
    writeFrames(4, 256, JournalFormat.DEFAULT_SEGMENT_BYTES);
    int next = JournalSegments.listSegmentIndexes(directory).getLast() + 1;
    // Exactly what a crash between creating a segment and forcing its
    // header leaves behind — and what recovery's own repair produces.
    Files.createFile(JournalSegments.segmentFile(directory, next));

    RecoveryReport first = JournalRecovery.recover(directory);
    assertThat(first.status()).isEqualTo(JournalScanStatus.OK);
    assertThat(first.bytesTruncated()).isZero();
    assertThat(first.framesVerified()).isEqualTo(4);

    // Idempotent: a second pass must reach the same clean answer.
    RecoveryReport second = JournalRecovery.recover(directory);
    assertThat(second.status()).isEqualTo(JournalScanStatus.OK);
    assertThat(second.bytesTruncated()).isZero();
    assertThat(second.framesVerified()).isEqualTo(first.framesVerified());
  }
}
