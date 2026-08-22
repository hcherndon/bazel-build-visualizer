package com.holtherndon.bazelviz.storage.entities;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Everything the overview shows, as one consistent read.
 *
 * <h2>Counted here, reported by Bazel there</h2>
 *
 * <p>Several numbers exist twice: this session's own row counts, and what
 * Bazel's {@code BuildMetrics} said. They are different measurements and they
 * legitimately disagree — {@code actionsExecuted} excludes cache hits, the
 * top-level {@code actionsCreated} does not equal the sum of the per-mnemonic
 * ones, and on Bazel 6.5 and 7.6 a fully-cached mnemonic vanishes from the
 * breakdown altogether. So each is carried under its own name and the view
 * labels them, rather than picking one and calling it "actions".
 *
 * <h2>Everything Bazel might not have said is optional</h2>
 *
 * <p>{@code criticalPathMicros} exists only on Bazel 9.2,
 * {@code executionPhaseMillis} not on 6.5, and the target and package counts
 * arrive empty from any build that failed during loading or analysis. A zero
 * for any of them would be a number the build never produced.
 *
 * @param bazelVersion what makes every other absence here readable
 * @param publishesAllActions absent means unknown; false means the actions
 *     table holds failures only, and can honestly say so
 * @param overallSuccess three states: succeeded, failed, or never reported —
 *     the last being a build that died before it could say
 * @param elapsedMicros what the user watched in the terminal
 * @param bazelWallMillis Bazel's own internal span, which was measured
 *     disagreeing with the above by up to a second
 */
public record OverviewSnapshot(
        Optional<String> bazelVersion,
        Optional<String> command,
        Optional<String> workspaceDirectory,
        Optional<Boolean> publishesAllActions,
        Optional<Boolean> overallSuccess,
        Optional<String> exitCodeName,
        OptionalLong elapsedMicros,
        boolean sawLastMessage,
        long targets,
        long configuredTargets,
        long targetsBuilt,
        long targetsFailed,
        long targetsNotCompleted,
        long actions,
        long actionsFailed,
        long tests,
        long testsFailed,
        long artifacts,
        long abortedEvents,
        OptionalLong bazelActionsCreated,
        OptionalLong bazelActionsExecuted,
        OptionalLong bazelCacheHits,
        OptionalLong bazelTargetsConfigured,
        OptionalLong bazelPackagesLoaded,
        OptionalLong bazelWallMillis,
        OptionalLong bazelCpuMillis,
        OptionalLong analysisPhaseMillis,
        OptionalLong executionPhaseMillis,
        OptionalLong criticalPathMicros,
        List<MnemonicWork> topMnemonics) {

    public OverviewSnapshot {
        Objects.requireNonNull(bazelVersion, "bazelVersion");
        topMnemonics = List.copyOf(topMnemonics);
    }

    /**
     * True when the build was known not to be publishing successful actions.
     *
     * <p>A statement about the options, not about the rows. Bazel publishes an
     * action event for a successful action only under
     * {@code --build_event_publish_all_actions}, so an imported BEP captured
     * without it has an actions table of failures and cache-miss stragglers —
     * which is the correct result rather than a bug, and which the view has to
     * be able to explain.
     *
     * <p>Absent (rather than false) when no {@code OptionsParsed} event arrived:
     * unknown is not the same as "the flag was off", and only the first of the
     * two justifies saying nothing.
     */
    public boolean actionsAreFailuresOnly() {
        return publishesAllActions.map(all -> !all).orElse(false);
    }

    /** Work Bazel attributed to one action type. */
    public record MnemonicWork(String mnemonic, OptionalLong created, OptionalLong executed) {}
}
