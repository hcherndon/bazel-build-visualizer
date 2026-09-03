package com.holtherndon.bazelviz.ui.configurations;

import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.Objects;

/** Bounded, offset-paged configuration summaries. */
public final class ConfigurationRowSource implements RowSource<ConfigurationQueries.Summary> {

  /** Rows fetched for each of the configuration explorer's grids. */
  public static final int PAGE_SIZE = 200;

  private final EntityReader reader;
  private final long count;

  private ConfigurationRowSource(EntityReader reader, long count) {
    this.reader = reader;
    this.count = count;
  }

  public static ConfigurationRowSource open(EntityReader reader) {
    Objects.requireNonNull(reader, "reader");
    return new ConfigurationRowSource(reader, reader.configurationCount());
  }

  @Override
  public long rowCount() {
    return count;
  }

  @Override
  public Page<ConfigurationQueries.Summary> fetchPage(long pageIndex, int pageSize) {
    requirePageSize(pageSize);
    return new Page<>(
        pageIndex, reader.configurations(Math.multiplyExact(pageIndex, pageSize), pageSize));
  }

  static void requirePageSize(int pageSize) {
    if (pageSize != PAGE_SIZE) {
      throw new IllegalArgumentException(
          "configuration rows use pages of " + PAGE_SIZE + ", not " + pageSize);
    }
  }
}
