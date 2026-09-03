package com.holtherndon.bazelviz.ui.lifecycle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, off-EDT shutdown policies for executors owned by Swing components. */
public final class ExecutorClose {

  private static final Logger log = LoggerFactory.getLogger(ExecutorClose.class);

  /** Cooperative drain time for ordinary UI-owned file/settings work. */
  public static final Duration ORDINARY_DRAIN_GRACE = Duration.ofSeconds(10);

  /** Time allowed after interruption before a close stage reports incomplete teardown. */
  public static final Duration FORCED_REAP_GRACE = Duration.ofSeconds(2);

  /**
   * Cooperative drain time for an accepted editor save.
   *
   * <p>The editor permits 16 MiB. A remote atomic replacement can perform an SFTP upload, the
   * guarded rename, and a full SFTP verification read; fifteen minutes exceeds those operations'
   * combined transport timeouts while keeping application shutdown finite.
   */
  public static final Duration FILE_SAVE_DRAIN_GRACE = Duration.ofMinutes(15);

  private ExecutorClose() {}

  /** Interrupts active work now and reports failure if it still has not stopped after the reap. */
  public static CompletionStage<Void> cancelAsync(ExecutorService executor, String owner) {
    Objects.requireNonNull(executor, "executor");
    requireText(owner);
    executor.shutdownNow();
    return awaitAsync(executor, Duration.ZERO, FORCED_REAP_GRACE, owner, false);
  }

  /** Drains accepted ordinary work, then interrupts and performs one bounded reap if needed. */
  public static CompletionStage<Void> drainAsync(ExecutorService executor, String owner) {
    return drainAsync(executor, ORDINARY_DRAIN_GRACE, owner);
  }

  /** Drains accepted work for the supplied grace, then interrupts and performs one reap. */
  public static CompletionStage<Void> drainAsync(
      ExecutorService executor, Duration drainGrace, String owner) {
    Objects.requireNonNull(executor, "executor");
    requirePositive(drainGrace, "drainGrace");
    requireText(owner);
    executor.shutdown();
    return awaitAsync(executor, drainGrace, FORCED_REAP_GRACE, owner, true);
  }

  private static CompletionStage<Void> awaitAsync(
      ExecutorService executor,
      Duration drainGrace,
      Duration forcedGrace,
      String owner,
      boolean interruptAfterDrain) {
    CompletableFuture<Void> completion = new CompletableFuture<>();
    Thread.ofVirtual()
        .name(owner + "-close")
        .start(
            () -> {
              try {
                if (interruptAfterDrain
                    && executor.awaitTermination(drainGrace.toNanos(), TimeUnit.NANOSECONDS)) {
                  completion.complete(null);
                  return;
                }
                if (interruptAfterDrain) {
                  List<Runnable> abandoned = executor.shutdownNow();
                  log.warn(
                      "{} exceeded its drain grace; interrupted active work and abandoned"
                          + " {} queued task(s)",
                      owner,
                      abandoned.size());
                }
                if (!executor.awaitTermination(forcedGrace.toNanos(), TimeUnit.NANOSECONDS)) {
                  throw new IllegalStateException(
                      owner + " did not stop after bounded shutdown waits");
                }
                completion.complete(null);
              } catch (InterruptedException interrupted) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                completion.completeExceptionally(interrupted);
              } catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
              }
            });
    return completion;
  }

  private static void requirePositive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("owner must not be blank");
    }
  }
}
