package com.holtherndon.bazelviz.ui.logging;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serializes rapid verbosity changes onto a shared background-I/O executor. */
public final class LoggingPreferenceWriter {

  private static final Logger log = LoggerFactory.getLogger(LoggingPreferenceWriter.class);

  private final LoggingSettingsStore store;
  private final Executor executor;
  private final Consumer<SaveFailure> failureHandler;
  private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

  private LogVerbosity pending;
  private boolean draining;
  private boolean closed;

  public LoggingPreferenceWriter(LoggingSettingsStore store, Executor executor) {
    this(store, executor, failure -> {});
  }

  public LoggingPreferenceWriter(
      LoggingSettingsStore store, Executor executor, Consumer<SaveFailure> failureHandler) {
    this.store = Objects.requireNonNull(store, "store");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
  }

  /** Queues the newest explicit user choice without doing I/O on the caller. */
  public void save(LogVerbosity verbosity) {
    Objects.requireNonNull(verbosity, "verbosity");
    boolean schedule = false;
    synchronized (this) {
      if (closed) {
        log.debug("ignoring logging verbosity after preference writer closed");
        return;
      }
      pending = verbosity;
      if (!draining) {
        draining = true;
        schedule = true;
      }
    }
    if (schedule) {
      submitDrain();
    }
  }

  /**
   * Stops accepting choices and completes after the last queued save attempt.
   *
   * <p>The returned stage never waits on the caller. Save failures are sent to the configured
   * failure handler and do not prevent close completion.
   */
  public CompletionStage<Void> closeAsync() {
    boolean complete;
    synchronized (this) {
      closed = true;
      complete = !draining;
    }
    if (complete) {
      closeCompletion.complete(null);
    }
    return closeCompletion;
  }

  private void submitDrain() {
    try {
      executor.execute(this::drain);
    } catch (RuntimeException rejected) {
      LogVerbosity abandoned;
      boolean complete;
      synchronized (this) {
        abandoned = pending;
        pending = null;
        draining = false;
        complete = closed;
      }
      if (abandoned != null) {
        reportFailure(abandoned, rejected);
      }
      if (complete) {
        closeCompletion.complete(null);
      }
    }
  }

  private void drain() {
    while (true) {
      LogVerbosity next;
      boolean complete;
      synchronized (this) {
        next = pending;
        pending = null;
        if (next == null) {
          draining = false;
          complete = closed;
        } else {
          complete = false;
        }
      }
      if (next == null) {
        if (complete) {
          closeCompletion.complete(null);
        }
        return;
      }
      try {
        if (!store.save(next)) {
          reportFailure(
              next,
              new IllegalStateException("Logging settings could not be saved to " + store.file()));
        }
      } catch (RuntimeException failure) {
        reportFailure(next, failure);
      }
    }
  }

  private void reportFailure(LogVerbosity verbosity, Throwable cause) {
    SaveFailure failure = new SaveFailure(verbosity, cause);
    try {
      failureHandler.accept(failure);
    } catch (RuntimeException callbackFailure) {
      log.warn("logging settings failure callback failed", callbackFailure);
    }
  }

  /** One failed persistence attempt. */
  public record SaveFailure(LogVerbosity verbosity, Throwable cause) {

    public SaveFailure {
      Objects.requireNonNull(verbosity, "verbosity");
      Objects.requireNonNull(cause, "cause");
    }
  }
}
