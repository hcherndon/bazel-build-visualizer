package com.holtherndon.bazelviz.core.journal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * The on-disk raw journal format (ADR-004, plan section 9.3).
 *
 * <p>The journal is the recovery and forward-compatibility source of truth:
 * payload bytes are appended exactly as received and are never re-serialized,
 * so a session can always be rebuilt from raw data even by a later version of
 * this application that understands more of the schema.
 *
 * <p>This class is the single definition of the byte layout. The writer, the
 * reader, and crash recovery all encode and decode through it, so the layout
 * cannot drift between them.
 *
 * <h2>Segment header, 32 bytes, written once at segment creation</h2>
 * <pre>
 *   0  8  magic {@value #MAGIC_STRING}
 *   8  4  format version
 *  12  4  segment index, 0-based
 *  16  8  session UUID, most significant bits
 *  24  8  session UUID, least significant bits
 * </pre>
 *
 * <h2>Frame, 27-byte header + payload + 4-byte trailing CRC</h2>
 * <pre>
 *   0  4  frame magic {@value #FRAME_MAGIC_STRING}
 *   4  4  payload length
 *   8  1  source kind ordinal, see {@link SourceKind}
 *   9  2  stream ordinal
 *  11  8  sequence number
 *  19  8  receive timestamp, epoch micros
 *  27  n  payload bytes, verbatim
 *  27+n 4 CRC-32C over bytes [0, 27+n)
 * </pre>
 *
 * <p>Every frame is independently checksummed so recovery can find the last
 * intact frame without trusting any index. The frame magic lets a reader
 * confirm it is positioned on a boundary rather than inside a payload.
 *
 * <p>All integers are little-endian.
 */
public final class JournalFormat {

    private JournalFormat() {}

    /** Segment header magic, 8 bytes. */
    public static final String MAGIC_STRING = "BBVJRNL";

    /** Frame header magic, 4 bytes. */
    public static final String FRAME_MAGIC_STRING = "BFRM";

    public static final byte[] MAGIC = {'B', 'B', 'V', 'J', 'R', 'N', 'L', 1};
    public static final byte[] FRAME_MAGIC = {'B', 'F', 'R', 'M'};

    public static final int FORMAT_VERSION = 1;

    public static final int SEGMENT_HEADER_BYTES = 32;
    public static final int FRAME_HEADER_BYTES = 27;
    public static final int FRAME_CRC_BYTES = 4;

    /**
     * Default ceiling on a single payload. A declared length above the
     * configured maximum is corruption, not a large event: honouring it would
     * mean allocating whatever a damaged length field happens to say
     * (plan 21.3).
     */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;

    /** Default segment rotation size. Rotation happens only at frame boundaries. */
    public static final long DEFAULT_SEGMENT_BYTES = 256L * 1024 * 1024;

    /** Segment file name pattern, e.g. {@code bes-000001.journal}. */
    public static final String SEGMENT_NAME_FORMAT = "bes-%06d.journal";

    /** What a journaled payload is, so a reader knows how to interpret the bytes. */
    public enum SourceKind {
        /** A BES {@code PublishBuildToolEventStreamRequest} envelope. */
        BES_ENVELOPE,
        /** A BEP {@code BuildEvent}, binary-encoded. */
        BEP_BINARY,
        /** One record from a JSON BEP file, verbatim. */
        BEP_JSON_RECORD;

        public static SourceKind fromOrdinal(int ordinal) {
            SourceKind[] values = values();
            if (ordinal < 0 || ordinal >= values.length) {
                throw new IllegalArgumentException("unknown source kind ordinal: " + ordinal);
            }
            return values[ordinal];
        }
    }

    /** A decoded frame header. The payload itself is read separately. */
    public record FrameHeader(
            int payloadLength,
            SourceKind sourceKind,
            int streamOrdinal,
            long sequence,
            long receiveMicros) {

        /** Total on-disk size of this frame including header and trailing CRC. */
        public int totalFrameBytes() {
            return FRAME_HEADER_BYTES + payloadLength + FRAME_CRC_BYTES;
        }
    }

    public static String segmentFileName(int segmentIndex) {
        return SEGMENT_NAME_FORMAT.formatted(segmentIndex);
    }

    public static ByteBuffer allocate(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Writes a segment header into {@code target} at its current position. */
    public static void writeSegmentHeader(
            ByteBuffer target, int segmentIndex, long sessionUuidHigh, long sessionUuidLow) {
        requireLittleEndian(target);
        target.put(MAGIC);
        target.putInt(FORMAT_VERSION);
        target.putInt(segmentIndex);
        target.putLong(sessionUuidHigh);
        target.putLong(sessionUuidLow);
    }

    /**
     * Writes a complete frame — header, payload and CRC — into {@code target}.
     * The CRC covers the header and payload together, so a frame damaged in
     * either part fails validation.
     */
    public static void writeFrame(ByteBuffer target, FrameHeader header, byte[] payload,
            int payloadOffset, int payloadLength) {
        requireLittleEndian(target);
        if (payloadLength != header.payloadLength()) {
            throw new IllegalArgumentException("payload length " + payloadLength
                    + " does not match header length " + header.payloadLength());
        }
        int frameStart = target.position();
        target.put(FRAME_MAGIC);
        target.putInt(payloadLength);
        target.put((byte) header.sourceKind().ordinal());
        target.putShort((short) header.streamOrdinal());
        target.putLong(header.sequence());
        target.putLong(header.receiveMicros());
        target.put(payload, payloadOffset, payloadLength);

        CRC32C crc = new CRC32C();
        crc.update(target.duplicate().position(frameStart)
                .limit(frameStart + FRAME_HEADER_BYTES + payloadLength));
        target.putInt((int) crc.getValue());
    }

    /**
     * Decodes a frame header from {@code source} at its current position,
     * advancing the position past the header.
     *
     * @throws IllegalArgumentException if the magic is wrong or the declared
     *     length exceeds {@code maxPayloadBytes}; callers treat both as
     *     corruption rather than retrying
     */
    public static FrameHeader readFrameHeader(ByteBuffer source, int maxPayloadBytes) {
        requireLittleEndian(source);
        byte[] magic = new byte[FRAME_MAGIC.length];
        source.get(magic);
        for (int i = 0; i < FRAME_MAGIC.length; i++) {
            if (magic[i] != FRAME_MAGIC[i]) {
                throw new IllegalArgumentException("frame magic mismatch");
            }
        }
        int payloadLength = source.getInt();
        if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
            throw new IllegalArgumentException(
                    "declared payload length " + payloadLength + " exceeds maximum " + maxPayloadBytes);
        }
        SourceKind kind = SourceKind.fromOrdinal(Byte.toUnsignedInt(source.get()));
        int streamOrdinal = Short.toUnsignedInt(source.getShort());
        long sequence = source.getLong();
        long receiveMicros = source.getLong();
        return new FrameHeader(payloadLength, kind, streamOrdinal, sequence, receiveMicros);
    }

    /** Computes the CRC a frame should carry, over its header and payload. */
    public static int computeFrameCrc(ByteBuffer frameHeaderAndPayload) {
        CRC32C crc = new CRC32C();
        crc.update(frameHeaderAndPayload.duplicate());
        return (int) crc.getValue();
    }

    private static void requireLittleEndian(ByteBuffer buffer) {
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("journal buffers must be little-endian");
        }
    }
}
