package com.holtherndon.bazelviz.app.cli;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Human-facing formatting for the text output.
 *
 * <p>One rule runs through all of it: an unavailable value prints as {@value #UNKNOWN}, never as
 * {@code 0} and never as an empty column (plan 11.4). A zero here would be indistinguishable from a
 * real measurement of zero, and the whole point of the {@code OptionalLong}s that reach this class
 * is that the difference is known and must survive to the screen.
 *
 * <p>Everything is {@link Locale#ROOT} so that output is identical on every machine and can be
 * asserted on in a test.
 */
final class Formatting {

  /** What an absent value looks like. Deliberately not "0" and not blank. */
  static final String UNKNOWN = "unknown";

  private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  private Formatting() {}

  /** A count with thousands separators, e.g. {@code 1,234,567}. */
  static String count(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  static String count(OptionalLong value) {
    return value.isPresent() ? count(value.getAsLong()) : UNKNOWN;
  }

  /** Bytes as a binary-prefixed size, e.g. {@code 12.3 MiB}. */
  static String bytes(long value) {
    if (value < 1024) {
      return value + " B";
    }
    double scaled = value;
    int unit = 0;
    while (scaled >= 1024 && unit < UNITS.length - 1) {
      scaled /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", scaled, UNITS[unit]);
  }

  static String bytes(OptionalLong value) {
    return value.isPresent() ? bytes(value.getAsLong()) : UNKNOWN;
  }

  /** Byte count with both the exact number and the readable size. */
  static String byteSize(OptionalLong value) {
    return value.isPresent()
        ? count(value.getAsLong()) + " (" + bytes(value.getAsLong()) + ")"
        : UNKNOWN;
  }

  /** An elapsed duration in the largest sensible unit, never rounded to zero. */
  static String duration(long nanos) {
    double seconds = nanos / 1_000_000_000.0;
    if (seconds < 1) {
      return String.format(Locale.ROOT, "%.0f ms", nanos / 1_000_000.0);
    }
    if (seconds < 60) {
      return String.format(Locale.ROOT, "%.2f s", seconds);
    }
    long wholeMinutes = (long) (seconds / 60);
    return String.format(Locale.ROOT, "%d m %.1f s", wholeMinutes, seconds - wholeMinutes * 60);
  }

  /**
   * A rate in records per second, or {@link #UNKNOWN} when too little time has passed to measure
   * one. An invented rate is worse than no rate.
   */
  static String rate(long records, long nanos) {
    if (nanos <= 0) {
      return UNKNOWN;
    }
    double perSecond = records / (nanos / 1_000_000_000.0);
    return String.format(Locale.ROOT, "%,.0f/s", perSecond);
  }

  /** Epoch microseconds as an ISO-8601 UTC instant. */
  static String micros(long value) {
    Instant instant =
        Instant.ofEpochSecond(
            Math.floorDiv(value, 1_000_000L), Math.floorMod(value, 1_000_000L) * 1_000L);
    return TIMESTAMP.format(instant);
  }

  static String micros(OptionalLong value) {
    return value.isPresent() ? micros(value.getAsLong()) : UNKNOWN;
  }

  /** A 64-bit hash as fixed-width hex, so two of them line up in a column. */
  static String hash(long value) {
    return String.format(Locale.ROOT, "0x%016x", value);
  }

  static String hash(OptionalLong value) {
    return value.isPresent() ? hash(value.getAsLong()) : UNKNOWN;
  }

  /** Lower-case hex of up to {@code limit} bytes, space separated. */
  static String hexPreview(byte[] data, int limit) {
    int shown = Math.min(limit, data.length);
    StringBuilder text = new StringBuilder(shown * 3);
    for (int i = 0; i < shown; i++) {
      if (i > 0) {
        text.append(' ');
      }
      text.append(String.format(Locale.ROOT, "%02x", data[i]));
    }
    if (shown < data.length) {
      text.append(" … (").append(count(data.length - (long) shown)).append(" more)");
    }
    return text.toString();
  }

  /** Lower-case hex of a whole byte array, unspaced. */
  static String hex(byte[] data) {
    StringBuilder text = new StringBuilder(data.length * 2);
    for (byte b : data) {
      text.append(String.format(Locale.ROOT, "%02x", b));
    }
    return text.toString();
  }
}
