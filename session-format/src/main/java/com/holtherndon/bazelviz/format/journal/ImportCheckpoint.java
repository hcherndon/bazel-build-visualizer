package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A resumable position in an import or capture: how far the raw journal has been written, and how
 * far normalization has consumed it (plan 5.2 step 10, plan 21.1).
 *
 * <p>The gap between {@link #framesWritten()} and {@link #eventsNormalized()} is the backlog that
 * recovery replays <em>from the journal</em>, never by re-reading the original source file — the
 * journal is the source of truth and the source file may have moved, grown, or gone.
 *
 * <p>The checkpoint is a hint, not an authority. It can legitimately be ahead of the bytes that
 * reached the disk, because the journal does not fsync per event; recovery treats a position past
 * the end of a segment as "rescan from a position we can prove", not as an error.
 *
 * @param formatVersion checkpoint format version, {@value #FORMAT_VERSION}
 * @param segmentIndex segment the next frame will be written to
 * @param segmentOffset byte offset within that segment
 * @param lastSequence sequence of the last journaled frame; empty means none has been journaled,
 *     which is not the same as sequence zero (plan 11.4)
 * @param framesWritten frames handed to the journal writer so far
 * @param eventsNormalized frames normalized into the database so far, always {@code <=
 *     framesWritten}
 * @param updatedAtMicros when this checkpoint was taken, epoch micros
 */
public record ImportCheckpoint(
    int formatVersion,
    int segmentIndex,
    long segmentOffset,
    OptionalLong lastSequence,
    long framesWritten,
    long eventsNormalized,
    long updatedAtMicros) {

  public static final int FORMAT_VERSION = 1;

  private static final String KEY_FORMAT_VERSION = "formatVersion";
  private static final String KEY_SEGMENT_INDEX = "segmentIndex";
  private static final String KEY_SEGMENT_OFFSET = "segmentOffset";
  private static final String KEY_LAST_SEQUENCE = "lastSequence";
  private static final String KEY_FRAMES_WRITTEN = "framesWritten";
  private static final String KEY_EVENTS_NORMALIZED = "eventsNormalized";
  private static final String KEY_UPDATED_AT_MICROS = "updatedAtMicros";

  public ImportCheckpoint {
    Objects.requireNonNull(lastSequence, "lastSequence");
    if (formatVersion != FORMAT_VERSION) {
      throw new IllegalArgumentException(
          "checkpoint format version " + formatVersion + " is not " + FORMAT_VERSION);
    }
    if (segmentIndex < 0) {
      throw new IllegalArgumentException("segmentIndex must be >= 0, got " + segmentIndex);
    }
    if (segmentOffset < JournalFormat.SEGMENT_HEADER_BYTES) {
      throw new IllegalArgumentException(
          "segmentOffset must be at least the segment header ("
              + JournalFormat.SEGMENT_HEADER_BYTES
              + "), got "
              + segmentOffset);
    }
    if (framesWritten < 0) {
      throw new IllegalArgumentException("framesWritten must be >= 0, got " + framesWritten);
    }
    if (eventsNormalized < 0) {
      throw new IllegalArgumentException("eventsNormalized must be >= 0, got " + eventsNormalized);
    }
    if (eventsNormalized > framesWritten) {
      throw new IllegalArgumentException(
          "eventsNormalized ("
              + eventsNormalized
              + ") cannot exceed framesWritten ("
              + framesWritten
              + ")");
    }
    if (updatedAtMicros < 0) {
      throw new IllegalArgumentException("updatedAtMicros must be >= 0, got " + updatedAtMicros);
    }
  }

  public static ImportCheckpoint at(
      JournalPosition position,
      OptionalLong lastSequence,
      long framesWritten,
      long eventsNormalized,
      long updatedAtMicros) {
    Objects.requireNonNull(position, "position");
    return new ImportCheckpoint(
        FORMAT_VERSION,
        position.segmentIndex(),
        position.byteOffset(),
        lastSequence,
        framesWritten,
        eventsNormalized,
        updatedAtMicros);
  }

  /** The journal position this checkpoint records. */
  public JournalPosition position() {
    return new JournalPosition(segmentIndex, segmentOffset);
  }

  /** Frames journaled but not yet normalized. Recovery replays exactly these. */
  public long normalizationBacklog() {
    return framesWritten - eventsNormalized;
  }

  /**
   * Serializes to the JSON in the Phase 1 contract. An absent {@code lastSequence} is written as
   * {@code null} rather than {@code 0}, because zero is a legal sequence number and "nothing
   * journaled yet" is not zero (plan 11.4).
   */
  public String toJson() {
    StringBuilder json = new StringBuilder(256);
    json.append("{\n");
    appendNumber(json, KEY_FORMAT_VERSION, formatVersion, true);
    appendNumber(json, KEY_SEGMENT_INDEX, segmentIndex, true);
    appendNumber(json, KEY_SEGMENT_OFFSET, segmentOffset, true);
    json.append("  \"")
        .append(KEY_LAST_SEQUENCE)
        .append("\": ")
        .append(lastSequence.isPresent() ? Long.toString(lastSequence.getAsLong()) : "null")
        .append(",\n");
    appendNumber(json, KEY_FRAMES_WRITTEN, framesWritten, true);
    appendNumber(json, KEY_EVENTS_NORMALIZED, eventsNormalized, true);
    appendNumber(json, KEY_UPDATED_AT_MICROS, updatedAtMicros, false);
    json.append("}\n");
    return json.toString();
  }

  /**
   * Parses the contract JSON. Strict on purpose: a missing key, a value that is not a number or
   * {@code null}, trailing garbage, or a violated invariant all fail rather than being repaired,
   * because a repaired checkpoint would silently resume from the wrong place. Unknown keys are
   * ignored so a newer build's checkpoint stays readable.
   */
  public static ImportCheckpoint fromJson(String text) throws CheckpointFormatException {
    Objects.requireNonNull(text, "text");
    Map<String, Long> fields = FlatJson.parseObject(text);
    try {
      return new ImportCheckpoint(
          requiredInt(fields, KEY_FORMAT_VERSION),
          requiredInt(fields, KEY_SEGMENT_INDEX),
          required(fields, KEY_SEGMENT_OFFSET),
          optional(fields, KEY_LAST_SEQUENCE),
          required(fields, KEY_FRAMES_WRITTEN),
          required(fields, KEY_EVENTS_NORMALIZED),
          required(fields, KEY_UPDATED_AT_MICROS));
    } catch (IllegalArgumentException invalid) {
      throw new CheckpointFormatException(
          "checkpoint is internally inconsistent: " + invalid.getMessage(), invalid);
    }
  }

  private static void appendNumber(StringBuilder json, String key, long value, boolean comma) {
    json.append("  \"").append(key).append("\": ").append(value);
    json.append(comma ? ",\n" : "\n");
  }

  private static long required(Map<String, Long> fields, String key)
      throws CheckpointFormatException {
    if (!fields.containsKey(key)) {
      throw new CheckpointFormatException("checkpoint is missing \"" + key + "\"");
    }
    Long value = fields.get(key);
    if (value == null) {
      throw new CheckpointFormatException("checkpoint field \"" + key + "\" is null");
    }
    return value;
  }

  /**
   * Rejects rather than narrows: a value that does not fit an int is corruption, not a big number.
   */
  private static int requiredInt(Map<String, Long> fields, String key)
      throws CheckpointFormatException {
    long value = required(fields, key);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new CheckpointFormatException(
          "checkpoint field \"" + key + "\" is out of int range: " + value);
    }
    return (int) value;
  }

  private static OptionalLong optional(Map<String, Long> fields, String key)
      throws CheckpointFormatException {
    if (!fields.containsKey(key)) {
      throw new CheckpointFormatException("checkpoint is missing \"" + key + "\"");
    }
    Long value = fields.get(key);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /**
   * A deliberately tiny JSON reader for one flat object of integer or null values. The checkpoint
   * is the only JSON this module writes and reads, and it is written by this same class; pulling in
   * a JSON dependency to parse seven integers would be a larger risk than these forty lines.
   * Anything richer than {@code {"key": 123, "key": null}} is rejected, which is exactly the
   * strictness a corruption detector needs.
   */
  private static final class FlatJson {

    private final String text;
    private int index;

    private FlatJson(String text) {
      this.text = text;
    }

    static Map<String, Long> parseObject(String text) throws CheckpointFormatException {
      FlatJson parser = new FlatJson(text);
      Map<String, Long> fields = new LinkedHashMap<>();
      parser.skipWhitespace();
      parser.expect('{');
      parser.skipWhitespace();
      if (parser.peek() == '}') {
        parser.index++;
      } else {
        while (true) {
          parser.skipWhitespace();
          String key = parser.readString();
          parser.skipWhitespace();
          parser.expect(':');
          parser.skipWhitespace();
          Long value = parser.readNumberOrNull();
          if (fields.containsKey(key)) {
            throw parser.fail("duplicate key \"" + key + "\"");
          }
          fields.put(key, value);
          parser.skipWhitespace();
          char next = parser.read();
          if (next == '}') {
            break;
          }
          if (next != ',') {
            throw parser.fail("expected ',' or '}' but found '" + next + "'");
          }
        }
      }
      parser.skipWhitespace();
      if (parser.index != text.length()) {
        throw parser.fail("trailing content after the JSON object");
      }
      return fields;
    }

    private void skipWhitespace() {
      while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
        index++;
      }
    }

    private char peek() throws CheckpointFormatException {
      if (index >= text.length()) {
        throw fail("unexpected end of checkpoint");
      }
      return text.charAt(index);
    }

    private char read() throws CheckpointFormatException {
      char c = peek();
      index++;
      return c;
    }

    private void expect(char expected) throws CheckpointFormatException {
      char actual = read();
      if (actual != expected) {
        throw fail("expected '" + expected + "' but found '" + actual + "'");
      }
    }

    private String readString() throws CheckpointFormatException {
      expect('"');
      StringBuilder value = new StringBuilder();
      while (true) {
        char c = read();
        if (c == '"') {
          return value.toString();
        }
        if (c == '\\' || c < 0x20) {
          // Checkpoint keys are plain ASCII identifiers. Escapes are
          // not produced by toJson(), so seeing one means this file
          // is not a checkpoint this build wrote.
          throw fail("unsupported character in checkpoint key");
        }
        value.append(c);
      }
    }

    private Long readNumberOrNull() throws CheckpointFormatException {
      if (text.startsWith("null", index)) {
        index += 4;
        return null;
      }
      int start = index;
      if (peek() == '-') {
        index++;
      }
      while (index < text.length() && Character.isDigit(text.charAt(index))) {
        index++;
      }
      if (index == start || (index == start + 1 && text.charAt(start) == '-')) {
        throw fail("expected a number");
      }
      try {
        return Long.parseLong(text, start, index, 10);
      } catch (NumberFormatException tooLarge) {
        throw fail("number out of range: " + text.substring(start, index));
      }
    }

    private CheckpointFormatException fail(String message) {
      return new CheckpointFormatException(
          "malformed checkpoint at character " + index + ": " + message);
    }
  }
}
