package com.holtherndon.bazelviz.capture.file.binary;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * Streaming parser for Bazel's {@code --build_event_binary_file} format: a
 * varint length followed by that many bytes of a serialized {@code BuildEvent},
 * repeated to end of file. This is protobuf's {@code writeDelimitedTo} framing.
 *
 * <h2>Bounded memory</h2>
 *
 * The parser reads through one reusable buffer whose capacity is
 * <em>never</em> derived from the length of the input (a Phase 1 exit
 * criterion). It starts at {@code bufferBytes} and grows only when a single
 * frame does not fit, up to {@code maxPayloadBytes + 5}. Peak usage is
 * therefore a function of the largest event in the stream, not of the stream —
 * a 40 GiB BEP file whose largest event is 2 MiB is parsed in a 2 MiB buffer.
 * {@link BinaryBepParseResult#peakBufferBytes()} reports the high-water mark so
 * tests can assert this rather than assume it.
 *
 * <h2>Truncation versus corruption</h2>
 *
 * A stream that ends inside a length prefix or inside a payload is
 * {@link BinaryBepParseOutcome#TRUNCATED}: normal for a build still writing, or
 * one that was killed. Every complete frame before the cut is still delivered,
 * and the result carries the exact offset of the partial frame so a later pass
 * resumes there.
 *
 * <p>A length prefix that does not terminate within five bytes, or that
 * declares more than {@code maxPayloadBytes}, is
 * {@link BinaryBepParseOutcome#CORRUPT}. The declared length is rejected
 * <em>before</em> any allocation: a damaged length field claiming 3 GiB must
 * not be honoured with a 3 GiB array (plan 21.3). The parser then stops rather
 * than hunting for the next plausible boundary, because scanning binary data
 * for guessed protobuf boundaries is exactly what plan 21.3 forbids.
 *
 * <h2>Resuming and growing files</h2>
 *
 * {@link #parseFile(Path, long, BinaryBepEventSink)} starts at any byte offset,
 * which must be a frame boundary — in practice the {@code resumeOffset} of an
 * earlier result or a checkpoint. Parsing a file, appending to it, and parsing
 * again from the returned resume offset delivers exactly the frames a single
 * pass over the final file would, with no rescanning. {@link BinaryBepTailReader}
 * wraps that loop for growing files (plan 9.5).
 *
 * <p>Instances are immutable configuration and can be shared; each
 * {@code parse} call allocates its own buffer and holds no state between calls.
 */
public final class BinaryBepParser {

    /** A protobuf {@code uint32} length prefix is at most five bytes. */
    public static final int MAX_LENGTH_PREFIX_BYTES = 5;

    /**
     * Initial read buffer. Large enough that a typical BEP file — mostly
     * progress and action-completed events of a few hundred bytes — is read
     * with one syscall per few hundred events and never grows the buffer.
     */
    public static final int DEFAULT_BUFFER_BYTES = 64 * 1024;

    /** Smallest legal buffer: a length prefix must always fit. */
    private static final int MIN_BUFFER_BYTES = 64;

    private static final int VARINT_NEED_MORE = -1;
    private static final int VARINT_MALFORMED = -2;

    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private final int maxPayloadBytes;
    private final int bufferBytes;

    /**
     * @param maxPayloadBytes largest payload a frame may declare; a larger
     *     declared length is corruption, not a big event. Shares its default
     *     with the journal's frame ceiling so a parsed event can always be
     *     journaled
     * @param bufferBytes initial read buffer size
     */
    public BinaryBepParser(int maxPayloadBytes, int bufferBytes) {
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be >= 1, got " + maxPayloadBytes);
        }
        if (maxPayloadBytes > Integer.MAX_VALUE - MAX_LENGTH_PREFIX_BYTES) {
            // The buffer ceiling is maxPayloadBytes plus a length prefix; letting
            // that sum overflow would turn the ceiling into a negative number and
            // disable the very check it exists to enforce.
            throw new IllegalArgumentException("maxPayloadBytes must be <= "
                    + (Integer.MAX_VALUE - MAX_LENGTH_PREFIX_BYTES) + ", got " + maxPayloadBytes);
        }
        if (bufferBytes < MIN_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "bufferBytes must be >= " + MIN_BUFFER_BYTES + ", got " + bufferBytes);
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.bufferBytes = bufferBytes;
    }

    /** A parser with the journal's payload ceiling and the default read buffer. */
    public static BinaryBepParser withDefaults() {
        return new BinaryBepParser(JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_BUFFER_BYTES);
    }

    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    public int bufferBytes() {
        return bufferBytes;
    }

    /**
     * Parses {@code file} from {@code startOffset} to end of file.
     *
     * @param startOffset a frame boundary: 0, or a {@code resumeOffset} from an
     *     earlier result. Starting anywhere else produces garbage lengths, so
     *     callers must not invent offsets
     */
    public BinaryBepParseResult parseFile(Path file, long startOffset, BinaryBepEventSink sink)
            throws IOException {
        return parseFile(file, startOffset, sink, NEVER_CANCELLED);
    }

    /** As {@link #parseFile(Path, long, BinaryBepEventSink)}, with cancellation. */
    public BinaryBepParseResult parseFile(
            Path file, long startOffset, BinaryBepEventSink sink, BooleanSupplier cancelRequested)
            throws IOException {
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be >= 0, got " + startOffset);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(startOffset);
            return parse(channel, startOffset, sink, cancelRequested);
        }
    }

    /**
     * Parses a stream that is already positioned at {@code startOffset}.
     *
     * <p>{@code startOffset} is not a seek instruction — the stream is read from
     * wherever it currently sits. It only tells the parser what absolute offset
     * the next byte has, so reported offsets stay absolute across resumes.
     */
    public BinaryBepParseResult parse(InputStream in, long startOffset, BinaryBepEventSink sink)
            throws IOException {
        return parse(Channels.newChannel(in), startOffset, sink, NEVER_CANCELLED);
    }

    /** As {@link #parse(ReadableByteChannel, long, BinaryBepEventSink, BooleanSupplier)}. */
    public BinaryBepParseResult parse(
            ReadableByteChannel channel, long startOffset, BinaryBepEventSink sink)
            throws IOException {
        return parse(channel, startOffset, sink, NEVER_CANCELLED);
    }

    /**
     * Parses from {@code channel} until it reports end of input, the input stops
     * making frame-shaped sense, or cancellation fires.
     *
     * <p>The channel must be blocking. A non-blocking channel can return zero
     * bytes without being at end of input, which this parser would read as "no
     * progress" and spin on; nothing in the application feeds it one.
     *
     * @param startOffset absolute offset of the channel's current position
     * @param cancelRequested polled between frames; when it returns true the
     *     parse stops with {@link BinaryBepParseOutcome#CANCELLED} and a resume
     *     offset on the frame boundary just reached, so the import can continue
     *     later instead of starting over
     */
    public BinaryBepParseResult parse(
            ReadableByteChannel channel,
            long startOffset,
            BinaryBepEventSink sink,
            BooleanSupplier cancelRequested)
            throws IOException {
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be >= 0, got " + startOffset);
        }
        Window window = new Window(channel, bufferBytes, maxPayloadBytes + MAX_LENGTH_PREFIX_BYTES);
        long frameOffset = startOffset;
        long events = 0;
        long[] declaredOut = new long[1];

        while (true) {
            if (cancelRequested.getAsBoolean()) {
                return result(BinaryBepParseOutcome.CANCELLED, events, startOffset, frameOffset,
                        0, OptionalLong.empty(), window,
                        "cancelled at offset " + frameOffset + " after " + events + " events");
            }

            // 1. Length prefix. Refill until it decodes or the input runs out.
            int prefixBytes;
            while (true) {
                prefixBytes = scanVarint(window.data, window.start, window.end, declaredOut);
                if (prefixBytes != VARINT_NEED_MORE || window.eof) {
                    break;
                }
                window.fill();
            }
            if (prefixBytes == VARINT_NEED_MORE) {
                int tail = window.available();
                if (tail == 0) {
                    return result(BinaryBepParseOutcome.COMPLETE, events, startOffset, frameOffset,
                            0, OptionalLong.empty(), window,
                            "input ended on a frame boundary at offset " + frameOffset
                                    + " after " + events + " events");
                }
                return result(BinaryBepParseOutcome.TRUNCATED, events, startOffset, frameOffset,
                        tail, OptionalLong.empty(), window,
                        "input ends inside a length prefix at offset " + frameOffset + "; "
                                + tail + " byte(s) of the prefix are present");
            }
            if (prefixBytes == VARINT_MALFORMED) {
                return result(BinaryBepParseOutcome.CORRUPT, events, startOffset, frameOffset,
                        window.available(), OptionalLong.empty(), window,
                        "length prefix at offset " + frameOffset + " does not terminate within "
                                + MAX_LENGTH_PREFIX_BYTES + " bytes");
            }

            long declared = declaredOut[0];
            if (declared > maxPayloadBytes) {
                // Rejected before any allocation: this is a damaged length field,
                // not a large event (plan 21.3).
                return result(BinaryBepParseOutcome.CORRUPT, events, startOffset, frameOffset,
                        window.available(), OptionalLong.of(declared), window,
                        "frame at offset " + frameOffset + " declares " + declared
                                + " payload bytes, above the " + maxPayloadBytes
                                + "-byte maximum; treating as corruption, not as a large event");
            }

            // 2. Payload. Grow at most to this frame's size, then refill.
            int payloadLength = (int) declared;
            int frameBytes = prefixBytes + payloadLength;
            window.ensureCapacity(frameBytes);
            while (window.available() < frameBytes && !window.eof) {
                window.fill();
            }
            if (window.available() < frameBytes) {
                int tail = window.available();
                return result(BinaryBepParseOutcome.TRUNCATED, events, startOffset, frameOffset,
                        tail, OptionalLong.empty(), window,
                        "frame at offset " + frameOffset + " declares " + payloadLength
                                + " payload bytes but only " + Math.max(0, tail - prefixBytes)
                                + " are present before end of input");
            }

            ByteBuffer payload = ByteBuffer
                    .wrap(window.data, window.start + prefixBytes, payloadLength)
                    .slice()
                    .asReadOnlyBuffer();
            sink.onEvent(new BinaryBepFrame(
                    frameOffset, frameOffset + prefixBytes, payloadLength, payload));

            window.start += frameBytes;
            frameOffset += frameBytes;
            events++;
        }
    }

    private static BinaryBepParseResult result(
            BinaryBepParseOutcome outcome,
            long events,
            long startOffset,
            long resumeOffset,
            long tailBytes,
            OptionalLong rejectedLength,
            Window window,
            String detail) {
        return new BinaryBepParseResult(outcome, events, startOffset, resumeOffset, tailBytes,
                rejectedLength, window.peakCapacity, detail);
    }

    /**
     * Decodes a protobuf varint32 at {@code start}.
     *
     * @return the number of bytes it occupied, {@link #VARINT_NEED_MORE} if the
     *     buffer ends mid-prefix, or {@link #VARINT_MALFORMED} if five bytes all
     *     carry a continuation bit. Five bytes is the ceiling for a uint32; a
     *     sixth would mean the writer emitted something that is not a length
     */
    private static int scanVarint(byte[] data, int start, int end, long[] valueOut) {
        long value = 0;
        int shift = 0;
        for (int i = 0; i < MAX_LENGTH_PREFIX_BYTES; i++) {
            int position = start + i;
            if (position >= end) {
                return VARINT_NEED_MORE;
            }
            int b = data[position] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                valueOut[0] = value;
                return i + 1;
            }
            shift += 7;
        }
        return VARINT_MALFORMED;
    }

    /**
     * The single read buffer: bytes {@code [start, end)} of {@code data} are
     * filled and unconsumed. Compacts in place rather than reallocating, and
     * grows only when one frame needs more room than it has.
     */
    private static final class Window {
        private final ReadableByteChannel channel;
        private final int hardCapacity;

        private byte[] data;
        private int start;
        private int end;
        private int peakCapacity;
        private boolean eof;

        Window(ReadableByteChannel channel, int initialCapacity, int hardCapacity) {
            this.channel = channel;
            this.hardCapacity = hardCapacity;
            this.data = new byte[initialCapacity];
            this.peakCapacity = initialCapacity;
        }

        int available() {
            return end - start;
        }

        /** Reads one chunk from the channel, compacting first if the buffer is full. */
        void fill() throws IOException {
            if (eof) {
                return;
            }
            if (end == data.length) {
                compact();
                if (end == data.length) {
                    // Caller must ensureCapacity before asking for more bytes.
                    throw new IllegalStateException(
                            "read buffer of " + data.length + " bytes is full and cannot compact");
                }
            }
            ByteBuffer target = ByteBuffer.wrap(data, end, data.length - end);
            int read = channel.read(target);
            if (read < 0) {
                eof = true;
                return;
            }
            end += read;
        }

        /** Makes room for {@code needed} contiguous bytes starting at {@code start}. */
        void ensureCapacity(int needed) {
            if (needed <= data.length - start) {
                return;
            }
            if (needed <= data.length) {
                compact();
                return;
            }
            int grown = (int) Math.max(needed, Math.min((long) data.length * 2, hardCapacity));
            if (grown > hardCapacity) {
                // Unreachable: a declared length above the maximum is rejected
                // before this point. Kept as a hard stop so no future edit can
                // turn a length field into an unbounded allocation.
                throw new IllegalStateException(
                        "refusing to grow read buffer to " + grown + " bytes, above the "
                                + hardCapacity + "-byte ceiling");
            }
            byte[] replacement = new byte[grown];
            System.arraycopy(data, start, replacement, 0, end - start);
            end -= start;
            start = 0;
            data = replacement;
            peakCapacity = Math.max(peakCapacity, grown);
        }

        private void compact() {
            if (start == 0) {
                return;
            }
            System.arraycopy(data, start, data, 0, end - start);
            end -= start;
            start = 0;
        }
    }
}
