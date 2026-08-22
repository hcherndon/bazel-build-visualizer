package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.runner.caps.Capability;
import java.util.EnumSet;
import java.util.Set;

/**
 * How much instrumentation to ask for (plan 4.2).
 *
 * <p>A preset is a <em>request</em>, never a promise: it names the capabilities
 * the planner should try to enable, and the planner drops any the selected
 * binary does not support, saying so in the plan. That is why the sets below
 * contain capabilities rather than flags — the spelling is the binary's business
 * (see {@link com.holtherndon.bazelviz.runner.caps.BazelCapabilities#preferredFlag}).
 *
 * <p>Phase 2 implements the mechanism and the BES/BEP entries. Profile,
 * execution-log and query capture are listed here because the presets are
 * defined by the plan as a whole, and the planner already reports them as
 * requested-but-not-yet-implemented rather than pretending a preset is smaller
 * than the plan says. Phases 4 and 5 fill in the catalog entries that turn them
 * into flags.
 */
public enum CapturePreset {

    /**
     * Preset A. Minimal perturbation: the event stream and the console, nothing
     * that writes another file or costs another analysis pass.
     */
    LIVE_ESSENTIALS(
            "Live Essentials",
            EnumSet.of(
                    Capability.BES_BACKEND,
                    Capability.PUBLISH_ALL_ACTIONS)),

    /**
     * Preset B, and the recommended default. Everything needed for the timing
     * and cache questions people actually open this tool to answer.
     */
    PERFORMANCE_DIAGNOSTICS(
            "Performance Diagnostics",
            EnumSet.of(
                    Capability.BES_BACKEND,
                    Capability.PUBLISH_ALL_ACTIONS,
                    Capability.EXECUTION_LOG_COMPACT,
                    Capability.JSON_TRACE_PROFILE,
                    Capability.PROFILE_PATH,
                    Capability.UNSLIM_PROFILE,
                    Capability.PROFILE_TARGET_LABELS,
                    Capability.AQUERY_PROTO_OUTPUT)),

    /**
     * Preset C. Adds the configured-target graph and complete edge
     * materialization. Must be presented with a disk, CPU and indexing-cost
     * warning (plan 4.2), which is why {@link #requiresCostWarning()} exists
     * rather than the UI hard-coding a comparison against this constant.
     */
    FULL_GRAPH_DIAGNOSTICS(
            "Full Graph Diagnostics",
            EnumSet.of(
                    Capability.BES_BACKEND,
                    Capability.PUBLISH_ALL_ACTIONS,
                    Capability.EXECUTION_LOG_COMPACT,
                    Capability.JSON_TRACE_PROFILE,
                    Capability.PROFILE_PATH,
                    Capability.UNSLIM_PROFILE,
                    Capability.PROFILE_TARGET_LABELS,
                    Capability.AQUERY_PROTO_OUTPUT,
                    Capability.CQUERY_PROTO_OUTPUT)),

    /** Preset D. Every source configured individually by the user. */
    CUSTOM("Custom", EnumSet.noneOf(Capability.class));

    private final String displayName;
    private final Set<Capability> requested;

    CapturePreset(String displayName, Set<Capability> requested) {
        this.displayName = displayName;
        this.requested = Set.copyOf(requested);
    }

    public String displayName() {
        return displayName;
    }

    /** The capabilities this preset asks for, before any are ruled out. */
    public Set<Capability> requestedCapabilities() {
        return requested;
    }

    /** The plan's recommended default (plan 4.2, Preset B). */
    public static CapturePreset defaultPreset() {
        return PERFORMANCE_DIAGNOSTICS;
    }

    /** True when the UI must show a prominent disk/CPU/indexing-cost warning. */
    public boolean requiresCostWarning() {
        return this == FULL_GRAPH_DIAGNOSTICS;
    }
}
