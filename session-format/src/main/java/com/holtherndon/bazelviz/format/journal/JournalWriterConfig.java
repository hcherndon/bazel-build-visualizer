package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;

/**
 * Tunables for {@link JournalWriter}. Every one of them is an explicit limit
 * the user can be shown (plan 20.3): nothing here silently drops or truncates
 * data, and exceeding {@link #maxPayloadBytes()} is an error at the call site
 * rather than a shortened payload.
 *
 * @param segmentBytes rotate to a new segment once a frame would push the
 *     current segment past this size. Rotation happens at frame boundaries
 *     only, so a segment may end slightly under this size, and a single frame
 *     larger than this size occupies a segment of its own rather than being
 *     split.
 * @param maxPayloadBytes largest payload a single frame may carry. A larger
 *     payload is rejected as an argument error; a larger <em>declared</em>
 *     length found on disk is corruption (plan 21.3).
 * @param bufferBytes staging buffer between the caller and the file channel.
 *     This is the "group flushes" knob from plan 9.3: frames accumulate here
 *     and are handed to the OS in one write when it fills. It is not a
 *     durability boundary — see {@link JournalWriter#force()}.
 */
public record JournalWriterConfig(long segmentBytes, int maxPayloadBytes, int bufferBytes) {

    /** Default staging buffer: one write syscall per ~1 MiB of events. */
    public static final int DEFAULT_BUFFER_BYTES = 1 << 20;

    /** Smallest buffer that can stage a frame header and its CRC. */
    public static final int MIN_BUFFER_BYTES =
            JournalFormat.FRAME_HEADER_BYTES + JournalFormat.FRAME_CRC_BYTES;

    public JournalWriterConfig {
        if (maxPayloadBytes < 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be >= 0, got " + maxPayloadBytes);
        }
        if (maxPayloadBytes > JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("maxPayloadBytes " + maxPayloadBytes
                    + " exceeds the format ceiling " + JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES);
        }
        long smallestUsableSegment = JournalFormat.SEGMENT_HEADER_BYTES
                + JournalFormat.FRAME_HEADER_BYTES + JournalFormat.FRAME_CRC_BYTES;
        if (segmentBytes < smallestUsableSegment) {
            throw new IllegalArgumentException("segmentBytes must be at least "
                    + smallestUsableSegment + " (header plus one empty frame), got " + segmentBytes);
        }
        if (bufferBytes < MIN_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "bufferBytes must be at least " + MIN_BUFFER_BYTES + ", got " + bufferBytes);
        }
    }

    public static JournalWriterConfig defaults() {
        return new JournalWriterConfig(
                JournalFormat.DEFAULT_SEGMENT_BYTES,
                JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES,
                DEFAULT_BUFFER_BYTES);
    }

    public JournalWriterConfig withSegmentBytes(long newSegmentBytes) {
        return new JournalWriterConfig(newSegmentBytes, maxPayloadBytes, bufferBytes);
    }

    public JournalWriterConfig withMaxPayloadBytes(int newMaxPayloadBytes) {
        return new JournalWriterConfig(segmentBytes, newMaxPayloadBytes, bufferBytes);
    }

    public JournalWriterConfig withBufferBytes(int newBufferBytes) {
        return new JournalWriterConfig(segmentBytes, maxPayloadBytes, newBufferBytes);
    }

    /**
     * The matching reader configuration, so a reader never disagrees with the
     * writer about the payload ceiling. The reader keeps its own (small) buffer
     * size: the writer's buffer is sized for write batching, the reader's is
     * sized only to stream bytes through a checksum.
     */
    public JournalReaderConfig readerConfig() {
        return new JournalReaderConfig(maxPayloadBytes, JournalReaderConfig.DEFAULT_BUFFER_BYTES, true);
    }
}
