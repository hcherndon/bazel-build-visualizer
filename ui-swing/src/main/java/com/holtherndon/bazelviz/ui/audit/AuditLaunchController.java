package com.holtherndon.bazelviz.ui.audit;

import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Result;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Review;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/** Serializes audit planning, execution and cleanup; all blocking work stays off the EDT. */
public final class AuditLaunchController {
  public interface Listener {
    void reviewReady(Review review);

    void finished(Result result);

    void failed(Throwable failure);
  }

  interface Operation extends AutoCloseable {
    Review preflight() throws IOException;

    Review replan(UnaryOperator<PlanRequest> change) throws IOException;

    Result run(Review review) throws IOException;

    void cancel();

    @Override
    void close() throws IOException;
  }

  private final Operation operation;
  private final Executor toUi;
  private final Listener listener;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().name("bbv-repro-audit").factory());
  private final AtomicBoolean pending = new AtomicBoolean();
  private final AtomicBoolean disposed = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final CompletableFuture<Void> completion = new CompletableFuture<>();

  public AuditLaunchController(
      ReproducibilityCoordinator coordinator, Executor toUi, Listener listener) {
    this(
        new Operation() {
          @Override
          public Review preflight() throws IOException {
            return coordinator.preflight();
          }

          @Override
          public Review replan(UnaryOperator<PlanRequest> change) throws IOException {
            return coordinator.replan(change);
          }

          @Override
          public Result run(Review review) throws IOException {
            return coordinator.run(review);
          }

          @Override
          public void cancel() {
            coordinator.cancel();
          }

          @Override
          public void close() throws IOException {
            coordinator.close();
          }
        },
        toUi,
        listener);
  }

  AuditLaunchController(Operation operation, Executor toUi, Listener listener) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.toUi = Objects.requireNonNull(toUi, "toUi");
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  public void preflight() {
    review(operation::preflight);
  }

  public void replan(UnaryOperator<PlanRequest> change) {
    review(() -> operation.replan(change));
  }

  private void review(ReviewTask task) {
    submit(
        () -> {
          try {
            if (disposed.get()) return;
            Review review = task.run();
            pending.set(false);
            post(() -> listener.reviewReady(review));
          } catch (IOException | RuntimeException failure) {
            finish(failure);
          }
        });
  }

  public void launch(Review review) {
    submit(
        () -> {
          try {
            if (disposed.get()) return;
            Result result = operation.run(review);
            if (finish(null)) post(() -> listener.finished(result));
          } catch (IOException | RuntimeException failure) {
            finish(failure);
          } finally {
            pending.set(false);
          }
        });
  }

  /** Signals the active build; it does not interrupt evidence preservation or replay commands. */
  public void cancel() {
    operation.cancel();
  }

  public boolean isBusy() {
    return !completion.isDone();
  }

  /** Completes only after queued work and coordinator cleanup release the workspace lease. */
  public CompletionStage<Void> closeAsync() {
    synchronized (worker) {
      if (disposed.compareAndSet(false, true)) {
        operation.cancel();
        if (!finished.get()) {
          try {
            worker.execute(() -> finish(null));
          } catch (RejectedExecutionException rejected) {
            if (!finished.get()) completion.completeExceptionally(rejected);
          }
        }
      }
    }
    return completion;
  }

  private void submit(Runnable task) {
    // Admission and enqueue are atomic relative to disposal. No I/O runs under this monitor.
    synchronized (worker) {
      if (!disposed.get() && !finished.get() && pending.compareAndSet(false, true)) {
        worker.execute(task);
      }
    }
  }

  private boolean finish(Throwable failure) {
    if (!finished.compareAndSet(false, true)) return false;
    try {
      operation.close();
    } catch (IOException | RuntimeException cleanupFailure) {
      if (failure == null) failure = cleanupFailure;
      else failure.addSuppressed(cleanupFailure);
    } finally {
      worker.shutdown();
    }
    if (failure == null) {
      completion.complete(null);
      return true;
    }
    Throwable reported = failure;
    post(() -> listener.failed(reported));
    completion.completeExceptionally(failure);
    return false;
  }

  private void post(Runnable callback) {
    toUi.execute(
        () -> {
          if (!disposed.get()) callback.run();
        });
  }

  @FunctionalInterface
  private interface ReviewTask {
    Review run() throws IOException;
  }
}
