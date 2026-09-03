package com.holtherndon.bazelviz.ui.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.query.BlobValue;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Rule 11 at the cell: a SQL NULL must not look like an empty string or a 0. */
final class SqlValueRendererTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("NULL, an empty string and a zero are three different cells")
  void nullIsNotEmptyAndNotZero() {
    assertThat(SqlValueRenderer.textFor(null)).isEqualTo("NULL");
    assertThat(SqlValueRenderer.textFor("")).isEmpty();
    assertThat(SqlValueRenderer.textFor(0L)).isEqualTo("0");
  }

  @Test
  @DisplayName("a SQL NULL is italic; the four-letter string \"NULL\" is not")
  void theTwoNullsAreToldApartByStyle() {
    SqlValueRenderer renderer = new SqlValueRenderer();
    JTable table = new JTable(new DefaultTableModel(new Object[][] {{null}}, new Object[] {"c"}));

    Component real = renderer.getTableCellRendererComponent(table, null, false, false, 0, 0);
    boolean realIsItalic = real.getFont().isItalic();
    Component text = renderer.getTableCellRendererComponent(table, "NULL", false, false, 0, 0);
    boolean textIsItalic = text.getFont().isItalic();

    assertThat(realIsItalic).as("a SQL NULL is drawn in italic").isTrue();
    assertThat(textIsItalic)
        .as("a string whose value is \"NULL\" is drawn upright, so the two differ")
        .isFalse();
    // Both styles were read at render time on purpose: DefaultTableCellRenderer
    // returns itself, so `real` and `text` are one component and re-reading
    // either after the second call would read the second call's styling.
  }

  @Test
  @DisplayName("a BLOB states its length instead of rendering as [B@...")
  void blobsDescribeThemselves() {
    assertThat(SqlValueRenderer.textFor(new BlobValue(2048)))
        .contains("2,048 bytes")
        .doesNotContain("[B@");
  }

  @Test
  @DisplayName("the paging placeholders are not mistaken for values")
  void placeholdersPassThrough() {
    assertThat(SqlValueRenderer.textFor(PagedTableModel.PLACEHOLDER))
        .isEqualTo(PagedTableModel.PLACEHOLDER);
    assertThat(SqlValueRenderer.textFor(PagedTableModel.ERROR_PLACEHOLDER))
        .isEqualTo(PagedTableModel.ERROR_PLACEHOLDER);
  }
}
