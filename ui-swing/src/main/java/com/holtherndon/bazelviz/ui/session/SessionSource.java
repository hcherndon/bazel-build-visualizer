package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import java.sql.Connection;

/**
 * An open session: its describable identity plus a factory for the per-thread readers that actually
 * query it.
 *
 * <p>The split exists because of connection affinity. A JDBC connection is not thread-safe, so each
 * background executor that reads the session gets its own {@link SessionReader}. The Events view
 * runs two: one single-threaded executor behind the table's page fetches, one behind the raw
 * inspector, so a slow journal read for the selected event cannot stall scrolling — and neither can
 * touch the EDT.
 *
 * <p>Closing the source closes every reader it handed out, so a view that is torn down while a
 * fetch is in flight does not leak a connection.
 */
public interface SessionSource extends AutoCloseable {

  /** What the manifest says about this session. Cheap; already in memory. */
  SessionInfo info();

  /**
   * Opens a reader for the calling background executor. Never call the returned reader from more
   * than one thread.
   */
  SessionReader openReader();

  /**
   * Opens a reader over the normalized entity tables, for the Phase 3 views.
   *
   * <p>Separate from {@link #openReader()} rather than merged into it because the two answer
   * different questions and are used by different views on different executors. A view that shows
   * actions has no use for the raw journal, and a reader that carried both would keep a journal
   * file handle open for every table on screen.
   */
  EntityReader openEntityReader();

  /**
   * Opens a reader over the dependency graph, for the Phase 5 views.
   *
   * <p>A third reader rather than a third method on the second one, for the same reason: it holds
   * memory-mapped CSR indexes that the tables views have no use for, and a session with no imported
   * graph opens it and gets an empty answer rather than paying for indexes that do not exist.
   */
  GraphQueries openGraphQueries();

  /**
   * Opens a reader over the metric catalog, for the Phase 8 dashboard and findings.
   *
   * <p>A fourth reader for the same reason as the third: it scans every action once and keeps the
   * resulting spans, which no other view wants to pay for. It is handed the session's graph reader
   * too, because the derived critical path needs a graph and the alternative — computing the
   * metrics without one and stitching the path in afterwards — would produce a dashboard whose
   * parts came from different reads.
   */
  MetricQueries openMetricQueries();

  /**
   * Opens a reader over an imported Starlark CPU profile.
   *
   * <p>The default keeps older and synthetic session sources source-compatible while schema and
   * storage adapters are introduced independently. A real session source overrides it; views
   * surface this exception as an unavailable profile instead of doing database work themselves.
   */
  default StarlarkProfileReader openStarlarkProfileReader() {
    throw new SessionDataException("Starlark CPU profile reading is not available");
  }

  /**
   * Opens a reader for user-written SQL over this session.
   *
   * <p>A fifth reader, and the only one whose statements this codebase did not author. Its
   * connection is opened read-only in fact rather than by convention — {@code SQLITE_OPEN_READONLY}
   * plus {@code query_only} — which is the difference between "no caller writes through this" and
   * "nothing can". Every other reader here is trusted; this one is not, and does not need to be.
   */
  QueryReader openQueryReader();

  /**
   * Opens a raw read connection for the timeline's own aggregation.
   *
   * <p>The timeline neither pages rows nor reads entities: it streams every span once to build a
   * pyramid, then fetches a window of exact spans per viewport. Neither shape fits {@link
   * EntityReader}, and giving the timeline its own connection is what lets it do that work on its
   * own thread while the tables keep theirs.
   */
  Connection openTimelineConnection();

  @Override
  void close();
}
