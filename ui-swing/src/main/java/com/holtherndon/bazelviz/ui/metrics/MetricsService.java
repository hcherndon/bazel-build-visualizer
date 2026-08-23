package com.holtherndon.bazelviz.ui.metrics;

import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.FindingRules;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p>The overview refreshes every two seconds because its numbers are counts
 * over indexed tables. This is not that: it scans every action, sweeps every
 * span and runs thirteen rules, which at Tier 3 is seconds of work. Putting it
 * on the overview's timer would make a large session unusable while telling the
 * user nothing new — the metrics of a finished build do not change. So it runs
 * when a session opens and when the user asks again, and the result is kept.
 *
 * <h2>Everything here is off the EDT</h2>
 *
 * <p>Rule 8. The collection blocks on SQL and on memory-mapped index reads;
 * only the callback crosses back to the EDT, with the finished result already
 * in hand. A generation counter drops a slow collection whose session has since
 * been replaced, so a result can never be delivered for a session the window
 * has already closed.
 */
public final class MetricsService implements AutoCloseable {

    private final SessionSource source;
    private final ExecutorService worker;
    private final Consumer<Runnable> onEventThread;
    private final AtomicLong generation = new AtomicLong();

    private final List<Consumer<Result>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile FindingThresholds thresholds = FindingThresholds.defaults();
    private volatile Result last;

    public MetricsService(SessionSource source) {
        this(source, SwingUtilities::invokeLater);
    }

    /**
     * @param onEventThread how a result gets back to the UI thread; a parameter
     *     so a headless test can run the whole path without an event queue
     */
    public MetricsService(SessionSource source, Consumer<Runnable> onEventThread) {
        this.source = Objects.requireNonNull(source, "source");
        this.onEventThread = Objects.requireNonNull(onEventThread, "onEventThread");
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-metrics");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The thresholds the next collection will use. */
    public void setThresholds(FindingThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /**
     * Observes every successful collection, on the UI thread.
     *
     * <p>So that a second view — the overview's cards — shows the numbers the
     * findings were produced from rather than starting a scan of its own. One
     * collection, several readers.
     */
    public void addListener(Consumer<Result> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** The most recent result, when one has arrived. */
    public Optional<Result> last() {
        return Optional.ofNullable(last);
    }

    /**
     * Collects the whole catalog and runs every rule.
     *
     * <p>Returns immediately; exactly one of the two callbacks runs, on the UI
     * thread, unless a newer request supersedes this one first.
     */
    public void collect(Consumer<Result> onDone, Consumer<Throwable> onError) {
        long mine = generation.incrementAndGet();
        FindingThresholds using = thresholds;
        worker.execute(() -> {
            if (generation.get() != mine) {
                return;
            }
            try (MetricQueries queries = source.openMetricQueries()) {
                CriticalPath.DurationSource durations = queries.bestDurationSource();
                SessionMetrics metrics =
                        queries.collect(MetricQueries.Request.everything(durations));
                List<Finding> findings = FindingRules.run(metrics.findingInputs(using));
                Result result = new Result(metrics, findings, using);
                if (generation.get() != mine) {
                    return;
                }
                last = result;
                onEventThread.accept(() -> {
                    onDone.accept(result);
                    for (Consumer<Result> listener : listeners) {
                        listener.accept(result);
                    }
                });
            } catch (Exception failure) {
                if (generation.get() == mine) {
                    onEventThread.accept(() -> onError.accept(failure));
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
        cancel();
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One collection: the catalog and what the rules made of it.
     *
     * @param thresholds the numbers the findings were produced with, kept
     *     because every finding states them and a later change must not make
     *     the displayed thresholds disagree with the displayed findings
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
