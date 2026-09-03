package com.holtherndon.bazelviz.ui.logging;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Atomic persistence for the user's logging verbosity choice. */
public final class LoggingSettingsStore {

  private static final Logger log = LoggerFactory.getLogger(LoggingSettingsStore.class);
  private static final String FORMAT = "1";
  private static final String INVALID_SETTINGS_WARNING =
      "Saved logging settings are invalid; Info will be used.";
  private static final String UNREADABLE_SETTINGS_WARNING =
      "Saved logging settings could not be read; Info will be used.";

  private final Path file;
  private final Replacer replacer;
  private final ReaderOpener readerOpener;

  public LoggingSettingsStore(Path settingsDirectory) {
    this(
        settingsDirectory,
        LoggingSettingsStore::replace,
        path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
  }

  LoggingSettingsStore(Path settingsDirectory, Replacer replacer) {
    this(
        settingsDirectory, replacer, path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
  }

  LoggingSettingsStore(Path settingsDirectory, Replacer replacer, ReaderOpener readerOpener) {
    file =
        Objects.requireNonNull(settingsDirectory, "settingsDirectory")
            .resolve("logging.properties");
    this.replacer = Objects.requireNonNull(replacer, "replacer");
    this.readerOpener = Objects.requireNonNull(readerOpener, "readerOpener");
  }

  /** Visible for troubleshooting, diagnostics UI and focused persistence tests. */
  public Path file() {
    return file;
  }

  /**
   * Loads the saved choice, or Info when settings are absent or invalid.
   *
   * <p>This store intentionally does not inspect {@code bbv.log.level}. A process override is
   * resolved by the composition root and must not replace the persisted user choice merely because
   * the application started.
   */
  public LogVerbosity load() {
    return loadWithDiagnostics().verbosity();
  }

  /**
   * Loads the saved choice together with a safe warning for an unusable existing settings file.
   *
   * <p>The warning never contains settings content, exception text, or the local file path. A
   * missing file is a normal first-run state and has no warning.
   */
  public LoadResult loadWithDiagnostics() {
    requireBackgroundThread();
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return new LoadResult(LogVerbosity.defaultVerbosity(), Optional.empty());
    }
    if (!Files.isRegularFile(file)) {
      log.warn("logging settings at {} are not a readable regular file; using Info", file);
      return fallback(UNREADABLE_SETTINGS_WARNING);
    }
    try (Reader reader = readerOpener.open(file)) {
      Properties values = new Properties();
      values.load(reader);
      if (!FORMAT.equals(values.getProperty("format"))) {
        throw new IllegalArgumentException("unknown logging settings format");
      }
      String id = values.getProperty("verbosity");
      if (id == null) {
        throw new IllegalArgumentException("missing verbosity");
      }
      LogVerbosity verbosity =
          LogVerbosity.fromId(id)
              .orElseThrow(() -> new IllegalArgumentException("unknown verbosity " + id));
      return new LoadResult(verbosity, Optional.empty());
    } catch (IOException unreadable) {
      log.warn("logging settings at {} could not be read; using Info", file, unreadable);
      return fallback(UNREADABLE_SETTINGS_WARNING);
    } catch (RuntimeException malformed) {
      log.warn("logging settings at {} are invalid; using Info", file, malformed);
      return fallback(INVALID_SETTINGS_WARNING);
    }
  }

  private static LoadResult fallback(String warning) {
    return new LoadResult(LogVerbosity.defaultVerbosity(), Optional.of(warning));
  }

  /**
   * Saves one immutable choice through a sibling temporary file.
   *
   * @return true only after the live settings file was replaced
   */
  public boolean save(LogVerbosity verbosity) {
    requireBackgroundThread();
    Objects.requireNonNull(verbosity, "verbosity");
    Path temporary = null;
    try {
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".logging-", ".tmp");
      Properties values = new Properties();
      values.setProperty("format", FORMAT);
      values.setProperty("verbosity", verbosity.id());
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        values.store(writer, "Bazel Build Visualizer logging");
      }
      replacer.replace(temporary, file);
      temporary = null;
      return true;
    } catch (IOException | RuntimeException failure) {
      log.warn("logging settings could not be saved to {}", file, failure);
      return false;
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
          log.warn(
              "temporary logging settings could not be removed from {}", temporary, cleanupFailure);
        }
      }
    }
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
      throw new IllegalStateException("logging settings I/O must not run on the EDT");
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

  /** Immutable logging preference load result. */
  public record LoadResult(LogVerbosity verbosity, Optional<String> warning) {

    public LoadResult {
      Objects.requireNonNull(verbosity, "verbosity");
      Objects.requireNonNull(warning, "warning");
    }
  }
}
