package com.holtherndon.bazelviz.ui.actions;

import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.session.EntityReader;

/**
 * Describes an action for the shared inspector.
 *
 * <p>Pure: it takes a row and returns what to show. That keeps the awkward
 * parts — which exit code to display, why a duration is missing — testable
 * without a database or a screen, which matters because they are exactly the
 * places where the tool could quietly say something untrue.
 */
public final class ActionInspection {

    private ActionInspection() {}

    public static Inspection of(ActionRow row) {
        Inspection.Builder builder = new Inspection.Builder(
                        row.label().orElseGet(row::outputFileName))
                .subtitle(row.mnemonic().orElse("action") + " · " + row.outcome().name())
                .sourceEvent(row.bepEventId());

        builder.section("Identity")
                .field("Primary output", row.primaryOutput())
                .field(EntityFormat.field("Target", row.label()))
                .field(EntityFormat.field("Mnemonic", row.mnemonic()))
                .field(EntityFormat.field("Configuration", row.configurationId()));

        builder.section("Timing")
                .field(EntityFormat.durationField("Duration", row.duration()))
                .field(row.startMicros().isPresent()
                        ? Inspection.Field.of("Start", EntityFormat.count(row.startMicros()) + " µs")
                        : Inspection.Field.unknown("Start", startNote(row)));

        builder.section("Result").field("Outcome", row.outcome().name());
        if (row.outcome() == com.holtherndon.bazelviz.core.domain.ActionOutcome.FAILED) {
            // Two exit codes, labelled for what they are. Bazel's own field was
            // measured as 1 for every failure regardless of the process's real
            // status, so showing it alone would send the reader looking for a
            // failure that did not happen.
            builder.field(row.spawnExitCode().isPresent()
                            ? Inspection.Field.of("Exit code",
                                    Integer.toString(row.spawnExitCode().getAsInt()))
                            : Inspection.Field.unknown("Exit code",
                                    "Bazel did not report the process's exit code"))
                    .field(EntityFormat.field("Bazel's status code",
                            row.bazelExitCode().isPresent()
                                    ? java.util.Optional.of(
                                            Integer.toString(row.bazelExitCode().getAsInt()))
                                    : java.util.Optional.empty()))
                    .field(EntityFormat.field("Failure category", row.failureCategory()))
                    .field(EntityFormat.field("Message", row.failureMessage()));
        }
        return builder.build();
    }

    /**
     * Why an action has no start time.
     *
     * <p>The row already carries the reason its duration is unknown, and the
     * start is missing for the same reason, so the two never disagree.
     */
    private static String startNote(ActionRow row) {
        return row.duration().warning().orElse("not reported");
    }

    /** Reads one action and describes it, off the EDT. */
    public static Inspection load(EntityReader reader, long actionId) {
        return reader.action(actionId).map(ActionInspection::of).orElse(Inspection.NONE);
    }
}
