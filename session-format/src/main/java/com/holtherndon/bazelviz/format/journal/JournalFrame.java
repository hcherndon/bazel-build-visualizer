package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.FrameHeader;
import java.util.Objects;

/**
 * One verified frame read back from a journal segment. A frame only exists as
 * an instance of this type after its CRC has been checked, so holding one is
 * proof that the bytes on disk were intact.
 *
 * @param segmentIndex segment the frame was read from
 * @param frameOffset byte offset of the frame header within that segment
 * @param header the decoded frame header
 * @param payload the payload bytes exactly as they were journaled, or
 *     {@code null} when the reader was configured not to read payloads
 *     ({@link JournalReaderConfig#readPayloads()}). Null here means "not read",
 *     never "empty": a zero-length payload is a legal frame and comes back as a
 *     zero-length array.
 */
public record JournalFrame(int segmentIndex, long frameOffset, FrameHeader header, byte[] payload) {

    public JournalFrame {
        Objects.requireNonNull(header, "header");
        if (payload != null && payload.length != header.payloadLength()) {
            throw new IllegalArgumentException("payload of " + payload.length
                    + " bytes does not match the header's " + header.payloadLength());
        }
    }

    /** False when this reader was verifying frames rather than reading them. */
    public boolean hasPayload() {
        return payload != null;
    }

    /**
     * The payload, or a failure if this frame came from a verify-only reader.
     * Callers that need bytes use this rather than {@link #payload()} so a
     * configuration mistake surfaces as an error instead of a
     * {@code NullPointerException} three frames later.
     */
    public byte[] requirePayload() {
        if (payload == null) {
            throw new IllegalStateException(
                    "frame at " + location() + " was verified but not read; "
                            + "configure JournalReaderConfig.readPayloads=true to retain payloads");
        }
        return payload;
    }

    /** Where this frame lives, for storing provenance alongside derived rows. */
    public JournalLocation location() {
        return new JournalLocation(segmentIndex, frameOffset, header.payloadLength());
    }

    /** The position immediately after this frame. */
    public JournalPosition endPosition() {
        return new JournalPosition(
                segmentIndex,
                frameOffset + JournalFormat.FRAME_HEADER_BYTES + header.payloadLength()
                        + JournalFormat.FRAME_CRC_BYTES);
    }
}
