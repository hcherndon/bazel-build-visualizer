package com.holtherndon.bazelviz.capture.file.detect;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Decides what a capture input actually is by reading its content, never its name (plan 5.2). A BEP
 * file may be called {@code build.bep}, {@code events.pb}, {@code bep.json} or nothing at all, and
 * users routinely rename them; extensions are not evidence.
 *
 * <h2>How binary BEP is recognized</h2>
 *
 * Not by a magic number — the format has none. The detector decodes the first varint length prefix
 * and requires the bytes it points at to parse as a real {@link BuildEvent}. Parsing alone is not
 * enough: protobuf is permissive, and plenty of unrelated byte sequences decode "successfully" into
 * a message made entirely of unknown fields. So the first event must also look like a BEP event —
 * it must carry a {@code BuildEventId} with a set oneof case, its unknown fields must not dominate
 * the message, and their field numbers must be in the range a future Bazel would plausibly use.
 * Random bytes essentially never clear all of that, which is what keeps a length-delimited
 * <em>something-else</em> from being imported as BEP.
 *
 * <h2>Ambiguity</h2>
 *
 * The binary and JSON tests are both run, always. A JSON file starting with <code>{</code> is also
 * a byte {@code 0x7B}, a perfectly plausible 123-byte length prefix, so "starts with a brace"
 * cannot be checked first and trusted. If both tests pass, the answer is {@link
 * DetectedFormat#UNKNOWN} with both reasons stated. The detector never picks a winner.
 *
 * <h2>Bounded lookahead</h2>
 *
 * Detection reads at most {@link #probeBytes()} bytes — 64 KiB by default — and reports how many it
 * used. That is the entire rewind budget it needs: a caller on a non-rewindable stream can hand it
 * a {@link BufferedInputStream} whose buffer is that size and lose nothing, and {@link
 * #detect(InputStream)} does exactly that and returns the stream rewound. The real parse never
 * re-reads the file from the start on account of detection.
 *
 * <p>The budget has to cover the first frame whole, because a frame that is not fully inside the
 * window cannot be validated. Bazel's first event is {@code BuildStarted} — command line,
 * workspace, options; hundreds of bytes, occasionally a few kilobytes with a long command line. 64
 * KiB is generous cover for that, and a first frame larger than the window is reported as
 * indeterminate rather than assumed good.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FormatDetector {

  /** Default lookahead. See the class notes on why the first frame must fit. */
  public static final int DEFAULT_PROBE_BYTES = 64 * 1024;

  /** The file whose presence marks a managed session directory (plan 10.2). */
  public static final String SESSION_MANIFEST_NAME = "manifest.json";

  private static final int MAX_LENGTH_PREFIX_BYTES = 5;
  private static final int VARINT_NEED_MORE = -1;
  private static final int VARINT_MALFORMED = -2;

  /**
   * Highest field number the vendored {@code BuildEvent} schema defines, read from the descriptor
   * so it tracks proto updates instead of drifting from them.
   */
  private static final int MAX_KNOWN_BUILD_EVENT_FIELD =
      BuildEvent.getDescriptor().getFields().stream()
          .mapToInt(FieldDescriptor::getNumber)
          .max()
          .orElse(1);

  /**
   * How far past the known fields an unknown field number may sit and still be believable. Bazel
   * adds fields in sequence, so a genuine newer event's unknown fields land just above the known
   * range; garbage produces field numbers scattered across the whole 2^29 space.
   */
  private static final int UNKNOWN_FIELD_HEADROOM = 64;

  private final int probeBytes;
  private final int maxPayloadBytes;

  public FormatDetector(int probeBytes, int maxPayloadBytes) {
    if (probeBytes < 64) {
      throw new IllegalArgumentException("probeBytes must be >= 64, got " + probeBytes);
    }
    if (maxPayloadBytes < 1) {
      throw new IllegalArgumentException("maxPayloadBytes must be >= 1, got " + maxPayloadBytes);
    }
    this.probeBytes = probeBytes;
    this.maxPayloadBytes = maxPayloadBytes;
  }

  /** A detector with the default 64 KiB window and the journal's payload ceiling. */
  public static FormatDetector withDefaults() {
    return new FormatDetector(DEFAULT_PROBE_BYTES, JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES);
  }

  /** Maximum bytes any detection will read; the rewind budget a caller must provide. */
  public int probeBytes() {
    return probeBytes;
  }

  /**
   * Detects the format of a file or directory.
   *
   * @throws NoSuchFileException if the path does not exist
   */
  public FormatDetection detect(Path path) throws IOException {
    if (Files.isDirectory(path)) {
      return detectDirectory(path);
    }
    if (!Files.exists(path)) {
      throw new NoSuchFileException(path.toString());
    }
    if (!Files.isRegularFile(path)) {
      return FormatDetection.unknown(path + " is neither a regular file nor a directory", 0);
    }
    byte[] probe = new byte[probeBytes];
    int read;
    boolean atEnd;
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      read = readFully(channel, probe);
      atEnd = read < probe.length;
    }
    if (read == 0) {
      return FormatDetection.unknown(path + " is empty", 0);
    }
    return detectPrefix(probe, read, atEnd);
  }

  /**
   * Detects the format of a stream and returns it rewound.
   *
   * <p>The returned stream is the one to parse from: if the caller's stream did not support marking
   * it is wrapped in a {@link BufferedInputStream} sized to the probe window, and the wrapper holds
   * the bytes detection consumed. Reading from the original stream afterwards would skip them.
   */
  public StreamDetection detect(InputStream in) throws IOException {
    InputStream markable =
        in.markSupported() ? in : new BufferedInputStream(in, probeBytes + MAX_LENGTH_PREFIX_BYTES);
    markable.mark(probeBytes + MAX_LENGTH_PREFIX_BYTES);
    byte[] probe = markable.readNBytes(probeBytes);
    markable.reset();
    if (probe.length == 0) {
      return new StreamDetection(FormatDetection.unknown("stream is empty", 0), markable);
    }
    boolean atEnd = probe.length < probeBytes;
    return new StreamDetection(detectPrefix(probe, probe.length, atEnd), markable);
  }

  /**
   * Detects from an already-read prefix — the form every other entry point funnels into, and the
   * one to use when the bytes came from somewhere this class cannot open.
   *
   * @param prefix bytes from the start of the input
   * @param length how many of them are valid
   * @param atEndOfInput true when {@code prefix} is the whole input rather than a window onto more.
   *     It changes an indeterminate verdict into a definite one: a first frame that runs past the
   *     end of a complete file is not BEP, whereas one that runs past the end of the window is
   *     merely unverifiable
   */
  public FormatDetection detectPrefix(byte[] prefix, int length, boolean atEndOfInput) {
    if (length <= 0) {
      return FormatDetection.unknown("input is empty", 0);
    }
    int inspected = Math.min(length, prefix.length);

    Verdict binary = inspectBinary(prefix, inspected, atEndOfInput);
    Verdict json = inspectJson(prefix, inspected);

    if (binary.matches() && json.matches()) {
      return FormatDetection.unknown(
          "input reads as both binary and JSON BEP, so the format cannot be established: "
              + binary.reason()
              + "; "
              + json.reason(),
          inspected);
    }
    if (binary.matches()) {
      return FormatDetection.of(DetectedFormat.BEP_BINARY, binary.reason(), inspected);
    }
    if (json.matches()) {
      return FormatDetection.of(DetectedFormat.BEP_JSON, json.reason(), inspected);
    }
    return FormatDetection.unknown(
        "not binary BEP (" + binary.reason() + ") and not JSON BEP (" + json.reason() + ")",
        inspected);
  }

  // ---------------------------------------------------------------- directory

  private FormatDetection detectDirectory(Path directory) throws IOException {
    Path manifest = directory.resolve(SESSION_MANIFEST_NAME);
    if (!Files.isRegularFile(manifest)) {
      return FormatDetection.unknown(
          directory
              + " is a directory with no "
              + SESSION_MANIFEST_NAME
              + ", so it is not a managed session",
          0);
    }
    byte[] head = new byte[Math.min(probeBytes, 256)];
    int read;
    try (FileChannel channel = FileChannel.open(manifest, StandardOpenOption.READ)) {
      read = readFully(channel, head);
    }
    int start = skipBomAndWhitespace(head, read);
    if (start >= read || head[start] != '{') {
      return FormatDetection.unknown(
          directory
              + " contains a "
              + SESSION_MANIFEST_NAME
              + " that does not begin with a JSON object",
          read);
    }
    return FormatDetection.of(
        DetectedFormat.MANAGED_SESSION_DIR,
        "directory contains a " + SESSION_MANIFEST_NAME + " holding a JSON object",
        read);
  }

  // ------------------------------------------------------------------- binary

  private Verdict inspectBinary(byte[] data, int length, boolean atEndOfInput) {
    long[] declaredOut = new long[1];
    int prefixBytes = scanVarint(data, 0, length, declaredOut);
    if (prefixBytes == VARINT_NEED_MORE) {
      return Verdict.no("the input is shorter than a single length prefix");
    }
    if (prefixBytes == VARINT_MALFORMED) {
      return Verdict.no(
          "the first length prefix does not terminate within "
              + MAX_LENGTH_PREFIX_BYTES
              + " bytes");
    }
    long declared = declaredOut[0];
    if (declared == 0) {
      return Verdict.no(
          "the first length prefix declares an empty payload, "
              + "which is not evidence of anything");
    }
    if (declared > maxPayloadBytes) {
      return Verdict.no(
          "the first length prefix declares "
              + declared
              + " bytes, above the "
              + maxPayloadBytes
              + "-byte maximum");
    }
    long frameEnd = prefixBytes + declared;
    if (frameEnd > length) {
      return Verdict.no(
          atEndOfInput
              ? "the first frame declares "
                  + declared
                  + " payload bytes but the input holds "
                  + (length - prefixBytes)
              : "the first frame declares "
                  + declared
                  + " payload bytes, more than the "
                  + length
                  + "-byte detection window can verify");
    }

    int payloadLength = (int) declared;
    BuildEvent event;
    try {
      event = BuildEvent.parseFrom(ByteBuffer.wrap(data, prefixBytes, payloadLength).slice());
    } catch (InvalidProtocolBufferException e) {
      return Verdict.no(
          "the first "
              + payloadLength
              + " bytes after the length prefix do not parse as a BuildEvent");
    }

    String rejection = rejectImplausibleBuildEvent(event, payloadLength);
    if (rejection != null) {
      return Verdict.no("the first frame parses as protobuf but " + rejection);
    }

    String confirmation = confirmSecondFrame(data, length, (int) frameEnd, atEndOfInput);
    return Verdict.yes(
        "the first frame declares "
            + payloadLength
            + " bytes that parse as a BuildEvent with id "
            + event.getId().getIdCase()
            + " and payload "
            + event.getPayloadCase()
            + confirmation);
  }

  /**
   * Returns why {@code event} is not believable as the first event of a BEP stream, or null when it
   * is.
   *
   * <p>Arbitrary bytes can parse as any protobuf message, so "it parsed" is not a format test. What
   * is hard to hit by accident is structure: a nested {@code BuildEventId} message with a set oneof
   * case, and unknown fields that are both a minority of the bytes and numbered where a newer Bazel
   * would number them. Unknown fields are tolerated rather than banned on purpose — a future
   * Bazel's events must still be recognized (plan 21.5).
   */
  private static String rejectImplausibleBuildEvent(BuildEvent event, int payloadLength) {
    if (!event.hasId()) {
      return "carries no BuildEventId; every BEP event has one";
    }
    if (event.getId().getIdCase() == BuildEventId.IdCase.ID_NOT_SET) {
      return "carries a BuildEventId with no id kind set";
    }
    UnknownFieldSet unknown = event.getUnknownFields();
    if (!unknown.asMap().isEmpty()) {
      int highest = unknown.asMap().keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
      int ceiling = MAX_KNOWN_BUILD_EVENT_FIELD + UNKNOWN_FIELD_HEADROOM;
      if (highest > ceiling) {
        return "has an unrecognized field number "
            + highest
            + ", far above the "
            + MAX_KNOWN_BUILD_EVENT_FIELD
            + " fields this build knows";
      }
      if (unknown.getSerializedSize() * 2 > payloadLength) {
        return "is mostly unrecognized fields ("
            + unknown.getSerializedSize()
            + " of "
            + payloadLength
            + " bytes), which is what arbitrary data looks like";
      }
    }
    return null;
  }

  /**
   * Adds corroboration when a second frame is fully inside the window, and withholds the verdict
   * when the byte right after the first frame cannot be a length prefix at all. One valid frame
   * followed by garbage is not a BEP stream.
   */
  private String confirmSecondFrame(byte[] data, int length, int offset, boolean atEndOfInput) {
    if (offset == length) {
      return atEndOfInput ? " and is the only frame in the input" : "";
    }
    long[] declaredOut = new long[1];
    int prefixBytes = scanVarint(data, offset, length, declaredOut);
    if (prefixBytes < 0) {
      return "";
    }
    long declared = declaredOut[0];
    if (declared == 0 || declared > maxPayloadBytes) {
      return "";
    }
    long end = offset + prefixBytes + declared;
    if (end > length) {
      return "";
    }
    try {
      BuildEvent second =
          BuildEvent.parseFrom(ByteBuffer.wrap(data, offset + prefixBytes, (int) declared).slice());
      if (rejectImplausibleBuildEvent(second, (int) declared) == null) {
        return ", followed by a second frame that also parses as a BuildEvent";
      }
    } catch (InvalidProtocolBufferException e) {
      return "";
    }
    return "";
  }

  // --------------------------------------------------------------------- json

  /**
   * Bazel's {@code --build_event_json_file} writes protobuf-JSON objects, one per line in current
   * releases and pretty-printed concatenated objects in some older ones; both start with an object.
   * The check requires a brace followed by a key or an immediate close, which costs nothing and
   * keeps a lone {@code 0x7B} byte at the head of a binary file from reading as JSON.
   */
  private static Verdict inspectJson(byte[] data, int length) {
    int start = skipBomAndWhitespace(data, length);
    if (start >= length) {
      return Verdict.no("the input is only whitespace");
    }
    byte first = data[start];
    if (first == '[') {
      return Verdict.no(
          "the input begins a JSON array; Bazel writes a stream of objects, " + "not an array");
    }
    if (first != '{') {
      return Verdict.no(
          "the first non-whitespace byte is 0x" + String.format("%02x", first) + ", not '{'");
    }
    int next = skipBomAndWhitespace(data, length, start + 1);
    if (next >= length) {
      return Verdict.no("the input is a bare '{' with nothing after it");
    }
    if (data[next] != '"' && data[next] != '}') {
      return Verdict.no(
          "the '{' is followed by 0x"
              + String.format("%02x", data[next])
              + " rather than a quoted key");
    }
    return Verdict.yes("the first non-whitespace byte is '{' opening a JSON object");
  }

  // -------------------------------------------------------------------- utils

  private static int skipBomAndWhitespace(byte[] data, int length) {
    return skipBomAndWhitespace(data, length, 0);
  }

  private static int skipBomAndWhitespace(byte[] data, int length, int from) {
    int i = from;
    if (i == 0
        && length >= 3
        && (data[0] & 0xFF) == 0xEF
        && (data[1] & 0xFF) == 0xBB
        && (data[2] & 0xFF) == 0xBF) {
      i = 3;
    }
    while (i < length) {
      byte b = data[i];
      if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
        i++;
      } else {
        break;
      }
    }
    return i;
  }

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

  /** Reads until {@code target} is full or the channel ends; returns bytes read. */
  private static int readFully(FileChannel channel, byte[] target) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(target);
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer);
      if (read < 0) {
        break;
      }
      total += read;
    }
    return total;
  }

  /** One format test's answer plus the sentence explaining it. */
  private record Verdict(boolean matches, String reason) {
    static Verdict yes(String reason) {
      return new Verdict(true, reason);
    }

    static Verdict no(String reason) {
      return new Verdict(false, reason);
    }
  }
}
