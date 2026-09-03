package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The two critical paths, side by side and never combined.
 *
 * <h2>Why this type exists at all</h2>
 *
 * <p>Plan 24's Phase 8 exit criterion is "Bazel-reported and derived critical paths remain
 * distinct", and a rule of that kind survives exactly as long as somebody remembers it. So it is a
 * type instead: this holds both, names both, and offers <em>no</em> accessor called {@code
 * criticalPath}, no "best available" fallback, and no method that returns one number for "the"
 * critical path. A caller that wants to show a critical path has to say which one, and a caller
 * that wants to compare them has to do so explicitly.
 *
 * <h2>They legitimately disagree</h2>
 *
 * <p>Bazel's is what actually gated the build, scheduling and machine limits included; it is
 * written into the trace profile and Phase 4 stores it untouched in {@code bazel_critical_path}.
 * The derived one is what the dependency graph alone implies, as if every action had started the
 * instant its inputs existed. A build where the two are close was limited by its dependencies; a
 * build where Bazel's is much longer was limited by something else — jobs, cores, a remote queue —
 * and that gap is the most useful thing on this screen. Collapsing them into one number destroys
 * precisely that.
 *
 * <p>ADR-009 requires both to survive separately, and they arrive from different sources with no
 * join between them: Bazel's components identify themselves by a progress message and nothing else,
 * so they cannot be matched to the actions table. That is a second, harder reason not to merge them
 * — there is no key on which a merge could even be attempted.
 *
 * @param bazelReportedMicros Bazel's own total, from {@code BuildMetrics} or the profile
 * @param bazelComponents any already-loaded components Bazel listed, in its order
 * @param bazelComponentCount exact number of components Bazel listed; the UI may page them instead
 *     of retaining them here
 * @param derived this application's computation over the action graph, absent when there is no
 *     graph to compute it over
 * @param derivedUnavailableReason exact reason the derived path is absent, when the collector could
 *     determine one
 * @param observedActionLowerBound the longest valid action span in the BEP, when one exists; this
 *     is useful fallback evidence but is deliberately not a dependency path
 */
public record CriticalPaths(
    Measured<Long> bazelReportedMicros,
    List<BazelComponent> bazelComponents,
    long bazelComponentCount,
    Optional<CriticalPath.Result> derived,
    Optional<String> derivedUnavailableReason,
    Optional<ObservedActionLowerBound> observedActionLowerBound) {

  public CriticalPaths {
    Objects.requireNonNull(bazelReportedMicros, "bazelReportedMicros");
    bazelReportedMicros
        .value()
        .ifPresent(
            value -> {
              if (value < 0) {
                throw new IllegalArgumentException(
                    "Bazel-reported critical-path duration must be nonnegative");
              }
            });
    bazelComponents = List.copyOf(bazelComponents);
    if (bazelComponentCount < 0) {
      throw new IllegalArgumentException("Bazel critical-path component count must be nonnegative");
    }
    if (bazelComponentCount < bazelComponents.size()) {
      throw new IllegalArgumentException(
          "Bazel critical-path component count cannot be smaller than the loaded list");
    }
    Objects.requireNonNull(derived, "derived");
    Objects.requireNonNull(derivedUnavailableReason, "derivedUnavailableReason");
    Objects.requireNonNull(observedActionLowerBound, "observedActionLowerBound");
    if (derived.isPresent()) {
      derivedUnavailableReason = Optional.empty();
    }
  }

  /** Compatibility constructor for callers without a separately computed timing fallback. */
  public CriticalPaths(
      Measured<Long> bazelReportedMicros,
      List<BazelComponent> bazelComponents,
      long bazelComponentCount,
      Optional<CriticalPath.Result> derived,
      Optional<String> derivedUnavailableReason) {
    this(
        bazelReportedMicros,
        bazelComponents,
        bazelComponentCount,
        derived,
        derivedUnavailableReason,
        Optional.empty());
  }

  /** Compatibility constructor for callers that already loaded every Bazel component. */
  public CriticalPaths(
      Measured<Long> bazelReportedMicros,
      List<BazelComponent> bazelComponents,
      Optional<CriticalPath.Result> derived,
      Optional<String> derivedUnavailableReason) {
    this(
        bazelReportedMicros,
        bazelComponents,
        bazelComponents.size(),
        derived,
        derivedUnavailableReason,
        Optional.empty());
  }

  /** Compatibility constructor for callers that have no more specific absence reason. */
  public CriticalPaths(
      Measured<Long> bazelReportedMicros,
      List<BazelComponent> bazelComponents,
      Optional<CriticalPath.Result> derived) {
    this(bazelReportedMicros, bazelComponents, derived, Optional.empty());
  }

  /** Neither path is available. */
  public static CriticalPaths none(String whyBazelMissing) {
    return new CriticalPaths(
        Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE, whyBazelMissing),
        List.of(),
        0,
        Optional.empty(),
        Optional.of("no imported action graph is available"),
        Optional.empty());
  }

  /** One entry of Bazel's own critical path, exactly as Bazel worded it. */
  public record BazelComponent(int ordinal, String description, OptionalLong durationMicros) {

    public BazelComponent {
      Objects.requireNonNull(description, "description");
      Objects.requireNonNull(durationMicros, "durationMicros");
      if (ordinal < 0) {
        throw new IllegalArgumentException("Bazel component ordinal must be nonnegative");
      }
      if (durationMicros.isPresent() && durationMicros.getAsLong() < 0) {
        throw new IllegalArgumentException("Bazel component duration must be nonnegative");
      }
    }
  }

  /**
   * A useful bound when no dependency graph exists, never a substitute for that graph.
   *
   * <p>One observed action cannot establish which other actions preceded it. Its duration is still
   * a concrete lower bound on the action work Bazel observed. Keeping the action identity and
   * timing coverage beside the value lets the UI make that limited claim without inventing an edge,
   * path total, or slack value.
   */
  public record ObservedActionLowerBound(
      long actionId,
      String primaryOutput,
      Optional<String> targetLabel,
      Optional<String> mnemonic,
      long durationMicros,
      long timedActions,
      long totalActions) {

    public ObservedActionLowerBound {
      if (actionId < 1) {
        throw new IllegalArgumentException("observed action id must be positive");
      }
      Objects.requireNonNull(primaryOutput, "primaryOutput");
      Objects.requireNonNull(targetLabel, "targetLabel");
      Objects.requireNonNull(mnemonic, "mnemonic");
      if (durationMicros <= 0) {
        throw new IllegalArgumentException("observed action duration must be positive");
      }
      if (timedActions < 1 || totalActions < timedActions) {
        throw new IllegalArgumentException(
            "observed action timing coverage must be positive and no larger than all actions");
      }
    }

    /** The explicit UI name; this evidence is not a dependency path. */
    public String displayName() {
      return "Longest observed action (not a dependency path)";
    }
  }

  /** The name Bazel's path may be shown under, and only that name. */
  public String bazelDisplayName() {
    return "Bazel-reported critical path";
  }

  /**
   * The name the derived path may be shown under.
   *
   * <p>Plan 13.4 fixes this wording: "Visualizer-computed dependency critical path". It comes from
   * {@link CriticalPath.Result#displayName()} so there is one place it is written.
   */
  public String derivedDisplayName() {
    return derived
        .map(CriticalPath.Result::displayName)
        .orElse("Visualizer-computed dependency critical path");
  }

  /** True when both paths have comparable numbers with complete dependency timing. */
  public boolean bothAvailable() {
    return bazelReportedMicros.isCompleteObservation()
        && derived
            .filter(result -> result.outcome() == CriticalPath.Outcome.COMPUTED)
            .filter(result -> !result.isPartial())
            .isPresent();
  }

  /**
   * Bazel's total minus the derived one, when both exist and all graph nodes were timed.
   *
   * <p>Deliberately signed and deliberately not called a "difference in accuracy". A partial
   * dependency result is deliberately not compared: its missing durations are part of the numeric
   * difference, so presenting that value as scheduling or wait time would manufacture an
   * explanation from absent data.
   */
  public OptionalLong schedulingGapMicros() {
    if (!bothAvailable()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(
        Math.subtractExact(
            bazelReportedMicros.value().orElseThrow(), derived.orElseThrow().makespanMicros()));
  }

  /**
   * What must be said whenever either number is shown.
   *
   * <p>Always names both, including when only one exists, because "critical path: 4.2 s" with no
   * qualifier is the sentence this whole type exists to make unwritable.
   */
  public String describe() {
    StringBuilder text = new StringBuilder();
    text.append(bazelDisplayName()).append(": ");
    if (bazelReportedMicros.isKnown()) {
      text.append(MetricFormat.duration(bazelReportedMicros.value().orElseThrow()));
      if (bazelComponentCount == 0) {
        text.append("; ")
            .append(bazelReportedMicros.warning().orElse("the component breakdown is unavailable"));
      } else {
        text.append(" across ")
            .append(MetricFormat.count(bazelComponentCount))
            .append(" components");
        bazelReportedMicros.warning().ifPresent(warning -> text.append("; ").append(warning));
      }
    } else {
      text.append("not reported")
          .append(bazelReportedMicros.warning().map(why -> " (" + why + ")").orElse(""));
      if (bazelComponentCount > 0) {
        text.append("; ")
            .append(MetricFormat.count(bazelComponentCount))
            .append(" components were recorded, but their total is unavailable");
      }
    }
    text.append(". ").append(derivedDisplayName()).append(": ");
    if (derived.isEmpty()) {
      text.append("not computed — ")
          .append(
              derivedUnavailableReason.orElse(
                  "there is no imported action graph to compute it over"));
    } else {
      text.append(derived.orElseThrow().describe());
    }
    if (bothAvailable()) {
      long gap = schedulingGapMicros().orElseThrow();
      text.append(" The two differ by ")
          .append(MetricFormat.duration(Math.abs(gap)))
          .append("; they measure different things and a difference is expected.");
    }
    return text.toString();
  }
}
