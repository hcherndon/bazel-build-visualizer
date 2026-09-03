package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** World-to-screen mapping, and the two invariants a user would notice breaking. */
final class GraphTransformTest {

  @Test
  @DisplayName("screen and world are inverses of each other")
  void roundTrip() {
    GraphTransform transform = new GraphTransform(120.5, -33.25, 2.75);

    assertThat(transform.worldX(transform.screenX(987.625))).isCloseTo(987.625, within(1e-9));
    assertThat(transform.worldY(transform.screenY(-42.5))).isCloseTo(-42.5, within(1e-9));
  }

  @Test
  @DisplayName("zooming keeps the world point under the pointer under the pointer")
  void zoomIsAnchored() {
    GraphTransform transform = new GraphTransform(0, 0, 1);
    double anchorWorldX = transform.worldX(300);
    double anchorWorldY = transform.worldY(200);

    GraphTransform zoomed = transform.zoomedAround(300, 200, 2.5);

    // The whole point of anchored zoom: scrolling over a node means closer
    // to that node, not closer to the middle of the window.
    assertThat(zoomed.screenX(anchorWorldX)).isCloseTo(300, within(1e-9));
    assertThat(zoomed.screenY(anchorWorldY)).isCloseTo(200, within(1e-9));
  }

  @Test
  @DisplayName("panning moves content with the pointer, one pixel for one pixel")
  void panFollowsThePointer() {
    GraphTransform transform = new GraphTransform(0, 0, 3);
    double worldX = transform.worldX(100);

    GraphTransform panned = transform.pannedByPixels(40, -25);

    assertThat(panned.screenX(worldX)).isCloseTo(140, within(1e-9));
    assertThat(panned.scale()).isEqualTo(3);
  }

  @Test
  @DisplayName("zoom is clamped, so a fast scroll cannot reach a degenerate scale")
  void zoomIsClamped() {
    GraphTransform transform = GraphTransform.identity();

    GraphTransform tiny = transform;
    GraphTransform huge = transform;
    for (int i = 0; i < 500; i++) {
      tiny = tiny.zoomedAround(0, 0, 0.5);
      huge = huge.zoomedAround(0, 0, 2);
    }

    assertThat(tiny.scale()).isEqualTo(GraphTransform.MIN_SCALE);
    assertThat(huge.scale()).isEqualTo(GraphTransform.MAX_SCALE);
  }

  @Test
  @DisplayName("fitting centres the graph in the viewport")
  void fitCentres() {
    double[] bounds = {0, 0, 1_000, 500};

    GraphTransform fitted = GraphTransform.fit(bounds, 800, 600, 40);

    // The centre of the box lands at the centre of the window whatever the
    // aspect ratios are.
    assertThat(fitted.screenX(500)).isCloseTo(400, within(1e-6));
    assertThat(fitted.screenY(250)).isCloseTo(300, within(1e-6));
  }

  @Test
  @DisplayName("fitting a linear layout does not divide by its zero height")
  void fitDegenerateBounds() {
    // A critical path has no height at all, and it is one of the views a
    // user reaches for most.
    GraphTransform fitted = GraphTransform.fit(new double[] {0, 7, 900, 7}, 800, 600, 40);

    assertThat(fitted.scale()).isBetween(GraphTransform.MIN_SCALE, GraphTransform.MAX_SCALE);
    assertThat(fitted.screenY(7)).isCloseTo(300, within(1e-6));
  }

  @Test
  @DisplayName("fitting a single point produces a usable view rather than infinite zoom")
  void fitSinglePoint() {
    GraphTransform fitted = GraphTransform.fit(new double[] {5, 5, 5, 5}, 800, 600, 40);

    assertThat(fitted.scale()).isEqualTo(GraphTransform.MAX_SCALE);
    assertThat(fitted.screenX(5)).isCloseTo(400, within(1e-6));
  }

  @Test
  @DisplayName("a zero-sized viewport is the identity, not an exception")
  void fitIntoNothing() {
    assertThat(GraphTransform.fit(new double[] {0, 0, 10, 10}, 0, 0, 4))
        .isEqualTo(GraphTransform.identity());
  }

  @Test
  @DisplayName("a fit with a label reservation keeps that many pixels free on the right")
  void fitWithReservation() {
    double[] bounds = {0, 0, 1_000, 500};

    GraphTransform plain = GraphTransform.fit(bounds, 800, 600, 40);
    GraphTransform reserved = GraphTransform.fit(bounds, 800, 600, 40, 200, 20);

    // Text does not scale with the world, so making room for it means a
    // smaller world scale, never a lie about the text fitting.
    assertThat(reserved.scale()).isLessThan(plain.scale());
    // The drawing starts at the margin and ends a reservation short of
    // the far margin, which is exactly where its labels will paint.
    assertThat(reserved.screenX(0)).isCloseTo(40, within(1e-6));
    assertThat(reserved.screenX(1_000)).isCloseTo(800 - 40 - 200, within(1e-6));
  }

  @Test
  @DisplayName("a zero reservation is exactly the plain fit")
  void zeroReservationIsThePlainFit() {
    double[] bounds = {-5, 12, 990, 480};

    assertThat(GraphTransform.fit(bounds, 800, 600, 40, 0, 0))
        .isEqualTo(GraphTransform.fit(bounds, 800, 600, 40));
  }

  @Test
  @DisplayName("a reservation wider than the window still produces a usable view")
  void absurdReservationDoesNotDegenerate() {
    GraphTransform fitted =
        GraphTransform.fit(new double[] {0, 0, 100, 100}, 800, 600, 40, 5_000, 5_000);

    // The caller caps reservations; the transform still refuses to hand
    // back a zero or negative scale if one gets through.
    assertThat(fitted.scale()).isBetween(GraphTransform.MIN_SCALE, GraphTransform.MAX_SCALE);
  }

  @Test
  @DisplayName("an impossible transform is rejected at construction")
  void invalidTransforms() {
    assertThatThrownBy(() -> new GraphTransform(0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GraphTransform(Double.NaN, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GraphTransform(0, 0, Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the visible world rectangle is what the viewport shows")
  void visibleWorld() {
    GraphTransform transform = new GraphTransform(100, 200, 2);

    double[] visible = transform.visibleWorld(400, 300);

    assertThat(visible).containsExactly(100, 200, 300, 350);
  }

  @Test
  @DisplayName("the detail bands step up with zoom and never skip one")
  void detailBands() {
    assertThat(GraphCanvas.Detail.forScale(0.01)).isEqualTo(GraphCanvas.Detail.FAR);
    assertThat(GraphCanvas.Detail.forScale(0.3)).isEqualTo(GraphCanvas.Detail.MEDIUM);
    assertThat(GraphCanvas.Detail.forScale(2)).isEqualTo(GraphCanvas.Detail.NEAR);
    // Monotone: zooming in never reduces detail, which would look like a
    // rendering bug even if each band were individually defensible.
    GraphCanvas.Detail previous = GraphCanvas.Detail.FAR;
    for (double scale = GraphTransform.MIN_SCALE; scale < GraphTransform.MAX_SCALE; scale *= 1.05) {
      GraphCanvas.Detail current = GraphCanvas.Detail.forScale(scale);
      assertThat(current.ordinal()).isGreaterThanOrEqualTo(previous.ordinal());
      previous = current;
    }
  }
}
