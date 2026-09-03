package com.holtherndon.bazelviz.app.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.holtherndon.bazelviz.ui.logging.LogVerbosity;
import com.holtherndon.bazelviz.ui.logging.LoggingRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Application-owned Logback runtime for the graphical application.
 *
 * <p>The class attaches only the rolling file destination. The console appender remains the one
 * declared by {@code logback.xml}, which preserves the CLI's stderr-only contract. Graphical
 * startup sets {@value #CONSOLE_LEVEL_PROPERTY} before SLF4J initializes so the console can stay at
 * WARN while this file records the selected, usually more detailed, level.
 *
 * <p>File writes happen on {@link LossAwareAsyncAppender}'s one bounded writer thread. A caller,
 * including the Swing event thread, only offers an immutable logging event to the queue and never
 * waits for disk I/O.
 */
public final class ApplicationLogging implements LoggingRuntime, AutoCloseable {

  /** Process-only verbosity override. */
  public static final String LEVEL_PROPERTY = "bbv.log.level";

  /** Console threshold read by {@code logback.xml}. */
  public static final String CONSOLE_LEVEL_PROPERTY = "bbv.log.console.level";

  /** Maximum bytes in one active or rolled application log. */
  public static final long MAX_LOG_FILE_BYTES = 8L * 1024 * 1024;

  /** Maximum age, in daily periods, retained by the rolling policy. */
  public static final int MAX_HISTORY_DAYS = 7;

  /** Maximum bytes retained across rolled application log archives. */
  public static final long TOTAL_LOG_BYTES = 64L * 1024 * 1024;

  /** Records waiting for the file writer before explicit overflow begins. */
  public static final int LOG_QUEUE_CAPACITY = 8192;

  /** Longest orderly shutdown waits for queued records to reach the file. */
  public static final Duration MAX_FLUSH_TIME = Duration.ofSeconds(5);

  static final String APPENDER_NAME = "APPLICATION_FILE_ASYNC";
  static final String FILE_APPENDER_NAME = "APPLICATION_FILE";
  static final String APPLICATION_LOGGER_NAME = "com.holtherndon.bazelviz";
  static final String FILE_NAME = "application.log";
  static final String ARCHIVE_PATTERN = "application.%d{yyyy-MM-dd}.%i.log.gz";
  static final String LOCK_FILE_NAME = ".application.log.lock";
  static final String FILE_PATTERN =
      "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} [%thread] " + "%-5level %logger{48} - %msg%n";

  private final LoggerContext context;
  private final Logger root;
  private final Logger applicationLogger;
  private final Path currentLog;
  private final LossAwareAsyncAppender appender;
  private final Optional<String> startupWarning;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile LogVerbosity verbosity;
  private final boolean configured;

  private ApplicationLogging(
      LoggerContext context,
      Logger root,
      Logger applicationLogger,
      Path currentLog,
      LossAwareAsyncAppender appender,
      LogVerbosity verbosity,
      Optional<String> startupWarning,
      boolean configured) {
    this.context = context;
    this.root = root;
    this.applicationLogger = applicationLogger;
    this.currentLog = currentLog;
    this.appender = appender;
    this.verbosity = Objects.requireNonNull(verbosity, "verbosity");
    this.startupWarning = Objects.requireNonNull(startupWarning, "startupWarning");
    this.configured = configured;
  }

  /** Starts graphical file logging using INFO as the saved first-run value. */
  public static ApplicationLogging start(Path logsDirectory) {
    return start(logsDirectory, LogVerbosity.defaultVerbosity());
  }

  /**
   * Starts graphical file logging.
   *
   * <p>A recognized {@value #LEVEL_PROPERTY} value wins for this process only. An invalid override
   * is ignored, recorded as a warning, and never changes the supplied saved preference.
   */
  public static ApplicationLogging start(Path logsDirectory, LogVerbosity savedVerbosity) {
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (!(factory instanceof LoggerContext context)) {
      LogVerbosity effective =
          effectiveVerbosity(System.getProperty(LEVEL_PROPERTY), savedVerbosity);
      return unavailable(
          effective, "SLF4J is not backed by Logback; application file logging is unavailable");
    }
    return start(logsDirectory, savedVerbosity, System.getProperty(LEVEL_PROPERTY), context);
  }

  /** Isolated-context entry point used by focused backend tests. */
  static ApplicationLogging start(
      Path logsDirectory,
      LogVerbosity savedVerbosity,
      String processOverride,
      LoggerContext context) {
    return start(logsDirectory, savedVerbosity, processOverride, context, LoggingPolicy.defaults());
  }

  /** Isolated-context and small-policy entry point used by backend tests. */
  static ApplicationLogging start(
      Path logsDirectory,
      LogVerbosity savedVerbosity,
      String processOverride,
      LoggerContext context,
      LoggingPolicy loggingPolicy) {
    Objects.requireNonNull(logsDirectory, "logsDirectory");
    Objects.requireNonNull(savedVerbosity, "savedVerbosity");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(loggingPolicy, "loggingPolicy");

    LogVerbosity effective = effectiveVerbosity(processOverride, savedVerbosity);
    Optional<String> invalidOverride = invalidOverrideWarning(processOverride, savedVerbosity);
    Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Logger applicationLogger = context.getLogger(APPLICATION_LOGGER_NAME);

    synchronized (context) {
      if (root.getAppender(APPENDER_NAME) != null) {
        throw new IllegalStateException("application file logging is already started");
      }

      Path directory = logsDirectory.toAbsolutePath().normalize();
      Path active = directory.resolve(FILE_NAME);
      ControlSafePatternLayoutEncoder encoder = null;
      SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = null;
      RollingFileAppender<ILoggingEvent> file = null;
      LossAwareAsyncAppender async = null;
      LogFileLease lease = null;
      boolean leaseOwnedByAppender = false;
      try {
        Files.createDirectories(directory);
        lease = LogFileLease.acquire(directory.resolve(LOCK_FILE_NAME));

        encoder = new ControlSafePatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(FILE_PATTERN);
        encoder.start();

        file = new RollingFileAppender<>();
        file.setContext(context);
        file.setName(FILE_APPENDER_NAME);
        file.setFile(active.toString());
        file.setAppend(true);
        file.setEncoder(encoder);

        policy = new SizeAndTimeBasedRollingPolicy<>();
        policy.setContext(context);
        policy.setParent(file);
        policy.setFileNamePattern(directory.resolve(ARCHIVE_PATTERN).toString());
        policy.setMaxFileSize(new FileSize(loggingPolicy.maxFileBytes()));
        policy.setMaxHistory(loggingPolicy.maxHistoryDays());
        policy.setTotalSizeCap(new FileSize(loggingPolicy.totalArchiveBytes()));
        policy.setCleanHistoryOnStart(true);
        policy.start();

        file.setRollingPolicy(policy);
        file.setTriggeringPolicy(policy);
        file.start();
        if (!encoder.isStarted() || !policy.isStarted() || !file.isStarted()) {
          throw new IllegalStateException("Logback did not start the rolling file appender");
        }

        LogFileLease acquiredLease = lease;
        async =
            new LossAwareAsyncAppender(
                file,
                LOG_QUEUE_CAPACITY,
                MAX_FLUSH_TIME,
                () -> {},
                () -> {
                  try {
                    enforceArchiveRetention(
                        directory,
                        loggingPolicy.maxHistoryDays(),
                        loggingPolicy.totalArchiveBytes());
                  } finally {
                    acquiredLease.close();
                  }
                });
        async.setContext(context);
        async.setName(APPENDER_NAME);
        async.start();
        if (!async.isStarted()) {
          throw new IllegalStateException("Logback did not start the asynchronous writer");
        }
        leaseOwnedByAppender = true;

        configureLevels(root, applicationLogger, effective);
        root.addAppender(async);
        ApplicationLogging runtime =
            new ApplicationLogging(
                context, root, applicationLogger, active, async, effective, invalidOverride, true);
        org.slf4j.Logger logger = context.getLogger(ApplicationLogging.class);
        // A malformed process override is a configuration warning even
        // when the saved level is ERROR. Send it directly to the file
        // destination so the chosen threshold cannot hide the reason
        // the override had no effect.
        invalidOverride.ifPresent(async::recordInternalWarning);
        logger.info(
            "Application file logging started at {} with {} verbosity",
            active,
            effective.displayName());
        return runtime;
      } catch (IOException | RuntimeException failure) {
        if (async != null && async.isStarted()) {
          async.stop();
        } else if (file != null && file.isStarted()) {
          file.stop();
        } else {
          if (policy != null) {
            policy.stop();
          }
          if (encoder != null) {
            encoder.stop();
          }
        }
        if (!leaseOwnedByAppender && lease != null) {
          try {
            lease.close();
          } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
          }
        }
        root.setLevel(dependencyLevel(effective));
        applicationLogger.setLevel(null);
        String warning =
            "Application file logging could not start at " + active + ": " + failure.getMessage();
        context.getLogger(ApplicationLogging.class).warn(warning, failure);
        return new ApplicationLogging(
            context, root, applicationLogger, null, null, effective, Optional.of(warning), false);
      }
    }
  }

  /** Startup warning suitable for a non-fatal UI notice. */
  public Optional<String> startupWarning() {
    return startupWarning;
  }

  @Override
  public LogVerbosity verbosity() {
    return verbosity;
  }

  /** Changes only in-memory Logback state; persistence belongs to the UI settings writer. */
  @Override
  public void setVerbosity(LogVerbosity verbosity) {
    LogVerbosity next = Objects.requireNonNull(verbosity, "verbosity");
    if (closed.get() || root == null || applicationLogger == null) {
      return;
    }
    configureLevels(root, applicationLogger, next);
    this.verbosity = next;
  }

  @Override
  public Path currentLog() {
    if (!available()) {
      throw new IllegalStateException("application file logging is unavailable");
    }
    return currentLog;
  }

  @Override
  public long droppedRecordCount() {
    return appender == null ? 0 : appender.droppedRecordCount();
  }

  /**
   * Records a trusted startup configuration warning even when Error is selected.
   *
   * <p>This is for fixed application-owned diagnostics discovered before the rolling destination
   * existed, such as a damaged preferences file. It must not be used for file content or other
   * untrusted text.
   */
  public void recordConfigurationWarning(String warning) {
    Objects.requireNonNull(warning, "warning");
    if (configured && !closed.get()) {
      appender.recordInternalWarning(warning);
    }
  }

  @Override
  public boolean available() {
    return configured && !closed.get() && appender.isStarted();
  }

  /**
   * Detaches and drains for at most {@link #MAX_FLUSH_TIME}.
   *
   * <p>Queued records left at the deadline become exact drops. If the file appender is already
   * inside an operating-system write, that write and the destination close may finish
   * asynchronously after this method returns.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true) || !configured) {
      return;
    }
    synchronized (context) {
      root.detachAppender(appender);
    }
    appender.stop();
  }

  private static ApplicationLogging unavailable(LogVerbosity verbosity, String warning) {
    return new ApplicationLogging(
        null, null, null, null, null, verbosity, Optional.of(warning), false);
  }

  private static LogVerbosity effectiveVerbosity(
      String processOverride, LogVerbosity savedVerbosity) {
    return LogVerbosity.startupVerbosity(
        processOverride, Objects.requireNonNull(savedVerbosity, "savedVerbosity"));
  }

  private static Optional<String> invalidOverrideWarning(
      String processOverride, LogVerbosity savedVerbosity) {
    if (processOverride == null
        || processOverride.isBlank()
        || LogVerbosity.fromId(processOverride).isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        "Unknown "
            + LEVEL_PROPERTY
            + " value '"
            + processOverride
            + "'; using saved "
            + savedVerbosity.displayName()
            + " verbosity");
  }

  private static Level level(LogVerbosity verbosity) {
    return switch (verbosity) {
      case ERROR -> Level.ERROR;
      case WARN -> Level.WARN;
      case INFO -> Level.INFO;
      case DEBUG -> Level.DEBUG;
      case TRACE -> Level.TRACE;
    };
  }

  private static void configureLevels(
      Logger root, Logger applicationLogger, LogVerbosity verbosity) {
    // Dependency internals remain at Warn even when application Debug or
    // Trace is selected. The application namespace gets the requested
    // detail and its additive events still reach the root destinations.
    root.setLevel(dependencyLevel(verbosity));
    applicationLogger.setLevel(level(verbosity));
  }

  private static Level dependencyLevel(LogVerbosity verbosity) {
    return verbosity == LogVerbosity.ERROR ? Level.ERROR : Level.WARN;
  }

  /** Makes age and byte retention exact after Logback's final asynchronous rollover. */
  private static void enforceArchiveRetention(Path directory, int historyDays, long byteCap) {
    try {
      FileTime cutoff = FileTime.from(Instant.now().minus(Duration.ofDays(historyDays)));
      try (DirectoryStream<Path> archives =
          Files.newDirectoryStream(directory, "application.*.log.gz")) {
        for (Path archive : archives) {
          if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
              && Files.getLastModifiedTime(archive, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff)
                  < 0) {
            Files.deleteIfExists(archive);
          }
        }
      }
      while (true) {
        long totalBytes = 0;
        Path oldest = null;
        FileTime oldestTime = null;
        try (DirectoryStream<Path> archives =
            Files.newDirectoryStream(directory, "application.*.log.gz")) {
          for (Path archive : archives) {
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
              continue;
            }
            long size = Files.size(archive);
            totalBytes = totalBytes > Long.MAX_VALUE - size ? Long.MAX_VALUE : totalBytes + size;
            FileTime modified = Files.getLastModifiedTime(archive, LinkOption.NOFOLLOW_LINKS);
            if (oldest == null
                || modified.compareTo(oldestTime) < 0
                || (modified.equals(oldestTime)
                    && archive.getFileName().toString().compareTo(oldest.getFileName().toString())
                        < 0)) {
              oldest = archive;
              oldestTime = modified;
            }
          }
        }
        if (totalBytes <= byteCap || oldest == null) {
          return;
        }
        Files.deleteIfExists(oldest);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(
          "application log archive retention could not be enforced", failure);
    }
  }

  /** Immutable rolling values; production uses the documented constants above. */
  record LoggingPolicy(long maxFileBytes, int maxHistoryDays, long totalArchiveBytes) {

    LoggingPolicy {
      if (maxFileBytes < 1) {
        throw new IllegalArgumentException("maxFileBytes must be positive");
      }
      if (maxHistoryDays < 1) {
        throw new IllegalArgumentException("maxHistoryDays must be positive");
      }
      if (totalArchiveBytes < maxFileBytes) {
        throw new IllegalArgumentException("totalArchiveBytes must be at least maxFileBytes");
      }
    }

    private static LoggingPolicy defaults() {
      return new LoggingPolicy(MAX_LOG_FILE_BYTES, MAX_HISTORY_DAYS, TOTAL_LOG_BYTES);
    }
  }

  /** One operating-system lease over the shared rolling-log destination. */
  private static final class LogFileLease {

    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LogFileLease(FileChannel channel, FileLock lock) {
      this.channel = channel;
      this.lock = lock;
    }

    private static LogFileLease acquire(Path path) throws IOException {
      FileChannel channel =
          FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        FileLock lock;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException alreadyHeldHere) {
          lock = null;
        }
        if (lock == null) {
          throw new IOException(
              "another application instance is already writing the application log");
        }
        return new LogFileLease(channel, lock);
      } catch (IOException | RuntimeException failure) {
        try {
          channel.close();
        } catch (IOException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    }

    private void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      IOException failure = null;
      try {
        lock.release();
      } catch (IOException releaseFailure) {
        failure = releaseFailure;
      }
      try {
        channel.close();
      } catch (IOException closeFailure) {
        if (failure == null) {
          failure = closeFailure;
        } else {
          failure.addSuppressed(closeFailure);
        }
      }
      if (failure != null) {
        throw new UncheckedIOException("application log lock could not be released", failure);
      }
    }
  }
}
