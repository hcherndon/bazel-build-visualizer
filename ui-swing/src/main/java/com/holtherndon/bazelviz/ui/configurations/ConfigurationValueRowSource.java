package com.holtherndon.bazelviz.ui.configurations;

import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.Objects;

/** Paged effective options and BEP make variables for one checksum. */
final class ConfigurationValueRowSource implements RowSource<ConfigurationQueries.Value> {

  private final EntityReader reader;
  private final String checksum;
  private final long count;

  private ConfigurationValueRowSource(EntityReader reader, String checksum, long count) {
    this.reader = reader;
    this.checksum = checksum;
    this.count = count;
  }

  static ConfigurationValueRowSource open(EntityReader reader, String checksum) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(checksum, "checksum");
    return new ConfigurationValueRowSource(
        reader, checksum, reader.configurationValueCount(checksum));
  }

  @Override
  public long rowCount() {
    return count;
  }

  @Override
  public Page<ConfigurationQueries.Value> fetchPage(long pageIndex, int pageSize) {
    ConfigurationRowSource.requirePageSize(pageSize);
    return new Page<>(
        pageIndex,
        reader.configurationValues(checksum, Math.multiplyExact(pageIndex, pageSize), pageSize));
  }
}
