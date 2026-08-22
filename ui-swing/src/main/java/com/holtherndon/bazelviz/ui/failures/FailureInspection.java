package com.holtherndon.bazelviz.ui.failures;

import com.holtherndon.bazelviz.storage.entities.FailureRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;

/**
 * Describes a failure for the shared inspector.
 *
 * <p>The message is Bazel's own text, shown verbatim. It is never parsed — its
 * wording changes between versions — and for a compiler error it is often the
 * only structured thing there is, because a syntax error produces thirteen
 * events and zero structured diagnostics.
 */
public final class FailureInspection {

    private FailureInspection() {}

    public static Inspection of(FailureRow row) {
        Inspection.Builder builder = new Inspection.Builder(row.subject())
                .subtitle(row.kind().title())
                .sourceEvent(row.bepEventId());

        builder.section("Failure")
                .field("Kind", row.kind().title())
                .field("Subject", row.subject())
                .field(EntityFormat.field(
                        row.kind() == FailureRow.Kind.NOT_BUILT ? "Reason" : "Category",
                        row.detail()))
                .field(EntityFormat.field("Message", row.message()));

        if (row.kind() == FailureRow.Kind.NOT_BUILT) {
            // Worth saying out loud: under --nokeep_going this row is a
            // statement about a sibling's failure, not about this target.
            builder.section("What this means").field(
                    "Not built",
                    "Bazel did not attempt this target. Under --nokeep_going one broken target"
                            + " aborts its siblings, so this may say more about another target"
                            + " than about this one.");
        }
        return builder.build();
    }
}
