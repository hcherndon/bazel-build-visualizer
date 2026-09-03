package com.holtherndon.bazelviz.capture.file.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.holtherndon.bazelviz.capture.file.binary.BepFixtures;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Format detection by content. The cases that matter are the negative ones: anything that merely
 * resembles a length-delimited protobuf must not be accepted as BEP, because accepting it starts an
 * import that then fails somewhere far less legible.
 */
final class FormatDetectorTest {

  @TempDir Path tempDir;

  private final FormatDetector detector = FormatDetector.withDefaults();

  private static final String JSON_LINES =
      """
      {"id":{"started":{}},"started":{"uuid":"abc","command":"build"}}
      {"id":{"progress":{"opaqueCount":1}},"progress":{"stdout":"compiling"}}
      """;

  private Path write(String name, byte[] bytes) throws IOException {
    Path file = tempDir.resolve(name);
    Files.write(file, bytes);
    return file;
  }

  private Path write(String name, String text) throws IOException {
    return write(name, text.getBytes(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------- positives

  @Test
  void binaryBepIsRecognizedByValidatingItsFirstFrame() throws IOException {
    // Named as if it were something else: detection reads content, not names.
    Path file = write("events.json", BepFixtures.delimit(BepFixtures.mixedStream()));

    FormatDetection detection = detector.detect(file);

    assertThat(detection.format()).isEqualTo(DetectedFormat.BEP_BINARY);
    assertThat(detection.reason()).contains("parse as a BuildEvent");
    assertThat(detection.reason()).contains("second frame");
    assertThat(detection.bytesInspected()).isLessThanOrEqualTo(detector.probeBytes());
  }

  @Test
  void jsonBepIsRecognizedOneObjectPerLine() throws IOException {
    Path file = write("build.bep", JSON_LINES);

    FormatDetection detection = detector.detect(file);

    assertThat(detection.format()).isEqualTo(DetectedFormat.BEP_JSON);
    assertThat(detection.reason()).contains("JSON object");
  }

  @Test
  void jsonBepIsRecognizedWhenPrettyPrintedWithLeadingWhitespaceAndABom() throws IOException {
    String pretty =
        """

        {
          "id": { "started": {} },
          "started": { "uuid": "abc" }
        }
        """;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    out.writeBytes(pretty.getBytes(StandardCharsets.UTF_8));

    FormatDetection detection = detector.detect(write("pretty.bep", out.toByteArray()));

    assertThat(detection.format()).isEqualTo(DetectedFormat.BEP_JSON);
  }

  @Test
  void aManagedSessionDirectoryIsRecognizedByItsManifest() throws IOException {
    Path session = Files.createDirectory(tempDir.resolve("session-1"));
    Files.writeString(
        session.resolve(FormatDetector.SESSION_MANIFEST_NAME),
        "{\"formatVersion\":1,\"sessionUuid\":\"abc\"}");
    Files.createDirectory(session.resolve("raw"));

    FormatDetection detection = detector.detect(session);

    assertThat(detection.format()).isEqualTo(DetectedFormat.MANAGED_SESSION_DIR);
  }

  // ------------------------------------------------------------- negatives

  @Test
  void aDirectoryWithoutAManifestIsUnknownNotGuessed() throws IOException {
    Path directory = Files.createDirectory(tempDir.resolve("plain"));
    Files.writeString(directory.resolve("build.bep"), JSON_LINES);

    FormatDetection detection = detector.detect(directory);

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains(FormatDetector.SESSION_MANIFEST_NAME);
  }

  @Test
  void aManifestThatIsNotAJsonObjectDoesNotMakeADirectoryASession() throws IOException {
    Path session = Files.createDirectory(tempDir.resolve("fake"));
    Files.writeString(session.resolve(FormatDetector.SESSION_MANIFEST_NAME), "not json");

    FormatDetection detection = detector.detect(session);

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("does not begin with a JSON object");
  }

  @Test
  void randomBytesAreUnknown() throws IOException {
    byte[] garbage = new byte[4096];
    new Random(20260821L).nextBytes(garbage);

    FormatDetection detection = detector.detect(write("garbage.bin", garbage));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("not binary BEP").contains("not JSON BEP");
  }

  @Test
  void plainTextIsUnknown() throws IOException {
    FormatDetection detection =
        detector.detect(write("build.log", "INFO: Analyzed 42 targets.\nINFO: Build completed.\n"));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
  }

  @Test
  void anEmptyFileIsUnknownRatherThanAnEmptyStreamOfSomething() throws IOException {
    FormatDetection detection = detector.detect(write("empty.bep", new byte[0]));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("empty");
    assertThat(detection.bytesInspected()).isZero();
  }

  @Test
  void aValidVarintFollowedByTextIsNotBinaryBep() throws IOException {
    // Correctly framed, wrong content: the classic false positive that a
    // magic-byte guess would fall for.
    byte[] text =
        "this is a log line, not a serialized BuildEvent at all!".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(BepFixtures.varint(text.length));
    out.writeBytes(text);

    FormatDetection detection = detector.detect(write("framed-text.bin", out.toByteArray()));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
  }

  @Test
  void aLengthDelimitedMessageOfADifferentTypeIsNotBinaryBep() throws IOException {
    // Progress and BuildEventId are real BEP messages, but neither is the
    // BuildEvent envelope, and a stream of them is not a BEP stream.
    ByteArrayOutputStream progressStream = new ByteArrayOutputStream();
    Progress.newBuilder()
        .setStdout("compiling //src:lib".repeat(4))
        .build()
        .writeDelimitedTo(progressStream);
    ByteArrayOutputStream idStream = new ByteArrayOutputStream();
    BuildEventId.newBuilder()
        .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(7))
        .build()
        .writeDelimitedTo(idStream);

    assertThat(detector.detect(write("progress.bin", progressStream.toByteArray())).format())
        .isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detector.detect(write("ids.bin", idStream.toByteArray())).format())
        .isEqualTo(DetectedFormat.UNKNOWN);
  }

  @Test
  void aZeroLengthFirstFrameProvesNothing() throws IOException {
    byte[] stream = new byte[] {0, 0, 0};

    FormatDetection detection = detector.detect(write("zeros.bin", stream));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("empty payload");
  }

  @Test
  void aFirstFrameLargerThanTheWindowIsReportedAsUnverifiableNotAssumedGood() throws IOException {
    FormatDetector narrow = new FormatDetector(64, 64 * 1024 * 1024);
    Path file =
        write(
            "big-first.bep",
            BepFixtures.delimit(List.of(BepFixtures.progress(1, "x".repeat(500)))));

    FormatDetection detection = narrow.detect(file);

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("detection window");
    assertThat(detection.bytesInspected()).isEqualTo(64);
  }

  @Test
  void aTruncatedFirstFrameInACompleteFileIsNotBinaryBep() throws IOException {
    byte[] full = BepFixtures.delimit(BepFixtures.mixedStream());
    byte[] head = Arrays.copyOf(full, 10);

    FormatDetection detection = detector.detect(write("head.bep", head));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("the input holds");
  }

  // ------------------------------------------------------------- ambiguity

  @Test
  void inputThatReadsAsBothFormatsIsUnknownRatherThanAGuess() throws IOException {
    // Constructed so that byte 0 is both '{' and the varint length 123, and
    // the bytes after it are simultaneously a valid 123-byte BuildEvent and
    // a newline plus an opening quote. Exactly the collision that makes
    // "starts with a brace" an unsafe test on its own.
    BuildEvent event =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setUnknown(
                        BuildEventId.UnknownBuildEventId.newBuilder().setDetails("d".repeat(30))))
            .setProgress(Progress.newBuilder().setStdout("s".repeat(83)))
            .build();
    assertThat(event.getSerializedSize()).isEqualTo(123);
    byte[] body = event.toByteArray();
    assertThat(body[0]).isEqualTo((byte) 0x0A); // reads as '\n' to the JSON test
    assertThat(body[1]).isEqualTo((byte) 0x22); // reads as '"' to the JSON test

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    event.writeDelimitedTo(out);
    assertThat(out.toByteArray()[0]).isEqualTo((byte) '{');

    FormatDetection detection = detector.detect(write("ambiguous.bin", out.toByteArray()));

    assertThat(detection.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detection.reason()).contains("both binary and JSON BEP");
  }

  // ---------------------------------------------------------------- streams

  @Test
  void streamDetectionRewindsSoTheParserStillSeesEveryEvent() throws IOException {
    List<BuildEvent> events = BepFixtures.mixedStream();
    byte[] stream = BepFixtures.delimit(events);
    // A stream that refuses to be marked, like a pipe from a subprocess.
    InputStream unmarkable = new UnmarkableStream(stream);

    StreamDetection detected = detector.detect(unmarkable);

    assertThat(detected.format()).isEqualTo(DetectedFormat.BEP_BINARY);

    AtomicInteger count = new AtomicInteger();
    var result =
        BinaryBepParser.withDefaults()
            .parse(detected.stream(), 0, frame -> count.incrementAndGet());

    assertThat(count.get()).isEqualTo(events.size());
    assertThat(result.resumeOffset()).isEqualTo(stream.length);
  }

  @Test
  void detectionNeverReadsMoreThanTheProbeWindow() throws IOException {
    byte[] stream = BepFixtures.delimit(BepFixtures.mixedStream());
    CountingStream counting = new CountingStream(stream);

    StreamDetection detected = detector.detect(counting);

    assertThat(detected.format()).isEqualTo(DetectedFormat.BEP_BINARY);
    assertThat(detected.detection().bytesInspected()).isLessThanOrEqualTo(detector.probeBytes());
    assertThat(counting.bytesRead())
        .isLessThanOrEqualTo(detector.probeBytes() + BinaryBepParser.MAX_LENGTH_PREFIX_BYTES);
  }

  @Test
  void anEmptyStreamIsUnknown() throws IOException {
    StreamDetection detected = detector.detect(new ByteArrayInputStream(new byte[0]));

    assertThat(detected.format()).isEqualTo(DetectedFormat.UNKNOWN);
    assertThat(detected.detection().reason()).contains("empty");
  }

  @Test
  void aMissingPathIsAnErrorNotAVerdict() {
    assertThatThrownBy(() -> detector.detect(tempDir.resolve("absent")))
        .isInstanceOf(NoSuchFileException.class);
  }

  /** An {@link InputStream} that cannot be marked, forcing the buffering path. */
  private static class UnmarkableStream extends InputStream {
    private final byte[] data;
    private int position;

    UnmarkableStream(byte[] data) {
      this.data = data;
    }

    @Override
    public int read() {
      return position < data.length ? data[position++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (position >= data.length) {
        return -1;
      }
      int n = Math.min(len, data.length - position);
      System.arraycopy(data, position, b, off, n);
      position += n;
      return n;
    }

    @Override
    public boolean markSupported() {
      return false;
    }
  }

  /** Counts bytes pulled from the underlying source. */
  private static final class CountingStream extends UnmarkableStream {
    private int bytesRead;

    CountingStream(byte[] data) {
      super(data);
    }

    @Override
    public int read() {
      int b = super.read();
      if (b >= 0) {
        bytesRead++;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      int n = super.read(b, off, len);
      if (n > 0) {
        bytesRead += n;
      }
      return n;
    }

    int bytesRead() {
      return bytesRead;
    }
  }
}
