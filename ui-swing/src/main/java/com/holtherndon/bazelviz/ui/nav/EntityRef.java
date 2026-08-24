package com.holtherndon.bazelviz.ui.nav;

import java.util.Objects;

/**
 * A cross-view reference to one build entity: a target label, an action, or
 * an event.
 *
 * <p>This is the identity the plan's {@code SelectionService} promised
 * (product-plan section 7): views hand each other one of these rather than a
 * view-specific row index or a bare {@code long} whose meaning the receiver
 * has to remember. It is deliberately small — the three identities the views
 * navigate by today — and deliberately a value: an {@code EntityRef} carries
 * no reader, no executor and no way to block, so it can cross any boundary,
 * including onto the EDT.
 *
 * <p>A row usually yields more than one of these at once — an action row is
 * an action, belongs to a label, and came from an event — which is why
 * {@link EntityActions} takes a list of them rather than exactly one.
 */
public sealed interface EntityRef {

    /** A target label, exactly as Bazel spells it: {@code //pkg:name} or {@code @repo//pkg:name}. */
    record TargetLabel(String label) implements EntityRef {
        public TargetLabel {
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("a label ref needs a label");
            }
        }
    }

    /** An executed action, by its {@code actions} row id. */
    record ActionId(long id) implements EntityRef {}

    /** A stored BEP event, by its {@code bep_events} row id. */
    record EventId(long id) implements EntityRef {}
}
