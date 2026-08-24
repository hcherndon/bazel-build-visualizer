package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How overlapping spans share a lane's vertical space.
 *
 * <p>The rules under test: stack top-to-bottom by start time, reuse a sub-row
 * once its occupant has ended, never exceed {@link SpanStacking#MAX_SUB_ROWS},
 * and count — exactly — every span that had to pile into the last sub-row,
 * because a pile that is not counted is a truncation that is not stated.
 */
final class SpanStackingTest {

    private static SpanWindow windowOf(long[][] spans) {
        SpanWindow.Builder builder = SpanWindow.builder(0, 1_000_000);
        for (int i = 0; i < spans.length; i++) {
            builder.add(spans[i][0], spans[i][1], 0, i + 1, "lane");
        }
        return builder.build();
    }

    @Test
    @DisplayName("overlapping spans stack top-to-bottom by start time")
    void overlapStacksDownward() {
        SpanWindow window = windowOf(new long[][] {
                {0, 100}, {10, 90}, {20, 80}});
        SpanStacking stacking = SpanStacking.of(window);

        assertThat(stacking.subRow(0)).isEqualTo(0);
        assertThat(stacking.subRow(1)).isEqualTo(1);
        assertThat(stacking.subRow(2)).isEqualTo(2);
        assertThat(stacking.depthOf("lane")).isEqualTo(3);
        assertThat(stacking.overflowCount()).isZero();
    }

    @Test
    @DisplayName("a sub-row is reused once its occupant has ended")
    void subRowsAreReused() {
        SpanWindow window = windowOf(new long[][] {
                {0, 50}, {10, 40}, {60, 100}, {70, 90}});
        SpanStacking stacking = SpanStacking.of(window);

        // The third span starts after the first ended, so it takes the top
        // sub-row back rather than stacking below for no reason.
        assertThat(stacking.subRow(2)).isEqualTo(0);
        assertThat(stacking.subRow(3)).isEqualTo(1);
        assertThat(stacking.depthOf("lane")).isEqualTo(2);
    }

    @Test
    @DisplayName("stacking is a fact about the spans, not about their order in the window")
    void insertionOrderDoesNotMatter() {
        SpanWindow forward = windowOf(new long[][] {{0, 100}, {10, 90}, {200, 300}});
        SpanWindow.Builder shuffled = SpanWindow.builder(0, 1_000_000);
        // The same three spans, delivered in a different order with the same ids.
        shuffled.add(200, 300, 0, 3, "lane");
        shuffled.add(0, 100, 0, 1, "lane");
        shuffled.add(10, 90, 0, 2, "lane");

        SpanStacking a = SpanStacking.of(forward);
        SpanStacking b = SpanStacking.of(shuffled.build());

        // Span 1 (starts at 0) is sub-row 0 in both; span 2 stacks below it in
        // both; span 3 reuses the top in both — regardless of fetch order.
        assertThat(a.subRow(0)).isEqualTo(b.subRow(1));
        assertThat(a.subRow(1)).isEqualTo(b.subRow(2));
        assertThat(a.subRow(2)).isEqualTo(b.subRow(0));
    }

    @Test
    @DisplayName("beyond the budget, spans pile into the last sub-row and are counted exactly")
    void overflowIsBoundedAndCounted() {
        int spanCount = SpanStacking.MAX_SUB_ROWS + 4;
        long[][] spans = new long[spanCount][];
        for (int i = 0; i < spanCount; i++) {
            spans[i] = new long[] {i, 10_000}; // all overlap all
        }
        SpanStacking stacking = SpanStacking.of(windowOf(spans));

        for (int i = 0; i < spanCount; i++) {
            assertThat(stacking.subRow(i))
                    .as("span %d stays inside the budget", i)
                    .isLessThan(SpanStacking.MAX_SUB_ROWS);
        }
        // The first MAX_SUB_ROWS each get a sub-row; the remaining four share
        // the last one and every one of them is counted — never dropped.
        assertThat(stacking.overflowCount()).isEqualTo(4);
        assertThat(stacking.depthOf("lane")).isEqualTo(SpanStacking.MAX_SUB_ROWS);
    }

    @Test
    @DisplayName("lanes stack independently of each other")
    void lanesAreIndependent() {
        SpanWindow.Builder builder = SpanWindow.builder(0, 1_000_000);
        builder.add(0, 100, 0, 1, "a");
        builder.add(10, 90, 0, 2, "b"); // overlaps span 1 in time, but elsewhere
        SpanStacking stacking = SpanStacking.of(builder.build());

        assertThat(stacking.subRow(0)).isEqualTo(0);
        assertThat(stacking.subRow(1)).isEqualTo(0);
        assertThat(stacking.depthOf("a")).isEqualTo(1);
        assertThat(stacking.depthOf("b")).isEqualTo(1);
    }
}
