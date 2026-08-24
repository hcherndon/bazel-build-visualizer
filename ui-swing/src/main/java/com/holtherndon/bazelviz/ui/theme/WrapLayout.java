package com.holtherndon.bazelviz.ui.theme;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A {@link FlowLayout} whose reported size accounts for wrapping, so a
 * container using it inside a {@code JScrollPane} shrinks its row count to
 * the available width instead of demanding one unbroken row.
 *
 * <h2>Why plain {@code FlowLayout} is not enough</h2>
 *
 * <p>{@code FlowLayout.layoutContainer} already wraps components onto new
 * rows once the container's actual width runs out — that part works
 * unmodified. What it gets wrong is {@code preferredLayoutSize}: it always
 * reports the width and height of one unwrapped row, so anything that reads
 * preferred size to decide how much room to reserve — a {@code BoxLayout}
 * parent stacking this container above others, in particular — never learns
 * that wrapping happened, and reserves too little height for the rows that
 * actually get drawn. This class overrides both {@code preferredLayoutSize}
 * and {@code minimumLayoutSize} to compute the size the wrapped rows really
 * need, at whatever width the container currently has (or, on the very first
 * layout pass — before anything downstream has a real width yet — the width
 * of the nearest ancestor that does).
 *
 * <h2>Where this fits: the other half of the fix</h2>
 *
 * <p>Wrapping only pays off once the container is actually given a width
 * narrower than one unwrapped row would need — otherwise every row still
 * overflows no matter how many components are in it. That half is {@link
 * ScrollableViewport}: a plain {@code JPanel} inside a {@code JScrollPane}
 * gets handed its own preferred width unconditionally, which is exactly what
 * defeats wrapping before it can start. Use the two together: a row of
 * variable-width content that should reflow rather than force a horizontal
 * scrollbar — a tile grid, a chip list — is a container using this layout,
 * somewhere inside a {@link ScrollableViewport}.
 *
 * <p>First introduced to replace the Overview tab's tile rows, which used a
 * fixed {@code GridLayout(0, 4, …)}: every column was sized to its widest
 * cell's preferred width, so one long note made all four columns that wide
 * regardless of the window's actual size. Reusable for the next panel with
 * the same shape of problem.
 */
public final class WrapLayout extends FlowLayout {

    public WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    public WrapLayout() {
        this(LEFT, 0, 0);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return wrappedSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return wrappedSize(target, false);
    }

    /**
     * The size the wrapped rows need at {@code target}'s current width.
     *
     * @param usePreferred each member's preferred size when true, its
     *     minimum size when false
     */
    private Dimension wrappedSize(Container target, boolean usePreferred) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int horizontalMargin = insets.left + insets.right + getHgap() * 2;
            int available = widthOf(target) - horizontalMargin;
            int maxWidth = available > 0 ? available : Integer.MAX_VALUE;

            Dimension total = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            boolean rowStarted = false;

            for (Component member : target.getComponents()) {
                if (!member.isVisible()) {
                    continue;
                }
                Dimension size = usePreferred ? member.getPreferredSize() : member.getMinimumSize();
                if (rowStarted && rowWidth + getHgap() + size.width > maxWidth) {
                    total.width = Math.max(total.width, rowWidth);
                    total.height += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                    rowStarted = false;
                }
                if (rowStarted) {
                    rowWidth += getHgap();
                }
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
                rowStarted = true;
            }
            total.width = Math.max(total.width, rowWidth);
            total.height += rowHeight;

            total.width += horizontalMargin;
            total.height += insets.top + insets.bottom + getVgap() * 2;
            return total;
        }
    }

    /**
     * {@code target}'s own width, once it has been laid out at least once —
     * or, on the very first pass, when a never-yet-laid-out container reports
     * zero, the width of the nearest ancestor that has one.
     *
     * <p>Without this climb, the very first {@code preferredLayoutSize} call —
     * the one that decides how much room a {@code JScrollPane} gives the
     * content before anything downstream has a size to hand back — sees
     * nothing but zeros, falls back to "unconstrained", and reports one
     * unwrapped row exactly as plain {@code FlowLayout} would.
     */
    private static int widthOf(Container target) {
        Container probe = target;
        while (probe.getWidth() == 0 && probe.getParent() != null) {
            probe = probe.getParent();
        }
        return probe.getWidth();
    }
}
