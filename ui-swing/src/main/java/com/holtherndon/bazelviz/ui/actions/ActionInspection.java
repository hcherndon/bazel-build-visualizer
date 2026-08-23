package com.holtherndon.bazelviz.ui.actions;

import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import java.util.List;

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
        return of(row, List.of());
    }

    /**
     * The action, plus what the execution log says about the spawns that ran
     * it.
     *
     * <p>Attempts are shown as their own sections rather than folded into the
     * action's timing, because they are a different measurement: the action's
     * start and end come from the BEP, the attempt's from the execution log,
     * and ADR-009 keeps both under names saying whose they are. An action whose
     * BEP duration and spawn duration disagree is telling the reader something,
     * and averaging them would tell them nothing.
     */
    public static Inspection of(ActionRow row, List<AttemptRow> attempts) {
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

        row.commandLine().ifPresent(argv -> builder.section("Command")
                .field("Arguments", describeArgv(argv)));

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

        addAttempts(builder, attempts);
        return builder.build();
    }

    /**
     * One section per spawn.
     *
     * <p>Numbered rather than named, because a spawn has no name of its own and
     * because an action with two of them has two answers to "where did it run"
     * — which is a fact about the build, not a presentation problem to smooth
     * over.
     */
    private static void addAttempts(Inspection.Builder builder, List<AttemptRow> attempts) {
        if (attempts.isEmpty()) {
            return;
        }
        for (int i = 0; i < attempts.size(); i++) {
            AttemptRow attempt = attempts.get(i);
            builder.section(attempts.size() == 1 ? "Execution" : "Execution " + (i + 1))
                    .field(EntityFormat.field("Runner", attempt.runner()))
                    .field("Cache hit", attempt.cacheHit() ? "yes" : "no")
                    .field(EntityFormat.durationField("Spawn time", attempt.elapsed()));
            for (var component : attempt.timing().measuredComponents()) {
                builder.field("  " + component.name(),
                        EntityFormat.count(component.micros()) + " \u00b5s");
            }
            attempt.correlationNote().ifPresent(note -> builder.field("Correlation", note));
        }
    }

    /**
     * The stored argv, one argument per numbered line.
     *
     * <p>Stored as a JSON array because an argument can contain a newline, and
     * shown numbered because that is the only way a reader can see where one
     * argument ends and the next begins in a {@code /bin/bash -c} script. The
     * text is never re-quoted into something that looks executable: the command
     * was not run through a shell (plan 22.2).
     *
     * <p>Secrets are masked before they reach the screen. docs/privacy.md says
     * the UI masks sensitive well-known fields by default, and this is the one
     * place a build's own credentials are shown a line at a time — a
     * {@code --remote_header} carrying a bearer token is an ordinary argument
     * and would otherwise be rendered in full. The display policy leaves paths
     * alone: the person at the keyboard already has the session on their disk,
     * and a path they cannot paste into a terminal is worse at the job the
     * inspector exists for.
     *
     * <p>A redactor per call rather than per session: the point here is masking
     * rather than linking, and a table of pseudonyms that outlived the panel
     * would be a table of secrets held for no reason.
     */
    private static String describeArgv(String json) {
        List<String> arguments = new Redactor(RedactionPolicy.forDisplay())
                .argv(JsonArgv.parse(json), "actions.command_line");
        if (arguments.isEmpty()) {
            // Not JSON this build understands. Showing it verbatim beats
            // showing nothing and beats guessing at its structure.
            return json;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                text.append("  ");
            }
            text.append('[').append(i).append("] ").append(arguments.get(i));
        }
        return text.toString();
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
