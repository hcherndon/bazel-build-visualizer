package com.holtherndon.bazelviz.ui.table;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-capacity LRU cache of row pages keyed by page index.
 *
 * <p>Concurrency contract: one background writer ({@link #put}) plus the EDT
 * reader ({@link #get}/{@link #peek}). Everything is guarded by the instance
 * monitor; the critical sections are single map operations, so the EDT never
 * waits behind anything slower than a hash lookup.
 */
public final class PageCache<T> {

    /** Point-in-time counters; {@code size} is the current number of cached pages. */
    public record Stats(long hits, long misses, long evictions, int size) {}

    private final int capacity;
    private final LinkedHashMap<Long, Page<T>> pages;
    private long hits;
    private long misses;
    private long evictions;

    public PageCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        // Access-ordered map: get() refreshes recency, so the eldest entry is
        // the least recently used page when put() pushes size past capacity.
        this.pages = new LinkedHashMap<>(capacity * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Page<T>> eldest) {
                if (size() > PageCache.this.capacity) {
                    evictions++;
                    return true;
                }
                return false;
            }
        };
    }

    /** Returns the cached page or {@code null}, counting a hit or miss and refreshing recency. */
    public synchronized Page<T> get(long pageIndex) {
        Page<T> page = pages.get(pageIndex);
        if (page == null) {
            misses++;
        } else {
            hits++;
        }
        return page;
    }

    /**
     * Presence probe that touches neither the hit/miss counters nor LRU
     * recency ({@code containsKey} does not record an access even in
     * access-order mode). For instrumentation and tests.
     */
    public synchronized boolean peek(long pageIndex) {
        return pages.containsKey(pageIndex);
    }

    /** Inserts (or replaces) a page, evicting the least recently used page when over capacity. */
    public synchronized void put(Page<T> page) {
        pages.put(page.pageIndex(), page);
    }

    public synchronized Stats stats() {
        return new Stats(hits, misses, evictions, pages.size());
    }

    public int capacity() {
        return capacity;
    }
}
