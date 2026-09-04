package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.capture.file.detect.DetectedFormat;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The source-side half of a resume point: where in the <em>capture file</em> reading had reached,
 * alongside the journal-side {@link com.holtherndon.bazelviz.format.journal.ImportCheckpoint} that
 * says where in the journal writing had reached.
 *
 * <h2>Why this exists as a second file</h2>
 *
 * <p>The frozen checkpoint contract records a journal position and frame counts; it has no field
 * for a source offset, and it must not grow one — its format is shared with live BES capture, where
 * there is no source file at all. But a resumed <em>file</em> import needs to know where to carry
 * on reading, and the answer is not derivable from the journal: a JSON record's journaled bytes are
 * the object alone, while the source also holds the whitespace, newlines and separators between
 * objects. Recomputing the source offset from journaled payload lengths would therefore be wrong
 * for JSON and merely fragile for binary.
 *
 * <h2>Why a stale copy of this file is safe</h2>
 *
 * <p>This sidecar is written <em>after</em> the journal has been forced and after the journal
 * checkpoint has been replaced, so it can lag the journal but can never lead it. A crash in that
 * window leaves {@code framesWritten} lower than the number of frames actually in the journal, and
 * the importer resumes by reading from {@code sourceOffset} and discarding the first {@code
 * journalFrames - framesWritten} records it re-reads instead of journaling them again. The journal
 * therefore ends up byte-identical to an uninterrupted run, which is what makes offsets
 * reproducible across a resume.
 *
 * @param formatVersion version of this sidecar's own layout
 * @param sourceOffset absolute byte offset in the source where reading stopped; always a record
 *     boundary
 * @param framesWritten journal frames that had been written when this offset was recorded
 * @param format the detected format of the source, so a resume does not have to re-run detection
 *     and cannot silently change its mind about it
 * @param preservation how the source was preserved
 * @param originalPath the path the file was originally read from
 * @param sha256 digest of the byte stream that was parsed
 * @param byteSize size of that byte stream
 */
public record SourceCheckpoint(
    int formatVersion,
    long sourceOffset,
    long framesWritten,
    DetectedFormat format,
    SourcePreservation preservation,
    String originalPath,
    String sha256,
    long byteSize) {

  public static final int FORMAT_VERSION = 1;

  private static final String KEY_FORMAT_VERSION = "formatVersion";
  private static final String KEY_SOURCE_OFFSET = "sourceOffset";
  private static final String KEY_FRAMES_WRITTEN = "framesWritten";
  private static final String KEY_FORMAT = "format";
  private static final String KEY_PRESERVATION = "preservation";
  private static final String KEY_ORIGINAL_PATH = "originalPath";
  private static final String KEY_SHA256 = "sha256";
  private static final String KEY_BYTE_SIZE = "byteSize";

  public SourceCheckpoint {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(preservation, "preservation");
    Objects.requireNonNull(originalPath, "originalPath");
    Objects.requireNonNull(sha256, "sha256");
    if (formatVersion != FORMAT_VERSION) {
      throw new IllegalArgumentException(
          "source checkpoint format version " + formatVersion + " is not " + FORMAT_VERSION);
    }
    if (sourceOffset < 0) {
      throw new IllegalArgumentException("sourceOffset must be >= 0, got " + sourceOffset);
    }
    if (framesWritten < 0) {
      throw new IllegalArgumentException("framesWritten must be >= 0, got " + framesWritten);
    }
    if (byteSize < 0) {
      throw new IllegalArgumentException("byteSize must be >= 0, got " + byteSize);
    }
  }

  static SourceCheckpoint at(
      long sourceOffset, long framesWritten, DetectedFormat format, PreservedSource source) {
    return new SourceCheckpoint(
        FORMAT_VERSION,
        sourceOffset,
        framesWritten,
        format,
        source.preservation(),
        source.originalPath().toString(),
        source.sha256(),
        source.byteSize());
  }

  JsonObject toJson() {
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put(KEY_FORMAT_VERSION, JsonValue.of(formatVersion));
    members.put(KEY_SOURCE_OFFSET, JsonValue.of(sourceOffset));
    members.put(KEY_FRAMES_WRITTEN, JsonValue.of(framesWritten));
    members.put(KEY_FORMAT, JsonValue.of(format.name()));
    members.put(KEY_PRESERVATION, JsonValue.of(preservation.name()));
    members.put(KEY_ORIGINAL_PATH, JsonValue.of(originalPath));
    members.put(KEY_SHA256, JsonValue.of(sha256));
    members.put(KEY_BYTE_SIZE, JsonValue.of(byteSize));
    return new JsonObject(members);
  }

  /**
   * Reads the sidecar back.
   *
   * <p>Strict: a missing or mistyped field fails rather than being defaulted, because a repaired
   * resume point resumes from the wrong place, and doing that quietly is how an import loses
   * records without anyone noticing.
   */
  static SourceCheckpoint fromJson(JsonValue value) throws ImportFormatException {
    if (!(value instanceof JsonObject object)) {
      throw new ImportFormatException("source checkpoint is not a JSON object");
    }
    return new SourceCheckpoint(
        integer(object, KEY_FORMAT_VERSION),
        number(object, KEY_SOURCE_OFFSET),
        number(object, KEY_FRAMES_WRITTEN),
        enumValue(object, KEY_FORMAT, DetectedFormat.class),
        enumValue(object, KEY_PRESERVATION, SourcePreservation.class),
        text(object, KEY_ORIGINAL_PATH),
        text(object, KEY_SHA256),
        number(object, KEY_BYTE_SIZE));
  }

  private static int integer(JsonObject object, String key) throws ImportFormatException {
    JsonValue member = numberMember(object, key);
    try {
      return ((JsonValue.JsonNumber) member).asInt();
    } catch (RuntimeException malformed) {
      throw new ImportFormatException(
          "source checkpoint field \"" + key + "\" is not a 32-bit integer", malformed);
    }
  }

  private static long number(JsonObject object, String key) throws ImportFormatException {
    JsonValue member = numberMember(object, key);
    try {
      return ((JsonValue.JsonNumber) member).asLong();
    } catch (RuntimeException malformed) {
      throw new ImportFormatException(
          "source checkpoint field \"" + key + "\" is not a 64-bit integer", malformed);
    }
  }

  private static JsonValue numberMember(JsonObject object, String key)
      throws ImportFormatException {
    JsonValue member =
        object
            .member(key)
            .orElseThrow(
                () -> new ImportFormatException("source checkpoint is missing \"" + key + "\""));
    if (!(member instanceof JsonValue.JsonNumber number)) {
      throw new ImportFormatException("source checkpoint field \"" + key + "\" is not a number");
    }
    return number;
  }

  private static String text(JsonObject object, String key) throws ImportFormatException {
    JsonValue member =
        object
            .member(key)
            .orElseThrow(
                () -> new ImportFormatException("source checkpoint is missing \"" + key + "\""));
    if (!(member instanceof JsonValue.JsonString s)) {
      throw new ImportFormatException("source checkpoint field \"" + key + "\" is not a string");
    }
    return s.value();
  }

  private static <E extends Enum<E>> E enumValue(JsonObject object, String key, Class<E> type)
      throws ImportFormatException {
    String raw = text(object, key);
    try {
      return Enum.valueOf(type, raw);
    } catch (IllegalArgumentException unknown) {
      throw new ImportFormatException(
          "source checkpoint field \""
              + key
              + "\" holds '"
              + raw
              + "', which this build does not know; the session may have been written by a"
              + " newer version",
          unknown);
    }
  }
}
