package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRecoveryTest {

    private static final int PAYLOAD_SIZE = 48;
    private static final int FRAME_SIZE = JournalFixture.frameBytes(PAYLOAD_SIZE);
    private static final JournalWriterConfig CONFIG = new JournalWriterConfig(1L << 20, 4096, 256);
    private static final JournalReaderConfig READER = JournalReaderConfig.verifyOnly();

    @TempDir
    Path directory;

    private void writeFrames(int count) throws IOException {
        JournalFixture.writeFrames(directory, CONFIG, count, i -> JournalFixture.payload(PAYLOAD_SIZE, i));
    }

    private long endOfFrames(int count) {
        return JournalFormat.SEGMENT_HEADER_BYTES + (long) count * FRAME_SIZE;
    }

    @Test
    void aCleanJournalIsLeftExactlyAsItWas() throws IOException {
        writeFrames(5);
        Path segment = JournalFixture.segment(directory, 0);
        byte[] before = Files.readAllBytes(segment);

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(Files.readAllBytes(segment)).isEqualTo(before);
        assertThat(report.status()).isEqualTo(JournalScanStatus.OK);
        assertThat(report.bytesTruncated()).isZero();
        assertThat(report.framesVerified()).isEqualTo(5);
        assertThat(report.lastValidPosition()).hasValue(new JournalPosition(0, endOfFrames(5)));
        assertThat(report.lastSequence()).hasValue(1004L);
        assertThat(report.isClean()).isTrue();
        assertThat(report.completeness()).isEqualTo(Completeness.COMPLETE);
    }

    @Test
    void garbageOnTheEndIsTrimmedBackToTheLastValidFrame() throws IOException {
        writeFrames(4);
        Path segment = JournalFixture.segment(directory, 0);
        long validSize = Files.size(segment);
        byte[] validBytes = Files.readAllBytes(segment);
        JournalFixture.appendBytes(segment, JournalFixture.payload(500, 42));

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(Files.size(segment)).isEqualTo(validSize);
        assertThat(Files.readAllBytes(segment))
                .as("valid bytes must be untouched by the repair")
                .isEqualTo(validBytes);
        assertThat(report.bytesTruncated()).isEqualTo(500);
        assertThat(report.truncationOccurred()).isTrue();
        assertThat(report.status()).isEqualTo(JournalScanStatus.BAD_MAGIC);
        assertThat(report.lastValidPosition()).hasValue(new JournalPosition(0, validSize));
        assertThat(report.lastSequence()).hasValue(1003L);
        assertThat(report.completeness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
        assertThat(report.detail()).contains("segment 0 at byte " + validSize);
    }

    @Test
    void aTornFinalFrameIsTrimmedAndReportedAsTruncationNotCorruption() throws IOException {
        writeFrames(4);
        Path segment = JournalFixture.segment(directory, 0);
        // A crash part-way through appending the fourth frame.
        JournalFixture.truncateFile(segment, endOfFrames(3) + 12);

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(Files.size(segment)).isEqualTo(endOfFrames(3));
        assertThat(report.status()).isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
        assertThat(report.completeness()).isEqualTo(Completeness.TRUNCATED);
        assertThat(report.bytesTruncated()).isEqualTo(12);
        assertThat(report.framesVerified()).isEqualTo(3);
    }

    @Test
    void recoveryIsIdempotent() throws IOException {
        writeFrames(6);
        Path segment = JournalFixture.segment(directory, 0);
        JournalFixture.appendBytes(segment, JournalFixture.payload(37, 8));

        RecoveryReport first = JournalRecovery.recover(directory, Optional.empty(), READER);
        byte[] afterFirst = Files.readAllBytes(segment);

        RecoveryReport second = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(Files.readAllBytes(segment)).isEqualTo(afterFirst);
        assertThat(second.bytesTruncated()).isZero();
        assertThat(second.status()).isEqualTo(JournalScanStatus.OK);
        assertThat(second.lastValidPosition()).isEqualTo(first.lastValidPosition());
        assertThat(second.lastSequence()).isEqualTo(first.lastSequence());
        assertThat(second.framesVerified()).isEqualTo(first.framesVerified());
    }

    @Test
    void inspectReportsTheDamageWithoutChangingTheFile() throws IOException {
        writeFrames(3);
        Path segment = JournalFixture.segment(directory, 0);
        JournalFixture.appendBytes(segment, JournalFixture.payload(64, 11));
        long damagedSize = Files.size(segment);

        RecoveryReport report = JournalRecovery.inspect(directory, Optional.empty(), READER);

        assertThat(Files.size(segment)).isEqualTo(damagedSize);
        assertThat(report.bytesTruncated()).isZero();
        assertThat(report.status()).isEqualTo(JournalScanStatus.BAD_MAGIC);
        assertThat(report.lastValidPosition()).hasValue(new JournalPosition(0, endOfFrames(3)));
    }

    @Test
    void aWriterCanResumeAppendingImmediatelyAfterRecovery() throws IOException {
        writeFrames(3);
        Path segment = JournalFixture.segment(directory, 0);
        JournalFixture.appendBytes(segment, JournalFixture.payload(19, 2));

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);
        try (JournalWriter writer = JournalWriter.resume(directory, JournalFixture.SESSION, CONFIG)) {
            assertThat(writer.position()).isEqualTo(report.lastValidPosition().orElseThrow());
            writer.append(SourceKind.BEP_BINARY, 0, 9999L, 1L, JournalFixture.payload(PAYLOAD_SIZE, 9));
        }

        List<JournalFrame> frames = JournalFixture.readAll(segment, CONFIG.readerConfig());
        assertThat(frames).hasSize(4);
        assertThat(frames.get(3).header().sequence()).isEqualTo(9999L);
    }

    @Test
    void damageInTheLastSegmentLeavesEarlierSegmentsAlone() throws IOException {
        JournalWriterConfig rotating =
                new JournalWriterConfig(JournalFormat.SEGMENT_HEADER_BYTES + 2L * FRAME_SIZE, 4096, 128);
        JournalFixture.writeFrames(directory, rotating, 6, i -> JournalFixture.payload(PAYLOAD_SIZE, i));
        assertThat(JournalSegments.listSegmentIndexes(directory)).containsExactly(0, 1, 2);

        byte[] firstSegment = Files.readAllBytes(JournalFixture.segment(directory, 0));
        Path last = JournalFixture.segment(directory, 2);
        JournalFixture.appendBytes(last, JournalFixture.payload(90, 6));

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(Files.readAllBytes(JournalFixture.segment(directory, 0))).isEqualTo(firstSegment);
        assertThat(report.segmentsScanned()).isEqualTo(3);
        assertThat(report.framesVerified()).isEqualTo(6);
        assertThat(report.bytesTruncated()).isEqualTo(90);
        assertThat(report.lastValidPosition())
                .hasValue(new JournalPosition(2, JournalFormat.SEGMENT_HEADER_BYTES + 2L * FRAME_SIZE));
        assertThat(report.orphanedSegments()).isEmpty();
    }

    @Test
    void segmentsAfterADamagedOneAreReportedAndLeftUntouched() throws IOException {
        JournalWriterConfig rotating =
                new JournalWriterConfig(JournalFormat.SEGMENT_HEADER_BYTES + 2L * FRAME_SIZE, 4096, 128);
        JournalFixture.writeFrames(directory, rotating, 6, i -> JournalFixture.payload(PAYLOAD_SIZE, i));

        Path middle = JournalFixture.segment(directory, 1);
        JournalFixture.flipByte(middle, JournalFormat.SEGMENT_HEADER_BYTES + JournalFormat.FRAME_HEADER_BYTES + 3);
        byte[] middleBytes = Files.readAllBytes(middle);
        byte[] lastBytes = Files.readAllBytes(JournalFixture.segment(directory, 2));

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(report.status()).isEqualTo(JournalScanStatus.CRC_MISMATCH);
        assertThat(report.orphanedSegments()).containsExactly(2);
        assertThat(Files.readAllBytes(JournalFixture.segment(directory, 2)))
                .as("a later segment may hold valid frames and must never be deleted")
                .isEqualTo(lastBytes);
        // The damaged frame is not the tail of the journal, so nothing after it
        // in that segment is removed either: those bytes are still evidence.
        assertThat(Files.readAllBytes(middle)).isEqualTo(middleBytes);
        assertThat(report.bytesTruncated()).isZero();
    }

    @Test
    void aMissingSegmentIsAHoleNotAShortTail() throws IOException {
        JournalWriterConfig rotating =
                new JournalWriterConfig(JournalFormat.SEGMENT_HEADER_BYTES + 2L * FRAME_SIZE, 4096, 128);
        JournalFixture.writeFrames(directory, rotating, 6, i -> JournalFixture.payload(PAYLOAD_SIZE, i));
        Files.delete(JournalFixture.segment(directory, 1));

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(report.missingSegments()).containsExactly(1);
        assertThat(report.completeness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
        assertThat(report.isClean()).isFalse();
    }

    @Test
    void anEmptyJournalDirectoryRecoversToNothingRatherThanFailing() throws IOException {
        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(report.status()).isEqualTo(JournalScanStatus.OK);
        assertThat(report.lastValidPosition()).isEmpty();
        assertThat(report.lastSequence()).isEmpty();
        assertThat(report.framesVerified()).isZero();
        assertThat(report.segmentsScanned()).isZero();
    }

    @Test
    void aSegmentThatNeverGotItsHeaderIsTrimmedToNothing() throws IOException {
        writeFrames(2);
        Path orphan = JournalFixture.segment(directory, 1);
        Files.write(orphan, new byte[] {1, 2, 3});

        RecoveryReport report = JournalRecovery.recover(directory, Optional.empty(), READER);

        assertThat(Files.size(orphan)).isZero();
        assertThat(report.bytesTruncated()).isEqualTo(3);
        assertThat(report.status()).isEqualTo(JournalScanStatus.TRUNCATED_TAIL);
        assertThat(report.lastValidPosition()).hasValue(new JournalPosition(0, endOfFrames(2)));
    }

    @Test
    void aWriterResumesIntoTheSegmentRecoveryEmptied() throws IOException {
        writeFrames(2);
        Path orphan = JournalFixture.segment(directory, 1);
        Files.write(orphan, new byte[] {1, 2, 3});

        JournalRecovery.recover(directory, Optional.empty(), READER);

        try (JournalWriter writer = JournalWriter.resume(directory, JournalFixture.SESSION, CONFIG)) {
            assertThat(writer.currentSegmentIndex()).isEqualTo(1);
            writer.append(SourceKind.BEP_BINARY, 0, 5555L, 1L, JournalFixture.payload(PAYLOAD_SIZE, 1));
        }

        assertThat(JournalFixture.readAll(JournalFixture.segment(directory, 0), CONFIG.readerConfig()))
                .hasSize(2);
        List<JournalFrame> continued = JournalFixture.readAll(orphan, CONFIG.readerConfig());
        assertThat(continued).hasSize(1);
        assertThat(continued.get(0).header().sequence()).isEqualTo(5555L);
    }

    // ---- checkpoint interaction --------------------------------------------

    @Test
    void anHonouredCheckpointSkipsVerificationOfBytesBeforeIt() throws IOException {
        writeFrames(10);
        ImportCheckpoint checkpoint = ImportCheckpoint.at(
                new JournalPosition(0, endOfFrames(6)), OptionalLong.of(1005L), 6, 6, 1L);

        RecoveryReport report = JournalRecovery.recover(directory, Optional.of(checkpoint), READER);

        assertThat(report.checkpointHonoured()).isTrue();
        assertThat(report.framesVerified())
                .as("only the frames after the checkpoint are re-verified")
                .isEqualTo(4);
        assertThat(report.lastValidPosition()).hasValue(new JournalPosition(0, endOfFrames(10)));
        assertThat(report.lastSequence()).hasValue(1009L);
        assertThat(report.bytesTruncated()).isZero();
    }

    @Test
    void aCheckpointAheadOfTheJournalCausesAFullRescanRatherThanAFailure() throws IOException {
        // The expected shape of a crash: the checkpoint was taken from the
        // writer's logical position, but the buffered bytes never reached disk.
        writeFrames(3);
        ImportCheckpoint optimistic = ImportCheckpoint.at(
                new JournalPosition(0, endOfFrames(9)), OptionalLong.of(1008L), 9, 9, 1L);

        RecoveryReport report = JournalRecovery.recover(directory, Optional.of(optimistic), READER);

        assertThat(report.checkpointHonoured()).isFalse();
        assertThat(report.framesVerified()).isEqualTo(3);
        assertThat(report.status()).isEqualTo(JournalScanStatus.OK);
        assertThat(report.lastValidPosition()).hasValue(new JournalPosition(0, endOfFrames(3)));
        assertThat(report.lastSequence()).hasValue(1002L);
    }

    @Test
    void aCheckpointNamingAMissingSegmentFallsBackToTheStartOfTheJournal() throws IOException {
        writeFrames(4);
        ImportCheckpoint elsewhere = ImportCheckpoint.at(
                new JournalPosition(7, 512), OptionalLong.of(50L), 51, 51, 1L);

        RecoveryReport report = JournalRecovery.recover(directory, Optional.of(elsewhere), READER);

        assertThat(report.checkpointHonoured()).isFalse();
        assertThat(report.framesVerified()).isEqualTo(4);
        assertThat(report.lastSequence()).hasValue(1003L);
    }

    @Test
    void anHonouredCheckpointCarriesItsSequenceWhenNoFurtherFramesExist() throws IOException {
        writeFrames(3);
        ImportCheckpoint atEnd = ImportCheckpoint.at(
                new JournalPosition(0, endOfFrames(3)), OptionalLong.of(1002L), 3, 3, 1L);

        RecoveryReport report = JournalRecovery.recover(directory, Optional.of(atEnd), READER);

        assertThat(report.framesVerified()).isZero();
        assertThat(report.lastSequence())
                .as("the sequence is known from the checkpoint even though nothing was rescanned")
                .hasValue(1002L);
        assertThat(report.status()).isEqualTo(JournalScanStatus.OK);
    }
}
