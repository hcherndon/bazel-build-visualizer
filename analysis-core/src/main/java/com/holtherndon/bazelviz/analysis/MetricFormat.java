package com.holtherndon.bazelviz.analysis;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * How a metric is written down, in one place.
 *
 * <h2>Why the formatting is not left to the view</h2>
 *
 * <p>Findings carry their numbers as text, because a finding's sentence has to
 * read as a sentence and because the value shown must be the value the rule
 * compared against its threshold. If the rules formatted one way and the
 * dashboard another, the same duration would appear as "1.2 s" on one screen
 * and "1200 ms" on the next, and a reader would reasonably wonder which was
 * measured.
 *
 * <h2>Unknown has a spelling</h2>
 *
 * <p>{@link #UNKNOWN} is what every optional overload writes when there is no
 * value. It is a word, not an empty cell and not a dash, because an empty cell
 * in a table of numbers reads as a zero that was not worth printing.
 */
public final class MetricFormat {

    private MetricFormat() {}

    /** What an absent value is written as, everywhere. */
    public static final String UNKNOWN = "not reported";

    /** A duration, in the largest unit that keeps it readable. */
    public static String duration(long micros) {
        long magnitude = Math.abs(micros);
        if (magnitude < 1_000) {
            return micros + " µs";
        }
        if (magnitude < 1_000_000) {
            return String.format(Locale.ROOT, "%.1f ms", micros / 1_000.0);
        }
        if (magnitude < 60_000_000L) {
            return String.format(Locale.ROOT, "%.2f s", micros / 1_000_000.0);
        }
        long totalSeconds = micros / 1_000_000L;
        return String.format(Locale.ROOT, "%d m %02d s", totalSeconds / 60, totalSeconds % 60);
    }

    public static String duration(OptionalLong micros) {
        return micros.isPresent() ? duration(micros.getAsLong()) : UNKNOWN;
    }

    /** A size, in binary units, because that is what a filesystem reports. */
    public static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double scaled = value;
        int unit = -1;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    public static String bytes(OptionalLong value) {
        return value.isPresent() ? bytes(value.getAsLong()) : UNKNOWN;
    }

    /** A fraction, as a percentage with one decimal place. */
    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    public static String percent(OptionalDouble fraction) {
        return fraction.isPresent() ? percent(fraction.getAsDouble()) : UNKNOWN;
    }

    /** A plain count, grouped so a seven-digit action count can be read. */
    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /** A ratio such as a parallelism factor, which is not a percentage. */
    public static String ratio(OptionalDouble value) {
        return value.isPresent()
                ? String.format(Locale.ROOT, "%.2f", value.getAsDouble())
                : UNKNOWN;
    }

    /**
     * A quantile interval, collapsed to one value when the sketch knows it
     * exactly.
     *
     * <p>Writing "1.20 s to 1.22 s" for every percentile would be noise; the
     * range appears only when it is real, so a reader who sees one can tell
     * that the sketch, and not the build, is where the imprecision came from.
     */
    public static String bounds(java.util.Optional<QuantileSketch.Bounds> bounds) {
        return bounds.map(value -> value.isExact()
                        ? duration(value.low())
                        : duration(value.low()) + " to " + duration(value.high()))
                .orElse(UNKNOWN);
    }
}
