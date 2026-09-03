package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Atomic launch conveniences for an ephemeral discovered Workspace identity. */
final class LauncherHistoryStore implements LauncherSettingsStore {

  private static final Logger log = LoggerFactory.getLogger(LauncherHistoryStore.class);
  private static final String FORMAT = "2";
  private static final String HISTORY_ONLY_FORMAT = "1";

  private final Path file;
  private final Replacer replacer;

  LauncherHistoryStore(Path settingsDirectory) {
    this(settingsDirectory, LauncherHistoryStore::replace);
  }

  LauncherHistoryStore(Path settingsDirectory, Replacer replacer) {
    file =
        Objects.requireNonNull(settingsDirectory, "settingsDirectory")
            .resolve("command-history.properties");
    this.replacer = Objects.requireNonNull(replacer, "replacer");
  }

  Path file() {
    return file;
  }

  @Override
  public LauncherStateStore.State load() {
    requireBackgroundThread();
    if (!Files.isRegularFile(file)) {
      return historyState(List.of());
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Properties values = new Properties();
      values.load(reader);
      String format = required(values, "format");
      if (!FORMAT.equals(format) && !HISTORY_ONLY_FORMAT.equals(format)) {
        throw new IllegalArgumentException("unknown launcher history format");
      }
      int count = Integer.parseInt(required(values, "history.count"));
      if (count < 0 || count > LauncherHistory.MAX_ENTRIES) {
        throw new IllegalArgumentException("invalid history count " + count);
      }
      ArrayList<String> history = new ArrayList<>(count);
      for (int index = 0; index < count; index++) {
        history.add(required(values, "history." + index));
      }
      String executable = FORMAT.equals(format) ? required(values, "bazel") : "bazel";
      return discoveredState(executable, history);
    } catch (IOException | RuntimeException unreadable) {
      log.warn(
          "discovered Workspace launch preferences at {} could not be read; using defaults",
          file,
          unreadable);
      return historyState(List.of());
    }
  }

  @Override
  public boolean save(LauncherStateStore.State state) {
    requireBackgroundThread();
    LauncherStateStore.State historyState = persistedState(state);
    Path temporary = null;
    try {
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".command-history-", ".tmp");
      Properties values = new Properties();
      values.setProperty("format", FORMAT);
      values.setProperty("bazel", historyState.bazelExecutable());
      values.setProperty("history.count", Integer.toString(historyState.history().size()));
      for (int index = 0; index < historyState.history().size(); index++) {
        values.setProperty("history." + index, historyState.history().get(index));
      }
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        values.store(writer, "Bazel Build Visualizer discovered Workspace launch preferences");
      }
      replacer.replace(temporary, file);
      temporary = null;
      return true;
    } catch (IOException | RuntimeException failure) {
      log.warn("discovered Workspace launch preferences could not be saved to {}", file, failure);
      return false;
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
          log.warn(
              "temporary launch preferences could not be removed from {}",
              temporary,
              cleanupFailure);
        }
      }
    }
  }

  @Override
  public LauncherStateStore.State persistedState(LauncherStateStore.State state) {
    Objects.requireNonNull(state, "state");
    return discoveredState(state.bazelExecutable(), state.history());
  }

  @Override
  public boolean discoveredWorkspaceOnly() {
    return true;
  }

  private static LauncherStateStore.State historyState(List<String> history) {
    return discoveredState("bazel", history);
  }

  private static LauncherStateStore.State discoveredState(
      String bazelExecutable, List<String> history) {
    return new LauncherStateStore.State(
        "",
        Objects.requireNonNull(bazelExecutable, "bazelExecutable"),
        CapturePreset.defaultPreset(),
        "",
        history,
        LauncherStateStore.ExecutionHost.LOCAL,
        "",
        "",
        List.of());
  }

  private static String required(Properties values, String key) {
    String value = values.getProperty(key);
    if (value == null) {
      throw new IllegalArgumentException("missing " + key);
    }
    return value;
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
      throw new IllegalStateException("launcher history I/O must not run on the EDT");
    }
  }

  @FunctionalInterface
  interface Replacer {
    void replace(Path temporary, Path destination) throws IOException;
  }
}
