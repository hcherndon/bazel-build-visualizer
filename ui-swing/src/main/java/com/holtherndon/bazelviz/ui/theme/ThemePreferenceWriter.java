package com.holtherndon.bazelviz.ui.theme;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Serializes rapid appearance changes onto a shared background-I/O executor. */
public final class ThemePreferenceWriter {

  private static final Logger log = LoggerFactory.getLogger(ThemePreferenceWriter.class);

  private final ThemeSettingsStore store;
  private final Executor executor;
  private final Consumer<SaveFailure> failureHandler;
  private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

  private AppTheme pending;
  private boolean draining;
  private boolean closed;

  public ThemePreferenceWriter(ThemeSettingsStore store, Executor executor) {
    this(store, executor, failure -> {});
  }

  public ThemePreferenceWriter(
      ThemeSettingsStore store, Executor executor, Consumer<SaveFailure> failureHandler) {
    this.store = Objects.requireNonNull(store, "store");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
  }

  /** Queues the newest choice without doing file I/O on the calling thread. */
  public void save(AppTheme theme) {
    Objects.requireNonNull(theme, "theme");
    boolean schedule = false;
    synchronized (this) {
      if (closed) {
        log.debug("ignoring appearance choice after preference writer closed");
        return;
      }
      pending = theme;
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
    boolean complete = false;
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
      AppTheme abandoned;
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
      AppTheme next;
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
              new IllegalStateException(
                  "Appearance settings could not be saved to " + store.file()));
        }
      } catch (RuntimeException failure) {
        reportFailure(next, failure);
      }
    }
  }

  private void reportFailure(AppTheme theme, Throwable cause) {
    SaveFailure failure = new SaveFailure(theme, cause);
    try {
      failureHandler.accept(failure);
    } catch (RuntimeException callbackFailure) {
      log.warn("appearance settings failure callback failed", callbackFailure);
    }
  }

  /** One failed persistence attempt. */
  public record SaveFailure(AppTheme theme, Throwable cause) {

    public SaveFailure {
      Objects.requireNonNull(theme, "theme");
      Objects.requireNonNull(cause, "cause");
    }
  }
}
