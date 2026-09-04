package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.SessionManifest.AuxiliaryCommand;
import com.holtherndon.bazelviz.format.session.SessionManifest.CaptureSourceEntry;
import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import com.holtherndon.bazelviz.format.session.json.JsonException;
import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonBool;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Reads and writes {@code manifest.json}.
 *
 * <p>Three behaviours here are load-bearing rather than incidental.
 *
 * <p><strong>Absent stays absent.</strong> An empty {@code Optional} omits its key entirely; it is
 * never written as {@code null}, {@code 0}, or {@code ""}. A key present with an explicit JSON
 * {@code null} reads back as absent, so a manifest hand-edited into that shape still round-trips
 * honestly.
 *
 * <p><strong>Unknown members are preserved.</strong> Any top-level member this build does not model
 * is collected into {@link SessionManifest#unknownFields()} and re-emitted after the known ones.
 * The same happens per capture-source entry. A session opened, state-changed and rewritten by an
 * older build therefore keeps everything a newer build put there (plan 21.5).
 *
 * <p><strong>Writes are atomic.</strong> {@link #write} goes through {@link AtomicFiles}, so a
 * crash mid-rewrite leaves the previous manifest intact rather than a truncated one.
 */
public final class SessionManifestCodec {

  // Canonical write order. Also the set of members that are *not* unknown.
  private static final String KEY_FORMAT_VERSION = ManifestMigrations.FORMAT_VERSION_KEY;
  private static final String KEY_APP_VERSION = "appVersion";
  private static final String KEY_SESSION_ID = "sessionId";
  private static final String KEY_CREATED_MICROS = "createdMicros";
  private static final String KEY_FINALIZED_MICROS = "finalizedMicros";
  private static final String KEY_STATE = "state";
  private static final String KEY_WORKING_DIRECTORY = "workingDirectory";
  private static final String KEY_WORKSPACE_ROOT = "workspaceRoot";
  private static final String KEY_EXECUTION_LOCATION = "executionLocation";
  private static final String KEY_BAZEL_EXECUTABLE = "bazelExecutable";
  private static final String KEY_BAZEL_VERSION = "bazelVersion";
  private static final String KEY_ORIGINAL_COMMAND = "originalCommand";
  private static final String KEY_EFFECTIVE_COMMAND = "effectiveCommand";
  private static final String KEY_ENVIRONMENT_CAPTURE_POLICY = "environmentCapturePolicy";
  private static final String KEY_CAPTURE_PRESET = "capturePreset";
  private static final String KEY_INJECTED_FLAGS = "injectedFlags";
  private static final String KEY_AUXILIARY_COMMANDS = "auxiliaryCommands";
  private static final String KEY_SOURCES = "sources";
  private static final String KEY_REDACTION_STATE = "redactionState";
  private static final String KEY_EVENT_COUNT = "eventCount";
  private static final String KEY_ACTION_COUNT = "actionCount";
  private static final String KEY_INDEX_VERSIONS = "indexVersions";
  private static final String KEY_SCHEMA_VERSION = "schemaVersion";
  private static final String KEY_WARNINGS = "warnings";
  private static final String KEY_CONTAINS_ABSOLUTE_PATHS = "containsAbsolutePaths";
  private static final String KEY_CONTAINS_ENVIRONMENT_VALUES = "containsEnvironmentValues";

  private static final Set<String> KNOWN_KEYS =
      new LinkedHashSet<>(
          Arrays.asList(
              KEY_FORMAT_VERSION,
              KEY_APP_VERSION,
              KEY_SESSION_ID,
              KEY_CREATED_MICROS,
              KEY_FINALIZED_MICROS,
              KEY_STATE,
              KEY_WORKING_DIRECTORY,
              KEY_WORKSPACE_ROOT,
              KEY_EXECUTION_LOCATION,
              KEY_BAZEL_EXECUTABLE,
              KEY_BAZEL_VERSION,
              KEY_ORIGINAL_COMMAND,
              KEY_EFFECTIVE_COMMAND,
              KEY_ENVIRONMENT_CAPTURE_POLICY,
              KEY_CAPTURE_PRESET,
              KEY_INJECTED_FLAGS,
              KEY_AUXILIARY_COMMANDS,
              KEY_SOURCES,
              KEY_REDACTION_STATE,
              KEY_EVENT_COUNT,
              KEY_ACTION_COUNT,
              KEY_INDEX_VERSIONS,
              KEY_SCHEMA_VERSION,
              KEY_WARNINGS,
              KEY_CONTAINS_ABSOLUTE_PATHS,
              KEY_CONTAINS_ENVIRONMENT_VALUES));

  private static final String SOURCE_KEY_KIND = "kind";
  private static final String SOURCE_KEY_PATH = "path";
  private static final String SOURCE_KEY_SHA256 = "sha256";
  private static final String SOURCE_KEY_BYTE_SIZE = "byteSize";
  private static final String SOURCE_KEY_COMPLETENESS = "completeness";
  private static final String SOURCE_KEY_NOTE = "note";

  private static final Set<String> KNOWN_SOURCE_KEYS =
      Set.of(
          SOURCE_KEY_KIND,
          SOURCE_KEY_PATH,
          SOURCE_KEY_SHA256,
          SOURCE_KEY_BYTE_SIZE,
          SOURCE_KEY_COMPLETENESS,
          SOURCE_KEY_NOTE);

  private static final String AUX_KEY_LABEL = "label";
  private static final String AUX_KEY_ARGV = "argv";

  private static final String LOCATION_KEY_KIND = "kind";
  private static final String LOCATION_KEY_DISPLAY_NAME = "displayName";
  private static final String LOCATION_KEY_SSH_DESTINATION = "sshDestination";
  private static final String LOCATION_KEY_SSH_PORT = "sshPort";

  private final ManifestMigrations migrations;

  public SessionManifestCodec(ManifestMigrations migrations) {
    this.migrations = migrations;
  }

  /** A codec using the migration chain this build ships. */
  public static SessionManifestCodec standard() {
    return new SessionManifestCodec(ManifestMigrations.standard());
  }

  public ManifestMigrations migrations() {
    return migrations;
  }

  // ------------------------------------------------------------------ read

  /** Reads and migrates the manifest at {@code file}, streaming it from disk. */
  public SessionManifest read(Path file) throws IOException {
    return read(file, false);
  }

  /**
   * Reads a manifest at a portable archive boundary, requiring one canonical UUID spelling.
   *
   * <p>Ordinary local reads intentionally remain tolerant of UUID aliases written by older builds.
   * An archive identity becomes a mutation key and directory name, so import and export use this
   * stricter entry point instead.
   */
  public SessionManifest readForPortableArchive(Path file) throws IOException {
    return read(file, true);
  }

  /** Reads a portable manifest from a caller-owned, already secured stream. */
  public SessionManifest readForPortableArchive(Reader reader, String location) throws IOException {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(location, "location");
    JsonValue document;
    try {
      document = JsonReader.parse(reader);
    } catch (JsonException e) {
      throw new SessionFormatException(
          "manifest at " + location + " is not valid JSON: " + e.getMessage(), e);
    }
    return fromJson(document, location, true);
  }

  private SessionManifest read(Path file, boolean requireCanonicalSessionId) throws IOException {
    String location = file.toString();
    JsonValue document;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      document = JsonReader.parse(reader);
    } catch (NoSuchFileException e) {
      throw new SessionFormatException("no manifest at " + location, e);
    } catch (JsonException e) {
      throw new SessionFormatException(
          "manifest at " + location + " is not valid JSON: " + e.getMessage(), e);
    }
    return fromJson(document, location, requireCanonicalSessionId);
  }

  /** Parses manifest text. Convenience for tests and for in-memory documents. */
  public SessionManifest readText(String text, String location) throws SessionFormatException {
    JsonValue document;
    try {
      document = JsonReader.parse(text);
    } catch (JsonException e) {
      throw new SessionFormatException(
          "manifest at " + location + " is not valid JSON: " + e.getMessage(), e);
    }
    return fromJson(document, location);
  }

  /** Maps an already-parsed document, migrating it forward first. */
  public SessionManifest fromJson(JsonValue document, String location)
      throws SessionFormatException {
    return fromJson(document, location, false);
  }

  private SessionManifest fromJson(
      JsonValue document, String location, boolean requireCanonicalSessionId)
      throws SessionFormatException {
    if (!(document instanceof JsonObject root)) {
      throw new SessionFormatException("manifest at " + location + " is not a JSON object");
    }
    JsonObject migrated = migrations.migrate(root, location);
    try {
      return map(migrated, location, requireCanonicalSessionId);
    } catch (JsonException | IllegalArgumentException e) {
      throw new SessionFormatException(
          "manifest at " + location + " is malformed: " + e.getMessage(), e);
    }
  }

  private SessionManifest map(JsonObject root, String location, boolean requireCanonicalSessionId)
      throws SessionFormatException {
    SessionManifest.Builder builder =
        new SessionManifest.Builder()
            .formatVersion(ManifestMigrations.declaredVersion(root, location))
            .appVersion(requiredString(root, KEY_APP_VERSION, location))
            .sessionId(
                parseSessionId(
                    requiredString(root, KEY_SESSION_ID, location),
                    location,
                    requireCanonicalSessionId))
            .createdMicros(requiredLong(root, KEY_CREATED_MICROS, location))
            .finalizedMicros(optionalLong(root, KEY_FINALIZED_MICROS))
            .state(parseState(requiredString(root, KEY_STATE, location), location))
            .workingDirectory(optionalString(root, KEY_WORKING_DIRECTORY))
            .workspaceRoot(optionalString(root, KEY_WORKSPACE_ROOT))
            .executionLocation(executionLocation(root, location))
            .bazelExecutable(optionalString(root, KEY_BAZEL_EXECUTABLE))
            .bazelVersion(optionalString(root, KEY_BAZEL_VERSION))
            .originalCommand(optionalStringList(root, KEY_ORIGINAL_COMMAND, location))
            .effectiveCommand(optionalStringList(root, KEY_EFFECTIVE_COMMAND, location))
            .environmentCapturePolicy(optionalString(root, KEY_ENVIRONMENT_CAPTURE_POLICY))
            .capturePreset(optionalString(root, KEY_CAPTURE_PRESET))
            .injectedFlags(optionalStringList(root, KEY_INJECTED_FLAGS, location))
            .auxiliaryCommands(auxiliaryCommands(root, location))
            .sources(sources(root, location))
            .redactionState(optionalString(root, KEY_REDACTION_STATE))
            .eventCount(optionalLong(root, KEY_EVENT_COUNT))
            .actionCount(optionalLong(root, KEY_ACTION_COUNT))
            .indexVersions(indexVersions(root, location))
            .schemaVersion(optionalInt(root, KEY_SCHEMA_VERSION))
            .warnings(optionalStringList(root, KEY_WARNINGS, location).orElse(List.of()))
            .containsAbsolutePaths(optionalBoolean(root, KEY_CONTAINS_ABSOLUTE_PATHS, location))
            .containsEnvironmentValues(
                optionalBoolean(root, KEY_CONTAINS_ENVIRONMENT_VALUES, location))
            .unknownFields(unknownMembers(root, KNOWN_KEYS));
    return builder.build();
  }

  private static Optional<ExecutionLocation> executionLocation(JsonObject root, String location)
      throws SessionFormatException {
    Optional<JsonValue> member = root.member(KEY_EXECUTION_LOCATION);
    if (member.isEmpty()) {
      return Optional.empty();
    }
    if (!(member.get() instanceof JsonObject object)) {
      throw new SessionFormatException(
          "manifest at " + location + ": '" + KEY_EXECUTION_LOCATION + "' must be an object");
    }
    String kindText = requiredString(object, LOCATION_KEY_KIND, location);
    ExecutionLocation.Kind kind;
    try {
      kind = ExecutionLocation.Kind.valueOf(kindText);
    } catch (IllegalArgumentException e) {
      throw new SessionFormatException(
          "manifest at "
              + location
              + " records execution location kind '"
              + kindText
              + "', which this build does not recognize",
          e);
    }
    try {
      return Optional.of(
          new ExecutionLocation(
              kind,
              requiredString(object, LOCATION_KEY_DISPLAY_NAME, location),
              optionalString(object, LOCATION_KEY_SSH_DESTINATION),
              optionalInt(object, LOCATION_KEY_SSH_PORT)));
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new SessionFormatException(
          "manifest at " + location + " has an invalid execution location: " + e.getMessage(), e);
    }
  }

  private static Map<String, JsonValue> unknownMembers(JsonObject object, Set<String> known) {
    Map<String, JsonValue> unknown = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> member : object.members().entrySet()) {
      if (!known.contains(member.getKey())) {
        unknown.put(member.getKey(), member.getValue());
      }
    }
    return unknown;
  }

  private static SessionId parseSessionId(
      String text, String location, boolean requireCanonicalSessionId)
      throws SessionFormatException {
    try {
      return requireCanonicalSessionId ? SessionId.parseCanonical(text) : SessionId.parse(text);
    } catch (IllegalArgumentException e) {
      if (!requireCanonicalSessionId) {
        throw new SessionFormatException(
            "manifest at " + location + " has an unparseable sessionId '" + text + "'", e);
      }
      throw new SessionFormatException(
          "manifest at " + location + " does not have a canonical UUID sessionId: '" + text + "'",
          e);
    }
  }

  private static SessionState parseState(String text, String location)
      throws SessionFormatException {
    try {
      return SessionState.valueOf(text);
    } catch (IllegalArgumentException e) {
      // A state we cannot interpret is not something to guess at: the whole
      // point of the state machine is that transitions are validated.
      throw new SessionFormatException(
          "manifest at "
              + location
              + " records session state '"
              + text
              + "', which this build does not recognize; it may have been written by a newer"
              + " version",
          e);
    }
  }

  private static Completeness parseCompleteness(String text, String location)
      throws SessionFormatException {
    try {
      return Completeness.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new SessionFormatException(
          "manifest at "
              + location
              + " records completeness '"
              + text
              + "', which this build does not recognize",
          e);
    }
  }

  private List<CaptureSourceEntry> sources(JsonObject root, String location)
      throws SessionFormatException {
    Optional<JsonValue> member = root.member(KEY_SOURCES);
    if (member.isEmpty()) {
      return List.of();
    }
    if (!(member.get() instanceof JsonArray array)) {
      throw new SessionFormatException(
          "manifest at " + location + ": '" + KEY_SOURCES + "' must be an array");
    }
    List<CaptureSourceEntry> entries = new ArrayList<>(array.elements().size());
    for (JsonValue element : array.elements()) {
      if (!(element instanceof JsonObject source)) {
        throw new SessionFormatException(
            "manifest at " + location + ": every '" + KEY_SOURCES + "' entry must be an object");
      }
      entries.add(
          new CaptureSourceEntry(
              requiredString(source, SOURCE_KEY_KIND, location),
              optionalString(source, SOURCE_KEY_PATH),
              optionalString(source, SOURCE_KEY_SHA256),
              optionalLong(source, SOURCE_KEY_BYTE_SIZE),
              parseCompleteness(
                  requiredString(source, SOURCE_KEY_COMPLETENESS, location), location),
              optionalString(source, SOURCE_KEY_NOTE),
              unknownMembers(source, KNOWN_SOURCE_KEYS)));
    }
    return entries;
  }

  private Optional<List<AuxiliaryCommand>> auxiliaryCommands(JsonObject root, String location)
      throws SessionFormatException {
    Optional<JsonValue> member = root.member(KEY_AUXILIARY_COMMANDS);
    if (member.isEmpty()) {
      return Optional.empty();
    }
    if (!(member.get() instanceof JsonArray array)) {
      throw new SessionFormatException(
          "manifest at " + location + ": '" + KEY_AUXILIARY_COMMANDS + "' must be an array");
    }
    List<AuxiliaryCommand> commands = new ArrayList<>(array.elements().size());
    for (JsonValue element : array.elements()) {
      if (!(element instanceof JsonObject command)) {
        throw new SessionFormatException(
            "manifest at " + location + ": every auxiliary command must be an object");
      }
      commands.add(
          new AuxiliaryCommand(
              requiredString(command, AUX_KEY_LABEL, location),
              stringList(command, AUX_KEY_ARGV, location).orElse(List.of())));
    }
    return Optional.of(commands);
  }

  private Optional<Map<String, Integer>> indexVersions(JsonObject root, String location)
      throws SessionFormatException {
    Optional<JsonValue> member = root.member(KEY_INDEX_VERSIONS);
    if (member.isEmpty()) {
      return Optional.empty();
    }
    if (!(member.get() instanceof JsonObject object)) {
      throw new SessionFormatException(
          "manifest at " + location + ": '" + KEY_INDEX_VERSIONS + "' must be an object");
    }
    Map<String, Integer> versions = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> entry : object.members().entrySet()) {
      if (!(entry.getValue() instanceof JsonNumber number)) {
        throw new SessionFormatException(
            "manifest at " + location + ": index version '" + entry.getKey() + "' is not a number");
      }
      versions.put(entry.getKey(), number.asInt());
    }
    return Optional.of(versions);
  }

  private static String requiredString(JsonObject object, String key, String location)
      throws SessionFormatException {
    return optionalString(object, key)
        .orElseThrow(
            () ->
                new SessionFormatException(
                    "manifest at " + location + " is missing required member '" + key + "'"));
  }

  private static long requiredLong(JsonObject object, String key, String location)
      throws SessionFormatException {
    OptionalLong value = optionalLong(object, key);
    if (value.isEmpty()) {
      throw new SessionFormatException(
          "manifest at " + location + " is missing required member '" + key + "'");
    }
    return value.getAsLong();
  }

  private static Optional<String> optionalString(JsonObject object, String key) {
    return object
        .member(key)
        .map(
            value -> {
              if (value instanceof JsonString string) {
                return string.value();
              }
              throw new JsonException("member '" + key + "' must be a string");
            });
  }

  private static OptionalLong optionalLong(JsonObject object, String key) {
    Optional<JsonValue> member = object.member(key);
    if (member.isEmpty()) {
      return OptionalLong.empty();
    }
    if (member.get() instanceof JsonNumber number) {
      return OptionalLong.of(number.asLong());
    }
    throw new JsonException("member '" + key + "' must be a number");
  }

  private static OptionalInt optionalInt(JsonObject object, String key) {
    OptionalLong value = optionalLong(object, key);
    return value.isEmpty()
        ? OptionalInt.empty()
        : OptionalInt.of(Math.toIntExact(value.getAsLong()));
  }

  private static Optional<Boolean> optionalBoolean(JsonObject object, String key, String location)
      throws SessionFormatException {
    Optional<JsonValue> member = object.member(key);
    if (member.isEmpty()) {
      return Optional.empty();
    }
    if (member.get() instanceof JsonBool bool) {
      return Optional.of(bool.value());
    }
    throw new SessionFormatException(
        "manifest at " + location + ": '" + key + "' must be true or false");
  }

  private static Optional<List<String>> optionalStringList(
      JsonObject object, String key, String location) throws SessionFormatException {
    return stringList(object, key, location);
  }

  private static Optional<List<String>> stringList(JsonObject object, String key, String location)
      throws SessionFormatException {
    Optional<JsonValue> member = object.member(key);
    if (member.isEmpty()) {
      return Optional.empty();
    }
    if (!(member.get() instanceof JsonArray array)) {
      throw new SessionFormatException(
          "manifest at " + location + ": '" + key + "' must be an array");
    }
    List<String> values = new ArrayList<>(array.elements().size());
    for (JsonValue element : array.elements()) {
      if (!(element instanceof JsonString string)) {
        throw new SessionFormatException(
            "manifest at " + location + ": every '" + key + "' element must be a string");
      }
      values.add(string.value());
    }
    return Optional.of(values);
  }

  // ----------------------------------------------------------------- write

  /** Atomically replaces the manifest at {@code file}. */
  public void write(Path file, SessionManifest manifest) throws IOException {
    AtomicFiles.writeString(file, writeText(manifest));
  }

  /** The exact text {@link #write} would produce. */
  public String writeText(SessionManifest manifest) {
    return JsonWriter.writePretty(toJson(manifest));
  }

  /** Maps a manifest to a document: known members in canonical order, then unknown ones. */
  public JsonObject toJson(SessionManifest manifest) {
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put(KEY_FORMAT_VERSION, JsonNumber.of(manifest.formatVersion()));
    members.put(KEY_APP_VERSION, new JsonString(manifest.appVersion()));
    members.put(KEY_SESSION_ID, new JsonString(manifest.sessionId().toString()));
    members.put(KEY_CREATED_MICROS, JsonNumber.of(manifest.createdMicros()));
    putLong(members, KEY_FINALIZED_MICROS, manifest.finalizedMicros());
    members.put(KEY_STATE, new JsonString(manifest.state().name()));
    putString(members, KEY_WORKING_DIRECTORY, manifest.workingDirectory());
    putString(members, KEY_WORKSPACE_ROOT, manifest.workspaceRoot());
    manifest
        .executionLocation()
        .ifPresent(
            executionLocation -> {
              Map<String, JsonValue> fields = new LinkedHashMap<>();
              fields.put(LOCATION_KEY_KIND, new JsonString(executionLocation.kind().name()));
              fields.put(
                  LOCATION_KEY_DISPLAY_NAME, new JsonString(executionLocation.displayName()));
              putString(fields, LOCATION_KEY_SSH_DESTINATION, executionLocation.sshDestination());
              executionLocation
                  .sshPort()
                  .ifPresent(port -> fields.put(LOCATION_KEY_SSH_PORT, JsonNumber.of(port)));
              members.put(KEY_EXECUTION_LOCATION, new JsonObject(fields));
            });
    putString(members, KEY_BAZEL_EXECUTABLE, manifest.bazelExecutable());
    putString(members, KEY_BAZEL_VERSION, manifest.bazelVersion());
    putStrings(members, KEY_ORIGINAL_COMMAND, manifest.originalCommand());
    putStrings(members, KEY_EFFECTIVE_COMMAND, manifest.effectiveCommand());
    putString(members, KEY_ENVIRONMENT_CAPTURE_POLICY, manifest.environmentCapturePolicy());
    putString(members, KEY_CAPTURE_PRESET, manifest.capturePreset());
    putStrings(members, KEY_INJECTED_FLAGS, manifest.injectedFlags());
    manifest
        .auxiliaryCommands()
        .ifPresent(
            commands -> {
              List<JsonValue> encoded = new ArrayList<>(commands.size());
              for (AuxiliaryCommand command : commands) {
                Map<String, JsonValue> fields = new LinkedHashMap<>();
                fields.put(AUX_KEY_LABEL, new JsonString(command.label()));
                fields.put(AUX_KEY_ARGV, JsonArray.ofStrings(command.argv()));
                encoded.add(new JsonObject(fields));
              }
              members.put(KEY_AUXILIARY_COMMANDS, new JsonArray(encoded));
            });
    members.put(KEY_SOURCES, encodeSources(manifest.sources()));
    putString(members, KEY_REDACTION_STATE, manifest.redactionState());
    putLong(members, KEY_EVENT_COUNT, manifest.eventCount());
    putLong(members, KEY_ACTION_COUNT, manifest.actionCount());
    manifest
        .indexVersions()
        .ifPresent(
            versions -> {
              Map<String, JsonValue> fields = new LinkedHashMap<>();
              versions.forEach((name, version) -> fields.put(name, JsonNumber.of(version)));
              members.put(KEY_INDEX_VERSIONS, new JsonObject(fields));
            });
    manifest
        .schemaVersion()
        .ifPresent(version -> members.put(KEY_SCHEMA_VERSION, JsonNumber.of(version)));
    members.put(KEY_WARNINGS, JsonArray.ofStrings(manifest.warnings()));
    manifest
        .containsAbsolutePaths()
        .ifPresent(flag -> members.put(KEY_CONTAINS_ABSOLUTE_PATHS, JsonValue.of(flag)));
    manifest
        .containsEnvironmentValues()
        .ifPresent(flag -> members.put(KEY_CONTAINS_ENVIRONMENT_VALUES, JsonValue.of(flag)));

    // Anything a newer build wrote, appended verbatim. Never dropped.
    for (Map.Entry<String, JsonValue> unknown : manifest.unknownFields().entrySet()) {
      members.putIfAbsent(unknown.getKey(), unknown.getValue());
    }
    return new JsonObject(members);
  }

  private static JsonArray encodeSources(List<CaptureSourceEntry> sources) {
    List<JsonValue> encoded = new ArrayList<>(sources.size());
    for (CaptureSourceEntry source : sources) {
      Map<String, JsonValue> fields = new LinkedHashMap<>();
      fields.put(SOURCE_KEY_KIND, new JsonString(source.kind()));
      putString(fields, SOURCE_KEY_PATH, source.path());
      putString(fields, SOURCE_KEY_SHA256, source.sha256());
      putLong(fields, SOURCE_KEY_BYTE_SIZE, source.byteSize());
      fields.put(SOURCE_KEY_COMPLETENESS, new JsonString(source.completeness().name()));
      putString(fields, SOURCE_KEY_NOTE, source.note());
      for (Map.Entry<String, JsonValue> unknown : source.unknownFields().entrySet()) {
        fields.putIfAbsent(unknown.getKey(), unknown.getValue());
      }
      encoded.add(new JsonObject(fields));
    }
    return new JsonArray(encoded);
  }

  private static void putString(
      Map<String, JsonValue> members, String key, Optional<String> value) {
    value.ifPresent(text -> members.put(key, new JsonString(text)));
  }

  private static void putStrings(
      Map<String, JsonValue> members, String key, Optional<List<String>> value) {
    value.ifPresent(list -> members.put(key, JsonArray.ofStrings(list)));
  }

  private static void putLong(Map<String, JsonValue> members, String key, OptionalLong value) {
    value.ifPresent(number -> members.put(key, JsonNumber.of(number)));
  }
}
