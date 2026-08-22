package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;

/**
 * Where one frame's bytes live. This is what the normalizer stores in
 * {@code bep_events.raw_segment} / {@code raw_offset} / {@code raw_length}, so
 * a row in the database can always be traced back to the exact raw bytes it was
 * derived from (ADR-004, plan 11.5).
 *
 * @param segmentIndex the segment file the frame was written to
 * @param frameOffset byte offset of the frame header within that segment
 * @param payloadLength payload byte count, excluding header and CRC
 */
public record JournalLocation(int segmentIndex, long frameOffset, int payloadLength) {

    public JournalLocation {
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0, got " + segmentIndex);
        }
        if (frameOffset < JournalFormat.SEGMENT_HEADER_BYTES) {
            throw new IllegalArgumentException("frameOffset must be past the segment header, got " + frameOffset);
        }
        if (payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must be >= 0, got " + payloadLength);
        }
    }

    /** Byte offset of the payload itself, skipping the frame header. */
    public long payloadOffset() {
        return frameOffset + JournalFormat.FRAME_HEADER_BYTES;
    }

    /** Total bytes this frame occupies on disk: header + payload + CRC. */
    public int totalFrameBytes() {
        return JournalFormat.FRAME_HEADER_BYTES + payloadLength + JournalFormat.FRAME_CRC_BYTES;
    }

    /** The position of this frame's first byte. */
    public JournalPosition start() {
        return new JournalPosition(segmentIndex, frameOffset);
    }

    /** The position immediately after this frame, where the next frame starts. */
    public JournalPosition end() {
        return new JournalPosition(segmentIndex, frameOffset + totalFrameBytes());
    }
}
