package com.holtherndon.bazelviz.ui.timeline;

import com.formdev.flatlaf.FlatLaf;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.InspectorHeader;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

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
 * Ordinary wheel and two-finger vertical gestures scroll the lanes. A
 * two-finger horizontal gesture (reported by Swing as Shift-wheel on macOS)
 * pans in time. Control/Command-wheel zooms around the pointer and is consumed
 * so it never also scrolls. The canvas, axis and lane labels all route through
 * that same contract rather than changing meaning at a component boundary.
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

    /**
     * Width of the needle used when a real duration is narrower than this on
     * screen. The needle is deliberately distinct from a proportional bar;
     * it keeps short work visible without pretending its duration is longer.
     * Documented in docs/limits.md; LimitsDocTest checks the value.
     */
    public static final int SHORT_SPAN_MARKER_WIDTH = 3;

    /** How far one wheel or trackpad scroll step moves, in pixels. */
    private static final int VERTICAL_SCROLL_UNIT = 16;
    /** Quiet time before a changed time range asks the worker for another window. */
    private static final int VIEWPORT_REFRESH_DELAY_MILLIS = 60;
    /** Vertical breathing room around each span bar. */
    private static final int SPAN_VERTICAL_INSET = 2;
    /** Slightly wider than a needle so short events remain easy to click. */
    public static final int MINIMUM_SPAN_HIT_WIDTH = 7;
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
    private static final String INTERACTION_HINT =
            "Scroll: lanes  ·  Two-finger sideways: pan  ·  Ctrl/⌘+scroll or pinch: zoom"
                    + "  ·  Drag: pan"
                    + "  ·  Shift+drag: select";
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
    private final JButton zoomOut = new JButton("−");
    private final JButton zoomIn = new JButton("+");
    private final JButton fitBuild = new JButton("Fit build");

    private final Canvas canvas = new Canvas();
    private final Header header = new Header();
    private final LaneLabels laneLabels = new LaneLabels();
    private final MacMagnificationSupport.Registration magnificationSupport;
    /** Vertical scrolling for the plot; the lane labels are its row header. */
    private final JScrollPane plotScroll = new JScrollPane(canvas);

    // --------------------------------------------------------- side inspector
    private final JPanel inspector = new JPanel(new BorderLayout(8, 2));
    /**
     * The same header the shared inspector and the Events inspector wear:
     * title, subtitle, and one overflow button that holds the cross-view
     * actions. The timeline's own Close rides its fixed edge.
     */
    private final InspectorHeader inspectorHeader = new InspectorHeader();
    private final JTextArea inspectorBody = new JTextArea();
    private final JButton inspectorClose = new JButton("Close");
    private final JSplitPane contentSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

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
    /** Span under the pointer; paint-only and reset whenever the window changes. */
    private int hoveredSpan = -1;
    /** Fractional trackpad movement that has not yet reached one device pixel. */
    private double verticalWheelRemainder;
    /** Fractional horizontal trackpad movement retained across native events. */
    private double horizontalWheelRemainder;
    private boolean inspectorHasDetails;
    private EntityActions entityActions;
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
    /**
     * Repaints every gesture immediately, but waits briefly before querying.
     * Smooth trackpad input can deliver dozens of deltas per second; issuing
     * one SQLite read for each delta would make the data trail the pointer.
     */
    private final javax.swing.Timer viewportRefreshTimer =
            new javax.swing.Timer(VIEWPORT_REFRESH_DELAY_MILLIS, event -> viewportChanged.run());

    public TimelineView() {
        super(new BorderLayout());
        magnificationSupport = MacMagnificationSupport.install(canvas,
                magnification -> zoomAround(
                        canvas.zoomAnchorX(), MacMagnificationSupport.zoomFactor(magnification)));
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
        groupChoice.setToolTipText("Choose how actions are divided into vertical lanes");
        sortChoice.setToolTipText("Choose the order of the timeline lanes");
        colourChoice.setToolTipText("Choose what each span colour communicates");
        followBox.setToolTipText("Keep the time axis following the end of a live build");

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

        viewportRefreshTimer.setRepeats(false);
        zoomOut.setToolTipText("Zoom out around the centre of the timeline");
        zoomOut.getAccessibleContext().setAccessibleName("Zoom out");
        zoomOut.addActionListener(event -> zoomAround(canvas.getWidth() / 2.0, 1.0 / 1.5));
        zoomIn.setToolTipText("Zoom in around the centre of the timeline");
        zoomIn.getAccessibleContext().setAccessibleName("Zoom in");
        zoomIn.addActionListener(event -> zoomAround(canvas.getWidth() / 2.0, 1.5));
        fitBuild.setToolTipText("Show the full invocation on the time axis");
        fitBuild.addActionListener(event -> fitBuild());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.add(PlainText.disableHtml(new JLabel("Group by:")));
        bar.add(groupChoice);
        bar.add(PlainText.disableHtml(new JLabel("Sort:")));
        bar.add(sortChoice);
        bar.add(PlainText.disableHtml(new JLabel("Colour:")));
        bar.add(colourChoice);
        bar.add(followBox);
        bar.add(zoomOut);
        bar.add(zoomIn);
        bar.add(fitBuild);

        // The axis is fixed: it stays put while the lanes scroll under it. The
        // strut is what keeps its x = 0 over the canvas's x = 0 rather than
        // over the lane labels, which is the coordinate system every tick
        // position is computed in.
        JPanel axis = new JPanel(new BorderLayout());
        axis.add(Box.createHorizontalStrut(LANE_LABEL_WIDTH), BorderLayout.WEST);
        axis.add(header, BorderLayout.CENTER);

        plotScroll.setRowHeaderView(laneLabels);
        // ALWAYS rather than AS_NEEDED: FlatLaf's default vertical scrollbar
        // is thin and low-contrast, and AS_NEEDED made it appear and
        // disappear as the plot's height changed, so a scrollable plot too
        // often looked exactly like a non-scrollable one. Always showing it,
        // restyled below, is the whole fix — a bar nobody notices is a bar
        // that might as well not scroll.
        plotScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        // Horizontal position belongs to the pan/zoom transform alone.
        plotScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        plotScroll.getVerticalScrollBar().setUnitIncrement(VERTICAL_SCROLL_UNIT);
        styleVerticalScrollbar(plotScroll.getVerticalScrollBar());
        // Wheel input is routed explicitly by the canvas, axis and row header:
        // vertical input scrolls lanes, horizontal input pans time, and
        // Control/Command input zooms. The scroll pane's default listener stays
        // off so it cannot apply a second, platform-dependent delta afterward.
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

        JPanel timeline = new JPanel(new BorderLayout());
        timeline.setMinimumSize(new Dimension(420, 220));
        timeline.add(plot, BorderLayout.CENTER);
        timeline.add(footer, BorderLayout.SOUTH);

        inspector.setMinimumSize(new Dimension(280, 180));
        inspector.setPreferredSize(new Dimension(340, PREFERRED_PLOT_HEIGHT));
        contentSplit.setLeftComponent(new SectionPane("Timeline", timeline));
        contentSplit.setRightComponent(new SectionPane("Action details", inspector));
        contentSplit.setResizeWeight(0.72);
        contentSplit.setContinuousLayout(true);

        JPanel body = new JPanel(new BorderLayout());
        body.add(bar, BorderLayout.NORTH);
        body.add(contentSplit, BorderLayout.CENTER);

        deck.add(empty, "empty");
        deck.add(body, "timeline");
        add(deck, BorderLayout.CENTER);
        cards.show(deck, "empty");
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // JPanel's constructor invokes updateUI before this class's fields are
        // initialized, hence the null guards. Runtime theme changes arrive
        // after construction and refresh the custom FlatLaf styling here.
        if (plotScroll != null) {
            styleVerticalScrollbar(plotScroll.getVerticalScrollBar());
        }
        if (inspector != null) {
            styleInspectorBorder();
        }
        repaint();
    }

    /**
     * Widens {@code bar}'s thumb and gives it a colour that reads against
     * the track in either theme, via FlatLaf's per-component style property
     * rather than a UI-default override — this scroll pane only, not every
     * scroll pane in the application. {@link com.holtherndon.bazelviz.ui.theme.Themes}
     * stays untouched; a look-and-feel other than FlatLaf simply ignores the
     * client property and keeps its own scrollbar.
     */
    private static void styleVerticalScrollbar(JScrollBar bar) {
        boolean dark = FlatLaf.isLafDark();
        Color thumb = dark ? new Color(0x9A, 0x9A, 0x9A) : new Color(0x6E, 0x6E, 0x6E);
        Color hoverThumb = dark ? new Color(0xC4, 0xC4, 0xC4) : new Color(0x46, 0x46, 0x46);
        bar.putClientProperty("FlatLaf.style", Map.of(
                "width", 14,
                "thumbArc", 8,
                "thumbInsets", new Insets(2, 3, 2, 3),
                "thumb", thumb,
                "hoverThumbColor", hoverThumb));
    }

    private static Color themeForeground() {
        Color foreground = UIManager.getColor("Label.foreground");
        return foreground == null ? Color.DARK_GRAY : foreground;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static Color spanBorder() {
        return withAlpha(FlatLaf.isLafDark() ? Color.WHITE : Color.BLACK, 82);
    }

    private static Color focusOutline() {
        Color focus = UIManager.getColor("Component.focusColor");
        return focus == null
                ? (FlatLaf.isLafDark() ? new Color(0xA9, 0xCE, 0xFF) : new Color(0x0B, 0x57, 0xD0))
                : focus;
    }

    private static Color gridLine() {
        return withAlpha(themeForeground(), FlatLaf.isLafDark() ? 38 : 28);
    }

    private static Color laneWash() {
        return withAlpha(themeForeground(), FlatLaf.isLafDark() ? 12 : 8);
    }

    private static Color rangeFill() {
        Color focus = focusOutline();
        return withAlpha(focus, FlatLaf.isLafDark() ? 42 : 30);
    }

    private void buildInspector() {
        styleInspectorBorder();
        inspectorBody.setEditable(false);
        inspectorBody.setOpaque(false);
        inspectorBody.setLineWrap(true);
        inspectorBody.setWrapStyleWord(true);
        inspectorBody.setFont(status.getFont());
        inspectorClose.addActionListener(event -> hideInspector());
        inspectorHeader.addTrailing(inspectorClose);
        inspector.add(inspectorHeader, BorderLayout.NORTH);
        JScrollPane detailsScroll = new JScrollPane(inspectorBody);
        detailsScroll.setBorder(BorderFactory.createEmptyBorder());
        detailsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inspector.add(detailsScroll, BorderLayout.CENTER);
        hideInspector();
    }

    private void styleInspectorBorder() {
        inspector.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    /** Called with a node id when the user selects a span. */
    public void onSelection(LongConsumer handler) {
        this.selectionHandler = handler;
    }

    /**
     * Called with an action id when a span is clicked and the side inspector
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

    /** Clears a drag-selected range and tells subscribers the filter is gone. */
    public void clearRange() {
        if (viewport == null || !viewport.hasRange()) {
            return;
        }
        viewport = viewport.withoutRange();
        repaintAll();
        rangeChanged.rangeChanged(
                java.util.OptionalLong.empty(), java.util.OptionalLong.empty());
    }

    /** Called when the visible range or grouping changed and data must be refetched. */
    public void onViewportChanged(Runnable handler) {
        this.viewportChanged = handler;
    }

    /**
     * Gives the side inspector the shared navigation vocabulary. Until this
     * is called, a clicked segment still shows its details but offers no
     * jumps — honest absence, never dead buttons.
     */
    public void installEntityActions(EntityActions actions) {
        entityActions = actions;
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
        hoveredSpan = -1;
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
        showInteractionHint();
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
        hoveredSpan = -1;
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
        hoveredSpan = -1;
        laneRowByKey = Map.of();
        unmatchedSpans = 0;
        relayoutLanes();
        // There is nothing to be scrolled into any more; the next session
        // starts at its own top rather than at the last one's offset.
        scrollTo(0);
        liveTicker.stop();
        viewportRefreshTimer.stop();
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
     * Applies one ordinary wheel/two-finger gesture to the shared vertical
     * viewport. Fractional deltas are retained instead of rounded away, which
     * is what makes small inertial trackpad movements accumulate smoothly.
     */
    private void scrollVertically(MouseWheelEvent event) {
        double increment = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? Math.max(VERTICAL_SCROLL_UNIT,
                        plotScroll.getViewport().getExtentSize().height - VERTICAL_SCROLL_UNIT)
                : Math.max(1, event.getScrollAmount()) * (double) VERTICAL_SCROLL_UNIT;
        double pixels = verticalWheelRemainder + event.getPreciseWheelRotation() * increment;
        int wholePixels = pixels < 0 ? (int) Math.ceil(pixels) : (int) Math.floor(pixels);
        verticalWheelRemainder = pixels - wholePixels;
        if (wholePixels != 0) {
            scrollTo(scrollPosition() + wholePixels);
        }
    }

    /**
     * Pans time for a native horizontal trackpad event. On macOS the JDK
     * exposes delta-X as a wheel event with Shift set; treating it as vertical
     * input was the reason a sideways gesture moved through lanes instead.
     */
    private void scrollHorizontally(MouseWheelEvent event) {
        if (viewport == null) {
            return;
        }
        double increment = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? Math.max(VERTICAL_SCROLL_UNIT, canvas.getWidth() - VERTICAL_SCROLL_UNIT)
                : Math.max(1, event.getScrollAmount()) * (double) VERTICAL_SCROLL_UNIT;
        double pixels = horizontalWheelRemainder
                + event.getPreciseWheelRotation() * increment;
        int wholePixels = pixels < 0 ? (int) Math.ceil(pixels) : (int) Math.floor(pixels);
        horizontalWheelRemainder = pixels - wholePixels;
        if (wholePixels == 0) {
            return;
        }
        TimelineTransform before = viewport.transform();
        TimelineTransform after = clamp(before.pannedByPixels(-wholePixels));
        if (after.equals(before)) {
            return;
        }
        viewport = viewport.navigatedTo(after);
        followBox.setSelected(false);
        repaintAll();
        viewportRefreshTimer.restart();
    }

    /** True for explicit zoom and for the modified wheel form used by trackpads. */
    private static boolean isZoomGesture(MouseWheelEvent event) {
        return event.isControlDown() || event.isMetaDown();
    }

    /** Routes wheel input consistently over the plot, its axis, and its labels. */
    private void handleWheel(MouseWheelEvent event, double anchorX) {
        event.consume();
        if (isZoomGesture(event)) {
            double factor = Math.pow(1.1, -event.getPreciseWheelRotation());
            zoomAround(anchorX, factor);
        } else if (event.isShiftDown()) {
            scrollHorizontally(event);
        } else {
            scrollVertically(event);
        }
    }

    /** Zooms immediately, then coalesces the exact-window refresh. */
    private void zoomAround(double anchorX, double factor) {
        if (viewport == null || !Double.isFinite(factor) || factor <= 0) {
            return;
        }
        TimelineTransform before = viewport.transform();
        TimelineTransform after = clamp(before.zoomedAround(anchorX, factor));
        if (after.equals(before)) {
            return;
        }
        viewport = viewport.navigatedTo(after);
        followBox.setSelected(false);
        repaintAll();
        viewportRefreshTimer.restart();
    }

    /** Fits the invocation while preserving the selected action and range. */
    private void fitBuild() {
        if (model == null || viewport == null) {
            return;
        }
        TimelineTransform fit = TimelineTransform.fit(
                model.wallStartMicros(), liveWallEnd(model), Math.max(1, canvas.getWidth()));
        viewport = viewport.navigatedTo(fit);
        followBox.setSelected(false);
        repaintAll();
        viewportRefreshTimer.restart();
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
                (long) Math.floor(viewport.transform().microsAtX(0)),
                (long) Math.ceil(viewport.transform().microsAtX(canvas.getWidth()))});
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
     * Shows the side inspector for one clicked segment. Called by the
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
        inspectorBody.setCaretPosition(0);
        inspectorHasDetails = true;
        inspectorClose.setVisible(true);
        inspector.revalidate();
        inspector.repaint();
    }

    /** Clears the side inspector without resizing the timeline. */
    public void hideInspector() {
        inspectorHasDetails = false;
        inspectorHeader.clear();
        inspectorBody.setText("Select a segment to see details.");
        inspectorBody.setCaretPosition(0);
        inspectorClose.setVisible(false);
    }

    /** Whether the side inspector currently contains selected-segment details. */
    boolean inspectorVisibleForTest() {
        return inspectorHasDetails;
    }

    /** The horizontal content split, for layout tests. */
    JSplitPane contentSplitForTest() {
        return contentSplit;
    }

    /** The details component installed on the split's right side. */
    JComponent inspectorForTest() {
        return inspector;
    }

    /** Builds a menu without showing it, so headless tests can inspect it. */
    JPopupMenu contextMenuAtForTest(int x, int y) {
        return contextMenuAt(x, y);
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
            if (!windowCoversViewport()) {
                text.append("Updating visible spans; showing ")
                        .append(window.size())
                        .append(" known individual spans in the loaded part of this range.");
            } else if (window.size() == 0) {
                text.append("No individual spans in this time range.");
            } else {
                text.append("Showing ").append(window.size()).append(" individual spans");
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
            long shortMarkers = shortSpanMarkerCount();
            if (shortMarkers > 0) {
                text.append("  ·  ").append(shortMarkers)
                        .append(shortMarkers == 1 ? " short span uses" : " short spans use")
                        .append(" a ").append(SHORT_SPAN_MARKER_WIDTH)
                        .append(" px needle — zoom in for proportional width");
            }
        } else if (!windowCoversViewport()) {
            text.append("Updating individual spans for this time range; showing exact aggregate"
                    + " density in the meantime.");
        } else if (window.droppedSpans() > 0) {
            text.append("Showing exact aggregate density: ")
                    .append(window.size() + window.droppedSpans())
                    .append(" spans are in view, above the ")
                    .append(SpanWindow.MAX_SPANS)
                    .append(" individual-span budget. Zoom in for segments.");
        } else {
            text.append("Showing aggregate density over ").append(model.spanCount())
                    .append(" spans. Zoom in for individual actions.");
        }
        viewport.describeRange().ifPresent(range -> text.append("  ·  ").append(range));
        return text.toString();
    }

    /**
     * True when exact marks are safe to draw. A fully covered empty window is
     * still exact. During a small pan, a non-truncated overlapping window stays
     * visible and only its uncovered edge is marked as loading; replacing the
     * whole plot with density for a few milliseconds caused the former flash.
     */
    private boolean drawingSpans() {
        return window.droppedSpans() == 0
                && (windowCoversViewport()
                        || (window.size() > 0 && windowOverlapsViewport()));
    }

    /**
     * Whether the prepared exact window covers every time currently visible.
     * An old window remains safe to reuse after zooming into its range, but it
     * must never be presented as exact after panning or zooming beyond it.
     */
    private boolean windowCoversViewport() {
        Optional<long[]> visible = visibleRange();
        return visible.isPresent() && window.covers(visible.get()[0], visible.get()[1]);
    }

    /** Whether any part of the prepared exact window is still on screen. */
    private boolean windowOverlapsViewport() {
        Optional<long[]> visible = visibleRange();
        return visible.isPresent()
                && window.toMicros() > visible.get()[0]
                && window.fromMicros() < visible.get()[1];
    }

    /** Number of exact spans currently represented by short-duration needles. */
    private long shortSpanMarkerCount() {
        long count = 0;
        for (int i = 0; i < window.size(); i++) {
            if (naturalSpanWidth(i) < SHORT_SPAN_MARKER_WIDTH) {
                count++;
            }
        }
        return count;
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
     * The stable visible canvas slice. Swing's graphics clip is only the dirty
     * repaint region and can change from frame to frame; using it to place the
     * density chart or its label made their geometry jump while data loaded.
     */
    private Rectangle visibleCanvasSlice() {
        Rectangle view = plotScroll.getViewport().getViewRect();
        if (view.width <= 0 || view.height <= 0) {
            return new Rectangle(0, 0, Math.max(0, canvas.getWidth()),
                    Math.max(0, canvas.getHeight()));
        }
        int top = Math.clamp(view.y, 0, Math.max(0, canvas.getHeight()));
        int bottom = (int) Math.clamp((long) view.y + view.height,
                (long) top, (long) Math.max(top, canvas.getHeight()));
        return new Rectangle(0, top, Math.max(0, canvas.getWidth()), bottom - top);
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

    /** Exact duration width at the current scale, before applying a marker. */
    private double naturalSpanWidth(int i) {
        TimelineTransform transform = viewport.transform();
        return Math.max(0.0,
                transform.xForMicros(window.endMicros(i))
                        - transform.xForMicros(window.startMicros(i)));
    }

    /**
     * Writes the painted bounds of span {@code i} into {@code out}. Duration
     * bars use their true covered pixels. Anything narrower uses a centred,
     * fixed-width needle, which reads as "shorter than this scale" rather than
     * as a malformed one-pixel box.
     */
    private void spanPaintBounds(int i, Rectangle out) {
        TimelineTransform transform = viewport.transform();
        double exactStart = transform.xForMicros(window.startMicros(i));
        double exactEnd = transform.xForMicros(window.endMicros(i));
        double naturalWidth = Math.max(0.0, exactEnd - exactStart);
        int x;
        int width;
        if (naturalWidth < SHORT_SPAN_MARKER_WIDTH) {
            width = SHORT_SPAN_MARKER_WIDTH;
            // Adding half the duration avoids overflowing two very large
            // screen coordinates merely to compute their midpoint.
            x = roundedScreenCoordinate(exactStart + naturalWidth / 2.0) - width / 2;
        } else {
            // A long action can begin far outside the current viewport. Clip
            // before converting to int so its rectangle cannot overflow and
            // wrap back across the canvas.
            double clippedStart = Math.max(-2.0, exactStart);
            double clippedEnd = Math.min(canvas.getWidth() + 2.0, exactEnd);
            x = flooredScreenCoordinate(clippedStart);
            int right = ceiledScreenCoordinate(clippedEnd);
            width = (int) Math.clamp((long) right - x, 1L, Integer.MAX_VALUE);
        }
        int row = rowOfSpan(i);
        // The row's own height says how many sub-rows it has, so a span can
        // never be placed below the lane it belongs to — including a span in
        // no current lane, whose extra row is one sub-row tall.
        int depth = laneHeight(row) / SUB_ROW_HEIGHT;
        int subRow = Math.clamp(stacking.subRow(i), 0, depth - 1);
        int y = bandHeight() + laneTop(row) + subRow * SUB_ROW_HEIGHT
                + SPAN_VERTICAL_INSET;
        out.setBounds(x, y, width, SUB_ROW_HEIGHT - 2 * SPAN_VERTICAL_INSET);
    }

    /** A larger click target that never changes the painted duration cue. */
    private void spanHitBounds(int i, Rectangle out) {
        spanPaintBounds(i, out);
        int extra = Math.max(0, MINIMUM_SPAN_HIT_WIDTH - out.width);
        out.setBounds(out.x - extra / 2,
                out.y - SPAN_VERTICAL_INSET,
                out.width + extra,
                SUB_ROW_HEIGHT);
    }

    /** True if the real interval, not its marker, touches the canvas. */
    private boolean spanTouchesCanvas(int i) {
        TimelineTransform transform = viewport.transform();
        double start = transform.xForMicros(window.startMicros(i));
        double end = transform.xForMicros(window.endMicros(i));
        return (end > 0 || (start == end && end == 0)) && start < canvas.getWidth();
    }

    private static int flooredScreenCoordinate(double value) {
        return (int) Math.clamp(Math.floor(value), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static int ceiledScreenCoordinate(double value) {
        return (int) Math.clamp(Math.ceil(value), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static int roundedScreenCoordinate(double value) {
        return (int) Math.clamp(Math.rint(value), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** The span under a point, or -1. Only meaningful while drawing spans. */
    int spanIndexAt(int x, int y) {
        if (viewport == null || !drawingSpans()) {
            return -1;
        }
        Rectangle hit = new Rectangle();
        // Last painted is visually on top, so it must also win hit testing.
        for (int i = window.size() - 1; i >= 0; i--) {
            if (!spanTouchesCanvas(i)) {
                continue;
            }
            spanHitBounds(i, hit);
            if (hit.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** Painted bounds for focused geometry tests. */
    Rectangle spanPaintBoundsForTest(int i) {
        Rectangle bounds = new Rectangle();
        spanPaintBounds(i, bounds);
        return bounds;
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
     * <p>A stable 1-2-5 time ladder keeps ticks around ninety pixels apart and
     * labels them from the build's start rather than in epoch time: a reader
     * cares how far into the build something happened. Precision follows the
     * step, so a microsecond view never repeats a row of labels saying 0.00s.
     * {@link #clamp} lets a pan or a zoom show a little
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
        TimelineTransform transform = viewport.transform();
        long step = TimelineCanvas.niceStep(90.0 / transform.pixelsPerMicro());
        long visibleFrom = (long) Math.floor(
                transform.microsAtX(0) - model.wallStartMicros());
        long visibleTo = (long) Math.ceil(
                transform.microsAtX(width) - model.wallStartMicros());
        long tick = Math.max(0, Math.ceilDiv(visibleFrom, step) * step);
        for (; tick <= visibleTo; tick += step) {
            int x = (int) Math.round(transform.xForMicros(model.wallStartMicros() + tick));
            ticks.add(new Tick(x, TimelineCanvas.formatMicros(tick, step)));
            if (tick > Long.MAX_VALUE - step) {
                break;
            }
        }
        return ticks;
    }

    /** The time axis. Its own component so it can stay put while lanes scroll. */
    private final class Header extends JComponent {

        private static final long serialVersionUID = 1L;

        Header() {
            setPreferredSize(new Dimension(0, HEADER_HEIGHT));
            addMouseWheelListener(event -> handleWheel(event, event.getX()));
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
                g2.setColor(gridLine());
                g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                g2.setColor(themeForeground());
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

        LaneLabels() {
            // The row header shares the plot's interaction contract. It has no
            // meaningful time coordinate, so modified-wheel zoom anchors at
            // the plot centre; ordinary input scrolls the shared viewport.
            addMouseWheelListener(event -> handleWheel(event, canvas.getWidth() / 2.0));
        }

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
                    g2.setColor(themeForeground());
                    g2.drawString("In-flight targets", 4, Math.min(band - 5, 13));
                }
                Rectangle clip = g2.getClipBounds();
                if (!drawingSpans()) {
                    // Density is global; showing the grouping's lane names
                    // beside it would imply a per-lane aggregate we do not
                    // compute. Keep the label pinned in the viewport, not the
                    // transient dirty-paint clip.
                    int visibleTop = Math.max(band, visibleCanvasSlice().y);
                    g2.setColor(themeForeground());
                    g2.drawString(windowCoversViewport()
                                    ? "Whole build · aggregate density"
                                    : "Updating visible spans…",
                            4, visibleTop + SUB_ROW_HEIGHT - 5);
                    return;
                }
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
                    g2.setColor(themeForeground());
                    g2.drawString(lanes.get(i).name(), 4, top + SUB_ROW_HEIGHT - 5);
                    // Lanes are no longer all the same height, so where one
                    // ends has to be visible rather than inferred.
                    g2.setColor(withAlpha(themeForeground(), 36));
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
        private int lastPointerX = -1;

        Canvas() {
            MouseAdapter mouse = new MouseAdapter() {
                private int lastX;
                private int pressX = -1;
                private boolean panning;

                @Override
                public void mousePressed(MouseEvent event) {
                    lastPointerX = event.getX();
                    if (maybeShowContextMenu(event)) {
                        pressX = -1;
                        panning = false;
                        return;
                    }
                    if (!SwingUtilities.isLeftMouseButton(event)) {
                        pressX = -1;
                        return;
                    }
                    lastX = event.getX();
                    pressX = event.getX();
                    panning = false;
                    if (event.isShiftDown()) {
                        dragStartX = event.getX();
                        dragCurrentX = event.getX();
                    }
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (viewport == null || pressX < 0
                            || (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) {
                        return;
                    }
                    if (dragStartX >= 0) {
                        dragCurrentX = event.getX();
                        repaint();
                        return;
                    }
                    // A click commonly jitters by a pixel or two. It should
                    // still click, not stop Follow live and query another
                    // time range. Three pixels establishes an intentional pan.
                    if (!panning && Math.abs(event.getX() - pressX) < 3) {
                        return;
                    }
                    panning = true;
                    int dx = event.getX() - lastX;
                    lastX = event.getX();
                    viewport = viewport.navigatedTo(
                            clamp(viewport.transform().pannedByPixels(dx)));
                    followBox.setSelected(false);
                    repaintAll();
                    viewportRefreshTimer.restart();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (maybeShowContextMenu(event)) {
                        pressX = -1;
                        panning = false;
                        return;
                    }
                    pressX = -1;
                    panning = false;
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
                    if (SwingUtilities.isLeftMouseButton(event)
                            && !event.isControlDown() && !event.isPopupTrigger()) {
                        clickAt(event.getX(), event.getY());
                    }
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    lastPointerX = event.getX();
                    if (model != null && event.getY() < bandHeight()) {
                        setHoveredSpan(-1);
                        hover.setText(bandLabel(model.liveBand()));
                        hover.setToolTipText(PlainText.tooltip(hover.getText()));
                        return;
                    }
                    int exact = spanIndexAt(event.getX(), event.getY());
                    setHoveredSpan(exact);
                    if (exact >= 0) {
                        describeSpan(exact);
                        return;
                    }
                    describeAt(event.getX());
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    setHoveredSpan(-1);
                    showInteractionHint();
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    lastPointerX = event.getX();
                    handleWheel(event, event.getX());
                }

                /** Checks both press and release because platforms disagree. */
                private boolean maybeShowContextMenu(MouseEvent event) {
                    if (!event.isPopupTrigger()) {
                        return false;
                    }
                    JPopupMenu menu = contextMenuAt(event.getX(), event.getY());
                    if (menu.getComponentCount() > 0) {
                        menu.show(Canvas.this, event.getX(), event.getY());
                    }
                    event.consume();
                    return true;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        double zoomAnchorX() {
            return lastPointerX >= 0 ? lastPointerX : getWidth() / 2.0;
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
                paintGuides(g2);
                paintBand(g2);
                if (drawingSpans()) {
                    paintSpans(g2);
                    paintLoadingEdges(g2);
                } else {
                    paintDensity(g2);
                }
                paintRange(g2);
            } finally {
                g2.dispose();
            }
        }

        /** Stable time guides and lane boundaries behind the data. */
        private void paintGuides(Graphics2D g2) {
            g2.setColor(gridLine());
            for (Tick tick : ticksFor(viewport, model, getWidth())) {
                g2.drawLine(tick.x(), 0, tick.x(), getHeight());
            }
            if (!drawingSpans()) {
                return;
            }
            int band = bandHeight();
            int realLanes = model.lanes().size();
            for (int row = 0; row <= realLanes; row++) {
                int top = band + laneTop(row);
                int height = laneHeight(row);
                if ((row & 1) != 0) {
                    g2.setColor(laneWash());
                    g2.fillRect(0, top, getWidth(), height);
                }
                g2.setColor(gridLine());
                for (int y = top + SUB_ROW_HEIGHT; y < top + height; y += SUB_ROW_HEIGHT) {
                    g2.drawLine(0, y - 1, getWidth(), y - 1);
                }
                g2.drawLine(0, top + height - 1, getWidth(), top + height - 1);
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
            g2.setColor(themeForeground());
            g2.drawString(bandLabel(model.liveBand()), 4, Math.min(band - 5, 13));
            g2.setColor(gridLine());
            g2.drawLine(0, band - 1, getWidth(), band - 1);
        }

        private void paintDensity(Graphics2D g2) {
            TimelineLodIndex index = model.index();
            TimelineTransform transform = viewport.transform();
            int level = index.levelForScale(transform.pixelsPerMicro());
            int maxActive = Math.max(1, index.maxActiveCount(level));
            // Density is one whole-build summary, not one chart per lane. Pin
            // it to the stable viewport slice. A Graphics clip is only the
            // current dirty rectangle and must never determine geometry.
            Rectangle visible = visibleCanvasSlice();
            int visibleTop = visible.y;
            int visibleBottom = visible.y + visible.height;
            int top = Math.max(bandHeight(), visibleTop);
            int height = visibleBottom - top;
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
         * Marks only the time not covered by the last exact window while its
         * replacement loads. Known spans remain in their lanes, so a small pan
         * no longer flashes into an unrelated whole-build histogram.
         */
        private void paintLoadingEdges(Graphics2D g2) {
            if (windowCoversViewport() || window.droppedSpans() > 0 || viewport == null) {
                return;
            }
            TimelineTransform transform = viewport.transform();
            Optional<long[]> visible = visibleRange();
            if (visible.isEmpty()) {
                return;
            }
            Color wash = withAlpha(focusOutline(), FlatLaf.isLafDark() ? 30 : 20);
            Color edge = withAlpha(focusOutline(), 92);
            if (visible.get()[0] < window.fromMicros()) {
                int right = Math.clamp(
                        ceiledScreenCoordinate(transform.xForMicros(window.fromMicros())),
                        0, getWidth());
                g2.setColor(wash);
                g2.fillRect(0, 0, right, getHeight());
                if (right > 0 && right < getWidth()) {
                    g2.setColor(edge);
                    g2.drawLine(right, 0, right, getHeight());
                }
            }
            if (visible.get()[1] > window.toMicros()) {
                int left = Math.clamp(
                        flooredScreenCoordinate(transform.xForMicros(window.toMicros())),
                        0, getWidth());
                g2.setColor(wash);
                g2.fillRect(left, 0, getWidth() - left, getHeight());
                if (left > 0 && left < getWidth()) {
                    g2.setColor(edge);
                    g2.drawLine(left, 0, left, getHeight());
                }
            }
        }

        /**
         * Places every span by its lane key and its stacked sub-row — facts
         * about the span — never by its index in the window, which is what
         * used to shuffle rows on every pan and zoom.
         */
        private void paintSpans(Graphics2D g2) {
            Rectangle bounds = new Rectangle();
            int selected = -1;
            for (int i = 0; i < window.size(); i++) {
                if (!spanTouchesCanvas(i)) {
                    continue;
                }
                spanPaintBounds(i, bounds);
                Color fill = TimelineColours.forSpan(colourMode, window.flags(i));
                g2.setColor(fill);
                g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                g2.setColor(spanBorder());
                g2.drawRect(bounds.x, bounds.y,
                        Math.max(0, bounds.width - 1), Math.max(0, bounds.height - 1));
                if ((window.flags(i) & SpanSource.FLAG_FAILED) != 0) {
                    // A second top rule distinguishes failure without relying
                    // on red versus green alone.
                    g2.drawLine(bounds.x, bounds.y + 2,
                            bounds.x + Math.max(0, bounds.width - 1), bounds.y + 2);
                }
                if (viewport.selectedNode().isPresent()
                        && viewport.selectedNode().getAsLong() == window.nodeId(i)) {
                    selected = i;
                }
            }
            if (hoveredSpan >= 0 && hoveredSpan < window.size() && hoveredSpan != selected
                    && spanTouchesCanvas(hoveredSpan)) {
                spanPaintBounds(hoveredSpan, bounds);
                paintOutsideOutline(g2, bounds, focusOutline(), false);
            }
            if (selected >= 0 && spanTouchesCanvas(selected)) {
                spanPaintBounds(selected, bounds);
                paintOutsideOutline(g2, bounds, focusOutline(), true);
            }
        }

        /** Draws around a span, never over its semantic fill. */
        private void paintOutsideOutline(
                Graphics2D g2, Rectangle bounds, Color colour, boolean strong) {
            g2.setColor(colour);
            g2.drawRect(bounds.x - 1, bounds.y - 1, bounds.width + 1, bounds.height + 1);
            if (strong) {
                g2.drawRect(bounds.x - 2, bounds.y - 2, bounds.width + 3, bounds.height + 3);
            }
        }

        /** The drag-selected range, drawn over everything. */
        private void paintRange(Graphics2D g2) {
            if (dragStartX >= 0) {
                int x0 = Math.min(dragStartX, dragCurrentX);
                int width = Math.abs(dragCurrentX - dragStartX);
                g2.setColor(rangeFill());
                g2.fillRect(x0, 0, width, getHeight());
                g2.setColor(focusOutline());
                g2.drawLine(x0, 0, x0, getHeight());
                g2.drawLine(x0 + width, 0, x0 + width, getHeight());
                return;
            }
            if (!viewport.hasRange()) {
                return;
            }
            TimelineTransform transform = viewport.transform();
            int x0 = (int) transform.xForMicros(viewport.rangeFromMicros().getAsLong());
            int x1 = (int) transform.xForMicros(viewport.rangeToMicros().getAsLong());
            int left = Math.min(x0, x1);
            int right = Math.max(x0, x1);
            g2.setColor(rangeFill());
            g2.fillRect(left, 0, right - left, getHeight());
            g2.setColor(focusOutline());
            g2.drawLine(left, 0, left, getHeight());
            g2.drawLine(right, 0, right, getHeight());
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
            selectBand(bandIndex);
            return;
        }
        int i = spanIndexAt(x, y);
        if (i < 0) {
            return;
        }
        selectSpan(i);
    }

    /** Shows the immediately available facts for one live-band target. */
    private void selectBand(int bandIndex) {
        TimelineModel.LiveBand.InFlight target = model.liveBand().inFlight().get(bandIndex);
        double intoBuild = (target.startMicros() - model.wallStartMicros()) / 1_000_000.0;
        showInspector(new SpanDetails(
                target.label(),
                List.of(
                        "Target, in flight: configured and not yet completed.",
                        String.format(java.util.Locale.ROOT,
                                "Configured %.2fs into the build (BEP receive time).",
                                intoBuild)),
                List.of(new EntityRef.TargetLabel(target.label()))));
    }

    /** Selects one exact action span and starts its asynchronous detail read. */
    private void selectSpan(int i) {
        long nodeId = window.nodeId(i);
        viewport = viewport.selecting(OptionalLong.of(nodeId));
        long start = Math.max(0, window.startMicros(i) - model.wallStartMicros());
        long duration = Math.max(0, window.endMicros(i) - window.startMicros(i));
        showInspector(new SpanDetails(
                "Action " + nodeId,
                List.of("Ran " + EntityFormat.duration(start)
                        + " into the build for " + EntityFormat.duration(duration)
                        + ". Fetching details…"),
                List.of(new EntityRef.ActionId(nodeId))));
        // A lane the click only caught the edge of comes fully into view, so
        // the inspector beside it is describing something the user can see.
        scrollSelectionIntoView();
        selectionHandler.accept(nodeId);
        actionPickedHandler.accept(nodeId);
        repaintAll();
    }

    /**
     * Builds the selection-first context menu for the segment under a point.
     * All refs come from the bounded model/window already in memory; no popup
     * path performs I/O on the EDT.
     */
    private JPopupMenu contextMenuAt(int x, int y) {
        JPopupMenu emptyMenu = new JPopupMenu();
        if (model == null || viewport == null) {
            return emptyMenu;
        }
        List<EntityRef> refs;
        int bandIndex = bandIndexAt(x, y);
        if (bandIndex >= 0) {
            selectBand(bandIndex);
            refs = List.of(new EntityRef.TargetLabel(
                    model.liveBand().inFlight().get(bandIndex).label()));
        } else {
            int spanIndex = spanIndexAt(x, y);
            if (spanIndex < 0) {
                return emptyMenu;
            }
            selectSpan(spanIndex);
            refs = List.of(new EntityRef.ActionId(window.nodeId(spanIndex)));
        }
        return entityActions == null
                ? emptyMenu
                : entityActions.popupFor(
                        refs, Set.of(EntityActions.Command.SHOW_ON_TIMELINE));
    }

    /** Changes the hover halo without disturbing selection or the viewport. */
    private void setHoveredSpan(int index) {
        if (hoveredSpan == index) {
            return;
        }
        hoveredSpan = index;
        canvas.repaint();
    }

    private void showInteractionHint() {
        hover.setText(INTERACTION_HINT);
        hover.setToolTipText(PlainText.tooltip(INTERACTION_HINT));
    }

    /** Exact local hover text for an individual span; no query is needed. */
    private String describeSpan(int index) {
        long startMicros = window.startMicros(index);
        long durationMicros = Math.max(0, window.endMicros(index) - startMicros);
        String lane = window.laneKey(index).isBlank()
                ? "Ungrouped" : window.laneKey(index);
        String text = "Action " + window.nodeId(index)
                + " · " + lane
                + " · starts " + EntityFormat.duration(
                        Math.max(0, startMicros - model.wallStartMicros()))
                + " into the build"
                + " · duration " + EntityFormat.duration(durationMicros);
        hover.setText(text);
        hover.setToolTipText(PlainText.tooltip(text));
        return text;
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

    /** Exact hover text for focused tests. */
    String describeSpanForTest(int index) {
        return describeSpan(index);
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

    /** Whether this JVM exposed the optional native macOS pinch bridge. */
    boolean nativePinchAvailableForTest() {
        return magnificationSupport.available();
    }

    /** Drives the same path as a native pinch without needing native test input. */
    void magnifyForTest(double magnification) {
        zoomAround(canvas.zoomAnchorX(), MacMagnificationSupport.zoomFactor(magnification));
    }

    /** The lane label column, for tests that check it reports the canvas's height. */
    JComponent laneLabelsForTest() {
        return laneLabels;
    }

    /** Whether exact marks, including a covered empty range, are the active mode. */
    boolean drawingSpansForTest() {
        return drawingSpans();
    }

    /** The viewport-derived vertical slice used by density and its label. */
    Rectangle visibleCanvasSliceForTest() {
        return new Rectangle(visibleCanvasSlice());
    }
}
