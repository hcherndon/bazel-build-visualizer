package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.capture.live.RemoteExecution;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static final System.Logger log =
            System.getLogger(LaunchController.class.getName());
    private static final Runnable NO_OP = () -> { };

    /** What the UI is told, always on the UI thread. */
    public interface Listener {

        /** A successful SSH preflight produced a reusable live workspace. */
        default void remoteConnected(RemoteExecution remote) { }

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
    private final Runnable afterCoordinatorClosed;
    private final AtomicReference<AcceptedCoordinator> active = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger pendingCoordinatorCleanup = new AtomicInteger();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    public LaunchController(Executor worker, Executor toUi, Listener listener) {
        this(worker, toUi, listener, Files::isDirectory, NO_OP);
    }

    /**
     * Creates a controller with a callback for releasing operation-scoped application resources.
     *
     * <p>The callback runs once for each coordinator accepted by {@link #preflight}, after that
     * coordinator has closed. It runs on the capture worker and must not update Swing directly.
     */
    public LaunchController(
            Executor worker,
            Executor toUi,
            Listener listener,
            Runnable afterCoordinatorClosed) {
        this(worker, toUi, listener, Files::isDirectory, afterCoordinatorClosed);
    }

    LaunchController(
            Executor worker,
            Executor toUi,
            Listener listener,
            Predicate<Path> directoryExists) {
        this(worker, toUi, listener, directoryExists, NO_OP);
    }

    LaunchController(
            Executor worker,
            Executor toUi,
            Listener listener,
            Predicate<Path> directoryExists,
            Runnable afterCoordinatorClosed) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.toUi = Objects.requireNonNull(toUi, "toUi");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.directoryExists = Objects.requireNonNull(directoryExists, "directoryExists");
        this.afterCoordinatorClosed = Objects.requireNonNull(
                afterCoordinatorClosed, "afterCoordinatorClosed");
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
        if (closed.get()) {
            return;
        }
        CaptureCoordinator coordinator = new CaptureCoordinator(request.withConsole(consoleSink())
                .withProgress(progress -> postToUi(() -> listener.captureProgress(progress))));
        AcceptedCoordinator accepted = new AcceptedCoordinator(coordinator);
        if (!active.compareAndSet(null, accepted)) {
            scheduleClose(coordinator);
            failOnUi(new IllegalStateException("a capture is already in progress"));
            return;
        }
        worker.execute(() -> {
            try {
                if (closed.get()) {
                    discard(accepted);
                    return;
                }
                if (!request.isRemote()
                        && !directoryExists.test(request.localWorkingDirectory())) {
                    throw new IOException(
                            "the working directory does not exist: " + request.workingDirectory());
                }
                Preflight preflight = coordinator.preflight();
                Optional<RemoteExecution> remote = coordinator.detachRemoteExecution();
                if (remote.isPresent()) {
                    postRemoteReady(remote.orElseThrow(), preflight);
                } else {
                    postToUi(() -> listener.planReady(preflight));
                }
            } catch (IOException | RuntimeException failure) {
                discard(accepted);
                failOnUi(failure);
            }
        });
    }

    /** Re-plans with the user's answer to a conflict, and reports the new plan. */
    public void resolve(PlanConflict.Kind kind, String resolutionId) {
        if (closed.get()) {
            return;
        }
        AcceptedCoordinator accepted = active.get();
        if (accepted == null) {
            return;
        }
        CaptureCoordinator coordinator = accepted.coordinator;
        worker.execute(() -> {
            try {
                if (closed.get()) {
                    discard(accepted);
                    return;
                }
                Preflight replanned = coordinator.replan(
                        request -> request.resolving(kind, resolutionId));
                postToUi(() -> listener.planReady(replanned));
            } catch (IOException | RuntimeException failure) {
                discard(accepted);
                failOnUi(failure);
            }
        });
    }

    /** Applies an arbitrary adjustment to the plan — a veto, an overwrite. */
    public void replan(java.util.function.UnaryOperator<PlanRequest> adjust) {
        if (closed.get()) {
            return;
        }
        AcceptedCoordinator accepted = active.get();
        if (accepted == null) {
            return;
        }
        CaptureCoordinator coordinator = accepted.coordinator;
        worker.execute(() -> {
            try {
                if (closed.get()) {
                    discard(accepted);
                    return;
                }
                Preflight replanned = coordinator.replan(adjust);
                postToUi(() -> listener.planReady(replanned));
            } catch (IOException | RuntimeException failure) {
                discard(accepted);
                failOnUi(failure);
            }
        });
    }

    /** Launches the planned build. Does nothing when no plan is pending. */
    public void launch() {
        if (closed.get()) {
            return;
        }
        AcceptedCoordinator accepted = active.get();
        if (accepted == null) {
            return;
        }
        CaptureCoordinator coordinator = accepted.coordinator;
        running.set(true);
        worker.execute(() -> {
            try {
                if (closed.get()) {
                    discard(accepted);
                    return;
                }
                Preflight preflight = coordinator.preflight();
                postToUi(() -> listener.captureStarted(preflight));
                CaptureResult result = coordinator.run();
                postToUi(() -> listener.captureFinished(result));
            } catch (IOException | RuntimeException failure) {
                failOnUi(failure);
            } finally {
                running.set(false);
                discard(accepted);
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
        AcceptedCoordinator accepted = active.get();
        if (accepted != null) {
            accepted.coordinator.cancel(mode);
        }
    }

    /** Abandons a plan that was never launched, releasing the BES port. */
    public void discardPlan() {
        AcceptedCoordinator accepted = active.get();
        if (accepted == null) {
            return;
        }
        beginCoordinatorCleanup();
        if (!active.compareAndSet(accepted, null)) {
            finishCoordinatorCleanup();
            return;
        }
        try {
            worker.execute(() -> closeAcceptedReserved(accepted));
        } catch (RuntimeException rejected) {
            finishCoordinatorCleanup();
            throw rejected;
        }
    }

    /**
     * Stops callbacks for a disposed window and releases capture resources on
     * the capture worker. A running build is asked to cancel and still gets to
     * finalize its journal; a pending preflight or plan is closed on the worker.
     */
    public void close() {
        closeAsync();
    }

    /**
     * Stops callbacks and completes after every accepted coordinator cleanup has finished.
     *
     * <p>The returned stage never waits on the caller. A running capture still follows its
     * cancellation and raw-data finalization path on the capture worker; a pending preflight or
     * review plan is discarded on that same worker. Cleanup already queued by {@link
     * #discardPlan()} is included even though that method clears the active slot first. This lets
     * a workspace window keep its SSH execution alive until capture-scoped tunnels and staging
     * resources are actually finished.
     */
    public CompletionStage<Void> closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return closeCompletion;
        }
        AcceptedCoordinator accepted = active.get();
        if (accepted == null) {
            completeCloseIfIdle();
            return closeCompletion;
        }
        if (running.get()) {
            accepted.coordinator.cancel(CancellationMode.CANCEL);
        } else {
            try {
                scheduleDiscard(accepted);
            } catch (RuntimeException rejected) {
                closeCompletion.completeExceptionally(rejected);
            }
        }
        return closeCompletion;
    }

    public boolean isBusy() {
        return active.get() != null;
    }

    /** The running capture, for a status display. */
    public Optional<CaptureCoordinator> current() {
        return Optional.ofNullable(active.get()).map(value -> value.coordinator);
    }

    private ConsoleSink consoleSink() {
        return (stream, data, offset, length) -> {
            // Copied before the hop: the pump thread reuses its buffer as soon
            // as this returns, so posting the array itself would show whatever
            // the next read happened to put there.
            byte[] copy = java.util.Arrays.copyOfRange(data, offset, offset + length);
            postToUi(() -> listener.consoleOutput(stream, copy, 0, copy.length));
        };
    }

    private void discard(AcceptedCoordinator accepted) {
        beginCoordinatorCleanup();
        discardReserved(accepted);
    }

    private void scheduleDiscard(AcceptedCoordinator accepted) {
        beginCoordinatorCleanup();
        try {
            worker.execute(() -> discardReserved(accepted));
        } catch (RuntimeException rejected) {
            finishCoordinatorCleanup();
            throw rejected;
        }
    }

    private void discardReserved(AcceptedCoordinator accepted) {
        try {
            if (active.compareAndSet(accepted, null)) {
                closeAccepted(accepted);
            }
        } finally {
            finishCoordinatorCleanup();
        }
    }

    private void scheduleClose(CaptureCoordinator coordinator) {
        beginCoordinatorCleanup();
        try {
            worker.execute(() -> closeUnacceptedReserved(coordinator));
        } catch (RuntimeException rejected) {
            finishCoordinatorCleanup();
            throw rejected;
        }
    }

    private void closeAcceptedReserved(AcceptedCoordinator accepted) {
        try {
            closeAccepted(accepted);
        } finally {
            finishCoordinatorCleanup();
        }
    }

    private void closeUnacceptedReserved(CaptureCoordinator coordinator) {
        try {
            coordinator.close();
        } finally {
            finishCoordinatorCleanup();
        }
    }

    private void closeAccepted(AcceptedCoordinator accepted) {
        try {
            accepted.coordinator.close();
        } finally {
            notifyCoordinatorClosed(accepted);
        }
    }

    private void notifyCoordinatorClosed(AcceptedCoordinator accepted) {
        if (!accepted.callbackDelivered.compareAndSet(false, true)) {
            return;
        }
        try {
            afterCoordinatorClosed.run();
        } catch (RuntimeException callbackFailure) {
            log.log(
                    System.Logger.Level.WARNING,
                    "Capture coordinator close callback failed",
                    callbackFailure);
        }
    }

    private void beginCoordinatorCleanup() {
        pendingCoordinatorCleanup.incrementAndGet();
    }

    private void finishCoordinatorCleanup() {
        int remaining = pendingCoordinatorCleanup.decrementAndGet();
        if (remaining < 0) {
            throw new IllegalStateException("capture cleanup accounting became negative");
        }
        completeCloseIfIdle();
    }

    private void completeCloseIfIdle() {
        if (closed.get() && active.get() == null && pendingCoordinatorCleanup.get() == 0) {
            closeCompletion.complete(null);
        }
    }

    private void failOnUi(Throwable failure) {
        postToUi(() -> listener.captureFailed(failure));
    }

    private void postRemoteReady(RemoteExecution remote, Preflight preflight) {
        try {
            toUi.execute(() -> {
                if (closed.get()) {
                    remote.close();
                    return;
                }
                boolean transferredToUi = false;
                try {
                    listener.remoteConnected(remote);
                    transferredToUi = true;
                    listener.planReady(preflight);
                } catch (RuntimeException failure) {
                    // Once remoteConnected returns, the window owns this connection. A later
                    // plan-rendering failure must not leave that window pointing at a closed
                    // repository/terminal session.
                    if (!transferredToUi) {
                        remote.close();
                    }
                    listener.captureFailed(failure);
                }
            });
        } catch (RuntimeException rejected) {
            remote.close();
            throw rejected;
        }
    }

    private void postToUi(Runnable callback) {
        if (closed.get()) {
            return;
        }
        toUi.execute(() -> {
            if (!closed.get()) {
                callback.run();
            }
        });
    }

    private static final class AcceptedCoordinator {
        private final CaptureCoordinator coordinator;
        private final AtomicBoolean callbackDelivered = new AtomicBoolean();

        private AcceptedCoordinator(CaptureCoordinator coordinator) {
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        }
    }
}
