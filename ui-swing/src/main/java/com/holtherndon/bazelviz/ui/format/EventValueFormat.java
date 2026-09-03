package com.holtherndon.bazelviz.ui.format;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Renders event values for display, with one rule above all others: an unavailable value renders as
 * an em dash and never as {@code 0}, an empty string, or the epoch (plan 11.4, project rule 11).
 *
 * <p>The distinction matters most where a number would look plausible. A BEP event that carries no
 * timestamp is not an event that happened at 1970-01-01T00:00:00Z; an event whose bytes did not
 * decode does not have zero children. Both would be readable, sortable, believable lies.
 *
 * <p>Timestamps render in UTC. Local time would be friendlier and would also make two people
 * reading the same capture disagree about when an event happened, so the column headers say UTC and
 * the values are UTC.
 */
public final class EventValueFormat {

  /** What every unavailable value renders as. */
  public static final String UNKNOWN = "—";

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

  private static final String[] BYTE_UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

  private EventValueFormat() {}

  /** An epoch-microsecond timestamp, or an em dash when the event carried none. */
  public static String timestamp(OptionalLong epochMicros) {
    Objects.requireNonNull(epochMicros, "epochMicros");
    if (epochMicros.isEmpty()) {
      return UNKNOWN;
    }
    return timestamp(epochMicros.getAsLong());
  }

  /** A known epoch-microsecond timestamp. */
  public static String timestamp(long epochMicros) {
    long seconds = Math.floorDiv(epochMicros, 1_000_000L);
    long micros = Math.floorMod(epochMicros, 1_000_000L);
    return TIMESTAMP.format(Instant.ofEpochSecond(seconds, micros * 1_000L));
  }

  /** A count that is genuinely known. */
  public static String count(long value) {
    return Long.toString(value);
  }

  /** A count that may be unknown. */
  public static String count(OptionalLong value) {
    Objects.requireNonNull(value, "value");
    return value.isEmpty() ? UNKNOWN : Long.toString(value.getAsLong());
  }

  /** Free text that may be absent. */
  public static String text(Optional<String> value) {
    Objects.requireNonNull(value, "value");
    return value.filter(text -> !text.isEmpty()).orElse(UNKNOWN);
  }

  /** A 64-bit id hash, in the hex form the event-id lookup accepts. */
  public static String idHash(OptionalLong hash) {
    Objects.requireNonNull(hash, "hash");
    return hash.isEmpty() ? UNKNOWN : "0x%016x".formatted(hash.getAsLong());
  }

  /** A byte count, with its exact value alongside the rounded one. */
  public static String bytes(long value) {
    if (value < 1024) {
      return value + " B";
    }
    double scaled = value;
    int unit = 0;
    while (scaled >= 1024 && unit < BYTE_UNITS.length - 1) {
      scaled /= 1024;
      unit++;
    }
    return "%.1f %s".formatted(scaled, BYTE_UNITS[unit]);
  }

  /** A byte count that may be unknown — an unsized stream, a vanished file. */
  public static String bytes(OptionalLong value) {
    Objects.requireNonNull(value, "value");
    return value.isEmpty() ? UNKNOWN : bytes(value.getAsLong());
  }

  /** A rate, or an em dash while too little time has passed to measure one. */
  public static String rate(OptionalDouble perSecond, String unit) {
    Objects.requireNonNull(perSecond, "perSecond");
    if (perSecond.isEmpty()) {
      return UNKNOWN;
    }
    double value = perSecond.getAsDouble();
    if (value >= 100) {
      return "%,.0f %s/s".formatted(value, unit);
    }
    return "%,.1f %s/s".formatted(value, unit);
  }

  /** A byte rate, or an em dash when none has been measured. */
  public static String byteRate(OptionalDouble bytesPerSecond) {
    Objects.requireNonNull(bytesPerSecond, "bytesPerSecond");
    if (bytesPerSecond.isEmpty()) {
      return UNKNOWN;
    }
    return bytes((long) bytesPerSecond.getAsDouble()) + "/s";
  }

  /** A percentage, or an em dash when the denominator is unknown. */
  public static String percentage(OptionalDouble fraction) {
    Objects.requireNonNull(fraction, "fraction");
    return fraction.isEmpty() ? UNKNOWN : "%.1f%%".formatted(fraction.getAsDouble() * 100);
  }
}
