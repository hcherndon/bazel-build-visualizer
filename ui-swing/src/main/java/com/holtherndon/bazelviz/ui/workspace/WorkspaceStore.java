package com.holtherndon.bazelviz.ui.workspace;

import com.holtherndon.bazelviz.ui.capture.LauncherStateStore;
import com.holtherndon.bazelviz.ui.capture.SshConnectionProfile;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, atomic persistence for user-managed workspaces. */
public final class WorkspaceStore {

  /** Maximum number of workspace profiles accepted from disk or by {@link #save}. */
  public static final int MAX_SAVED_WORKSPACES = 100;

  /** Maximum encoded size of the workspace settings file. */
  public static final long MAX_SETTINGS_FILE_BYTES = 1_048_576L;

  private static final Logger log = LoggerFactory.getLogger(WorkspaceStore.class);
  private static final String FORMAT = "1";
  private static final String INVALID_WARNING =
      "Saved workspaces are invalid; no workspace was loaded.";
  private static final String UNREADABLE_WARNING =
      "Saved workspaces could not be read; no workspace was loaded.";
  private static final String SAVE_WARNING =
      "Workspaces could not be saved; the previous list is unchanged.";

  private final Path file;
  private final Replacer replacer;
  private final ReaderOpener readerOpener;

  public WorkspaceStore(Path settingsDirectory) {
    this(
        settingsDirectory,
        WorkspaceStore::replace,
        path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
  }

  WorkspaceStore(Path settingsDirectory, Replacer replacer) {
    this(
        settingsDirectory, replacer, path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
  }

  WorkspaceStore(Path settingsDirectory, Replacer replacer, ReaderOpener readerOpener) {
    file =
        Objects.requireNonNull(settingsDirectory, "settingsDirectory")
            .resolve("workspaces.properties");
    this.replacer = Objects.requireNonNull(replacer, "replacer");
    this.readerOpener = Objects.requireNonNull(readerOpener, "readerOpener");
  }

  /** Visible for troubleshooting, diagnostics UI, and focused persistence tests. */
  public Path file() {
    return file;
  }

  /** Loads the saved list, or an empty list when settings are absent or unusable. */
  public List<WorkspaceProfile> load() {
    return loadWithDiagnostics().workspaces();
  }

  /** Loads the saved list and reports whether migration or user action is appropriate. */
  public LoadResult loadWithDiagnostics() {
    requireBackgroundThread();
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return new LoadResult(List.of(), Source.MISSING, false, List.of());
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      log.warn("workspace settings at {} are not a readable regular file", file);
      return unusable(UNREADABLE_WARNING);
    }
    try {
      long size = Files.size(file);
      if (size > MAX_SETTINGS_FILE_BYTES) {
        throw new IllegalArgumentException(
            "workspace settings exceed " + MAX_SETTINGS_FILE_BYTES + " bytes");
      }
      try (Reader reader = readerOpener.open(file)) {
        Properties values = new Properties();
        values.load(reader);
        List<WorkspaceProfile> workspaces = readProfiles(values);
        return new LoadResult(workspaces, Source.STORED, true, List.of());
      }
    } catch (IOException unreadable) {
      log.warn("workspace settings at {} could not be read", file, unreadable);
      return unusable(UNREADABLE_WARNING);
    } catch (RuntimeException malformed) {
      log.warn("workspace settings at {} are invalid", file, malformed);
      return unusable(INVALID_WARNING);
    }
  }

  /**
   * Loads current settings, migrating a caller-supplied legacy launcher snapshot only when the
   * workspace file is absent.
   *
   * <p>The caller decides whether its launcher snapshot represents real legacy settings or
   * first-run defaults. Existing but corrupt workspace settings are never overwritten.
   */
  public LoadResult loadOrMigrate(LauncherStateStore.State legacyState, long migrationTimeMicros) {
    requireBackgroundThread();
    Objects.requireNonNull(legacyState, "legacyState");
    LoadResult loaded = loadWithDiagnostics();
    if (loaded.source() != Source.MISSING) {
      return loaded;
    }

    MigrationResult migrated = migrateLegacy(legacyState, migrationTimeMicros);
    SaveResult saved = saveWithDiagnostics(migrated.workspaces());
    List<String> diagnostics = new ArrayList<>(migrated.diagnostics());
    diagnostics.addAll(saved.diagnostics());
    return new LoadResult(
        migrated.workspaces(), Source.LEGACY_MIGRATION, saved.saved(), diagnostics);
  }

  /** Saves a complete snapshot. A failure leaves the previous live file unchanged. */
  public boolean save(List<WorkspaceProfile> workspaces) {
    return saveWithDiagnostics(workspaces).saved();
  }

  /** Saves a complete snapshot and returns safe user-facing diagnostics. */
  public SaveResult saveWithDiagnostics(List<WorkspaceProfile> workspaces) {
    requireBackgroundThread();
    Path temporary = null;
    try {
      List<WorkspaceProfile> normalized = normalize(workspaces);
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".workspaces-", ".tmp");
      Properties values = writeProfiles(normalized);
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        values.store(writer, "Bazel Build Visualizer workspaces");
      }
      if (Files.size(temporary) > MAX_SETTINGS_FILE_BYTES) {
        return failed("The workspace list is too large to save.");
      }
      replacer.replace(temporary, file);
      temporary = null;
      return new SaveResult(true, List.of());
    } catch (TooManyWorkspaces tooMany) {
      return failed("At most " + MAX_SAVED_WORKSPACES + " workspaces can be saved.");
    } catch (IllegalArgumentException invalid) {
      log.warn("invalid workspace snapshot was not saved", invalid);
      return failed("The workspace list is invalid and was not saved.");
    } catch (IOException | RuntimeException failure) {
      log.warn("workspace settings could not be saved to {}", file, failure);
      return failed(SAVE_WARNING);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
          log.warn(
              "temporary workspace settings could not be removed from {}",
              temporary,
              cleanupFailure);
        }
      }
    }
  }

  /**
   * Converts launcher settings without file or network access.
   *
   * <p>The selected launcher workspace becomes the most recent entry. Saved SSH profiles follow in
   * their legacy order. Stable IDs are derived only from execution location and repository, so
   * migration is repeatable.
   */
  public static MigrationResult migrateLegacy(
      LauncherStateStore.State legacyState, long migrationTimeMicros) {
    Objects.requireNonNull(legacyState, "legacyState");
    if (migrationTimeMicros < 0) {
      throw new IllegalArgumentException("migration time must not be negative");
    }

    Map<String, LegacyCandidate> unique = new LinkedHashMap<>();
    int skipped = 0;
    if (legacyState.executionHost() == LauncherStateStore.ExecutionHost.LOCAL) {
      try {
        LegacyCandidate local =
            new LegacyCandidate(
                "local\u001f" + legacyState.workspace(),
                WorkspaceProfile.local(
                    migratedId("local", "", OptionalInt.empty(), legacyState.workspace()),
                    repositoryLabel(legacyState.workspace()),
                    legacyState.workspace(),
                    legacyState.bazelExecutable(),
                    OptionalLong.empty()));
        unique.put(local.key(), local);
      } catch (RuntimeException invalid) {
        skipped++;
      }
    } else {
      try {
        LegacyCandidate remote =
            fromLegacySsh(
                legacyState.sshDestination(),
                legacyState.sshPort(),
                legacyState.workspace(),
                legacyState.bazelExecutable());
        unique.put(remote.key(), remote);
      } catch (RuntimeException invalid) {
        skipped++;
      }
    }

    for (SshConnectionProfile legacyProfile : legacyState.sshProfiles()) {
      try {
        LegacyCandidate remote =
            fromLegacySsh(
                legacyProfile.destination(),
                legacyProfile.port(),
                legacyProfile.workingDirectory(),
                legacyProfile.bazelExecutable());
        unique.putIfAbsent(remote.key(), remote);
      } catch (RuntimeException invalid) {
        skipped++;
      }
    }

    List<WorkspaceProfile> migrated = new ArrayList<>(unique.size());
    long openedMicros = migrationTimeMicros;
    for (LegacyCandidate candidate : unique.values()) {
      migrated.add(candidate.profile().openedAt(openedMicros));
      if (openedMicros > 0) {
        openedMicros--;
      }
    }
    List<String> diagnostics =
        skipped == 0
            ? List.of()
            : List.of(
                skipped == 1
                    ? "One invalid legacy workspace was skipped."
                    : skipped + " invalid legacy workspaces were skipped.");
    return new MigrationResult(migrated, diagnostics);
  }

  private static LegacyCandidate fromLegacySsh(
      String destination, String portText, String workingDirectory, String bazelExecutable) {
    OptionalInt port = parseOptionalPort(portText);
    WorkspaceProfile profile =
        WorkspaceProfile.ssh(
            migratedId("ssh", destination, port, workingDirectory),
            repositoryLabel(workingDirectory),
            destination,
            port,
            workingDirectory,
            bazelExecutable,
            OptionalLong.empty());
    return new LegacyCandidate(
        profile.machineKey() + "\u001f" + profile.workingDirectory(), profile);
  }

  private static OptionalInt parseOptionalPort(String value) {
    String cleaned = Objects.requireNonNull(value, "port").strip();
    if (cleaned.isEmpty()) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(Integer.parseInt(cleaned));
  }

  private static String migratedId(
      String kind, String destination, OptionalInt port, String workingDirectory) {
    String seed =
        "bazelviz-workspace-v1\u001f"
            + kind
            + "\u001f"
            + Objects.requireNonNull(destination, "destination").strip()
            + "\u001f"
            + (port.isPresent() ? Integer.toString(port.getAsInt()) : "")
            + "\u001f"
            + Objects.requireNonNull(workingDirectory, "workingDirectory").strip();
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String repositoryLabel(String workingDirectory) {
    String cleaned = Objects.requireNonNull(workingDirectory, "workingDirectory").strip();
    int end = cleaned.length();
    while (end > 1 && (cleaned.charAt(end - 1) == '/' || cleaned.charAt(end - 1) == '\\')) {
      end--;
    }
    String withoutTrailing = cleaned.substring(0, end);
    int slash = Math.max(withoutTrailing.lastIndexOf('/'), withoutTrailing.lastIndexOf('\\'));
    String candidate = withoutTrailing.substring(slash + 1);
    return candidate.isBlank() ? withoutTrailing : candidate;
  }

  private static List<WorkspaceProfile> readProfiles(Properties values) {
    if (!FORMAT.equals(values.getProperty("format"))) {
      throw new IllegalArgumentException("unknown workspace settings format");
    }
    int count = Integer.parseInt(required(values, "count"));
    if (count < 0 || count > MAX_SAVED_WORKSPACES) {
      throw new IllegalArgumentException("invalid workspace count " + count);
    }
    List<WorkspaceProfile> workspaces = new ArrayList<>(count);
    Set<String> identifiers = new HashSet<>();
    for (int index = 0; index < count; index++) {
      String prefix = "workspace." + index + ".";
      String id = required(values, prefix + "id");
      if (!identifiers.add(id)) {
        throw new IllegalArgumentException("duplicate workspace id");
      }
      WorkspaceProfile.Kind kind = WorkspaceProfile.Kind.valueOf(required(values, prefix + "kind"));
      String destination = required(values, prefix + "destination");
      OptionalInt port = parseOptionalPort(required(values, prefix + "port"));
      OptionalLong lastOpened = parseOptionalLong(required(values, prefix + "lastOpenedMicros"));
      WorkspaceProfile profile;
      if (kind == WorkspaceProfile.Kind.LOCAL) {
        if (!destination.isBlank() || port.isPresent()) {
          throw new IllegalArgumentException("local workspace contains SSH settings");
        }
        profile =
            WorkspaceProfile.local(
                id,
                required(values, prefix + "label"),
                required(values, prefix + "workingDirectory"),
                required(values, prefix + "bazel"),
                lastOpened);
      } else {
        profile =
            WorkspaceProfile.ssh(
                id,
                required(values, prefix + "label"),
                destination,
                port,
                required(values, prefix + "workingDirectory"),
                required(values, prefix + "bazel"),
                lastOpened);
      }
      workspaces.add(profile);
    }
    workspaces.sort(WorkspaceProfile.RECENT_FIRST);
    return List.copyOf(workspaces);
  }

  private static OptionalLong parseOptionalLong(String value) {
    String cleaned = Objects.requireNonNull(value, "value").strip();
    return cleaned.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(cleaned));
  }

  private static Properties writeProfiles(List<WorkspaceProfile> workspaces) {
    Properties values = new Properties();
    values.setProperty("format", FORMAT);
    values.setProperty("count", Integer.toString(workspaces.size()));
    for (int index = 0; index < workspaces.size(); index++) {
      WorkspaceProfile workspace = workspaces.get(index);
      String prefix = "workspace." + index + ".";
      values.setProperty(prefix + "id", workspace.id());
      values.setProperty(prefix + "label", workspace.label());
      values.setProperty(prefix + "kind", workspace.kind().name());
      values.setProperty(prefix + "destination", workspace.destination().orElse(""));
      values.setProperty(
          prefix + "port",
          workspace.port().isPresent() ? Integer.toString(workspace.port().getAsInt()) : "");
      values.setProperty(prefix + "workingDirectory", workspace.workingDirectory());
      values.setProperty(prefix + "bazel", workspace.bazelExecutable());
      values.setProperty(
          prefix + "lastOpenedMicros",
          workspace.lastOpenedMicros().isPresent()
              ? Long.toString(workspace.lastOpenedMicros().getAsLong())
              : "");
    }
    return values;
  }

  private static List<WorkspaceProfile> normalize(List<WorkspaceProfile> workspaces) {
    Objects.requireNonNull(workspaces, "workspaces");
    if (workspaces.size() > MAX_SAVED_WORKSPACES) {
      throw new TooManyWorkspaces();
    }
    List<WorkspaceProfile> normalized = new ArrayList<>(workspaces.size());
    Set<String> identifiers = new HashSet<>();
    for (WorkspaceProfile workspace : workspaces) {
      WorkspaceProfile required = Objects.requireNonNull(workspace, "workspace");
      if (!identifiers.add(required.id())) {
        throw new IllegalArgumentException("duplicate workspace id");
      }
      normalized.add(required);
    }
    normalized.sort(WorkspaceProfile.RECENT_FIRST);
    return List.copyOf(normalized);
  }

  private static String required(Properties values, String key) {
    String value = values.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("missing " + key);
    }
    return value;
  }

  private static LoadResult unusable(String diagnostic) {
    return new LoadResult(List.of(), Source.UNUSABLE, false, List.of(diagnostic));
  }

  private static SaveResult failed(String diagnostic) {
    return new SaveResult(false, List.of(diagnostic));
  }

  private static void replace(Path temporary, Path destination) throws IOException {
    try {
      Files.move(
          temporary,
          destination,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void requireBackgroundThread() {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace settings I/O must not run on the EDT");
    }
  }

  @FunctionalInterface
  interface Replacer {
    void replace(Path temporary, Path destination) throws IOException;
  }

  @FunctionalInterface
  interface ReaderOpener {
    Reader open(Path source) throws IOException;
  }

  private record LegacyCandidate(String key, WorkspaceProfile profile) {}

  private static final class TooManyWorkspaces extends IllegalArgumentException {}

  /** Why a load produced its returned list. */
  public enum Source {
    MISSING,
    STORED,
    UNUSABLE,
    LEGACY_MIGRATION
  }

  /** Immutable result of loading or migration. */
  public record LoadResult(
      List<WorkspaceProfile> workspaces,
      Source source,
      boolean persisted,
      List<String> diagnostics) {

    public LoadResult {
      workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
      source = Objects.requireNonNull(source, "source");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
  }

  /** Immutable result of one complete-list save attempt. */
  public record SaveResult(boolean saved, List<String> diagnostics) {

    public SaveResult {
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
  }

  /** Immutable output of the pure launcher-settings migration. */
  public record MigrationResult(List<WorkspaceProfile> workspaces, List<String> diagnostics) {

    public MigrationResult {
      workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
  }
}
