package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * One measured quantity over a set of things, with the two facts a number is
 * not allowed to be shown without: where it came from, and how much of the set
 * it covers.
 *
 * <h2>Why the coverage travels with the number</h2>
 *
 * <p>Plan 24's Phase 8 exit criterion is "every displayed metric reports source
 * and completeness", and this is the type that makes that structural rather
 * than a habit. A median build duration computed over the 4 actions of 13 that
 * produced an execution-log record is a real number about a real subset, and
 * shown alone it reads as a statement about the build. The subset is not a
 * failure — most actions run inside the Bazel server and never spawn a
 * subprocess, so no attempt record exists for them and none ever will — which
 * is exactly why the count has to be visible: a reader who cannot see it goes
 * looking for missing data that is not missing.
 *
 * <h2>Unavailable is counted, not dropped</h2>
 *
 * <p>{@link #unavailable} is the number of members that reported nothing. They
 * are not in the distribution, so they do not drag a mean toward zero (rule 11),
 * and they are not silently absent either, so a coverage figure can be computed
 * from the series alone rather than from the series plus a number the caller has
 * to remember to pass along.
 *
 * @param name what is being measured, in the words a user reads
 * @param units what the numbers are in, so a formatter can pick a scale
 * @param source where the observations came from (plan 11.5)
 * @param completeness how much of that source was read — a truncated execution
 *     log makes every series drawn from it partial however many observations
 *     it happened to yield
 * @param distribution the observations
 * @param unavailable members that reported no value at all
 */
public record MetricSeries(
        String name,
        Units units,
        DataSource source,
        Completeness completeness,
        QuantileSketch.Distribution distribution,
        long unavailable) {

    public MetricSeries {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(distribution, "distribution");
        if (unavailable < 0) {
            throw new IllegalArgumentException("unavailable cannot be negative: " + unavailable);
        }
    }

    /** What a series is counted in. */
    public enum Units {
        MICROSECONDS("µs"),
        BYTES("bytes"),
        COUNT("");

        private final String symbol;

        Units(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** Starts a series; every observation and every gap goes through it. */
    public static Builder builder(String name, Units units, DataSource source) {
        return new Builder(name, units, source);
    }

    /** Members that reported a value. */
    public long observed() {
        return distribution.count();
    }

    /** Members considered, whether or not they reported anything. */
    public long total() {
        return distribution.count() + unavailable;
    }

    /** The fraction that reported, or empty when there was nothing to cover. */
    public OptionalDouble coverage() {
        long total = total();
        return total == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) distribution.count() / (double) total);
    }

    /** True when every member reported and the source was read whole. */
    public boolean isComplete() {
        return unavailable == 0 && completeness.isComplete() && distribution.count() > 0;
    }

    /** The total, when every member reported it and it did not overflow. */
    public OptionalLong exactSum() {
        return unavailable == 0 && completeness.isComplete()
                ? distribution.sum()
                : OptionalLong.empty();
    }

    /**
     * The total of what was observed, which is a lower bound when anything was
     * not.
     *
     * <p>Distinct from {@link #exactSum()} on purpose: a caller that wants to
     * draw a bar has to choose knowingly between a bar that is exact and a bar
     * that is "at least this", and a single {@code sum()} would let it not
     * notice which one it drew.
     */
    public OptionalLong observedSum() {
        return distribution.sum();
    }

    /** The middle observation's interval, or empty when nothing reported. */
    public Optional<QuantileSketch.Bounds> median() {
        return distribution.quantile(0.5);
    }

    /** One quantile of the observations. */
    public Optional<QuantileSketch.Bounds> quantile(double quantile) {
        return distribution.quantile(quantile);
    }

    /**
     * The sentence that has to appear wherever this metric does.
     *
     * <p>Formatting of the values themselves belongs to the view, which knows
     * about locales and adaptive units; this decides only what must be said.
     */
    public String describe() {
        StringBuilder text = new StringBuilder(name);
        if (!units.symbol().isEmpty()) {
            text.append(" (").append(units.symbol()).append(')');
        }
        text.append(", ").append(sourceName()).append(": ");
        if (distribution.count() == 0) {
            text.append(unavailable == 0
                    ? "nothing to measure"
                    : "not reported for any of the " + unavailable + " considered");
            return text.toString();
        }
        text.append(distribution.count());
        if (unavailable > 0) {
            text.append(" of ").append(total()).append(" reported (")
                    .append(String.format("%.1f%%", coverage().orElse(0) * 100))
                    .append("), the rest unknown rather than zero");
        } else {
            text.append(" of ").append(total()).append(" reported");
        }
        if (!completeness.isComplete()) {
            text.append("; the ").append(sourceName()).append(" is ")
                    .append(completeness.name().toLowerCase(java.util.Locale.ROOT)
                            .replace('_', ' '))
                    .append(", so this may not be all of it");
        }
        return text.toString();
    }

    private String sourceName() {
        return switch (source) {
            case BEP -> "build event stream";
            case BES_ENVELOPE -> "BES envelope";
            case EXECUTION_LOG -> "execution log";
            case PROFILE -> "trace profile";
            case AQUERY -> "aquery";
            case CQUERY -> "cquery";
            case QUERY -> "query";
            case FILESYSTEM -> "filesystem";
            case DERIVED -> "computed here";
            case USER_ANNOTATION -> "user annotation";
        };
    }

    /** Accumulates a series one member at a time. Not thread-safe. */
    public static final class Builder {

        private final String name;
        private final Units units;
        private final DataSource source;
        private final QuantileSketch sketch = new QuantileSketch();
        private Completeness completeness = Completeness.COMPLETE;
        private long unavailable;

        private Builder(String name, Units units, DataSource source) {
            this.name = Objects.requireNonNull(name, "name");
            this.units = Objects.requireNonNull(units, "units");
            this.source = Objects.requireNonNull(source, "source");
        }

        /** Records a member that reported a value. */
        public Builder observe(long value) {
            sketch.add(value);
            return this;
        }

        /** Records a member that reported nothing. */
        public Builder unavailable() {
            unavailable++;
            return this;
        }

        /** Records a member, present or not, in one call. */
        public Builder observe(OptionalLong value) {
            if (value.isPresent()) {
                return observe(value.getAsLong());
            }
            return unavailable();
        }

        /** States how much of the underlying source was read. */
        public Builder completeness(Completeness completeness) {
            this.completeness = Objects.requireNonNull(completeness, "completeness");
            return this;
        }

        public MetricSeries build() {
            return new MetricSeries(name, units, source, completeness, sketch.snapshot(),
                    unavailable);
        }
    }
}
