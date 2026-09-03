package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two formulas at the plan's own Tier 3 ceiling, measured.
 *
 * <p>Five million actions is what plan 20.1's Tier 3 names, and both of these run over every one of
 * them. Neither needs a database or a Bazel server, so measuring them at full size costs a second
 * rather than the gigabytes a real build of that size would — which is the reason the figures in
 * docs/performance.md are at the ceiling rather than at a tenth of it.
 */
final class MetricFormulaScaleTest {

  private static final int ACTIONS = 5_000_000;

  @Test
  @DisplayName("a sketch of five million durations, and a sweep of five million spans")
  void formulasRunAtTierThree() {
    long[] starts = new long[ACTIONS];
    long[] durations = new long[ACTIONS];
    for (int i = 0; i < ACTIONS; i++) {
      // Sixteen lanes of work with durations spread over four orders of
      // magnitude, which is roughly what a large build looks like.
      starts[i] = (i / 16L) * 1_000L;
      durations[i] = 500L + (i % 99_991) * 137L;
    }

    long began = System.nanoTime();
    QuantileSketch sketch = new QuantileSketch();
    for (long duration : durations) {
      sketch.add(duration);
    }
    QuantileSketch.Distribution distribution = sketch.snapshot();
    long sketchMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

    began = System.nanoTime();
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    for (int i = 0; i < ACTIONS; i++) {
      spans.add(starts[i], starts[i] + durations[i]);
    }
    ConcurrencySweep.Result sweep = ConcurrencySweep.sweep(spans);
    long sweepMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

    began = System.nanoTime();
    List<ConcurrencySweep.Window> windows = spans.windowsBelow(64, 1_000);
    long windowMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

    System.out.printf(
        "MetricFormulaScale: %d actions | sketch %d ms | sweep %d ms | windows %d ms%n"
            + "  p50 %s, p99 %s, peak %d, %d low windows, %d observations%n",
        ACTIONS,
        sketchMillis,
        sweepMillis,
        windowMillis,
        MetricFormat.bounds(distribution.quantile(0.5)),
        MetricFormat.bounds(distribution.quantile(0.99)),
        sweep.peakActive(),
        windows.size(),
        distribution.count());

    assertThat(distribution.count()).isEqualTo(ACTIONS);
    assertThat(sweep.sweptSpans()).isEqualTo(ACTIONS);
    assertThat(sweep.peakActive()).isPositive();
  }
}
