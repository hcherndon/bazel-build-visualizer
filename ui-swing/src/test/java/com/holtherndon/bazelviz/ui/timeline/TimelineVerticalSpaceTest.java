package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The timeline's vertical space: fixed sub-rows, depth-scaled lanes, and a
 * scroll pane that absorbs the total.
 *
 * <h2>The bug this pins down</h2>
 *
 * <p>Lane height used to be {@code canvas.getHeight() / lanes}, floored at
 * three pixels, and a span's sub-row was that divided again by its lane's
 * stacking depth — up to {@link SpanStacking#MAX_SUB_ROWS}. Thirty lanes in a
 * four-hundred-pixel window gave thirteen-pixel lanes and two-pixel sub-rows:
 * marks too thin to see and too thin to click, thinner the more concurrency
 * the build actually had. Meanwhile the in-flight band above them already drew
 * fixed-height rows, so the same plot used two different rules.
 *
 * <p>Now every sub-row is {@link TimelineView#SUB_ROW_HEIGHT} pixels, a lane is
 * as many of those as it stacks deep, and the height that produces is reported
 * to a {@link JScrollPane} rather than divided into whatever the window has.
 * These tests hold that: a depth-6 lane is six times a depth-1 lane, every one
 * of its sub-rows is separately hittable, the canvas reports the sum, the
 * scroll position survives a live rebuild, and the wheel still means zoom and
 * only zoom.
 */
final class TimelineVerticalSpaceTest {

    private static final int WIDTH = 1_000;
    private static final int HEIGHT = 400;
    private static final long WALL_END = 10_000_000; // a ten-second wall
    /** The visible height of the plot in the tests that need a real viewport. */
    private static final int VIEWPORT_HEIGHT = 200;

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }

    private static TimelineModel modelWithLanes(String... laneKeys) {
        return modelWithLanes(TimelineModel.LiveBand.EMPTY, laneKeys);
    }

    private static TimelineModel modelWithLanes(
            TimelineModel.LiveBand band, String... laneKeys) {
        TimelineLodIndex index = TimelineLodIndex.build(new SpanSource() {
            @Override
            public long spanCount() {
                return 1;
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                consumer.accept(0, 1_000, 0, 0, SpanSource.BYTES_UNKNOWN);
            }
        }, 0, WALL_END);
        List<TimelineModel.Lane> lanes = java.util.Arrays.stream(laneKeys)
                .map(key -> new TimelineModel.Lane(key, key, 1, 1_000, 0, 1_000))
                .toList();
        return new TimelineModel(index, lanes, List.of(), Map.of(), 0, 0, 0, band);
    }

    /** A model with {@code count} lanes named lane0, lane1, … */
    private static TimelineModel modelWithLaneCount(int count) {
        String[] keys = new String[count];
        for (int i = 0; i < count; i++) {
            keys[i] = "lane" + i;
        }
        return modelWithLanes(keys);
    }

    private static TimelineView viewShowing(TimelineModel model) {
        TimelineView view = new TimelineView();
        view.setClockForTest(() -> WALL_END);
        view.canvasForTest().setSize(WIDTH, HEIGHT);
        view.setModel(model);
        return view;
    }

    /**
     * Gives the scroll pane's viewport a real extent without a display. The
     * scroll pane is never laid out in a headless test, so its viewport would
     * otherwise be zero-sized and every clamp against it vacuous.
     */
    private static void sizeViewport(TimelineView view) {
        view.scrollForTest().getViewport().setSize(WIDTH, VIEWPORT_HEIGHT);
    }

    /** Six spans that all overlap, so they stack six sub-rows deep in one lane. */
    private static SpanWindow sixDeepIn(String laneKey, String shallowLane) {
        SpanWindow.Builder window = SpanWindow.builder(0, WALL_END);
        for (int i = 0; i < SpanStacking.MAX_SUB_ROWS; i++) {
            // Staggered starts, common end: each new span finds every earlier
            // sub-row still occupied, which is exactly what stacks them.
            window.add(i * 1_000L, 1_000_000, 0, 100 + i, laneKey);
        }
        window.add(2_000_000, 3_000_000, 0, 7, shallowLane);
        return window.build();
    }

    @Test
    @DisplayName("a lane is as tall as it stacks deep, at a fixed sub-row height")
    void laneHeightScalesWithDepthOnly() {
        TimelineView view = viewShowing(modelWithLanes("shallow", "deep"));
        view.setWindow(sixDeepIn("deep", "shallow"));

        assertThat(view.laneHeight(0))
                .as("a lane with no overlap is exactly one sub-row")
                .isEqualTo(TimelineView.SUB_ROW_HEIGHT);
        assertThat(view.laneHeight(1))
                .as("six concurrent spans make a lane six sub-rows tall")
                .isEqualTo(6 * TimelineView.SUB_ROW_HEIGHT);
        // Depth-scaled, not uniform: the shallow lane paid nothing for its
        // neighbour's concurrency, and the deep lane's rows are the same
        // readable height as the shallow one's.
        assertThat(view.laneTop(0)).isZero();
        assertThat(view.laneTop(1)).isEqualTo(TimelineView.SUB_ROW_HEIGHT);
    }

    @Test
    @DisplayName("every sub-row of a six-deep lane is separately hittable")
    void everySubRowCanBeClicked() {
        TimelineView view = viewShowing(modelWithLanes("shallow", "deep"));
        view.setWindow(sixDeepIn("deep", "shallow"));

        // x = 50 is inside all six (they run 0..1s of a ten-second wall over a
        // thousand pixels), so what distinguishes them is only the sub-row.
        List<Integer> hit = new ArrayList<>();
        for (int subRow = 0; subRow < SpanStacking.MAX_SUB_ROWS; subRow++) {
            int y = view.laneTop(1) + subRow * TimelineView.SUB_ROW_HEIGHT + 5;
            hit.add(view.spanIndexAt(50, y));
        }

        assertThat(hit)
                .as("each sub-row of the deep lane answers with its own span")
                .doesNotContain(-1)
                .doesNotHaveDuplicates()
                .hasSize(SpanStacking.MAX_SUB_ROWS);
        // Under the old division this was one or two pixels per sub-row; the
        // point of the fix is that the number is now fixed and readable.
        assertThat(TimelineView.SUB_ROW_HEIGHT).isGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("the canvas asks for the band plus every lane row, and the labels agree")
    void preferredHeightIsTheBandPlusTheLanes() {
        TimelineView view = viewShowing(modelWithLanes("shallow", "deep"));
        view.setWindow(sixDeepIn("deep", "shallow"));

        // One shallow lane, one six-deep lane, and the extra row that catches
        // spans in no current lane — which is always there so a straggler has
        // somewhere real to be drawn.
        int expected = (1 + 6 + 1) * TimelineView.SUB_ROW_HEIGHT;
        assertThat(view.bandHeight()).as("no band on a finished session").isZero();
        assertThat(view.totalLaneHeight()).isEqualTo(expected);
        assertThat(view.contentHeight()).isEqualTo(expected);
        assertThat(view.canvasForTest().getPreferredSize().height).isEqualTo(expected);
        assertThat(view.laneLabelsForTest().getPreferredSize().height)
                .as("the row header must report the same height or the rows drift apart")
                .isEqualTo(expected);

        // A live band adds its own fixed rows on top, and the lanes start below.
        TimelineView live = viewShowing(modelWithLanes(
                new TimelineModel.LiveBand(
                        true,
                        List.of(new TimelineModel.LiveBand.InFlight("//pkg:a", 1_000)),
                        1, 0),
                "shallow", "deep"));
        live.setWindow(sixDeepIn("deep", "shallow"));
        assertThat(live.bandHeight()).isEqualTo(TimelineView.SUB_ROW_HEIGHT);
        assertThat(live.canvasForTest().getPreferredSize().height)
                .isEqualTo(TimelineView.SUB_ROW_HEIGHT + expected);
    }

    @Test
    @DisplayName("nothing horizontal scrolls: the width is the viewport's and the policy is never")
    void horizontalPositionStaysTheTransformsBusiness() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        JScrollPane scroll = view.scrollForTest();

        assertThat(scroll.getHorizontalScrollBarPolicy())
                .isEqualTo(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        assertThat(scroll.getVerticalScrollBarPolicy())
                .as("always visible, not AS_NEEDED — a bar that appears and "
                        + "disappears with the plot's height is one nobody notices")
                .isEqualTo(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        assertThat(scroll.getVerticalScrollBar().getUnitIncrement()).isEqualTo(16);
        assertThat(((javax.swing.Scrollable) view.canvasForTest())
                .getScrollableTracksViewportWidth())
                .as("the plot is exactly as wide as the window, always")
                .isTrue();
        assertThat(scroll.getViewport().getView())
                .as("the canvas is what scrolls")
                .isSameAs(view.canvasForTest());
        assertThat(scroll.getRowHeader().getView())
                .as("the lane labels ride along as the row header, in lockstep")
                .isSameAs(view.laneLabelsForTest());
    }

    @Test
    @DisplayName("a live rebuild leaves the scroll position where the user put it")
    void scrollPositionSurvivesALiveRebuild() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        sizeViewport(view);
        JScrollPane scroll = view.scrollForTest();

        // Twenty one-sub-row lanes plus the extra row: 378 pixels of plot in a
        // 200-pixel window, so 120 is a position a user could really be at.
        assertThat(view.contentHeight()).isEqualTo(21 * TimelineView.SUB_ROW_HEIGHT);
        scroll.getViewport().setViewPosition(new Point(0, 120));

        // The live update path: a fresh model lands, wall and all.
        view.setModel(modelWithLaneCount(20));

        assertThat(scroll.getViewport().getViewPosition().y)
                .as("new data must not return a reading user to the top")
                .isEqualTo(120);
    }

    @Test
    @DisplayName("a rebuild that shortens the plot clamps rather than parking past the end")
    void scrollPositionIsClampedToWhatIsThere() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        sizeViewport(view);
        JScrollPane scroll = view.scrollForTest();
        scroll.getViewport().setViewPosition(new Point(0, 150));

        // A regroup: twenty lanes become two, and the whole plot now fits.
        view.setModel(modelWithLanes("alpha", "beta"));

        assertThat(view.contentHeight()).isEqualTo(3 * TimelineView.SUB_ROW_HEIGHT);
        assertThat(scroll.getViewport().getViewPosition().y)
                .as("a plot shorter than its window has nothing to scroll to")
                .isZero();
    }

    @Test
    @DisplayName("selecting from elsewhere scrolls the span's lane into view")
    void revealScrollsTheLaneIntoView() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        sizeViewport(view);
        SpanWindow.Builder window = SpanWindow.builder(0, WALL_END);
        window.add(1_000_000, 2_000_000, 0, 42, "lane15");
        view.setWindow(window.build());

        int top = view.laneTop(15);
        assertThat(top)
                .as("the setup must put the lane below the fold, or this proves nothing")
                .isGreaterThan(VIEWPORT_HEIGHT);

        view.select(42);

        int scrolledTo = view.scrollForTest().getViewport().getViewPosition().y;
        assertThat(scrolledTo).as("the view actually moved").isPositive();
        assertThat(top).isGreaterThanOrEqualTo(scrolledTo);
        assertThat(top + view.laneHeight(15))
                .as("the whole lane is inside the window, not merely its edge")
                .isLessThanOrEqualTo(scrolledTo + VIEWPORT_HEIGHT);
        // Horizontal is the transform's alone; a reveal may not nudge it.
        assertThat(view.scrollForTest().getViewport().getViewPosition().x).isZero();
    }

    @Test
    @DisplayName("the wheel over the plot zooms, and does not also scroll")
    void wheelZoomsWithoutScrolling() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        sizeViewport(view);
        JScrollPane scroll = view.scrollForTest();
        scroll.getViewport().setViewPosition(new Point(0, 100));
        JComponent canvas = view.canvasForTest();

        double before = view.viewport().orElseThrow().transform().pixelsPerMicro();
        MouseWheelEvent wheel = new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 500, 10, 500, 10, 1, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -2, -2.0);
        canvas.getMouseWheelListeners()[0].mouseWheelMoved(wheel);

        assertThat(view.viewport().orElseThrow().transform().pixelsPerMicro())
                .as("the gesture zoomed")
                .isGreaterThan(before);
        assertThat(scroll.getViewport().getViewPosition().y)
                .as("and did not also scroll")
                .isEqualTo(100);
        // Both halves of why: the event is consumed here, and the scroll pane
        // is not listening for it either.
        assertThat(wheel.isConsumed()).isTrue();
        assertThat(scroll.isWheelScrollingEnabled()).isFalse();
    }

    @Test
    @DisplayName("the wheel over the lane labels scrolls the shared viewport, and does not zoom")
    void wheelOverLaneLabelsScrollsTheSharedViewport() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        sizeViewport(view);
        JScrollPane scroll = view.scrollForTest();
        scroll.getViewport().setViewPosition(new Point(0, 50));
        JComponent laneLabels = view.laneLabelsForTest();

        // plotScroll.setWheelScrollingEnabled(false) is pane-wide, so nothing
        // but a listener on the label column itself moves this viewport when
        // the pointer is over the names rather than the plot.
        double before = view.viewport().orElseThrow().transform().pixelsPerMicro();
        MouseWheelEvent wheel = new MouseWheelEvent(laneLabels, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 50, 10, 50, 10, 1, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 2, 2.0);
        laneLabels.getMouseWheelListeners()[0].mouseWheelMoved(wheel);

        // Two notches at the same sixteen-pixel unit increment Scrollable
        // reports for this column.
        assertThat(scroll.getViewport().getViewPosition().y)
                .as("wheeling the label column moved the shared viewport")
                .isEqualTo(50 + Math.round(2.0 * 16));
        assertThat(view.viewport().orElseThrow().transform().pixelsPerMicro())
                .as("the label column's wheel gesture scrolls only — the canvas is still the one place it zooms")
                .isEqualTo(before);
        assertThat(wheel.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("an empty session leaves nothing scrolled and asks for no height")
    void showingEmptyResetsTheScroll() {
        TimelineView view = viewShowing(modelWithLaneCount(20));
        sizeViewport(view);
        view.scrollForTest().getViewport().setViewPosition(new Point(0, 120));

        view.showEmpty("No timeline for this session.");

        assertThat(view.scrollForTest().getViewport().getViewPosition().y).isZero();
        // One empty row, not a claim that there are lanes to look at.
        assertThat(view.contentHeight()).isEqualTo(TimelineView.SUB_ROW_HEIGHT);
        assertThat(view.canvasForTest().getPreferredSize().height)
                .isEqualTo(TimelineView.SUB_ROW_HEIGHT);
    }
}
