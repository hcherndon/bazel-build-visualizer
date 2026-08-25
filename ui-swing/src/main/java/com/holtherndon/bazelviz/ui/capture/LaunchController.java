package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Drives a capture from the UI without ever touching a disk, a socket or a
 * subprocess on the EDT.
 *
 * <h2>The thread rule</h2>
 *
 * <p>Everything the coordinator does blocks: probing runs a subprocess,
 * planning reads the filesystem, the run itself waits for a build. All of it
 * happens on the supplied {@code worker}. Every callback into the listener is
 * posted back through {@code toUi}, so a listener can update Swing directly and
 * never has to think about which thread it is on (plan 19.1, project rule 8).
 *
 * <p>Console bytes are the one thing that arrives at full speed, and they are
 * posted as-is rather than coalesced here: coalescing is the console model's
 * job, and doing it on the pump thread would put the decision about what to
 * show on the thread that must not block.
 */
public final class LaunchController {

    /** What the UI is told, always on the UI thread. */
    public interface Listener {

        /** Preflight finished and the plan is ready to show. */
        void planReady(Preflight preflight);

        /** The build has started. */
        void captureStarted(Preflight preflight);

        /** New counters. Throttled by the pipeline, not by this. */
        void captureProgress(com.holtherndon.bazelviz.capture.live.CaptureProgress progress);

        /** Console bytes from the build. The array is not retained by the caller. */
        void consoleOutput(ConsoleSink.ConsoleStream stream, byte[] data, int offset, int length);

        /** The capture finished, however it finished. */
        void captureFinished(CaptureResult result);

        /** Something went wrong before or instead of a capture. */
        void captureFailed(Throwable failure);
    }

    private final Executor worker;
    private final Executor toUi;
    private final Listener listener;
    private final Predicate<Path> directoryExists;
    private final AtomicReference<CaptureCoordinator> active = new AtomicReference<>();

    public LaunchController(Executor worker, Executor toUi, Listener listener) {
        this(worker, toUi, listener, Files::isDirectory);
    }

    LaunchController(
            Executor worker,
            Executor toUi,
            Listener listener,
            Predicate<Path> directoryExists) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.toUi = Objects.requireNonNull(toUi, "toUi");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.directoryExists = Objects.requireNonNull(directoryExists, "directoryExists");
    }

    /**
     * Resolves and plans, then reports the plan.
     *
     * <p>Stops there. Launching is a separate call because the user has to see
     * the plan first (ADR-007), and because a plan with an unresolved mandatory
     * conflict must not be able to slide into a launch by accident.
     */
    public void preflight(CaptureRequest request) {
        Objects.requireNonNull(request, "request");
        if (active.get() != null) {
            failOnUi(new IllegalStateException("a capture is already in progress"));
            return;
        }
        CaptureCoordinator coordinator = new CaptureCoordinator(request.withConsole(consoleSink())
                .withProgress(progress -> toUi.execute(() -> listener.captureProgress(progress))));
        if (!active.compareAndSet(null, coordinator)) {
            coordinator.close();
            failOnUi(new IllegalStateException("a capture is already in progress"));
            return;
        }
        worker.execute(() -> {
            try {
                if (!directoryExists.test(request.workingDirectory())) {
                    throw new IOException(
                            "the working directory does not exist: " + request.workingDirectory());
                }
                Preflight preflight = coordinator.preflight();
                toUi.execute(() -> listener.planReady(preflight));
            } catch (IOException | RuntimeException failure) {
                discard(coordinator);
                failOnUi(failure);
            }
        });
    }

    /** Re-plans with the user's answer to a conflict, and reports the new plan. */
    public void resolve(PlanConflict.Kind kind, String resolutionId) {
        CaptureCoordinator coordinator = active.get();
        if (coordinator == null) {
            return;
        }
        worker.execute(() -> {
            try {
                Preflight replanned = coordinator.replan(
                        request -> request.resolving(kind, resolutionId));
                toUi.execute(() -> listener.planReady(replanned));
            } catch (IOException | RuntimeException failure) {
                discard(coordinator);
                failOnUi(failure);
            }
        });
    }

    /** Applies an arbitrary adjustment to the plan — a veto, an overwrite. */
    public void replan(java.util.function.UnaryOperator<PlanRequest> adjust) {
        CaptureCoordinator coordinator = active.get();
        if (coordinator == null) {
            return;
        }
        worker.execute(() -> {
            try {
                toUi.execute(() -> {
                    try {
                        listener.planReady(coordinator.replan(adjust));
                    } catch (IOException failure) {
                        listener.captureFailed(failure);
                    }
                });
            } catch (RuntimeException failure) {
                failOnUi(failure);
            }
        });
    }

    /** Launches the planned build. Does nothing when no plan is pending. */
    public void launch() {
        CaptureCoordinator coordinator = active.get();
        if (coordinator == null) {
            return;
        }
        worker.execute(() -> {
            try {
                Preflight preflight = coordinator.preflight();
                toUi.execute(() -> listener.captureStarted(preflight));
                CaptureResult result = coordinator.run();
                toUi.execute(() -> listener.captureFinished(result));
            } catch (IOException | RuntimeException failure) {
                failOnUi(failure);
            } finally {
                discard(coordinator);
            }
        });
    }

    /**
     * Stops a running build. Safe on the EDT: it signals and returns.
     *
     * <p>The capture then finalizes on its own and the listener is told through
     * {@link Listener#captureFinished}, with the session marked cancelled. There
     * is no separate "cancelled" callback, because a cancelled capture is still
     * a capture and still produces a session worth opening.
     */
    public void cancel(CancellationMode mode) {
        CaptureCoordinator coordinator = active.get();
        if (coordinator != null) {
            coordinator.cancel(mode);
        }
    }

    /** Abandons a plan that was never launched, releasing the BES port. */
    public void discardPlan() {
        CaptureCoordinator coordinator = active.getAndSet(null);
        if (coordinator != null) {
            coordinator.close();
        }
    }

    public boolean isBusy() {
        return active.get() != null;
    }

    /** The running capture, for a status display. */
    public Optional<CaptureCoordinator> current() {
        return Optional.ofNullable(active.get());
    }

    private ConsoleSink consoleSink() {
        return (stream, data, offset, length) -> {
            // Copied before the hop: the pump thread reuses its buffer as soon
            // as this returns, so posting the array itself would show whatever
            // the next read happened to put there.
            byte[] copy = java.util.Arrays.copyOfRange(data, offset, offset + length);
            toUi.execute(() -> listener.consoleOutput(stream, copy, 0, copy.length));
        };
    }

    private void discard(CaptureCoordinator coordinator) {
        if (active.compareAndSet(coordinator, null)) {
            coordinator.close();
        }
    }

    private void failOnUi(Throwable failure) {
        toUi.execute(() -> listener.captureFailed(failure));
    }
}
