package com.holtherndon.bazelviz.app.cli;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Turns Ctrl-C into a clean cancellation instead of a killed process.
 *
 * <p>Without this, a SIGINT during an import kills the JVM wherever it happens to be — quite
 * possibly between the journal append and the database write — and leaves a session whose
 * checkpoint is older than its journal. The import pipeline can recover from that, but the operator
 * has no way to know it was meant to be resumable. So the hook does two things: it raises the flag
 * the importer polls between records, and then it <em>waits</em> for the import to come to rest
 * before letting the JVM finish exiting.
 *
 * <p>The wait is the load-bearing half. A shutdown hook that only sets a flag and returns lets the
 * JVM halt immediately, cancelling nothing. The wait is bounded, because a hook that never returns
 * hangs the process: if the import has not reached a record boundary within {@link
 * #DEFAULT_WAIT_MILLIS} the guard says so and gives up, and the session is then recovered by {@code
 * JournalRecovery} on the next open — one level worse than a clean stop, still not a corrupt
 * session.
 *
 * <p>The registry is an interface rather than a direct call to {@link Runtime} so a test can
 * trigger the hook deterministically, in-process, without actually shutting down the test JVM.
 */
final class CancellationGuard implements BooleanSupplier, AutoCloseable {

  /**
   * How long the hook waits for the current record to finish. Generous compared with the cost of
   * one record, short enough that an operator pressing Ctrl-C does not think the tool has hung.
   */
  static final long DEFAULT_WAIT_MILLIS = 30_000;

  /** Where shutdown hooks are registered. */
  interface HookRegistry {

    void addShutdownHook(Thread hook);

    void removeShutdownHook(Thread hook);

    /** The real JVM. */
    static HookRegistry jvm() {
      return new HookRegistry() {
        @Override
        public void addShutdownHook(Thread hook) {
          Runtime.getRuntime().addShutdownHook(hook);
        }

        @Override
        public void removeShutdownHook(Thread hook) {
          try {
            Runtime.getRuntime().removeShutdownHook(hook);
          } catch (IllegalStateException alreadyShuttingDown) {
            // The hook is running or has run; there is nothing to remove.
          }
        }
      };
    }
  }

  private final HookRegistry registry;
  private final PrintStream err;
  private final long waitMillis;
  private final String stoppingMessage;
  private final String timeoutMessage;
  private final AtomicBoolean cancelRequested = new AtomicBoolean();
  private final CountDownLatch settled = new CountDownLatch(1);
  private final Thread hook;
  private volatile boolean installed;
  private volatile Runnable listener = () -> {};

  /** What an interrupted import says it is doing, and what it says if it cannot. */
  static final String IMPORT_STOPPING =
      "interrupted: finishing the current record and writing a resume point…";

  static final String IMPORT_TIMED_OUT =
      "the import did not stop within %ds; the session's journal will be recovered to its"
          + " last intact frame when it is next opened";

  /**
   * The same, for a launched build. Different wording because a different thing is happening: there
   * is another process to stop, and the session is finalized as cancelled rather than left
   * resumable.
   */
  static final String RUN_STOPPING =
      "interrupted: asking Bazel to stop and finalizing the session…";

  static final String RUN_TIMED_OUT =
      "the build did not stop within %ds; the session is being left as it stands and will"
          + " be recovered when it is next opened";

  private CancellationGuard(
      HookRegistry registry,
      PrintStream err,
      long waitMillis,
      String stoppingMessage,
      String timeoutMessage) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.err = Objects.requireNonNull(err, "err");
    this.waitMillis = waitMillis;
    this.stoppingMessage = Objects.requireNonNull(stoppingMessage, "stoppingMessage");
    this.timeoutMessage = Objects.requireNonNull(timeoutMessage, "timeoutMessage");
    this.hook = new Thread(this::onShutdown, "bbv-cancel");
  }

  /** Installs the hook; close the returned guard to remove it again. */
  static CancellationGuard install(HookRegistry registry, PrintStream err) {
    return install(registry, err, DEFAULT_WAIT_MILLIS);
  }

  static CancellationGuard install(HookRegistry registry, PrintStream err, long waitMillis) {
    return install(registry, err, waitMillis, IMPORT_STOPPING, IMPORT_TIMED_OUT);
  }

  static CancellationGuard install(
      HookRegistry registry,
      PrintStream err,
      long waitMillis,
      String stoppingMessage,
      String timeoutMessage) {
    CancellationGuard guard =
        new CancellationGuard(registry, err, waitMillis, stoppingMessage, timeoutMessage);
    registry.addShutdownHook(guard.hook);
    guard.installed = true;
    return guard;
  }

  /**
   * Called when a stop is requested, in addition to the polled flag.
   *
   * <p>An import polls; a launched build cannot, because it is blocked waiting on another process.
   * So a listener may be registered to push the request onward rather than waiting to be asked.
   */
  void onCancelRequested(Runnable listener) {
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /** Polled by the importer between records. */
  @Override
  public boolean getAsBoolean() {
    return cancelRequested.get();
  }

  /** True when a shutdown actually asked for the stop. */
  boolean wasCancelled() {
    return cancelRequested.get();
  }

  /**
   * Announces that the command has finished its work <em>and</em> written whatever it was going to
   * print. Called from a {@code finally} so that the waiting hook releases the JVM only after the
   * summary has reached the terminal, not merely after the import returned.
   */
  void settled() {
    settled.countDown();
  }

  /** The hook thread, so a test can run it instead of sending a signal. */
  Thread hookThread() {
    return hook;
  }

  @Override
  public void close() {
    if (installed) {
      installed = false;
      registry.removeShutdownHook(hook);
    }
  }

  private void onShutdown() {
    cancelRequested.set(true);
    try {
      listener.run();
    } catch (RuntimeException misbehaving) {
      // A listener that throws must not stop the guard from waiting for
      // the command to come to rest, which is the half that matters.
      err.println("the cancel listener failed: " + misbehaving);
    }
    err.println();
    err.println(stoppingMessage);
    err.flush();
    try {
      if (!settled.await(waitMillis, TimeUnit.MILLISECONDS)) {
        err.println(timeoutMessage.formatted(waitMillis / 1000));
        err.flush();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
