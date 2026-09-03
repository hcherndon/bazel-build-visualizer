package com.holtherndon.bazelviz.ui.configurations;

import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.Objects;

/** Paged effective-option differences between two exact checksums. */
final class ConfigurationDifferenceRowSource implements RowSource<ConfigurationQueries.Difference> {

  private final EntityReader reader;
  private final String baseline;
  private final String candidate;
  private final long count;

  private ConfigurationDifferenceRowSource(
      EntityReader reader, String baseline, String candidate, long count) {
    this.reader = reader;
    this.baseline = baseline;
    this.candidate = candidate;
    this.count = count;
  }

  static ConfigurationDifferenceRowSource open(
      EntityReader reader, String baseline, String candidate) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(candidate, "candidate");
    return new ConfigurationDifferenceRowSource(
        reader, baseline, candidate, reader.configurationDifferenceCount(baseline, candidate));
  }

  @Override
  public long rowCount() {
    return count;
  }

  @Override
  public Page<ConfigurationQueries.Difference> fetchPage(long pageIndex, int pageSize) {
    ConfigurationRowSource.requirePageSize(pageSize);
    return new Page<>(
        pageIndex,
        reader.configurationDifferences(
            baseline, candidate, Math.multiplyExact(pageIndex, pageSize), pageSize));
  }
}
