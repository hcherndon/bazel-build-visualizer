package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * t5: "The timeline page can go negative - time should not be negative."
 *
 * <h2>What "fixed" means here</h2>
 *
 * <p>The timeline canvas's own mouse handlers used to feed
 * {@link TimelineTransform#pannedByPixels} and
 * {@link TimelineTransform#zoomedAround} straight into the viewport, so an
 * offset or a zoom driven by the mouse could drift arbitrarily far from the
 * session's own wall — which is what let {@code TimelineView}'s axis header
 * print an arbitrarily negative number of seconds. The fix ports
 * {@code TimelineCanvas}'s own {@code clamped()} bound into this view (see
 * {@code TimelineView.clamp}): a pan or a zoom can still show a little of the
 * wall's own edge as a margin — the same design {@link TimelineCanvas}'s spike
 * already used, on purpose, to avoid the view hitting a hard wall — but it is
 * now a fixed, computable bound rather than an unbounded drift. These tests
 * drive the real listeners (obtained off the canvas, not reimplemented) with
 * absurd pans and zooms and check the result never moves past that bound, and
 * that it stops moving once it gets there.
 */
final class TimelineViewTest {

    private static final int WIDTH = 1_000;
    private static final int HEIGHT = 400;
    private static final long WALL_START = 0;
    private static final long WALL_END = 10_000_000; // a ten-second wall

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    /** One span, described in full, for building a tiny model by hand. */
    private record Span(long start, long end, int category, int flags, long bytes) {}

    private static SpanSource sourceOf(List<Span> spans) {
        return new SpanSource() {
            @Override
            public long spanCount() {
                return spans.size();
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                for (Span span : spans) {
                    consumer.accept(span.start(), span.end(), span.category(),
                            span.flags(), span.bytes());
                }
            }
        };
    }

    /** A ten-second wall, the same one every test in this class looks at. */
    private static TimelineModel aModel() {
        TimelineLodIndex index = TimelineLodIndex.build(
                sourceOf(List.of(new Span(WALL_START, WALL_START + 1_000, 0, 0,
                        SpanSource.BYTES_UNKNOWN))),
                WALL_START, WALL_END);
        return new TimelineModel(
                index,
                List.of(new TimelineModel.Lane(
                        "All actions", "", 1, 1_000, WALL_START, WALL_START + 1_000)),
                List.of(), Map.of(), 0, 0, 0);
    }

    /** A view showing the given model, sized so mouse pixel math means something. */
    private static TimelineView viewShowing(TimelineModel model) {
        TimelineView view = new TimelineView();
        // Before setModel: the fitted viewport's zoom is derived from the
        // canvas width at the moment the model lands.
        view.canvasForTest().setSize(WIDTH, HEIGHT);
        view.setModel(model);
        return view;
    }

    /** A view showing a real wall, sized so mouse pixel math means something. */
    private static TimelineView viewShowingAWall() {
        return viewShowing(aModel());
    }

    private static MouseListener pressListener(JComponent canvas) {
        return canvas.getMouseListeners()[0];
    }

    private static MouseMotionListener dragListener(JComponent canvas) {
        return canvas.getMouseMotionListeners()[0];
    }

    private static MouseWheelListener wheelListener(JComponent canvas) {
        return canvas.getMouseWheelListeners()[0];
    }

    private static MouseEvent pressAt(JComponent canvas, int x) {
        return new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, 10, 1, false);
    }

    private static MouseEvent dragTo(JComponent canvas, int x) {
        return new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), 0, x, 10, 1, false);
    }

    /** @param rotation positive scrolls "down", which this view treats as zooming out. */
    private static MouseWheelEvent wheelAt(JComponent canvas, int x, double rotation) {
        return new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                x, 10, x, 10, 1, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1,
                (int) Math.round(rotation), rotation);
    }

    @Test
    @DisplayName("dragging hard left cannot pan the offset past the ported floor")
    void panningIsBounded() {
        TimelineView view = viewShowingAWall();
        JComponent canvas = view.canvasForTest();

        pressListener(canvas).mousePressed(pressAt(canvas, 500));
        // One absurd drag -- 50,000 pixels in a single event, which at the
        // fitted zoom (1e-4 px/us here) would pan the offset by roughly
        // 5*10^8 microseconds without a clamp: five hundred seconds behind a
        // ten-second wall.
        dragListener(canvas).mouseDragged(dragTo(canvas, 50_500));

        TimelineTransform after = view.viewport().orElseThrow().transform();
        double visible = WIDTH / after.pixelsPerMicro();
        double expectedFloor = WALL_START - 0.5 * visible;

        assertThat(after.offsetMicros()).isCloseTo(expectedFloor, within(1e-6));
        // And nowhere near what an unclamped drag of that size would have
        // produced -- proof this is the floor, not a coincidence of scale.
        assertThat(after.offsetMicros()).isGreaterThan(-1_000_000_000.0);

        // A second, equally absurd drag does not push it any further: this is
        // a floor, not merely a slower drift.
        dragListener(canvas).mouseDragged(dragTo(canvas, 100_500));
        assertThat(view.viewport().orElseThrow().transform().offsetMicros())
                .isCloseTo(expectedFloor, within(1e-6));
    }

    @Test
    @DisplayName("scrolling out cannot zoom past the ported floor")
    void zoomingOutIsBounded() {
        TimelineView view = viewShowingAWall();
        JComponent canvas = view.canvasForTest();

        double wallSpan = WALL_END - WALL_START;
        double minPpm = WIDTH / (2.0 * wallSpan);

        // Fifty wheel-out ticks, as a user spinning a wheel would send. At
        // factor 1.1^-3 per tick this multiplies the zoom by roughly
        // 1.1^-150 -- about six orders of magnitude below the floor -- if
        // nothing bounds it.
        for (int i = 0; i < 50; i++) {
            wheelListener(canvas).mouseWheelMoved(wheelAt(canvas, 500, 3.0));
        }

        double ppm = view.viewport().orElseThrow().transform().pixelsPerMicro();
        assertThat(ppm).isCloseTo(minPpm, within(minPpm * 1e-9));

        // One more tick does not shrink it any further: this is a floor.
        wheelListener(canvas).mouseWheelMoved(wheelAt(canvas, 500, 3.0));
        assertThat(view.viewport().orElseThrow().transform().pixelsPerMicro())
                .isCloseTo(minPpm, within(minPpm * 1e-9));
    }

    @Test
    @DisplayName("the axis draws no tick for a time before the wall, even parked at the "
            + "extreme left of the clamp")
    void axisNeverLabelsATimeBeforeTheWall() {
        // A bounded pan is still a real fix for t5's "time should not be
        // negative" only if the axis itself never asserts one of those
        // in-bounds-but-before-the-wall x positions as a time. This drives the
        // view to the exact floor panningIsBounded already proved exists, via
        // the same real mouse handler, and then calls the exact method
        // Header.paintComponent calls to decide what to draw -- not a
        // reimplementation of its rule.
        TimelineModel model = aModel();
        TimelineView view = viewShowing(model);
        JComponent canvas = view.canvasForTest();

        pressListener(canvas).mousePressed(pressAt(canvas, 500));
        dragListener(canvas).mouseDragged(dragTo(canvas, 50_500));

        TimelineViewport viewport = view.viewport().orElseThrow();
        // x = 0 is provably inside the clamped margin here: the floor test
        // already established offsetMicros sits at wallStart - 0.5*visible,
        // strictly before the wall, and x = 0 is exactly where that offset is
        // shown.
        double microsAtLeftEdge = viewport.transform().microsAtX(0) - model.wallStartMicros();
        assertThat(microsAtLeftEdge)
                .as("the setup must actually reach into the margin, or this test proves nothing")
                .isNegative();

        List<TimelineView.Tick> ticks = TimelineView.ticksFor(viewport, model, WIDTH);

        assertThat(ticks)
                .as("no tick may claim a time before the build started")
                .noneMatch(tick -> tick.label().startsWith("-"));
        // Not merely non-negative by coincidence: the leftmost tick position
        // sits in the margin and must be entirely absent, tick and label both.
        assertThat(ticks).noneMatch(tick -> tick.x() == 0);
    }
}
