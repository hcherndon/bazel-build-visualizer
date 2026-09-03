package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JPanel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Native pinch is optional, but its zoom math and packaged-JVM bridge are deterministic. */
final class MacMagnificationSupportTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("magnification deltas produce finite, symmetric, bounded zoom factors")
  void zoomFactorIsSafe() {
    double zoomIn = MacMagnificationSupport.zoomFactor(0.25);
    double zoomOut = MacMagnificationSupport.zoomFactor(-0.25);

    assertThat(zoomIn).isGreaterThan(1.0);
    assertThat(zoomOut).isBetween(0.0, 1.0);
    assertThat(zoomIn * zoomOut).isCloseTo(1.0, within(1e-12));
    assertThat(MacMagnificationSupport.zoomFactor(50.0))
        .isEqualTo(MacMagnificationSupport.zoomFactor(1.0));
    assertThat(MacMagnificationSupport.zoomFactor(-50.0))
        .isEqualTo(MacMagnificationSupport.zoomFactor(-1.0));
    assertThat(MacMagnificationSupport.zoomFactor(Double.NaN)).isEqualTo(1.0);
    assertThat(MacMagnificationSupport.zoomFactor(Double.POSITIVE_INFINITY)).isEqualTo(1.0);
  }

  @Test
  @DisplayName("the packaged macOS JVM exposes the native bridge, and other platforms decline it")
  void availabilityMatchesThePlatform() {
    MacMagnificationSupport.Registration registration =
        MacMagnificationSupport.install(new JPanel(), ignored -> {});
    boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    try {
      assertThat(registration.available()).isEqualTo(mac);
    } finally {
      registration.close();
    }
    assertThat(registration.available()).isFalse();
  }

  @Test
  @DisplayName("a native pinch preserves the time beneath its centre anchor")
  void pinchUsesTheViewsAnchorPreservingZoom() {
    TimelineLodIndex index =
        TimelineLodIndex.build(
            new SpanSource() {
              @Override
              public long spanCount() {
                return 1;
              }

              @Override
              public void forEachSpan(SpanConsumer consumer) {
                consumer.accept(0, 1_000, 0, 0, SpanSource.BYTES_UNKNOWN);
              }
            },
            0,
            10_000_000);
    TimelineModel model =
        new TimelineModel(
            index,
            List.of(new TimelineModel.Lane("All", "", 1, 1_000, 0, 1_000)),
            List.of(),
            Map.of(),
            0,
            0,
            0);
    TimelineView view = new TimelineView();
    view.canvasForTest().setSize(1_000, 400);
    view.setModel(model);

    int centre = 500;
    TimelineTransform before = view.viewport().orElseThrow().transform();
    double anchoredMicros = before.microsAtX(centre);
    view.magnifyForTest(0.3);
    TimelineTransform after = view.viewport().orElseThrow().transform();

    assertThat(after.pixelsPerMicro()).isGreaterThan(before.pixelsPerMicro());
    assertThat(after.microsAtX(centre)).isCloseTo(anchoredMicros, within(1e-6));
    assertThat(view.followingForTest()).isFalse();
  }
}
