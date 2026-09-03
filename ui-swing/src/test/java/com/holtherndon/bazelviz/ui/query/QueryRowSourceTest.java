package com.holtherndon.bazelviz.ui.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.query.QueryOutline;
import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.storage.query.ReadOnlySql;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.storage.query.TempViewDefinition;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The row source and its dynamic columns, without a database.
 *
 * <p>What matters here is that the <em>existing</em> paging shell is what carries the result:
 * {@code PagedTableModel} is constructed over this source with no change to it, and the columns it
 * is given were built from the query's own result metadata rather than declared.
 */
final class QueryRowSourceTest {

  @Test
  @DisplayName("columns come from the query's own result labels, in order")
  void columnsAreDynamic() {
    QueryRowSource source =
        new QueryRowSource(
            new FakeReader(0), outline(List.of("mnemonic", "actions", "total_ms"), 0));

    assertThat(source.columns())
        .extracting(ColumnSpec::name)
        .containsExactly("mnemonic", "actions", "total_ms");
  }

  @Test
  @DisplayName("an extractor hands back a SQL NULL as null, for the renderer to name")
  void nullSurvivesTheExtractor() {
    QueryRowSource source = new QueryRowSource(new FakeReader(0), outline(List.of("a", "b"), 0));
    QueryRow row = QueryRow.of("x", null);

    assertThat(source.columns().get(0).extractor().apply(row)).isEqualTo("x");
    assertThat(source.columns().get(1).extractor().apply(row))
        .as(
            "mapping it to \"\" here would make NULL and empty indistinguishable"
                + " before anything could distinguish them")
        .isNull();
  }

  @Test
  @DisplayName("the row count is the capped total, never the raw one")
  void rowCountIsTheVisibleCount() {
    QueryRowSource capped =
        new QueryRowSource(
            new FakeReader(0),
            new QueryOutline(
                "SELECT 1",
                ReadOnlySql.Shape.TABULAR,
                List.of("x"),
                OptionalLong.of(5_000_000L),
                1_000L,
                1_000L,
                1L,
                List.of()));

    assertThat(capped.rowCount()).isEqualTo(1_000L);
    assertThat(capped.outline().isCapped()).isTrue();
  }

  @Test
  @DisplayName("the existing PagedTableModel drives this source unmodified")
  void theExistingPagedModelDrivesIt() throws Exception {
    FakeReader reader = new FakeReader(450);
    QueryRowSource source = new QueryRowSource(reader, outline(List.of("id", "note"), 450));
    List<Runnable> queued = new ArrayList<>();
    PagedTableModel<QueryRow> model =
        new PagedTableModel<>(source, source.columns(), queued::add, QueryRowSource.PAGE_SIZE, 8);

    assertThat(model.getRowCount()).isEqualTo(450);
    assertThat(model.getColumnName(1)).isEqualTo("note");

    // A miss is a placeholder and a scheduled fetch, never a blocking read.
    assertThat(model.getValueAt(0, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
    drain(queued);
    assertThat(model.getValueAt(0, 0)).isEqualTo(0L);
    assertThat(model.getValueAt(0, 1)).as("row 0 stores a SQL NULL").isNull();

    // A page in the middle, and the short final page.
    model.getValueAt(300, 0);
    drain(queued);
    assertThat(model.getValueAt(300, 0)).isEqualTo(300L);
    model.getValueAt(449, 0);
    drain(queued);
    assertThat(model.getValueAt(449, 0)).isEqualTo(449L);
    assertThat(model.failedFetchCount()).isZero();
    assertThat(reader.pagesServed.get()).isEqualTo(3L);
  }

  private static void drain(List<Runnable> queued) throws Exception {
    while (!queued.isEmpty()) {
      List<Runnable> batch = List.copyOf(queued);
      queued.clear();
      batch.forEach(Runnable::run);
    }
    // The model coalesces its row-updated events onto the EDT; let them
    // land so the assertions below read a settled model.
    SwingUtilities.invokeAndWait(() -> {});
  }

  private static QueryOutline outline(List<String> columns, long rows) {
    return new QueryOutline(
        "SELECT " + String.join(", ", columns) + " FROM t",
        ReadOnlySql.Shape.TABULAR,
        columns,
        OptionalLong.of(rows),
        rows,
        1_000_000L,
        1L,
        List.of());
  }

  /** Serves synthetic rows; every odd column value is a SQL NULL. */
  private static final class FakeReader implements QueryReader {

    private final long rows;
    private final AtomicLong pagesServed = new AtomicLong();

    FakeReader(long rows) {
      this.rows = rows;
    }

    @Override
    public List<SchemaTable> schema() {
      return List.of();
    }

    @Override
    public QueryOutline describe(String sql, long rowLimit) {
      throw new UnsupportedOperationException("this test drives page() only");
    }

    @Override
    public List<QueryRow> page(QueryOutline outline, long offset, int limit) {
      pagesServed.incrementAndGet();
      List<QueryRow> page = new ArrayList<>();
      for (long i = offset; i < Math.min(offset + limit, rows); i++) {
        page.add(QueryRow.of(i, i % 2 == 0 ? null : "note " + i));
      }
      return page;
    }

    @Override
    public List<String> applyTempViews(List<TempViewDefinition> views) {
      return List.of();
    }

    @Override
    public void cancel() {}

    @Override
    public int timeoutSeconds() {
      return 60;
    }

    @Override
    public void close() {}
  }
}
