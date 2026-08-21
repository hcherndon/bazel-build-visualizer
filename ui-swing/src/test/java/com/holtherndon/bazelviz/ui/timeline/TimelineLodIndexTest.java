package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class TimelineLodIndexTest {

    private record Span(long start, long end, boolean failed) {}

    private static SpanSource sourceOf(Span... spans) {
        return new SpanSource() {
            @Override
            public long spanCount() {
                return spans.length;
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                for (Span s : spans) {
                    consumer.accept(s.start, s.end, 0, s.failed);
                }
            }
        };
    }

    @Test
    void levelStructureGrowsByPowersOfFourUntilTopLevelFits() {
        // 5 s wall: level 0 (1 ms) has 5000 bins > 2048, level 1 (4 ms) has 1250.
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(), 0, 5_000_000);
        assertThat(index.levelCount()).isEqualTo(2);
        assertThat(index.binWidthMicros(0)).isEqualTo(1_000);
        assertThat(index.binWidthMicros(1)).isEqualTo(4_000);
        assertThat(index.binCount(0)).isEqualTo(5_000);
        assertThat(index.binCount(1)).isEqualTo(1_250);
    }

    @Test
    void tinyWallGetsSingleLevelWithCeilBinCount() {
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(), 0, 2_500);
        assertThat(index.levelCount()).isEqualTo(1);
        assertThat(index.binCount(0)).isEqualTo(3); // ceil(2500 / 1000)
    }

    @Test
    void rejectsEmptyWall() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TimelineLodIndex.build(sourceOf(), 10, 10));
    }

    @Test
    void accountsOverlapStartsActiveAndFailuresAcrossBinBoundaries() {
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(
                new Span(500, 2_500, false),    // A: crosses bins 0,1,2
                new Span(1_000, 1_400, true),   // B: inside bin 1, starts on a boundary
                new Span(3_999, 4_001, false)), // C: 1us on each side of the 3/4 boundary
                0, 5_000_000);

        assertThat(index.totalSpanCount()).isEqualTo(3);

        // Level 0, 1 ms bins. Starts land in the bin containing startMicros.
        assertThat(index.startCount(0, 0)).isEqualTo(1);
        assertThat(index.startCount(0, 1)).isEqualTo(1);
        assertThat(index.startCount(0, 2)).isZero();
        assertThat(index.startCount(0, 3)).isEqualTo(1);
        assertThat(index.startCount(0, 4)).isZero();

        // Overlap splits exactly at boundaries; end is exclusive.
        assertThat(index.overlapMicros(0, 0)).isEqualTo(500);   // A: [500, 1000)
        assertThat(index.overlapMicros(0, 1)).isEqualTo(1_400); // A 1000 + B 400
        assertThat(index.overlapMicros(0, 2)).isEqualTo(500);   // A: [2000, 2500)
        assertThat(index.overlapMicros(0, 3)).isEqualTo(1);     // C: [3999, 4000)
        assertThat(index.overlapMicros(0, 4)).isEqualTo(1);     // C: [4000, 4001)
        assertThat(index.overlapMicros(0, 5)).isZero();

        // Active = spans intersecting the bin; C intersects both boundary bins.
        assertThat(index.activeCount(0, 0)).isEqualTo(1);
        assertThat(index.activeCount(0, 1)).isEqualTo(2);
        assertThat(index.activeCount(0, 2)).isEqualTo(1);
        assertThat(index.activeCount(0, 3)).isEqualTo(1);
        assertThat(index.activeCount(0, 4)).isEqualTo(1);
        assertThat(index.activeCount(0, 5)).isZero();

        // Only B failed, and it only intersects bin 1.
        assertThat(index.failureCount(0, 0)).isZero();
        assertThat(index.failureCount(0, 1)).isEqualTo(1);
        assertThat(index.failureCount(0, 2)).isZero();

        // Level 1, 4 ms bins: A and B fall in bin 0; C straddles bins 0 and 1.
        assertThat(index.startCount(1, 0)).isEqualTo(3);
        assertThat(index.overlapMicros(1, 0)).isEqualTo(2_000 + 400 + 1);
        assertThat(index.overlapMicros(1, 1)).isEqualTo(1);
        assertThat(index.activeCount(1, 0)).isEqualTo(3);
        assertThat(index.activeCount(1, 1)).isEqualTo(1);
        assertThat(index.failureCount(1, 0)).isEqualTo(1);
        assertThat(index.failureCount(1, 1)).isZero();

        assertThat(index.maxOverlapMicros(0)).isEqualTo(1_400);
        assertThat(index.maxActiveCount(0)).isEqualTo(2);
        assertThat(index.maxOverlapMicros(1)).isEqualTo(2_401);
        assertThat(index.maxActiveCount(1)).isEqualTo(3);
    }

    @Test
    void spanFillingExactlyOneBinTouchesNoNeighbor() {
        TimelineLodIndex index =
                TimelineLodIndex.build(sourceOf(new Span(1_000, 2_000, false)), 0, 5_000_000);
        assertThat(index.activeCount(0, 0)).isZero();
        assertThat(index.activeCount(0, 1)).isEqualTo(1);
        assertThat(index.activeCount(0, 2)).isZero();
        assertThat(index.overlapMicros(0, 1)).isEqualTo(1_000);
    }

    @Test
    void zeroLengthSpanCountsAsStartAndActiveWithNoOverlap() {
        TimelineLodIndex index =
                TimelineLodIndex.build(sourceOf(new Span(1_500, 1_500, false)), 0, 5_000_000);
        assertThat(index.startCount(0, 1)).isEqualTo(1);
        assertThat(index.activeCount(0, 1)).isEqualTo(1);
        assertThat(index.overlapMicros(0, 1)).isZero();
    }

    @Test
    void spansOutsideTheWallAreClippedOrIgnored() {
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(
                new Span(-500, 1_500, false),          // clipped to [0, 1500)
                new Span(4_999_000, 6_000_000, false), // clipped to the last bin
                new Span(-10, -5, true),               // entirely before: ignored
                new Span(9_000_000, 9_000_500, true)), // entirely after: ignored
                0, 5_000_000);

        assertThat(index.totalSpanCount()).isEqualTo(4); // fed count, not accepted count
        assertThat(index.startCount(0, 0)).isEqualTo(1);
        assertThat(index.overlapMicros(0, 0)).isEqualTo(1_000);
        assertThat(index.overlapMicros(0, 1)).isEqualTo(500);
        assertThat(index.overlapMicros(0, 4_999)).isEqualTo(1_000);
        assertThat(index.activeCount(0, 4_999)).isEqualTo(1);
        // The ignored failed spans left no trace on any level.
        assertThat(index.maxActiveCount(1)).isEqualTo(1);
        assertThat(index.failureCount(0, 0)).isZero();
    }

    @Test
    void levelSelectionPicksFinestLevelWideEnoughOnScreen() {
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(), 0, 5_000_000);
        // Bins must be >= 2 px: at 0.01 px/us a 1 ms bin is 10 px -> level 0.
        assertThat(index.levelForScale(0.01)).isZero();
        // At 0.001 px/us a 1 ms bin is 1 px but a 4 ms bin is 4 px -> level 1.
        assertThat(index.levelForScale(0.001)).isEqualTo(1);
        // Deep zoom-out: nothing satisfies the target, coarsest level wins.
        assertThat(index.levelForScale(0.000_01)).isEqualTo(1);
        // Exactly on the threshold: 1 ms * 0.002 = 2 px.
        assertThat(index.levelForScale(0.002)).isZero();
    }

    @Test
    void binIndexOfClampsOutOfRangeTimes() {
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(), 0, 5_000_000);
        assertThat(index.binIndexOf(0, -999_999)).isZero();
        assertThat(index.binIndexOf(0, 0)).isZero();
        assertThat(index.binIndexOf(0, 1_000)).isEqualTo(1);
        assertThat(index.binIndexOf(0, 4_999_999)).isEqualTo(4_999);
        assertThat(index.binIndexOf(0, 99_999_999)).isEqualTo(4_999);
        assertThat(index.binStartMicros(0, 4_999)).isEqualTo(4_999_000);
    }

    @Test
    void wallOffsetShiftsBinning() {
        // Wall starting at a non-zero instant: bins are relative to wallStart.
        TimelineLodIndex index =
                TimelineLodIndex.build(sourceOf(new Span(10_500, 11_500, false)), 10_000, 5_010_000);
        assertThat(index.binStartMicros(0, 0)).isEqualTo(10_000);
        assertThat(index.overlapMicros(0, 0)).isEqualTo(500);
        assertThat(index.overlapMicros(0, 1)).isEqualTo(500);
        assertThat(index.startCount(0, 0)).isEqualTo(1);
    }
}
