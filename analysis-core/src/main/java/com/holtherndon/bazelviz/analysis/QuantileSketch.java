package com.holtherndon.bazelviz.analysis;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * A mergeable, deterministic summary of a distribution of non-negative
 * measurements: durations in microseconds, sizes in bytes, counts.
 *
 * <h2>Why a sketch and not the numbers</h2>
 *
 * <p>Plan 15.3 asks for count, sum, minimum, maximum, mean, median, p90, p95
 * and p99 over every aggregate dimension — mnemonic, target, package, rule
 * class, configuration, execution platform, runner, cache state, status, test
 * suite, time window. A Tier 3 session has five million actions, and holding
 * every duration so that eleven groupings can each be sorted is both the
 * memory the plan forbids (ADR-007) and work that has to be redone whenever a
 * filter changes.
 *
 * <p>The same section says how to avoid that: "mergeable quantile sketches for
 * global live aggregates and exact SQL calculations for bounded selected result
 * sets". This is that sketch. It is a fixed-layout histogram, so one sketch per
 * group costs a bounded few kilobytes, a group's sketch can be built in a
 * single streaming pass, and two sketches add together — which is what makes a
 * live aggregate possible at all, since a capture that is still running has to
 * fold new actions into a total it has already drawn.
 *
 * <h2>A quantile is reported as the interval it is known to lie in</h2>
 *
 * <p>{@link #quantile(double)} returns {@link Bounds}, not a number. That is
 * not caution for its own sake: a histogram genuinely does not know where in a
 * bucket its values fell, and returning the bucket's midpoint would present a
 * derived guess with the same face as a measurement, which plan rule 14
 * forbids. The bounds are the exact integer edges of the bucket the quantile
 * lands in, tightened by the exact minimum and maximum, so {@code p0} and
 * {@code p100} come back as single values rather than as ranges, and every
 * other quantile comes back as a claim that is true.
 *
 * <h2>Integer arithmetic only, so merging is exact</h2>
 *
 * <p>Bucket boundaries are powers of two subdivided into {@value #SUB_BUCKETS}
 * equal parts, chosen with shifts rather than logarithms. No floating point is
 * involved in deciding where a value goes, so the same values produce the same
 * histogram on any machine, in any order, and {@code merge(a, b)} and
 * {@code merge(b, a)} are the same sketch rather than nearly the same one.
 * Values below {@value #SUB_BUCKETS} get a bucket each and are therefore exact:
 * a distribution of short durations is not approximated at all.
 *
 * <p>The relative width of a bucket above that is at most
 * {@code 1 / SUB_BUCKETS}, so a reported quantile interval is never wider than
 * about 1.6% of its own value.
 *
 * <h2>Absent is not zero</h2>
 *
 * <p>A measurement nothing reported is not added. The count of those is the
 * caller's to carry — see {@link Distribution#count()} against the caller's own
 * total — because a sketch that accepted an "unknown" would have to invent a
 * bucket for it, and every quantile downstream would then be a statement about
 * a value that was never observed.
 */
public final class QuantileSketch {

    /**
     * Sub-buckets per power of two.
     *
     * <p>64 gives a bucket at most 1/64 of its own value wide and about 3,700
     * buckets in the worst case, of which a duration distribution uses roughly
     * 1,900. Doubling it halves the error and doubles the memory; the error
     * that matters here is already far below the disagreement between the
     * sources being measured.
     */
    public static final int SUB_BUCKETS = 64;

    private static final int SUB_BUCKET_BITS = 6;

    /** Values below this get one bucket each and are recorded exactly. */
    private static final int EXACT_BELOW = SUB_BUCKETS;

    private long[] counts = new long[EXACT_BELOW];
    private long count;
    private long sum;
    private boolean sumExact = true;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    public QuantileSketch() {}

    /**
     * Records one observation.
     *
     * @param value a measurement, which must be non-negative — a negative
     *     duration or size is a defect in whatever produced it, not a data
     *     point, and silently bucketing one would hide the defect behind a
     *     plausible percentile
     */
    public void add(long value) {
        add(value, 1);
    }

    /** Records {@code occurrences} observations of the same value. */
    public void add(long value, long occurrences) {
        if (value < 0) {
            throw new IllegalArgumentException("measurements cannot be negative: " + value);
        }
        if (occurrences < 0) {
            throw new IllegalArgumentException("occurrences cannot be negative: " + occurrences);
        }
        if (occurrences == 0) {
            return;
        }
        int bucket = bucketOf(value);
        ensureBucket(bucket);
        counts[bucket] += occurrences;
        count += occurrences;
        addToSum(value, occurrences);
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
    }

    /**
     * Folds {@code other} into this sketch.
     *
     * <p>Commutative and associative, because both operands are histograms over
     * the same fixed layout and merging is addition. That is the property the
     * live overview depends on: a running capture folds each batch of new
     * actions into the total it has already shown, and the answer must not
     * depend on how the batches happened to be cut.
     */
    public void merge(QuantileSketch other) {
        Objects.requireNonNull(other, "other");
        if (other.count == 0) {
            return;
        }
        ensureBucket(other.counts.length - 1);
        for (int bucket = 0; bucket < other.counts.length; bucket++) {
            counts[bucket] += other.counts[bucket];
        }
        count += other.count;
        if (!other.sumExact) {
            sumExact = false;
        } else {
            addToSum(other.sum, 1);
        }
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
    }

    /** Everything this sketch knows, as a value that will not change under it. */
    public Distribution snapshot() {
        return new Distribution(
                count,
                sumExact && count > 0 ? OptionalLong.of(sum) : OptionalLong.empty(),
                count == 0 ? OptionalLong.empty() : OptionalLong.of(min),
                count == 0 ? OptionalLong.empty() : OptionalLong.of(max),
                counts);
    }

    /** How many observations have been recorded. */
    public long count() {
        return count;
    }

    private void addToSum(long value, long occurrences) {
        if (!sumExact) {
            return;
        }
        try {
            sum = Math.addExact(sum, Math.multiplyExact(value, occurrences));
        } catch (ArithmeticException overflow) {
            // A sum that wrapped is a wrong number wearing the face of a right
            // one. The sketch keeps every other statistic and reports the sum
            // as unavailable, which rule 11 prefers to a plausible negative.
            sumExact = false;
        }
    }

    private void ensureBucket(int bucket) {
        if (bucket >= counts.length) {
            counts = Arrays.copyOf(counts, bucket + 1);
        }
    }

    /**
     * The bucket a value belongs to.
     *
     * <p>Below {@link #EXACT_BELOW} the bucket is the value. Above it, the
     * value's leading bit selects a power-of-two band and the next
     * {@value #SUB_BUCKET_BITS} bits select one of {@value #SUB_BUCKETS} equal
     * slices of that band, which is why the numbering is contiguous across the
     * join: the first band is exactly the values that already had a bucket
     * each.
     */
    static int bucketOf(long value) {
        if (value < EXACT_BELOW) {
            return (int) value;
        }
        int magnitude = 63 - Long.numberOfLeadingZeros(value);
        int shift = magnitude - SUB_BUCKET_BITS;
        long slice = (value >>> shift) - SUB_BUCKETS;
        return ((magnitude - SUB_BUCKET_BITS + 1) << SUB_BUCKET_BITS) + (int) slice;
    }

    /** The smallest value that lands in {@code bucket}. */
    static long bucketLowerBound(int bucket) {
        if (bucket < EXACT_BELOW) {
            return bucket;
        }
        int band = ((bucket + SUB_BUCKETS) >>> SUB_BUCKET_BITS);
        int magnitude = band + SUB_BUCKET_BITS - 2;
        long slice = SUB_BUCKETS + ((bucket + SUB_BUCKETS) & (SUB_BUCKETS - 1));
        return slice << (magnitude - SUB_BUCKET_BITS);
    }

    /** The largest value that lands in {@code bucket}. */
    static long bucketUpperBound(int bucket) {
        if (bucket < EXACT_BELOW) {
            return bucket;
        }
        int band = ((bucket + SUB_BUCKETS) >>> SUB_BUCKET_BITS);
        int magnitude = band + SUB_BUCKET_BITS - 2;
        int width = magnitude - SUB_BUCKET_BITS;
        return bucketLowerBound(bucket) + (1L << width) - 1;
    }

    /**
     * The range a quantile is known to lie in.
     *
     * <p>{@link #low} equals {@link #high} when the answer is exact, which
     * happens for small values, for the minimum and maximum, and whenever every
     * observation fell in one bucket.
     */
    public record Bounds(long low, long high) {

        public Bounds {
            if (low > high) {
                throw new IllegalArgumentException("empty range: " + low + " > " + high);
            }
        }

        public boolean isExact() {
            return low == high;
        }
    }

    /**
     * A distribution, frozen.
     *
     * <p>A class rather than a record because it holds the bucket array: a
     * record would give it reference equality, so two identical distributions
     * would compare unequal, and would hand the array out to any caller who
     * asked. Neither is what a value should do.
     */
    public static final class Distribution {

        private final long count;
        private final OptionalLong sum;
        private final OptionalLong min;
        private final OptionalLong max;
        private final long[] counts;

        Distribution(long count, OptionalLong sum, OptionalLong min, OptionalLong max,
                long[] counts) {
            this.count = count;
            this.sum = sum;
            this.min = min;
            this.max = max;
            this.counts = trim(counts);
        }

        /** A distribution of nothing: every statistic unavailable, none of them zero. */
        public static Distribution empty() {
            return new Distribution(
                    0, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                    new long[0]);
        }

        private static long[] trim(long[] source) {
            int highest = source.length - 1;
            while (highest >= 0 && source[highest] == 0) {
                highest--;
            }
            return Arrays.copyOf(source, highest + 1);
        }

        /** How many observations there were. Exact. */
        public long count() {
            return count;
        }

        /**
         * The total, when it did not overflow. Exact when present.
         *
         * <p>Absent for an empty distribution rather than zero. The empty sum
         * is arithmetically zero, but "these actions took 0 µs in total" and
         * "nothing timed these actions" are the two readings a bare zero cannot
         * be told apart from, and rule 11 settles which one a view is allowed
         * to show.
         */
        public OptionalLong sum() {
            return sum;
        }

        /** The smallest observation. Exact. Absent when there were none. */
        public OptionalLong min() {
            return min;
        }

        /** The largest observation. Exact. Absent when there were none. */
        public OptionalLong max() {
            return max;
        }

        /** The arithmetic mean, absent when the count is zero or the sum overflowed. */
        public OptionalDouble mean() {
            if (count == 0 || sum.isEmpty()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of((double) sum.getAsLong() / (double) count);
        }

        /**
         * The interval containing the {@code quantile}-th value, or empty when
         * there are no observations.
         *
         * <p>Rank is {@code ceil(quantile * count)} clamped to at least one.
         * The first and last ranks are the minimum and the maximum, which are
         * tracked exactly, so {@code quantile(0)} and {@code quantile(1)} come
         * back as single values. Ranks between them are known only to the width
         * of a bucket, and the interval says so.
         *
         * @param quantile between 0 and 1 inclusive
         */
        public Optional<Bounds> quantile(double quantile) {
            if (quantile < 0 || quantile > 1 || Double.isNaN(quantile)) {
                throw new IllegalArgumentException("quantile must be in [0, 1]: " + quantile);
            }
            if (count == 0) {
                return Optional.empty();
            }
            long rank = (long) Math.ceil(quantile * count);
            if (rank < 1) {
                rank = 1;
            }
            // The first and last observations are known exactly, whatever
            // bucket they fell in, so the extremes come back as single values
            // rather than as the width of their bucket. Everything between them
            // is genuinely only known to bucket resolution.
            if (rank == 1) {
                return Optional.of(new Bounds(min.getAsLong(), min.getAsLong()));
            }
            if (rank == count) {
                return Optional.of(new Bounds(max.getAsLong(), max.getAsLong()));
            }
            long cumulative = 0;
            for (int bucket = 0; bucket < counts.length; bucket++) {
                cumulative += counts[bucket];
                if (cumulative >= rank) {
                    // Tightened by the exact extremes: the bucket holding the
                    // maximum usually extends past it, and reporting a p100
                    // larger than the largest thing observed would be a number
                    // this session has no evidence for.
                    long low = Math.max(bucketLowerBound(bucket), min.getAsLong());
                    long high = Math.min(bucketUpperBound(bucket), max.getAsLong());
                    return Optional.of(new Bounds(low, high));
                }
            }
            throw new IllegalStateException(
                    "bucket counts sum to less than the observation count: " + cumulative
                            + " < " + count);
        }

        /** Buckets in use; the trailing empty ones are not stored. */
        public int bucketCount() {
            return counts.length;
        }

        /** How many observations landed in one bucket. */
        public long bucketCount(int bucket) {
            return bucket < 0 || bucket >= counts.length ? 0 : counts[bucket];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Distribution that
                    && count == that.count
                    && sum.equals(that.sum)
                    && min.equals(that.min)
                    && max.equals(that.max)
                    && Arrays.equals(counts, that.counts);
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, sum, min, max, Arrays.hashCode(counts));
        }

        @Override
        public String toString() {
            return "Distribution[count=" + count + ", sum=" + sum + ", min=" + min
                    + ", max=" + max + ", buckets=" + counts.length + "]";
        }
    }
}
