package com.holtherndon.bazelviz.bepcodec.entity;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.util.OptionalLong;

/**
 * Reading protobuf times without inventing any.
 *
 * <h2>Presence, not zero</h2>
 *
 * <p>{@code Timestamp} and {@code Duration} are messages, so
 * {@code hasStartTime()} answers "did the stream carry one" definitively. The
 * deprecated {@code *_millis} fields beside them are scalars, where absent and
 * zero are the same bytes and no such question can be asked.
 *
 * <p>Both spellings are emitted on all four supported Bazel versions and
 * disagreed in zero of 148 measured comparisons, so this reads the message form
 * first and falls back to the millis field only when the message is absent and
 * the scalar is non-zero. (The measurement report recommends preferring the
 * millis spelling; that advice is about protobuf-JSON, where neither form can
 * be tested for presence. On the binary wire the message form is strictly more
 * informative, and the two never disagree, so nothing is given up.)
 */
public final class ProtoTimes {

    private ProtoTimes() {}

    /** Epoch micros for a timestamp the caller has already confirmed is present. */
    public static long micros(Timestamp timestamp) {
        return timestamp.getSeconds() * 1_000_000L + timestamp.getNanos() / 1_000L;
    }

    /** Micros for a duration the caller has already confirmed is present. */
    public static long micros(Duration duration) {
        return duration.getSeconds() * 1_000_000L + duration.getNanos() / 1_000L;
    }

    /**
     * The timestamp, preferring the message form and falling back to a
     * deprecated millis field.
     *
     * @param present whether the message-form field was set
     * @param millis the deprecated scalar; zero is read as absent, because on
     *     the wire it is indistinguishable from absent and no build event
     *     legitimately happened at the epoch
     */
    public static OptionalLong micros(boolean present, Timestamp timestamp, long millis) {
        if (present) {
            return OptionalLong.of(micros(timestamp));
        }
        return millis == 0L ? OptionalLong.empty() : OptionalLong.of(millis * 1_000L);
    }

    /**
     * The duration, preferring the message form and falling back to a
     * deprecated millis field.
     *
     * <p>Zero millis is read as absent for the same reason as above, with one
     * consequence worth stating: a genuinely instantaneous span becomes
     * "unknown" rather than "zero". That is the conservative direction —
     * claiming a five-second action took no time is a stronger falsehood than
     * declining to say.
     */
    public static OptionalLong micros(boolean present, Duration duration, long millis) {
        if (present) {
            return OptionalLong.of(micros(duration));
        }
        return millis == 0L ? OptionalLong.empty() : OptionalLong.of(millis * 1_000L);
    }
}
