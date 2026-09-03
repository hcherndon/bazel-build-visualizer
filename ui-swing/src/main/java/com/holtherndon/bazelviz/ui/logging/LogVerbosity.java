package com.holtherndon.bazelviz.ui.logging;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** One user-selectable application logging level. */
public enum LogVerbosity {
  ERROR("error", "Error", "Record only errors that prevent an operation from completing."),
  WARN("warn", "Warn", "Record warnings and errors that may need attention."),
  INFO(
      "info", "Info", "Record normal application lifecycle and operation summaries (recommended)."),
  DEBUG("debug", "Debug", "Record detailed decisions and operation progress for troubleshooting."),
  TRACE(
      "trace",
      "Trace",
      "Record the most detailed execution flow; log files grow fastest at this level.");

  private final String id;
  private final String displayName;
  private final String description;

  LogVerbosity(String id, String displayName, String description) {
    this.id = id;
    this.displayName = displayName;
    this.description = description;
  }

  /** Stable value written to settings and accepted by {@code -Dbbv.log.level}. */
  public String id() {
    return id;
  }

  /** Short label used by the logging menu. */
  public String displayName() {
    return displayName;
  }

  /** Plain-language explanation shown by tooltips and accessibility tools. */
  public String description() {
    return description;
  }

  /** First-run and invalid-settings fallback. */
  public static LogVerbosity defaultVerbosity() {
    return INFO;
  }

  /** Resolves a stable id case-insensitively; blank and unknown ids are absent. */
  public static Optional<LogVerbosity> fromId(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    String normalized = id.strip().toLowerCase(Locale.ROOT);
    for (LogVerbosity verbosity : values()) {
      if (verbosity.id.equals(normalized)) {
        return Optional.of(verbosity);
      }
    }
    return Optional.empty();
  }

  /**
   * Resolves graphical startup without changing the saved preference.
   *
   * <p>A recognized process override wins for this launch only. The caller should persist a value
   * only after an explicit user selection, never as a side effect of this resolution.
   */
  public static LogVerbosity startupVerbosity(String processOverride, LogVerbosity savedVerbosity) {
    Objects.requireNonNull(savedVerbosity, "savedVerbosity");
    return fromId(processOverride).orElse(savedVerbosity);
  }

  @Override
  public String toString() {
    return displayName;
  }
}
