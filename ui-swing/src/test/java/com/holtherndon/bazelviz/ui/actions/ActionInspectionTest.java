package com.holtherndon.bazelviz.ui.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the inspector says about an action, and what it refuses to say. */
class ActionInspectionTest {

    @Test
    @DisplayName("the exit code shown is the process's, with Bazel's beside it")
    void theProcessExitCodeIsTheHeadline() {
        ActionRow failed = new ActionRow(
                7,
                "bazel-out/bin/pkg/out.txt",
                Optional.of("//pkg:boom"),
                Optional.of("Genrule"),
                ActionOutcome.FAILED,
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.of(ActionTiming.NOT_REPORTED),
                OptionalInt.of(1),
                OptionalInt.of(7),
                Optional.of("spawn/NON_ZERO_EXIT"),
                Optional.of("Action failed: /bin/sh -c 'exit 7'"),
                Optional.of("cfg-1"),
                Optional.of("[\"/bin/sh\",\"-c\",\"exit 7\"]"),
                OptionalLong.of(900),
                ActionRow.Execution.none());

        Inspection inspection = ActionInspection.of(failed);

        // Bazel reported 1 for a command that exited 7. Showing 1 as "the exit
        // code" would send the reader looking for a failure that did not happen.
        assertThat(valueOf(inspection, "Exit code")).hasValue("7");
        assertThat(valueOf(inspection, "Bazel's status code")).hasValue("1");
        assertThat(failed.processExitCode()).hasValue(7);
        // The argv survives as arguments, not as one string: an argument may
        // contain a newline and joining on one destroys the boundaries.
        assertThat(valueOf(inspection, "Arguments"))
                .hasValue("[0] /bin/sh  [1] -c  [2] exit 7");
        assertThat(valueOf(inspection, "Failure category")).hasValue("spawn/NON_ZERO_EXIT");
        assertThat(inspection.sourceEventId()).hasValue(900L);
    }

    @Test
    @DisplayName("an untimed action explains itself rather than showing zero")
    void untimedActionsExplainThemselves() {
        ActionRow untimed = new ActionRow(
                3,
                "bazel-out/stable-status.txt",
                Optional.empty(),
                Optional.empty(),
                ActionOutcome.SUCCEEDED,
                OptionalLong.empty(),
                OptionalLong.empty(),
                Optional.of(ActionTiming.NOT_REPORTED),
                OptionalInt.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("system"),
                Optional.empty(),
                OptionalLong.empty(),
                ActionRow.Execution.none());

        Inspection inspection = ActionInspection.of(untimed);

        // Says only what the row knows. Two different facts produce an untimed
        // action -- a Bazel that reports no action timestamps at all, and an
        // action that ran no spawn on one that does -- and the row cannot tell
        // them apart, so it must not pick one.
        assertThat(noteOf(inspection, "Duration"))
                .hasValue("Bazel reported no start or end time for it");
        assertThat(valueOf(inspection, "Target")).isEmpty();
        assertThat(noteOf(inspection, "Start")).isPresent();
        // The workspace-status action has no label on three of the four
        // supported versions, so the title falls back to its output.
        assertThat(inspection.title()).isEqualTo("stable-status.txt");
        assertThat(inspection.sourceEventId()).isEmpty();
    }

    @Test
    @DisplayName("a zero-length span is unknown, and says which Bazel does that")
    void zeroLengthSpansAreExplained() {
        ActionRow zero = new ActionRow(
                4,
                "bazel-out/bin/a.o",
                Optional.of("//pkg:a"),
                Optional.of("CppCompile"),
                ActionOutcome.SUCCEEDED,
                OptionalLong.of(5_000),
                OptionalLong.empty(),
                Optional.of(ActionTiming.ZERO_LENGTH_SPAN),
                OptionalInt.empty(),
                OptionalInt.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("cfg-1"),
                Optional.empty(),
                OptionalLong.of(12),
                ActionRow.Execution.none());

        assertThat(noteOf(ActionInspection.of(zero), "Duration"))
                .hasValueSatisfying(note -> assertThat(note).contains("8.4.x"));
    }

    private static Optional<String> valueOf(Inspection inspection, String field) {
        return fieldOf(inspection, field).flatMap(Inspection.Field::value);
    }

    private static Optional<String> noteOf(Inspection inspection, String field) {
        return fieldOf(inspection, field).flatMap(Inspection.Field::unknownNote);
    }

    private static Optional<Inspection.Field> fieldOf(Inspection inspection, String name) {
        List<Inspection.Section> sections = inspection.sections();
        return sections.stream()
                .flatMap(section -> section.fields().stream())
                .filter(field -> field.name().equals(name))
                .findFirst();
    }
}
