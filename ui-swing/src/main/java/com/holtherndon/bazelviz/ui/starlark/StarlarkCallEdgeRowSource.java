package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.List;
import java.util.Objects;

/** A stable page source for one side of a selected function's call graph. */
final class StarlarkCallEdgeRowSource implements RowSource<StarlarkProfileReader.CallEdge> {

  private final StarlarkProfileReader reader;
  private final long functionId;
  private final StarlarkProfileReader.CallDirection direction;
  private final long rowCount;

  private StarlarkCallEdgeRowSource(
      StarlarkProfileReader reader,
      long functionId,
      StarlarkProfileReader.CallDirection direction,
      long rowCount) {
    this.reader = reader;
    this.functionId = functionId;
    this.direction = direction;
    this.rowCount = rowCount;
  }

  /** Blocking count read. Call on the view's worker. */
  static StarlarkCallEdgeRowSource open(
      StarlarkProfileReader reader,
      long functionId,
      StarlarkProfileReader.CallDirection direction) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(direction, "direction");
    long count = reader.callEdgeCount(functionId, direction);
    if (count < 0) {
      throw new IllegalStateException(
          "the " + direction.name().toLowerCase() + " count is negative: " + count);
    }
    return new StarlarkCallEdgeRowSource(reader, functionId, direction, count);
  }

  @Override
  public long rowCount() {
    return rowCount;
  }

  @Override
  public Page<StarlarkProfileReader.CallEdge> fetchPage(long pageIndex, int pageSize) {
    long offset = Math.multiplyExact(pageIndex, (long) pageSize);
    List<StarlarkProfileReader.CallEdge> rows =
        reader.callEdges(functionId, direction, offset, pageSize);
    StarlarkFunctionRowSource.verifyPage(
        rows, offset, pageSize, rowCount, direction.name().toLowerCase());
    return new Page<>(pageIndex, rows);
  }
}
