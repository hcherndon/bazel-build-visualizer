package com.holtherndon.bazelviz.testsupport.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SyntheticActionGeneratorTest {

    private static final long CANONICAL_SEED = 42;

    // ---------------------------------------------------------------- determinism

    @Test
    void sameScaleAndSeedYieldIdenticalActions() {
        SyntheticActionGenerator a = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        SyntheticActionGenerator b = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        for (long index : sampledIndices(SyntheticScale.TIER1.actionCount(), 500)) {
            assertThat(a.actionAt(index)).isEqualTo(b.actionAt(index));
        }
    }

    @Test
    void differentSeedsYieldDifferentActions() {
        SyntheticActionGenerator a = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        SyntheticActionGenerator b = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED + 1);
        boolean anyDiffer = false;
        for (long index : sampledIndices(SyntheticScale.TIER1.actionCount(), 100)) {
            if (!a.actionAt(index).equals(b.actionAt(index))) {
                anyDiffer = true;
                break;
            }
        }
        assertThat(anyDiffer).as("seeds 42 and 43 must not produce the same sequence").isTrue();
    }

    // ---------------------------------------------------------------- bounds

    @Test
    void everyTier1ActionSatisfiesBounds() {
        SyntheticActionGenerator generator = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        long wall = generator.buildWallMicros();
        for (long i = 0; i < SyntheticScale.TIER1.actionCount(); i++) {
            assertActionWithinBounds(generator.actionAt(i), wall);
        }
    }

    @Test
    void sampledTier2AndTier3ActionsSatisfyBounds() {
        for (SyntheticScale scale : List.of(SyntheticScale.TIER2, SyntheticScale.TIER3)) {
            SyntheticActionGenerator generator = new SyntheticActionGenerator(scale, CANONICAL_SEED);
            assertThat(generator.actionCount()).isEqualTo(scale.actionCount());
            long wall = generator.buildWallMicros();
            assertThat(wall).isGreaterThan(0);
            for (long index : sampledIndices(scale.actionCount(), 2_000)) {
                assertActionWithinBounds(generator.actionAt(index), wall);
            }
        }
    }

    @Test
    void actionAtRejectsOutOfRangeIndices() {
        SyntheticActionGenerator generator = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        assertThatThrownBy(() -> generator.actionAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> generator.actionAt(SyntheticScale.TIER1.actionCount()))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    /**
     * Regression: exp(log(400)) evaluates to 399.999... in double arithmetic, so
     * hashes with a near-zero uniform sample used to truncate to a 399 us
     * duration, below the documented 400 us floor. These (seed, index) pairs
     * produced 399 before the clamp; seed 0 index 0 is the exact-zero hash case
     * because mix(0) == 0.
     */
    @Test
    void durationFloorHoldsAtKnownNearZeroHashes() {
        long[][] pairs = {{0, 0}, {1, 16_975}, {2, 44_455}, {4, 77_113}};
        for (long[] pair : pairs) {
            SyntheticActionGenerator generator = new SyntheticActionGenerator(SyntheticScale.TIER1, pair[0]);
            assertThat(generator.actionAt(pair[1]).durationMicros())
                    .as("seed %d index %d", pair[0], pair[1])
                    .isGreaterThanOrEqualTo(400);
        }
    }

    // ---------------------------------------------------------------- distribution

    @Test
    void tier1DistributionIsLogSkewedWithPlausibleRates() {
        SyntheticActionGenerator generator = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        int samples = (int) SyntheticScale.TIER1.actionCount(); // 100k
        long[] durations = new long[samples];
        double durationSum = 0;
        int failures = 0;
        boolean[] mnemonicSeen = new boolean[SyntheticActionGenerator.MNEMONICS.length];
        boolean[] runnerSeen = new boolean[4];
        boolean[] cacheStateSeen = new boolean[3];
        for (int i = 0; i < samples; i++) {
            SyntheticAction action = generator.actionAt(i);
            durations[i] = action.durationMicros();
            durationSum += action.durationMicros();
            if (action.status() == 1) {
                failures++;
            }
            mnemonicSeen[action.mnemonicIndex()] = true;
            runnerSeen[action.runner()] = true;
            cacheStateSeen[action.cacheState()] = true;
        }

        Arrays.sort(durations);
        long median = durations[samples / 2];
        double mean = durationSum / samples;
        assertThat((double) median)
                .as("log-skew: median (%d) must sit well below the mean (%.0f)", median, mean)
                .isLessThan(mean / 10);

        double failureRate = (double) failures / samples;
        assertThat(failureRate).isBetween(0.0005, 0.02);

        assertThat(mnemonicSeen).as("every mnemonic occurs").containsOnly(true);
        assertThat(runnerSeen).as("every runner occurs").containsOnly(true);
        assertThat(cacheStateSeen).as("every cache state occurs").containsOnly(true);
    }

    // ---------------------------------------------------------------- stream vs random access

    @Test
    void streamMatchesRandomAccess() {
        SyntheticActionGenerator generator = new SyntheticActionGenerator(SyntheticScale.TIER1, CANONICAL_SEED);
        List<SyntheticAction> streamed = generator.stream().limit(1000).toList();
        assertThat(streamed).hasSize(1000);
        for (int i = 0; i < streamed.size(); i++) {
            assertThat(streamed.get(i)).isEqualTo(generator.actionAt(i));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Evenly strided indices across [0, count), always including the last index. */
    private static long[] sampledIndices(long count, int samples) {
        long[] indices = new long[samples];
        long stride = Math.max(1, count / samples);
        for (int i = 0; i < samples; i++) {
            indices[i] = Math.min(count - 1, i * stride);
        }
        indices[samples - 1] = count - 1;
        return indices;
    }

    /**
     * Plain comparisons instead of per-field AssertJ calls: this runs for every
     * TIER1 index, and one descriptive failure is all a violation needs.
     */
    private static void assertActionWithinBounds(SyntheticAction action, long wallMicros) {
        long duration = action.durationMicros();
        boolean ok = action.startMicros() >= 0
                && action.startMicros() < action.endMicros()
                && action.endMicros() <= wallMicros
                && duration >= 400
                && duration <= 120_000_000L
                && action.mnemonicIndex() >= 0
                && action.mnemonicIndex() < SyntheticActionGenerator.MNEMONICS.length
                && action.targetIndex() >= 0
                && action.packageIndex() >= 0
                && action.status() >= 0 && action.status() <= 2
                && action.cacheState() >= 0 && action.cacheState() <= 2
                && action.runner() >= 0 && action.runner() <= 3
                && action.inputCount() > 0
                && action.knownInputBytes() > 0
                && action.outputCount() > 0
                && action.knownOutputBytes() > 0;
        if (!ok) {
            fail("action violates bounds (wall=%d): %s", wallMicros, action);
        }
    }
}
