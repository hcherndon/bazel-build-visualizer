package com.holtherndon.bazelviz.benchmarks.spike;

import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticAction;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticActionGenerator;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticScale;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.PageCache;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Phase 0 spike: paged {@link JTable} at 50 million logical rows (plan
 * benchmark "JTable with 50 million logical rows"; plan 17.4/17.5).
 *
 * <p>Default mode opens a window with the table plus a HUD of live model
 * stats; {@code -Dbbv.smoke=true} auto-closes after 2 s. {@code --offscreen}
 * runs fully headless: it verifies the int-based Swing geometry budget at
 * this row count, simulates 200 random viewport jumps against the model, and
 * prints page-fetch latency percentiles, cache stats, and a PASS/FAIL line.
 */
public final class TableSpike {

    private static final long LOGICAL_ROWS = 50_000_000L;
    private static final int PAGE_SIZE = 512;
    private static final int CACHE_CAPACITY = 64;
    private static final int ROW_HEIGHT = 20;
    private static final int VIEWPORT_ROWS = 40;
    private static final int JUMPS = 200;
    private static final int CACHED_READS = 1_000;
    private static final long SEED = 42L;

    private static final long CACHED_ACCESS_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long UNCACHED_FETCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private static final String[] STATUS = {"success", "failed", "cancelled"};
    private static final String[] CACHE = {"unknown", "hit", "miss"};
    private static final String[] RUNNER = {"local", "remote", "worker", "sandbox"};

    /** Keeps the true 50M-range logical row index alongside the cycled action. */
    private record SpikeRow(long rowIndex, SyntheticAction action) {}

    private TableSpike() {}

    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--offscreen")) {
            // Must be set before any AWT class initializes.
            System.setProperty("java.awt.headless", "true");
            runOffscreen();
            return;
        }
        runWindowed();
    }

    /**
     * 50M logical rows over TIER3's 5M distinct actions: row indices cycle
     * into the generator via modulo, which keeps every {@code actionAt} call
     * inside the generator's own bounds while the table model still exercises
     * true 50-million-row paging and geometry. The wrapper row carries the
     * un-cycled index so the "index" column stays honest.
     */
    private static RowSource<SpikeRow> rowSource(SyntheticActionGenerator generator) {
        long actionCount = generator.actionCount();
        return new RowSource<>() {
            @Override
            public long rowCount() {
                return LOGICAL_ROWS;
            }

            @Override
            public Page<SpikeRow> fetchPage(long pageIndex, int pageSize) {
                long first = pageIndex * pageSize;
                int n = (int) Math.min(pageSize, LOGICAL_ROWS - first);
                List<SpikeRow> rows = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    long row = first + i;
                    rows.add(new SpikeRow(row, generator.actionAt(row % actionCount)));
                }
                return new Page<>(pageIndex, rows);
            }
        };
    }

    private static List<ColumnSpec<SpikeRow>> columns() {
        return List.of(
                new ColumnSpec<>("index", SpikeRow::rowIndex),
                new ColumnSpec<>("start µs", r -> r.action().startMicros()),
                new ColumnSpec<>("end µs", r -> r.action().endMicros()),
                new ColumnSpec<>("duration µs", r -> r.action().durationMicros()),
                new ColumnSpec<>("mnemonic",
                        r -> SyntheticActionGenerator.MNEMONICS[r.action().mnemonicIndex()]),
                new ColumnSpec<>("status", r -> STATUS[r.action().status()]),
                new ColumnSpec<>("cache", r -> CACHE[r.action().cacheState()]),
                new ColumnSpec<>("runner", r -> RUNNER[r.action().runner()]),
                new ColumnSpec<>("input bytes", r -> r.action().knownInputBytes()));
    }

    private static ExecutorService newFetchPool() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "table-spike-fetch");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static PagedTableModel<SpikeRow> newModel(ExecutorService fetchPool) {
        SyntheticActionGenerator generator =
                new SyntheticActionGenerator(SyntheticScale.TIER3, SEED);
        return new PagedTableModel<>(
                rowSource(generator), columns(), fetchPool, PAGE_SIZE, CACHE_CAPACITY);
    }

    // ------------------------------------------------------------------ window

    private static void runWindowed() {
        ExecutorService fetchPool = newFetchPool();
        SwingUtilities.invokeLater(() -> {
            PagedTableModel<SpikeRow> model = newModel(fetchPool);
            JTable table = new JTable(model);
            table.setRowHeight(ROW_HEIGHT);
            table.setFillsViewportHeight(true);

            JLabel hud = new JLabel(hudText(model));
            hud.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            new Timer(250, e -> hud.setText(hudText(model))).start();

            JFrame frame = new JFrame("TableSpike — 50M logical rows");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(new JScrollPane(table), BorderLayout.CENTER);
            frame.add(hud, BorderLayout.SOUTH);
            frame.setSize(1150, 760);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);

            if (Boolean.getBoolean("bbv.smoke")) {
                Timer close = new Timer(2_000, e -> {
                    frame.dispose();
                    System.exit(0);
                });
                close.setRepeats(false);
                close.start();
            }
        });
    }

    private static String hudText(PagedTableModel<SpikeRow> model) {
        PageCache.Stats stats = model.cacheStats();
        long lastFetchNanos = model.lastFetchNanos();
        return String.format(
                "rows=%,d | page fetches=%d skipped=%d | cache hits=%d misses=%d evictions=%d"
                        + " size=%d/%d | last fetch=%s",
                LOGICAL_ROWS, model.fetchCount(), model.skippedFetchCount(),
                stats.hits(), stats.misses(), stats.evictions(), stats.size(), CACHE_CAPACITY,
                lastFetchNanos < 0 ? "n/a" : String.format("%,d µs", lastFetchNanos / 1_000));
    }

    // --------------------------------------------------------------- offscreen

    private static void runOffscreen() throws Exception {
        ExecutorService fetchPool = newFetchPool();
        PagedTableModel<SpikeRow> model = newModel(fetchPool);
        List<Long> fetchNanos = Collections.synchronizedList(new ArrayList<>());
        model.setFetchObserver((pageIndex, nanos) -> fetchNanos.add(nanos));

        System.out.println("=== TableSpike --offscreen ===");
        System.out.printf("rows=%,d pageSize=%d cacheCapacity=%d pages=%,d%n",
                LOGICAL_ROWS, PAGE_SIZE, CACHE_CAPACITY,
                (LOGICAL_ROWS + PAGE_SIZE - 1) / PAGE_SIZE);

        checkGeometry(model);

        Random random = new Random(SEED);
        long lastWindowStart = 0;
        for (int jump = 0; jump < JUMPS; jump++) {
            long windowStart = random.nextLong(LOGICAL_ROWS - VIEWPORT_ROWS);
            lastWindowStart = windowStart;
            long firstRow = windowStart;
            // Ask the model for the visible window on the EDT, exactly as a
            // painting JTable would.
            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < VIEWPORT_ROWS; i++) {
                    model.getValueAt((int) (firstRow + i), 0);
                }
            });
            awaitWindowPages(model, windowStart);
        }

        long[] fetches = fetchNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("viewport jumps=%d window=%d rows%n", JUMPS, VIEWPORT_ROWS);
        System.out.printf(
                "page fetch latency: count=%d mean=%.1f µs p50=%.1f µs p95=%.1f µs p99=%.1f µs"
                        + " max=%.1f µs%n",
                fetches.length,
                Arrays.stream(fetches).average().orElseThrow() / 1_000.0,
                percentileMicros(fetches, 50), percentileMicros(fetches, 95),
                percentileMicros(fetches, 99), fetches[fetches.length - 1] / 1_000.0);

        long cachedMaxNanos = measureCachedAccess(model, lastWindowStart);

        PageCache.Stats stats = model.cacheStats();
        System.out.printf("cache stats: hits=%d misses=%d evictions=%d size=%d/%d%n",
                stats.hits(), stats.misses(), stats.evictions(), stats.size(), CACHE_CAPACITY);
        System.out.printf("model: fetches=%d skipped=%d%n",
                model.fetchCount(), model.skippedFetchCount());

        long uncachedP99Nanos = fetches[percentileIndex(fetches.length, 99)];
        boolean pass = cachedMaxNanos < CACHED_ACCESS_BUDGET_NANOS
                && uncachedP99Nanos < UNCACHED_FETCH_BUDGET_NANOS;
        System.out.printf(
                "%s: cached access max %.1f µs (budget %d ms), uncached fetch p99 %.1f µs"
                        + " (budget %d ms)%n",
                pass ? "PASS" : "FAIL",
                cachedMaxNanos / 1_000.0, TimeUnit.NANOSECONDS.toMillis(CACHED_ACCESS_BUDGET_NANOS),
                uncachedP99Nanos / 1_000.0,
                TimeUnit.NANOSECONDS.toMillis(UNCACHED_FETCH_BUDGET_NANOS));
        // The offscreen harness always exits 0 (its contract is "runs to
        // completion"); the PASS/FAIL line above is the recorded verdict.
        System.exit(0);
    }

    /**
     * JTable/JScrollPane geometry is int pixels. 50M rows x 20 px = 1e9 px,
     * inside Integer.MAX_VALUE, but ~107.4M rows would overflow — hence the
     * warning below and the plan-17.5 custom scroll shell beyond this scale.
     */
    private static void checkGeometry(PagedTableModel<SpikeRow> model) {
        long preferredHeightPx = LOGICAL_ROWS * ROW_HEIGHT;
        long headroomPx = Integer.MAX_VALUE - preferredHeightPx;
        System.out.printf(
                "geometry: %,d rows x %d px rowHeight = %,d px preferred table height%n",
                LOGICAL_ROWS, ROW_HEIGHT, preferredHeightPx);
        System.out.printf(
                "geometry: Integer.MAX_VALUE=%,d px, headroom=%,d px (%.1f%% of int range used)%n",
                Integer.MAX_VALUE, headroomPx,
                100.0 * preferredHeightPx / Integer.MAX_VALUE);
        System.out.println(
                "WARNING: at rowHeight 20 the int pixel space overflows near 107.4M rows;"
                        + " 100M+ rows will need the plan-17.5 custom scroll shell"
                        + " (virtual scrollbar, not JScrollPane pixel geometry).");
        try {
            // Headless construction of a JTable normally works (no window is
            // created); if this toolkit refuses, the arithmetic check above
            // stands on its own.
            JTable table = new JTable(model);
            table.setRowHeight(ROW_HEIGHT);
            int actualHeightPx = table.getPreferredSize().height;
            System.out.printf("geometry: headless JTable reports preferred height %,d px (%s)%n",
                    actualHeightPx,
                    actualHeightPx == preferredHeightPx ? "matches" : "MISMATCH vs arithmetic");
        } catch (Throwable t) {
            System.out.println("geometry: JTable not constructible headless ("
                    + t.getClass().getSimpleName() + "); using arithmetic check only");
        }
    }

    private static void awaitWindowPages(PagedTableModel<SpikeRow> model, long windowStart)
            throws InterruptedException {
        long firstPage = windowStart / PAGE_SIZE;
        long lastPage = (windowStart + VIEWPORT_ROWS - 1) / PAGE_SIZE;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (long page = firstPage; page <= lastPage; page++) {
            while (!model.isPageLoaded(page)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException(
                            "page " + page + " did not load within 10 s");
                }
                Thread.sleep(1);
            }
        }
    }

    /** Times individual getValueAt calls against pages known to be cached. */
    private static long measureCachedAccess(PagedTableModel<SpikeRow> model, long windowStart)
            throws Exception {
        long[] accessNanos = new long[CACHED_READS];
        int columnCount = model.getColumnCount();
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < CACHED_READS; i++) {
                int row = (int) (windowStart + (i % VIEWPORT_ROWS));
                int column = i % columnCount;
                long start = System.nanoTime();
                model.getValueAt(row, column);
                accessNanos[i] = System.nanoTime() - start;
            }
        });
        long max = Arrays.stream(accessNanos).max().orElseThrow();
        System.out.printf(
                "cached access: %d reads, mean=%.2f µs max=%.2f µs%n",
                CACHED_READS,
                Arrays.stream(accessNanos).average().orElseThrow() / 1_000.0,
                max / 1_000.0);
        return max;
    }

    private static double percentileMicros(long[] sortedNanos, double percentile) {
        return sortedNanos[percentileIndex(sortedNanos.length, percentile)] / 1_000.0;
    }

    private static int percentileIndex(int length, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * length) - 1;
        return Math.max(0, Math.min(length - 1, index));
    }
}
