package com.holtherndon.bazelviz.format.portable;

import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The archive's own table of contents, written as {@code archive.json} at the root.
 *
 * <h2>Why the Zip's central directory is not enough</h2>
 *
 * <p>A Zip entry carries a CRC-32, which detects accidental corruption and nothing else: it is
 * trivial to construct different bytes with the same CRC. Plan 22.4 requires validating checksums
 * on an archive that is explicitly untrusted, so each entry also carries a SHA-256 here, and the
 * reader checks the bytes it actually decompressed against this list rather than against anything
 * the Zip structure claims.
 *
 * <p>It also records what a reader cannot otherwise know: whether the archive was redacted, and
 * whether the raw sources are in it. Both change what the session means — a redacted archive cannot
 * be re-derived from raw bytes, because the raw bytes are the unredacted ones and are deliberately
 * absent.
 *
 * @param formatVersion the archive format, so a future reader can refuse rather than misread
 * @param redacted true when the contents went through the redaction engine
 * @param includesRawSources false for a redacted archive, always: the raw journal holds the
 *     original event bytes, secrets included, so exporting it alongside a redacted database would
 *     undo the redaction
 * @param entries every entry except this file, in the order they were written
 */
public record BvizIndex(
    int formatVersion,
    String appVersion,
    String sessionId,
    long createdMicros,
    boolean redacted,
    boolean includesRawSources,
    String note,
    List<Entry> entries) {

  /** The only format version this application writes or reads. */
  public static final int FORMAT_VERSION = 1;

  /** The archive-root file this is stored as. */
  public static final String FILE_NAME = "archive.json";

  public BvizIndex {
    Objects.requireNonNull(appVersion, "appVersion");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(note, "note");
    entries = List.copyOf(entries);
  }

  /**
   * One file in the archive.
   *
   * @param path archive-relative, always with {@code /} separators
   * @param bytes the decompressed length
   * @param sha256 lower-case hex of the decompressed content
   */
  public record Entry(String path, long bytes, String sha256) {

    public Entry {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(sha256, "sha256");
      if (bytes < 0) {
        throw new IllegalArgumentException("an entry cannot have negative length: " + path);
      }
    }
  }

  /** The entry for one path, or empty. */
  public Optional<Entry> entry(String path) {
    return entries.stream().filter(entry -> entry.path().equals(path)).findFirst();
  }

  public String toJson() {
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put("formatVersion", JsonValue.JsonNumber.of(formatVersion));
    members.put("appVersion", new JsonValue.JsonString(appVersion));
    members.put("sessionId", new JsonValue.JsonString(sessionId));
    members.put("createdMicros", JsonValue.JsonNumber.of(createdMicros));
    members.put("redacted", redacted ? JsonValue.JsonBool.TRUE : JsonValue.JsonBool.FALSE);
    members.put(
        "includesRawSources",
        includesRawSources ? JsonValue.JsonBool.TRUE : JsonValue.JsonBool.FALSE);
    members.put("note", new JsonValue.JsonString(note));
    List<JsonValue> list = new ArrayList<>(entries.size());
    for (Entry entry : entries) {
      Map<String, JsonValue> fields = new LinkedHashMap<>();
      fields.put("path", new JsonValue.JsonString(entry.path()));
      fields.put("bytes", JsonValue.JsonNumber.of(entry.bytes()));
      fields.put("sha256", new JsonValue.JsonString(entry.sha256()));
      list.add(new JsonValue.JsonObject(fields));
    }
    members.put("entries", JsonValue.JsonArray.of(list));
    return JsonWriter.writePretty(new JsonValue.JsonObject(members));
  }

  /** Parses an index, refusing anything it cannot read rather than guessing. */
  public static BvizIndex fromJson(String json) throws BvizFormatException {
    JsonValue parsed;
    try {
      parsed = JsonReader.parse(json);
    } catch (RuntimeException malformed) {
      throw new BvizFormatException(
          FILE_NAME + " is not valid JSON: " + malformed.getMessage(), malformed);
    }
    if (!(parsed instanceof JsonValue.JsonObject object)) {
      throw new BvizFormatException(FILE_NAME + " must be a JSON object");
    }
    int version = (int) number(object, "formatVersion");
    if (version != FORMAT_VERSION) {
      throw new BvizFormatException(
          "this archive declares format version "
              + version
              + " and this application"
              + " reads version "
              + FORMAT_VERSION
              + ". Opening it would mean guessing at a layout that has changed.");
    }
    List<Entry> entries = new ArrayList<>();
    JsonValue list =
        object
            .member("entries")
            .orElseThrow(() -> new BvizFormatException(FILE_NAME + " lists no entries"));
    if (!(list instanceof JsonValue.JsonArray array)) {
      throw new BvizFormatException(FILE_NAME + ": entries must be an array");
    }
    for (JsonValue element : array.elements()) {
      if (!(element instanceof JsonValue.JsonObject entry)) {
        throw new BvizFormatException(FILE_NAME + ": every entry must be an object");
      }
      entries.add(new Entry(text(entry, "path"), number(entry, "bytes"), text(entry, "sha256")));
    }
    return new BvizIndex(
        version,
        text(object, "appVersion"),
        text(object, "sessionId"),
        number(object, "createdMicros"),
        bool(object, "redacted"),
        bool(object, "includesRawSources"),
        object
            .member("note")
            .filter(JsonValue.JsonString.class::isInstance)
            .map(value -> ((JsonValue.JsonString) value).value())
            .orElse(""),
        entries);
  }

  private static String text(JsonValue.JsonObject object, String key) throws BvizFormatException {
    return object
        .member(key)
        .filter(JsonValue.JsonString.class::isInstance)
        .map(value -> ((JsonValue.JsonString) value).value())
        .orElseThrow(
            () -> new BvizFormatException(FILE_NAME + " is missing the string \"" + key + "\""));
  }

  private static long number(JsonValue.JsonObject object, String key) throws BvizFormatException {
    return object
        .member(key)
        .filter(JsonValue.JsonNumber.class::isInstance)
        .map(value -> ((JsonValue.JsonNumber) value).asLong())
        .orElseThrow(
            () -> new BvizFormatException(FILE_NAME + " is missing the number \"" + key + "\""));
  }

  private static boolean bool(JsonValue.JsonObject object, String key) throws BvizFormatException {
    return object
        .member(key)
        .filter(JsonValue.JsonBool.class::isInstance)
        .map(value -> ((JsonValue.JsonBool) value).value())
        .orElseThrow(
            () -> new BvizFormatException(FILE_NAME + " is missing the boolean \"" + key + "\""));
  }
}
