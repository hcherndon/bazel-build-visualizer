package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.format.session.json.JsonException;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Brings an older {@code manifest.json} up to the version this build reads, and refuses one from
 * the future.
 *
 * <p>Migration happens on the JSON document, before it is mapped onto {@link SessionManifest}. That
 * ordering is the point: a v1 document can be missing members that v2's record requires, so there
 * is no way to construct the typed value first and fix it afterwards. Working on the document also
 * means a step can rename or restructure members, which a record-level patch cannot.
 *
 * <p>Steps run in ascending order, each raising the version by exactly one. A step that leaves
 * {@code formatVersion} wrong is a bug and fails loudly rather than being tolerated — a
 * half-migrated manifest is worse than a rejected one.
 *
 * <p>A version newer than {@link #targetVersion()} throws {@link
 * UnsupportedManifestVersionException}. It is never guessed at: reading a manifest this build does
 * not understand and then writing it back would delete whatever the newer build recorded, and a
 * session must survive being opened by an older install.
 */
public final class ManifestMigrations {

  /** The manifest format version this build writes. */
  public static final int CURRENT_FORMAT_VERSION = 1;

  /** The member every manifest carries, at every version. */
  public static final String FORMAT_VERSION_KEY = "formatVersion";

  /** One version-to-version rewrite of the manifest document. */
  @FunctionalInterface
  public interface ManifestMigration {

    /**
     * Rewrites a manifest at version {@code fromVersion} into one at {@code fromVersion + 1}. The
     * returned document must carry the new {@code formatVersion}. Members the step does not
     * understand must be passed through untouched.
     */
    JsonObject migrate(JsonObject manifest);
  }

  private final int targetVersion;
  private final Map<Integer, ManifestMigration> steps;

  private ManifestMigrations(int targetVersion, Map<Integer, ManifestMigration> steps) {
    this.targetVersion = targetVersion;
    this.steps = Map.copyOf(steps);
  }

  /**
   * The migrations this build ships. Version 1 is current and there is nothing older, so the chain
   * is empty — but the hook is wired through the codec now, so version 2 is a one-line registration
   * rather than a refactor.
   */
  public static ManifestMigrations standard() {
    return new ManifestMigrations(CURRENT_FORMAT_VERSION, Map.of());
  }

  /**
   * A custom chain. {@code steps} is keyed by the version each step reads, and must cover every
   * version from the oldest supported up to {@code targetVersion - 1}.
   */
  public static ManifestMigrations of(int targetVersion, Map<Integer, ManifestMigration> steps) {
    if (targetVersion < 1) {
      throw new IllegalArgumentException("target version must be positive: " + targetVersion);
    }
    return new ManifestMigrations(targetVersion, steps);
  }

  public int targetVersion() {
    return targetVersion;
  }

  /** The version declared by a document, for reporting before any migration runs. */
  public static int declaredVersion(JsonObject manifest, String location)
      throws SessionFormatException {
    JsonValue value =
        manifest
            .member(FORMAT_VERSION_KEY)
            .orElseThrow(
                () ->
                    new SessionFormatException(
                        "manifest at "
                            + location
                            + " has no '"
                            + FORMAT_VERSION_KEY
                            + "' member; "
                            + "it is not a Bazel Build Visualizer session manifest"));
    if (!(value instanceof JsonNumber number)) {
      throw new SessionFormatException(
          "manifest at " + location + " has a non-numeric '" + FORMAT_VERSION_KEY + "'");
    }
    try {
      return number.asInt();
    } catch (JsonException e) {
      throw new SessionFormatException(
          "manifest at " + location + " has an out-of-range '" + FORMAT_VERSION_KEY + "'", e);
    }
  }

  /**
   * Migrates {@code manifest} forward to {@link #targetVersion()}.
   *
   * @param location a human-readable source, used only in error messages
   * @return the document at the target version; the same instance when already current
   * @throws UnsupportedManifestVersionException when the document is newer than this build
   */
  public JsonObject migrate(JsonObject manifest, String location) throws SessionFormatException {
    Objects.requireNonNull(manifest, "manifest");
    int version = declaredVersion(manifest, location);
    if (version > targetVersion) {
      throw new UnsupportedManifestVersionException(version, targetVersion, location);
    }
    if (version < 1) {
      throw new SessionFormatException(
          "manifest at "
              + location
              + " declares format version "
              + version
              + ", which was never a valid version");
    }
    JsonObject current = manifest;
    List<String> applied = new ArrayList<>();
    while (version < targetVersion) {
      ManifestMigration step = steps.get(version);
      if (step == null) {
        throw new SessionFormatException(
            "manifest at "
                + location
                + " is at format version "
                + version
                + " and no migration to version "
                + (version + 1)
                + " is registered"
                + (applied.isEmpty()
                    ? ""
                    : " (already applied: " + String.join(", ", applied) + ")"));
      }
      JsonObject migrated = step.migrate(current);
      int newVersion = declaredVersion(migrated, location);
      if (newVersion != version + 1) {
        throw new SessionFormatException(
            "manifest migration "
                + version
                + " -> "
                + (version + 1)
                + " produced version "
                + newVersion
                + "; refusing a half-migrated manifest at "
                + location);
      }
      applied.add(version + " -> " + newVersion);
      current = migrated;
      version = newVersion;
    }
    return current;
  }

  /**
   * Helper for migration steps: a copy of {@code manifest} with {@code formatVersion} set, member
   * order otherwise preserved.
   */
  public static JsonObject withFormatVersion(JsonObject manifest, int version) {
    Map<String, JsonValue> members = new LinkedHashMap<>(manifest.members());
    members.put(FORMAT_VERSION_KEY, JsonNumber.of(version));
    return new JsonObject(members);
  }
}
