package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class TimelineTransformTest {

  private static final double EPS = 1e-6;

  @Test
  void roundTripsWorldToScreenAndBack() {
    TimelineTransform t = new TimelineTransform(123_456.0, 0.004);
    for (double micros : new double[] {0, 123_456, 999_999_999, -50_000}) {
      assertThat(t.microsAtX(t.xForMicros(micros))).isCloseTo(micros, within(EPS));
    }
    for (double x : new double[] {-1000, 0, 0.5, 1600, 1e7}) {
      assertThat(t.xForMicros(t.microsAtX(x))).isCloseTo(x, within(EPS));
    }
  }

  @Test
  void fitMapsRangeEndpointsToComponentEdges() {
    TimelineTransform t = TimelineTransform.fit(1_000, 11_000, 500);
    assertThat(t.xForMicros(1_000)).isCloseTo(0, within(EPS));
    assertThat(t.xForMicros(11_000)).isCloseTo(500, within(EPS));
    assertThat(t.microsAtX(250)).isCloseTo(6_000, within(EPS));
  }

  @Test
  void panMovesContentWithThePointer() {
    TimelineTransform t = new TimelineTransform(5_000.0, 0.01);
    TimelineTransform panned = t.pannedByPixels(120);
    // The world time previously at x is now at x + 120.
    assertThat(panned.microsAtX(120)).isCloseTo(t.microsAtX(0), within(EPS));
    assertThat(panned.pixelsPerMicro()).isEqualTo(t.pixelsPerMicro());
    // Panning back is an exact inverse.
    assertThat(panned.pannedByPixels(-120).offsetMicros()).isCloseTo(t.offsetMicros(), within(EPS));
  }

  @Test
  void zoomKeepsAnchorInvariant() {
    TimelineTransform t = new TimelineTransform(250_000.0, 0.002);
    for (double anchorX : new double[] {0, 1, 400, 1599.5}) {
      for (double factor : new double[] {0.25, 0.5, 2, 8}) {
        TimelineTransform zoomed = t.zoomedAround(anchorX, factor);
        assertThat(zoomed.pixelsPerMicro()).isCloseTo(t.pixelsPerMicro() * factor, within(EPS));
        assertThat(zoomed.microsAtX(anchorX)).isCloseTo(t.microsAtX(anchorX), within(EPS));
      }
    }
  }

  @Test
  void zoomInThenOutRestoresTheView() {
    TimelineTransform t = new TimelineTransform(77_000.0, 0.05);
    TimelineTransform back = t.zoomedAround(333, 4.0).zoomedAround(333, 0.25);
    assertThat(back.offsetMicros()).isCloseTo(t.offsetMicros(), within(EPS));
    assertThat(back.pixelsPerMicro()).isCloseTo(t.pixelsPerMicro(), within(EPS));
  }

  @Test
  void rejectsNonPositiveOrNonFiniteZoom() {
    assertThatIllegalArgumentException().isThrownBy(() -> new TimelineTransform(0, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> new TimelineTransform(0, -1));
    assertThatIllegalArgumentException().isThrownBy(() -> new TimelineTransform(Double.NaN, 1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TimelineTransform(0, Double.POSITIVE_INFINITY));
  }
}
