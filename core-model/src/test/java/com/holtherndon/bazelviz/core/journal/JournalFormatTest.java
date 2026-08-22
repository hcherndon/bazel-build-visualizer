package com.holtherndon.bazelviz.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.journal.JournalFormat.FrameHeader;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * These assertions pin the on-disk layout. A change that breaks them changes
 * the format, which orphans every journal written by an earlier build — so a
 * failure here means bumping {@code FORMAT_VERSION} and writing a reader for
 * the old layout, not editing the expected values.
 */
class JournalFormatTest {

    private static final int MAX_PAYLOAD = JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES;

    @Test
    void headerAndFrameSizesAreFixed() {
        assertThat(JournalFormat.SEGMENT_HEADER_BYTES).isEqualTo(32);
        assertThat(JournalFormat.FRAME_HEADER_BYTES).isEqualTo(27);
        assertThat(JournalFormat.FRAME_CRC_BYTES).isEqualTo(4);
        assertThat(JournalFormat.FORMAT_VERSION).isEqualTo(1);
    }

    @Test
    void segmentHeaderLayoutIsStable() {
        ByteBuffer buffer = JournalFormat.allocate(JournalFormat.SEGMENT_HEADER_BYTES);
        JournalFormat.writeSegmentHeader(buffer, 7, 0x0123456789ABCDEFL, 0x76543210FEDCBA98L);

        assertThat(buffer.position()).isEqualTo(JournalFormat.SEGMENT_HEADER_BYTES);
        buffer.flip();
        byte[] magic = new byte[8];
        buffer.get(magic);
        assertThat(magic).isEqualTo(JournalFormat.MAGIC);
        assertThat(buffer.getInt()).isEqualTo(1);
        assertThat(buffer.getInt()).isEqualTo(7);
        assertThat(buffer.getLong()).isEqualTo(0x0123456789ABCDEFL);
        assertThat(buffer.getLong()).isEqualTo(0x76543210FEDCBA98L);
    }

    @Test
    void frameRoundTripsThroughWriteAndRead() {
        byte[] payload = "a serialized BuildEvent would live here".getBytes();
        FrameHeader header = new FrameHeader(
                payload.length, SourceKind.BEP_BINARY, 3, 4242L, 1_755_800_000_000_000L);

        ByteBuffer buffer = JournalFormat.allocate(header.totalFrameBytes());
        JournalFormat.writeFrame(buffer, header, payload, 0, payload.length);
        assertThat(buffer.position()).isEqualTo(header.totalFrameBytes());

        buffer.flip();
        FrameHeader read = JournalFormat.readFrameHeader(buffer, MAX_PAYLOAD);
        assertThat(read).isEqualTo(header);

        byte[] readPayload = new byte[read.payloadLength()];
        buffer.get(readPayload);
        assertThat(readPayload).isEqualTo(payload);

        int storedCrc = buffer.getInt();
        int expectedCrc = JournalFormat.computeFrameCrc(
                buffer.duplicate().position(0).limit(JournalFormat.FRAME_HEADER_BYTES + payload.length));
        assertThat(storedCrc).isEqualTo(expectedCrc);
        assertThat(buffer.hasRemaining()).isFalse();
    }

    @Test
    void emptyPayloadIsLegal() {
        FrameHeader header = new FrameHeader(0, SourceKind.BES_ENVELOPE, 0, 1L, 2L);
        ByteBuffer buffer = JournalFormat.allocate(header.totalFrameBytes());
        JournalFormat.writeFrame(buffer, header, new byte[0], 0, 0);

        buffer.flip();
        assertThat(JournalFormat.readFrameHeader(buffer, MAX_PAYLOAD)).isEqualTo(header);
    }

    @Test
    void crcCoversThePayloadNotJustTheHeader() {
        byte[] payload = "original".getBytes();
        FrameHeader header = new FrameHeader(payload.length, SourceKind.BEP_BINARY, 1, 1L, 1L);
        ByteBuffer buffer = JournalFormat.allocate(header.totalFrameBytes());
        JournalFormat.writeFrame(buffer, header, payload, 0, payload.length);

        int crcOffset = JournalFormat.FRAME_HEADER_BYTES + payload.length;
        int originalCrc = buffer.getInt(crcOffset);

        // Corrupt one payload byte; the CRC over header+payload must change.
        buffer.put(JournalFormat.FRAME_HEADER_BYTES, (byte) 'X');
        int corruptedCrc = JournalFormat.computeFrameCrc(
                buffer.duplicate().position(0).limit(crcOffset));

        assertThat(corruptedCrc).isNotEqualTo(originalCrc);
    }

    @Test
    void badMagicIsRejected() {
        ByteBuffer buffer = JournalFormat.allocate(64);
        buffer.put("XXXX".getBytes()).putInt(4);
        buffer.flip();

        assertThatThrownBy(() -> JournalFormat.readFrameHeader(buffer, MAX_PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void oversizedDeclaredLengthIsRejectedWithoutAllocating() {
        ByteBuffer buffer = JournalFormat.allocate(64);
        buffer.put(JournalFormat.FRAME_MAGIC).putInt(Integer.MAX_VALUE);
        buffer.flip();

        assertThatThrownBy(() -> JournalFormat.readFrameHeader(buffer, MAX_PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum");
    }

    @Test
    void negativeDeclaredLengthIsRejected() {
        ByteBuffer buffer = JournalFormat.allocate(64);
        buffer.put(JournalFormat.FRAME_MAGIC).putInt(-1);
        buffer.flip();

        assertThatThrownBy(() -> JournalFormat.readFrameHeader(buffer, MAX_PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bigEndianBuffersAreRejectedRatherThanSilentlyMisread() {
        ByteBuffer bigEndian = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        assertThatThrownBy(() -> JournalFormat.writeSegmentHeader(bigEndian, 0, 0L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("little-endian");
    }

    @Test
    void segmentFileNamesSortLexicographically() {
        assertThat(JournalFormat.segmentFileName(0)).isEqualTo("bes-000000.journal");
        assertThat(JournalFormat.segmentFileName(1)).isEqualTo("bes-000001.journal");
        assertThat(JournalFormat.segmentFileName(123456)).isEqualTo("bes-123456.journal");
        // Lexicographic order must match numeric order, or recovery replays segments out of sequence.
        assertThat(JournalFormat.segmentFileName(2))
                .isLessThan(JournalFormat.segmentFileName(10));
    }

    @Test
    void sourceKindOrdinalsAreStable() {
        // Persisted as a byte in every frame; reordering this enum reinterprets old journals.
        assertThat(SourceKind.BES_ENVELOPE.ordinal()).isZero();
        assertThat(SourceKind.BEP_BINARY.ordinal()).isEqualTo(1);
        assertThat(SourceKind.BEP_JSON_RECORD.ordinal()).isEqualTo(2);
        assertThatThrownBy(() -> SourceKind.fromOrdinal(99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
