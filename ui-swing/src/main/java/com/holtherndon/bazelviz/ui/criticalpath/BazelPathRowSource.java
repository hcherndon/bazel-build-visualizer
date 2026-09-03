package com.holtherndon.bazelviz.ui.criticalpath;

import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Lazy, exact-order rows over Bazel's profile-reported critical path. */
public final class BazelPathRowSource implements RowSource<CriticalPaths.BazelComponent> {

  @FunctionalInterface
  interface ComponentLookup {
    List<CriticalPaths.BazelComponent> components(long firstOrdinal, int limit) throws SQLException;
  }

  private final long count;
  private final ComponentLookup components;

  public BazelPathRowSource(long count, MetricQueries queries) {
    this(count, queries::bazelCriticalPathComponents);
  }

  BazelPathRowSource(long count, ComponentLookup components) {
    if (count < 0) {
      throw new IllegalArgumentException("component count must be nonnegative");
    }
    this.count = count;
    this.components = Objects.requireNonNull(components, "components");
  }

  @Override
  public long rowCount() {
    return count;
  }

  @Override
  public Page<CriticalPaths.BazelComponent> fetchPage(long pageIndex, int pageSize) {
    if (pageIndex < 0 || pageSize < 1) {
      throw new IllegalArgumentException("invalid page " + pageIndex + " at " + pageSize);
    }
    long firstOrdinal = Math.multiplyExact(pageIndex, (long) pageSize);
    if (firstOrdinal >= count) {
      return new Page<>(pageIndex, List.of());
    }
    int wanted = Math.toIntExact(Math.min((long) pageSize, count - firstOrdinal));
    List<CriticalPaths.BazelComponent> rows;
    try {
      rows = List.copyOf(components.components(firstOrdinal, wanted));
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read Bazel critical-path components", failure);
    }
    if (rows.size() != wanted) {
      throw new IllegalStateException(
          "Bazel critical-path page at ordinal "
              + firstOrdinal
              + " returned "
              + rows.size()
              + " of "
              + wanted
              + " rows");
    }
    for (int offset = 0; offset < rows.size(); offset++) {
      long expected = firstOrdinal + offset;
      if (rows.get(offset).ordinal() != expected) {
        throw new IllegalStateException(
            "Bazel critical-path ordinal sequence is broken:"
                + " expected "
                + expected
                + " but read "
                + rows.get(offset).ordinal());
      }
    }
    return new Page<>(pageIndex, rows);
  }
}
