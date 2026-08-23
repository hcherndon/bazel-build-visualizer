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
 * <p>Plan 24's Phase 8 exit criterion is "Bazel-reported and derived critical
 * paths remain distinct", and a rule of that kind survives exactly as long as
 * somebody remembers it. So it is a type instead: this holds both, names both,
 * and offers <em>no</em> accessor called {@code criticalPath}, no "best
 * available" fallback, and no method that returns one number for "the" critical
 * path. A caller that wants to show a critical path has to say which one, and a
 * caller that wants to compare them has to do so explicitly.
 *
 * <h2>They legitimately disagree</h2>
 *
 * <p>Bazel's is what actually gated the build, scheduling and machine limits
 * included; it is written into the trace profile and Phase 4 stores it
 * untouched in {@code bazel_critical_path}. The derived one is what the
 * dependency graph alone implies, as if every action had started the instant
 * its inputs existed. A build where the two are close was limited by its
 * dependencies; a build where Bazel's is much longer was limited by something
 * else — jobs, cores, a remote queue — and that gap is the most useful thing on
 * this screen. Collapsing them into one number destroys precisely that.
 *
 * <p>ADR-009 requires both to survive separately, and they arrive from
 * different sources with no join between them: Bazel's components identify
 * themselves by a progress message and nothing else, so they cannot be matched
 * to the actions table. That is a second, harder reason not to merge them —
 * there is no key on which a merge could even be attempted.
 *
 * @param bazelReportedMicros Bazel's own total, from {@code BuildMetrics} or
 *     the profile
 * @param bazelComponents the components Bazel listed, in its order
 * @param derived this application's computation over the action graph, absent
 *     when there is no graph to compute it over
 */
public record CriticalPaths(
        Measured<Long> bazelReportedMicros,
        List<BazelComponent> bazelComponents,
        Optional<CriticalPath.Result> derived) {

    public CriticalPaths {
        Objects.requireNonNull(bazelReportedMicros, "bazelReportedMicros");
        bazelComponents = List.copyOf(bazelComponents);
        Objects.requireNonNull(derived, "derived");
    }

    /** Neither path is available. */
    public static CriticalPaths none(String whyBazelMissing) {
        return new CriticalPaths(
                Measured.unknown(DataSource.PROFILE, Completeness.UNAVAILABLE, whyBazelMissing),
                List.of(),
                Optional.empty());
    }

    /** One entry of Bazel's own critical path, exactly as Bazel worded it. */
    public record BazelComponent(
            int ordinal, String description, OptionalLong durationMicros) {

        public BazelComponent {
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(durationMicros, "durationMicros");
        }
    }

    /** The name Bazel's path may be shown under, and only that name. */
    public String bazelDisplayName() {
        return "Bazel-reported critical path";
    }

    /**
     * The name the derived path may be shown under.
     *
     * <p>Plan 13.4 fixes this wording: "Visualizer-computed dependency critical
     * path". It comes from {@link CriticalPath.Result#displayName()} so there is
     * one place it is written.
     */
    public String derivedDisplayName() {
        return derived.map(CriticalPath.Result::displayName)
                .orElse("Visualizer-computed dependency critical path");
    }

    /** True when both paths have a number, which is the only case worth comparing. */
    public boolean bothAvailable() {
        return bazelReportedMicros.isKnown()
                && derived.filter(result -> result.outcome() == CriticalPath.Outcome.COMPUTED)
                        .isPresent();
    }

    /**
     * Bazel's total minus the derived one, when both exist.
     *
     * <p>Deliberately signed and deliberately not called a "difference in
     * accuracy". A positive gap means the build took longer than its
     * dependencies required, which points at scheduling; a negative one means
     * the derived path is longer than what Bazel measured, which happens when
     * the graph contains actions this invocation did not execute — a cache hit
     * has a dependency edge and no execution time — and is a statement about
     * coverage rather than about the build.
     */
    public OptionalLong schedulingGapMicros() {
        if (!bothAvailable()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(
                bazelReportedMicros.value().orElseThrow() - derived.orElseThrow().makespanMicros());
    }

    /**
     * What must be said whenever either number is shown.
     *
     * <p>Always names both, including when only one exists, because "critical
     * path: 4.2 s" with no qualifier is the sentence this whole type exists to
     * make unwritable.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(bazelDisplayName()).append(": ");
        if (bazelReportedMicros.isKnown()) {
            text.append(bazelReportedMicros.value().orElseThrow() / 1000).append(" ms across ")
                    .append(bazelComponents.size()).append(" components");
        } else {
            text.append("not reported")
                    .append(bazelReportedMicros.warning().map(why -> " (" + why + ")").orElse(""));
        }
        text.append(". ").append(derivedDisplayName()).append(": ");
        if (derived.isEmpty()) {
            text.append("not computed — there is no imported action graph to compute it over");
        } else {
            text.append(derived.orElseThrow().describe());
        }
        if (bothAvailable()) {
            long gap = schedulingGapMicros().orElseThrow();
            text.append(" The two differ by ").append(Math.abs(gap) / 1000)
                    .append(" ms; they measure different things and a difference is expected.");
        }
        return text.toString();
    }
}
