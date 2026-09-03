package com.holtherndon.bazelviz.ui.workspace;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, atomic persistence for the ordered set of open Workspace windows. */
public final class WorkspaceWindowStateStore {

  private static final Logger log = LoggerFactory.getLogger(WorkspaceWindowStateStore.class);
  private static final String FORMAT = "1";
  private static final String INVALID_WARNING =
      "Saved Workspace windows are invalid; no windows were restored.";
  private static final String UNREADABLE_WARNING =
      "Saved Workspace windows could not be read; no windows were restored.";
  private static final String SAVE_WARNING =
      "Workspace windows could not be saved; the previous layout is unchanged.";
  private static final String INVALID_BOUNDS_WARNING =
      "Saved bounds for one or more Workspace windows were invalid and will be ignored.";

  private final Path file;
  private final Replacer replacer;
  private final ReaderOpener readerOpener;

  public WorkspaceWindowStateStore(Path settingsDirectory) {
    this(
        settingsDirectory,
        WorkspaceWindowStateStore::replace,
        path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
  }

  WorkspaceWindowStateStore(Path settingsDirectory, Replacer replacer) {
    this(
        settingsDirectory, replacer, path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
  }

  WorkspaceWindowStateStore(Path settingsDirectory, Replacer replacer, ReaderOpener readerOpener) {
    file =
        Objects.requireNonNull(settingsDirectory, "settingsDirectory")
            .resolve("workspace-window-state.properties");
    this.replacer = Objects.requireNonNull(replacer, "replacer");
    this.readerOpener = Objects.requireNonNull(readerOpener, "readerOpener");
  }

  /** Visible for diagnostics and focused persistence tests. */
  public Path file() {
    return file;
  }

  /** Loads the saved snapshot, or an empty snapshot when settings are absent or unusable. */
  public WorkspaceWindowState load() {
    return loadWithDiagnostics().state();
  }

  /** Loads one complete snapshot and reports whether it was absent, stored, or unusable. */
  public LoadResult loadWithDiagnostics() {
    requireBackgroundThread();
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return new LoadResult(WorkspaceWindowState.empty(), Source.MISSING, List.of());
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      log.warn("Workspace window state at {} is not a readable regular file", file);
      return unusable(UNREADABLE_WARNING);
    }
    try {
      if (Files.size(file) > WorkspaceStore.MAX_SETTINGS_FILE_BYTES) {
        throw new IllegalArgumentException("Workspace window state exceeds the size bound");
      }
      Properties values = new Properties();
      try (Reader reader = readerOpener.open(file)) {
        values.load(reader);
      }
      ReadResult read = readState(values);
      return new LoadResult(read.state(), Source.STORED, read.diagnostics());
    } catch (IOException unreadable) {
      log.warn("Workspace window state at {} could not be read", file, unreadable);
      return unusable(UNREADABLE_WARNING);
    } catch (RuntimeException malformed) {
      log.warn("Workspace window state at {} is invalid", file, malformed);
      return unusable(INVALID_WARNING);
    }
  }

  /** Saves a complete snapshot. A failure leaves the previous live file unchanged. */
  public boolean save(WorkspaceWindowState state) {
    return saveWithDiagnostics(state).saved();
  }

  /** Saves a complete snapshot and returns safe user-facing diagnostics. */
  public SaveResult saveWithDiagnostics(WorkspaceWindowState state) {
    requireBackgroundThread();
    Path temporary = null;
    try {
      WorkspaceWindowState checked = Objects.requireNonNull(state, "state");
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".workspace-window-state-", ".tmp");
      Properties values = writeState(checked);
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        values.store(writer, "Bazel Build Visualizer Workspace windows");
      }
      if (Files.size(temporary) > WorkspaceStore.MAX_SETTINGS_FILE_BYTES) {
        return failed("The Workspace window layout is too large to save.");
      }
      replacer.replace(temporary, file);
      temporary = null;
      return new SaveResult(true, List.of());
    } catch (IllegalArgumentException invalid) {
      log.warn("invalid Workspace window snapshot was not saved", invalid);
      return failed("The Workspace window layout is invalid and was not saved.");
    } catch (IOException | RuntimeException failure) {
      log.warn("Workspace window state could not be saved to {}", file, failure);
      return failed(SAVE_WARNING);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
          log.warn(
              "temporary Workspace window state could not be removed from {}",
              temporary,
              cleanupFailure);
        }
      }
    }
  }

  private static ReadResult readState(Properties values) {
    if (!FORMAT.equals(values.getProperty("format"))) {
      throw new IllegalArgumentException("unknown Workspace window-state format");
    }
    int count = Integer.parseInt(required(values, "count"));
    if (count < 0 || count > WorkspaceWindowState.MAX_OPEN_WORKSPACES) {
      throw new IllegalArgumentException("invalid open Workspace count " + count);
    }
    Set<String> expectedKeys = new HashSet<>(Set.of("format", "count"));
    List<WorkspaceWindowState.OpenWorkspace> openWorkspaces = new ArrayList<>(count);
    boolean ignoredInvalidBounds = false;
    for (int index = 0; index < count; index++) {
      String prefix = "window." + index + ".";
      expectedKeys.add(prefix + "workspaceId");
      expectedKeys.add(prefix + "maximized");
      expectedKeys.add(prefix + "bounds.present");
      String workspaceId = required(values, prefix + "workspaceId");
      boolean maximized = parseBoolean(required(values, prefix + "maximized"));
      boolean hasBounds = parseBoolean(required(values, prefix + "bounds.present"));
      Optional<WorkspaceWindowState.WindowBounds> bounds;
      if (hasBounds) {
        expectedKeys.add(prefix + "bounds.x");
        expectedKeys.add(prefix + "bounds.y");
        expectedKeys.add(prefix + "bounds.width");
        expectedKeys.add(prefix + "bounds.height");
        int x = Integer.parseInt(required(values, prefix + "bounds.x"));
        int y = Integer.parseInt(required(values, prefix + "bounds.y"));
        int width = Integer.parseInt(required(values, prefix + "bounds.width"));
        int height = Integer.parseInt(required(values, prefix + "bounds.height"));
        if (WorkspaceWindowState.WindowBounds.hasUsableSize(width, height)) {
          bounds = Optional.of(new WorkspaceWindowState.WindowBounds(x, y, width, height));
        } else {
          bounds = Optional.empty();
          ignoredInvalidBounds = true;
        }
      } else {
        bounds = Optional.empty();
      }
      openWorkspaces.add(new WorkspaceWindowState.OpenWorkspace(workspaceId, bounds, maximized));
    }
    if (!values.stringPropertyNames().equals(expectedKeys)) {
      throw new IllegalArgumentException("unexpected Workspace window-state properties");
    }
    return new ReadResult(
        new WorkspaceWindowState(openWorkspaces),
        ignoredInvalidBounds ? List.of(INVALID_BOUNDS_WARNING) : List.of());
  }

  private static Properties writeState(WorkspaceWindowState state) {
    Properties values = new Properties();
    values.setProperty("format", FORMAT);
    values.setProperty("count", Integer.toString(state.openWorkspaces().size()));
    for (int index = 0; index < state.openWorkspaces().size(); index++) {
      WorkspaceWindowState.OpenWorkspace openWorkspace = state.openWorkspaces().get(index);
      String prefix = "window." + index + ".";
      values.setProperty(prefix + "workspaceId", openWorkspace.workspaceId());
      values.setProperty(prefix + "maximized", Boolean.toString(openWorkspace.maximized()));
      values.setProperty(
          prefix + "bounds.present", Boolean.toString(openWorkspace.bounds().isPresent()));
      openWorkspace
          .bounds()
          .ifPresent(
              bounds -> {
                values.setProperty(prefix + "bounds.x", Integer.toString(bounds.x()));
                values.setProperty(prefix + "bounds.y", Integer.toString(bounds.y()));
                values.setProperty(prefix + "bounds.width", Integer.toString(bounds.width()));
                values.setProperty(prefix + "bounds.height", Integer.toString(bounds.height()));
              });
    }
    return values;
  }

  private static boolean parseBoolean(String value) {
    return switch (value) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("invalid boolean value");
    };
  }

  private static String required(Properties values, String key) {
    String value = values.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("missing " + key);
    }
    return value;
  }

  private static LoadResult unusable(String diagnostic) {
    return new LoadResult(WorkspaceWindowState.empty(), Source.UNUSABLE, List.of(diagnostic));
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
      throw new IllegalStateException("Workspace window-state I/O must not run on the EDT");
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

  private record ReadResult(WorkspaceWindowState state, List<String> diagnostics) {}

  /** Why a load produced its returned state. */
  public enum Source {
    MISSING,
    STORED,
    UNUSABLE
  }

  /** Immutable result of one complete-state load. */
  public record LoadResult(WorkspaceWindowState state, Source source, List<String> diagnostics) {

    public LoadResult {
      state = Objects.requireNonNull(state, "state");
      source = Objects.requireNonNull(source, "source");
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
  }

  /** Immutable result of one complete-state save attempt. */
  public record SaveResult(boolean saved, List<String> diagnostics) {

    public SaveResult {
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }
  }
}
