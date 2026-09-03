package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.storage.query.BlobValue;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Renders one result cell, with SQL NULL made unmistakable.
 *
 * <h2>Rule 11, at the sharpest place it applies</h2>
 *
 * <p>A NULL is not zero and not an empty string. Swing's default renderer shows all three as an
 * empty cell, which would turn "this action has no recorded duration" and "this action took 0 µs"
 * into the same picture — two different facts about a build, one of them false.
 *
 * <p>So a NULL is drawn as the word {@code NULL}, in italic and in the theme's disabled foreground.
 * A <em>string</em> whose value happens to be "NULL" is drawn upright in the normal colour, and the
 * legend under the grid says which is which. Styling rather than a sentinel string, because any
 * sentinel is a value some column could legitimately hold.
 *
 * <p>{@code PagedTableModel}'s own placeholders pass through unchanged: "…" still means the page is
 * loading and "⚠" still means it failed, and neither is confused with a value.
 *
 * <p>HTML stays off, for the reason {@link PlainText} exists: every string here came out of
 * somebody's build.
 */
final class SqlValueRenderer extends DefaultTableCellRenderer {

  private static final long serialVersionUID = 1L;

  /** What a SQL NULL is drawn as. */
  static final String NULL_TEXT = "NULL";

  private static final String HTML_DISABLE = "html.disable";

  private Font upright;
  private Font italic;

  SqlValueRenderer() {
    putClientProperty(HTML_DISABLE, Boolean.TRUE);
  }

  @Override
  public Component getTableCellRendererComponent(
      JTable table, Object value, boolean selected, boolean focused, int row, int column) {
    boolean isNull = value == null;
    Object shown = isNull ? NULL_TEXT : text(value);
    Component rendered =
        super.getTableCellRendererComponent(table, shown, selected, focused, row, column);
    if (rendered instanceof JComponent component) {
      component.putClientProperty(HTML_DISABLE, Boolean.TRUE);
    }
    applyNullStyling(isNull, selected);
    setToolTipText(
        PlainText.tooltip(
            isNull
                ? "SQL NULL — no value was recorded, which is not the same as 0 or an empty string"
                : String.valueOf(shown)));
    return rendered;
  }

  /** BLOBs describe themselves; everything else renders as its own text. */
  private static Object text(Object value) {
    return value instanceof BlobValue blob ? blob.describe() : value;
  }

  private void applyNullStyling(boolean isNull, boolean selected) {
    Font base = getFont();
    if (base == null) {
      return;
    }
    if (upright == null || !base.equals(upright) && !base.equals(italic)) {
      upright = base.deriveFont(Font.PLAIN);
      italic = base.deriveFont(Font.ITALIC);
    }
    setFont(isNull ? italic : upright);
    if (isNull && !selected) {
      Color dim = UIManager.getColor("Label.disabledForeground");
      if (dim == null) {
        dim = UIManager.getColor("Label.disabledText");
      }
      if (dim != null) {
        setForeground(dim);
      }
    }
  }

  /** Visible for testing: what this renderer draws for {@code value}. */
  static String textFor(Object value) {
    if (value == null) {
      return NULL_TEXT;
    }
    if (PagedTableModel.PLACEHOLDER.equals(value)
        || PagedTableModel.ERROR_PLACEHOLDER.equals(value)) {
      return String.valueOf(value);
    }
    return String.valueOf(text(value));
  }
}
