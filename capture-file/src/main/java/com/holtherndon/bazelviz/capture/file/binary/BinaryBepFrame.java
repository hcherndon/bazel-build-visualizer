package com.holtherndon.bazelviz.capture.file.binary;

import java.nio.ByteBuffer;

/**
 * One length-delimited record read from a binary BEP stream: the raw payload
 * bytes plus the exact byte range they occupied in the source.
 *
 * <p>The offsets are what make an import resumable and reproducible (plan
 * exit criterion "event counts and offsets are reproducible"): {@code
 * frameOffset} is where the length prefix started, so restarting a parse at
 * that offset re-reads this frame and everything after it and nothing else.
 *
 * <p><strong>The {@link #payload} buffer is only valid for the duration of the
 * {@link BinaryBepEventSink#onEvent} call that received this frame.</strong> It
 * is a read-only view over the parser's reusable read buffer, which the next
 * refill overwrites. That is deliberate — copying every payload would double the
 * bytes moved during an import — so a sink that retains the bytes must call
 * {@link #copyPayload()} or write the buffer out before returning. Payload bytes
 * are never re-serialized (ADR-004); they are exactly the bytes on disk.
 *
 * @param frameOffset absolute offset of the frame's first byte, i.e. the first
 *     byte of its varint length prefix
 * @param payloadOffset absolute offset of the first payload byte
 * @param payloadLength declared and verified payload length in bytes
 * @param payload read-only view of exactly {@code payloadLength} bytes, valid
 *     only during the sink callback
 */
public record BinaryBepFrame(
        long frameOffset, long payloadOffset, int payloadLength, ByteBuffer payload) {

    public BinaryBepFrame {
        if (frameOffset < 0) {
            throw new IllegalArgumentException("frameOffset must be >= 0, got " + frameOffset);
        }
        if (payloadOffset <= frameOffset) {
            throw new IllegalArgumentException(
                    "payloadOffset " + payloadOffset + " must follow frameOffset " + frameOffset);
        }
        if (payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must be >= 0, got " + payloadLength);
        }
        if (payload.remaining() != payloadLength) {
            throw new IllegalArgumentException("payload holds " + payload.remaining()
                    + " bytes but payloadLength is " + payloadLength);
        }
    }

    /** Bytes the varint length prefix occupied, 1 through 5. */
    public int lengthPrefixBytes() {
        return (int) (payloadOffset - frameOffset);
    }

    /** Absolute offset one past this frame's last byte — where the next frame starts. */
    public long endOffset() {
        return payloadOffset + payloadLength;
    }

    /** Total on-disk size of this frame, length prefix included. */
    public int totalFrameBytes() {
        return lengthPrefixBytes() + payloadLength;
    }

    /**
     * Copies the payload into a fresh array. Use this, not the buffer, when the
     * bytes outlive the sink callback.
     */
    public byte[] copyPayload() {
        ByteBuffer view = payload.duplicate();
        byte[] copy = new byte[view.remaining()];
        view.get(copy);
        return copy;
    }
}
