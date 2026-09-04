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

  /** Whether a time was absent, validly present, or present but malformed. */
  public enum State {
    ABSENT,
    PRESENT,
    INVALID
  }

  /** A checked conversion that does not collapse malformed input into absence. */
  public record Checked(State state, OptionalLong micros) {
    public Checked {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(micros, "micros");
      if ((state == State.PRESENT) != micros.isPresent()) {
        throw new IllegalArgumentException("only a present time may carry microseconds");
      }
    }

    public boolean isInvalid() {
      return state == State.INVALID;
    }

    public static Checked absent() {
      return new Checked(State.ABSENT, OptionalLong.empty());
    }

    private static Checked present(long micros) {
      return new Checked(State.PRESENT, OptionalLong.of(micros));
    }

    private static Checked invalid() {
      return new Checked(State.INVALID, OptionalLong.empty());
    }
  }

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
    return checkedTimestampMicros(timestamp).micros();
  }

  /** Checked epoch-microsecond conversion for a timestamp known to be present. */
  public static Checked checkedTimestampMicros(Timestamp timestamp) {
    Objects.requireNonNull(timestamp, "timestamp");
    long seconds = timestamp.getSeconds();
    int nanos = timestamp.getNanos();
    // google.protobuf.Timestamp is restricted to years 0001 through 9999, inclusive.
    if (seconds < -62_135_596_800L
        || seconds > 253_402_300_799L
        || nanos < 0
        || nanos > 999_999_999) {
      return Checked.invalid();
    }
    return checkedExactMicros(seconds, nanos);
  }

  /** Micros, or unavailable when the protobuf duration is malformed. */
  public static OptionalLong durationMicros(Duration duration) {
    return checkedDurationMicros(duration).micros();
  }

  /** Checked microsecond conversion for a duration known to be present. */
  public static Checked checkedDurationMicros(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    long seconds = duration.getSeconds();
    int nanos = duration.getNanos();
    if (!isValidDuration(duration)) {
      return Checked.invalid();
    }
    return checkedExactMicros(seconds, nanos);
  }

  /** Checked duration conversion for fields whose domain cannot be negative. */
  public static Checked checkedNonnegativeDurationMicros(Duration duration) {
    Checked checked = checkedDurationMicros(duration);
    return checked.state() == State.PRESENT
            && (duration.getSeconds() < 0 || duration.getNanos() < 0)
        ? Checked.invalid()
        : checked;
  }

  /** Optional form of {@link #checkedNonnegativeDurationMicros(Duration)}. */
  public static OptionalLong nonnegativeDurationMicros(Duration duration) {
    return checkedNonnegativeDurationMicros(duration).micros();
  }

  /** Whether this message obeys protobuf Duration's range and sign rules. */
  public static boolean isValidDuration(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    long seconds = duration.getSeconds();
    int nanos = duration.getNanos();
    // google.protobuf.Duration spans +/-10,000 years. Seconds and nanos must carry the same sign.
    return seconds >= -315_576_000_000L
        && seconds <= 315_576_000_000L
        && nanos >= -999_999_999
        && nanos <= 999_999_999
        && (seconds >= 0 || nanos <= 0)
        && (seconds <= 0 || nanos >= 0);
  }

  /** Micros for a legacy millisecond field, or unavailable when multiplication would overflow. */
  public static OptionalLong millisMicros(long millis) {
    return checkedMillisMicros(millis).micros();
  }

  /** Checked conversion for a legacy scalar whose zero value is indistinguishable from absence. */
  public static Checked checkedMillisMicros(long millis) {
    if (millis == 0L) {
      return Checked.absent();
    }
    try {
      return Checked.present(Math.multiplyExact(millis, 1_000L));
    } catch (ArithmeticException overflow) {
      return Checked.invalid();
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
    return checkedMicros(present, timestamp, millis).micros();
  }

  /** Checked timestamp conversion, preserving message presence even when its value is the epoch. */
  public static Checked checkedMicros(boolean present, Timestamp timestamp, long millis) {
    if (present) {
      return checkedTimestampMicros(timestamp);
    }
    return checkedMillisMicros(millis);
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
    return checkedMicros(present, duration, millis).micros();
  }

  /** Checked duration conversion with a legacy-millisecond fallback. */
  public static Checked checkedMicros(boolean present, Duration duration, long millis) {
    if (present) {
      return checkedDurationMicros(duration);
    }
    return checkedMillisMicros(millis);
  }

  /** Checked nonnegative duration conversion with a legacy-millisecond fallback. */
  public static Checked checkedNonnegativeMicros(boolean present, Duration duration, long millis) {
    Checked checked =
        present ? checkedNonnegativeDurationMicros(duration) : checkedMillisMicros(millis);
    return checked.state() == State.PRESENT && checked.micros().orElseThrow() < 0
        ? Checked.invalid()
        : checked;
  }

  /** Optional form of {@link #checkedNonnegativeMicros(boolean, Duration, long)}. */
  public static OptionalLong nonnegativeMicros(boolean present, Duration duration, long millis) {
    return checkedNonnegativeMicros(present, duration, millis).micros();
  }

  private static Checked checkedExactMicros(long seconds, int nanos) {
    try {
      return Checked.present(
          Math.addExact(Math.multiplyExact(seconds, 1_000_000L), nanos / 1_000L));
    } catch (ArithmeticException overflow) {
      // The documented protobuf ranges fit in a long at microsecond precision. Keep this guard at
      // the conversion boundary so a future range change cannot silently wrap.
      return Checked.invalid();
    }
  }
}
