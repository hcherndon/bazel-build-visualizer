package com.holtherndon.bazelviz.storage.events;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One accepted raw event, ready to be written to {@code bep_events}.
 *
 * <p>The row id is deliberately absent: SQLite assigns it, in normalization
 * order, and a duplicate delivery consumes none. Assigning ids in Java would
 * mean burning an id on every retransmission and then having to explain the
 * holes.
 *
 * <p>{@code eventIdHash} and {@code eventMicros} are {@link OptionalLong}
 * because both are genuinely absent for some events — a progress event carries
 * no timestamp, and not every event has an id. Storing 0 for those would make
 * them read back as "the epoch" and "id hash zero" (plan 11.4).
 *
 * @param streamId {@code event_streams.id} this event arrived on
 * @param sequence BES sequence number, or the import ordinal for a file source
 * @param eventType payload-case number; 0 means none/unrecognized
 * @param eventIdHash low 64 bits of the canonical event-id hash, empty when
 *     the event carries no id
 * @param lastMessage whether the stream declared this its final message
 * @param childCount how many children the event announced
 * @param rawSegment journal segment holding the verbatim payload
 * @param rawOffset byte offset of the frame within that segment
 * @param rawLength payload length in bytes
 * @param decodeStatus how completely this build understood the payload
 * @param hasUnknownFields whether the payload carried unrecognized fields
 * @param eventMicros the event's own timestamp, empty when it carries none
 * @param receiveMicros when this application received the event; always known
 */
public record EventRecord(
        long streamId,
        long sequence,
        int eventType,
        OptionalLong eventIdHash,
        boolean lastMessage,
        int childCount,
        int rawSegment,
        long rawOffset,
        int rawLength,
        DecodeStatus decodeStatus,
        boolean hasUnknownFields,
        OptionalLong eventMicros,
        long receiveMicros) {

    public EventRecord {
        Objects.requireNonNull(eventIdHash, "eventIdHash");
        Objects.requireNonNull(decodeStatus, "decodeStatus");
        Objects.requireNonNull(eventMicros, "eventMicros");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative: " + sequence);
        }
        if (childCount < 0) {
            throw new IllegalArgumentException("childCount must not be negative: " + childCount);
        }
        if (rawSegment < 0) {
            throw new IllegalArgumentException("rawSegment must not be negative: " + rawSegment);
        }
        if (rawOffset < 0) {
            throw new IllegalArgumentException("rawOffset must not be negative: " + rawOffset);
        }
        if (rawLength < 0) {
            throw new IllegalArgumentException("rawLength must not be negative: " + rawLength);
        }
    }

    /** Where the verbatim payload lives in the raw journal. */
    public RawLocation rawLocation() {
        return new RawLocation(rawSegment, rawOffset, rawLength);
    }
}
