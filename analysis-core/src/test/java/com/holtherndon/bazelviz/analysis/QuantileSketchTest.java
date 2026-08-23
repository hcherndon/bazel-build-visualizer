package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sketch's three promises: the bucket layout is consistent, the answers
 * contain the truth, and merging is order-independent.
 *
 * <p>Plan 24's Phase 8 exit criterion is "formulas have deterministic unit
 * tests", so nothing here uses an unseeded random source, and the one seeded
 * source exists to generate a distribution rather than to sample behaviour.
 */
final class QuantileSketchTest {

    @Test
    @DisplayName("every value lands in a bucket whose bounds contain it")
    void bucketBoundsAreConsistent() {
        // Exhaustive below the exact range, then every power of two and its
        // neighbourhood above it -- the joins between bands are the only place
        // an off-by-one in the shift arithmetic can hide.
        for (long value = 0; value < 4_096; value++) {
            assertBucketContains(value);
        }
        for (int magnitude = 6; magnitude < 62; magnitude++) {
            long base = 1L << magnitude;
            for (long offset = -2; offset <= 2; offset++) {
                if (base + offset >= 0) {
                    assertBucketContains(base + offset);
                }
            }
            assertBucketContains(base + (base / 3));
        }
        assertBucketContains(Long.MAX_VALUE);
    }

    private static void assertBucketContains(long value) {
        int bucket = QuantileSketch.bucketOf(value);
        assertThat(QuantileSketch.bucketLowerBound(bucket))
                .as("lower bound of bucket %d for value %d", bucket, value)
                .isLessThanOrEqualTo(value);
        assertThat(QuantileSketch.bucketUpperBound(bucket))
                .as("upper bound of bucket %d for value %d", bucket, value)
                .isGreaterThanOrEqualTo(value);
    }

    @Test
    @DisplayName("bucket numbering is monotonic, so a larger value never sorts earlier")
    void bucketsAreMonotonic() {
        int previous = -1;
        for (long value = 0; value < 100_000; value++) {
            int bucket = QuantileSketch.bucketOf(value);
            assertThat(bucket).as("bucket for %d", value).isGreaterThanOrEqualTo(previous);
            previous = bucket;
        }
    }

    @Test
    @DisplayName("small values are recorded exactly, so short durations are not approximated")
    void smallValuesAreExact() {
        QuantileSketch sketch = new QuantileSketch();
        for (long value = 0; value < QuantileSketch.SUB_BUCKETS; value++) {
            sketch.add(value);
        }

        QuantileSketch.Distribution distribution = sketch.snapshot();
        for (double quantile : new double[] {0, 0.25, 0.5, 0.9, 1}) {
            assertThat(distribution.quantile(quantile).orElseThrow().isExact())
                    .as("quantile %s", quantile)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the reported interval always contains the true quantile")
    void quantilesBracketTheTruth() {
        List<Long> values = new ArrayList<>();
        Random random = new Random(20260822L);
        QuantileSketch sketch = new QuantileSketch();
        for (int i = 0; i < 10_000; i++) {
            long value = (long) Math.abs(random.nextGaussian() * 250_000);
            values.add(value);
            sketch.add(value);
        }
        values.sort(null);
        QuantileSketch.Distribution distribution = sketch.snapshot();

        for (double quantile : new double[] {0, 0.5, 0.9, 0.95, 0.99, 1}) {
            int rank = (int) Math.max(1, Math.ceil(quantile * values.size()));
            long truth = values.get(rank - 1);
            QuantileSketch.Bounds bounds = distribution.quantile(quantile).orElseThrow();
            assertThat(truth)
                    .as("p%s should lie inside %s", quantile * 100, bounds)
                    .isBetween(bounds.low(), bounds.high());
        }
    }

    @Test
    @DisplayName("a reported interval is never wider than one part in SUB_BUCKETS")
    void quantileIntervalsAreTight() {
        QuantileSketch sketch = new QuantileSketch();
        for (long value = 1_000; value < 2_000_000; value += 977) {
            sketch.add(value);
        }

        QuantileSketch.Bounds bounds = sketch.snapshot().quantile(0.99).orElseThrow();
        long width = bounds.high() - bounds.low();
        assertThat(width)
                .as("interval %s is wider than 1/%d of its own value", bounds,
                        QuantileSketch.SUB_BUCKETS)
                .isLessThanOrEqualTo(bounds.low() / QuantileSketch.SUB_BUCKETS + 1);
    }

    @Test
    @DisplayName("count, sum, minimum and maximum are exact, not approximated")
    void extremesAreExact() {
        QuantileSketch sketch = new QuantileSketch();
        sketch.add(7);
        sketch.add(1_234_567);
        sketch.add(89);

        QuantileSketch.Distribution distribution = sketch.snapshot();
        assertThat(distribution.count()).isEqualTo(3);
        assertThat(distribution.sum()).hasValue(7 + 1_234_567 + 89);
        assertThat(distribution.min()).hasValue(7);
        assertThat(distribution.max()).hasValue(1_234_567);
        assertThat(distribution.mean()).hasValue((7 + 1_234_567 + 89) / 3.0);
        // p100 is the maximum itself, not the top of the bucket it fell in.
        assertThat(distribution.quantile(1).orElseThrow())
                .isEqualTo(new QuantileSketch.Bounds(1_234_567, 1_234_567));
        assertThat(distribution.quantile(0).orElseThrow())
                .isEqualTo(new QuantileSketch.Bounds(7, 7));
    }

    @Test
    @DisplayName("merging is order-independent, which is what makes a live aggregate possible")
    void mergingIsCommutative() {
        QuantileSketch first = new QuantileSketch();
        QuantileSketch second = new QuantileSketch();
        for (int i = 0; i < 500; i++) {
            first.add(i * 31L + 1);
            second.add(i * 17L + 7);
        }

        QuantileSketch forward = new QuantileSketch();
        forward.merge(first);
        forward.merge(second);
        QuantileSketch backward = new QuantileSketch();
        backward.merge(second);
        backward.merge(first);

        assertThat(forward.snapshot()).isEqualTo(backward.snapshot());
    }

    @Test
    @DisplayName("a merged sketch equals one built from every value in any order")
    void mergingMatchesDirectAccumulation() {
        long[] values = new long[2_000];
        Random random = new Random(19700101L);
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.abs(random.nextLong() % 5_000_000);
        }

        QuantileSketch whole = new QuantileSketch();
        for (long value : values) {
            whole.add(value);
        }
        QuantileSketch partA = new QuantileSketch();
        QuantileSketch partB = new QuantileSketch();
        QuantileSketch partC = new QuantileSketch();
        for (int i = 0; i < values.length; i++) {
            QuantileSketch part = switch (i % 3) {
                case 0 -> partA;
                case 1 -> partB;
                default -> partC;
            };
            part.add(values[i]);
        }
        QuantileSketch merged = new QuantileSketch();
        merged.merge(partC);
        merged.merge(partA);
        merged.merge(partB);

        assertThat(merged.snapshot()).isEqualTo(whole.snapshot());
    }

    @Test
    @DisplayName("repeated occurrences are the same as repeated adds")
    void occurrenceCountsMatchRepeatedAdds() {
        QuantileSketch repeated = new QuantileSketch();
        repeated.add(4_096, 1_000);
        QuantileSketch individually = new QuantileSketch();
        for (int i = 0; i < 1_000; i++) {
            individually.add(4_096);
        }

        assertThat(repeated.snapshot()).isEqualTo(individually.snapshot());
    }

    @Test
    @DisplayName("a distribution of nothing has no minimum, no mean and no sum")
    void emptyIsUnknownNotZero() {
        QuantileSketch.Distribution empty = new QuantileSketch().snapshot();

        // Rule 11: a group nobody timed must not read as a group that took no
        // time. Every statistic is absent rather than zero, and the count --
        // which is genuinely zero -- is the one number that says why.
        assertThat(empty.count()).isZero();
        assertThat(empty.min()).isEmpty();
        assertThat(empty.max()).isEmpty();
        assertThat(empty.sum()).isEmpty();
        assertThat(empty.mean()).isEmpty();
        assertThat(empty.quantile(0.5)).isEmpty();
        assertThat(empty).isEqualTo(QuantileSketch.Distribution.empty());
    }

    @Test
    @DisplayName("zero is a measurement and is kept as one")
    void zeroIsAValue() {
        QuantileSketch sketch = new QuantileSketch();
        sketch.add(0);
        sketch.add(0);
        sketch.add(10);

        QuantileSketch.Distribution distribution = sketch.snapshot();
        assertThat(distribution.count()).isEqualTo(3);
        assertThat(distribution.min()).hasValue(0);
        assertThat(distribution.sum()).hasValue(10);
        assertThat(distribution.quantile(0.5).orElseThrow())
                .isEqualTo(new QuantileSketch.Bounds(0, 0));
    }

    @Test
    @DisplayName("a negative measurement is a defect and is refused, not bucketed")
    void negativesAreRefused() {
        assertThatThrownBy(() -> new QuantileSketch().add(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("a quantile outside zero to one is a caller error")
    void quantileRangeIsChecked() {
        QuantileSketch.Distribution distribution = new QuantileSketch().snapshot();

        assertThatThrownBy(() -> distribution.quantile(1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> distribution.quantile(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sum that would overflow is reported as unavailable, not as a wrapped number")
    void overflowingSumsAreUnavailable() {
        QuantileSketch sketch = new QuantileSketch();
        sketch.add(Long.MAX_VALUE);
        sketch.add(Long.MAX_VALUE);

        QuantileSketch.Distribution distribution = sketch.snapshot();
        assertThat(distribution.sum()).isEmpty();
        assertThat(distribution.mean()).isEmpty();
        // Everything that did not overflow survives.
        assertThat(distribution.count()).isEqualTo(2);
        assertThat(distribution.max()).hasValue(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("the bucket array is not reachable, so a distribution cannot be edited")
    void bucketsAreNotHandedOut() {
        for (java.lang.reflect.Method method
                : QuantileSketch.Distribution.class.getMethods()) {
            if (method.getDeclaringClass() != QuantileSketch.Distribution.class) {
                continue;
            }
            assertThat(method.getReturnType())
                    .as("%s", method.getName())
                    .isNotEqualTo(long[].class);
        }
    }
}
