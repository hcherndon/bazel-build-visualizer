package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.measure.Measured;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One row of the actions table.
 *
 * <p>Every optional here is a fact about the build rather than a gap in the
 * query. {@code label} is absent for the workspace-status action on three of
 * the four supported Bazel versions; {@code startMicros} is absent on two of
 * them entirely; {@code spawnExitCode} is absent unless the action failed as a
 * spawn that reported one.
 *
 * @param id the row id, which is also the keyset anchor
 * @param primaryOutput the action's identity, from its event id
 * @param durationUnknownReason why no duration is derivable, when none is
 * @param bepEventId the event this row came from, for the inspector
 */
public record ActionRow(
        long id,
        String primaryOutput,
        Optional<String> label,
        Optional<String> mnemonic,
        ActionOutcome outcome,
        OptionalLong startMicros,
        OptionalLong durationMicros,
        Optional<String> durationUnknownReason,
        OptionalInt bazelExitCode,
        OptionalInt spawnExitCode,
        Optional<String> failureCategory,
        Optional<String> failureMessage,
        Optional<String> configurationId,
        OptionalLong bepEventId) {

    public ActionRow {
        Objects.requireNonNull(primaryOutput, "primaryOutput");
        Objects.requireNonNull(outcome, "outcome");
    }

    /**
     * The duration, carrying why it is unavailable when it is (plan 11.4).
     *
     * <p>Built here rather than in the view so that every place showing a
     * duration shows the same thing: a number, or an explanation, and never a
     * zero standing in for either.
     */
    public Measured<Long> duration() {
        if (durationMicros.isPresent()) {
            return Measured.of(durationMicros.getAsLong(), DataSource.BEP);
        }
        String reason = durationUnknownReason.orElse(ActionTiming.NOT_REPORTED);
        return Measured.unknown(DataSource.BEP, Completeness.UNAVAILABLE, explain(reason));
    }

    /** The exit code worth showing: the process's, not Bazel's constant 1. */
    public OptionalInt effectiveExitCode() {
        return spawnExitCode.isPresent() ? spawnExitCode : bazelExitCode;
    }

    /** The output's file name, for a column too narrow for the path. */
    public String outputFileName() {
        int slash = primaryOutput.lastIndexOf('/');
        return slash < 0 ? primaryOutput : primaryOutput.substring(slash + 1);
    }

    private static String explain(String reason) {
        return switch (reason) {
            case ActionTiming.NOT_REPORTED ->
                    "this Bazel version does not report action timestamps";
            case ActionTiming.ZERO_LENGTH_SPAN ->
                    "Bazel reported the same time for start and end, which it does for every"
                            + " action on 8.4.x regardless of how long the action took";
            case ActionTiming.PARTIAL -> "only one of the two timestamps arrived";
            case ActionTiming.END_BEFORE_START -> "the reported end precedes the reported start";
            default -> reason;
        };
    }
}
