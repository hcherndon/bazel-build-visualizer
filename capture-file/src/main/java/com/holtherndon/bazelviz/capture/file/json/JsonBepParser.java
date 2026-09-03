package com.holtherndon.bazelviz.capture.file.json;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.capture.file.json.JsonParseDiagnostic.Code;
import com.holtherndon.bazelviz.capture.file.json.JsonParseDiagnostic.Severity;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Streaming reader for a Bazel {@code --build_event_json_file} stream.
 *
 * <p>The file is never read into memory (Phase 1 exit criterion). Bytes flow through a fixed read
 * buffer; a record is accumulated only for as long as it takes to hand it to the listener, and the
 * accumulator itself is capped by {@link JsonBepParserOptions#maxRecordBytes()}. Peak footprint is
 * therefore the read buffer plus at most one record, whatever the file's size, and both numbers
 * come back in {@link JsonBepParseResult} so a test can assert it.
 *
 * <p>Record boundaries come from {@link JsonRecordScanner}, which tracks JSON structural depth.
 * That is deliberate: the one-object-per-line layout Bazel usually emits is not guaranteed, and
 * pretty-printed output from other versions has no separator at all. See that class for why brace
 * counting alone is not enough.
 *
 * <p>What the parser will not do:
 *
 * <ul>
 *   <li>Skip anything silently. An oversized record is reported with its exact offset and true
 *       length before parsing resumes at the next boundary; nothing is dropped without a diagnostic
 *       (plan 21.3).
 *   <li>Allocate whatever the file asks for. A record over the limit stops being buffered at the
 *       limit, so a corrupt or hostile file cannot make the parser allocate its way out of memory.
 *   <li>Confuse truncation with corruption. A final object that simply ends early is {@link
 *       Completeness#TRUNCATED} — the normal shape of a cancelled or still-running build — reported
 *       with the offset a tailing reader resumes from, and every record before it is delivered.
 *   <li>Lose what it cannot understand. Raw bytes are preserved verbatim regardless of decode
 *       outcome (ADR-004), and a record carrying fields these protos do not know is delivered and
 *       flagged, not discarded (plan 21.5).
 * </ul>
 *
 * <p>Instances are stateless and reusable; each {@code parse} call runs independently. Parsing must
 * not run on the Swing EDT.
 */
public final class JsonBepParser {

  private static final int INITIAL_RECORD_BUFFER_BYTES = 8 * 1024;

  private final JsonBepParserOptions options;
  private final JsonBuildEventDecoder decoder;

  public JsonBepParser() {
    this(JsonBepParserOptions.defaults());
  }

  public JsonBepParser(JsonBepParserOptions options) {
    this.options = Objects.requireNonNull(options, "options");
    this.decoder = options.decodeEvents() ? new JsonBuildEventDecoder() : null;
  }

  public JsonBepParserOptions options() {
    return options;
  }

  /** Parses {@code source}, streaming records to {@code listener}. */
  public JsonBepParseResult parse(Path source, JsonBepListener listener) throws IOException {
    Objects.requireNonNull(source, "source");
    try (InputStream in = Files.newInputStream(source)) {
      return parse(in, listener);
    }
  }

  /**
   * Parses {@code source}, streaming records to {@code listener}. The stream is read sequentially
   * and is not closed by this method.
   */
  public JsonBepParseResult parse(InputStream source, JsonBepListener listener) throws IOException {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(listener, "listener");
    return new Run(source, listener).execute();
  }

  /** One pass over one stream. All the mutable state lives here, not on the parser. */
  private final class Run {

    private final InputStream source;
    private final JsonBepListener listener;
    private final JsonRecordScanner scanner = new JsonRecordScanner();

    private final byte[] readBuffer = new byte[options.readBufferBytes()];

    /** Absolute source offset of {@code readBuffer[0]}. */
    private long bufferBase;

    private int fill;
    private int pos;

    private byte[] accumulator =
        new byte[Math.min(INITIAL_RECORD_BUFFER_BYTES, options.maxRecordBytes())];
    private int accumulated;
    private int peakAccumulatorBytes = accumulator.length;

    /** True once the open record has outgrown the limit; its bytes stop being kept. */
    private boolean overflowed;

    private long recordStartOffset = -1;

    /** True byte length of the open record so far, counted even past the limit. */
    private long recordLength;

    private long bytesRead;
    private long delimitedRecords;
    private long deliveredRecords;
    private long oversizedRecords;
    private long failedDecodes;
    private long unknownFieldRecords;
    private boolean commaSeparatorReported;
    private boolean malformedTopLevel;

    Run(InputStream source, JsonBepListener listener) {
      this.source = source;
      this.listener = listener;
    }

    JsonBepParseResult execute() throws IOException {
      consumeByteOrderMark();

      while (true) {
        if (pos == fill) {
          if (!refill()) {
            break;
          }
        }
        if (scanner.isInRecord()) {
          continueRecord();
        } else if (!startRecord()) {
          break;
        }
      }

      return finish();
    }

    /**
     * Reads the first three bytes separately so a UTF-8 byte order mark can be recognized and
     * reported before it is mistaken for junk between records. Everything that is not a BOM is
     * pushed back into the normal read buffer, so offsets stay absolute from byte zero either way.
     */
    private void consumeByteOrderMark() throws IOException {
      byte[] head = source.readNBytes(3);
      bytesRead += head.length;
      int start = 0;
      if (head.length == 3
          && head[0] == (byte) 0xEF
          && head[1] == (byte) 0xBB
          && head[2] == (byte) 0xBF) {
        start = 3;
        emit(
            JsonParseDiagnostic.at(
                Severity.INFO,
                Code.BYTE_ORDER_MARK_SKIPPED,
                0,
                "source begins with a UTF-8 byte order mark; 3 bytes skipped before the "
                    + "first record"));
      }
      System.arraycopy(head, start, readBuffer, 0, head.length - start);
      fill = head.length - start;
      pos = 0;
      bufferBase = start;
    }

    /**
     * @return false at end of stream
     */
    private boolean refill() throws IOException {
      bufferBase += fill;
      pos = 0;
      // readNBytes rather than read: it returns 0 only at end of stream,
      // so a short read can never be mistaken for EOF.
      fill = source.readNBytes(readBuffer, 0, readBuffer.length);
      if (fill == 0) {
        return false;
      }
      bytesRead += fill;
      return true;
    }

    /**
     * @return false when a structurally invalid top-level byte ends the parse
     */
    private boolean startRecord() throws IOException {
      int index = scanner.findRecordStart(readBuffer, pos, fill);
      if (index == JsonRecordScanner.RANGE_CONSUMED) {
        pos = fill;
        return true;
      }
      long offset = bufferBase + index;
      if (scanner.sawSeparatorComma() && !commaSeparatorReported) {
        commaSeparatorReported = true;
        emit(
            JsonParseDiagnostic.at(
                Severity.INFO,
                Code.COMMA_SEPARATED_RECORDS,
                offset,
                "records are separated by commas rather than concatenated; the source is "
                    + "probably an unwrapped JSON array. Commas are tolerated and the "
                    + "objects between them are read normally"));
      }
      if (readBuffer[index] != '{') {
        malformedTopLevel = true;
        emit(
            JsonParseDiagnostic.at(
                Severity.ERROR,
                Code.MALFORMED_TOP_LEVEL,
                offset,
                "byte 0x%02X at offset %d is not the start of a JSON object; parsing "
                        .formatted(readBuffer[index] & 0xFF, offset)
                    + "stopped here because no reliable next record boundary exists"));
        pos = index;
        return false;
      }
      recordStartOffset = offset;
      recordLength = 0;
      accumulated = 0;
      overflowed = false;
      scanner.beginRecord();
      append(index, index + 1);
      pos = index + 1;
      return true;
    }

    private void continueRecord() throws IOException {
      int end = scanner.scanBody(readBuffer, pos, fill);
      int consumeTo = end == JsonRecordScanner.RANGE_CONSUMED ? fill : end;
      append(pos, consumeTo);
      pos = consumeTo;
      if (end != JsonRecordScanner.RANGE_CONSUMED) {
        completeRecord();
      }
    }

    /**
     * Accumulates {@code readBuffer[from, to)} into the open record, or declines to once the record
     * has outgrown the configured limit. The true length keeps being counted either way, because
     * the user needs the real number to choose a bigger limit.
     */
    private void append(int from, int to) {
      int length = to - from;
      if (length == 0) {
        return;
      }
      long grown = recordLength + length;
      if (!overflowed) {
        if (grown > options.maxRecordBytes()) {
          overflowed = true;
          // Release the partial copy immediately: this record will
          // not be delivered, so holding its bytes buys nothing.
          accumulator = new byte[Math.min(INITIAL_RECORD_BUFFER_BYTES, options.maxRecordBytes())];
          accumulated = 0;
        } else {
          ensureCapacity((int) grown);
          System.arraycopy(readBuffer, from, accumulator, accumulated, length);
          accumulated = (int) grown;
        }
      }
      recordLength = grown;
    }

    private void ensureCapacity(int needed) {
      if (accumulator.length >= needed) {
        return;
      }
      int max = options.maxRecordBytes();
      int capacity = accumulator.length;
      while (capacity < needed) {
        capacity = capacity > max / 2 ? max : capacity * 2;
      }
      accumulator = Arrays.copyOf(accumulator, capacity);
      peakAccumulatorBytes = Math.max(peakAccumulatorBytes, capacity);
    }

    private void completeRecord() throws IOException {
      long ordinal = delimitedRecords++;
      if (overflowed) {
        oversizedRecords++;
        emit(
            JsonParseDiagnostic.forRecord(
                Severity.ERROR,
                Code.RECORD_TOO_LARGE,
                recordStartOffset,
                recordLength,
                ("JSON record %d at byte offset %d is %d bytes, over the configured "
                        + "maximum of %d. Its bytes were not retained; parsing resumed at "
                        + "the next record boundary. Raise the per-record limit to import "
                        + "it.")
                    .formatted(
                        ordinal, recordStartOffset, recordLength, options.maxRecordBytes())));
        return;
      }

      byte[] raw = Arrays.copyOf(accumulator, accumulated);
      // The delivered copy lives alongside the accumulator, so both are
      // resident at once. Counting only the accumulator understated the
      // real peak by a whole record — and this figure is what the
      // bounded-memory claim is measured against, so it has to be the
      // true high-water mark rather than the convenient half of it.
      peakAccumulatorBytes = Math.max(peakAccumulatorBytes, accumulator.length + raw.length);
      DecodeStatus status = DecodeStatus.NOT_ATTEMPTED;
      BuildEvent event = null;
      String detail = null;

      if (decoder != null) {
        JsonDecodeResult decoded = decoder.decode(raw);
        status = decoded.status();
        event = decoded.event();
        detail = decoded.message();
        switch (status) {
          case FAILED -> {
            failedDecodes++;
            emit(
                JsonParseDiagnostic.forRecord(
                    Severity.ERROR,
                    Code.DECODE_FAILED,
                    recordStartOffset,
                    raw.length,
                    ("JSON record %d at byte offset %d (%d bytes) is not a decodable "
                            + "BuildEvent: %s. Its raw bytes are preserved so a later "
                            + "version can reinterpret it.")
                        .formatted(ordinal, recordStartOffset, raw.length, detail)));
          }
          case UNKNOWN_FIELDS -> {
            unknownFieldRecords++;
            emit(
                JsonParseDiagnostic.forRecord(
                    Severity.WARNING,
                    Code.UNKNOWN_FIELDS,
                    recordStartOffset,
                    raw.length,
                    ("JSON record %d at byte offset %d carries fields this build's "
                            + "protos do not define (%s). The event was kept without "
                            + "them and its raw bytes are preserved in full.")
                        .formatted(ordinal, recordStartOffset, detail)));
          }
          default -> {
            /* OK and NOT_ATTEMPTED need no diagnostic. */
          }
        }
      }

      listener.onRecord(
          new JsonBepRecord(ordinal, recordStartOffset, raw.length, raw, status, event, detail));
      deliveredRecords++;
    }

    private JsonBepParseResult finish() throws IOException {
      Completeness completeness;
      OptionalLong truncatedOffset = OptionalLong.empty();
      OptionalLong truncatedBytes = OptionalLong.empty();

      if (scanner.isInRecord()) {
        // A clean short tail: the object simply stops. Distinct from
        // corruption (plan 21.3) and everything before it stands.
        completeness = Completeness.TRUNCATED;
        truncatedOffset = OptionalLong.of(recordStartOffset);
        truncatedBytes = OptionalLong.of(recordLength);
        emit(
            JsonParseDiagnostic.forRecord(
                Severity.WARNING,
                Code.TRUNCATED_TAIL,
                recordStartOffset,
                recordLength,
                ("the source ends inside the JSON object that begins at byte offset %d; "
                        + "%d bytes of it are present, %s. All %d complete records before "
                        + "it were read.")
                    .formatted(
                        recordStartOffset,
                        recordLength,
                        scanner.isInString()
                            ? "cut inside a string literal"
                            : "still " + scanner.depth() + " level(s) of " + "nesting open",
                        deliveredRecords)));
      } else if (malformedTopLevel) {
        completeness = Completeness.CORRUPT_PARTIAL;
      } else {
        completeness = Completeness.COMPLETE;
        if (bytesRead == 0) {
          emit(
              JsonParseDiagnostic.at(
                  Severity.INFO,
                  Code.EMPTY_INPUT,
                  0,
                  "the source is empty; it contains no build events"));
        }
      }

      return new JsonBepParseResult(
          deliveredRecords,
          oversizedRecords,
          failedDecodes,
          unknownFieldRecords,
          bytesRead,
          completeness,
          truncatedOffset,
          truncatedBytes,
          peakAccumulatorBytes,
          readBuffer.length);
    }

    private void emit(JsonParseDiagnostic diagnostic) throws IOException {
      listener.onDiagnostic(diagnostic);
    }
  }
}
