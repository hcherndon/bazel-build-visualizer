package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.ui.session.SessionDataException;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The chronological event table's {@link RowSource}: index-addressed on the outside, keyset-paged
 * on the inside.
 *
 * <h2>How a row index becomes a page</h2>
 *
 * <ol>
 *   <li>{@link EventRowIndex} turns the page's first row index into a keyset anchor — arithmetic
 *       when the event ids are contiguous (which {@link EventRowIndex#open} verifies), a bounded
 *       sparse anchor array when they are not. Neither path uses {@code OFFSET}.
 *   <li>One {@link SessionReader#pageAfter} call fetches the page's rows from that anchor.
 *   <li>The predicted first id is checked against what came back. A mismatch demotes the index and
 *       the fetch is retried once, rather than the table showing rows from the wrong place.
 * </ol>
 *
 * <h2>One query per page</h2>
 *
 * <p>A page costs one range scan and nothing else. {@code EventSummary} carries the raw location
 * and the canonical id display alongside the summary columns, so the rows are built straight from
 * what the keyset query returned.
 *
 * <p>It was not always so: this used to add an indexed point lookup per row for those two columns,
 * because {@code storage-sqlite}'s schema was frozen for Phase 1 and the alternative was leaving
 * two required columns out. The Phase 1 audit closed that by widening the summary query, and this
 * comment is kept because the shape of the fix — widen the query rather than loop — is the one to
 * reach for the next time a column is missing from a page.
 *
 * <p>What is <em>not</em> fetched per row is the payload itself. The table shows how many bytes a
 * record occupies; the bytes themselves are read only for the selected event, by {@link
 * EventInspectorModel} (plan 17.11).
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #fetchPage} runs on {@code PagedTableModel}'s single-threaded fetch executor, which is
 * also the only thread allowed to touch the {@link SessionReader} and the {@link EventRowIndex}.
 * {@link #rowCount()} is called by the model's constructor on the EDT, which is why it returns a
 * value captured during {@link #open} rather than issuing a query.
 */
public final class EventRowSource implements RowSource<EventRow> {

  /**
   * Rows per page. Small enough that a page's per-row lookups stay well inside the 100 ms
   * cached-page objective (docs/performance.md), large enough that a screenful never spans more
   * than two pages.
   */
  public static final int DEFAULT_PAGE_SIZE = 200;

  private final SessionReader reader;
  private final int pageSize;
  private final long rowCount;
  private final EventRowIndex index;
  private final FilterExpression filter;
  private final long totalRowCount;

  private EventRowSource(
      SessionReader reader,
      int pageSize,
      long rowCount,
      EventRowIndex index,
      FilterExpression filter,
      long totalRowCount) {
    this.reader = reader;
    this.pageSize = pageSize;
    this.rowCount = rowCount;
    this.index = index;
    this.filter = filter;
    this.totalRowCount = totalRowCount;
  }

  /**
   * Probes the session and builds a source over it.
   *
   * <p>Blocking: run this on the fetch executor, never on the EDT. The resulting object answers
   * {@link #rowCount()} without I/O so that constructing the table model on the EDT is safe.
   */
  public static EventRowSource open(SessionReader reader, int pageSize) {
    return open(reader, pageSize, FilterExpression.ALL);
  }

  public static EventRowSource open(SessionReader reader, int pageSize, FilterExpression filter) {
    Objects.requireNonNull(reader, "reader");
    EventRowIndex index = EventRowIndex.open(reader, pageSize, filter);
    return new EventRowSource(
        reader,
        pageSize,
        index.rowCount(),
        index,
        filter,
        filter.isEmpty() ? index.rowCount() : reader.eventCount());
  }

  public FilterExpression filter() {
    return filter;
  }

  public long totalRowCount() {
    return totalRowCount;
  }

  /** Stops obsolete sparse-index work when the host replaces this source. */
  void cancel() {
    index.cancel();
  }

  /** Opens with {@link #DEFAULT_PAGE_SIZE}. */
  public static EventRowSource open(SessionReader reader) {
    return open(reader, DEFAULT_PAGE_SIZE);
  }

  @Override
  public long rowCount() {
    return rowCount;
  }

  /** The page size this source's row index was built for. */
  public int pageSize() {
    return pageSize;
  }

  /**
   * The reader backing this source.
   *
   * <p>Package-private: {@link EventsView} is the only caller, and the only reason it needs this is
   * a live refresh, which rebuilds the row index over the same connection rather than opening a new
   * one every tick — the connection is otherwise idle between page fetches, and a live capture that
   * ticks every couple of seconds for the life of a long build would otherwise accumulate one
   * reader per tick in {@code SqliteSessionSource}'s reader list, which is only ever cleared when
   * the whole session closes.
   */
  SessionReader reader() {
    return reader;
  }

  /** How row indices are currently resolved; for the view's status line. */
  public EventRowIndex.Mode rowIndexMode() {
    return index.mode();
  }

  @Override
  public Page<EventRow> fetchPage(long pageIndex, int requestedPageSize) {
    index.checkCancelled();
    if (requestedPageSize != pageSize) {
      // The row index's anchors are laid out for one page size. Serving a
      // different one would silently misalign every page boundary.
      throw new IllegalArgumentException(
          "this source was opened for pages of "
              + pageSize
              + " rows and cannot serve pages of "
              + requestedPageSize);
    }
    long firstRow = pageIndex * pageSize;
    if (firstRow >= rowCount) {
      return new Page<>(pageIndex, List.of());
    }
    int wanted = (int) Math.min(pageSize, rowCount - firstRow);
    return new Page<>(pageIndex, fetchRows(firstRow, wanted, true));
  }

  private List<EventRow> fetchRows(long firstRow, int wanted, boolean mayRetry) {
    EventRowIndex.Anchor anchor = index.anchorForRow(firstRow);
    List<EventSummary> summaries = reader.pageAfter(anchor.exclusiveId(), wanted, filter);
    if (summaries.size() != wanted) {
      throw new SessionDataException(
          "row "
              + firstRow
              + " of "
              + rowCount
              + " asked for "
              + wanted
              + " events and the store returned "
              + summaries.size()
              + "; the session changed while it was being viewed");
    }
    if (anchor.expectedFirstId().isPresent()) {
      long expected = anchor.expectedFirstId().getAsLong();
      long actual = summaries.getFirst().id();
      if (expected != actual) {
        index.reportPredictionMismatch(firstRow, expected, actual);
        if (mayRetry) {
          return fetchRows(firstRow, wanted, false);
        }
        throw new SessionDataException(
            "row "
                + firstRow
                + " resolved to event id "
                + actual
                + " where "
                + expected
                + " was expected, and the rebuilt row"
                + " index did not agree either");
      }
    }
    // Built straight from the page: the summary now carries the raw
    // location and the id display, so a page costs one query rather than
    // one query plus a point lookup per row.
    List<EventRow> rows = new ArrayList<>(wanted);
    for (EventSummary summary : summaries) {
      rows.add(EventRow.of(summary));
    }
    return rows;
  }
}
