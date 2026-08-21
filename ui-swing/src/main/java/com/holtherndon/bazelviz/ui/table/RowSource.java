package com.holtherndon.bazelviz.ui.table;

/**
 * Abstract paged row supplier behind {@link PagedTableModel}. Implementations
 * may block (SQL, file I/O): {@link #fetchPage} is only ever invoked on the
 * model's fetch executor, never on the EDT.
 */
public interface RowSource<T> {

    /** Total logical row count. Stable for the lifetime of the model. */
    long rowCount();

    /**
     * Fetches the rows of {@code pageIndex}. The returned page must carry the
     * requested index and at most {@code pageSize} rows (the last page of the
     * source may be shorter).
     */
    Page<T> fetchPage(long pageIndex, int pageSize);
}
