package com.holtherndon.bazelviz.ui.table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One view's {@link ColumnState}, as a JSON file a person can read and delete: {@code
 * settings/columns/<view>.json} under the application settings directory — the same directory the
 * Query card's saved-query library lives in, and reached the same way (the shell hands the path in;
 * nothing here resolves platform directories).
 *
 * <h2>Failure is defaults, never a dialog</h2>
 *
 * <p>This file records presentation preferences, not data. A missing file is the first run; a
 * corrupt file is treated exactly the same way, because a modal error about column widths would
 * cost the user more than the widths were worth. Corruption is logged and the file is overwritten
 * by the next save.
 *
 * <h2>Threading</h2>
 *
 * <p>Both methods do file I/O and block; callers keep them off the EDT. {@link
 * TableHeaderInteractions} routes them through a single shared I/O thread, the same shape {@code
 * QueryView} uses for its library.
 */
public final class ColumnStateStore {

  private static final Logger log = LoggerFactory.getLogger(ColumnStateStore.class);

  private final Path file;

  public ColumnStateStore(Path settingsDirectory, String viewId) {
    Objects.requireNonNull(settingsDirectory, "settingsDirectory");
    this.file = settingsDirectory.resolve("columns").resolve(fileName(viewId));
  }

  /** Where this view's state lives. Visible so a test can corrupt it. */
  public Path file() {
    return file;
  }

  /**
   * The stored state, or {@link ColumnState#empty()} when the file is absent, unreadable or not the
   * expected shape. Blocking.
   */
  public ColumnState load() {
    if (!Files.isRegularFile(file)) {
      return ColumnState.empty();
    }
    try {
      return ColumnState.fromJson(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException unreadable) {
      log.warn("column state at {} could not be read; using defaults", file, unreadable);
      return ColumnState.empty();
    }
  }

  /**
   * Writes {@code state}, creating the directory on first use. Blocking. A failed save is logged
   * and dropped — the on-screen state is unaffected and the next change tries again.
   */
  public void save(ColumnState state) {
    Objects.requireNonNull(state, "state");
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, state.toJson(), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException failure) {
      log.warn("column state could not be saved to {}", file, failure);
    }
  }

  /**
   * A view id as a file stem: lower-cased, everything but letters, digits, {@code -} and {@code _}
   * squeezed to {@code -} — the same taming {@code QueryLibrary} applies to saved-query names.
   */
  private static String fileName(String viewId) {
    Objects.requireNonNull(viewId, "viewId");
    String stem =
        viewId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
    if (stem.isEmpty()) {
      throw new IllegalArgumentException("view id \"" + viewId + "\" leaves no usable file name");
    }
    return stem + ".json";
  }
}
