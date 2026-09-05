package com.holtherndon.bazelviz.ui.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Delivery races at the boundary between the metrics worker and the UI thread. */
final class MetricsServiceTest {

  @TempDir Path tempDir;

  private SessionDatabase database;

  @BeforeEach
  void createSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
  }

  @AfterEach
  void closeSession() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("closing and replacing a service drops a result already queued for the UI")
  void queuedResultFromClosedSessionIsNotDelivered() throws Exception {
    BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
    AtomicInteger completions = new AtomicInteger();
    AtomicInteger listenerCalls = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();

    Runnable stale;
    try (MetricsService closing = service(uiQueue)) {
      closing.addListener(result -> listenerCalls.incrementAndGet());
      closing.collect(
          result -> completions.incrementAndGet(), failure -> failures.incrementAndGet());
      stale = uiQueue.poll(10, TimeUnit.SECONDS);
      assertThat(stale).as("the old session's queued UI delivery").isNotNull();
    }

    // close() increments the generation after the worker has already
    // handed this Runnable to the UI queue. Running it now models the EDT
    // reaching the old callback after the window opened another session.
    stale.run();
    assertThat(completions).hasValue(0);
    assertThat(listenerCalls).hasValue(0);
    assertThat(failures).hasValue(0);

    try (MetricsService replacement = service(uiQueue)) {
      replacement.addListener(result -> listenerCalls.incrementAndGet());
      replacement.collect(
          result -> completions.incrementAndGet(), failure -> failures.incrementAndGet());
      Runnable current = uiQueue.poll(10, TimeUnit.SECONDS);
      assertThat(current).as("the replacement session's UI delivery").isNotNull();
      current.run();
    }

    assertThat(completions).hasValue(1);
    assertThat(listenerCalls).hasValue(1);
    assertThat(failures).hasValue(0);
  }

  @Test
  @DisplayName("a metric read failure reaches both the collector and passive view listeners")
  void failuresReachErrorListeners() throws Exception {
    BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
    AtomicInteger results = new AtomicInteger();
    AtomicReference<Throwable> directFailure = new AtomicReference<>();
    AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
    IllegalStateException expected = new IllegalStateException("metrics unavailable");
    SessionSource failingSource =
        new MetricOnlySource() {
          @Override
          public MetricQueries openMetricQueries() {
            throw expected;
          }
        };

    try (MetricsService service =
        new MetricsService(failingSource, uiQueue::add, FindingThresholds.defaults())) {
      service.addListener(result -> results.incrementAndGet());
      service.addErrorListener(listenerFailure::set);
      service.collect(result -> results.incrementAndGet(), directFailure::set);

      Runnable delivery = uiQueue.poll(10, TimeUnit.SECONDS);
      assertThat(delivery).as("the queued UI-thread failure delivery").isNotNull();
      delivery.run();
    }

    assertThat(results).hasValue(0);
    assertThat(directFailure).hasValue(expected);
    assertThat(listenerFailure).hasValue(expected);
  }

  @Test
  @DisplayName("cancel immediately invalidates UI deliveries while close waits off-thread")
  void cancelDropsQueuedListenersBeforeDeferredClose() throws Exception {
    BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
    AtomicInteger completions = new AtomicInteger();
    AtomicInteger listeners = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    MetricsService service = service(uiQueue);
    try {
      service.addListener(result -> listeners.incrementAndGet());
      service.collect(
          result -> completions.incrementAndGet(), failure -> failures.incrementAndGet());
      Runnable queued = uiQueue.poll(10, TimeUnit.SECONDS);
      assertThat(queued).as("the completed scan queued for the UI thread").isNotNull();

      // MainWindow can call cancel on the EDT before it schedules the
      // potentially blocking close on its I/O executor. A queued result
      // must already be stale during that interval.
      service.cancel();
      queued.run();

      assertThat(completions).hasValue(0);
      assertThat(listeners).hasValue(0);
      assertThat(failures).hasValue(0);
    } finally {
      service.close();
    }
  }

  @Test
  @DisplayName("close stays pending until an interrupted metric reader releases the session")
  void closeFencesActiveMetricReader() throws Exception {
    CountDownLatch readerEntered = new CountDownLatch(1);
    CountDownLatch readerInterrupted = new CountDownLatch(1);
    CountDownLatch releaseReader = new CountDownLatch(1);
    MetricOnlySource blockingSource =
        new MetricOnlySource() {
          @Override
          public MetricQueries openMetricQueries() {
            readerEntered.countDown();
            while (releaseReader.getCount() != 0) {
              try {
                releaseReader.await();
              } catch (InterruptedException cancelled) {
                readerInterrupted.countDown();
              }
            }
            return super.openMetricQueries();
          }
        };
    MetricsService service =
        new MetricsService(blockingSource, Runnable::run, FindingThresholds.defaults());
    try {
      service.collect(ignored -> {}, ignored -> {});
      assertThat(readerEntered.await(10, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<Void> closing = service.closeAsync().toCompletableFuture();
      assertThat(readerInterrupted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(closing).isNotDone();

      releaseReader.countDown();
      closing.get(10, TimeUnit.SECONDS);
      assertThat(closing).isCompleted();
    } finally {
      releaseReader.countDown();
      service.close();
    }
  }

  private MetricsService service(BlockingQueue<Runnable> uiQueue) {
    return new MetricsService(new MetricOnlySource(), uiQueue::add, FindingThresholds.defaults());
  }

  /** A session source exposing only a fresh metrics connection per collection. */
  private class MetricOnlySource implements SessionSource {

    @Override
    public MetricQueries openMetricQueries() {
      try {
        return new MetricQueries(database.newReadConnection());
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public SessionInfo info() {
      throw unused();
    }

    @Override
    public SessionReader openReader() {
      throw unused();
    }

    @Override
    public EntityReader openEntityReader() {
      throw unused();
    }

    @Override
    public GraphQueries openGraphQueries() {
      throw unused();
    }

    @Override
    public QueryReader openQueryReader() {
      throw unused();
    }

    @Override
    public Connection openTimelineConnection() {
      throw unused();
    }

    @Override
    public void close() {
      // Readers own their connections; the fixture owns the database.
    }

    private UnsupportedOperationException unused() {
      return new UnsupportedOperationException("the metrics service asks only for metrics");
    }
  }
}
