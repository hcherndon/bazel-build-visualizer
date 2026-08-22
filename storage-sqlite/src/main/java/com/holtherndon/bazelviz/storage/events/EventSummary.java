package com.holtherndon.bazelviz.storage.events;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One row of the chronological event table (plan 17.11).
 *
 * <p>Carries the raw location and the id display because the view needs both
 * per row. They were briefly excluded, which forced one point lookup per row
 * on top of the page query — 200 extra round trips per page. The location is
 * twelve bytes and travels with the row; only the payload <em>bytes</em> are
 * fetched on demand for the selected event, which is what plan 17.11 is
 * actually about.
 *
 * <p>{@code idDisplay} is empty when the event carries no {@code
 * BuildEventId}, which is a real state rather than a missing join.
 *
 * @param id row id, which is also normalization order
 */
public record EventSummary(
        long id,
        long streamId,
        long sequence,
        int eventType,
        OptionalLong eventIdHash,
        boolean lastMessage,
        int childCount,
        DecodeStatus decodeStatus,
        boolean hasUnknownFields,
        OptionalLong eventMicros,
        long receiveMicros,
        RawLocation rawLocation,
        Optional<String> idDisplay) {

    public EventSummary {
        Objects.requireNonNull(eventIdHash, "eventIdHash");
        Objects.requireNonNull(decodeStatus, "decodeStatus");
        Objects.requireNonNull(eventMicros, "eventMicros");
        Objects.requireNonNull(rawLocation, "rawLocation");
        Objects.requireNonNull(idDisplay, "idDisplay");
    }

    /** Size of the journaled payload, in bytes. */
    public int rawLength() {
        return rawLocation.length();
    }
}
