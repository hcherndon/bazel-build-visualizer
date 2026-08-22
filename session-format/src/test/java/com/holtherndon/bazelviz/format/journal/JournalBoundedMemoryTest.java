package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 1 exit criterion: no parser reads a whole file into memory. The journal
 * reader's working set must depend on its configured buffer and the size of one
 * frame — never on the size of the segment.
 */
class JournalBoundedMemoryTest {

    private static final int FRAME_COUNT = 24;
    private static final int PAYLOAD_SIZE = 256 * 1024;
    private static final int TINY_BUFFER = 1024;

    @TempDir
    Path directory;

    private long writeLargeJournal() throws IOException {
        // A 64-byte staging buffer against 256 KiB payloads: the writer streams
        // every frame straight to the channel rather than staging it.
        JournalWriterConfig config = new JournalWriterConfig(1L << 40, 1 << 20, 64);
        JournalFixture.writeFrames(
                directory, config, FRAME_COUNT, i -> JournalFixture.payload(PAYLOAD_SIZE, i));
        return Files.size(JournalFixture.segment(directory, 0));
    }

    @Test
    void aSegmentFarLargerThanTheBufferIsVerifiedWithoutGrowingTheBuffer() throws IOException {
        long fileSize = writeLargeJournal();
        JournalReaderConfig config =
                new JournalReaderConfig(1 << 20, TINY_BUFFER, false);

        try (JournalReader reader = JournalReader.open(JournalFixture.segment(directory, 0), config)) {
            assertThat(reader.scratchBufferCapacity()).isEqualTo(TINY_BUFFER);
            SegmentScan scan = reader.verify();

            assertThat(scan.status()).isEqualTo(JournalScanStatus.OK);
            assertThat(scan.framesRead()).isEqualTo(FRAME_COUNT);
            assertThat(reader.scratchBufferCapacity())
                    .as("the buffer must not have grown to meet the file")
                    .isEqualTo(TINY_BUFFER);
        }
        assertThat(fileSize).isGreaterThan(100L * TINY_BUFFER);
    }

    @Test
    void verifyingASegmentAllocatesFarLessThanTheSegmentItReads() throws IOException {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "JVM does not report per-thread allocation");
        threads.setThreadAllocatedMemoryEnabled(true);

        long fileSize = writeLargeJournal();
        Path segment = JournalFixture.segment(directory, 0);
        JournalReaderConfig config = new JournalReaderConfig(1 << 20, TINY_BUFFER, false);

        // Warm up so class loading and the NIO temporary-buffer cache are not
        // counted as if they were per-byte costs.
        JournalFixture.scan(segment, config);

        long before = threads.getCurrentThreadAllocatedBytes();
        SegmentScan scan = JournalFixture.scan(segment, config);
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertThat(scan.framesRead()).isEqualTo(FRAME_COUNT);
        assertThat(allocated)
                .as("verifying %d bytes must not allocate anything like %d bytes", fileSize, fileSize)
                .isLessThan(fileSize / 4);
    }

    @Test
    void readingPayloadsAllocatesOneFrameAtATimeNotOneFile() throws IOException {
        writeLargeJournal();
        JournalReaderConfig config = new JournalReaderConfig(1 << 20, TINY_BUFFER, true);

        int seen = 0;
        try (JournalReader reader = JournalReader.open(JournalFixture.segment(directory, 0), config)) {
            JournalFrame frame;
            while ((frame = reader.next()) != null) {
                // Each payload is materialised, checked, and immediately dropped:
                // nothing accumulates across the scan.
                assertThat(frame.requirePayload()).isEqualTo(JournalFixture.payload(PAYLOAD_SIZE, seen));
                seen++;
            }
            assertThat(reader.result().status()).isEqualTo(JournalScanStatus.OK);
            assertThat(reader.scratchBufferCapacity()).isEqualTo(TINY_BUFFER);
        }
        assertThat(seen).isEqualTo(FRAME_COUNT);
    }

    @Test
    void verifyOnlyFramesSayTheyHaveNoPayloadRatherThanPretendingItIsEmpty() throws IOException {
        JournalWriterConfig writerConfig = new JournalWriterConfig(1L << 20, 4096, 256);
        try (JournalWriter writer =
                JournalWriter.create(directory, JournalFixture.SESSION, writerConfig)) {
            writer.append(SourceKind.BEP_BINARY, 0, 1L, 1L, JournalFixture.payload(64, 1));
        }

        try (JournalReader reader = JournalReader.open(
                JournalFixture.segment(directory, 0), JournalReaderConfig.verifyOnly())) {
            JournalFrame frame = reader.next();

            assertThat(frame).isNotNull();
            assertThat(frame.hasPayload()).isFalse();
            assertThat(frame.header().payloadLength())
                    .as("the length is known even when the bytes were not retained")
                    .isEqualTo(64);
            assertThat(frame.payload()).isNull();
            assertThat(org.assertj.core.api.Assertions.catchThrowable(frame::requirePayload))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
