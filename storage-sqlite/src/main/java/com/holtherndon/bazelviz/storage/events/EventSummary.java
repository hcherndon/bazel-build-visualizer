package com.holtherndon.bazelviz.storage.events;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * One row of the chronological event table (plan 17.11), without the raw
 * location — that is fetched only for the selected event, because the table
 * scrolls through millions of rows and must not carry per-row detail it is not
 * showing.
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
        long receiveMicros) {

    public EventSummary {
        Objects.requireNonNull(eventIdHash, "eventIdHash");
        Objects.requireNonNull(decodeStatus, "decodeStatus");
        Objects.requireNonNull(eventMicros, "eventMicros");
    }
}
