package com.holtherndon.bazelviz.ui.actions;

import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionQueries;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The actions table's {@link RowSource}: index-addressed on the outside, keyset-paged on the
 * inside.
 *
 * <h2>How a row index becomes a page</h2>
 *
 * <p>{@code JTable} asks for row 412,000; SQLite is asked for "the page after this row". Bridging
 * the two without {@code OFFSET} needs a mapping from page number to anchor, and that is what
 * {@link ActionQueries.Index} is: one ordered scan reading only the sort value and the id, keeping
 * every {@code pageSize}-th pair. At a page size of 200 a five-million-action build yields 25,000
 * anchors, which is small enough to hold and exact enough that jumping to any page costs one seek.
 *
 * <p>The Events view solves the same problem arithmetically, because its ids are contiguous.
 * Actions under a user-chosen sort have no such structure — "the 412,000th action by duration"
 * cannot be computed — so the index is built rather than derived. It is rebuilt whenever the
 * filter, the sort or the direction changes, because all three change what the row at a given index
 * is.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #open} and {@link #fetchPage} both run on the table's single fetch executor, the only
 * thread allowed to touch the {@link EntityReader}. {@link #rowCount()} is called on the EDT during
 * model construction, so it returns a value captured during {@link #open} rather than issuing a
 * query.
 */
public final class ActionRowSource implements RowSource<ActionRow> {

  /**
   * Rows per page. Matched to the Events view's, which the Phase 1 measurements sized: large enough
   * that a screenful never spans more than two pages, small enough that one page stays inside the
   * 100 ms objective.
   */
  public static final int DEFAULT_PAGE_SIZE = 200;

  private final EntityReader reader;
  private final ActionFilter filter;
  private final ActionSort sort;
  private final boolean descending;
  private final int pageSize;
  private final ActionQueries.Index index;
  private final long totalCount;

  private ActionRowSource(
      EntityReader reader,
      ActionFilter filter,
      ActionSort sort,
      boolean descending,
      int pageSize,
      ActionQueries.Index index,
      long totalCount) {
    this.reader = reader;
    this.filter = filter;
    this.sort = sort;
    this.descending = descending;
    this.pageSize = pageSize;
    this.index = index;
    this.totalCount = totalCount;
  }

  /**
   * Builds a source for one (filter, sort, direction).
   *
   * <p>Blocking: the anchor scan runs here. Call it on the fetch executor.
   */
  public static ActionRowSource open(
      EntityReader reader, ActionFilter filter, ActionSort sort, boolean descending, int pageSize) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(sort, "sort");
    ActionQueries.Index index = reader.actionIndex(filter, sort, descending, pageSize);
    return new ActionRowSource(
        reader, filter, sort, descending, pageSize, index, reader.actionCount());
  }

  @Override
  public long rowCount() {
    return index.rowCount();
  }

  /** How many actions the session holds before this source's filter. */
  public long unfilteredCount() {
    return totalCount;
  }

  public ActionFilter filter() {
    return filter;
  }

  public ActionSort sort() {
    return sort;
  }

  public boolean descending() {
    return descending;
  }

  public int pageSize() {
    return pageSize;
  }

  /**
   * The page containing {@code row} under this source's sort and direction.
   *
   * <p>The anchor index stores the last row of every full page. A binary search over those
   * boundaries locates an exact row without scanning the action table or issuing an {@code OFFSET}
   * query. Callers must supply a row that matches this source's filter.
   */
  public long pageIndexOf(ActionRow row) {
    Objects.requireNonNull(row, "row");
    ActionQueries.Anchor target = ActionQueries.Anchor.of(row, sort);
    List<ActionQueries.Anchor> anchors = index.anchors();
    int low = 0;
    int high = anchors.size();
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (compareInDisplayOrder(target, anchors.get(middle)) <= 0) {
        high = middle;
      } else {
        low = middle + 1;
      }
    }
    return low;
  }

  private int compareInDisplayOrder(ActionQueries.Anchor left, ActionQueries.Anchor right) {
    int comparison = compareValues(left.sortValue(), right.sortValue());
    if (comparison == 0) {
      comparison = Long.compare(left.id(), right.id());
    }
    return descending ? -comparison : comparison;
  }

  private static int compareValues(Optional<Object> left, Optional<Object> right) {
    if (left.isEmpty()) {
      return right.isEmpty() ? 0 : -1;
    }
    if (right.isEmpty()) {
      return 1;
    }
    Object leftValue = left.orElseThrow();
    Object rightValue = right.orElseThrow();
    if (leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber) {
      return Long.compare(leftNumber.longValue(), rightNumber.longValue());
    }
    return compareSqliteText(leftValue.toString(), rightValue.toString());
  }

  /** SQLite's default BINARY collation: unsigned lexicographic UTF-8 bytes. */
  static int compareSqliteText(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int shared = Math.min(leftBytes.length, rightBytes.length);
    for (int i = 0; i < shared; i++) {
      int comparison =
          Integer.compare(Byte.toUnsignedInt(leftBytes[i]), Byte.toUnsignedInt(rightBytes[i]));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  @Override
  public Page<ActionRow> fetchPage(long pageIndex, int requestedPageSize) {
    if (requestedPageSize != pageSize) {
      // The anchors are laid out for one page size. Serving a different
      // one would misalign every page boundary, silently.
      throw new IllegalArgumentException(
          "this source was opened for pages of "
              + pageSize
              + " rows and cannot serve pages of "
              + requestedPageSize);
    }
    Optional<ActionQueries.Anchor> anchor = index.anchorFor(pageIndex);
    List<ActionRow> rows =
        anchor.isPresent()
            ? reader.actionsAfter(anchor.get(), filter, sort, descending, pageSize)
            : reader.firstActionPage(filter, sort, descending, pageSize);
    return new Page<>(pageIndex, rows);
  }
}
