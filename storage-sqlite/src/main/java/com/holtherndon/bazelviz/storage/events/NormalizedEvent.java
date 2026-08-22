package com.holtherndon.bazelviz.storage.events;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything one raw event contributes to the database: the event row, the
 * identity it declares (if any), and the children it announces (if any).
 *
 * <p>Bundling them keeps the three writes ordered correctly — the event row
 * must exist before edges can reference it — without every caller having to
 * know that.
 *
 * @param event the {@code bep_events} row
 * @param identity the {@code bep_event_ids} row this event's own id defines,
 *     empty when the event carries no id
 * @param announcedChildHashes canonical hashes of the children this event
 *     announces, in announcement order; the list index becomes {@code ordinal}
 */
public record NormalizedEvent(
        EventRecord event, Optional<EventIdentity> identity, List<Long> announcedChildHashes) {

    public NormalizedEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(identity, "identity");
        announcedChildHashes = List.copyOf(announcedChildHashes);
    }

    /** An event with no id of its own and no announced children. */
    public static NormalizedEvent of(EventRecord event) {
        return new NormalizedEvent(event, Optional.empty(), List.of());
    }

    /** An event carrying its own identity but announcing no children. */
    public static NormalizedEvent of(EventRecord event, EventIdentity identity) {
        return new NormalizedEvent(event, Optional.of(identity), List.of());
    }
}
