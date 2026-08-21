package com.holtherndon.bazelviz.ui.table;

import java.util.List;

/**
 * One fetched page of rows. Immutable; safe to hand from the fetch thread to
 * the EDT without further synchronization beyond the cache's monitor.
 */
public record Page<T>(long pageIndex, List<T> rows) {

    public Page {
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0: " + pageIndex);
        }
        rows = List.copyOf(rows);
    }
}
