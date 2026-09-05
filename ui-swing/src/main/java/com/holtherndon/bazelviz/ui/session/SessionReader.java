package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The read service the Events view is written against (plan rule 19: UI components depend on
 * service interfaces, not on SQLite implementation classes). {@link SqliteSessionSource} provides
 * the real implementation; the tests provide in-memory ones, which is why every model in {@code
 * ui.events} can be exercised headlessly without a database or a journal on disk.
 *
 * <h2>Keyset only</h2>
 *
 * <p>There is deliberately no {@code pageAt(long rowOffset)} method. The underlying {@code
 * EventQueries} pages by anchor id and the Phase 0 spike measured why (docs/performance.md): {@code
 * OFFSET} costs grow with scroll depth, keyset costs do not. Adding an offset-shaped method here
 * would let a caller reintroduce that cost one layer up, so the mapping from table row index to
 * anchor id is solved explicitly by {@link com.holtherndon.bazelviz.ui.events.EventRowIndex}
 * instead.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method blocks on SQL or file I/O, so <b>none of them may be called on the Swing EDT</b>.
 * One reader is bound to one thread: the real implementation wraps a JDBC connection, and
 * connections are not thread-safe. Callers obtain one reader per single-threaded executor from
 * {@link SessionSource#openReader()}.
 */
public interface SessionReader extends AutoCloseable {

  /** Total number of stored events. One aggregate query; do not call per row. */
  long eventCount();

  /** Count matching events. Implementations must apply the filter before counting. */
  default long eventCount(FilterExpression filter) {
    if (!filter.isEmpty()) {
      throw new UnsupportedOperationException("This reader does not support event filters.");
    }
    return eventCount();
  }

  /** Matching events after an exclusive keyset anchor. */
  default List<EventSummary> pageAfter(OptionalLong afterId, int limit, FilterExpression filter) {
    if (!filter.isEmpty()) {
      throw new UnsupportedOperationException("This reader does not support event filters.");
    }
    return pageAfter(afterId, limit);
  }

  /** Matching events before an exclusive keyset anchor, returned in ascending order. */
  default List<EventSummary> pageBefore(OptionalLong beforeId, int limit, FilterExpression filter) {
    if (!filter.isEmpty()) {
      throw new UnsupportedOperationException("This reader does not support event filters.");
    }
    return pageBefore(beforeId, limit);
  }

  /**
   * The events after {@code afterId} in ascending id order, at most {@code limit} of them.
   *
   * @param afterId exclusive anchor, empty to start at the first row
   */
  List<EventSummary> pageAfter(OptionalLong afterId, int limit);

  /**
   * The events before {@code beforeId}, returned in ascending id order.
   *
   * @param beforeId exclusive anchor, empty to start at the last row
   */
  List<EventSummary> pageBefore(OptionalLong beforeId, int limit);

  /** One event's full row including its raw journal location, or empty when no such row exists. */
  Optional<EventDetail> event(long id);

  /**
   * The verbatim payload at {@code location}, read back out of the journal.
   *
   * <p>Called only for the event the user selected (plan 17.11), never for a whole page: a page
   * holds hundreds of rows and their payloads can be megabytes each.
   */
  RawPayload rawPayload(RawLocation location);

  /**
   * Asks the in-flight query to stop, from another thread. Used when the UI abandons results it no
   * longer wants (plan 10.9, cancellable long-running queries). Safe to call when nothing is
   * running.
   */
  void cancelRunningQuery();

  @Override
  void close();
}
