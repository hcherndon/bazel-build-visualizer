package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * {@code manifest.json}: everything needed to describe a session without opening its database (plan
 * section 10.3).
 *
 * <p>Two rules shape this record.
 *
 * <p><strong>Absent is not zero, and absent is not empty.</strong> Every field that can
 * legitimately be unknown is an {@code Optional}, and an absent value is written by <em>omitting
 * the key</em>, never as {@code 0}, {@code ""} or {@code []}. A pure file import has no original
 * command — that is different from having an empty one, and the manifest says so (plan 11.4). The
 * collections that are not optional ({@link #sources}, {@link #warnings}) are ones this application
 * always knows the full contents of, where empty genuinely means "none", not "not established".
 *
 * <p><strong>Unknown members survive.</strong> {@link #unknownFields} carries every top-level
 * member a newer build wrote that this one does not model, and {@link SessionManifestCodec} writes
 * them back out. Without that, opening a session with an older build would quietly delete whatever
 * the newer build had recorded (plan 21.5). The same preservation applies per capture source; see
 * {@link CaptureSourceEntry}.
 *
 * <p>Instances are immutable. Use {@link #toBuilder()} for read-modify-write, which carries unknown
 * members forward automatically.
 */
public record SessionManifest(
    int formatVersion,
    String appVersion,
    SessionId sessionId,
    long createdMicros,
    OptionalLong finalizedMicros,
    SessionState state,
    Optional<String> workingDirectory,
    Optional<String> workspaceRoot,
    Optional<ExecutionLocation> executionLocation,
    Optional<String> bazelExecutable,
    Optional<String> bazelVersion,
    Optional<List<String>> originalCommand,
    Optional<List<String>> effectiveCommand,
    Optional<String> environmentCapturePolicy,
    Optional<String> capturePreset,
    Optional<List<String>> injectedFlags,
    Optional<List<AuxiliaryCommand>> auxiliaryCommands,
    List<CaptureSourceEntry> sources,
    Optional<String> redactionState,
    OptionalLong eventCount,
    OptionalLong actionCount,
    Optional<Map<String, Integer>> indexVersions,
    OptionalInt schemaVersion,
    List<String> warnings,
    Optional<Boolean> containsAbsolutePaths,
    Optional<Boolean> containsEnvironmentValues,
    Map<String, JsonValue> unknownFields) {

  public SessionManifest {
    Objects.requireNonNull(appVersion, "appVersion");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(state, "state");
    finalizedMicros = Objects.requireNonNull(finalizedMicros, "finalizedMicros");
    workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    executionLocation = Objects.requireNonNull(executionLocation, "executionLocation");
    bazelExecutable = Objects.requireNonNull(bazelExecutable, "bazelExecutable");
    bazelVersion = Objects.requireNonNull(bazelVersion, "bazelVersion");
    originalCommand = copyOptionalList(originalCommand);
    effectiveCommand = copyOptionalList(effectiveCommand);
    environmentCapturePolicy =
        Objects.requireNonNull(environmentCapturePolicy, "environmentCapturePolicy");
    capturePreset = Objects.requireNonNull(capturePreset, "capturePreset");
    injectedFlags = copyOptionalList(injectedFlags);
    auxiliaryCommands = copyOptionalList(auxiliaryCommands);
    sources = List.copyOf(sources);
    redactionState = Objects.requireNonNull(redactionState, "redactionState");
    eventCount = Objects.requireNonNull(eventCount, "eventCount");
    actionCount = Objects.requireNonNull(actionCount, "actionCount");
    indexVersions =
        Objects.requireNonNull(indexVersions, "indexVersions")
            .map(map -> Collections.unmodifiableMap(new LinkedHashMap<>(map)));
    schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
    warnings = List.copyOf(warnings);
    containsAbsolutePaths = Objects.requireNonNull(containsAbsolutePaths, "containsAbsolutePaths");
    containsEnvironmentValues =
        Objects.requireNonNull(containsEnvironmentValues, "containsEnvironmentValues");
    unknownFields =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(unknownFields, "unknownFields")));
    if (createdMicros < 0) {
      throw new IllegalArgumentException("createdMicros must not be negative: " + createdMicros);
    }
  }

  private static <T> Optional<List<T>> copyOptionalList(Optional<List<T>> value) {
    return Objects.requireNonNull(value).map(List::copyOf);
  }

  /**
   * One capture source and how much of it was read (plan 10.3, mirrored by the {@code
   * capture_sources} table in the frozen schema).
   *
   * <p>{@code kind} is free text rather than an enum on purpose: it mirrors the schema's {@code
   * TEXT} column, whose value set grows with every capture source added in later phases, and a
   * manifest written by a newer build must not fail to load because it names a source this build
   * has not heard of.
   *
   * <p>{@code byteSize} is an {@link OptionalLong}: a source of unknown size — a stream still being
   * written, a file that vanished — reports unknown, never zero.
   */
  public record CaptureSourceEntry(
      String kind,
      Optional<String> path,
      Optional<String> sha256,
      OptionalLong byteSize,
      Completeness completeness,
      Optional<String> note,
      Map<String, JsonValue> unknownFields) {

    public CaptureSourceEntry {
      Objects.requireNonNull(kind, "kind");
      path = Objects.requireNonNull(path, "path");
      sha256 = Objects.requireNonNull(sha256, "sha256");
      byteSize = Objects.requireNonNull(byteSize, "byteSize");
      Objects.requireNonNull(completeness, "completeness");
      note = Objects.requireNonNull(note, "note");
      unknownFields =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(unknownFields, "unknownFields")));
    }

    public static CaptureSourceEntry of(
        String kind,
        Optional<String> path,
        Optional<String> sha256,
        OptionalLong byteSize,
        Completeness completeness,
        Optional<String> note) {
      return new CaptureSourceEntry(kind, path, sha256, byteSize, completeness, note, Map.of());
    }

    /** A source whose bytes have not been examined yet. */
    public static CaptureSourceEntry pending(String kind, String path) {
      return of(
          kind,
          Optional.of(path),
          Optional.empty(),
          OptionalLong.empty(),
          Completeness.UNKNOWN,
          Optional.empty());
    }
  }

  /**
   * Where the live command ran. This is provenance, never an instruction to reconnect: imported
   * manifests remain display-only and cannot start SSH.
   *
   * @param kind local desktop process or an explicitly selected SSH host
   * @param displayName safe, human-readable host/profile label
   * @param sshDestination OpenSSH Host alias or {@code user@host}; absent for local
   * @param sshPort explicitly selected SSH port; absent means the SSH config/default
   */
  public record ExecutionLocation(
      Kind kind, String displayName, Optional<String> sshDestination, OptionalInt sshPort) {

    public enum Kind {
      LOCAL,
      SSH
    }

    public ExecutionLocation {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(displayName, "displayName");
      sshDestination = Objects.requireNonNull(sshDestination, "sshDestination");
      sshPort = Objects.requireNonNull(sshPort, "sshPort");
      if (displayName.isBlank()) {
        throw new IllegalArgumentException("execution location display name is blank");
      }
      if (kind == Kind.LOCAL && (sshDestination.isPresent() || sshPort.isPresent())) {
        throw new IllegalArgumentException("a local execution location cannot carry SSH fields");
      }
      if (kind == Kind.SSH && sshDestination.filter(value -> !value.isBlank()).isEmpty()) {
        throw new IllegalArgumentException("an SSH execution location needs a destination");
      }
      if (sshPort.isPresent() && (sshPort.getAsInt() < 1 || sshPort.getAsInt() > 65_535)) {
        throw new IllegalArgumentException("SSH port must be in 1..65535");
      }
    }

    public static ExecutionLocation local() {
      return new ExecutionLocation(
          Kind.LOCAL, "This computer", Optional.empty(), OptionalInt.empty());
    }

    public static ExecutionLocation ssh(String displayName, String destination, OptionalInt port) {
      return new ExecutionLocation(Kind.SSH, displayName, Optional.of(destination), port);
    }
  }

  /** An enrichment command run alongside the build (plan 8.6, 10.3). */
  public record AuxiliaryCommand(String label, List<String> argv) {
    public AuxiliaryCommand {
      Objects.requireNonNull(label, "label");
      argv = List.copyOf(argv);
    }
  }

  /** True when the recorded state admits no further transitions. */
  public boolean isFinalized() {
    return state.isTerminal();
  }

  /** A builder pre-loaded with this manifest, including its unknown members. */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /**
   * A new manifest for a session that has just been created: state {@link SessionState#NEW}, no
   * sources, no warnings, everything else unknown.
   */
  public static Builder newSession(SessionId sessionId, String appVersion, long createdMicros) {
    return new Builder()
        .formatVersion(ManifestMigrations.CURRENT_FORMAT_VERSION)
        .appVersion(appVersion)
        .sessionId(sessionId)
        .createdMicros(createdMicros)
        .state(SessionState.NEW);
  }

  /** Mutable accumulator for {@link SessionManifest}. Not thread-safe. */
  public static final class Builder {

    private int formatVersion = ManifestMigrations.CURRENT_FORMAT_VERSION;
    private String appVersion;
    private SessionId sessionId;
    private long createdMicros;
    private OptionalLong finalizedMicros = OptionalLong.empty();
    private SessionState state = SessionState.NEW;
    private Optional<String> workingDirectory = Optional.empty();
    private Optional<String> workspaceRoot = Optional.empty();
    private Optional<ExecutionLocation> executionLocation = Optional.empty();
    private Optional<String> bazelExecutable = Optional.empty();
    private Optional<String> bazelVersion = Optional.empty();
    private Optional<List<String>> originalCommand = Optional.empty();
    private Optional<List<String>> effectiveCommand = Optional.empty();
    private Optional<String> environmentCapturePolicy = Optional.empty();
    private Optional<String> capturePreset = Optional.empty();
    private Optional<List<String>> injectedFlags = Optional.empty();
    private Optional<List<AuxiliaryCommand>> auxiliaryCommands = Optional.empty();
    private List<CaptureSourceEntry> sources = List.of();
    private Optional<String> redactionState = Optional.empty();
    private OptionalLong eventCount = OptionalLong.empty();
    private OptionalLong actionCount = OptionalLong.empty();
    private Optional<Map<String, Integer>> indexVersions = Optional.empty();
    private OptionalInt schemaVersion = OptionalInt.empty();
    private List<String> warnings = List.of();
    private Optional<Boolean> containsAbsolutePaths = Optional.empty();
    private Optional<Boolean> containsEnvironmentValues = Optional.empty();
    private Map<String, JsonValue> unknownFields = Map.of();

    public Builder() {}

    private Builder(SessionManifest source) {
      this.formatVersion = source.formatVersion;
      this.appVersion = source.appVersion;
      this.sessionId = source.sessionId;
      this.createdMicros = source.createdMicros;
      this.finalizedMicros = source.finalizedMicros;
      this.state = source.state;
      this.workingDirectory = source.workingDirectory;
      this.workspaceRoot = source.workspaceRoot;
      this.executionLocation = source.executionLocation;
      this.bazelExecutable = source.bazelExecutable;
      this.bazelVersion = source.bazelVersion;
      this.originalCommand = source.originalCommand;
      this.effectiveCommand = source.effectiveCommand;
      this.environmentCapturePolicy = source.environmentCapturePolicy;
      this.capturePreset = source.capturePreset;
      this.injectedFlags = source.injectedFlags;
      this.auxiliaryCommands = source.auxiliaryCommands;
      this.sources = source.sources;
      this.redactionState = source.redactionState;
      this.eventCount = source.eventCount;
      this.actionCount = source.actionCount;
      this.indexVersions = source.indexVersions;
      this.schemaVersion = source.schemaVersion;
      this.warnings = source.warnings;
      this.containsAbsolutePaths = source.containsAbsolutePaths;
      this.containsEnvironmentValues = source.containsEnvironmentValues;
      this.unknownFields = source.unknownFields;
    }

    public Builder formatVersion(int value) {
      this.formatVersion = value;
      return this;
    }

    public Builder appVersion(String value) {
      this.appVersion = value;
      return this;
    }

    public Builder sessionId(SessionId value) {
      this.sessionId = value;
      return this;
    }

    public Builder createdMicros(long value) {
      this.createdMicros = value;
      return this;
    }

    public Builder finalizedMicros(OptionalLong value) {
      this.finalizedMicros = value;
      return this;
    }

    public Builder finalizedAtMicros(long value) {
      this.finalizedMicros = OptionalLong.of(value);
      return this;
    }

    public Builder state(SessionState value) {
      this.state = value;
      return this;
    }

    public Builder workingDirectory(Optional<String> value) {
      this.workingDirectory = value;
      return this;
    }

    public Builder workspaceRoot(Optional<String> value) {
      this.workspaceRoot = value;
      return this;
    }

    public Builder executionLocation(Optional<ExecutionLocation> value) {
      this.executionLocation = Objects.requireNonNull(value, "value");
      return this;
    }

    public Builder bazelExecutable(Optional<String> value) {
      this.bazelExecutable = value;
      return this;
    }

    public Builder bazelVersion(Optional<String> value) {
      this.bazelVersion = value;
      return this;
    }

    public Builder originalCommand(Optional<List<String>> value) {
      this.originalCommand = value;
      return this;
    }

    public Builder effectiveCommand(Optional<List<String>> value) {
      this.effectiveCommand = value;
      return this;
    }

    public Builder environmentCapturePolicy(Optional<String> value) {
      this.environmentCapturePolicy = value;
      return this;
    }

    public Builder capturePreset(Optional<String> value) {
      this.capturePreset = value;
      return this;
    }

    public Builder injectedFlags(Optional<List<String>> value) {
      this.injectedFlags = value;
      return this;
    }

    public Builder auxiliaryCommands(Optional<List<AuxiliaryCommand>> value) {
      this.auxiliaryCommands = value;
      return this;
    }

    public Builder sources(List<CaptureSourceEntry> value) {
      this.sources = value;
      return this;
    }

    public Builder addSource(CaptureSourceEntry value) {
      List<CaptureSourceEntry> combined = new ArrayList<>(this.sources);
      combined.add(value);
      this.sources = combined;
      return this;
    }

    public Builder redactionState(Optional<String> value) {
      this.redactionState = value;
      return this;
    }

    public Builder eventCount(OptionalLong value) {
      this.eventCount = value;
      return this;
    }

    public Builder actionCount(OptionalLong value) {
      this.actionCount = value;
      return this;
    }

    public Builder indexVersions(Optional<Map<String, Integer>> value) {
      this.indexVersions = value;
      return this;
    }

    public Builder schemaVersion(OptionalInt value) {
      this.schemaVersion = value;
      return this;
    }

    public Builder warnings(List<String> value) {
      this.warnings = value;
      return this;
    }

    /** Appends a warning, keeping order and skipping an exact duplicate. */
    public Builder addWarning(String value) {
      Objects.requireNonNull(value, "value");
      if (this.warnings.contains(value)) {
        return this;
      }
      List<String> combined = new ArrayList<>(this.warnings);
      combined.add(value);
      this.warnings = combined;
      return this;
    }

    public Builder containsAbsolutePaths(Optional<Boolean> value) {
      this.containsAbsolutePaths = value;
      return this;
    }

    public Builder containsEnvironmentValues(Optional<Boolean> value) {
      this.containsEnvironmentValues = value;
      return this;
    }

    /** Members from a newer build, carried through untouched. */
    public Builder unknownFields(Map<String, JsonValue> value) {
      this.unknownFields = value;
      return this;
    }

    public SessionManifest build() {
      return new SessionManifest(
          formatVersion,
          appVersion,
          sessionId,
          createdMicros,
          finalizedMicros,
          state,
          workingDirectory,
          workspaceRoot,
          executionLocation,
          bazelExecutable,
          bazelVersion,
          originalCommand,
          effectiveCommand,
          environmentCapturePolicy,
          capturePreset,
          injectedFlags,
          auxiliaryCommands,
          sources,
          redactionState,
          eventCount,
          actionCount,
          indexVersions,
          schemaVersion,
          warnings,
          containsAbsolutePaths,
          containsEnvironmentValues,
          unknownFields);
    }
  }
}
