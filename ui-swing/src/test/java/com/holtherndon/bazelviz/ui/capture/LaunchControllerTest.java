package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import java.nio.file.Path;
import java.util.List;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "test-launch-worker"));

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
        CountDownLatch failed = new CountDownLatch(1);
        LaunchController.Listener listener = new LaunchController.Listener() {
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
        LaunchController controller = new LaunchController(
                worker,
                SwingUtilities::invokeLater,
                listener,
                path -> {
                    checkedOnEdt.set(SwingUtilities.isEventDispatchThread());
                    return false;
                });
        CaptureRequest request = CaptureRequest.of(
                temporary.resolve("sessions"), "test", "bazel", missing, List.of("build", "//..."));

        SwingUtilities.invokeAndWait(() -> controller.preflight(request));

        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(checkedOnEdt).isFalse();
        assertThat(callbackOnEdt).isTrue();
        assertThat(plans).hasValue(0);
        assertThat(failure.get())
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("the working directory does not exist: " + missing);
        assertThat(controller.isBusy()).isFalse();
    }
}
