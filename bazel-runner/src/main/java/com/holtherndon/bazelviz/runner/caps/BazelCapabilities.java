package com.holtherndon.bazelviz.runner.caps;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What one Bazel binary was observed to support (plan 7.1
 * {@code BazelCapabilityDetector}).
 *
 * <p>Immutable, and cached by the detector under the resolved executable, its
 * version and the startup options that could change it — startup options select
 * a different server, and a different server can have different flags.
 *
 * <h2>Reading this correctly</h2>
 *
 * <p>{@link #status} answers "may the planner inject this", and it answers
 * {@link CapabilityStatus#UNKNOWN} when the probe failed. {@link #flag} answers
 * "what did the binary say about this flag name", and is empty both for a flag
 * that does not exist and for a probe that never ran — which is why the status
 * carries {@link #detection()} alongside it. A UI that shows capability status
 * must show the detection method too, or a user cannot tell "your Bazel lacks
 * this" from "we could not ask".
 */
public record BazelCapabilities(
        String versionOutput,
        Optional<String> bazelVersion,
        DetectionMethod detection,
        Map<String, FlagSpec> flags,
        Map<Capability, CapabilityStatus> statuses,
        List<String> probeWarnings) {

    /** How the flag table was obtained. */
    public enum DetectionMethod {
        /** {@code bazel help flags-as-proto}: exact, structured, per-command. */
        FLAGS_PROTO,
        /**
         * {@code bazel help <command> --long}: text, and therefore weaker. It can
         * tell that a flag exists but not reliably whether it takes a value, so
         * every {@link FlagSpec} it produces leaves {@code requiresValue} absent.
         */
        HELP_TEXT,
        /** Nothing was probed; every capability is {@link CapabilityStatus#UNKNOWN}. */
        NONE
    }

    public BazelCapabilities {
        Objects.requireNonNull(versionOutput, "versionOutput");
        bazelVersion = Objects.requireNonNull(bazelVersion, "bazelVersion");
        Objects.requireNonNull(detection, "detection");
        flags = Map.copyOf(Objects.requireNonNull(flags, "flags"));
        statuses = Map.copyOf(Objects.requireNonNull(statuses, "statuses"));
        probeWarnings = List.copyOf(Objects.requireNonNull(probeWarnings, "probeWarnings"));
    }

    /**
     * A capability set for a binary nothing is known about: every capability
     * {@link CapabilityStatus#UNKNOWN}, so the planner injects nothing and says
     * why. This is what a failed probe produces — never an empty-but-successful
     * set, which would read as "your Bazel supports nothing".
     */
    public static BazelCapabilities unprobed(String reason) {
        Map<Capability, CapabilityStatus> statuses = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            statuses.put(capability, CapabilityStatus.UNKNOWN);
        }
        return new BazelCapabilities(
                "", Optional.empty(), DetectionMethod.NONE, Map.of(), statuses, List.of(reason));
    }

    /**
     * Derives capability statuses from an observed flag table.
     *
     * @param flags every flag the binary reported, keyed by name without dashes
     */
    public static BazelCapabilities fromFlags(
            String versionOutput,
            Optional<String> bazelVersion,
            DetectionMethod detection,
            Map<String, FlagSpec> flags,
            List<String> probeWarnings) {
        Map<Capability, CapabilityStatus> statuses = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            boolean found = capability.flagNames().stream()
                    .map(flags::get)
                    .filter(Objects::nonNull)
                    .anyMatch(spec -> spec.appliesTo(capability.probeCommand()));
            statuses.put(capability, found ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED);
        }
        return new BazelCapabilities(
                versionOutput,
                bazelVersion,
                detection,
                new LinkedHashMap<>(flags),
                statuses,
                probeWarnings);
    }

    public CapabilityStatus status(Capability capability) {
        return statuses.getOrDefault(capability, CapabilityStatus.UNKNOWN);
    }

    public boolean supports(Capability capability) {
        return status(capability).isSupported();
    }

    public Optional<FlagSpec> flag(String flagName) {
        return Optional.ofNullable(flags.get(flagName));
    }

    /**
     * The name this binary accepts for {@code capability}, most preferred first,
     * or empty when it accepts none of them.
     *
     * <p>This is what makes the alias table load-bearing rather than decorative:
     * the planner asks for a capability and is told which spelling to use, so a
     * flag renamed between Bazel versions costs one table entry instead of a
     * version comparison at every call site.
     */
    public Optional<String> preferredFlag(Capability capability) {
        for (String name : capability.flagNames()) {
            FlagSpec spec = flags.get(name);
            if (spec != null && spec.appliesTo(capability.probeCommand())) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /** True when the probe produced no flag table at all. */
    public boolean isUnprobed() {
        return detection == DetectionMethod.NONE;
    }
}
