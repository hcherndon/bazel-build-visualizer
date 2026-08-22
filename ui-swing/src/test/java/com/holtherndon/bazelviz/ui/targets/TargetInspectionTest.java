package com.holtherndon.bazelviz.ui.targets;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.domain.TargetOutcome;
import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the inspector says about a target. */
class TargetInspectionTest {

    @Test
    @DisplayName("a target that never completed says so instead of claiming an outcome")
    void neverCompletedTargetsSaySo() {
        TargetRow configuredOnly = new TargetRow(
                1,
                "//app:main",
                Optional.empty(),
                Optional.of("java_binary rule"),
                Optional.empty(),
                TargetOutcome.CONFIGURED,
                Optional.empty(),
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.of(12));

        Inspection inspection = TargetInspection.of(configuredOnly, List.of(), List.of());

        // An interrupt during analysis produces a build made entirely of these.
        // Showing a blank outcome would read as a rendering gap.
        assertThat(inspection.subtitle()).hasValue("CONFIGURED, never completed");
        assertThat(noteOf(inspection, "Outcome"))
                .hasValueSatisfying(note -> assertThat(note).contains("the build stopped first"));
    }

    @Test
    @DisplayName("a target with no analysis payload says its kind is unknown, not blank")
    void missingKindIsExplained() {
        TargetRow analysisFailed = new TargetRow(
                2,
                "//app:broken",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TargetOutcome.ABORTED,
                Optional.of("cfg"),
                Optional.of(TargetOutcome.FAILED),
                OptionalLong.of(5),
                OptionalLong.of(30));

        assertThat(noteOf(TargetInspection.of(analysisFailed, List.of(), List.of()), "Kind"))
                .hasValueSatisfying(note -> assertThat(note).contains("no analysis payload"));
    }

    @Test
    @DisplayName("a tag says which event supplied it")
    void tagsKeepTheirSource() {
        TargetRow row = built();
        List<TargetQueries.Tag> tags = List.of(
                new TargetQueries.Tag("manual", "CONFIGURED"),
                new TargetQueries.Tag("small", "COMPLETED"));

        Inspection inspection = TargetInspection.of(row, tags, List.of());

        // From Bazel 7.6.1 the completion event appends tags the user never
        // wrote. Merging them would show `small` as though it were in a BUILD
        // file.
        assertThat(valueOf(inspection, "manual")).hasValue("from configured event");
        assertThat(valueOf(inspection, "small")).hasValue("from completed event");
    }

    @Test
    @DisplayName("an incomplete output group says so")
    void incompleteOutputGroupsAreCalledOut() {
        Inspection inspection = TargetInspection.of(
                built(),
                List.of(),
                List.of(new TargetQueries.OutputGroup("default", true, Optional.of("58"))));

        // The flag turns any roll-up under the group into a lower bound, so it
        // is said out loud rather than left to a colour.
        assertThat(valueOf(inspection, "default"))
                .hasValueSatisfying(text -> assertThat(text).contains("incomplete"));
    }

    private static TargetRow built() {
        return new TargetRow(
                3,
                "//pkg:lib",
                Optional.empty(),
                Optional.of("java_library rule"),
                Optional.empty(),
                TargetOutcome.CONFIGURED,
                Optional.of("cfg-1"),
                Optional.of(TargetOutcome.BUILT),
                OptionalLong.of(9),
                OptionalLong.of(41));
    }

    private static Optional<String> valueOf(Inspection inspection, String name) {
        return field(inspection, name).flatMap(Inspection.Field::value);
    }

    private static Optional<String> noteOf(Inspection inspection, String name) {
        return field(inspection, name).flatMap(Inspection.Field::unknownNote);
    }

    private static Optional<Inspection.Field> field(Inspection inspection, String name) {
        return inspection.sections().stream()
                .flatMap(section -> section.fields().stream())
                .filter(f -> f.name().equals(name))
                .findFirst();
    }
}
