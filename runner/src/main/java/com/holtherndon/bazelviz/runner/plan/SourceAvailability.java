package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the session will and will not contain if this plan runs (plan 7.1, {@code
 * InstrumentationPlanner} output: "source availability plan").
 *
 * <p>This exists so that the answer to "why is the dependency graph empty" is available
 * <em>before</em> the build rather than discovered afterwards in an empty view. Every later phase's
 * views gate on it: a view whose source is {@link Availability#UNAVAILABLE} explains itself instead
 * of rendering nothing, which is the difference between a tool that looks broken and one that is
 * honest about what it captured.
 */
public record SourceAvailability(Map<DataSource, Entry> bySource) {

  /** Whether a source will be captured. */
  public enum Availability {
    /** It will be captured. */
    PLANNED,
    /** It could be, but the user or the preset declined. */
    DECLINED,
    /** The selected binary cannot produce it. */
    UNAVAILABLE,
    /** Whether it can be produced is not known, because the probe failed. */
    UNKNOWN
  }

  /**
   * @param availability the verdict
   * @param reason why, in one sentence; always present, because an empty view with no explanation
   *     is the failure this record exists to prevent
   * @param enabledBy the flag that would produce it, when there is one
   */
  public record Entry(Availability availability, String reason, Optional<String> enabledBy) {
    public Entry {
      Objects.requireNonNull(availability, "availability");
      Objects.requireNonNull(reason, "reason");
      enabledBy = Objects.requireNonNull(enabledBy, "enabledBy");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("availability " + availability + " must state a reason");
      }
    }
  }

  public SourceAvailability {
    bySource = Map.copyOf(Objects.requireNonNull(bySource, "bySource"));
  }

  /**
   * The verdict for {@code source}. A source nobody recorded a verdict for is {@link
   * Availability#UNKNOWN}, never {@code UNAVAILABLE}: forgetting to plan for a source is not
   * evidence that it cannot be captured.
   */
  public Entry entry(DataSource source) {
    return bySource.getOrDefault(
        source,
        new Entry(
            Availability.UNKNOWN, "no availability was recorded for " + source, Optional.empty()));
  }

  public boolean isPlanned(DataSource source) {
    return entry(source).availability() == Availability.PLANNED;
  }
}
