package com.holtherndon.bazelviz.ui.metrics;

import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.FindingRules;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * The one place a metric collection runs, on one background thread.
 *
 * <h2>Once per session, not on a timer</h2>
 *
 * <p>The overview refreshes every two seconds because its numbers are counts over indexed tables.
 * This is not that: it scans every action, sweeps every span and runs thirteen rules, which at Tier
 * 3 is seconds of work. Putting it on the overview's timer would make a large session unusable
 * while telling the user nothing new — the metrics of a finished build do not change. So it runs
 * when a session opens and when the user asks again, and the result is kept.
 *
 * <h2>Everything here is off the EDT</h2>
 *
 * <p>Rule 8. The collection blocks on SQL and on memory-mapped index reads; only the callback
 * crosses back to the EDT, with the finished result already in hand. A generation counter drops a
 * slow collection whose session has since been replaced, so a result can never be delivered for a
 * session the window has already closed.
 */
public final class MetricsService implements AutoCloseable {

  private final SessionSource source;
  private final ExecutorService worker;
  private final Consumer<Runnable> onEventThread;
  private final AtomicLong generation = new AtomicLong();

  private final List<Consumer<Result>> listeners = new CopyOnWriteArrayList<>();
  private final List<Consumer<Throwable>> errorListeners = new CopyOnWriteArrayList<>();
  private final FindingThresholds thresholds;
  private CompletableFuture<Void> closeStage;

  public MetricsService(SessionSource source) {
    this(source, SwingUtilities::invokeLater, FindingThresholds.defaults());
  }

  /**
   * @param onEventThread how a result gets back to the UI thread; a parameter so a headless test
   *     can run the whole path without an event queue
   * @param thresholds the numbers the rules compare against, fixed for the life of this service —
   *     every finding states the threshold it used, and a setter would let those printed numbers
   *     disagree with the findings already on screen
   */
  public MetricsService(
      SessionSource source, Consumer<Runnable> onEventThread, FindingThresholds thresholds) {
    this.source = Objects.requireNonNull(source, "source");
    this.onEventThread = Objects.requireNonNull(onEventThread, "onEventThread");
    this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    this.worker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-metrics");
              thread.setDaemon(true);
              return thread;
            });
  }

  /**
   * Observes every successful collection, on the UI thread.
   *
   * <p>So that a second view — the overview's cards — shows the numbers the findings were produced
   * from rather than starting a scan of its own. One collection, several readers.
   */
  public void addListener(Consumer<Result> listener) {
    listeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /** Observes a failed collection on the UI thread, for passive sibling views. */
  public void addErrorListener(Consumer<Throwable> listener) {
    errorListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /**
   * Collects the whole catalog and runs every rule.
   *
   * <p>Returns immediately; exactly one of the two callbacks runs, on the UI thread, unless a newer
   * request supersedes this one first.
   */
  public void collect(Consumer<Result> onDone, Consumer<Throwable> onError) {
    long mine = generation.incrementAndGet();
    FindingThresholds using = thresholds;
    worker.execute(
        () -> {
          if (generation.get() != mine) {
            return;
          }
          try (MetricQueries queries = source.openMetricQueries()) {
            if (generation.get() != mine) {
              return;
            }
            CriticalPath.DurationSource durations = queries.bestDurationSource();
            SessionMetrics metrics = queries.collect(MetricQueries.Request.everything(durations));
            List<Finding> findings = FindingRules.run(metrics.findingInputs(using));
            Result result = new Result(metrics, findings, using);
            if (generation.get() != mine) {
              return;
            }
            onEventThread.accept(
                () -> {
                  // Closing or replacing a service can happen after this
                  // Runnable was queued but before the UI thread reaches it.
                  // Check at delivery time too, or an old session can
                  // repopulate views that have already been cleared.
                  if (generation.get() != mine) {
                    return;
                  }
                  onDone.accept(result);
                  for (Consumer<Result> listener : listeners) {
                    listener.accept(result);
                  }
                });
          } catch (Exception failure) {
            if (generation.get() == mine) {
              onEventThread.accept(
                  () -> {
                    if (generation.get() == mine) {
                      onError.accept(failure);
                      for (Consumer<Throwable> listener : errorListeners) {
                        listener.accept(failure);
                      }
                    }
                  });
            }
          }
        });
  }

  /** Abandons any in-flight collection's result. */
  public void cancel() {
    generation.incrementAndGet();
  }

  @Override
  public void close() {
    closeAsync().toCompletableFuture().join();
  }

  /** Cancels collection without blocking the EDT and settles only after its reader is released. */
  public synchronized CompletionStage<Void> closeAsync() {
    cancel();
    if (closeStage == null) {
      worker.shutdownNow();
      closeStage =
          ViewClose.runAsync(
                  "bbv-metrics-close",
                  () -> {
                    boolean interrupted = false;
                    while (!worker.isTerminated()) {
                      try {
                        worker.awaitTermination(2, TimeUnit.SECONDS);
                      } catch (InterruptedException waiting) {
                        interrupted = true;
                      }
                    }
                    if (interrupted) {
                      Thread.currentThread().interrupt();
                    }
                  })
              .toCompletableFuture();
    }
    return closeStage;
  }

  /**
   * One collection: the catalog and what the rules made of it.
   *
   * @param thresholds the numbers the findings were produced with, kept because every finding
   *     states them and a later change must not make the displayed thresholds disagree with the
   *     displayed findings
   */
  public record Result(
      SessionMetrics metrics, List<Finding> findings, FindingThresholds thresholds) {

    public Result {
      Objects.requireNonNull(metrics, "metrics");
      findings = List.copyOf(findings);
      Objects.requireNonNull(thresholds, "thresholds");
    }
  }
}
