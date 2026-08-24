package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a colour is allowed to claim.
 *
 * <p>The cache and runner modes describe facts most sessions do not have. A
 * mode painting those spans as misses or as local would put the most
 * interesting claim on the screen on no evidence, which is the failure these
 * tests exist to prevent.
 */
final class TimelineColoursTest {

    private static TimelineLodIndex indexOf(int... flagsPerSpan) {
        return TimelineLodIndex.build(new SpanSource() {
            @Override
            public long spanCount() {
                return flagsPerSpan.length;
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                for (int flags : flagsPerSpan) {
                    consumer.accept(0, 100, 1, flags, SpanSource.BYTES_UNKNOWN);
                }
            }
        }, 0, 1_000_000);
    }

    private static TimelineModel modelOf(
            TimelineLodIndex index, long cacheKnown, long runnersKnown) {
        return new TimelineModel(
                index, List.of(), List.of(), Map.of(), 0, cacheKnown, runnersKnown);
    }

    @Test
    @DisplayName("a bin nobody reported a cache result for is unknown, not a miss")
    void unknownCacheIsGrey() {
        TimelineLodIndex index = indexOf(0, 0, 0);

        assertThat(TimelineColours.forBin(index, TimelineColours.Mode.CACHE, 0, 0))
                .isEqualTo(TimelineColours.UNKNOWN);
    }

    @Test
    @DisplayName("a bin of hits is a hit, a bin of misses is a miss")
    void cacheMajorityWins() {
        TimelineLodIndex hits = indexOf(
                SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT,
                SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT,
                SpanSource.FLAG_CACHE_KNOWN);
        assertThat(TimelineColours.forBin(hits, TimelineColours.Mode.CACHE, 0, 0))
                .isEqualTo(TimelineColours.CACHE_HIT);

        TimelineLodIndex misses = indexOf(
                SpanSource.FLAG_CACHE_KNOWN, SpanSource.FLAG_CACHE_KNOWN,
                SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT);
        assertThat(TimelineColours.forBin(misses, TimelineColours.Mode.CACHE, 0, 0))
                .isEqualTo(TimelineColours.CACHE_MISS);
    }

    @Test
    @DisplayName("an evenly split bin is unknown rather than arbitrary")
    void tiesAreNotResolvedByLuck() {
        TimelineLodIndex tied = indexOf(
                SpanSource.FLAG_CACHE_KNOWN | SpanSource.FLAG_CACHE_HIT,
                SpanSource.FLAG_CACHE_KNOWN);

        // Colouring it for whichever was counted first would be a coin toss
        // presented as a measurement.
        assertThat(TimelineColours.forBin(tied, TimelineColours.Mode.CACHE, 0, 0))
                .isEqualTo(TimelineColours.UNKNOWN);
    }

    @Test
    @DisplayName("failure outranks every other colour, in every mode")
    void failureAlwaysShows() {
        TimelineLodIndex failed = indexOf(
                SpanSource.FLAG_FAILED | SpanSource.FLAG_CACHE_KNOWN
                        | SpanSource.FLAG_CACHE_HIT);

        for (TimelineColours.Mode mode : TimelineColours.Mode.values()) {
            assertThat(TimelineColours.forBin(failed, mode, 0, 0))
                    .as("%s", mode)
                    .isEqualTo(TimelineColours.FAILED);
        }
    }

    @Test
    @DisplayName("completed success is green, failure red, and blue belongs to in-flight alone")
    void outcomeColoursAreDistinct() {
        // A completed action segment is green on success and red on failure.
        assertThat(TimelineColours.forSpan(TimelineColours.Mode.OUTCOME, 0))
                .isEqualTo(TimelineColours.SUCCESS);
        assertThat(TimelineColours.forSpan(
                        TimelineColours.Mode.OUTCOME, SpanSource.FLAG_FAILED))
                .isEqualTo(TimelineColours.FAILED);
        // Blue is reserved for the live band's in-flight targets: nothing the
        // outcome mode paints may wear it, or a finished action would read as
        // still running.
        assertThat(TimelineColours.forSpan(TimelineColours.Mode.OUTCOME, 0))
                .isNotEqualTo(TimelineColours.IN_FLIGHT);
        assertThat(TimelineColours.IN_FLIGHT)
                .isNotEqualTo(TimelineColours.SUCCESS)
                .isNotEqualTo(TimelineColours.FAILED)
                .isNotEqualTo(TimelineColours.UNKNOWN);
        // The bins agree with the spans.
        assertThat(TimelineColours.forBin(
                        indexOf(0), TimelineColours.Mode.OUTCOME, 0, 0))
                .isEqualTo(TimelineColours.SUCCESS);
    }

    @Test
    @DisplayName("a span with no cache flag is grey, not a miss")
    void unknownSpansAreGrey() {
        assertThat(TimelineColours.forSpan(TimelineColours.Mode.CACHE, 0))
                .isEqualTo(TimelineColours.UNKNOWN);
        assertThat(TimelineColours.forSpan(TimelineColours.Mode.RUNNER, 0))
                .isEqualTo(TimelineColours.UNKNOWN);
    }

    @Test
    @DisplayName("a span said to be a miss is a miss")
    void knownSpansAreColoured() {
        assertThat(TimelineColours.forSpan(
                        TimelineColours.Mode.CACHE, SpanSource.FLAG_CACHE_KNOWN))
                .isEqualTo(TimelineColours.CACHE_MISS);
        assertThat(TimelineColours.forSpan(TimelineColours.Mode.RUNNER,
                        SpanSource.FLAG_RUNNER_KNOWN | SpanSource.FLAG_REMOTE))
                .isEqualTo(TimelineColours.REMOTE);
    }

    @Test
    @DisplayName("a mode with nothing to say is not offered, and explains itself")
    void unavailableModesSayWhy() {
        TimelineModel bare = modelOf(indexOf(0), 0, 0);

        assertThat(TimelineColours.Mode.OUTCOME.isAvailable(bare)).isTrue();
        assertThat(TimelineColours.Mode.CACHE.isAvailable(bare)).isFalse();
        assertThat(TimelineColours.Mode.RUNNER.isAvailable(bare)).isFalse();
        assertThat(TimelineColours.Mode.CACHE.unavailableReason(bare))
                .hasValueSatisfying(why -> assertThat(why).contains("no execution log"));
    }

    @Test
    @DisplayName("a session with an execution log offers every mode")
    void enrichedSessionsOfferEverything() {
        TimelineModel enriched = modelOf(indexOf(SpanSource.FLAG_CACHE_KNOWN), 5, 5);

        for (TimelineColours.Mode mode : TimelineColours.Mode.values()) {
            assertThat(mode.isAvailable(enriched)).as("%s", mode).isTrue();
            assertThat(mode.unavailableReason(enriched)).isEmpty();
        }
    }

    @Test
    @DisplayName("the coverage note names what is missing from the timeline")
    void coverageIsStated() {
        TimelineModel partial = new TimelineModel(
                indexOf(0), List.of(), List.of(), Map.of(), 11_960, 0, 0);

        // A timeline showing 40 of a build's 12,000 actions is not wrong; one
        // that does not say so is.
        assertThat(partial.coverageNote())
                .hasValueSatisfying(note -> assertThat(note)
                        .contains("11960")
                        .contains("no timestamps"));
        assertThat(modelOf(indexOf(0), 0, 0).coverageNote()).isEmpty();
    }
}
