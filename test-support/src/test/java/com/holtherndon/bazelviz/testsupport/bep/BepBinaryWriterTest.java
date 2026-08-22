package com.holtherndon.bazelviz.testsupport.bep;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the binary fixture really is Bazel's {@code --build_event_binary_file}
 * layout by reading it back with a reader written from the wire-format spec,
 * not with protobuf's own {@code parseDelimitedFrom} and not with the module's
 * {@link LengthDelimitedFrames}. If the writer and both readers agree, the
 * bytes are the format and not a convention shared by one library.
 */
final class BepBinaryWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void outputRoundTripsThroughAHandWrittenLengthDelimitedReader() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(203);
        Path file = tempDir.resolve("build.bep");

        long bytesWritten = BepBinaryWriter.write(file, stream);

        assertThat(bytesWritten).isEqualTo(Files.size(file));

        List<BuildEvent> decoded = readHandRolled(Files.readAllBytes(file));
        List<BuildEvent> expected = stream.events().toList();

        assertThat(decoded).hasSize(stream.eventCount());
        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void fileLengthIsExactlyTheSumOfPrefixAndPayloadLengths() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(80);
        Path file = tempDir.resolve("build.bep");
        long bytesWritten = BepBinaryWriter.write(file, stream);

        long expected = 0;
        for (BuildEvent event : stream.events().toList()) {
            int payload = event.getSerializedSize();
            expected += LengthDelimitedFrames.varintLengthOf(payload) + payload;
        }

        assertThat(bytesWritten).isEqualTo(expected);
    }

    @Test
    void theModulesFrameIndexAgreesWithTheHandWrittenReader() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(120);
        Path file = tempDir.resolve("build.bep");
        BepBinaryWriter.write(file, stream);

        LengthDelimitedFrames.IndexResult scan = LengthDelimitedFrames.index(file);
        assertThat(scan.tail()).isEqualTo(LengthDelimitedFrames.Tail.CLEAN);
        assertThat(scan.truncatedTailBytes()).isZero();
        assertThat(scan.cleanEndOffset()).isEqualTo(scan.fileBytes());
        assertThat(scan.frames()).hasSize(stream.eventCount());

        byte[] all = Files.readAllBytes(file);
        long offset = 0;
        for (int i = 0; i < scan.frames().size(); i++) {
            LengthDelimitedFrames.Frame frame = scan.frames().get(i);
            int payloadLength = stream.eventAt(i).getSerializedSize();
            assertThat(frame.index()).isEqualTo(i);
            assertThat(frame.varintOffset()).isEqualTo(offset);
            assertThat(frame.varintLength()).isEqualTo(LengthDelimitedFrames.varintLengthOf(payloadLength));
            assertThat(frame.payloadLength()).isEqualTo(payloadLength);
            assertThat(frame.payloadOffset()).isEqualTo(offset + frame.varintLength());

            byte[] payload = new byte[payloadLength];
            System.arraycopy(all, (int) frame.payloadOffset(), payload, 0, payloadLength);
            assertThat(BuildEvent.parseFrom(payload)).isEqualTo(stream.eventAt(i));

            offset = frame.endOffset();
        }
        assertThat(offset).isEqualTo(Files.size(file));
    }

    @Test
    void writingTheSameStreamTwiceProducesIdenticalBytes() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(64);
        Path first = tempDir.resolve("a.bep");
        Path second = tempDir.resolve("b.bep");

        BepBinaryWriter.write(first, stream);
        BepBinaryWriter.write(second, SyntheticBepStream.of(64));

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
    }

    @Test
    void aLargeStreamIsWrittenAndScannedWithoutMaterializingEitherSide() throws IOException {
        // Writer and scanner both have to be streaming for the Phase 1
        // "no full file in memory" criterion to be testable at all. Neither side
        // here holds more than one frame.
        SyntheticBepStream stream = SyntheticBepStream.of(50_000);
        Path file = tempDir.resolve("large.bep");

        long bytesWritten = BepBinaryWriter.write(file, stream);
        assertThat(bytesWritten).isEqualTo(Files.size(file));
        assertThat(bytesWritten).isGreaterThan(1_000_000L);

        int[] frames = {0};
        LengthDelimitedFrames.ScanResult scan = LengthDelimitedFrames.scan(file, frame -> {
            frames[0]++;
            return true;
        });

        assertThat(frames[0]).isEqualTo(50_000);
        assertThat(scan.tail()).isEqualTo(LengthDelimitedFrames.Tail.CLEAN);
        assertThat(scan.cleanEndOffset()).isEqualTo(bytesWritten);
    }

    @Test
    void writeCreatesMissingParentDirectories() throws IOException {
        Path nested = tempDir.resolve("a/b/c/build.bep");
        BepBinaryWriter.write(nested, SyntheticBepStream.of(16));
        assertThat(Files.exists(nested)).isTrue();
    }

    /**
     * A length-delimited reader written straight from the protobuf wire-format
     * spec: base-128 varint, little-endian groups, high bit as the continuation
     * flag, then exactly that many payload bytes.
     */
    private static List<BuildEvent> readHandRolled(byte[] bytes) throws IOException {
        List<BuildEvent> events = new ArrayList<>();
        try (InputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            while (true) {
                int first = in.read();
                if (first < 0) {
                    return events;
                }
                int length = first & 0x7F;
                int shift = 7;
                int b = first;
                while ((b & 0x80) != 0) {
                    b = in.read();
                    if (b < 0) {
                        throw new IOException("truncated varint");
                    }
                    length |= (b & 0x7F) << shift;
                    shift += 7;
                }
                byte[] payload = in.readNBytes(length);
                if (payload.length != length) {
                    throw new IOException("truncated payload: wanted " + length + " got " + payload.length);
                }
                events.add(BuildEvent.parseFrom(payload));
            }
        }
    }
}
