package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LaunchControllerTest {

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "test-launch-worker"));

  @AfterEach
  void tearDown() {
    worker.shutdownNow();
  }

  @Test
  @Timeout(10)
  void nonexistentWorkspaceFailsOffEdtBeforeAPlanCanBeReviewed(@TempDir Path temporary)
      throws Exception {
    Path missing = temporary.resolve("missing-workspace");
    AtomicBoolean checkedOnEdt = new AtomicBoolean(true);
    AtomicInteger plans = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean callbackOnEdt = new AtomicBoolean();
    AtomicInteger coordinatorClosed = new AtomicInteger();
    CountDownLatch failed = new CountDownLatch(1);
    LaunchController.Listener listener =
        new LaunchController.Listener() {
          @Override
          public void planReady(Preflight preflight) {
            plans.incrementAndGet();
          }

          @Override
          public void captureStarted(Preflight preflight) {}

          @Override
          public void captureProgress(CaptureProgress progress) {}

          @Override
          public void consoleOutput(
              ConsoleSink.ConsoleStream stream, byte[] data, int offset, int length) {}

          @Override
          public void captureFinished(CaptureResult result) {}

          @Override
          public void captureFailed(Throwable thrown) {
            callbackOnEdt.set(SwingUtilities.isEventDispatchThread());
            failure.set(thrown);
            failed.countDown();
          }
        };
    LaunchController controller =
        new LaunchController(
            worker,
            SwingUtilities::invokeLater,
            listener,
            path -> {
              checkedOnEdt.set(SwingUtilities.isEventDispatchThread());
              return false;
            },
            coordinatorClosed::incrementAndGet);
    CaptureRequest request =
        CaptureRequest.of(
            temporary.resolve("sessions"), "test", "bazel", missing, List.of("build", "//..."));

    SwingUtilities.invokeAndWait(() -> controller.preflight(request));

    assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(checkedOnEdt).isFalse();
    assertThat(callbackOnEdt).isTrue();
    assertThat(plans).hasValue(0);
    assertThat(failure.get())
        .isInstanceOf(IOException.class)
        .hasMessage("the working directory does not exist: " + missing);
    assertThat(controller.isBusy()).isFalse();
    assertThat(coordinatorClosed).hasValue(1);
  }

  @Test
  void queuedFailureDoesNotReachAListenerAfterClose(@TempDir Path temporary) {
    ArrayDeque<Runnable> queuedUi = new ArrayDeque<>();
    AtomicInteger failures = new AtomicInteger();
    LaunchController controller =
        new LaunchController(
            Runnable::run, queuedUi::add, new CountingListener(failures), path -> false);
    CaptureRequest request =
        CaptureRequest.of(
            temporary.resolve("sessions"),
            "test",
            "bazel",
            temporary.resolve("missing"),
            List.of("build", "//..."));

    controller.preflight(request);
    assertThat(queuedUi).hasSize(1);

    controller.close();
    queuedUi.remove().run();

    assertThat(failures).hasValue(0);
    assertThat(controller.isBusy()).isFalse();
  }

  @Test
  void closeReleasesAPendingPreflightOnlyWhenItsWorkerRuns(@TempDir Path temporary) {
    ArrayDeque<Runnable> queuedWorker = new ArrayDeque<>();
    ArrayDeque<Runnable> queuedUi = new ArrayDeque<>();
    AtomicInteger failures = new AtomicInteger();
    LaunchController controller =
        new LaunchController(
            queuedWorker::add,
            queuedUi::add,
            new CountingListener(failures),
            path -> {
              throw new AssertionError("validation must not run after close");
            });
    CaptureRequest request =
        CaptureRequest.of(
            temporary.resolve("sessions"), "test", "bazel", temporary, List.of("build", "//..."));

    controller.preflight(request);
    CompletionStage<Void> closed = controller.closeAsync();

    assertThat(controller.isBusy()).isTrue();
    assertThat(closed.toCompletableFuture()).isNotDone();
    assertThat(queuedWorker).hasSize(2);
    queuedWorker.remove().run();
    assertThat(controller.isBusy()).isFalse();
    assertThat(closed.toCompletableFuture()).isNotDone();
    queuedWorker.remove().run();
    assertThat(closed.toCompletableFuture()).isCompleted();
    assertThat(queuedUi).isEmpty();
    assertThat(failures).hasValue(0);
  }

  @Test
  void closeWaitsForDiscardPlanWorkQueuedAfterActiveWasCleared(@TempDir Path temporary) {
    ArrayDeque<Runnable> queuedWorker = new ArrayDeque<>();
    AtomicInteger coordinatorClosed = new AtomicInteger();
    LaunchController controller =
        new LaunchController(
            queuedWorker::add,
            Runnable::run,
            new CountingListener(new AtomicInteger()),
            path -> true,
            coordinatorClosed::incrementAndGet);
    CaptureRequest request =
        CaptureRequest.of(
            temporary.resolve("sessions"), "test", "bazel", temporary, List.of("build", "//..."));

    controller.preflight(request);
    controller.discardPlan();
    CompletionStage<Void> closed = controller.closeAsync();

    assertThat(controller.isBusy()).isFalse();
    assertThat(closed.toCompletableFuture()).isNotDone();
    assertThat(coordinatorClosed).hasValue(0);
    assertThat(queuedWorker).hasSize(2);

    queuedWorker.remove().run();
    assertThat(closed.toCompletableFuture()).isNotDone();
    assertThat(coordinatorClosed).hasValue(0);

    queuedWorker.remove().run();
    assertThat(closed.toCompletableFuture()).isCompleted();
    assertThat(coordinatorClosed).hasValue(1);
  }

  @Test
  void coordinatorThatLosesTheActiveCasDoesNotRunTheCloseCallback(@TempDir Path temporary) {
    List<Runnable> queuedWorker = new ArrayList<>();
    AtomicInteger coordinatorClosed = new AtomicInteger();
    LaunchController controller =
        new LaunchController(
            queuedWorker::add,
            Runnable::run,
            new CountingListener(new AtomicInteger()),
            path -> true,
            coordinatorClosed::incrementAndGet);
    CaptureRequest request =
        CaptureRequest.of(
            temporary.resolve("sessions"), "test", "bazel", temporary, List.of("build", "//..."));

    controller.preflight(request);
    controller.preflight(request);

    assertThat(queuedWorker).hasSize(2);
    queuedWorker.remove(1).run();
    assertThat(coordinatorClosed).hasValue(0);

    CompletionStage<Void> closed = controller.closeAsync();
    assertThat(queuedWorker).hasSize(2);

    queuedWorker.removeFirst().run();
    assertThat(coordinatorClosed).hasValue(1);
    assertThat(closed.toCompletableFuture()).isNotDone();

    queuedWorker.removeFirst().run();
    assertThat(closed.toCompletableFuture()).isCompleted();
    assertThat(coordinatorClosed).hasValue(1);
  }

  @Test
  void callbackFailureDoesNotBreakPreflightFailureCleanup(@TempDir Path temporary) {
    AtomicInteger coordinatorClosed = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    LaunchController controller =
        new LaunchController(
            Runnable::run,
            Runnable::run,
            new CountingListener(failures),
            path -> false,
            () -> {
              coordinatorClosed.incrementAndGet();
              throw new IllegalStateException("callback failed");
            });
    CaptureRequest request =
        CaptureRequest.of(
            temporary.resolve("sessions"), "test", "bazel", temporary, List.of("build", "//..."));

    controller.preflight(request);

    assertThat(coordinatorClosed).hasValue(1);
    assertThat(failures).hasValue(1);
    assertThat(controller.isBusy()).isFalse();
    assertThat(controller.closeAsync().toCompletableFuture()).isCompleted();
  }

  @Test
  void closingAnIdleControllerCompletesImmediately() {
    LaunchController controller =
        new LaunchController(
            Runnable::run, Runnable::run, new CountingListener(new AtomicInteger()), path -> true);

    assertThat(controller.closeAsync().toCompletableFuture()).isCompleted();
    assertThat(controller.closeAsync().toCompletableFuture()).isCompleted();
  }

  private static final class CountingListener implements LaunchController.Listener {
    private final AtomicInteger failures;

    private CountingListener(AtomicInteger failures) {
      this.failures = failures;
    }

    @Override
    public void planReady(Preflight preflight) {}

    @Override
    public void captureStarted(Preflight preflight) {}

    @Override
    public void captureProgress(CaptureProgress progress) {}

    @Override
    public void consoleOutput(
        ConsoleSink.ConsoleStream stream, byte[] data, int offset, int length) {}

    @Override
    public void captureFinished(CaptureResult result) {}

    @Override
    public void captureFailed(Throwable failure) {
      failures.incrementAndGet();
    }
  }
}
