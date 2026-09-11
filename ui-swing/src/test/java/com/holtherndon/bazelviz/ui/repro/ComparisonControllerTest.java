package com.holtherndon.bazelviz.ui.repro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ComparisonControllerTest {
  @Test
  void openingQueriesAndCleanupStayOnOneWorker() throws Exception {
    FakeComparison source = new FakeComparison();
    Listener listener = new Listener();
    ComparisonController controller =
        new ComparisonController(
            cancelled -> {
              source.owner = Thread.currentThread();
              return source;
            },
            Runnable::run,
            listener);
    assertTrue(listener.opened.await(5, TimeUnit.SECONDS));
    controller.page(FilterExpression.ALL, 0, 20);
    assertTrue(listener.paged.await(5, TimeUnit.SECONDS));
    controller.details(1, 0, 20);
    assertTrue(listener.detailed.await(5, TimeUnit.SECONDS));
    controller.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertTrue(source.closed);
    assertEquals(1, source.pages.get());
    assertFalse(source.wrongThread);
    assertEquals(null, listener.failure.get());
  }

  @Test
  void openingKeepsOnlyLatestNavigationRequest() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeComparison source = new FakeComparison();
    Listener listener = new Listener();
    ComparisonController controller =
        new ComparisonController(
            cancelled -> {
              source.owner = Thread.currentThread();
              await(release);
              return source;
            },
            Runnable::run,
            listener);
    try {
      for (int i = 1; i <= 100; i++) controller.page(FilterExpression.ALL, i, 20);
      release.countDown();
      assertTrue(listener.paged.await(5, TimeUnit.SECONDS));
      assertEquals(100, source.after);
      assertEquals(1, source.pages.get());
    } finally {
      release.countDown();
      controller.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void closeWaitsForPrivateSourceCleanup() throws Exception {
    CountDownLatch closing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeComparison source =
        new FakeComparison() {
          @Override
          public void close() {
            closing.countDown();
            boolean interrupted = Thread.interrupted();
            try {
              await(release);
            } catch (IOException failure) {
              throw new IllegalStateException(failure);
            } finally {
              if (interrupted) Thread.currentThread().interrupt();
            }
            super.close();
          }
        };
    Listener listener = new Listener();
    ComparisonController controller =
        new ComparisonController(
            cancelled -> {
              source.owner = Thread.currentThread();
              return source;
            },
            Runnable::run,
            listener);
    assertTrue(listener.opened.await(5, TimeUnit.SECONDS));
    var completion = controller.closeAsync().toCompletableFuture();
    try {
      assertTrue(closing.await(5, TimeUnit.SECONDS));
      assertFalse(completion.isDone());
    } finally {
      release.countDown();
    }
    completion.get(5, TimeUnit.SECONDS);
    assertTrue(source.closed);
  }

  private static void await(CountDownLatch latch) throws IOException {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", interrupted);
    }
  }

  @Test
  void cancellationDoesNotHideCleanupFailure() throws Exception {
    FakeComparison source =
        new FakeComparison() {
          @Override
          public void close() {
            throw new IllegalStateException("Private comparison cleanup failed");
          }
        };
    Listener listener = new Listener();
    ComparisonController controller =
        new ComparisonController(
            cancelled -> {
              source.owner = Thread.currentThread();
              return source;
            },
            Runnable::run,
            listener);
    assertTrue(listener.opened.await(5, TimeUnit.SECONDS));
    ExecutionException failure =
        assertThrows(
            ExecutionException.class,
            () -> controller.closeAsync().toCompletableFuture().get(5, TimeUnit.SECONDS));
    assertEquals("Private comparison cleanup failed", failure.getCause().getMessage());
  }

  @Test
  void cancelledOpeningPreservesSuppressedRollbackFailure() throws Exception {
    IOException openingFailure = new IOException("Opening cancelled");
    openingFailure.addSuppressed(new IOException("Private snapshot removal failed"));
    assertCancelledOpening(openingFailure, true);
  }

  @Test
  void cancelledOpeningPreservesRollbackFailureOnWrappedCause() throws Exception {
    IOException databaseFailure = new IOException("Database interrupted");
    databaseFailure.addSuppressed(new IOException("Private database removal failed"));
    assertCancelledOpening(new IOException("Comparison stopped", databaseFailure), true);
  }

  @Test
  void ordinaryCancelledOpeningRemainsQuietAndClosesNormally() throws Exception {
    assertCancelledOpening(new IOException("Opening cancelled"), false);
  }

  private static void assertCancelledOpening(IOException openingFailure, boolean cleanupFailed)
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Listener listener = new Listener();
    ComparisonController controller =
        new ComparisonController(
            cancelled -> {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              // Model a factory that rolls back its partially created private files on cancel.
              throw openingFailure;
            },
            Runnable::run,
            listener);
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var completion = controller.closeAsync().toCompletableFuture();
      if (cleanupFailed) {
        ExecutionException failure =
            assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS));
        assertSame(openingFailure, failure.getCause());
      } else completion.get(5, TimeUnit.SECONDS);
      assertEquals(null, listener.failure.get());
      assertEquals(1, listener.opened.getCount());
      assertEquals(1, listener.paged.getCount());
      assertEquals(1, listener.detailed.getCount());
    } finally {
      release.countDown();
      controller.closeAsync();
    }
  }

  private static class FakeComparison implements ReproComparison {
    volatile Thread owner;
    volatile boolean wrongThread;
    volatile boolean closed;
    volatile long after;
    final AtomicInteger pages = new AtomicInteger();

    private void checkThread() {
      wrongThread |= owner != Thread.currentThread();
    }

    @Override
    public Summary summary() {
      checkThread();
      return new Summary(1, 1, 1, 1, 0, 0, 0, 0, List.of("Not proof of hermeticity"));
    }

    @Override
    public Page page(FilterExpression filter, long afterId, int limit) {
      checkThread();
      after = afterId;
      pages.incrementAndGet();
      return new Page(List.of(), 0);
    }

    @Override
    public Details details(long id, long offset, int limit) {
      checkThread();
      return new Details(
          new Row(1, "//:a", "Genrule", "a", Finding.UNCHANGED, false, false, false, ""),
          List.of(),
          0,
          false);
    }

    @Override
    public void close() {
      checkThread();
      closed = true;
    }
  }

  private static final class Listener implements ComparisonController.Listener {
    final CountDownLatch opened = new CountDownLatch(1);
    final CountDownLatch paged = new CountDownLatch(1);
    final CountDownLatch detailed = new CountDownLatch(1);
    final AtomicReference<String> failure = new AtomicReference<>();

    @Override
    public void opened(ReproComparison.Summary summary) {
      opened.countDown();
    }

    @Override
    public void pageLoaded(ReproComparison.Page page, long afterId) {
      paged.countDown();
    }

    @Override
    public void detailsLoaded(ReproComparison.Details details, long offset) {
      detailed.countDown();
    }

    @Override
    public void failed(String message) {
      failure.set(message);
    }
  }
}
