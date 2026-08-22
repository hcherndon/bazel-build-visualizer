package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything {@link InstrumentationPlanner} needs to produce a plan (plan 7.1
 * input list).
 *
 * <p>Planning is pure: the same request always yields the same plan, and
 * building one starts nothing. That is what lets the UI plan, show, let the
 * user veto a flag or resolve a conflict, and plan again — the second plan is
 * a new value, not a mutation of the first, so there is no state to get out of
 * step with the dialog.
 *
 * @param original the command as the user gave it
 * @param capabilities what the selected binary supports
 * @param preset how much instrumentation to ask for
 * @param sessionRawDirectory where session-local output files go; every
 *     generated path is under this and absolute (plan 8.4)
 * @param besEndpoint the embedded server's address, absent when it could not be
 *     started — in which case the plan says so rather than injecting a backend
 *     nothing is listening on
 * @param vetoed capabilities the user turned off in the dialog
 * @param resolutions the user's answers to conflicts, keyed by conflict kind
 * @param allowOverwrite whether an existing destination file may be replaced
 * @param effectiveOptions the options Bazel will really apply, rc files and
 *     {@code --config} expansion included, as
 *     {@code bazel canonicalize-flags} reports them. Absent when Bazel could
 *     not be asked — which is not the same as "no extra options", and the
 *     planner says so rather than assuming
 */
public record PlanRequest(
        BazelCommand original,
        BazelCapabilities capabilities,
        CapturePreset preset,
        Path sessionRawDirectory,
        Optional<String> besEndpoint,
        Set<Capability> vetoed,
        Map<PlanConflict.Kind, String> resolutions,
        boolean allowOverwrite,
        Optional<java.util.List<String>> effectiveOptions) {

    public PlanRequest {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(sessionRawDirectory, "sessionRawDirectory");
        besEndpoint = Objects.requireNonNull(besEndpoint, "besEndpoint");
        vetoed = Set.copyOf(Objects.requireNonNull(vetoed, "vetoed"));
        resolutions = Map.copyOf(Objects.requireNonNull(resolutions, "resolutions"));
        effectiveOptions = Objects.requireNonNull(effectiveOptions, "effectiveOptions")
                .map(java.util.List::copyOf);
    }

    /** A first plan: nothing vetoed, nothing resolved, no overwriting. */
    public static PlanRequest initial(
            BazelCommand original,
            BazelCapabilities capabilities,
            CapturePreset preset,
            Path sessionRawDirectory,
            Optional<String> besEndpoint) {
        return new PlanRequest(
                original, capabilities, preset, sessionRawDirectory, besEndpoint,
                Set.of(), Map.of(), false, Optional.empty());
    }

    /** The same request with one conflict resolved. */
    public PlanRequest resolving(PlanConflict.Kind kind, String resolutionId) {
        Map<PlanConflict.Kind, String> merged = new java.util.EnumMap<>(PlanConflict.Kind.class);
        merged.putAll(resolutions);
        merged.put(kind, resolutionId);
        return new PlanRequest(
                original, capabilities, preset, sessionRawDirectory, besEndpoint,
                vetoed, merged, allowOverwrite, effectiveOptions);
    }

    /** The same request with one capability turned off. */
    public PlanRequest vetoing(Capability capability) {
        Set<Capability> merged = java.util.EnumSet.noneOf(Capability.class);
        merged.addAll(vetoed);
        merged.add(capability);
        return new PlanRequest(
                original, capabilities, preset, sessionRawDirectory, besEndpoint,
                merged, resolutions, allowOverwrite, effectiveOptions);
    }

    /**
     * The same request pointed at a real session directory.
     *
     * <p>Planning happens twice: once before a session exists, to show the user
     * what will run, and once at launch against the directory that now does.
     * The second must be the first with one field changed — anything rebuilt
     * from parts loses whatever the user decided in between.
     */
    public PlanRequest inSession(Path realSessionRawDirectory) {
        return new PlanRequest(
                original, capabilities, preset, realSessionRawDirectory, besEndpoint,
                vetoed, resolutions, true, effectiveOptions);
    }

    /** The same request, told what Bazel will really apply. */
    public PlanRequest withEffectiveOptions(Optional<java.util.List<String>> options) {
        return new PlanRequest(
                original, capabilities, preset, sessionRawDirectory, besEndpoint,
                vetoed, resolutions, allowOverwrite, options);
    }

    /** The resolution the user chose for {@code kind}, if any. */
    public Optional<String> resolutionFor(PlanConflict.Kind kind) {
        return Optional.ofNullable(resolutions.get(kind));
    }
}
