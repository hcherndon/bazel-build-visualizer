package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.List;
import java.util.Objects;

/** A stable, offset-paged view of one Starlark function query. */
final class StarlarkFunctionRowSource implements RowSource<StarlarkProfileReader.HotFunction> {

  private final StarlarkProfileReader reader;
  private final StarlarkProfileReader.FunctionQuery query;
  private final long rowCount;

  private StarlarkFunctionRowSource(
      StarlarkProfileReader reader, StarlarkProfileReader.FunctionQuery query, long rowCount) {
    this.reader = reader;
    this.query = query;
    this.rowCount = rowCount;
  }

  /** Blocking count read. Call on the view's worker. */
  static StarlarkFunctionRowSource open(
      StarlarkProfileReader reader, StarlarkProfileReader.FunctionQuery query) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(query, "query");
    long count = reader.hotFunctionCount(query);
    if (count < 0) {
      throw new IllegalStateException("the Starlark function count is negative: " + count);
    }
    return new StarlarkFunctionRowSource(reader, query, count);
  }

  @Override
  public long rowCount() {
    return rowCount;
  }

  @Override
  public Page<StarlarkProfileReader.HotFunction> fetchPage(long pageIndex, int pageSize) {
    long offset = Math.multiplyExact(pageIndex, (long) pageSize);
    List<StarlarkProfileReader.HotFunction> rows = reader.hotFunctions(query, offset, pageSize);
    verifyPage(rows, offset, pageSize, rowCount, "functions");
    return new Page<>(pageIndex, rows);
  }

  static void verifyPage(List<?> rows, long offset, int pageSize, long total, String name) {
    Objects.requireNonNull(rows, "rows");
    if (rows.size() > pageSize) {
      throw new IllegalStateException(
          "the " + name + " reader returned " + rows.size() + " rows for a page of " + pageSize);
    }
    long expected = Math.min(pageSize, Math.max(0, total - offset));
    if (rows.size() != expected) {
      throw new IllegalStateException(
          "the "
              + name
              + " reader returned "
              + rows.size()
              + " rows at offset "
              + offset
              + "; expected "
              + expected
              + " from the exact total "
              + total);
    }
  }
}
