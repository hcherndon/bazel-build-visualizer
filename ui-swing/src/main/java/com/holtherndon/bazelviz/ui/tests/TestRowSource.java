package com.holtherndon.bazelviz.ui.tests;

import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The tests table's {@link RowSource}, keyset-paged behind an anchor index.
 *
 * <p>The ordering is fixed: failures first, then timeouts and build failures, then flakes, then
 * everything that passed. A user opens this view because something went wrong, and making them sort
 * to find it would be making them ask a question the view already knows the answer to.
 */
public final class TestRowSource implements RowSource<TestRow> {

  public static final int DEFAULT_PAGE_SIZE = 200;

  private final EntityReader reader;
  private final int pageSize;
  private final TestQueries.Index index;

  private TestRowSource(EntityReader reader, int pageSize, TestQueries.Index index) {
    this.reader = reader;
    this.pageSize = pageSize;
    this.index = index;
  }

  /** Blocking: the anchor scan runs here. Call it on the fetch executor. */
  public static TestRowSource open(EntityReader reader, int pageSize) {
    Objects.requireNonNull(reader, "reader");
    return new TestRowSource(reader, pageSize, reader.testIndex(pageSize));
  }

  @Override
  public long rowCount() {
    return index.rowCount();
  }

  public int pageSize() {
    return pageSize;
  }

  @Override
  public Page<TestRow> fetchPage(long pageIndex, int requestedPageSize) {
    if (requestedPageSize != pageSize) {
      throw new IllegalArgumentException(
          "this source was opened for pages of "
              + pageSize
              + " rows and cannot serve pages of "
              + requestedPageSize);
    }
    Optional<TestQueries.Anchor> anchor = index.anchorFor(pageIndex);
    List<TestRow> rows =
        anchor.isPresent()
            ? reader.testsAfter(anchor.get(), pageSize)
            : reader.firstTestPage(pageSize);
    return new Page<>(pageIndex, rows);
  }
}
