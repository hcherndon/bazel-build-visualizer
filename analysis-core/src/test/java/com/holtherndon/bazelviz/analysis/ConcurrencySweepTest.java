package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What was running when, and the three ways a sweep can quietly lie: counting a handover as an
 * overlap, treating an untimed action as an idle one, and reporting a peak of nothing when every
 * span has zero length.
 */
final class ConcurrencySweepTest {

  private static ConcurrencySweep.Spans spans(long... startEndPairs) {
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    for (int i = 0; i < startEndPairs.length; i += 2) {
      spans.add(startEndPairs[i], startEndPairs[i + 1]);
    }
    return spans;
  }

  @Test
  @DisplayName("overlapping spans raise the count and the peak is the highest instant")
  void peakIsTheHighestInstant() {
    // 0-100, 50-150, 60-70: three overlap only across 60-70.
    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans(0, 100, 50, 150, 60, 70));

    assertThat(result.peakActive()).isEqualTo(3);
    assertThat(result.peakFirstSeenMicros()).isEqualTo(60);
    assertThat(result.timeAtLevel(3)).isEqualTo(10);
    assertThat(result.timeAtLevel(2)).isEqualTo(40);
    assertThat(result.timeAtLevel(1)).isEqualTo(100);
    assertThat(result.timeAtLevel(0)).isZero();
  }

  @Test
  @DisplayName("a handover is not an overlap")
  void backToBackActionsDoNotOverlap() {
    // The half-open convention. Without it, a chain of a thousand
    // sequential actions would report a peak of two and a build that looked
    // twice as parallel as it was.
    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans(0, 10, 10, 20, 20, 30));

    assertThat(result.peakActive()).isEqualTo(1);
    assertThat(result.timeAtLevel(1)).isEqualTo(30);
    assertThat(result.timeAtLevel(2)).isZero();
  }

  @Test
  @DisplayName("gaps are counted as idle, and only the gaps")
  void idleTimeIsTheGaps() {
    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans(0, 10, 25, 30));

    assertThat(result.idleMicros()).isEqualTo(15);
    assertThat(result.windowMicros()).isEqualTo(30);
    assertThat(result.timeAtLevel(1)).isEqualTo(15);
  }

  @Test
  @DisplayName("the parallelism factor is span time over wall time")
  void parallelismFactorIsDefined() {
    // Plan 15.4: sum of durations / wall-clock interval. Two 100 µs actions
    // fully overlapping across 100 µs is a factor of two.
    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans(0, 100, 0, 100));

    assertThat(result.totalSpanMicros()).hasValue(200);
    assertThat(result.parallelismFactor()).hasValue(2.0);
    assertThat(result.averageActive()).isEqualTo(result.parallelismFactor());
  }

  @Test
  @DisplayName("average while busy ignores the idle stretches, and is a different number")
  void averageWhileBusyExcludesIdleTime() {
    // 100 µs of one action, 100 µs of nothing, 100 µs of two actions.
    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans(0, 100, 200, 300, 200, 300));

    assertThat(result.windowMicros()).isEqualTo(300);
    assertThat(result.idleMicros()).isEqualTo(100);
    assertThat(result.parallelismFactor()).hasValue(300.0 / 300.0);
    assertThat(result.averageActiveWhileBusy().orElseThrow())
        .isCloseTo(300.0 / 200.0, within(1e-9));
  }

  @Test
  @DisplayName("low-parallelism windows are found, and short ones can be ignored")
  void lowWindowsAreReported() {
    ConcurrencySweep.Spans spans = spans(0, 100, 0, 100, 101, 102, 500, 600, 500, 600);

    // Below two active: the single-action blip at 101 and the gap that
    // follows it, which together run from 100 to 500.
    List<ConcurrencySweep.Window> windows = spans.windowsBelow(2, 0);

    assertThat(windows).hasSize(1);
    assertThat(windows.getFirst().startMicros()).isEqualTo(100);
    assertThat(windows.getFirst().endMicros()).isEqualTo(500);
    assertThat(windows.getFirst().durationMicros()).isEqualTo(400);
    assertThat(windows.getFirst().peakActive()).isEqualTo(1);
    // And a minimum length filters it out entirely.
    assertThat(spans.windowsBelow(2, 500)).isEmpty();
  }

  @Test
  @DisplayName("a low window still open at the last end is closed there, not dropped")
  void trailingLowWindowIsKept() {
    // Two actions to 100, then one long straggler to 1000. The straggler is
    // the classic slow tail and it must not vanish because the sweep ran
    // out of events while the window was open.
    ConcurrencySweep.Spans spans = spans(0, 100, 0, 100, 100, 1_000);

    List<ConcurrencySweep.Window> windows = spans.windowsBelow(2, 0);

    assertThat(windows).hasSize(1);
    assertThat(windows.getFirst().startMicros()).isEqualTo(100);
    assertThat(windows.getFirst().endMicros()).isEqualTo(1_000);
  }

  @Test
  @DisplayName("untimed actions are counted and excluded, never treated as idle")
  void untimedActionsAreCarriedNotAssumed() {
    ConcurrencySweep.Spans spans = spans(0, 100);
    for (int i = 0; i < 40; i++) {
      spans.addUntimed();
    }

    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans);

    assertThat(result.peakActive()).isEqualTo(1);
    assertThat(result.untimedSpans()).isEqualTo(40);
    assertThat(result.sweptSpans()).isEqualTo(1);
    assertThat(result.isPartial()).isTrue();
    assertThat(result.describe()).contains("40 further actions");
  }

  @Test
  @DisplayName("a build whose every span has zero length says so instead of reporting a peak")
  void instantaneousSpansAreExplained() {
    // Bazel 8.4.1 publishes endTime == startTime for every action it
    // reports. A sweep that answered "peak concurrency: 0" here would be
    // describing the timings and appearing to describe the build.
    ConcurrencySweep.Spans spans = spans(1_000, 1_000, 2_000, 2_000, 3_000, 3_000);

    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans);

    assertThat(result.sweptSpans()).isZero();
    assertThat(result.instantaneousSpans()).isEqualTo(3);
    assertThat(result.peakActive()).isZero();
    assertThat(result.isPartial()).isTrue();
    assertThat(result.describe()).contains("same start and").contains("unknown here, not zero");
  }

  @Test
  @DisplayName("nothing at all is unknown, not a quiet build")
  void emptySweep() {
    ConcurrencySweep.Result result = ConcurrencySweep.sweep(new ConcurrencySweep.Spans());

    assertThat(result.peakActive()).isZero();
    assertThat(result.parallelismFactor()).isEmpty();
    assertThat(result.totalSpanMicros()).isEmpty();
    assertThat(result.describe()).isEqualTo("No actions to sweep.");
  }

  @Test
  @DisplayName("the sweep does not depend on the order spans arrived in")
  void orderDoesNotMatter() {
    ConcurrencySweep.Result forward =
        ConcurrencySweep.sweep(spans(0, 100, 50, 150, 60, 70, 200, 260));
    ConcurrencySweep.Result shuffled =
        ConcurrencySweep.sweep(spans(200, 260, 60, 70, 0, 100, 50, 150));

    assertThat(shuffled.peakActive()).isEqualTo(forward.peakActive());
    assertThat(shuffled.peakFirstSeenMicros()).isEqualTo(forward.peakFirstSeenMicros());
    assertThat(shuffled.idleMicros()).isEqualTo(forward.idleMicros());
    assertThat(shuffled.totalSpanMicros()).isEqualTo(forward.totalSpanMicros());
    for (int level = 0; level <= forward.peakActive(); level++) {
      assertThat(shuffled.timeAtLevel(level))
          .as("time at level %d", level)
          .isEqualTo(forward.timeAtLevel(level));
    }
  }

  @Test
  @DisplayName("time at every level sums to the window, so nothing is unaccounted for")
  void levelsAccountForTheWholeWindow() {
    ConcurrencySweep.Result result =
        ConcurrencySweep.sweep(spans(0, 100, 50, 150, 60, 70, 400, 500));

    long total = 0;
    for (int level = 0; level <= result.peakActive(); level++) {
      total += result.timeAtLevel(level);
    }
    assertThat(total).isEqualTo(result.windowMicros());
  }

  @Test
  @DisplayName("microsBelow adds up the levels under a threshold")
  void microsBelowIsCumulative() {
    ConcurrencySweep.Result result =
        ConcurrencySweep.sweep(spans(0, 100, 50, 150, 60, 70, 400, 500));

    assertThat(result.microsBelow(1)).isEqualTo(result.timeAtLevel(0));
    assertThat(result.microsBelow(2)).isEqualTo(result.timeAtLevel(0) + result.timeAtLevel(1));
  }

  @Test
  @DisplayName("a span that ends before it starts is a defect, not a data point")
  void backwardsSpansAreRefused() {
    assertThatThrownBy(() -> new ConcurrencySweep.Spans().add(100, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot end before it starts");
  }

  @Test
  @DisplayName("a threshold below one asks for a window that cannot exist")
  void thresholdIsChecked() {
    assertThatThrownBy(() -> new ConcurrencySweep.Spans().windowsBelow(0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("many spans sweep in one pass and agree with a brute-force count")
  void agreesWithBruteForceAtScale() {
    // The check that matters: a second implementation, written the obvious
    // slow way, over a distribution with ties at both ends.
    int count = 2_000;
    ConcurrencySweep.Spans spans = new ConcurrencySweep.Spans();
    long[] starts = new long[count];
    long[] ends = new long[count];
    for (int i = 0; i < count; i++) {
      starts[i] = (i * 37L) % 1_000;
      ends[i] = starts[i] + 1 + ((i * 13L) % 250);
      spans.add(starts[i], ends[i]);
    }

    ConcurrencySweep.Result result = ConcurrencySweep.sweep(spans);

    int bruteForcePeak = 0;
    for (long instant = 0; instant <= 1_300; instant++) {
      int active = 0;
      for (int i = 0; i < count; i++) {
        if (starts[i] <= instant && instant < ends[i]) {
          active++;
        }
      }
      bruteForcePeak = Math.max(bruteForcePeak, active);
    }
    assertThat(result.peakActive()).isEqualTo(bruteForcePeak);
  }
}
