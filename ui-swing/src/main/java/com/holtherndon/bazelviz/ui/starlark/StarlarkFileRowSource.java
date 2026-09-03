package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.List;
import java.util.Objects;

/** A stable, offset-paged view of one Starlark source-file query. */
final class StarlarkFileRowSource implements RowSource<StarlarkProfileReader.SourceFile> {

  private final StarlarkProfileReader reader;
  private final StarlarkProfileReader.FileQuery query;
  private final long rowCount;

  private StarlarkFileRowSource(
      StarlarkProfileReader reader, StarlarkProfileReader.FileQuery query, long rowCount) {
    this.reader = reader;
    this.query = query;
    this.rowCount = rowCount;
  }

  /** Blocking count read. Call on the view's worker. */
  static StarlarkFileRowSource open(
      StarlarkProfileReader reader, StarlarkProfileReader.FileQuery query) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(query, "query");
    long count = reader.sourceFileCount(query);
    if (count < 0) {
      throw new IllegalStateException("the Starlark source-file count is negative: " + count);
    }
    return new StarlarkFileRowSource(reader, query, count);
  }

  @Override
  public long rowCount() {
    return rowCount;
  }

  @Override
  public Page<StarlarkProfileReader.SourceFile> fetchPage(long pageIndex, int pageSize) {
    long offset = Math.multiplyExact(pageIndex, (long) pageSize);
    List<StarlarkProfileReader.SourceFile> rows = reader.sourceFiles(query, offset, pageSize);
    StarlarkFunctionRowSource.verifyPage(rows, offset, pageSize, rowCount, "source files");
    return new Page<>(pageIndex, rows);
  }
}
