package com.holtherndon.bazelviz.testsupport.bep;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-level view of a Bazel {@code --build_event_binary_file}: a bare sequence
 * of {@code varint32 length} + {@code length} payload bytes, with no header,
 * footer, or record separator.
 *
 * <p>This is the fixture module's own reader. It exists so damage helpers can
 * land a byte flip or a truncation at an offset they can name exactly, and so a
 * test can check a parser's frame boundaries against a second, independent
 * implementation. It is <em>not</em> the production parser and must not be used
 * as one: it makes no attempt at incremental or tailing reads.
 *
 * <p>{@link #scan} is genuinely streaming — it holds one varint at a time and
 * skips payloads without reading them, retaining no frame — so it can walk a
 * file far larger than heap. {@link #index(Path)} materializes one small record
 * per frame and is therefore fixture-scale only.
 */
public final class LengthDelimitedFrames {

    /** How the byte stream ends, which is exactly the TRUNCATED-vs-CORRUPT distinction's input. */
    public enum Tail {
        /** The last frame's payload ended precisely at end-of-file. */
        CLEAN,
        /** The file ends part-way through a length varint. */
        PARTIAL_VARINT,
        /** A complete length varint was read but fewer payload bytes than it declares remain. */
        PARTIAL_PAYLOAD
    }

    /** One length-delimited record. All offsets are absolute within the file. */
    public record Frame(
            int index,
            long varintOffset,
            int varintLength,
            long payloadOffset,
            int payloadLength) {

        /** First offset past this frame's payload. */
        public long endOffset() {
            return payloadOffset + payloadLength;
        }
    }

    /**
     * Outcome of a streaming scan. Deliberately carries no frame list: the whole
     * point of {@link #scan} is that it retains nothing per frame.
     *
     * @param cleanEndOffset first offset not covered by a complete frame
     * @param truncatedTailBytes bytes after {@code cleanEndOffset} that did not
     *     form a complete frame; 0 when {@code tail == CLEAN}
     */
    public record ScanResult(
            long frameCount,
            long fileBytes,
            long cleanEndOffset,
            Tail tail,
            long truncatedTailBytes) {}

    /**
     * Outcome of {@link #index(Path)}: a scan plus every frame descriptor.
     * Fixture-scale only.
     */
    public record IndexResult(List<Frame> frames, ScanResult scan) {

        public long fileBytes() {
            return scan.fileBytes();
        }

        public long cleanEndOffset() {
            return scan.cleanEndOffset();
        }

        public Tail tail() {
            return scan.tail();
        }

        public long truncatedTailBytes() {
            return scan.truncatedTailBytes();
        }
    }

    /** Callback form; return {@code false} to stop scanning early. */
    @FunctionalInterface
    public interface FrameVisitor {
        boolean visit(Frame frame) throws IOException;
    }

    private LengthDelimitedFrames() {}

    /**
     * Streams over the frames in {@code file}, retaining none of them. Payload
     * bytes are skipped rather than buffered, so peak memory is the
     * buffered-stream buffer regardless of file size.
     *
     * @return where the frames stopped and why
     */
    public static ScanResult scan(Path file, FrameVisitor visitor) throws IOException {
        long fileBytes = Files.size(file);
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            return scan(in, fileBytes, visitor);
        }
    }

    private static ScanResult scan(InputStream in, long fileBytes, FrameVisitor visitor) throws IOException {
        long offset = 0;
        int index = 0;
        while (true) {
            long varintOffset = offset;
            int shift = 0;
            int length = 0;
            int varintLength = 0;
            boolean complete = false;
            while (shift < 35) {
                int b = in.read();
                if (b < 0) {
                    Tail tail = varintLength == 0 ? Tail.CLEAN : Tail.PARTIAL_VARINT;
                    return new ScanResult(index, fileBytes, varintOffset, tail, fileBytes - varintOffset);
                }
                varintLength++;
                offset++;
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    complete = true;
                    break;
                }
                shift += 7;
            }
            if (!complete) {
                throw new IOException("length varint at offset " + varintOffset + " is malformed (>5 bytes)");
            }
            if (length < 0) {
                throw new IOException("length varint at offset " + varintOffset + " declares " + length
                        + " bytes, which is not a valid payload length");
            }
            long payloadOffset = offset;
            if (fileBytes - payloadOffset < length) {
                return new ScanResult(
                        index, fileBytes, varintOffset, Tail.PARTIAL_PAYLOAD, fileBytes - varintOffset);
            }
            skipFully(in, length);
            offset = payloadOffset + length;
            Frame frame = new Frame(index++, varintOffset, varintLength, payloadOffset, length);
            if (!visitor.visit(frame)) {
                return new ScanResult(index, fileBytes, offset, Tail.CLEAN, 0);
            }
        }
    }

    /**
     * Materializes every frame descriptor. Fixture-scale convenience only: one
     * small record per frame is retained, so do not point it at a production
     * capture. Use {@link #scan} when the file may be large.
     */
    public static IndexResult index(Path file) throws IOException {
        List<Frame> frames = new ArrayList<>();
        ScanResult scan = scan(file, frame -> {
            frames.add(frame);
            return true;
        });
        return new IndexResult(List.copyOf(frames), scan);
    }

    /** Reads the payload bytes of one frame. Fixture-scale convenience. */
    public static byte[] payloadOf(Path file, Frame frame) throws IOException {
        byte[] all = Files.readAllBytes(file);
        byte[] out = new byte[frame.payloadLength()];
        System.arraycopy(all, (int) frame.payloadOffset(), out, 0, frame.payloadLength());
        return out;
    }

    /** Number of bytes protobuf uses for the length prefix of a payload of {@code length} bytes. */
    public static int varintLengthOf(int length) {
        int n = 1;
        int v = length;
        while ((v & ~0x7F) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("unexpected end of stream with " + remaining + " bytes left to skip");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
