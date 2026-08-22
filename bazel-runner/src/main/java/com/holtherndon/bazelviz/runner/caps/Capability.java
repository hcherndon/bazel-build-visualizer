package com.holtherndon.bazelviz.runner.caps;

import java.util.List;
import java.util.Objects;

/**
 * A thing this application may want a Bazel binary to do, together with the
 * flag names that would do it (plan 7.1 {@code BazelCapabilityDetector}).
 *
 * <h2>Observed, never inferred</h2>
 *
 * <p>Every constant here names candidate flags rather than a version threshold,
 * because {@code docs/bazel-compatibility.md} makes observation the rule: forks,
 * release candidates and vendored builds all carry version strings that do not
 * predict their flags. The detector asks the binary what it accepts and records
 * the answer; nothing in this package compares version numbers.
 *
 * <h2>Aliases are ordered</h2>
 *
 * <p>{@link #flagNames()} is the compatibility table from plan 7.1 rule 5, most
 * preferred first. A capability is supported when <em>any</em> of its names is
 * accepted, and {@link BazelCapabilities#preferredFlag} returns the first one
 * that is — so a Bazel 6 that only has {@code --execution_log_binary_file} and a
 * Bazel 7 that also has {@code --execution_log_compact_file} both report the
 * execution log as available, and each is asked for it by the name it knows.
 *
 * <p>Observed on real binaries at Phase 2 (6.5.0, 7.6.1, 8.4.1, 9.2.0); see
 * {@code docs/bazel-compatibility.md} for the measured matrix.
 */
public enum Capability {

    /** Publish the event stream to a gRPC Build Event Service endpoint. */
    BES_BACKEND("bes_backend"),

    /**
     * Publish the four lifecycle events over {@code PublishLifecycleEvent}.
     * Negatable, and the launcher may turn it off: the lifecycle RPCs carry no
     * BEP payload, so a capture that does not want them loses nothing but the
     * build/invocation envelope timestamps.
     */
    BES_LIFECYCLE_EVENTS("bes_lifecycle_events"),

    /** Bound the wait for the BES upload to finish. */
    BES_TIMEOUT("bes_timeout"),

    /** Choose whether the build blocks on the BES upload completing. */
    BES_UPLOAD_MODE("bes_upload_mode"),

    /** Write the event stream to a local length-delimited protobuf file. */
    BEP_BINARY_FILE("build_event_binary_file"),

    /** Write the event stream to a local protobuf-JSON file. */
    BEP_JSON_FILE("build_event_json_file"),

    /** Choose whether the build blocks on the local BEP file being complete. */
    BEP_FILE_UPLOAD_MODE("build_event_binary_file_upload_mode"),

    /**
     * Emit an event for every action rather than only for failed ones. This is
     * the flag that turns the action tables from "what broke" into "what ran",
     * and it is the single largest driver of event volume.
     */
    PUBLISH_ALL_ACTIONS("build_event_publish_all_actions"),

    /** Bound the size of a {@code NamedSetOfFiles} chunk. */
    BEP_NAMED_SET_LIMIT("build_event_max_named_set_of_file_entries"),

    /** Compact execution log (Bazel 7 and later). */
    EXECUTION_LOG_COMPACT("execution_log_compact_file"),

    /** Binary execution log, the pre-compact format. */
    EXECUTION_LOG_BINARY("execution_log_binary_file"),

    /** JSON execution log. Large; offered for interoperability, not for analysis. */
    EXECUTION_LOG_JSON("execution_log_json_file"),

    /**
     * Make Bazel 6.5.0 report spawn durations in the execution log.
     *
     * <p>Exists only on 6.5.0 and defaults to false. Without it that version's
     * log carries no metrics submessage at all; with it there are durations,
     * and still no spawn start on any setting (finding S2 in
     * docs/exec-log-and-profile.md).
     */
    EXECUTION_LOG_SPAWN_METRICS("experimental_execution_log_spawn_metrics"),

    /** Write a Chrome-trace JSON profile. */
    JSON_TRACE_PROFILE("generate_json_trace_profile"),

    /** Choose where the profile is written. */
    PROFILE_PATH("profile"),

    /** Keep the profile unabridged, so per-action spans survive. */
    UNSLIM_PROFILE("slim_profile"),

    /** Label profile spans with their target. */
    PROFILE_TARGET_LABELS("experimental_profile_include_target_label"),

    /**
     * Label profile spans with the output they produced.
     *
     * <p>The load-bearing one: {@code out} is the same key the BEP uses for
     * action identity, and it is the only thing tying a span to an action.
     * {@code args.target} was measured empty on 7.6.1 for the workspace-status
     * action, so the label flag is not a substitute (finding P4). Defaults to
     * false on all four supported versions.
     */
    PROFILE_PRIMARY_OUTPUT("experimental_profile_include_primary_output"),

    /** Ask {@code aquery} for protobuf output. */
    AQUERY_PROTO_OUTPUT("output"),

    /** Ask {@code cquery} for protobuf output. */
    CQUERY_PROTO_OUTPUT("output"),

    /** Memory profile, used by the metrics view rather than by the graph. */
    MEMORY_PROFILE("memory_profile");

    private final List<String> flagNames;

    Capability(String... flagNames) {
        this.flagNames = List.of(flagNames);
    }

    /** Candidate flag names, without leading dashes, most preferred first. */
    public List<String> flagNames() {
        return flagNames;
    }

    /** The name this capability is known by when nothing has been probed. */
    public String canonicalFlagName() {
        return flagNames.get(0);
    }

    /**
     * The Bazel command a capability is probed against. Flags are per-command in
     * {@code bazel help flags-as-proto}, and asking whether {@code build} accepts
     * {@code --output} would report the {@code aquery} capability as missing.
     */
    public String probeCommand() {
        return switch (this) {
            case AQUERY_PROTO_OUTPUT -> "aquery";
            case CQUERY_PROTO_OUTPUT -> "cquery";
            default -> "build";
        };
    }

    /** The capability whose flag has this name for {@code command}, if any. */
    public static java.util.Optional<Capability> forFlag(String flagName, String command) {
        Objects.requireNonNull(flagName, "flagName");
        String bare = flagName.startsWith("--") ? flagName.substring(2) : flagName;
        String noNegation = bare.startsWith("no") ? bare.substring(2) : bare;
        for (Capability capability : values()) {
            if (!capability.probeCommand().equals(command)) {
                continue;
            }
            if (capability.flagNames.contains(bare) || capability.flagNames.contains(noNegation)) {
                return java.util.Optional.of(capability);
            }
        }
        return java.util.Optional.empty();
    }
}
