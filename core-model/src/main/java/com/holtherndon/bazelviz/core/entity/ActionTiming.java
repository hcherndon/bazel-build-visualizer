package com.holtherndon.bazelviz.core.entity;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * When an action ran, and — when that cannot be known — why not.
 *
 * <h2>Action timing is unavailable more often than it is available</h2>
 *
 * <p>Measured across the four supported Bazel versions:
 *
 * <ul>
 *   <li><b>6.5.0 and 7.6.1</b> emit no action timestamps at all. The fields do
 *       not exist in the stream.
 *   <li><b>8.4.1</b> emits both, and {@code endTime} equals {@code startTime}
 *       for <em>every</em> action — a deliberate five-second sleep included. The
 *       timestamps are present and the duration they describe is fiction.
 *   <li><b>9.2.0</b> emits usable timestamps, but only for actions that ran a
 *       spawn: roughly a third of action events carry none.
 * </ul>
 *
 * <p>So a duration is derivable in a minority of cases, and the majority must
 * be reported as unknown rather than as zero (plan 11.4). {@link #unknownReason}
 * says which of the three situations produced the unknown, so a view can
 * explain itself — "Bazel 7 does not report action timings" is useful where a
 * blank cell is not.
 *
 * <p>Both raw timestamps are kept even when the duration is unusable. They are
 * what Bazel said, and discarding them would make the 8.4.1 defect invisible to
 * anyone re-reading the session later.
 *
 * @param startMicros epoch micros, empty when not reported
 * @param endMicros epoch micros, empty when not reported
 * @param unknownReason empty exactly when a duration is derivable
 */
public record ActionTiming(
        OptionalLong startMicros, OptionalLong endMicros, Optional<String> unknownReason) {

    /** Neither timestamp was in the stream — Bazel 6.5.0 and 7.6.1, always. */
    public static final String NOT_REPORTED = "NOT_REPORTED";

    /**
     * Both timestamps arrived and are equal. Recorded as unknown because Bazel
     * 8.4.1 reports this for every action regardless of how long it took, so an
     * equal pair carries no information about duration on any version.
     */
    public static final String ZERO_LENGTH_SPAN = "ZERO_LENGTH_SPAN";

    /** Only one of the two arrived. */
    public static final String PARTIAL = "PARTIAL";

    /** The end precedes the start. Stored, and refused as a duration. */
    public static final String END_BEFORE_START = "END_BEFORE_START";

    public static final ActionTiming NONE =
            new ActionTiming(OptionalLong.empty(), OptionalLong.empty(), Optional.of(NOT_REPORTED));

    public ActionTiming {
        Objects.requireNonNull(startMicros, "startMicros");
        Objects.requireNonNull(endMicros, "endMicros");
        Objects.requireNonNull(unknownReason, "unknownReason");
    }

    /**
     * Classifies a pair of timestamps. Takes the two values rather than the
     * message, so the rule -- which is about what Bazel's numbers mean, not
     * about protobuf -- stays testable without building an event.
     */
    public static ActionTiming of(OptionalLong start, OptionalLong end) {
        boolean hasStart = start.isPresent();
        boolean hasEnd = end.isPresent();
        if (!hasStart && !hasEnd) {
            return NONE;
        }
        if (!hasStart || !hasEnd) {
            return new ActionTiming(start, end, Optional.of(PARTIAL));
        }
        long from = start.getAsLong();
        long to = end.getAsLong();
        if (to < from) {
            return new ActionTiming(start, end, Optional.of(END_BEFORE_START));
        }
        if (to == from) {
            return new ActionTiming(start, end, Optional.of(ZERO_LENGTH_SPAN));
        }
        return new ActionTiming(start, end, Optional.empty());
    }

    /** The duration, present only when both ends are trustworthy. */
    public OptionalLong durationMicros() {
        if (unknownReason.isPresent()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(endMicros.getAsLong() - startMicros.getAsLong());
    }
}
