package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/** Shared helpers for the journal tests: deterministic payloads and raw file surgery. */
final class JournalFixture {

    static final UUID SESSION = UUID.fromString("6f9619ff-8b86-d011-b42d-00cf4fc964ff");
    static final UUID OTHER_SESSION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private JournalFixture() {}

    /** Deterministic, non-repeating bytes so a corrupted byte cannot look like a valid one. */
    static byte[] payload(int size, int seed) {
        byte[] bytes = new byte[size];
        int state = seed * 0x9E3779B1 + 1;
        for (int i = 0; i < size; i++) {
            state = state * 1103515245 + 12345;
            bytes[i] = (byte) (state >>> 16);
        }
        return bytes;
    }

    /** Writes {@code count} frames, returning the payloads written, in order. */
    static List<byte[]> writeFrames(
            Path directory, JournalWriterConfig config, int count, IntFunction<byte[]> payloads)
            throws IOException {
        List<byte[]> written = new ArrayList<>(count);
        try (JournalWriter writer = JournalWriter.create(directory, SESSION, config)) {
            for (int i = 0; i < count; i++) {
                byte[] payload = payloads.apply(i);
                writer.append(SourceKind.BEP_BINARY, i % 3, 1000L + i, 1_700_000_000_000_000L + i, payload);
                written.add(payload);
            }
        }
        return written;
    }

    /** Reads every frame a segment yields, stopping wherever the reader stops. */
    static List<JournalFrame> readAll(Path segmentFile, JournalReaderConfig config) throws IOException {
        List<JournalFrame> frames = new ArrayList<>();
        try (JournalReader reader = JournalReader.open(segmentFile, config)) {
            JournalFrame frame;
            while ((frame = reader.next()) != null) {
                frames.add(frame);
            }
        }
        return frames;
    }

    static SegmentScan scan(Path segmentFile, JournalReaderConfig config) throws IOException {
        try (JournalReader reader = JournalReader.open(segmentFile, config)) {
            return reader.verify();
        }
    }

    static Path segment(Path directory, int index) {
        return JournalSegments.segmentFile(directory, index);
    }

    static void truncateFile(Path file, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        }
    }

    static void appendBytes(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** Flips every bit of one byte in place; the CRC over that frame must change. */
    static void flipByte(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.read(one, offset);
            one.flip();
            byte flipped = (byte) ~one.get(0);
            channel.write(ByteBuffer.wrap(new byte[] {flipped}), offset);
        }
    }

    /**
     * Builds a frame by hand so tests can produce bytes the writer never would,
     * such as a source-kind ordinal from a future release, with a correct CRC.
     */
    static byte[] handBuiltFrame(
            int sourceKindOrdinal, int streamOrdinal, long sequence, long receiveMicros, byte[] payload) {
        ByteBuffer buffer = JournalFormat.allocate(
                JournalFormat.FRAME_HEADER_BYTES + payload.length + JournalFormat.FRAME_CRC_BYTES);
        buffer.put(JournalFormat.FRAME_MAGIC);
        buffer.putInt(payload.length);
        buffer.put((byte) sourceKindOrdinal);
        buffer.putShort((short) streamOrdinal);
        buffer.putLong(sequence);
        buffer.putLong(receiveMicros);
        buffer.put(payload);
        int crc = JournalFormat.computeFrameCrc(
                buffer.duplicate().position(0).limit(JournalFormat.FRAME_HEADER_BYTES + payload.length));
        buffer.putInt(crc);
        return buffer.array();
    }

    /** Total on-disk size of a frame carrying {@code payloadLength} bytes. */
    static int frameBytes(int payloadLength) {
        return JournalFormat.FRAME_HEADER_BYTES + payloadLength + JournalFormat.FRAME_CRC_BYTES;
    }
}
