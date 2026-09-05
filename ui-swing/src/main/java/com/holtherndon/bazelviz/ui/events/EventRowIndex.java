package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.ui.session.SessionDataException;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a table row index onto a keyset anchor.
 *
 * <h2>The problem</h2>
 *
 * <p>{@code JTable} addresses rows by index: "give me row 6,412,900". The event store pages by
 * anchor: "give me the rows after id X". The Phase 0 spike settled why the store will not grow an
 * index-addressed method — {@code OFFSET} makes SQLite walk and discard every skipped row, so a
 * jump into the middle of a large table costs time proportional to the depth of the jump (11 ms at
 * 2M rows and rising), while the keyset seek stayed at 0.23 ms regardless of depth
 * (docs/performance.md). So the translation has to happen here, and it has to happen without a
 * scan.
 *
 * <h2>The solution: prove density, then do arithmetic</h2>
 *
 * <p>{@code bep_events.id} is SQLite's rowid, assigned in normalization order, and Phase 1 never
 * deletes an event row. The ids are therefore contiguous, and if they are contiguous then
 *
 * <pre>{@code id(rowIndex) == minId + rowIndex}</pre>
 *
 * <p>which turns "row 6,412,900" into "the row after id 6,412,900 + minId − 1" — one arithmetic
 * step and one B-tree seek, the same cost at row 10,000,000 as at row 10. That is the answer to a
 * random jump into the middle of a ten-million-row table: no scan, no counting, no OFFSET, no page
 * walk.
 *
 * <p>Density is <em>verified, not assumed</em>. {@link #open} reads the first id, the last id and
 * the row count — three indexed queries, no scan — and checks {@code maxId - minId + 1 == count}.
 * Every fetched page is then checked against the id its index predicted; a single mismatch demotes
 * the index rather than letting it hand out wrong rows.
 *
 * <h2>The fallback: bounded sparse anchors</h2>
 *
 * <p>When the ids are not contiguous — a filtered result or a session compacted by a later tool —
 * arithmetic cannot work. The index walks matches only as far as the requested page through the
 * ordinary keyset API, keeping one anchor id every {@code stride} rows and discarding everything in
 * between, so its memory is {@code rowCount / stride} longs and never the table. {@code stride} is
 * a multiple of the page size chosen so the anchor array stays under {@value #MAX_ANCHORS} entries;
 * a page start that falls between two anchors is reached by walking at most {@code stride /
 * pageSize − 1} pages forward from the nearer one. First-page access needs no index walk. A first
 * seek far down the result is O(preceding matches); previously indexed lookups are O(1) plus that
 * bounded walk. Counts for arbitrary filters can still scan the store.
 *
 * <h2>Threading</h2>
 *
 * <p>Confined to the single-threaded page-fetch executor that owns the {@link SessionReader}. Never
 * queried on the EDT; {@link #mode()} is volatile for the status line, and {@link #cancel()} may be
 * called from the EDT to abandon an obsolete index.
 */
public final class EventRowIndex {

  /** Cap on retained anchors: 65,536 longs is half a megabyte, whatever the table size. */
  static final int MAX_ANCHORS = 65_536;

  private static final Logger log = LoggerFactory.getLogger(EventRowIndex.class);

  /** How row indices are being resolved right now. */
  public enum Mode {
    /** Ids are contiguous: row index maps to id by arithmetic. */
    DENSE,
    /** Ids have gaps: row index maps through a sparse anchor array. */
    SPARSE_ANCHORS
  }

  /**
   * Where to start a page.
   *
   * @param exclusiveId anchor for {@link SessionReader#pageAfter}; empty means the start of the
   *     table
   * @param expectedFirstId the id the first returned row must have, when the index is in a position
   *     to know it. Empty in sparse mode, where the anchors are the only thing known and the rows
   *     between them are not
   */
  public record Anchor(OptionalLong exclusiveId, OptionalLong expectedFirstId) {
    public Anchor {
      Objects.requireNonNull(exclusiveId, "exclusiveId");
      Objects.requireNonNull(expectedFirstId, "expectedFirstId");
    }
  }

  private final SessionReader reader;
  private final long rowCount;
  private final int pageSize;
  private final long minId;
  private final FilterExpression filter;

  private volatile Mode mode;
  private long[] anchors;
  private int stride;
  private int builtBuckets;
  private long indexedRows;
  private OptionalLong indexedThrough = OptionalLong.empty();
  private volatile boolean cancelled;

  private EventRowIndex(
      SessionReader reader,
      long rowCount,
      int pageSize,
      long minId,
      Mode mode,
      FilterExpression filter) {
    this.reader = reader;
    this.rowCount = rowCount;
    this.pageSize = pageSize;
    this.minId = minId;
    this.mode = mode;
    this.filter = filter;
  }

  /**
   * Probes the unfiltered table and returns an index for it. Three indexed queries; no table scan
   * even when the outcome is sparse (the anchor array is built on first use, so an index that is
   * never asked for a row never pays for one).
   *
   * <p>Blocking: call on the fetch executor, never the EDT.
   */
  public static EventRowIndex open(SessionReader reader, int pageSize) {
    return open(reader, pageSize, FilterExpression.ALL);
  }

  public static EventRowIndex open(SessionReader reader, int pageSize, FilterExpression filter) {
    Objects.requireNonNull(reader, "reader");
    if (pageSize <= 0) {
      throw new IllegalArgumentException("pageSize must be positive: " + pageSize);
    }
    long rowCount = reader.eventCount(filter);
    if (rowCount <= 0) {
      return new EventRowIndex(reader, 0, pageSize, 0, Mode.DENSE, filter);
    }
    List<EventSummary> first = reader.pageAfter(OptionalLong.empty(), 1, filter);
    List<EventSummary> last = reader.pageBefore(OptionalLong.empty(), 1, filter);
    if (first.isEmpty() || last.isEmpty()) {
      throw new SessionDataException(
          "the event table reports "
              + rowCount
              + " rows but returned none for its first or last row");
    }
    long minId = first.getFirst().id();
    long maxId = last.getLast().id();
    boolean dense = maxId - minId + 1 == rowCount;
    if (!dense) {
      log.debug(
          "event ids in this result are not contiguous (first={}, last={}, count={});"
              + " row lookups will use a sparse anchor index",
          minId,
          maxId,
          rowCount);
    }
    return new EventRowIndex(
        reader, rowCount, pageSize, minId, dense ? Mode.DENSE : Mode.SPARSE_ANCHORS, filter);
  }

  /** Rows the index was opened over. */
  public long rowCount() {
    return rowCount;
  }

  /** How lookups are currently resolved. */
  public Mode mode() {
    return mode;
  }

  /** Anchors retained by the sparse index, or 0 while none are needed. */
  public int retainedAnchors() {
    long[] built = anchors;
    return built == null ? 0 : built.length;
  }

  /**
   * The anchor for the page that starts at {@code rowIndex}.
   *
   * @param rowIndex first row of a page; must be a multiple of the page size the index was opened
   *     with, which is what the table model always asks for
   */
  public Anchor anchorForRow(long rowIndex) {
    checkCancelled();
    if (rowIndex < 0 || rowIndex >= rowCount) {
      throw new IndexOutOfBoundsException(
          "row " + rowIndex + " is outside the " + rowCount + " rows of this session");
    }
    if (rowIndex % pageSize != 0) {
      throw new IllegalArgumentException(
          "row " + rowIndex + " does not start a page of " + pageSize + " rows");
    }
    if (mode == Mode.DENSE) {
      long firstId = minId + rowIndex;
      return rowIndex == 0
          ? new Anchor(OptionalLong.empty(), OptionalLong.of(firstId))
          : new Anchor(OptionalLong.of(firstId - 1), OptionalLong.of(firstId));
    }
    return sparseAnchor(rowIndex);
  }

  /**
   * Reports that a page's first row was not the id the index predicted, which can only mean the ids
   * are not contiguous after all. The index demotes itself to sparse mode and drops any arithmetic
   * assumption; the caller refetches.
   *
   * <p>This is here so that a wrong prediction produces a visibly rebuilt index rather than a table
   * quietly showing the wrong rows.
   */
  public void reportPredictionMismatch(long rowIndex, long expectedId, long actualId) {
    log.warn(
        "row {} was predicted to be event id {} but the store returned {};"
            + " rebuilding the row index as sparse",
        rowIndex,
        expectedId,
        actualId);
    mode = Mode.SPARSE_ANCHORS;
    anchors = null;
    builtBuckets = 0;
    indexedRows = 0;
    indexedThrough = OptionalLong.empty();
  }

  // --------------------------------------------------------------- sparse

  private Anchor sparseAnchor(long rowIndex) {
    stride = chooseStride(rowCount, pageSize);
    int bucket = (int) (rowIndex / stride);
    long[] built = ensureAnchors(bucket);
    long bucketRow = (long) bucket * stride;
    OptionalLong anchor =
        bucket == 0 && bucketRow == 0 ? OptionalLong.empty() : OptionalLong.of(built[bucket]);
    long toSkip = rowIndex - bucketRow;
    while (toSkip > 0) {
      int step = (int) Math.min(pageSize, toSkip);
      checkCancelled();
      List<EventSummary> skipped = reader.pageAfter(anchor, step, filter);
      if (skipped.size() != step) {
        throw new SessionDataException(
            "walking to row "
                + rowIndex
                + " asked for "
                + step
                + " rows after "
                + anchor
                + " and got "
                + skipped.size()
                + "; the table changed under the view");
      }
      anchor = OptionalLong.of(skipped.getLast().id());
      toSkip -= step;
    }
    // Sparse mode knows the anchors and nothing about the rows between
    // them, so it does not claim to predict the first id.
    return new Anchor(anchor, OptionalLong.empty());
  }

  private long[] ensureAnchors(int throughBucket) {
    long[] built = anchors;
    int buckets = (int) ((rowCount + stride - 1) / stride);
    if (built == null) {
      built = new long[buckets];
      anchors = built;
      builtBuckets = 1;
    }
    while (indexedRows < rowCount && builtBuckets <= throughBucket) {
      // Resume at the same boundary if an earlier query failed mid-bucket.
      long remaining = (long) builtBuckets * stride - indexedRows;
      while (remaining > 0 && indexedRows < rowCount) {
        checkCancelled();
        int step = (int) Math.min(pageSize, remaining);
        List<EventSummary> page = reader.pageAfter(indexedThrough, step, filter);
        if (page.isEmpty()) {
          throw new SessionDataException(
              "the event table ended after "
                  + indexedRows
                  + " rows while building a row index over "
                  + rowCount
                  + " rows");
        }
        indexedThrough = OptionalLong.of(page.getLast().id());
        indexedRows += page.size();
        remaining -= page.size();
      }
      // The id of the last row of bucket-1 is the exclusive anchor the
      // next bucket's first page starts after.
      built[builtBuckets++] = indexedThrough.orElseThrow();
    }
    return built;
  }

  void cancel() {
    cancelled = true;
  }

  void checkCancelled() {
    if (cancelled || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Event indexing cancelled");
    }
  }

  /**
   * The smallest multiple of {@code pageSize} that keeps the anchor array under {@link
   * #MAX_ANCHORS} entries. A multiple of the page size is what makes the walk from an anchor to a
   * page start an exact number of pages.
   */
  static int chooseStride(long rowCount, int pageSize) {
    long smallestUsable = (rowCount + MAX_ANCHORS - 1) / MAX_ANCHORS;
    long multiplier = Math.max(1, (smallestUsable + pageSize - 1) / pageSize);
    return Math.toIntExact(multiplier * pageSize);
  }
}
