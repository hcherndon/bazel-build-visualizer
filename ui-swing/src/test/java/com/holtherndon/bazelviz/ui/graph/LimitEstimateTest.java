package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whether a drawing will fit, and how confidently that can be said. */
final class LimitEstimateTest {

  @Test
  @DisplayName("a whole-graph estimate is exact, because the index knows its own size")
  void wholeGraphIsExact() {
    LimitEstimate estimate =
        LimitEstimate.of(GraphExtract.Mode.WHOLE, 120_000, 400_000, 50_000, 200_000);

    assertThat(estimate.exact()).isTrue();
    assertThat(estimate.fits()).isFalse();
    assertThat(estimate.describe())
        .contains("120000 actions")
        .contains("400000 dependencies")
        .contains("50000-action");
  }

  @Test
  @DisplayName("a traversal estimate is a ceiling and says so")
  void traversalsAreBounded() {
    LimitEstimate estimate =
        LimitEstimate.of(GraphExtract.Mode.NEIGHBOURHOOD, 120_000, 400_000, 50_000, 200_000);

    // Only a traversal knows how far a traversal reaches. Presenting the
    // ceiling as a count is the small dishonesty that makes every other
    // number suspect.
    assertThat(estimate.exact()).isFalse();
    assertThat(estimate.describe())
        .contains("in total")
        .contains("may stop before it has drawn everything");
  }

  @Test
  @DisplayName("a graph that fits produces no warning at all")
  void fittingGraphsAreSilent() {
    LimitEstimate estimate = LimitEstimate.of(GraphExtract.Mode.WHOLE, 900, 2_000, 50_000, 200_000);

    assertThat(estimate.fits()).isTrue();
    // Not a reassuring sentence: a standing notice trains a user to stop
    // reading the place the real warning appears.
    assertThat(estimate.warning()).isEmpty();
  }

  @Test
  @DisplayName("the edge limit can be the one that does not fit")
  void edgesCanBeTheProblem() {
    // A dense graph can be well under the node limit and far over the edge
    // one; checking only nodes would draw it and then be very slow.
    LimitEstimate estimate =
        LimitEstimate.of(GraphExtract.Mode.WHOLE, 1_000, 900_000, 50_000, 200_000);

    assertThat(estimate.fits()).isFalse();
    assertThat(estimate.warning()).isPresent();
  }

  @Test
  @DisplayName("exactly at the limit still fits")
  void theLimitIsInclusive() {
    assertThat(LimitEstimate.of(GraphExtract.Mode.WHOLE, 50_000, 200_000, 50_000, 200_000).fits())
        .isTrue();
    assertThat(LimitEstimate.of(GraphExtract.Mode.WHOLE, 50_001, 200_000, 50_000, 200_000).fits())
        .isFalse();
  }
}
