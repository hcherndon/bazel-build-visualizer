package com.holtherndon.bazelviz.ui.theme;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Atomic persistence for the global appearance choice. */
public final class ThemeSettingsStore {

  private static final Logger log = LoggerFactory.getLogger(ThemeSettingsStore.class);
  private static final String FORMAT = "1";

  private final Path file;
  private final Replacer replacer;

  public ThemeSettingsStore(Path settingsDirectory) {
    this(settingsDirectory, ThemeSettingsStore::replace);
  }

  ThemeSettingsStore(Path settingsDirectory, Replacer replacer) {
    file =
        Objects.requireNonNull(settingsDirectory, "settingsDirectory")
            .resolve("appearance.properties");
    this.replacer = Objects.requireNonNull(replacer, "replacer");
  }

  /** Visible for troubleshooting and focused persistence tests. */
  public Path file() {
    return file;
  }

  /** Loads the saved theme, or the light default when settings are absent or invalid. */
  public AppTheme load() {
    requireBackgroundThread();
    if (!Files.isRegularFile(file)) {
      return AppTheme.defaultTheme();
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Properties values = new Properties();
      values.load(reader);
      if (!FORMAT.equals(values.getProperty("format"))) {
        throw new IllegalArgumentException("unknown appearance settings format");
      }
      String id = values.getProperty("theme");
      if (id == null) {
        throw new IllegalArgumentException("missing theme");
      }
      return AppTheme.fromId(id)
          .orElseThrow(() -> new IllegalArgumentException("unknown theme " + id));
    } catch (IOException | RuntimeException unreadable) {
      log.warn("appearance settings at {} could not be read; using Light", file, unreadable);
      return AppTheme.defaultTheme();
    }
  }

  /**
   * Saves one immutable choice through a sibling temporary file.
   *
   * @return true only after the live settings file was replaced
   */
  public boolean save(AppTheme theme) {
    requireBackgroundThread();
    Objects.requireNonNull(theme, "theme");
    Path temporary = null;
    try {
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".appearance-", ".tmp");
      Properties values = new Properties();
      values.setProperty("format", FORMAT);
      values.setProperty("theme", theme.id());
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        values.store(writer, "Bazel Build Visualizer appearance");
      }
      replacer.replace(temporary, file);
      temporary = null;
      return true;
    } catch (IOException | RuntimeException failure) {
      log.warn("appearance settings could not be saved to {}", file, failure);
      return false;
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
          log.warn(
              "temporary appearance settings could not be removed from {}",
              temporary,
              cleanupFailure);
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
      throw new IllegalStateException("appearance settings I/O must not run on the EDT");
    }
  }

  @FunctionalInterface
  interface Replacer {
    void replace(Path temporary, Path destination) throws IOException;
  }
}
