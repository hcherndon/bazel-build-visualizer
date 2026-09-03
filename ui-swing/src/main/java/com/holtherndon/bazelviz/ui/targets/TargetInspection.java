package com.holtherndon.bazelviz.ui.targets;

import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.util.List;

/**
 * Describes a target for the shared inspector.
 *
 * <h2>Two outcomes, because there are two questions</h2>
 *
 * <p>"What did analysis say about this label" and "what happened when it was
 * built in this configuration" are different, and a target can have an answer
 * to the first and none to the second — which is the entire content of a build
 * interrupted during analysis. The inspector shows both under their own names
 * rather than merging them into one status that would be wrong for one of them.
 *
 * <h2>Tags keep their source</h2>
 *
 * <p>From Bazel 7.6.1 the completion event appends synthetic tags — the test
 * size, the timeout, {@code noflaky} — that the user never wrote in a BUILD
 * file. They are listed separately from the ones analysis reported, so a reader
 * can tell which are theirs.
 */
public final class TargetInspection {

    private TargetInspection() {}

    public static Inspection of(
            TargetRow target, List<TargetQueries.Tag> tags, List<TargetQueries.OutputGroup> groups) {
        Inspection.Builder builder = new Inspection.Builder(target.label())
                .subtitle(target.outcome().map(Enum::name)
                        .orElse(target.analysisOutcome().name() + ", never completed"))
                .sourceEvent(target.bepEventId());
        builder.ref(new EntityRef.TargetLabel(target.label()));
        target.configurationId().ifPresent(checksum ->
                builder.ref(new EntityRef.ConfigurationChecksum(checksum)));
        target.bepEventId().ifPresent(eventId ->
                builder.ref(new EntityRef.EventId(eventId)));

        builder.section("Target")
                .field("Label", target.label())
                .field(EntityFormat.field("Aspect", target.aspect()))
                .field(target.targetKind().isPresent()
                        ? Inspection.Field.of("Kind", target.targetKind().orElseThrow())
                        // From Bazel 7.6.1 an analysis-failed target emits no
                        // `configured` payload at all, so the kind is genuinely
                        // absent rather than merely unread.
                        : Inspection.Field.unknown("Kind",
                                "no analysis payload arrived for this target"))
                .field(EntityFormat.field("Test size", target.testSize()))
                .field("Analysis outcome", target.analysisOutcome().name());

        builder.section("Build")
                .field(EntityFormat.field("Configuration", target.configurationId()))
                .field(target.outcome().isPresent()
                        ? Inspection.Field.of("Outcome", target.outcome().orElseThrow().name())
                        : Inspection.Field.unknown("Outcome",
                                "no completion event arrived; the build stopped first"));

        if (!tags.isEmpty()) {
            builder.section("Tags");
            for (TargetQueries.Tag tag : tags) {
                builder.field(tag.tag(), "from " + tag.fromEvent().toLowerCase(java.util.Locale.ROOT)
                        + " event");
            }
        }

        if (!groups.isEmpty()) {
            builder.section("Output groups");
            for (TargetQueries.OutputGroup group : groups) {
                // `incomplete` is the flag that turns a total into a lower
                // bound, so it is said out loud rather than left to a colour.
                String detail = group.rootDepsetId()
                        .map(id -> "file set " + id)
                        .orElse("no file set");
                builder.field(
                        group.name(),
                        group.incomplete() ? detail + " — incomplete" : detail);
            }
        }
        return builder.build();
    }
}
