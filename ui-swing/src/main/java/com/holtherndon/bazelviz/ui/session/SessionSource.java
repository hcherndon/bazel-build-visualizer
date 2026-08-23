package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.storage.graph.GraphQueries;

/**
 * An open session: its describable identity plus a factory for the per-thread
 * readers that actually query it.
 *
 * <p>The split exists because of connection affinity. A JDBC connection is not
 * thread-safe, so each background executor that reads the session gets its own
 * {@link SessionReader}. The Events view runs two: one single-threaded executor
 * behind the table's page fetches, one behind the raw inspector, so a slow
 * journal read for the selected event cannot stall scrolling — and neither can
 * touch the EDT.
 *
 * <p>Closing the source closes every reader it handed out, so a view that is
 * torn down while a fetch is in flight does not leak a connection.
 */
public interface SessionSource extends AutoCloseable {

    /** What the manifest says about this session. Cheap; already in memory. */
    SessionInfo info();

    /**
     * Opens a reader for the calling background executor. Never call the
     * returned reader from more than one thread.
     */
    SessionReader openReader();

    /**
     * Opens a reader over the normalized entity tables, for the Phase 3 views.
     *
     * <p>Separate from {@link #openReader()} rather than merged into it because
     * the two answer different questions and are used by different views on
     * different executors. A view that shows actions has no use for the raw
     * journal, and a reader that carried both would keep a journal file handle
     * open for every table on screen.
     */
    EntityReader openEntityReader();

    /**
     * Opens a reader over the dependency graph, for the Phase 5 views.
     *
     * <p>A third reader rather than a third method on the second one, for the
     * same reason: it holds memory-mapped CSR indexes that the tables views
     * have no use for, and a session with no imported graph opens it and gets
     * an empty answer rather than paying for indexes that do not exist.
     */
    GraphQueries openGraphQueries();

    @Override
    void close();
}
