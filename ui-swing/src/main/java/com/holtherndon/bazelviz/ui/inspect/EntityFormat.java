package com.holtherndon.bazelviz.ui.inspect;

import com.holtherndon.bazelviz.core.measure.Measured;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Rendering entity values for the Phase 3 tables and the inspector.
 *
 * <p>Everything unknown renders as one em dash, everywhere, so a user scanning a column can tell at
 * a glance which cells are values and which are absences. The <em>reason</em> for an absence goes
 * in the tooltip and in the inspector, not in the cell: a table column wide enough for "not
 * reported by this Bazel version" is a table column too wide for the number it usually holds.
 *
 * <p>Durations are formatted with a unit that keeps three significant figures, because an actions
 * table sorted by duration spans microseconds to minutes and a single unit makes one end of it
 * unreadable.
 */
public final class EntityFormat {

  /** What every unavailable value renders as. Matches the Events view. */
  public static final String UNKNOWN = "—";

  private EntityFormat() {}

  /** A microsecond duration, or an em dash when it was never measured. */
  public static String duration(OptionalLong micros) {
    Objects.requireNonNull(micros, "micros");
    return micros.isEmpty() ? UNKNOWN : duration(micros.getAsLong());
  }

  /** A duration carried with its provenance. */
  public static String duration(Measured<Long> measured) {
    Objects.requireNonNull(measured, "measured");
    return measured.value().map(EntityFormat::duration).orElse(UNKNOWN);
  }

  /** A known microsecond duration. */
  public static String duration(long micros) {
    if (micros < 0) {
      // A negative span is not a duration; showing "-3 ms" would invite
      // the reader to believe it.
      return UNKNOWN;
    }
    if (micros < 1_000) {
      return micros + " µs";
    }
    if (micros < 1_000_000) {
      return "%.1f ms".formatted(micros / 1_000.0);
    }
    if (micros < 60_000_000L) {
      return "%.2f s".formatted(micros / 1_000_000.0);
    }
    long totalSeconds = micros / 1_000_000L;
    return "%d m %02d s".formatted(totalSeconds / 60, totalSeconds % 60);
  }

  /** A count that may be unknown. */
  public static String count(OptionalLong value) {
    Objects.requireNonNull(value, "value");
    return value.isEmpty() ? UNKNOWN : "%,d".formatted(value.getAsLong());
  }

  /** A count that may be unknown. */
  public static String count(OptionalInt value) {
    Objects.requireNonNull(value, "value");
    return value.isEmpty() ? UNKNOWN : "%,d".formatted(value.getAsInt());
  }

  /** A count that is genuinely known. */
  public static String count(long value) {
    return "%,d".formatted(value);
  }

  /** Free text that may be absent. */
  public static String text(Optional<String> value) {
    Objects.requireNonNull(value, "value");
    return value.filter(text -> !text.isEmpty()).orElse(UNKNOWN);
  }

  /** An exit code that may be absent. */
  public static String exitCode(OptionalInt value) {
    Objects.requireNonNull(value, "value");
    return value.isEmpty() ? UNKNOWN : Integer.toString(value.getAsInt());
  }

  /**
   * A three-state boolean.
   *
   * <p>"unknown" rather than "no", because the two are asked about exactly the fields where the
   * difference matters — whether the build succeeded, whether all actions were published.
   */
  public static String yesNo(Optional<Boolean> value) {
    Objects.requireNonNull(value, "value");
    return value.map(yes -> yes ? "yes" : "no").orElse(UNKNOWN);
  }

  /**
   * A field for the inspector, carrying the reason when there is no value.
   *
   * @param unknownNote shown beside the word "unknown" when the value is absent
   */
  public static Inspection.Field field(String name, OptionalLong value, String unknownNote) {
    return value.isPresent()
        ? Inspection.Field.of(name, count(value))
        : Inspection.Field.unknown(name, unknownNote);
  }

  /** An inspector field for a value that may simply be absent. */
  public static Inspection.Field field(String name, Optional<String> value) {
    return value
        .filter(text -> !text.isEmpty())
        .map(text -> Inspection.Field.of(name, text))
        .orElseGet(() -> Inspection.Field.unknown(name));
  }

  /** An inspector field for a measured value, carrying its own warning. */
  public static Inspection.Field durationField(String name, Measured<Long> measured) {
    if (measured.isKnown()) {
      return Inspection.Field.of(name, duration(measured.value().orElseThrow()));
    }
    return measured
        .warning()
        .map(why -> Inspection.Field.unknown(name, why))
        .orElseGet(() -> Inspection.Field.unknown(name));
  }
}
