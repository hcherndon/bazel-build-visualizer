package com.holtherndon.bazelviz.ui.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Result;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Review;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AuditLaunchControllerTest {
  @Test
  void canLaunchImmediatelyFromReviewAndClosesBeforeFinishedCallback() throws Exception {
    FakeOperation operation = new FakeOperation();
    AtomicReference<AuditLaunchController> controller = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    controller.set(
        new AuditLaunchController(
            operation,
            Runnable::run,
            new AuditLaunchController.Listener() {
              @Override
              public void reviewReady(Review review) {
                controller.get().launch(review);
              }

              @Override
              public void finished(Result result) {
                if (!operation.closed) failure.set(new AssertionError("cleanup not finished"));
                done.countDown();
              }

              @Override
              public void failed(Throwable value) {
                failure.set(value);
                done.countDown();
              }
            }));
    controller.get().preflight();
    try {
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(operation.runs.get()).isEqualTo(1);
      assertThat(failure.get()).isNull();
      assertThat(controller.get().isBusy()).isFalse();
    } finally {
      controller.get().closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void disposalWaitsForPreflightAndCleanupBeforeReleasingBusyState() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeOperation operation =
        new FakeOperation() {
          @Override
          public Review preflight() throws IOException {
            started.countDown();
            await(release);
            return null;
          }
        };
    SilentListener listener = new SilentListener();
    AuditLaunchController controller =
        new AuditLaunchController(operation, Runnable::run, listener);
    controller.preflight();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    var completion = controller.closeAsync().toCompletableFuture();
    try {
      assertThat(completion.isDone()).isFalse();
      assertThat(controller.isBusy()).isTrue();
      assertThat(operation.cancelled).isTrue();
    } finally {
      release.countDown();
    }
    completion.get(5, TimeUnit.SECONDS);
    assertThat(operation.closed).isTrue();
    assertThat(listener.callbacks.get()).isZero();
  }

  @Test
  void disposalReportsCleanupFailure() {
    FakeOperation operation =
        new FakeOperation() {
          @Override
          public void close() throws IOException {
            throw new IOException("cleanup failed");
          }
        };
    AuditLaunchController controller =
        new AuditLaunchController(operation, Runnable::run, new SilentListener());
    assertThatThrownBy(() -> controller.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(IOException.class);
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException(interrupted);
    }
  }

  private static class FakeOperation implements AuditLaunchController.Operation {
    final AtomicInteger runs = new AtomicInteger();
    volatile boolean cancelled;
    volatile boolean closed;

    @Override
    public Review preflight() throws IOException {
      return null;
    }

    @Override
    public Review replan(UnaryOperator<PlanRequest> change) {
      return null;
    }

    @Override
    public Result run(Review review) {
      runs.incrementAndGet();
      return new Result(
          Path.of("audit"),
          ReproducibilityCoordinator.State.CAPTURED,
          ReproducibilityCoordinator.Cleanup.REMOVED,
          Optional.empty(),
          Optional.empty(),
          List.of());
    }

    @Override
    public void cancel() {
      cancelled = true;
    }

    @Override
    public void close() throws IOException {
      closed = true;
    }
  }

  private static final class SilentListener implements AuditLaunchController.Listener {
    final AtomicInteger callbacks = new AtomicInteger();

    @Override
    public void reviewReady(Review review) {
      callbacks.incrementAndGet();
    }

    @Override
    public void finished(Result result) {
      callbacks.incrementAndGet();
    }

    @Override
    public void failed(Throwable failure) {
      callbacks.incrementAndGet();
    }
  }
}
