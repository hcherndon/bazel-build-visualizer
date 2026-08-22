package com.holtherndon.bazelviz.ui.theme;

import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultTreeCellRenderer;

/**
 * Makes Swing show text as text.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code JLabel}, every default table and tree cell renderer, and every
 * tooltip run their string through {@code BasicHTML}, which turns anything
 * beginning with {@code <html>} into a live HTML document. That document's
 * {@code <img src="http://…">} is fetched when it is painted.
 *
 * <p>Everything this application displays came from a build: a label, a path,
 * a compiler's error text, an abort description, a tag someone wrote in a BUILD
 * file. An imported session is untrusted outright (plan 22.4), and even a
 * trusted one carries text nobody vetted. So a string in a session file can
 * make the application open a network connection — against plan 22.1, which
 * says it makes none — and can render arbitrary markup where the user expects
 * to read what Bazel said.
 *
 * <p>Verified rather than assumed: {@code BasicHTML.updateRenderer} installs a
 * renderer for {@code "<html><b>x</b>"} and installs none once the component
 * carries {@code html.disable}.
 *
 * <h2>How to use it</h2>
 *
 * <p>Every component that displays a string derived from a session gets
 * {@link #disableHtml}; every table and tree gets {@link #install}. Tooltips
 * are a separate path — the tooltip component belongs to {@code ToolTipManager},
 * not to us — so their text goes through {@link #tooltip} instead.
 */
public final class PlainText {

    /**
     * Swing's own opt-out, honoured by {@code BasicHTML.updateRenderer}. Not a
     * constant Swing exposes, which is why it is spelled out here.
     */
    private static final String HTML_DISABLE = "html.disable";

    private PlainText() {}

    /** Stops {@code component} rendering its text as HTML. Returns it. */
    public static <T extends JComponent> T disableHtml(T component) {
        component.putClientProperty(HTML_DISABLE, Boolean.TRUE);
        return component;
    }

    /** Installs a cell renderer that shows every value as text. */
    public static void install(JTable table) {
        table.setDefaultRenderer(Object.class, new PlainCellRenderer());
        disableHtml(table);
    }

    /** Installs a cell renderer that shows every node as text. */
    public static void install(JTree tree) {
        tree.setCellRenderer(new PlainTreeRenderer());
        disableHtml(tree);
    }

    /**
     * Tooltip text that will not be parsed as markup.
     *
     * <p>The tooltip component is created by {@code ToolTipManager} and cannot
     * be given the client property, so the string itself is made
     * unrecognisable to {@code BasicHTML.isHTMLString} — which requires the
     * first character to be {@code '<'}. One leading space does that, is
     * invisible in a tooltip, and leaves the rest of the text exactly as the
     * build wrote it. Escaping instead would show the user {@code &lt;} where
     * their build said {@code <}.
     *
     * @return {@code text}, or null when it is null
     */
    public static String tooltip(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '<') {
            return text;
        }
        return " " + text;
    }

    /** A table cell renderer with HTML off. */
    private static final class PlainCellRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        PlainCellRenderer() {
            putClientProperty(HTML_DISABLE, Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            Component rendered = super.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            // The renderer is reused for every cell and super() may replace the
            // property, so it is reasserted rather than only set once.
            if (rendered instanceof JComponent component) {
                component.putClientProperty(HTML_DISABLE, Boolean.TRUE);
            }
            setToolTipText(tooltip(value == null ? null : value.toString()));
            return rendered;
        }
    }

    /** A tree cell renderer with HTML off. */
    private static final class PlainTreeRenderer extends DefaultTreeCellRenderer {

        private static final long serialVersionUID = 1L;

        PlainTreeRenderer() {
            putClientProperty(HTML_DISABLE, Boolean.TRUE);
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean focused) {
            Component rendered = super.getTreeCellRendererComponent(
                    tree, value, selected, expanded, leaf, row, focused);
            if (rendered instanceof JComponent component) {
                component.putClientProperty(HTML_DISABLE, Boolean.TRUE);
            }
            return rendered;
        }
    }
}
