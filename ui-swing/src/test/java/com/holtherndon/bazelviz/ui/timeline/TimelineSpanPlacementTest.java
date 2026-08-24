package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A span's row is a fact about the span, never about the fetch.
 *
 * <h2>The bug this pins down</h2>
 *
 * <p>{@code paintSpans} used to place span {@code i} at row {@code i % lanes}:
 * the model's lane list and the painted spans were two unconnected data sets,
 * so any refetch — every pan, every zoom — changed each span's {@code i} and
 * shuffled the whole plot vertically. Now every span carries its lane key and
 * the painter joins it to the model's lanes, so these tests refetch the window
 * in different orders and subsets and require every span to stay put.
 */
final class TimelineSpanPlacementTest {

    private static final int WIDTH = 1_000;
    private static final int HEIGHT = 400;

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }

    private static TimelineModel modelWithLanes(String... laneKeys) {
        TimelineLodIndex index = TimelineLodIndex.build(new SpanSource() {
            @Override
            public long spanCount() {
                return 1;
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                consumer.accept(0, 1_000, 0, 0, SpanSource.BYTES_UNKNOWN);
            }
        }, 0, 10_000_000);
        List<TimelineModel.Lane> lanes = java.util.Arrays.stream(laneKeys)
                .map(key -> new TimelineModel.Lane(key, key, 1, 1_000, 0, 1_000))
                .toList();
        return new TimelineModel(index, lanes, List.of(), Map.of(), 0, 0, 0);
    }

    private static TimelineView viewShowing(TimelineModel model) {
        TimelineView view = new TimelineView();
        view.canvasForTest().setSize(WIDTH, HEIGHT);
        view.setModel(model);
        return view;
    }

    @Test
    @DisplayName("spans are placed by lane key, not by their index in the window")
    void placementFollowsTheKey() {
        TimelineView view = viewShowing(modelWithLanes("alpha", "beta"));

        SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
        window.add(0, 1_000, 0, 1, "beta");
        window.add(2_000, 3_000, 0, 2, "alpha");
        window.add(4_000, 5_000, 0, 3, "beta");
        view.setWindow(window.build());

        assertThat(view.rowOfSpan(0)).isEqualTo(1);
        assertThat(view.rowOfSpan(1)).isEqualTo(0);
        assertThat(view.rowOfSpan(2)).isEqualTo(1);
    }

    @Test
    @DisplayName("a refetch in another order or subset moves no span between rows")
    void refetchCannotShuffleRows() {
        TimelineView view = viewShowing(modelWithLanes("alpha", "beta", "gamma"));

        SpanWindow.Builder first = SpanWindow.builder(0, 10_000_000);
        first.add(0, 1_000, 0, 1, "gamma");
        first.add(2_000, 3_000, 0, 2, "alpha");
        first.add(4_000, 5_000, 0, 3, "beta");
        view.setWindow(first.build());
        int rowOfGamma = view.rowOfSpan(0);
        int rowOfBeta = view.rowOfSpan(2);

        // The same world after a pan: span 1 scrolled out of view, and the
        // fetch happened to return the survivors in a different order. Under
        // i % lanes both survivors would move; by key, neither may.
        SpanWindow.Builder second = SpanWindow.builder(2_000, 10_000_000);
        second.add(4_000, 5_000, 0, 3, "beta");
        second.add(2_000, 3_000, 0, 2, "alpha");
        view.setWindow(second.build());

        assertThat(view.rowOfSpan(0)).isEqualTo(rowOfBeta);
        assertThat(view.rowOfSpan(1)).isEqualTo(0); // alpha is lane 0, as before
        assertThat(rowOfGamma).isEqualTo(2);
    }

    @Test
    @DisplayName("driving the real pan and zoom listeners never moves a span between rows")
    void panAndZoomKeepRows() {
        TimelineView view = viewShowing(modelWithLanes("alpha", "beta"));
        SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
        window.add(0, 1_000, 0, 1, "beta");
        window.add(2_000, 3_000, 0, 2, "alpha");
        view.setWindow(window.build());
        int before0 = view.rowOfSpan(0);
        int before1 = view.rowOfSpan(1);

        JComponent canvas = view.canvasForTest();
        canvas.getMouseListeners()[0].mousePressed(new MouseEvent(canvas,
                MouseEvent.MOUSE_PRESSED, 0, 0, 500, 10, 1, false));
        canvas.getMouseMotionListeners()[0].mouseDragged(new MouseEvent(canvas,
                MouseEvent.MOUSE_DRAGGED, 0, 0, 300, 10, 1, false));
        canvas.getMouseWheelListeners()[0].mouseWheelMoved(new MouseWheelEvent(canvas,
                MouseEvent.MOUSE_WHEEL, 0, 0, 400, 10, 400, 10, 1, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -2, -2.0));

        // The viewport moved; the rows did not. (The window object is the
        // same here on purpose: the shuffle bug lived in the paint-time row
        // computation, which is exactly what rowOfSpan exposes.)
        assertThat(view.rowOfSpan(0)).isEqualTo(before0);
        assertThat(view.rowOfSpan(1)).isEqualTo(before1);
    }

    @Test
    @DisplayName("a span in no current lane goes to the extra row and is counted on the status line")
    void unmatchedSpansAreStatedNotHidden() {
        TimelineView view = viewShowing(modelWithLanes("alpha"));

        SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
        window.add(0, 1_000, 0, 1, "alpha");
        window.add(2_000, 3_000, 0, 2, "straggler");
        view.setWindow(window.build());

        assertThat(view.rowOfSpan(0)).isEqualTo(0);
        // Below every real lane, not silently in one of them.
        assertThat(view.rowOfSpan(1)).isEqualTo(1);
        assertThat(view.statusText()).contains("1 spans are in no current lane");
    }
}
