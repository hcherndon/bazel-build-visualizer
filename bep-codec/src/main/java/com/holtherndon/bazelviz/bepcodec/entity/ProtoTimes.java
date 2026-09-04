package com.holtherndon.bazelviz.bepcodec.entity;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Reading protobuf times without inventing any.
 *
 * <h2>Presence, not zero</h2>
 *
 * <p>{@code Timestamp} and {@code Duration} are messages, so {@code hasStartTime()} answers "did
 * the stream carry one" definitively. The deprecated {@code *_millis} fields beside them are
 * scalars, where absent and zero are the same bytes and no such question can be asked.
 *
 * <p>Both spellings are emitted on all four supported Bazel versions and disagreed in zero of 148
 * measured comparisons, so this reads the message form first and falls back to the millis field
 * only when the message is absent and the scalar is non-zero. (The measurement report recommends
 * preferring the millis spelling; that advice is about protobuf-JSON, where neither form can be
 * tested for presence. On the binary wire the message form is strictly more informative, and the
 * two never disagree, so nothing is given up.)
 */
public final class ProtoTimes {

  private ProtoTimes() {}

  /** Epoch micros for a valid timestamp the caller has already confirmed is present. */
  public static long micros(Timestamp timestamp) {
    return timestampMicros(timestamp)
        .orElseThrow(() -> new IllegalArgumentException("timestamp is outside protobuf's range"));
  }

  /** Micros for a valid duration the caller has already confirmed is present. */
  public static long micros(Duration duration) {
    return durationMicros(duration)
        .orElseThrow(() -> new IllegalArgumentException("duration is outside protobuf's range"));
  }

  /** Epoch micros, or unavailable when the protobuf timestamp is malformed. */
  public static OptionalLong timestampMicros(Timestamp timestamp) {
    Objects.requireNonNull(timestamp, "timestamp");
    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();
    // google.protobuf.Timestamp is restricted to years 0001 through 9999, inclusive.
    if (seconds < -62_135_596_800L
        || seconds > 253_402_300_799L
        || nanos < 0
        || nanos > 999_999_999) {
      return OptionalLong.empty();
    }
    return exactMicros(seconds, nanos);
  }

  /** Micros, or unavailable when the protobuf duration is malformed. */
  public static OptionalLong durationMicros(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    long seconds = duration.getSeconds();
    int nanos = duration.getNanos();
    // google.protobuf.Duration spans +/-10,000 years. Seconds and nanos must carry the same sign.
    if (seconds < -315_576_000_000L
        || seconds > 315_576_000_000L
        || nanos < -999_999_999
        || nanos > 999_999_999
        || (seconds < 0 && nanos > 0)
        || (seconds > 0 && nanos < 0)) {
      return OptionalLong.empty();
    }
    return exactMicros(seconds, nanos);
  }

  /** Micros for a legacy millisecond field, or unavailable when multiplication would overflow. */
  public static OptionalLong millisMicros(long millis) {
    if (millis == 0L) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Math.multiplyExact(millis, 1_000L));
    } catch (ArithmeticException overflow) {
      return OptionalLong.empty();
    }
  }

  /**
   * The timestamp, preferring the message form and falling back to a deprecated millis field.
   *
   * @param present whether the message-form field was set
   * @param millis the deprecated scalar; zero is read as absent, because on the wire it is
   *     indistinguishable from absent and no build event legitimately happened at the epoch
   */
  public static OptionalLong micros(boolean present, Timestamp timestamp, long millis) {
    if (present) {
      return timestampMicros(timestamp);
    }
    return millisMicros(millis);
  }

  /**
   * The duration, preferring the message form and falling back to a deprecated millis field.
   *
   * <p>Zero millis is read as absent for the same reason as above, with one consequence worth
   * stating: a genuinely instantaneous span becomes "unknown" rather than "zero". That is the
   * conservative direction — claiming a five-second action took no time is a stronger falsehood
   * than declining to say.
   */
  public static OptionalLong micros(boolean present, Duration duration, long millis) {
    if (present) {
      return durationMicros(duration);
    }
    return millisMicros(millis);
  }

  private static OptionalLong exactMicros(long seconds, int nanos) {
    try {
      return OptionalLong.of(
          Math.addExact(Math.multiplyExact(seconds, 1_000_000L), nanos / 1_000L));
    } catch (ArithmeticException overflow) {
      // The documented protobuf ranges fit in a long at microsecond precision. Keep this guard at
      // the conversion boundary so a future range change cannot silently wrap.
      return OptionalLong.empty();
    }
  }
}
