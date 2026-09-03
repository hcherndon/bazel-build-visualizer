package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Live updates must not move the user.
 *
 * <p>Plan 24's fifth Phase 6 exit criterion, and plan 14.4's "do not auto-scroll if the user has
 * navigated into history". Every one of these tests is that sentence in a different shape.
 */
final class TimelineViewportTest {

  private static final int WIDTH = 1_000;

  private static TimelineViewport fitted() {
    return TimelineViewport.fitting(0, 1_000_000, WIDTH);
  }

  @Test
  @DisplayName("a fresh viewport follows the build")
  void freshViewportsFollow() {
    assertThat(fitted().following()).isTrue();
    assertThat(fitted().followLabel()).isEqualTo("Following live");
  }

  @Test
  @DisplayName("a following viewport is refitted when the build grows")
  void followingViewportsGrow() {
    TimelineViewport grown = fitted().withWall(0, 2_000_000, WIDTH);

    // Half the zoom, because the wall doubled and the window did not.
    assertThat(grown.transform().pixelsPerMicro())
        .isEqualTo(fitted().transform().pixelsPerMicro() / 2);
    assertThat(grown.following()).isTrue();
  }

  @Test
  @DisplayName("panning stops the timeline following, immediately")
  void navigatingStopsFollowing() {
    TimelineViewport moved = fitted().navigatedTo(fitted().transform().pannedByPixels(-120));

    assertThat(moved.following()).isFalse();
    assertThat(moved.followLabel()).isEqualTo("Follow live");
  }

  @Test
  @DisplayName("a viewport the user moved is not moved again by new data")
  void newDataDoesNotStealTheView() {
    TimelineViewport moved = fitted().navigatedTo(fitted().transform().zoomedAround(500, 8.0));
    TimelineTransform before = moved.transform();

    TimelineViewport after = moved.withWall(0, 90_000_000, WIDTH);

    // The build grew ninety-fold and the user's view did not move a pixel.
    assertThat(after.transform()).isEqualTo(before);
    assertThat(after.following()).isFalse();
  }

  @Test
  @DisplayName("a live update keeps the selection")
  void selectionSurvivesLiveUpdates() {
    TimelineViewport selected = fitted().selecting(OptionalLong.of(42));

    assertThat(selected.withWall(0, 5_000_000, WIDTH).selectedNode()).hasValue(42);
    // And keeps it when the user has moved, too.
    assertThat(
            selected
                .navigatedTo(selected.transform().pannedByPixels(-10))
                .withWall(0, 5_000_000, WIDTH)
                .selectedNode())
        .hasValue(42);
  }

  @Test
  @DisplayName("a live update keeps a dragged-out time range")
  void rangeSurvivesLiveUpdates() {
    TimelineViewport ranged = fitted().withRange(200_000, 300_000);

    TimelineViewport after = ranged.withWall(0, 5_000_000, WIDTH);

    assertThat(after.hasRange()).isTrue();
    assertThat(after.rangeFromMicros()).hasValue(200_000);
    assertThat(after.rangeToMicros()).hasValue(300_000);
  }

  @Test
  @DisplayName("a range dragged backwards is still a range")
  void rangesAreOrdered() {
    TimelineViewport backwards = fitted().withRange(900_000, 100_000);

    assertThat(backwards.rangeFromMicros()).hasValue(100_000);
    assertThat(backwards.rangeToMicros()).hasValue(900_000);
    assertThat(backwards.describeRange()).hasValue("0.800 s selected");
  }

  @Test
  @DisplayName("turning following back on lets the build pull the view again")
  void followingCanBeResumed() {
    TimelineViewport moved = fitted().navigatedTo(fitted().transform().pannedByPixels(-500));
    TimelineViewport resumed = moved.following(true);

    assertThat(resumed.withWall(0, 4_000_000, WIDTH).transform()).isNotEqualTo(moved.transform());
  }

  @Test
  @DisplayName("selecting does not change the view, and moving does not change the selection")
  void selectionAndViewAreIndependent() {
    TimelineViewport start = fitted().selecting(OptionalLong.of(7));

    TimelineViewport panned = start.navigatedTo(start.transform().pannedByPixels(-40));

    assertThat(panned.selectedNode()).hasValue(7);
    assertThat(start.selecting(OptionalLong.of(9)).transform()).isEqualTo(start.transform());
  }

  @Test
  @DisplayName("clearing the range keeps everything else")
  void clearingRangeKeepsTheRest() {
    TimelineViewport ranged =
        fitted().selecting(OptionalLong.of(3)).withRange(10, 20).following(false);

    TimelineViewport cleared = ranged.withoutRange();

    assertThat(cleared.hasRange()).isFalse();
    assertThat(cleared.selectedNode()).hasValue(3);
    assertThat(cleared.following()).isFalse();
    assertThat(cleared.transform()).isEqualTo(ranged.transform());
  }
}
