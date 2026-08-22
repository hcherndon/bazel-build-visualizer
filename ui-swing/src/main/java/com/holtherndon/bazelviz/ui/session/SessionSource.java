package com.holtherndon.bazelviz.ui.session;

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

    @Override
    void close();
}
