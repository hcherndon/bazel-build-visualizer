package com.holtherndon.bazelviz.ui.logging;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Backend-neutral control surface for the application-owned logging runtime.
 *
 * <p>The Swing module deliberately knows nothing about Logback. The application composition root
 * supplies an implementation. Accessors and {@link #setVerbosity(LogVerbosity)} must be fast,
 * in-memory operations because the menu calls them on the EDT; file creation and persistence belong
 * on background I/O threads.
 */
public interface LoggingRuntime {

  /** Currently active application logging level. */
  LogVerbosity verbosity();

  /** Applies a level immediately without performing file I/O. */
  void setVerbosity(LogVerbosity verbosity);

  /** Active application log file. Valid only while {@link #available()} is true. */
  Path currentLog();

  /** Number of log records an asynchronous destination could not retain. */
  long droppedRecordCount();

  /** Whether the file-backed runtime is available for control and inspection. */
  boolean available();

  /** Safe placeholder used by compatibility constructors and failed startup. */
  static LoggingRuntime unavailable() {
    return UnavailableLoggingRuntime.INSTANCE;
  }
}

/** Package-private singleton keeps the placeholder implementation out of the public API. */
final class UnavailableLoggingRuntime implements LoggingRuntime {

  static final UnavailableLoggingRuntime INSTANCE = new UnavailableLoggingRuntime();

  private UnavailableLoggingRuntime() {}

  @Override
  public LogVerbosity verbosity() {
    return LogVerbosity.defaultVerbosity();
  }

  @Override
  public void setVerbosity(LogVerbosity verbosity) {
    Objects.requireNonNull(verbosity, "verbosity");
  }

  @Override
  public Path currentLog() {
    throw new IllegalStateException("application file logging is unavailable");
  }

  @Override
  public long droppedRecordCount() {
    return 0;
  }

  @Override
  public boolean available() {
    return false;
  }
}
