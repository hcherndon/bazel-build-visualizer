package com.holtherndon.bazelviz.core.measure;

import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A value together with where it came from and how much of it is there (plan 11.4).
 *
 * <h2>Why a wrapper and not a bare number</h2>
 *
 * <p>Plan 11.4 requires every optional metric to carry a present value, a source, a completeness,
 * and an optional warning — and then says the thing this type exists to enforce: <em>never convert
 * unavailable data to zero</em>. A bare {@code long} cannot express "no source reported this", so a
 * codebase built on bare numbers reaches for {@code 0} or {@code -1} and both are lies. Zero input
 * bytes and unknown input bytes look identical in a table, sum differently, and sort differently,
 * and a user cannot tell which they are looking at.
 *
 * <p>The same value can also arrive from more than one source with different numbers — ADR-009
 * keeps those apart rather than collapsing them — so the source travels with the value rather than
 * being recorded once per table.
 *
 * <h2>Absent is a first-class state</h2>
 *
 * <p>{@link #isKnown()} is false when nothing reported the value. Such a measurement still carries
 * a {@link #source} (which source was expected) and a {@link #completeness} (why it is missing),
 * because "we did not look", "the source was truncated before this" and "the source does not report
 * it" are three different answers and the user is entitled to which one applies.
 *
 * @param value the measurement, absent when no source supplied one
 * @param source where it came from, or would have come from
 * @param completeness how much of the underlying source was read
 * @param warning something the user should know before relying on it
 */
public record Measured<T>(
    Optional<T> value, DataSource source, Completeness completeness, Optional<String> warning) {

  public Measured {
    value = Objects.requireNonNull(value, "value");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(completeness, "completeness");
    warning = Objects.requireNonNull(warning, "warning");
  }

  /** A value read directly and completely from {@code source}. */
  public static <T> Measured<T> of(T value, DataSource source) {
    return new Measured<>(
        Optional.of(Objects.requireNonNull(value, "value")),
        source,
        Completeness.COMPLETE,
        Optional.empty());
  }

  /**
   * A value nothing reported.
   *
   * @param source the source that would have carried it, so the UI can say which one was missing
   *     rather than only that something was
   * @param completeness why it is absent
   */
  public static <T> Measured<T> unknown(DataSource source, Completeness completeness) {
    return new Measured<>(Optional.empty(), source, completeness, Optional.empty());
  }

  /** A value nothing reported, for a reason worth stating. */
  public static <T> Measured<T> unknown(DataSource source, Completeness completeness, String why) {
    return new Measured<>(
        Optional.empty(), source, completeness, Optional.of(Objects.requireNonNull(why)));
  }

  /**
   * A value derived from other measurements.
   *
   * <p>{@link DataSource#DERIVED} rather than the source of its inputs: a computed number is this
   * application's claim, not an observation, and plan rule 14 forbids presenting a derived finding
   * as if it were measured.
   */
  public static <T> Measured<T> derived(T value, Completeness completeness) {
    return new Measured<>(
        Optional.of(Objects.requireNonNull(value, "value")),
        DataSource.DERIVED,
        completeness,
        Optional.empty());
  }

  public boolean isKnown() {
    return value.isPresent();
  }

  /** The value, or {@code fallback} — for display only, never for arithmetic. */
  public T orElse(T fallback) {
    return value.orElse(fallback);
  }

  /** Adds a warning, keeping everything else. */
  public Measured<T> warn(String message) {
    return new Measured<>(value, source, completeness, Optional.of(message));
  }

  /**
   * Applies {@code mapper} to the value, keeping the provenance.
   *
   * <p>The result is <em>not</em> re-sourced to {@link DataSource#DERIVED}: a unit conversion or a
   * field extraction is still the same observation from the same source. Use {@link #derived} when
   * combining measurements into something none of them stated.
   */
  public <R> Measured<R> map(Function<T, R> mapper) {
    return new Measured<>(value.map(mapper), source, completeness, warning);
  }

  /**
   * True when this can be trusted for a total.
   *
   * <p>A known value from a truncated source is real but partial, and summing it into a total that
   * is presented as complete would overstate what the session knows. Callers building totals check
   * this and count what they had to leave out (see {@link ByteTotal}).
   */
  public boolean isCompleteObservation() {
    return isKnown() && completeness.isComplete();
  }
}
