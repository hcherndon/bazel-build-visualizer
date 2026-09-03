package com.holtherndon.bazelviz.ui.theme;

import java.awt.Dimension;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * A {@code JLabel} look-alike that wraps onto more than one line instead of
 * clipping.
 *
 * <h2>Why not just wrap a {@code JLabel} in {@code <html>}</h2>
 *
 * <p>Swing's own word-wrap for a label goes through {@code <html>…</html>},
 * and {@link PlainText} exists specifically to turn that off: everything this
 * application displays came from a build, an imported session is untrusted
 * outright (plan 22.4), and {@code BasicHTML} fetches {@code
 * <img src="http://…">} when it paints. Reaching for HTML to wrap one
 * sentence would undo that guard for whatever else the same string happens to
 * contain. This gets the same visual result — text that wraps instead of
 * being clipped or needing a scrollbar — from a non-editable {@link
 * JTextArea}, which never parses its contents as markup.
 *
 * <h2>When to reach for this instead of {@code JLabel}</h2>
 *
 * <p>Anywhere a string whose length the UI does not control — an explanatory
 * sentence, an error excerpt, anything a build wrote rather than a fixed
 * label this codebase wrote — sits in a container whose width can end up
 * narrower than the text needs. A plain {@code JLabel} does not wrap: once
 * its container is forced to a width narrower than the text (which is
 * exactly what a {@link ScrollableViewport} does on purpose), whatever does
 * not fit is simply not painted. That is the silent truncation plan rule 12
 * forbids. First used for {@code CoverageView}'s explanatory notes, several
 * of which measure over 800 pixels wide at the default font — comfortably
 * wider than the narrowest width the Overview tab's content area is ever
 * given.
 */
public final class WrappingLabel {

    private WrappingLabel() {}

    /** A wrapping, selectable, non-editable {@code JLabel}-styled text area. */
    public static JTextArea create(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        // Read-only must not mean inert. Inspector values, paths, warnings and
        // metrics use this component, and standard text selection plus Copy is
        // essential when moving evidence into an issue or terminal.
        area.setFocusable(true);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(null);
        Object font = UIManager.get("Label.font");
        if (font instanceof java.awt.Font labelFont) {
            area.setFont(labelFont);
        }
        Object foreground = UIManager.get("Label.foreground");
        if (foreground instanceof java.awt.Color labelColor) {
            area.setForeground(labelColor);
        }
        // A JTextArea's text view otherwise contributes its unwrapped width as
        // a hard minimum. GridBagLayout may then refuse to make the column any
        // narrower, defeating lineWrap and pushing a dashboard wider than its
        // viewport. Height remains the font's minimum; only width yields.
        Dimension minimum = area.getMinimumSize();
        area.setMinimumSize(new Dimension(0, minimum.height));
        PlainText.disableHtml(area);
        return area;
    }
}
