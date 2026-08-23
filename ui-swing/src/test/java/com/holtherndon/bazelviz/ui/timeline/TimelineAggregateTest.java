package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a timeline bin knows, and what it refuses to claim.
 *
 * <p>Plan 14.3 lists what each bin stores. The interesting half is the
 * distinction between a zero and an unknown: a session with no execution log
 * knows nothing about caching, and a bin that reported that as "no cache hits"
 * would colour a whole build as cache misses.
 */
final class TimelineAggregateTest {

    /** One span, described in full, for building tiny sources by hand. */
    private record Span(long start, long end, int category, int flags, long bytes) {}

    private static SpanSource sourceOf(List<Span> spans) {
        return new SpanSource() {
            @Override
            public long spanCount() {
                return spans.size();
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                for (Span span : spans) {
                    consumer.accept(span.start(), span.end(), span.category(),
                            span.flags(), span.bytes());
                }
            }
        };
    }

    private static TimelineLodIndex indexOf(Span... spans) {
        return TimelineLodIndex.build(sourceOf(List.of(spans)), 0, 1_000_000);
    }

    @Test
    @DisplayName("a cache result nobody reported is unknown, not a miss")
    void unknownCacheIsNotAMiss() {
        TimelineLodIndex index = indexOf(
                new Span(0, 1_000, 1, 0, SpanSource.BYTES_UNKNOWN),
                new Span(0, 1_000, 1, 0, SpanSource.BYTES_UNKNOWN));

        // Every span in a session with no execution log looks like this. If
        // the index reported two misses, a cache-coloured timeline would paint
        // the whole build red on no evidence.
        assertThat(index.cacheKnownCount(0, 0)).isZero();
        assertThat(index.cacheHitCount(0, 0)).isZero();
        assertThat(index.cacheMissCount(0, 0)).isZero();
        assertThat(index.startCount(0, 0)).isEqualTo(2);
    }

    @Test
    @DisplayName("hits and misses are counted only where something reported them")
    void cacheResultsAreCounted() {
        TimelineLodIndex index = indexOf(
                new Span(0, 100, 1, SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT, 10),
                new Span(0, 100, 1, SpanSource.FLAG_CACHE_KNOWN, 20),
                new Span(0, 100, 1, 0, SpanSource.BYTES_UNKNOWN));

        assertThat(index.cacheKnownCount(0, 0)).isEqualTo(2);
        assertThat(index.cacheHitCount(0, 0)).isEqualTo(1);
        // Derived, so it cannot disagree with the two it comes from.
        assertThat(index.cacheMissCount(0, 0)).isEqualTo(1);
        assertThat(index.startCount(0, 0)).isEqualTo(3);
    }

    @Test
    @DisplayName("local and remote are counted only where the runner is known")
    void runnersAreCounted() {
        TimelineLodIndex index = indexOf(
                new Span(0, 100, 1, SpanSource.FLAG_RUNNER_KNOWN | SpanSource.FLAG_REMOTE, 0),
                new Span(0, 100, 1, SpanSource.FLAG_RUNNER_KNOWN, 0),
                new Span(0, 100, 1, 0, SpanSource.BYTES_UNKNOWN));

        assertThat(index.runnerKnownCount(0, 0)).isEqualTo(2);
        assertThat(index.remoteCount(0, 0)).isEqualTo(1);
        assertThat(index.localCount(0, 0)).isEqualTo(1);
    }

    @Test
    @DisplayName("bytes nobody reported add nothing, rather than adding zero")
    void unknownBytesAreNotZero() {
        TimelineLodIndex index = indexOf(
                new Span(0, 100, 1, 0, 4_096),
                new Span(0, 100, 1, 0, SpanSource.BYTES_UNKNOWN));

        // 4096, and a lower bound. A span that reported nothing contributes
        // nothing rather than a guess, so the total is "at least" and never
        // "exactly" -- which is what the accessor's contract says.
        assertThat(index.byteTotal(0, 0)).isEqualTo(4_096);
    }

    @Test
    @DisplayName("a bin of one category names it")
    void uniformBinsNameTheirCategory() {
        TimelineLodIndex index = indexOf(
                new Span(0, 100, 7, 0, 0),
                new Span(0, 100, 7, 0, 0),
                new Span(0, 100, 7, 0, 0));

        assertThat(index.uniformCategory(0, 0)).hasValue(7);
    }

    @Test
    @DisplayName("a bin of mixed categories names none, even when one is commonest")
    void mixedBinsNameNothing() {
        TimelineLodIndex index = indexOf(
                new Span(0, 100, 7, 0, 0),
                new Span(0, 100, 7, 0, 0),
                new Span(0, 100, 7, 0, 0),
                new Span(0, 100, 9, 0, 0));

        // 7 holds a strict majority here, and the vote cannot prove it in one
        // pass. Reporting it would claim a ranking nothing computed, so the
        // answer is that the bin is mixed.
        assertThat(index.uniformCategory(0, 0)).isEmpty();
    }

    @Test
    @DisplayName("an empty bin names no category")
    void emptyBinsNameNothing() {
        TimelineLodIndex index = indexOf(new Span(0, 100, 3, 0, 0));

        assertThat(index.uniformCategory(0, 0)).hasValue(3);
        assertThat(index.uniformCategory(0, 500)).isEmpty();
    }

    @Test
    @DisplayName("the aggregates survive into the coarser levels")
    void coarserLevelsAggregateToo() {
        // A ten-second wall, so the pyramid really has more than one level: at
        // 1 ms the level-0 bins outnumber the 2048 cap and coarser levels are
        // built. A one-second wall fits in a single level and would have made
        // this assert nothing.
        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(List.of(
                new Span(0, 100, 4, SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT, 10),
                new Span(5_000, 5_100, 4,
                        SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT, 20))),
                0, 10_000_000);

        int top = index.levelCount() - 1;
        assertThat(index.levelCount()).isGreaterThan(1);
        int bin = index.binIndexOf(top, 0);
        assertThat(index.cacheHitCount(top, bin)).isEqualTo(2);
        assertThat(index.byteTotal(top, bin)).isEqualTo(30);
        assertThat(index.uniformCategory(top, bin)).hasValue(4);
    }

    @Test
    @DisplayName("a failure is counted at every level")
    void failuresAreCounted() {
        TimelineLodIndex index = indexOf(
                new Span(0, 100, 1, SpanSource.FLAG_FAILED, 0),
                new Span(0, 100, 1, 0, 0));

        assertThat(index.failureCount(0, 0)).isEqualTo(1);
        assertThat(index.startCount(0, 0)).isEqualTo(2);
    }

    @Test
    @DisplayName("cache counts are attributed to the bin a span starts in, not spread")
    void cacheCountsAreStartAttributed() {
        // One span covering a thousand level-0 bins.
        TimelineLodIndex index = indexOf(new Span(0, 1_000_000, 1,
                SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT, 0));

        assertThat(index.cacheHitCount(0, 0)).isEqualTo(1);
        // Spreading it would make one long cached action outweigh a hundred
        // short ones in every bin it touched.
        assertThat(index.cacheHitCount(0, 500)).isZero();
        // Its duration is still spread, because that is what overlap means.
        assertThat(index.overlapMicros(0, 500)).isPositive();
    }

    @Test
    @DisplayName("the whole feed is counted, including spans outside the wall")
    void everySpanIsAccountedFor() {
        List<Span> spans = new ArrayList<>();
        spans.add(new Span(0, 100, 1, 0, 0));
        spans.add(new Span(2_000_000, 2_000_100, 1, 0, 0));

        TimelineLodIndex index = TimelineLodIndex.build(sourceOf(spans), 0, 1_000_000);

        // The second is outside the wall and contributes to no bin. It is still
        // counted, so "spans" and "spans drawn" cannot be silently conflated.
        assertThat(index.totalSpanCount()).isEqualTo(2);
    }
}
