package com.holtherndon.bazelviz.ui.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Permanent Query-card teardown, separate from reusable session changes. */
final class QueryViewLifecycleTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("close drains accepted library work without applying late UI callbacks")
  void closeAsyncDrainsLibraryWorker(@TempDir Path temporary) throws Exception {
    ExecutorService libraryWorker = Executors.newSingleThreadExecutor();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    libraryWorker.execute(
        () -> {
          started.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    QueryView view = onEdt(() -> new QueryView(libraryWorker));
    onEdt(
        () -> {
          view.attachLibrary(temporary.resolve("settings"));
          return null;
        });

    long closeStarted = System.nanoTime();
    CompletableFuture<Void> closed = onEdt(() -> view.closeAsync().toCompletableFuture());
    long edtMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted);

    assertThat(edtMillis).isLessThan(2_000L);
    assertThat(closed).isNotDone();
    release.countDown();
    closed.get(10, TimeUnit.SECONDS);
    onEdt(() -> null); // Drain callbacks posted by the final library read.
    assertThat(libraryWorker.isTerminated()).isTrue();
    assertThat(onEdt(() -> view.libraryPanelForTest().viewNamesForTest())).isEmpty();
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call();
    }
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }
}
