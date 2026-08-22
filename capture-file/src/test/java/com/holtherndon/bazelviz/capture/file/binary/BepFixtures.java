package com.holtherndon.bazelviz.capture.file.binary;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixtures built from the real generated BEP protobuf classes and framed with
 * protobuf's own {@code writeDelimitedTo}, so the tests exercise the byte layout
 * Bazel actually writes rather than one this project invented and might have
 * got wrong in the same way in both places.
 *
 * <p>Shared by the parser and detector tests.
 */
public final class BepFixtures {

    private BepFixtures() {}

    public static BuildEvent started(String uuid) {
        return BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setStarted(BuildEventId.BuildStartedId.getDefaultInstance()))
                .setStarted(BuildStarted.newBuilder()
                        .setUuid(uuid)
                        .setCommand("build")
                        .setBuildToolVersion("7.4.1")
                        .setWorkspaceDirectory("/workspace"))
                .build();
    }

    public static BuildEvent progress(int opaqueCount, String stdout) {
        return BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setProgress(BuildEventId.ProgressId.newBuilder()
                                .setOpaqueCount(opaqueCount)))
                .setProgress(Progress.newBuilder().setStdout(stdout))
                .build();
    }

    /**
     * A short stream whose frame sizes deliberately cross the varint length
     * boundaries: one-byte prefixes (payload &lt; 128), two-byte (&lt; 16384) and
     * three-byte prefixes. Truncating this stream therefore exercises cuts
     * inside prefixes of every width.
     */
    public static List<BuildEvent> mixedStream() {
        List<BuildEvent> events = new ArrayList<>();
        events.add(started("11111111-2222-3333-4444-555555555555"));
        events.add(progress(1, ""));
        events.add(progress(2, "x".repeat(200)));
        events.add(progress(3, "y".repeat(20_000)));
        events.add(progress(4, "done"));
        return List.copyOf(events);
    }

    /** Small events only, for tests that parse the stream once per truncation length. */
    public static List<BuildEvent> smallStream() {
        return List.of(
                started("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                progress(1, "compiling"),
                progress(2, ""),
                progress(3, "linking"));
    }

    /** Length-delimited encoding of {@code events}, exactly as Bazel writes it. */
    public static byte[] delimit(List<BuildEvent> events) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (BuildEvent event : events) {
                event.writeDelimitedTo(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Absolute frame offsets of {@code events} in their delimited encoding. */
    public static long[] frameOffsets(List<BuildEvent> events) {
        long[] offsets = new long[events.size()];
        long offset = 0;
        for (int i = 0; i < events.size(); i++) {
            offsets[i] = offset;
            int payload = events.get(i).getSerializedSize();
            offset += varintLength(payload) + payload;
        }
        return offsets;
    }

    /** Bytes a protobuf {@code uint32} varint occupies. */
    public static int varintLength(int value) {
        int bytes = 1;
        long remaining = Integer.toUnsignedLong(value);
        while (remaining >= 0x80) {
            remaining >>>= 7;
            bytes++;
        }
        return bytes;
    }

    /** Encodes {@code value} as a protobuf varint. */
    public static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long remaining = value;
        while (true) {
            if ((remaining & ~0x7FL) == 0) {
                out.write((int) remaining);
                return out.toByteArray();
            }
            out.write((int) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
    }

    public static Path writeStream(Path file, List<BuildEvent> events) throws IOException {
        Files.write(file, delimit(events));
        return file;
    }
}
