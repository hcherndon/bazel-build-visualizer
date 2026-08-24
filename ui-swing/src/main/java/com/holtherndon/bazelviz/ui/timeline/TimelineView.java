package com.holtherndon.bazelviz.ui.timeline;

import com.holtherndon.bazelviz.ui.inspect.InspectorHeader;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
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
 *
 * <h2>A span's row is a fact about the span</h2>
 *
 * <p>Every span carries the lane key it belongs to, and painting places it by
 * looking that key up in the model's lane list. Panning and zooming refetch
 * the window but cannot move a span between rows, because nothing about the
 * fetch participates in the placement. Within a lane, overlapping spans stack
 * top-to-bottom by start time ({@link SpanStacking}), bounded at
 * {@link SpanStacking#MAX_SUB_ROWS} with the overflow drawn in the last
 * sub-row and its exact count on the status line.
 *
 * <h2>The live band</h2>
 *
 * <p>While a capture is running, a labelled band above the lanes shows the
 * targets configured and not yet completed, in {@link TimelineColours#IN_FLIGHT}
 * blue — target-level on purpose, because BEP has no action-start event and a
 * band that claimed to show running actions would be inventing them. The band
 * grows to the current wall clock, and while "Follow live" is on, so does the
 * right edge of the whole plot.
 *
 * <h2>Height is the data's, not the window's</h2>
 *
 * <p>Every sub-row is {@link #SUB_ROW_HEIGHT} pixels tall, always. A lane is
 * as tall as the sub-rows it actually needs — {@link SpanStacking#depthOf} of
 * its key times that height — so only lanes with real overlap grow, and the
 * plot's height is a sum of readable rows rather than a division of whatever
 * space the window happens to have. The earlier model divided the canvas
 * height by the lane count and then divided again by the lane's stacking
 * depth, which on any real session produced one- and two-pixel sub-rows: too
 * thin to see, too thin to click, and silently thinner the more the data had
 * to say. The in-flight band already used fixed rows; now the lanes agree
 * with it.
 *
 * <p>What absorbs the total height is a {@link JScrollPane}, not the rows.
 * The canvas reports that height as its preferred size and the lane labels
 * ride along as the scroll pane's row header, so labels and rows cannot drift
 * apart; the time axis stays outside the scroll pane, pinned. Horizontally
 * nothing scrolls — {@link Scrollable#getScrollableTracksViewportWidth} is
 * true and the horizontal policy is NEVER, because horizontal position is the
 * pan/zoom transform's business and two mechanisms for one axis would fight.
 * For the same reason the wheel over the plot zooms and is consumed there: a
 * zoom gesture never also scrolls.
 */
public final class TimelineView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int HEADER_HEIGHT = 28;
    private static final int LANE_LABEL_WIDTH = 220;

    /**
     * The height of one sub-row, in pixels — the same for a lane's every
     * stacked sub-row and for every row of the in-flight band. Fixed on
     * purpose: a row that shrank to fit the window would become unreadable and
     * unclickable exactly when the build had the most to show. A lane's own
     * height is this times its stacking depth, and the total is what the
     * scroll pane absorbs. Documented in docs/limits.md; LimitsDocTest checks
     * the value.
     */
    static final int SUB_ROW_HEIGHT = 18;

    /** How far one wheel-free scroll step moves, in pixels. */
    private static final int VERTICAL_SCROLL_UNIT = 16;
    /** The plot's preferred width; its real width is the viewport's. */
    private static final int PREFERRED_PLOT_WIDTH = 900;
    /** How much of the plot the window should try to show at once. */
    private static final int PREFERRED_PLOT_HEIGHT = 400;
    /** Max zoom: one pixel per microsecond, the same ceiling {@link TimelineCanvas} uses. */
    private static final double MAX_PIXELS_PER_MICRO = 1.0;
    /** How often the live edge and the in-flight band advance, in milliseconds. */
    private static final int LIVE_TICK_MILLIS = 250;
    /** The in-flight band's wash, so its rows cannot pass for lanes. */
    private static final Color BAND_BACKGROUND = new Color(0x42, 0x85, 0xF4, 24);
    /** The hairline between two lanes in the label column. */
    private static final Color LANE_SEPARATOR = new Color(0, 0, 0, 28);

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
    /** Vertical scrolling for the plot; the lane labels are its row header. */
    private final JScrollPane plotScroll = new JScrollPane(canvas);

    // ------------------------------------------------------- inline inspector
    private final JPanel inspector = new JPanel(new BorderLayout(8, 2));
    /**
     * The same header the shared inspector and the Events inspector wear:
     * title, subtitle, and one overflow button that holds the cross-view
     * actions. The timeline's own Close rides its fixed edge.
     */
    private final InspectorHeader inspectorHeader = new InspectorHeader();
    private final JTextArea inspectorBody = new JTextArea();

    private TimelineModel model;
    private SpanWindow window = SpanWindow.EMPTY;
    private SpanStacking stacking = SpanStacking.EMPTY;
    /** Lane row index by lane key, rebuilt whenever the model is. */
    private Map<String, Integer> laneRowByKey = Map.of();
    /**
     * The top of every lane row, relative to the first, with one more entry
     * than there are rows: the total height. Rows are the model's lanes plus
     * the extra row that catches spans in no current lane, and each is as tall
     * as its stacking depth needs, so this is rebuilt whenever the lanes or
     * the stacking change — {@link #relayoutLanes}.
     */
    private int[] laneTops = {0, SUB_ROW_HEIGHT};
    /** Spans whose key is in no current lane — transient while regrouping. */
    private long unmatchedSpans;
    private TimelineViewport viewport;
    private TimelineColours.Mode colourMode = TimelineColours.Mode.OUTCOME;
    private LongConsumer selectionHandler = node -> { };
    private LongConsumer actionPickedHandler = node -> { };
    private Runnable viewportChanged = () -> { };
    private TimelineController.RangeListener rangeChanged = (from, to) -> { };

    /**
     * Wall-clock now, for the live edge and the growing in-flight spans.
     * Injectable so a test can hold time still.
     */
    private LongSupplier clock = () -> System.currentTimeMillis() * 1_000L;
    /**
     * Advances the live edge between rebuilds. A {@link javax.swing.Timer}
     * fires on the EDT and does no I/O — it re-fits the viewport to the wall
     * clock and repaints from the model already in hand. Runs only while the
     * model says the capture is live.
     */
    private final javax.swing.Timer liveTicker =
            new javax.swing.Timer(LIVE_TICK_MILLIS, event -> onLiveTick());

    public TimelineView() {
        super(new BorderLayout());
        PlainText.disableHtml(empty);
        PlainText.disableHtml(status);
        PlainText.disableHtml(coverage);
        PlainText.disableHtml(hover);
        empty.setEnabled(false);
        coverage.setFont(coverage.getFont().deriveFont(Font.ITALIC));

        // The defaults are a choice, not an accident of enum order: Mnemonic
        // lanes sorted by name is the view that answers "what kind of work ran
        // when" on every session, while Runner — the enums' first member —
        // needs an execution log most sessions do not have.
        groupChoice.setSelectedItem(LaneGrouping.By.MNEMONIC);
        sortChoice.setSelectedItem(LaneGrouping.SortBy.NAME);

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

        // The axis is fixed: it stays put while the lanes scroll under it. The
        // strut is what keeps its x = 0 over the canvas's x = 0 rather than
        // over the lane labels, which is the coordinate system every tick
        // position is computed in.
        JPanel axis = new JPanel(new BorderLayout());
        axis.add(Box.createHorizontalStrut(LANE_LABEL_WIDTH), BorderLayout.WEST);
        axis.add(header, BorderLayout.CENTER);

        plotScroll.setRowHeaderView(laneLabels);
        plotScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        // Horizontal position belongs to the pan/zoom transform alone.
        plotScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        plotScroll.getVerticalScrollBar().setUnitIncrement(VERTICAL_SCROLL_UNIT);
        // The wheel over this plot means zoom. Leaving the scroll pane's own
        // wheel handling on would make one gesture mean two things at once.
        plotScroll.setWheelScrollingEnabled(false);
        plotScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel plot = new JPanel(new BorderLayout());
        plot.add(axis, BorderLayout.NORTH);
        plot.add(plotScroll, BorderLayout.CENTER);

        buildInspector();

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        footer.add(status, BorderLayout.NORTH);
        footer.add(hover, BorderLayout.CENTER);
        footer.add(coverage, BorderLayout.SOUTH);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(inspector);
        south.add(footer);

        JPanel body = new JPanel(new BorderLayout());
        body.add(bar, BorderLayout.NORTH);
        body.add(plot, BorderLayout.CENTER);
        body.add(south, BorderLayout.SOUTH);

        deck.add(empty, "empty");
        deck.add(body, "timeline");
        add(deck, BorderLayout.CENTER);
        cards.show(deck, "empty");
    }

    private void buildInspector() {
        inspector.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        inspectorBody.setEditable(false);
        inspectorBody.setOpaque(false);
        inspectorBody.setLineWrap(false);
        inspectorBody.setFont(status.getFont());
        JButton close = new JButton("Close");
        close.addActionListener(event -> hideInspector());
        inspectorHeader.addTrailing(close);
        inspector.add(inspectorHeader, BorderLayout.NORTH);
        inspector.add(inspectorBody, BorderLayout.CENTER);
        inspector.setVisible(false);
    }

    /** Called with a node id when the user selects a span. */
    public void onSelection(LongConsumer handler) {
        this.selectionHandler = handler;
    }

    /**
     * Called with an action id when a span is clicked and the inline inspector
     * needs that action's details — the controller's hook, distinct from
     * {@link #onSelection} which is the application's selection sync.
     */
    void onActionPicked(LongConsumer handler) {
        this.actionPickedHandler = handler;
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
     * Gives the inline inspector the shared navigation vocabulary. Until this
     * is called, a clicked segment still shows its details but offers no
     * jumps — honest absence, never dead buttons.
     */
    public void installEntityActions(EntityActions actions) {
        // Not "show on timeline": the segment it would show is the one
        // already under the pointer.
        inspectorHeader.installEntityActions(
                actions, Set.of(EntityActions.Command.SHOW_ON_TIMELINE));
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
        // Read before, reapply after: the model swap is what a live rebuild
        // does, and a user reading a lane halfway down has not asked to be
        // returned to the top every two seconds.
        int scrolledTo = scrollPosition();
        this.model = next;
        Map<String, Integer> rows = new HashMap<>();
        List<TimelineModel.Lane> lanes = next.lanes();
        for (int i = 0; i < lanes.size(); i++) {
            rows.putIfAbsent(lanes.get(i).key(), i);
        }
        this.laneRowByKey = Map.copyOf(rows);
        recountUnmatched();
        relayoutLanes();
        scrollTo(scrolledTo);
        int width = Math.max(1, canvas.getWidth());
        long fitEnd = liveWallEnd(next);
        viewport = viewport == null
                ? TimelineViewport.fitting(next.wallStartMicros(), fitEnd, width)
                : viewport.withWall(next.wallStartMicros(), fitEnd, width);

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
        if (next.liveBand().live()) {
            liveTicker.start();
        } else {
            liveTicker.stop();
        }
        repaintAll();
    }

    private static TimelineColours.Mode mode(TimelineColours.Mode current) {
        return current == null ? TimelineColours.Mode.OUTCOME : current;
    }

    /**
     * Where the wall's right edge is for fitting purposes: the data's own end
     * for a finished session, the wall clock for a live one — the clock is
     * what makes the right edge advance between arrivals of new data.
     */
    private long liveWallEnd(TimelineModel of) {
        return of.liveBand().live()
                ? Math.max(of.wallEndMicros(), clock.getAsLong())
                : of.wallEndMicros();
    }

    /**
     * One tick of the live clock: while following, re-fit to the advancing
     * wall and repaint, so the right edge and the in-flight spans grow in
     * real time rather than in two-second jumps. A user who has navigated is
     * left alone — {@link TimelineViewport#withWall} refuses to move them.
     */
    private void onLiveTick() {
        if (model == null || viewport == null || !model.liveBand().live()) {
            return;
        }
        if (viewport.following()) {
            viewport = viewport.withWall(
                    model.wallStartMicros(), liveWallEnd(model), Math.max(1, canvas.getWidth()));
        }
        canvas.repaint();
        header.repaint();
    }

    /** Installs the exact spans for the current range. */
    public void setWindow(SpanWindow next) {
        int scrolledTo = scrollPosition();
        this.window = next;
        this.stacking = SpanStacking.of(next);
        recountUnmatched();
        // A refetch can change a lane's depth, and so the plot's total height.
        // The position is kept where the user put it, clamped to what is now
        // there rather than left pointing past the end.
        relayoutLanes();
        scrollTo(scrolledTo);
        repaintAll();
    }

    private void recountUnmatched() {
        long count = 0;
        for (int i = 0; i < window.size(); i++) {
            if (!laneRowByKey.containsKey(window.laneKey(i))) {
                count++;
            }
        }
        unmatchedSpans = count;
    }

    /** Shows the "no timeline" card with a reason. */
    public void showEmpty(String why) {
        empty.setText(why);
        empty.setToolTipText(PlainText.tooltip(why));
        model = null;
        viewport = null;
        window = SpanWindow.EMPTY;
        stacking = SpanStacking.EMPTY;
        laneRowByKey = Map.of();
        unmatchedSpans = 0;
        relayoutLanes();
        // There is nothing to be scrolled into any more; the next session
        // starts at its own top rather than at the last one's offset.
        scrollTo(0);
        liveTicker.stop();
        hideInspector();
        cards.show(deck, "empty");
    }

    // ---------------------------------------------------------------- scroll

    /** How far down the plot is scrolled, in pixels. */
    private int scrollPosition() {
        return plotScroll.getViewport().getViewPosition().y;
    }

    /**
     * Scrolls to {@code y}, clamped to what the plot is currently tall enough
     * to show. Clamping here rather than trusting the viewport is what keeps a
     * rebuild that shortened the plot — fewer lanes, or a window whose lanes
     * stack less deeply — from leaving the view parked past the end.
     */
    private void scrollTo(int y) {
        JViewport port = plotScroll.getViewport();
        int limit = Math.max(0, contentHeight() - port.getExtentSize().height);
        canvas.revalidate();
        laneLabels.revalidate();
        port.setViewPosition(new Point(0, Math.clamp(y, 0, limit)));
    }

    /**
     * Scrolls the selected span's lane into view, if that span is in the
     * current window. The reveal paths' other half: selecting from elsewhere
     * in the application highlights a span, and a highlight below the fold is
     * one the user cannot see.
     */
    private void scrollSelectionIntoView() {
        if (viewport == null || viewport.selectedNode().isEmpty()) {
            return;
        }
        long node = viewport.selectedNode().getAsLong();
        for (int i = 0; i < window.size(); i++) {
            if (window.nodeId(i) != node) {
                continue;
            }
            int row = rowOfSpan(i);
            // Width 1 at x 0 on purpose: this may move the view vertically and
            // must never nudge it horizontally, where the transform rules.
            canvas.scrollRectToVisible(new Rectangle(
                    0, bandHeight() + laneTop(row), 1, laneHeight(row)));
            return;
        }
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

    /**
     * Selects a node from elsewhere in the application, without moving the
     * view in time — the pan and the zoom are the user's. Vertically it does
     * move: a selection scrolled off the bottom of the lanes would be a reveal
     * that revealed nothing.
     */
    public void select(long nodeId) {
        if (viewport != null) {
            viewport = viewport.selecting(OptionalLong.of(nodeId));
            scrollSelectionIntoView();
            repaintAll();
        }
    }

    // ------------------------------------------------------------- inspector

    /**
     * Shows the inline inspector for one clicked segment. Called by the
     * controller once details arrive, and by the view itself with what it
     * already knows the moment of the click.
     */
    public void showInspector(SpanDetails details) {
        // The controller words the first line as the segment's one-line
        // identity — "Action 7 (Javac) — SUCCESS" — which is exactly what the
        // header's subtitle slot is for. The rest stay the body.
        List<String> lines = details.lines();
        inspectorHeader.show(
                details.title(),
                lines.isEmpty() ? Optional.empty() : Optional.of(lines.getFirst()),
                details.refs());
        inspectorBody.setText(lines.isEmpty()
                ? "" : String.join("\n", lines.subList(1, lines.size())));
        inspector.setVisible(true);
        inspector.revalidate();
        inspector.repaint();
    }

    /** Hides the inline inspector. */
    public void hideInspector() {
        inspector.setVisible(false);
        inspectorHeader.clear();
    }

    /** Whether the inline inspector is showing, for tests. */
    boolean inspectorVisibleForTest() {
        return inspector.isVisible();
    }

    /** The inspector's shared header, for tests that read it or open its menu. */
    InspectorHeader inspectorHeaderForTest() {
        return inspectorHeader;
    }

    /** The inspector's title text, for tests. */
    String inspectorTitleForTest() {
        return inspectorHeader.titleForTest();
    }

    /**
     * Bounds a user-driven pan or zoom to the session's own wall, so dragging or
     * scrolling past the edge cannot leave the transform to wander arbitrarily
     * far from it.
     *
     * <h2>Why this lives here</h2>
     *
     * <p>{@link TimelineTransform} is deliberately pure arithmetic with no idea
     * what a "wall" is, so it cannot clamp itself. {@link TimelineViewport#navigatedTo}
     * does not have the wall either — it carries a transform, a selection and a
     * range, and the wall lives on {@link TimelineModel}. This view is where a
     * transform and a model are in scope at the same moment, which is exactly
     * why the earlier bug existed: nothing else in the split had everything it
     * needed to enforce the bound, so nothing did.
     *
     * <p>The floor is ported, not reinvented, from {@code TimelineCanvas}'s own
     * {@code clamped()} (used only by the benchmark spike, never by this live
     * view): zoom is floored so the wall can never shrink to less than half
     * the visible width, and the offset is floored and ceilinged so the wall
     * can be panned at most half a screen past either edge. That is a bound,
     * not a hard stop at zero — a build's wall can still be scrolled a little
     * past its own edge, the same way {@link TimelineCanvas}'s spike allows on
     * purpose — but it is now a bound. Before this, the canvas's own mouse
     * handlers fed {@link TimelineTransform#pannedByPixels} and
     * {@link TimelineTransform#zoomedAround} straight into the viewport with
     * nothing checking either, so the offset {@link Header#paintComponent}
     * subtracts {@link TimelineModel#wallStartMicros} from could drift to any
     * value at all — which is what let the axis print an arbitrarily negative
     * time. Clamping only the label there would have hidden that the transform
     * itself had wandered off; this clamps the transform, so the label is
     * correct because what it is printing is.
     */
    private TimelineTransform clamp(TimelineTransform proposed) {
        if (model == null) {
            return proposed;
        }
        int width = Math.max(1, canvas.getWidth());
        double wallSpan = liveWallEnd(model) - model.wallStartMicros();
        if (!(wallSpan > 0)) {
            // Not reachable while a model exists -- build() never hands out one
            // whose wall is not a real span -- but a transform this cannot make
            // sense of is a transform it should not touch.
            return proposed;
        }
        double minPpm = width / (2.0 * wallSpan); // zoom-out floor: wall fills half the width
        double ppm = Math.clamp(
                proposed.pixelsPerMicro(), minPpm, Math.max(MAX_PIXELS_PER_MICRO, minPpm));
        double visible = width / ppm;
        double offset = Math.clamp(proposed.offsetMicros(),
                model.wallStartMicros() - 0.5 * visible,
                model.wallStartMicros() + wallSpan - 0.5 * visible);
        return ppm == proposed.pixelsPerMicro() && offset == proposed.offsetMicros()
                ? proposed
                : new TimelineTransform(offset, ppm);
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
            if (stacking.overflowCount() > 0) {
                text.append("  ·  ").append(stacking.overflowCount())
                        .append(" overlapping spans share their lane's last sub-row (")
                        .append(SpanStacking.MAX_SUB_ROWS)
                        .append(" sub-rows per lane) — zoom in to separate them");
            }
            if (unmatchedSpans > 0) {
                text.append("  ·  ").append(unmatchedSpans)
                        .append(" spans are in no current lane while the grouping updates");
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

    // -------------------------------------------------------------- geometry

    /**
     * How many rows the in-flight band occupies: none for a finished session,
     * otherwise one per drawable in-flight target up to the stacking budget —
     * and at least one, so a live build with nothing in flight still shows a
     * labelled, empty band rather than silently having none.
     */
    int bandRows() {
        if (model == null || !model.liveBand().live()) {
            return 0;
        }
        return Math.max(1, Math.min(
                model.liveBand().inFlight().size(), SpanStacking.MAX_SUB_ROWS));
    }

    /** The band's height in pixels; lanes start below it. */
    int bandHeight() {
        return bandRows() * SUB_ROW_HEIGHT;
    }

    /**
     * Recomputes every lane row's top and the plot's total height.
     *
     * <p>Called whenever the lanes change or the stacking does, which is the
     * whole of what the geometry depends on: not the canvas's size, which is
     * the point of the change — a lane is as tall as its content needs and the
     * scroll pane deals with the sum.
     */
    private void relayoutLanes() {
        int lanes = model == null ? 0 : model.lanes().size();
        // Every lane, then the extra row for spans in no current lane, then
        // the total. The extra row is always there so a straggler always has
        // somewhere real to be drawn rather than being placed past the end.
        int[] tops = new int[lanes + 2];
        int y = 0;
        for (int row = 0; row <= lanes; row++) {
            tops[row] = y;
            y += heightOfRow(row);
        }
        tops[lanes + 1] = y;
        laneTops = tops;
    }

    /** The height of lane row {@code row}: its stacking depth, in sub-rows. */
    private int heightOfRow(int row) {
        if (model == null || row >= model.lanes().size()) {
            return SUB_ROW_HEIGHT;
        }
        int depth = stacking.depthOf(model.lanes().get(row).key());
        return Math.clamp(depth, 1, SpanStacking.MAX_SUB_ROWS) * SUB_ROW_HEIGHT;
    }

    /** The top of lane row {@code row}, relative to the first lane. */
    int laneTop(int row) {
        return laneTops[Math.clamp(row, 0, laneTops.length - 1)];
    }

    /**
     * The height of lane row {@code row} — {@link SpanStacking#depthOf} of its
     * key times {@link #SUB_ROW_HEIGHT}, so a lane with no overlap is one
     * sub-row tall and only lanes that actually stack take more room.
     */
    int laneHeight(int row) {
        int at = Math.clamp(row, 0, laneTops.length - 2);
        return laneTops[at + 1] - laneTops[at];
    }

    /** Every lane row together, in pixels. */
    int totalLaneHeight() {
        return laneTops[laneTops.length - 1];
    }

    /** What the plot is tall enough to need: the band and every lane row. */
    int contentHeight() {
        return bandHeight() + totalLaneHeight();
    }

    /**
     * The lane row a span paints in: its key's position in the model's lane
     * list, or the extra row below every lane for a span whose key is in no
     * current lane (transient while a regroup's model catches up with its
     * window). Never a function of the span's position in the window — that
     * was the zoom-shuffle bug.
     */
    int rowOfSpan(int i) {
        Integer row = laneRowByKey.get(window.laneKey(i));
        return row == null
                ? (model == null ? 0 : model.lanes().size())
                : row;
    }

    /**
     * The painted bounds of span {@code i}, for painting and hit-testing
     * alike. Its sub-row is {@link #SUB_ROW_HEIGHT} tall whatever else is on
     * screen, less one pixel so two stacked sub-rows read as two.
     */
    private Rectangle boundsOfSpan(int i) {
        TimelineTransform transform = viewport.transform();
        int x0 = (int) transform.xForMicros(window.startMicros(i));
        int x1 = (int) transform.xForMicros(window.endMicros(i));
        int width = Math.max(1, x1 - x0);
        int row = rowOfSpan(i);
        // The row's own height says how many sub-rows it has, so a span can
        // never be placed below the lane it belongs to — including a span in
        // no current lane, whose extra row is one sub-row tall.
        int depth = laneHeight(row) / SUB_ROW_HEIGHT;
        int subRow = Math.clamp(stacking.subRow(i), 0, depth - 1);
        int y = bandHeight() + laneTop(row) + subRow * SUB_ROW_HEIGHT;
        return new Rectangle(x0, y, width, SUB_ROW_HEIGHT - 1);
    }

    /** The span under a point, or -1. Only meaningful while drawing spans. */
    int spanIndexAt(int x, int y) {
        if (viewport == null || !drawingSpans()) {
            return -1;
        }
        for (int i = 0; i < window.size(); i++) {
            if (boundsOfSpan(i).contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** The in-flight target under a point, or -1. */
    int bandIndexAt(int x, int y) {
        if (model == null || viewport == null || y >= bandHeight()) {
            return -1;
        }
        List<TimelineModel.LiveBand.InFlight> inFlight = model.liveBand().inFlight();
        TimelineTransform transform = viewport.transform();
        long now = clock.getAsLong();
        int rows = bandRows();
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            int row = Math.min(i, rows - 1);
            int y0 = row * SUB_ROW_HEIGHT;
            if (y < y0 || y >= y0 + SUB_ROW_HEIGHT) {
                continue;
            }
            int x0 = (int) transform.xForMicros(inFlight.get(i).startMicros());
            int x1 = (int) transform.xForMicros(Math.max(
                    inFlight.get(i).startMicros(), now));
            if (x >= x0 && x <= Math.max(x0 + 1, x1)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * What the band says about itself, with exact numbers. "Targets", loudly,
     * so nobody reads its spans as actions; "received", because the positions
     * are BEP receive times, the only live signal target events carry.
     */
    static String bandLabel(TimelineModel.LiveBand band) {
        if (band.totalInFlight() == 0) {
            return "In-flight targets (live): none right now";
        }
        StringBuilder text = new StringBuilder("In-flight targets (live): ")
                .append(band.totalInFlight())
                .append(" configured, not yet completed — positioned by BEP receive time");
        long drawable = band.totalInFlight() - band.withoutTimestamps();
        if (band.inFlight().size() < drawable) {
            text.append("; drawing the earliest ").append(band.inFlight().size())
                    .append(" of ").append(drawable);
        }
        if (band.withoutTimestamps() > 0) {
            text.append("; ").append(band.withoutTimestamps())
                    .append(" have no received timestamp and are counted but not drawn");
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ parts

    /** One axis tick: the pixel it sits at, and the label to draw there. */
    record Tick(int x, String label) {}

    /**
     * The ticks the axis header draws for one frame -- pure computation, no
     * {@link Graphics2D}, so what gets drawn can be checked without painting.
     *
     * <p>Every hundred pixels, labelled in seconds from the build's start
     * rather than in epoch time: a reader cares how far into the build
     * something happened. {@link #clamp} lets a pan or a zoom show a little
     * space before the wall as a margin, on purpose -- but there is no build
     * time before the build started, so an x whose computed time falls before
     * {@code model.wallStartMicros()} is left out of the result entirely, tick
     * and label both. Printing "-0.42s" there would assert a time that does
     * not exist; printing "0.00s" at several different x positions would
     * assert a position the viewport is not actually at. Neither is honest,
     * so the margin gets no tick at all rather than a wrong one.
     */
    static List<Tick> ticksFor(TimelineViewport viewport, TimelineModel model, int width) {
        List<Tick> ticks = new ArrayList<>();
        for (int x = 0; x < width; x += 100) {
            double micros = viewport.transform().microsAtX(x) - model.wallStartMicros();
            if (micros < 0) {
                continue;
            }
            ticks.add(new Tick(x, String.format(
                    java.util.Locale.ROOT, "%.2fs", micros / 1_000_000.0)));
        }
        return ticks;
    }

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
                for (Tick tick : ticksFor(viewport, model, getWidth())) {
                    g2.drawLine(tick.x(), getHeight() - 6, tick.x(), getHeight() - 1);
                    g2.drawString(tick.label(), tick.x() + 3, 14);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Lane names, aligned with the canvas rows, below the band's own label.
     *
     * <p>The scroll pane's row header, so the alignment is not something this
     * has to maintain: it scrolls with the canvas by construction, and reports
     * the same height the canvas does.
     */
    private final class LaneLabels extends JComponent implements Scrollable {

        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(LANE_LABEL_WIDTH, Math.max(SUB_ROW_HEIGHT, contentHeight()));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return VERTICAL_SCROLL_UNIT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(VERTICAL_SCROLL_UNIT, visible.height - VERTICAL_SCROLL_UNIT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            // The label column is as wide as it says, never as wide as the
            // window: names are read left to right and truncating them to a
            // resize would lose the end of every long one.
            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport port
                    && port.getHeight() > getPreferredSize().height;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (model == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int band = bandHeight();
                if (band > 0) {
                    g2.setColor(BAND_BACKGROUND);
                    g2.fillRect(0, 0, getWidth(), band);
                    g2.setColor(new Color(0x1A, 0x56, 0xB0));
                    g2.drawString("In-flight targets", 4, Math.min(band - 5, 13));
                }
                Rectangle clip = g2.getClipBounds();
                List<TimelineModel.Lane> lanes = model.lanes();
                for (int i = 0; i < lanes.size(); i++) {
                    int top = band + laneTop(i);
                    int height = laneHeight(i);
                    if (clip != null
                            && (top + height < clip.y || top > clip.y + clip.height)) {
                        continue;
                    }
                    // The name sits in the lane's first sub-row, so a lane that
                    // stacks six deep is still labelled where it begins.
                    g2.setColor(Color.DARK_GRAY);
                    g2.drawString(lanes.get(i).name(), 4, top + SUB_ROW_HEIGHT - 5);
                    // Lanes are no longer all the same height, so where one
                    // ends has to be visible rather than inferred.
                    g2.setColor(LANE_SEPARATOR);
                    g2.drawLine(0, top + height - 1, getWidth(), top + height - 1);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** The plot. Reads the model and the window and nothing else. */
    private final class Canvas extends JComponent implements Scrollable {

        private static final long serialVersionUID = 1L;

        private int dragStartX = -1;
        private int dragCurrentX = -1;

        Canvas() {
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
                            clamp(viewport.transform().pannedByPixels(dx)));
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
                    clickAt(event.getX(), event.getY());
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    if (model != null && event.getY() < bandHeight()) {
                        hover.setText(bandLabel(model.liveBand()));
                        hover.setToolTipText(PlainText.tooltip(hover.getText()));
                        return;
                    }
                    describeAt(event.getX());
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover.setText(" ");
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    // The wheel over the plot means zoom, and only zoom. The
                    // scroll pane's own wheel handling is off as well; both,
                    // because a gesture that zoomed and scrolled at once would
                    // be unusable and neither half is obviously the one to
                    // rely on.
                    event.consume();
                    if (viewport == null) {
                        return;
                    }
                    double factor = Math.pow(1.1, -event.getPreciseWheelRotation());
                    viewport = viewport.navigatedTo(
                            clamp(viewport.transform().zoomedAround(event.getX(), factor)));
                    followBox.setSelected(false);
                    repaintAll();
                    viewportChanged.run();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        /**
         * As tall as the band and the lanes need, which is what the scroll
         * pane scrolls. The width is a hint only: {@link
         * #getScrollableTracksViewportWidth} makes the real width the
         * viewport's, because horizontal position is the transform's.
         */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                    PREFERRED_PLOT_WIDTH, Math.max(SUB_ROW_HEIGHT, contentHeight()));
        }

        /**
         * How much of the plot the window should try to show, which is not the
         * same as how tall the plot is: a session with three hundred lanes asks
         * for a window of a reasonable size and scrolls, rather than asking for
         * a window taller than the screen.
         */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(PREFERRED_PLOT_WIDTH, PREFERRED_PLOT_HEIGHT);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return VERTICAL_SCROLL_UNIT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(VERTICAL_SCROLL_UNIT, visible.height - VERTICAL_SCROLL_UNIT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /**
         * When the lanes do not fill the window, the plot stretches to it
         * instead of leaving a differently coloured band of nothing below —
         * and the aggregate density, which draws over the whole height, gets
         * the room it had before there was a scroll pane.
         */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport port
                    && port.getHeight() > getPreferredSize().height;
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
                paintBand(g2);
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

        /**
         * The in-flight band: blue spans growing from each target's received
         * configuration to the wall clock, on a washed background with its own
         * label so its rows cannot pass for action lanes. A target beyond the
         * sub-row budget draws in the last row; the label carries the exact
         * counts either way.
         */
        private void paintBand(Graphics2D g2) {
            int band = bandHeight();
            if (band == 0) {
                return;
            }
            g2.setColor(BAND_BACKGROUND);
            g2.fillRect(0, 0, getWidth(), band);
            TimelineTransform transform = viewport.transform();
            List<TimelineModel.LiveBand.InFlight> inFlight = model.liveBand().inFlight();
            long now = clock.getAsLong();
            int rows = bandRows();
            g2.setColor(TimelineColours.IN_FLIGHT);
            for (int i = 0; i < inFlight.size(); i++) {
                int row = Math.min(i, rows - 1);
                long start = inFlight.get(i).startMicros();
                int x0 = (int) transform.xForMicros(start);
                int x1 = (int) transform.xForMicros(Math.max(start, now));
                g2.fillRect(x0, row * SUB_ROW_HEIGHT + 1,
                        Math.max(1, x1 - x0), SUB_ROW_HEIGHT - 3);
            }
            g2.setColor(new Color(0x1A, 0x56, 0xB0));
            g2.drawString(bandLabel(model.liveBand()), 4, Math.min(band - 5, 13));
            g2.setColor(Color.GRAY);
            g2.drawLine(0, band - 1, getWidth(), band - 1);
        }

        private void paintDensity(Graphics2D g2) {
            TimelineLodIndex index = model.index();
            TimelineTransform transform = viewport.transform();
            int level = index.levelForScale(transform.pixelsPerMicro());
            int maxActive = Math.max(1, index.maxActiveCount(level));
            int top = bandHeight();
            int height = getHeight() - top;
            if (height <= 0) {
                return;
            }
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
                g2.fillRect(x, top + height - barHeight, 1, barHeight);
            }
        }

        /**
         * Places every span by its lane key and its stacked sub-row — facts
         * about the span — never by its index in the window, which is what
         * used to shuffle rows on every pan and zoom.
         */
        private void paintSpans(Graphics2D g2) {
            for (int i = 0; i < window.size(); i++) {
                Rectangle bounds = boundsOfSpan(i);
                g2.setColor(TimelineColours.forSpan(colourMode, window.flags(i)));
                g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                if (viewport.selectedNode().isPresent()
                        && viewport.selectedNode().getAsLong() == window.nodeId(i)) {
                    g2.setColor(Color.BLACK);
                    g2.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
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
     * One click: an in-flight target opens the inspector with what the band
     * already knows; a span selects it, shows the inspector immediately with
     * the facts in hand, and asks the controller for the rest.
     */
    void clickAt(int x, int y) {
        if (model == null || viewport == null) {
            return;
        }
        int bandIndex = bandIndexAt(x, y);
        if (bandIndex >= 0) {
            TimelineModel.LiveBand.InFlight target =
                    model.liveBand().inFlight().get(bandIndex);
            double intoBuild =
                    (target.startMicros() - model.wallStartMicros()) / 1_000_000.0;
            showInspector(new SpanDetails(
                    target.label(),
                    List.of(
                            "Target, in flight: configured and not yet completed.",
                            String.format(java.util.Locale.ROOT,
                                    "Configured %.2fs into the build (BEP receive time).",
                                    intoBuild)),
                    List.of(new EntityRef.TargetLabel(target.label()))));
            return;
        }
        int i = spanIndexAt(x, y);
        if (i < 0) {
            return;
        }
        long nodeId = window.nodeId(i);
        viewport = viewport.selecting(OptionalLong.of(nodeId));
        double start = (window.startMicros(i) - model.wallStartMicros()) / 1_000_000.0;
        double duration = (window.endMicros(i) - window.startMicros(i)) / 1_000_000.0;
        showInspector(new SpanDetails(
                "Action " + nodeId,
                List.of(String.format(java.util.Locale.ROOT,
                        "Ran %.2fs into the build for %.3fs. Fetching details…",
                        start, duration)),
                List.of(new EntityRef.ActionId(nodeId))));
        // A lane the click only caught the edge of comes fully into view, so
        // the inspector below is describing something the user can see.
        scrollSelectionIntoView();
        selectionHandler.accept(nodeId);
        actionPickedHandler.accept(nodeId);
        repaintAll();
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

    /** Pins the live clock, for tests. */
    void setClockForTest(LongSupplier fixed) {
        this.clock = fixed;
    }

    /** Whether the follow-live toggle is on, for tests. */
    boolean followingForTest() {
        return followBox.isSelected();
    }

    /**
     * The plot component, for tests that drive its real mouse and wheel
     * listeners directly rather than re-implementing what they do.
     */
    JComponent canvasForTest() {
        return canvas;
    }

    /** The plot's scroll pane, for tests that drive or read the scroll state. */
    JScrollPane scrollForTest() {
        return plotScroll;
    }

    /** The lane label column, for tests that check it reports the canvas's height. */
    JComponent laneLabelsForTest() {
        return laneLabels;
    }
}
