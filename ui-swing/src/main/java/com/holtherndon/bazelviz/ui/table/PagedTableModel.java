package com.holtherndon.bazelviz.ui.table;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/**
 * Windowed table model over a {@link RowSource}, designed for tens of
 * millions of logical rows (plan 17.4).
 *
 * <p>{@link #getValueAt} never blocks: a cache miss returns
 * {@link #PLACEHOLDER} and schedules a page fetch on the supplied executor.
 * When a page arrives it is put into the {@link PageCache} and a coalesced
 * {@code fireTableRowsUpdated} is dispatched on the EDT — multiple pages
 * completing in a burst share one EDT dispatch. In-flight fetches whose page
 * has since moved far away from the viewport (further than the cache
 * capacity, in pages) are skipped as obsolete: fast scrubbing must not fetch
 * every page the user flew past.
 *
 * <p>Row identity is the {@code long} row index for now; stable-ID selection
 * arrives with the real model. The source's row count must fit in an
 * {@code int} because {@code JTable} row geometry is int-based; larger counts
 * need the custom scroll shell of plan 17.5 and are rejected here rather than
 * silently clamped.
 */
public final class PagedTableModel<T> extends AbstractTableModel {

    /** Rendered for any cell whose page is not cached yet. */
    public static final String PLACEHOLDER = "…";

    /** Observes completed page fetches; called on the fetch executor thread. */
    @FunctionalInterface
    public interface FetchObserver {
        void pageLoaded(long pageIndex, long fetchNanos);
    }

    private final RowSource<T> source;
    private final List<ColumnSpec<T>> columns;
    private final Executor fetchExecutor;
    private final int pageSize;
    private final int rowCount;
    private final PageCache<T> cache;
    private final int relevanceDistancePages;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<int[]> pendingUpdates = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicLong fetchCount = new AtomicLong();
    private final AtomicLong skippedFetchCount = new AtomicLong();
    private volatile long lastRequestedPage;
    private volatile long lastFetchNanos = -1; // -1 = no fetch yet; never fake a zero latency
    private volatile FetchObserver fetchObserver;

    public PagedTableModel(RowSource<T> source, List<ColumnSpec<T>> columns,
            Executor fetchExecutor, int pageSize, int cacheCapacity) {
        this.source = Objects.requireNonNull(source, "source");
        this.columns = List.copyOf(columns);
        this.fetchExecutor = Objects.requireNonNull(fetchExecutor, "fetchExecutor");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("at least one column is required");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
        }
        this.pageSize = pageSize;
        long rows = source.rowCount();
        if (rows < 0 || rows > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("row count " + rows
                    + " does not fit JTable's int-based model; the plan-17.5 custom scroll shell"
                    + " is required beyond Integer.MAX_VALUE rows");
        }
        this.rowCount = (int) rows;
        this.cache = new PageCache<>(cacheCapacity);
        this.relevanceDistancePages = Math.max(2, cacheCapacity);
    }

    @Override
    public int getRowCount() {
        return rowCount;
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnName(int column) {
        return columns.get(column).name();
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        long page = pageIndexForRow(rowIndex);
        lastRequestedPage = page; // viewport proxy for the obsolete-fetch check
        Page<T> cached = cache.get(page);
        if (cached == null) {
            scheduleFetch(page);
            return PLACEHOLDER;
        }
        int offset = (int) (rowIndex - page * pageSize);
        return columns.get(columnIndex).extractor().apply(cached.rows().get(offset));
    }

    public long pageIndexForRow(long rowIndex) {
        return rowIndex / pageSize;
    }

    public int pageSize() {
        return pageSize;
    }

    /** Cache presence without disturbing hit/miss stats or LRU order. */
    public boolean isPageLoaded(long pageIndex) {
        return cache.peek(pageIndex);
    }

    public PageCache.Stats cacheStats() {
        return cache.stats();
    }

    /** Completed (non-skipped) page fetches. */
    public long fetchCount() {
        return fetchCount.get();
    }

    /** In-flight fetches dropped because the viewport had moved too far away. */
    public long skippedFetchCount() {
        return skippedFetchCount.get();
    }

    /** Duration of the most recent completed fetch, or -1 before the first one. */
    public long lastFetchNanos() {
        return lastFetchNanos;
    }

    public void setFetchObserver(FetchObserver observer) {
        this.fetchObserver = observer;
    }

    private void scheduleFetch(long pageIndex) {
        if (!inFlight.add(pageIndex)) {
            return; // already queued or fetching
        }
        fetchExecutor.execute(() -> runFetch(pageIndex));
    }

    private void runFetch(long pageIndex) {
        try {
            if (Math.abs(pageIndex - lastRequestedPage) > relevanceDistancePages) {
                // Obsolete: the viewport moved on while this fetch was queued.
                // If the user comes back, the repaint-driven getValueAt miss
                // simply schedules the page again.
                skippedFetchCount.incrementAndGet();
                return;
            }
            long started = System.nanoTime();
            Page<T> page = source.fetchPage(pageIndex, pageSize);
            if (page.pageIndex() != pageIndex) {
                throw new IllegalStateException("source returned page " + page.pageIndex()
                        + " for requested page " + pageIndex);
            }
            cache.put(page);
            long elapsed = System.nanoTime() - started;
            lastFetchNanos = elapsed;
            fetchCount.incrementAndGet();
            FetchObserver observer = fetchObserver;
            if (observer != null) {
                observer.pageLoaded(pageIndex, elapsed);
            }
            pageArrived(pageIndex, page.rows().size());
        } finally {
            inFlight.remove(pageIndex);
        }
    }

    private void pageArrived(long pageIndex, int rowsInPage) {
        int first = (int) (pageIndex * pageSize);
        int last = Math.min(first + rowsInPage, rowCount) - 1;
        pendingUpdates.add(new int[] {first, last});
        // Coalesce: one EDT dispatch drains every page that completed since
        // the last flush, instead of one invokeLater per page.
        if (flushScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::flushPendingUpdates);
        }
    }

    private void flushPendingUpdates() {
        // Reset before draining so a producer that enqueues after our drain
        // schedules a fresh flush rather than being lost.
        flushScheduled.set(false);
        int[] range;
        while ((range = pendingUpdates.poll()) != null) {
            fireTableRowsUpdated(range[0], range[1]);
        }
    }
}
