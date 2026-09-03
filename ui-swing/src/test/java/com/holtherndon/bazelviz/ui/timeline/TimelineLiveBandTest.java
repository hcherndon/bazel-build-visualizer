package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The live view: an in-flight target band, and a right edge that keeps up with the clock.
 *
 * <h2>Why the band is targets and says so</h2>
 *
 * <p>BEP has no action-start event — {@code ActionExecuted} fires once, at completion, and {@code
 * ActionOutcome} documents that there is no way to spell RUNNING. {@code TargetConfigured} → {@code
 * TargetCompleted} are the stream's real live signals, so what is "in flight" is a target, and the
 * band is labelled as targets so its spans cannot be misread as actions. These tests also pin the
 * honesty rules: exact counts, a cap that names its numbers, and a target with no timestamp absent
 * rather than zero-length.
 */
final class TimelineLiveBandTest {

  private static final int WIDTH = 1_000;
  private static final int HEIGHT = 400;
  private static final long WALL_START = 0;
  private static final long WALL_END = 10_000_000;

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  private static TimelineModel model(TimelineModel.LiveBand band) {
    TimelineLodIndex index =
        TimelineLodIndex.build(
            new SpanSource() {
              @Override
              public long spanCount() {
                return 1;
              }

              @Override
              public void forEachSpan(SpanConsumer consumer) {
                consumer.accept(WALL_START, WALL_START + 1_000, 0, 0, SpanSource.BYTES_UNKNOWN);
              }
            },
            WALL_START,
            WALL_END);
    return new TimelineModel(
        index,
        List.of(new TimelineModel.Lane("All actions", "", 1, 1_000, 0, 1_000)),
        List.of(),
        Map.of(),
        0,
        0,
        0,
        band);
  }

  private static TimelineView viewShowing(TimelineModel model, long nowMicros) {
    TimelineView view = new TimelineView();
    view.setClockForTest(() -> nowMicros);
    view.canvasForTest().setSize(WIDTH, HEIGHT);
    view.setModel(model);
    return view;
  }

  @Test
  @DisplayName("a finished session has no band; a live one always has a labelled band")
  void bandExistsExactlyWhenLive() {
    assertThat(viewShowing(model(TimelineModel.LiveBand.EMPTY), WALL_END).bandHeight()).isZero();

    TimelineView live =
        viewShowing(model(new TimelineModel.LiveBand(true, List.of(), 0, 0)), WALL_END);
    // Even with nothing in flight the band is present and says so, so the
    // lanes below cannot be mistaken for the whole story of a running build.
    assertThat(live.bandHeight()).isGreaterThan(0);
    assertThat(TimelineView.bandLabel(new TimelineModel.LiveBand(true, List.of(), 0, 0)))
        .contains("In-flight targets")
        .contains("none right now");
  }

  @Test
  @DisplayName("while live and following, the right edge is the wall clock, not the last datum")
  void rightEdgeAdvancesWithTheClock() {
    long now = WALL_END + 20_000_000; // twenty seconds past the last datum
    TimelineView view = viewShowing(model(new TimelineModel.LiveBand(true, List.of(), 0, 0)), now);

    TimelineTransform transform = view.viewport().orElseThrow().transform();
    assertThat(transform.microsAtX(WIDTH))
        .as("the fitted right edge sits at the clock")
        .isCloseTo(now, within(1_000.0));
  }

  @Test
  @DisplayName("a user who navigated away is not dragged along by the live edge")
  void navigatedViewportIsLeftAlone() {
    long now = WALL_END + 20_000_000;
    TimelineView view = viewShowing(model(new TimelineModel.LiveBand(true, List.of(), 0, 0)), now);
    // The user pans: following turns off and the transform is theirs.
    MouseEvent press =
        new MouseEvent(
            view.canvasForTest(),
            MouseEvent.MOUSE_PRESSED,
            0,
            InputEvent.BUTTON1_DOWN_MASK,
            500,
            10,
            1,
            false,
            MouseEvent.BUTTON1);
    MouseEvent drag =
        new MouseEvent(
            view.canvasForTest(),
            MouseEvent.MOUSE_DRAGGED,
            0,
            InputEvent.BUTTON1_DOWN_MASK,
            450,
            10,
            1,
            false,
            MouseEvent.NOBUTTON);
    view.canvasForTest().getMouseListeners()[0].mousePressed(press);
    view.canvasForTest().getMouseMotionListeners()[0].mouseDragged(drag);
    TimelineTransform navigated = view.viewport().orElseThrow().transform();

    // A live rebuild lands. The wall grew; the user's transform must not move.
    view.setModel(model(new TimelineModel.LiveBand(true, List.of(), 0, 0)));

    assertThat(view.viewport().orElseThrow().transform()).isEqualTo(navigated);
    assertThat(view.followingForTest()).isFalse();
  }

  @Test
  @DisplayName("in-flight spans grow from their received configuration to now, in reserved blue")
  void inFlightSpansGrowToNow() {
    long now = WALL_END;
    long configuredAt = 2_000_000;
    TimelineView view =
        viewShowing(
            model(
                new TimelineModel.LiveBand(
                    true,
                    List.of(new TimelineModel.LiveBand.InFlight("//pkg:a", configuredAt)),
                    1,
                    0)),
            now);

    // One in-flight target: one band row, and a click anywhere along its
    // grown extent opens the inspector on the target, labelled as one.
    assertThat(view.bandRows()).isEqualTo(1);
    assertThat(view.bandIndexAt(500, 5)).isZero();
    view.clickAt(500, 5);
    assertThat(view.inspectorVisibleForTest()).isTrue();
    assertThat(view.inspectorTitleForTest()).isEqualTo("//pkg:a");

    // Before its configuration was received, the target is nowhere.
    assertThat(view.bandIndexAt(100, 5)).isEqualTo(-1);

    // Blue is reserved for exactly this.
    assertThat(TimelineColours.IN_FLIGHT)
        .isNotEqualTo(TimelineColours.SUCCESS)
        .isNotEqualTo(TimelineColours.FAILED);
  }

  @Test
  @DisplayName("the band's label carries exact counts: total, cap, and the undrawable")
  void bandLabelIsHonest() {
    List<TimelineModel.LiveBand.InFlight> shown =
        List.of(
            new TimelineModel.LiveBand.InFlight("//pkg:a", 1_000),
            new TimelineModel.LiveBand.InFlight("//pkg:b", 2_000));

    // Twelve in flight, three with no timestamp, only two drawn: every
    // number is in the sentence, so a partial band cannot pass for whole.
    String label = TimelineView.bandLabel(new TimelineModel.LiveBand(true, shown, 12, 3));
    assertThat(label)
        .contains("12 configured, not yet completed")
        .contains("BEP receive time")
        .contains("drawing the earliest 2 of 9")
        .contains("3 have no received timestamp and are counted but not drawn");

    // Nothing missing, nothing capped: no partiality claims either.
    String complete = TimelineView.bandLabel(new TimelineModel.LiveBand(true, shown, 2, 0));
    assertThat(complete)
        .contains("2 configured")
        .doesNotContain("drawing the earliest")
        .doesNotContain("no received timestamp");
  }
}
