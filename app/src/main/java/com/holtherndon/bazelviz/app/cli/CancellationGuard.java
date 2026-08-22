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
 * <p>Without this, a SIGINT during an import kills the JVM wherever it happens
 * to be — quite possibly between the journal append and the database write —
 * and leaves a session whose checkpoint is older than its journal. The import
 * pipeline can recover from that, but the operator has no way to know it was
 * meant to be resumable. So the hook does two things: it raises the flag the
 * importer polls between records, and then it <em>waits</em> for the import to
 * come to rest before letting the JVM finish exiting.
 *
 * <p>The wait is the load-bearing half. A shutdown hook that only sets a flag
 * and returns lets the JVM halt immediately, cancelling nothing. The wait is
 * bounded, because a hook that never returns hangs the process: if the import
 * has not reached a record boundary within {@link #DEFAULT_WAIT_MILLIS} the
 * guard says so and gives up, and the session is then recovered by
 * {@code JournalRecovery} on the next open — one level worse than a clean stop,
 * still not a corrupt session.
 *
 * <p>The registry is an interface rather than a direct call to {@link Runtime}
 * so a test can trigger the hook deterministically, in-process, without
 * actually shutting down the test JVM.
 */
final class CancellationGuard implements BooleanSupplier, AutoCloseable {

    /**
     * How long the hook waits for the current record to finish. Generous
     * compared with the cost of one record, short enough that an operator
     * pressing Ctrl-C does not think the tool has hung.
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
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final CountDownLatch settled = new CountDownLatch(1);
    private final Thread hook;
    private volatile boolean installed;

    private CancellationGuard(HookRegistry registry, PrintStream err, long waitMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.err = Objects.requireNonNull(err, "err");
        this.waitMillis = waitMillis;
        this.hook = new Thread(this::onShutdown, "bbv-cancel");
    }

    /** Installs the hook; close the returned guard to remove it again. */
    static CancellationGuard install(HookRegistry registry, PrintStream err) {
        return install(registry, err, DEFAULT_WAIT_MILLIS);
    }

    static CancellationGuard install(HookRegistry registry, PrintStream err, long waitMillis) {
        CancellationGuard guard = new CancellationGuard(registry, err, waitMillis);
        registry.addShutdownHook(guard.hook);
        guard.installed = true;
        return guard;
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
     * Announces that the command has finished its work <em>and</em> written
     * whatever it was going to print. Called from a {@code finally} so that the
     * waiting hook releases the JVM only after the summary has reached the
     * terminal, not merely after the import returned.
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
        err.println();
        err.println("interrupted: finishing the current record and writing a resume point…");
        err.flush();
        try {
            if (!settled.await(waitMillis, TimeUnit.MILLISECONDS)) {
                err.println("the import did not stop within " + (waitMillis / 1000)
                        + "s; the session's journal will be recovered to its last intact frame"
                        + " when it is next opened");
                err.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
