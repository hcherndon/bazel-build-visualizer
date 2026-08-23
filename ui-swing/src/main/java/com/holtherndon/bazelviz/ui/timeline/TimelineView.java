package com.holtherndon.bazelviz.ui.timeline;

import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The Timeline card: what ran when, at whatever zoom the user is at.
 *
 * <h2>Painting reads one object</h2>
 *
 * <p>Plan 24 requires that no SQLite access occurs during painting. This class
 * holds a {@link TimelineModel} and a {@link SpanWindow} and nothing else — no
 * connection, no reader, no query — so the requirement is not something to
 * remember while editing {@code paintComponent}, it is something the class has
 * no way to violate. Both are replaced by field assignment from the EDT after a
 * worker has built them.
 *
 * <h2>Two ways to draw, and the zoom decides</h2>
 *
 * <p>Broad: aggregate bins from the pyramid, one column per pixel or so, which
 * is why a million spans draw in under a millisecond. Close: the individual
 * spans in the visible range, fetched into a {@link SpanWindow}.
 *
 * <p>The switch is by span count rather than by zoom level, because what
 * matters is whether individual marks would be wider than a pixel. Below
 * {@link SpanWindow#MAX_SPANS} in view, spans; above it, bins — and the status
 * line says which, because a density plot and a span plot answer different
 * questions and a viewer who cannot tell them apart will misread one of them.
 */
public final class TimelineView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int HEADER_HEIGHT = 28;
    private static final int LANE_LABEL_WIDTH = 220;
    private static final int LANE_HEIGHT = 18;

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel empty =
            new JLabel("No timeline for this session.", SwingConstants.CENTER);
    private final JLabel status = new JLabel(" ");
    private final JLabel coverage = new JLabel(" ");
    private final JLabel hover = new JLabel(" ");

    private final JComboBox<LaneGrouping.By> groupChoice =
            new JComboBox<>(LaneGrouping.By.values());
    private final JComboBox<LaneGrouping.SortBy> sortChoice =
            new JComboBox<>(LaneGrouping.SortBy.values());
    private final JComboBox<TimelineColours.Mode> colourChoice = new JComboBox<>();
    private final JCheckBox followBox = new JCheckBox("Follow live", true);

    private final Canvas canvas = new Canvas();
    private final Header header = new Header();
    private final LaneLabels laneLabels = new LaneLabels();

    private TimelineModel model;
    private SpanWindow window = SpanWindow.EMPTY;
    private TimelineViewport viewport;
    private TimelineColours.Mode colourMode = TimelineColours.Mode.OUTCOME;
    private LongConsumer selectionHandler = node -> { };
    private Runnable viewportChanged = () -> { };
    private TimelineController.RangeListener rangeChanged = (from, to) -> { };

    public TimelineView() {
        super(new BorderLayout());
        PlainText.disableHtml(empty);
        PlainText.disableHtml(status);
        PlainText.disableHtml(coverage);
        PlainText.disableHtml(hover);
        empty.setEnabled(false);
        coverage.setFont(coverage.getFont().deriveFont(Font.ITALIC));

        followBox.addActionListener(event -> {
            if (viewport != null) {
                viewport = viewport.following(followBox.isSelected());
                repaintAll();
            }
        });
        colourChoice.addActionListener(event -> {
            TimelineColours.Mode chosen = (TimelineColours.Mode) colourChoice.getSelectedItem();
            if (chosen != null) {
                colourMode = chosen;
                repaintAll();
            }
        });
        groupChoice.addActionListener(event -> viewportChanged.run());
        sortChoice.addActionListener(event -> viewportChanged.run());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.add(PlainText.disableHtml(new JLabel("Group by:")));
        bar.add(groupChoice);
        bar.add(PlainText.disableHtml(new JLabel("Sort:")));
        bar.add(sortChoice);
        bar.add(PlainText.disableHtml(new JLabel("Colour:")));
        bar.add(colourChoice);
        bar.add(followBox);

        JPanel plot = new JPanel(new BorderLayout());
        plot.add(header, BorderLayout.NORTH);
        plot.add(laneLabels, BorderLayout.WEST);
        plot.add(canvas, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        footer.add(status, BorderLayout.NORTH);
        footer.add(hover, BorderLayout.CENTER);
        footer.add(coverage, BorderLayout.SOUTH);

        JPanel body = new JPanel(new BorderLayout());
        body.add(bar, BorderLayout.NORTH);
        body.add(plot, BorderLayout.CENTER);
        body.add(footer, BorderLayout.SOUTH);

        deck.add(empty, "empty");
        deck.add(body, "timeline");
        add(deck, BorderLayout.CENTER);
        cards.show(deck, "empty");
    }

    /** Called with a node id when the user selects a span. */
    public void onSelection(LongConsumer handler) {
        this.selectionHandler = handler;
    }

    /** Called when the user drags out a time range, or clears one. */
    public void onRangeChanged(TimelineController.RangeListener listener) {
        this.rangeChanged = listener;
    }

    /** Called when the visible range or grouping changed and data must be refetched. */
    public void onViewportChanged(Runnable handler) {
        this.viewportChanged = handler;
    }

    /**
     * Installs a freshly built model.
     *
     * <p>The viewport survives. This is the live-update path, and plan 14.4 is
     * explicit that new data must not move a user who has navigated — which
     * {@link TimelineViewport#withWall} enforces rather than this method
     * remembering to.
     */
    public void setModel(TimelineModel next) {
        this.model = next;
        int width = Math.max(1, canvas.getWidth());
        viewport = viewport == null
                ? TimelineViewport.fitting(next.wallStartMicros(), next.wallEndMicros(), width)
                : viewport.withWall(next.wallStartMicros(), next.wallEndMicros(), width);

        colourChoice.removeAllItems();
        for (TimelineColours.Mode mode : TimelineColours.Mode.values()) {
            if (mode.isAvailable(next)) {
                colourChoice.addItem(mode);
            }
        }
        if (!mode(colourMode).isAvailable(next)) {
            colourMode = TimelineColours.Mode.OUTCOME;
        }
        colourChoice.setSelectedItem(colourMode);

        coverage.setText(next.coverageNote().orElse(" "));
        coverage.setToolTipText(PlainText.tooltip(coverage.getText()));
        cards.show(deck, "timeline");
        repaintAll();
    }

    private static TimelineColours.Mode mode(TimelineColours.Mode current) {
        return current == null ? TimelineColours.Mode.OUTCOME : current;
    }

    /** Installs the exact spans for the current range. */
    public void setWindow(SpanWindow next) {
        this.window = next;
        repaintAll();
    }

    /** Shows the "no timeline" card with a reason. */
    public void showEmpty(String why) {
        empty.setText(why);
        empty.setToolTipText(PlainText.tooltip(why));
        model = null;
        viewport = null;
        window = SpanWindow.EMPTY;
        cards.show(deck, "empty");
    }

    /** The range the user is looking at, for the worker that fetches spans. */
    public Optional<long[]> visibleRange() {
        if (model == null || viewport == null || canvas.getWidth() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new long[] {
                (long) viewport.transform().microsAtX(0),
                (long) viewport.transform().microsAtX(canvas.getWidth())});
    }

    /** The grouping and sort the user has chosen. */
    public LaneGrouping.By grouping() {
        return (LaneGrouping.By) groupChoice.getSelectedItem();
    }

    public LaneGrouping.SortBy sortBy() {
        return (LaneGrouping.SortBy) sortChoice.getSelectedItem();
    }

    /** The current viewport, for tests and for the live update path. */
    public Optional<TimelineViewport> viewport() {
        return Optional.ofNullable(viewport);
    }

    /** Selects a node from elsewhere in the application, without moving the view. */
    public void select(long nodeId) {
        if (viewport != null) {
            viewport = viewport.selecting(OptionalLong.of(nodeId));
            repaintAll();
        }
    }

    private void repaintAll() {
        canvas.repaint();
        header.repaint();
        laneLabels.repaint();
        status.setText(describeStatus());
        status.setToolTipText(PlainText.tooltip(status.getText()));
    }

    /**
     * What the status line says.
     *
     * <p>Which of the two drawing modes is in use, and what is not on screen.
     * A density plot and a span plot answer different questions, and a viewer
     * who cannot tell which they are looking at will misread one of them.
     */
    private String describeStatus() {
        if (model == null) {
            return " ";
        }
        StringBuilder text = new StringBuilder();
        if (drawingSpans()) {
            text.append("Showing ").append(window.size()).append(" individual spans");
            if (window.droppedSpans() > 0) {
                text.append(" of ").append(window.size() + window.droppedSpans())
                        .append(" in view — zoom in to see the rest");
            }
        } else {
            text.append("Showing aggregate density over ").append(model.spanCount())
                    .append(" spans. Zoom in for individual actions.");
        }
        viewport.describeRange().ifPresent(range -> text.append("  ·  ").append(range));
        return text.toString();
    }

    /** True when the visible range is sparse enough to draw span by span. */
    private boolean drawingSpans() {
        return window.size() > 0 && window.droppedSpans() == 0;
    }

    // ------------------------------------------------------------------ parts

    /** The time axis. Its own component so it can stay put while lanes scroll. */
    private final class Header extends JComponent {

        private static final long serialVersionUID = 1L;

        Header() {
            setPreferredSize(new Dimension(0, HEADER_HEIGHT));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (model == null || viewport == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Color.GRAY);
                g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                // Ticks every hundred pixels, labelled in seconds from the
                // build's start rather than in epoch time: a reader cares how
                // far into the build something happened.
                for (int x = 0; x < getWidth(); x += 100) {
                    double micros = viewport.transform().microsAtX(x) - model.wallStartMicros();
                    g2.drawLine(x, getHeight() - 6, x, getHeight() - 1);
                    g2.drawString(String.format(
                            java.util.Locale.ROOT, "%.2fs", micros / 1_000_000.0), x + 3, 14);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** Lane names, aligned with the canvas rows. */
    private final class LaneLabels extends JComponent {

        private static final long serialVersionUID = 1L;

        LaneLabels() {
            setPreferredSize(new Dimension(LANE_LABEL_WIDTH, 0));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (model == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(Color.DARK_GRAY);
                List<TimelineModel.Lane> lanes = model.lanes();
                for (int i = 0; i < lanes.size(); i++) {
                    int y = i * LANE_HEIGHT + LANE_HEIGHT - 5;
                    if (y > getHeight()) {
                        break;
                    }
                    g2.drawString(lanes.get(i).name(), 4, y);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** The plot. Reads the model and the window and nothing else. */
    private final class Canvas extends JComponent {

        private static final long serialVersionUID = 1L;

        private int dragStartX = -1;
        private int dragCurrentX = -1;

        Canvas() {
            setPreferredSize(new Dimension(900, 400));
            MouseAdapter mouse = new MouseAdapter() {
                private int lastX;

                @Override
                public void mousePressed(MouseEvent event) {
                    lastX = event.getX();
                    if (event.isShiftDown()) {
                        dragStartX = event.getX();
                        dragCurrentX = event.getX();
                    }
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (viewport == null) {
                        return;
                    }
                    if (dragStartX >= 0) {
                        dragCurrentX = event.getX();
                        repaint();
                        return;
                    }
                    int dx = event.getX() - lastX;
                    lastX = event.getX();
                    viewport = viewport.navigatedTo(
                            viewport.transform().pannedByPixels(dx));
                    followBox.setSelected(false);
                    repaintAll();
                    viewportChanged.run();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (dragStartX < 0 || viewport == null) {
                        return;
                    }
                    long from = (long) viewport.transform().microsAtX(dragStartX);
                    long to = (long) viewport.transform().microsAtX(dragCurrentX);
                    viewport = Math.abs(dragCurrentX - dragStartX) < 3
                            ? viewport.withoutRange() : viewport.withRange(from, to);
                    dragStartX = -1;
                    dragCurrentX = -1;
                    repaintAll();
                    rangeChanged.rangeChanged(
                            viewport.rangeFromMicros(), viewport.rangeToMicros());
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    if (viewport == null || !drawingSpans()) {
                        return;
                    }
                    long at = (long) viewport.transform().microsAtX(event.getX());
                    for (int i = 0; i < window.size(); i++) {
                        if (window.startMicros(i) <= at && at < window.endMicros(i)) {
                            viewport = viewport.selecting(OptionalLong.of(window.nodeId(i)));
                            selectionHandler.accept(window.nodeId(i));
                            repaintAll();
                            return;
                        }
                    }
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    describeAt(event.getX());
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover.setText(" ");
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    if (viewport == null) {
                        return;
                    }
                    double factor = Math.pow(1.1, -event.getPreciseWheelRotation());
                    viewport = viewport.navigatedTo(
                            viewport.transform().zoomedAround(event.getX(), factor));
                    followBox.setSelected(false);
                    repaintAll();
                    viewportChanged.run();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (model == null || viewport == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g2.setColor(getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                if (drawingSpans()) {
                    paintSpans(g2);
                } else {
                    paintDensity(g2);
                }
                paintRange(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintDensity(Graphics2D g2) {
            TimelineLodIndex index = model.index();
            TimelineTransform transform = viewport.transform();
            int level = index.levelForScale(transform.pixelsPerMicro());
            int maxActive = Math.max(1, index.maxActiveCount(level));
            int height = getHeight();
            for (int x = 0; x < getWidth(); x++) {
                long at = (long) transform.microsAtX(x);
                if (at < index.wallStartMicros() || at >= index.wallEndMicros()) {
                    continue;
                }
                int bin = index.binIndexOf(level, at);
                int active = index.activeCount(level, bin);
                if (active == 0) {
                    continue;
                }
                int barHeight = Math.max(1, (int) ((long) active * height / maxActive));
                g2.setColor(TimelineColours.forBin(index, colourMode, level, bin));
                g2.fillRect(x, height - barHeight, 1, barHeight);
            }
        }

        private void paintSpans(Graphics2D g2) {
            TimelineTransform transform = viewport.transform();
            int lanes = Math.max(1, model.lanes().size());
            int rowHeight = Math.max(3, Math.min(LANE_HEIGHT, getHeight() / lanes));
            for (int i = 0; i < window.size(); i++) {
                int x0 = (int) transform.xForMicros(window.startMicros(i));
                int x1 = (int) transform.xForMicros(window.endMicros(i));
                int width = Math.max(1, x1 - x0);
                int row = lanes == 1 ? 0 : i % lanes;
                int y = row * rowHeight;
                g2.setColor(TimelineColours.forSpan(colourMode, window.flags(i)));
                g2.fillRect(x0, y, width, rowHeight - 1);
                if (viewport.selectedNode().isPresent()
                        && viewport.selectedNode().getAsLong() == window.nodeId(i)) {
                    g2.setColor(Color.BLACK);
                    g2.drawRect(x0, y, width, rowHeight - 1);
                }
            }
        }

        /** The drag-selected range, drawn over everything. */
        private void paintRange(Graphics2D g2) {
            if (dragStartX >= 0) {
                int x0 = Math.min(dragStartX, dragCurrentX);
                int width = Math.abs(dragCurrentX - dragStartX);
                g2.setColor(new Color(0, 0, 0, 40));
                g2.fillRect(x0, 0, width, getHeight());
                return;
            }
            if (!viewport.hasRange()) {
                return;
            }
            TimelineTransform transform = viewport.transform();
            int x0 = (int) transform.xForMicros(viewport.rangeFromMicros().getAsLong());
            int x1 = (int) transform.xForMicros(viewport.rangeToMicros().getAsLong());
            g2.setColor(new Color(0, 0, 0, 30));
            g2.fillRect(Math.min(x0, x1), 0, Math.abs(x1 - x0), getHeight());
        }
    }

    /**
     * What the bin under the pointer contains.
     *
     * <p>This is where plan 14.3's per-bin aggregates reach a person. They are
     * computed for every bin at every level and cost real memory — the byte
     * total alone is eight bytes a bin — so a build that stored them and showed
     * none would be paying for nothing. Everything the bin knows is here, and
     * everything it does not know says so rather than reading as a zero.
     */
    String describeAt(int x) {
        if (model == null || viewport == null) {
            return " ";
        }
        TimelineLodIndex index = model.index();
        long at = (long) viewport.transform().microsAtX(x);
        if (at < index.wallStartMicros() || at >= index.wallEndMicros()) {
            hover.setText(" ");
            return " ";
        }
        int level = index.levelForScale(viewport.transform().pixelsPerMicro());
        int bin = index.binIndexOf(level, at);
        int starts = index.startCount(level, bin);
        if (starts == 0) {
            hover.setText("Nothing started here.");
            return hover.getText();
        }

        StringBuilder text = new StringBuilder();
        text.append(starts).append(starts == 1 ? " action started" : " actions started")
                .append(" in this ").append(index.binWidthMicros(level) / 1000)
                .append(" ms bin; ").append(index.activeCount(level, bin)).append(" running");
        if (index.failureCount(level, bin) > 0) {
            text.append("; ").append(index.failureCount(level, bin)).append(" failed");
        }
        // Only reported where something knows. A bin that said "0 cache hits"
        // when nothing had been imported would be the loudest wrong claim on
        // the screen.
        if (index.cacheKnownCount(level, bin) > 0) {
            text.append("; ").append(index.cacheHitCount(level, bin)).append(" cached, ")
                    .append(index.cacheMissCount(level, bin)).append(" executed");
        }
        if (index.runnerKnownCount(level, bin) > 0) {
            text.append("; ").append(index.localCount(level, bin)).append(" local, ")
                    .append(index.remoteCount(level, bin)).append(" remote");
        }
        if (index.byteTotal(level, bin) > 0) {
            text.append("; at least ").append(index.byteTotal(level, bin) / 1024)
                    .append(" KiB of inputs");
        }
        index.uniformCategory(level, bin).ifPresent(category -> {
            String name = model.categoryNames().get(category);
            if (name != null) {
                text.append("; all ").append(name);
            }
        });
        hover.setText(text.toString());
        hover.setToolTipText(PlainText.tooltip(hover.getText()));
        return hover.getText();
    }

    /** The status line, for tests. */
    String statusText() {
        return status.getText();
    }

    /** The coverage note, for tests. */
    String coverageText() {
        return coverage.getText();
    }
}
