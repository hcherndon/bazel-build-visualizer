package com.holtherndon.bazelviz.capture.file.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behaviour of the length-delimited BEP parser: what it delivers, where it says
 * it stopped, and what it refuses to do.
 */
final class BinaryBepParserTest {

    @TempDir
    Path tempDir;

    private final BinaryBepParser parser = BinaryBepParser.withDefaults();

    private static BinaryBepParseResult parse(byte[] bytes, BinaryBepParser parser,
            RecordingSink sink) throws IOException {
        return parser.parse(new ByteArrayInputStream(bytes), 0, sink);
    }

    // ------------------------------------------------------------ happy path

    @Test
    void deliversEveryEventWithItsExactOffsetAndBytes() throws IOException {
        List<BuildEvent> events = BepFixtures.mixedStream();
        byte[] stream = BepFixtures.delimit(events);
        long[] expectedOffsets = BepFixtures.frameOffsets(events);

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(stream, parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.eventCount()).isEqualTo(events.size());
        assertThat(result.resumeOffset()).isEqualTo(stream.length);
        assertThat(result.bytesConsumed()).isEqualTo(stream.length);
        assertThat(result.incompleteTailBytes()).isZero();
        assertThat(result.incompleteFrameOffset()).isEmpty();
        assertThat(result.rejectedDeclaredLength()).isEmpty();

        assertThat(sink.frameOffsets())
                .containsExactly(Arrays.stream(expectedOffsets).boxed().toArray(Long[]::new));
        for (int i = 0; i < events.size(); i++) {
            RecordingSink.Delivered delivered = sink.frames().get(i);
            BuildEvent expected = events.get(i);
            assertThat(delivered.payloadLength()).isEqualTo(expected.getSerializedSize());
            // Bytes are handed over verbatim (ADR-004), not re-serialized.
            assertThat(delivered.payload()).isEqualTo(expected.toByteArray());
            assertThat(BuildEvent.parseFrom(delivered.payload())).isEqualTo(expected);
            assertThat(delivered.payloadOffset() - delivered.frameOffset())
                    .isEqualTo(BepFixtures.varintLength(expected.getSerializedSize()));
        }
    }

    @Test
    void prefixWidthsInTheFixtureCoverEveryVarintSize() {
        // Guards the fixture itself: the truncation tests below only exercise
        // multi-byte prefixes if the stream actually contains them.
        List<Integer> widths = BepFixtures.mixedStream().stream()
                .map(event -> BepFixtures.varintLength(event.getSerializedSize()))
                .distinct()
                .sorted()
                .toList();
        assertThat(widths).containsExactly(1, 2, 3);
    }

    @Test
    void parsesFromAFileAtOffsetZero() throws IOException {
        List<BuildEvent> events = BepFixtures.mixedStream();
        Path file = BepFixtures.writeStream(tempDir.resolve("build.bep"), events);

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parser.parseFile(file, 0, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(sink.size()).isEqualTo(events.size());
        assertThat(result.resumeOffset()).isEqualTo(Files.size(file));
    }

    // ---------------------------------------------------------------- resume

    @Test
    void resumingAtAnyFrameOffsetMatchesTheTailOfAFullParse() throws IOException {
        List<BuildEvent> events = BepFixtures.mixedStream();
        Path file = BepFixtures.writeStream(tempDir.resolve("build.bep"), events);

        RecordingSink full = new RecordingSink();
        parser.parseFile(file, 0, full);

        for (int start = 0; start < full.size(); start++) {
            long offset = full.frames().get(start).frameOffset();
            RecordingSink resumed = new RecordingSink();
            BinaryBepParseResult result = parser.parseFile(file, offset, resumed);

            assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
            assertThat(result.startOffset()).isEqualTo(offset);
            assertThat(result.eventCount()).isEqualTo(full.size() - start);
            assertThat(resumed.frames())
                    .describedAs("resume at frame %d (offset %d)", start, offset)
                    .usingRecursiveComparison()
                    .isEqualTo(full.frames().subList(start, full.size()));
        }
    }

    @Test
    void resumeOffsetOfATruncatedParseContinuesExactlyWhereItStopped() throws IOException {
        List<BuildEvent> events = BepFixtures.mixedStream();
        byte[] stream = BepFixtures.delimit(events);
        long[] offsets = BepFixtures.frameOffsets(events);
        // Cut three bytes into the last frame's payload.
        int cut = (int) offsets[4] + 3;

        Path file = tempDir.resolve("growing.bep");
        Files.write(file, Arrays.copyOf(stream, cut));

        RecordingSink first = new RecordingSink();
        BinaryBepParseResult firstPass = parser.parseFile(file, 0, first);
        assertThat(firstPass.outcome()).isEqualTo(BinaryBepParseOutcome.TRUNCATED);
        assertThat(firstPass.resumeOffset()).isEqualTo(offsets[4]);
        assertThat(first.size()).isEqualTo(4);

        Files.write(file, stream);
        RecordingSink second = new RecordingSink();
        BinaryBepParseResult secondPass =
                parser.parseFile(file, firstPass.resumeOffset(), second);

        assertThat(secondPass.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(second.size()).isEqualTo(1);
        assertThat(second.frames().get(0).frameOffset()).isEqualTo(offsets[4]);
        assertThat(first.size() + second.size()).isEqualTo(events.size());
    }

    // ------------------------------------------------------------ truncation

    @Test
    void everyTruncationLengthReportsTheRightOffsetAndKeepsPrecedingEvents() throws IOException {
        List<BuildEvent> events = BepFixtures.smallStream();
        byte[] stream = BepFixtures.delimit(events);
        long[] offsets = BepFixtures.frameOffsets(events);

        for (int cut = 0; cut <= stream.length; cut++) {
            RecordingSink sink = new RecordingSink();
            BinaryBepParseResult result =
                    parse(Arrays.copyOf(stream, cut), parser, sink);

            int completeFrames = 0;
            while (completeFrames < events.size()
                    && offsets[completeFrames] + BepFixtures.varintLength(
                            events.get(completeFrames).getSerializedSize())
                            + events.get(completeFrames).getSerializedSize() <= cut) {
                completeFrames++;
            }
            long boundary = completeFrames == events.size()
                    ? stream.length
                    : offsets[completeFrames];

            assertThat(sink.size()).describedAs("events at cut %d", cut).isEqualTo(completeFrames);
            assertThat(result.eventCount()).isEqualTo(completeFrames);
            assertThat(result.resumeOffset()).describedAs("resume offset at cut %d", cut)
                    .isEqualTo(boundary);
            assertThat(result.incompleteTailBytes()).isEqualTo(cut - boundary);
            if (cut == boundary) {
                assertThat(result.outcome()).describedAs("cut %d lands on a boundary", cut)
                        .isEqualTo(BinaryBepParseOutcome.COMPLETE);
            } else {
                assertThat(result.outcome()).describedAs("cut %d lands mid-frame", cut)
                        .isEqualTo(BinaryBepParseOutcome.TRUNCATED);
                assertThat(result.completeness()).isEqualTo(Completeness.TRUNCATED);
                assertThat(result.incompleteFrameOffset()).hasValue(boundary);
            }
        }
    }

    @Test
    void truncationInsideAMultiByteLengthPrefixIsReportedAtTheFrameStart() throws IOException {
        // The third fixture event needs a two-byte prefix; keep only its first byte.
        List<BuildEvent> events = BepFixtures.mixedStream();
        byte[] stream = BepFixtures.delimit(events);
        long[] offsets = BepFixtures.frameOffsets(events);
        assertThat(BepFixtures.varintLength(events.get(2).getSerializedSize())).isEqualTo(2);

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result =
                parse(Arrays.copyOf(stream, (int) offsets[2] + 1), parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.TRUNCATED);
        assertThat(result.resumeOffset()).isEqualTo(offsets[2]);
        assertThat(result.incompleteTailBytes()).isEqualTo(1);
        assertThat(result.detail()).contains("inside a length prefix");
        assertThat(sink.size()).isEqualTo(2);
    }

    @Test
    void truncationImmediatelyAfterALengthPrefixIsTruncationNotCorruption() throws IOException {
        List<BuildEvent> events = BepFixtures.smallStream();
        byte[] stream = BepFixtures.delimit(events);
        long[] offsets = BepFixtures.frameOffsets(events);
        int prefixBytes = BepFixtures.varintLength(events.get(1).getSerializedSize());

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result =
                parse(Arrays.copyOf(stream, (int) offsets[1] + prefixBytes), parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.TRUNCATED);
        assertThat(result.resumeOffset()).isEqualTo(offsets[1]);
        assertThat(result.incompleteTailBytes()).isEqualTo(prefixBytes);
        assertThat(result.detail()).contains("only 0 are present");
        assertThat(sink.size()).isEqualTo(1);
    }

    @Test
    void emptyInputEndsOnAFrameBoundaryWithNoEvents() throws IOException {
        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(new byte[0], parser, sink);

        // Zero bytes is a frame boundary, not a partial frame: there is nothing
        // to truncate. "The source contained no events" is a separate judgement
        // for the importer, made with information the parser does not have.
        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(result.eventCount()).isZero();
        assertThat(result.resumeOffset()).isZero();
        assertThat(sink.size()).isZero();
    }

    @Test
    void aSingleContinuationByteIsATruncatedPrefix() throws IOException {
        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(new byte[] {(byte) 0x80}, parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.TRUNCATED);
        assertThat(result.resumeOffset()).isZero();
        assertThat(result.incompleteTailBytes()).isEqualTo(1);
    }

    @Test
    void aZeroLengthFrameIsDeliveredRatherThanDropped() throws IOException {
        // Never silently drop: a zero-length frame is a legal encoding of a
        // default BuildEvent, so it is reported, not skipped.
        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(new byte[] {0, 0}, parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(sink.size()).isEqualTo(2);
        assertThat(sink.frames().get(0).payloadLength()).isZero();
        assertThat(sink.frames().get(1).frameOffset()).isEqualTo(1);
    }

    // ------------------------------------------------------------ corruption

    @Test
    void anOversizedDeclaredLengthIsRejectedWithoutAllocatingForIt() throws IOException {
        BinaryBepParser bounded = new BinaryBepParser(1024, 4096);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BuildEvent first = BepFixtures.progress(1, "fine");
        first.writeDelimitedTo(out);
        int goodFrameBytes = out.size();
        out.writeBytes(BepFixtures.varint(1L << 30));

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(out.toByteArray(), bounded, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.CORRUPT);
        assertThat(result.completeness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
        assertThat(result.rejectedDeclaredLength()).hasValue(1L << 30);
        assertThat(result.resumeOffset()).isEqualTo(goodFrameBytes);
        assertThat(result.eventCount()).isEqualTo(1);
        assertThat(sink.size()).isEqualTo(1);
        // The buffer never grew towards the declared gigabyte.
        assertThat(result.peakBufferBytes()).isEqualTo(4096);
        assertThat(result.detail()).contains("not as a large event");
    }

    @Test
    void aLengthPrefixAboveTheUint32RangeIsCorruptNotHuge() throws IOException {
        // Five continuation-free bytes that decode to more than 2^32.
        byte[] stream = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(stream, parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.CORRUPT);
        assertThat(result.rejectedDeclaredLength()).isPresent();
        assertThat(result.rejectedDeclaredLength().getAsLong())
                .isGreaterThan(parser.maxPayloadBytes());
        assertThat(result.resumeOffset()).isZero();
    }

    @Test
    void aLengthPrefixThatNeverTerminatesIsCorrupt() throws IOException {
        byte[] stream = new byte[8];
        Arrays.fill(stream, (byte) 0xFF);

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(stream, parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.CORRUPT);
        assertThat(result.rejectedDeclaredLength()).isEmpty();
        assertThat(result.detail()).contains("does not terminate");
        assertThat(result.outcome().isResumable()).isFalse();
    }

    @Test
    void corruptionAfterGoodEventsKeepsThoseEvents() throws IOException {
        List<BuildEvent> events = BepFixtures.smallStream();
        byte[] good = BepFixtures.delimit(events);
        byte[] stream = Arrays.copyOf(good, good.length + 5);
        Arrays.fill(stream, good.length, stream.length, (byte) 0xFF);

        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(stream, parser, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.CORRUPT);
        assertThat(sink.size()).isEqualTo(events.size());
        assertThat(result.resumeOffset()).isEqualTo(good.length);
    }

    // --------------------------------------------------------- bounded memory

    @Test
    void peakBufferStaysAtTheConfiguredSizeAcrossAStreamFarLargerThanIt() throws IOException {
        byte[] block = BepFixtures.delimit(BepFixtures.mixedStream());
        int repeats = 6_000;
        long totalBytes = (long) block.length * repeats;
        assertThat(totalBytes).isGreaterThan(100L * 1024 * 1024);

        RecordingCountingSink counter = new RecordingCountingSink();
        BinaryBepParseResult result = parser.parse(
                new RepeatingChannel(block, repeats), 0, counter);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(result.bytesConsumed()).isEqualTo(totalBytes);
        assertThat(counter.count()).isEqualTo(5L * repeats);
        // The whole point: a 100+ MiB stream is read through a 64 KiB buffer.
        assertThat(result.peakBufferBytes()).isEqualTo(BinaryBepParser.DEFAULT_BUFFER_BYTES);
    }

    @Test
    void theBufferGrowsToTheLargestFrameAndNoFurther() throws IOException {
        BuildEvent huge = BepFixtures.progress(1, "z".repeat(200_000));
        List<BuildEvent> events = List.of(BepFixtures.progress(0, "small"), huge,
                BepFixtures.progress(2, "small"));
        byte[] stream = BepFixtures.delimit(events);
        int largestFrameBytes = BepFixtures.varintLength(huge.getSerializedSize())
                + huge.getSerializedSize();

        BinaryBepParser small = new BinaryBepParser(1 << 20, 4096);
        RecordingSink sink = new RecordingSink();
        BinaryBepParseResult result = parse(stream, small, sink);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.COMPLETE);
        assertThat(sink.size()).isEqualTo(3);
        assertThat(result.peakBufferBytes()).isEqualTo(largestFrameBytes);
        assertThat(result.peakBufferBytes()).isLessThan(stream.length);
    }

    // ------------------------------------------------------------ cancellation

    @Test
    void cancellationStopsOnAFrameBoundaryAndSaysWhereToResume() throws IOException {
        List<BuildEvent> events = BepFixtures.mixedStream();
        Path file = BepFixtures.writeStream(tempDir.resolve("build.bep"), events);
        long[] offsets = BepFixtures.frameOffsets(events);

        RecordingSink sink = new RecordingSink();
        AtomicInteger seen = new AtomicInteger();
        BinaryBepParseResult result = parser.parseFile(file, 0,
                frame -> {
                    seen.incrementAndGet();
                    sink.onEvent(frame);
                },
                () -> seen.get() >= 2);

        assertThat(result.outcome()).isEqualTo(BinaryBepParseOutcome.CANCELLED);
        assertThat(result.completeness()).isEqualTo(Completeness.UNKNOWN);
        assertThat(sink.size()).isEqualTo(2);
        assertThat(result.resumeOffset()).isEqualTo(offsets[2]);

        RecordingSink rest = new RecordingSink();
        parser.parseFile(file, result.resumeOffset(), rest);
        assertThat(sink.size() + rest.size()).isEqualTo(events.size());
    }

    // --------------------------------------------------------------- guards

    @Test
    void rejectsANegativeStartOffset() {
        assertThatThrownBy(() -> parser.parseFile(tempDir.resolve("nope"), -1, frame -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startOffset");
    }

    @Test
    void rejectsAPayloadCeilingThatWouldOverflowTheBufferCeiling() {
        assertThatThrownBy(() -> new BinaryBepParser(Integer.MAX_VALUE, 4096))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPayloadBytes");
    }

    /** Counts frames without retaining them, for the large-stream test. */
    private static final class RecordingCountingSink implements BinaryBepEventSink {
        private long count;

        @Override
        public void onEvent(BinaryBepFrame frame) {
            count++;
        }

        long count() {
            return count;
        }
    }

    /**
     * Emits {@code block} {@code repeats} times without ever materializing the
     * whole stream, so the bounded-memory test does not itself need the memory
     * it is proving the parser avoids.
     */
    private static final class RepeatingChannel
            implements java.nio.channels.ReadableByteChannel {
        private final byte[] block;
        private final int repeats;
        private int emitted;
        private int position;
        private boolean open = true;

        RepeatingChannel(byte[] block, int repeats) {
            this.block = block;
            this.repeats = repeats;
        }

        @Override
        public int read(java.nio.ByteBuffer dst) {
            if (emitted == repeats) {
                return -1;
            }
            int n = Math.min(dst.remaining(), block.length - position);
            dst.put(block, position, n);
            position += n;
            if (position == block.length) {
                position = 0;
                emitted++;
            }
            return n;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
