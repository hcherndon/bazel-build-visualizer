package com.holtherndon.bazelviz.ui.theme;

import java.awt.Dimension;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * A {@code JPanel} that a {@link JScrollPane} sizes to its own width instead
 * of to this panel's preferred width.
 *
 * <h2>What a plain {@code JPanel} gets wrong here</h2>
 *
 * <p>A {@code JScrollPane} only narrows its view to the viewport's width when
 * the view is a {@link Scrollable} that says to. A plain {@code JPanel} is
 * not one, so the scroll pane takes its preferred width literally: if any
 * descendant prefers to be wider than the window — an unwrapped label
 * carrying a long build-reported string, a fixed-column grid that never
 * shrinks below its widest cell — the scroll pane grows a horizontal
 * scrollbar and shows that width rather than asking the content to narrow.
 * The window ends up narrower than its own content, not the other way round.
 *
 * <p>Returning {@code true} from {@link #getScrollableTracksViewportWidth()}
 * tells the scroll pane the opposite: give this view your width, whatever it
 * is, and let its height be whatever it needs. Combined with a layout that
 * actually reflows once it is handed a narrower width — see {@link
 * WrapLayout} — a panel built from this class becomes safe to resize down to
 * whatever the window allows, instead of quietly assuming it will always be
 * given as much width as its widest child prefers.
 *
 * <h2>Reusable</h2>
 *
 * <p>First introduced to fix the Overview tab and the enrichment coverage
 * view, both of which put variable-width, build-reported content straight
 * into a {@code JScrollPane}. The next panel that does the same should use
 * this instead of writing its own {@code getScrollableTracksViewportWidth}
 * override from scratch — that is the whole reason it lives in {@code
 * ui.theme} rather than beside either of them.
 */
public class ScrollableViewport extends JPanel implements Scrollable {

    private static final long serialVersionUID = 1L;

    public ScrollableViewport() {
        super();
    }

    public ScrollableViewport(LayoutManager layout) {
        super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    /**
     * Match the viewport's width exactly, so the enclosing scroll pane never
     * shows a horizontal scrollbar and never lets this panel be wider than
     * the window it lives in: the whole point of this class.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /**
     * Do not match the viewport's height: this panel's content decides how
     * tall it needs to be, and a vertical scrollbar is exactly how the
     * overflow is meant to be reached.
     */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
    }
}
