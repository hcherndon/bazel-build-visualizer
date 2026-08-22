package com.holtherndon.bazelviz.core.enrich;

import java.util.Optional;

/**
 * The absolute instant a profile's {@code ts: 0} corresponds to, together with
 * how well it is known.
 *
 * <h2>Why this is a type and not a long</h2>
 *
 * <p>The profile's anchor is the most dangerous single value in Phase 4.
 * Measured across four versions (P1):
 *
 * <table>
 *   <caption>Anchor key and meaning by version</caption>
 *   <tr><th>Version</th><th>Key</th><th>Anchor − BEP buildStarted</th></tr>
 *   <tr><td>6.5.0</td><td>{@code profile_finish_ts}</td><td>−895 ms</td></tr>
 *   <tr><td>7.6.1</td><td>{@code profile_finish_ts}</td><td>−756 ms</td></tr>
 *   <tr><td>8.4.1</td><td>{@code profile_start_ts}</td><td>+20 ms</td></tr>
 *   <tr><td>9.2.0</td><td>{@code profile_start_ts}</td><td>+22 ms</td></tr>
 * </table>
 *
 * <p>On the older pair the field is named for the finish and holds the start,
 * floored to a whole second. It sits before the build began and roughly two
 * seconds before it ended, while the trace itself spans 1.2–1.5 s, so it cannot
 * be a finish. Taking it at its name puts every span about one build-length too
 * late.
 *
 * <p>Flooring also costs precision, and that is the part a plain long would
 * lose: on 6.5.0 and 7.6.1 a profile span cannot be aligned with an
 * execution-log attempt to better than one second. Drawing the two on a shared
 * timeline as though they agreed would be a claim the data does not support,
 * which plan 11.4 forbids. {@link #uncertaintyMicros()} exists so that a
 * caller cannot forget.
 *
 * @param epochMicros the instant of {@code ts: 0}
 * @param meaning what the source field actually held
 * @param sourceKey the {@code otherData} key it was read from, kept so the
 *     inspector can show where the number came from
 * @param uncertaintyMicros the width of the window the true instant lies in;
 *     zero when exact
 */
public record ProfileAnchor(
        long epochMicros, Meaning meaning, String sourceKey, long uncertaintyMicros) {

    /** One second, in microseconds: the error introduced by flooring. */
    public static final long FLOORED_UNCERTAINTY_MICROS = 1_000_000L;

    /** What the field the anchor was read from actually contained. */
    public enum Meaning {
        /** An exact start, to the millisecond. Bazel 8.4.1 and later. */
        EXACT_START,
        /**
         * A start floored to the whole second, published under a key naming it
         * a finish. Bazel 6.5.0 and 7.6.1.
         */
        START_FLOORED_TO_SECOND,
        /** No anchor field was present; spans have relative time only. */
        ABSENT
    }

    public ProfileAnchor {
        if (uncertaintyMicros < 0) {
            throw new IllegalArgumentException("uncertainty cannot be negative");
        }
        if (meaning == Meaning.EXACT_START && uncertaintyMicros != 0) {
            throw new IllegalArgumentException(
                    "an exact anchor cannot carry uncertainty; got " + uncertaintyMicros);
        }
        if (meaning == Meaning.ABSENT && epochMicros != 0) {
            throw new IllegalArgumentException("an absent anchor has no instant");
        }
    }

    /** The Bazel 8.4.1+ shape: {@code profile_start_ts}, exact. */
    public static ProfileAnchor exact(long epochMicros, String sourceKey) {
        return new ProfileAnchor(epochMicros, Meaning.EXACT_START, sourceKey, 0);
    }

    /**
     * The Bazel 6.5.0/7.6.1 shape: {@code profile_finish_ts}, which is a start
     * floored to the second.
     */
    public static ProfileAnchor flooredStart(long epochMicros, String sourceKey) {
        return new ProfileAnchor(
                epochMicros, Meaning.START_FLOORED_TO_SECOND, sourceKey,
                FLOORED_UNCERTAINTY_MICROS);
    }

    /** No anchor at all: spans keep relative time and cannot be placed. */
    public static ProfileAnchor absent() {
        return new ProfileAnchor(0, Meaning.ABSENT, "", 0);
    }

    /** True when a span can be given an absolute instant at all. */
    public boolean canPlaceAbsolutely() {
        return meaning != Meaning.ABSENT;
    }

    /**
     * The absolute instant of a trace timestamp, or empty when there is no
     * anchor.
     *
     * <p>Trace timestamps are relative to {@code Initialize command} and are
     * legitimately negative — {@code Launch Blaze} begins at −17,000 to
     * −20,000 µs (P3) — so this must not clamp.
     */
    public Optional<Long> absolute(long traceMicros) {
        return canPlaceAbsolutely()
                ? Optional.of(epochMicros + traceMicros)
                : Optional.empty();
    }

    /**
     * Why this anchor cannot be compared with execution-log timings at full
     * precision, or empty when it can.
     */
    public Optional<String> precisionCaveat() {
        return switch (meaning) {
            case EXACT_START -> Optional.empty();
            case ABSENT -> Optional.of(
                    "this profile carries no absolute start, so its spans have"
                            + " relative time only");
            case START_FLOORED_TO_SECOND -> Optional.of(
                    "this Bazel version publishes the profile's start rounded down to"
                            + " the second, so profile times and execution-log times"
                            + " cannot be lined up more closely than one second");
        };
    }
}
