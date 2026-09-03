package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.bepcodec.EventIdDisplay;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One row of the chronological event table.
 *
 * <p>Optional-typed wherever the database column is nullable, so the renderer cannot accidentally
 * turn "not known" into a number. {@code childCount} and {@code eventType} are the interesting
 * cases: both are {@code NOT NULL} columns that the importer writes as {@code 0} when a record
 * failed to decode, because a row must exist for those bytes even though nothing could be read out
 * of them. Storing 0 is right — the column cannot be null — but <em>displaying</em> 0 would claim
 * the event had no payload and no children, which is not what was observed. {@link
 * #hasReadableStructure()} is what the renderer consults, and {@link EventTableColumns} renders an
 * em dash for those cells.
 *
 * @param rawLocation where the verbatim bytes live; the inspector reads them on demand and the
 *     table never does
 * @param idDisplay the canonical id rendering from {@code bep_event_ids}, empty when the event
 *     declared no id
 */
public record EventRow(
    long id,
    long sequence,
    int eventType,
    DecodeStatus decodeStatus,
    boolean hasUnknownFields,
    OptionalLong eventIdHash,
    Optional<String> idDisplay,
    int childCount,
    boolean lastMessage,
    RawLocation rawLocation,
    OptionalLong eventMicros,
    long receiveMicros) {

  public EventRow {
    Objects.requireNonNull(decodeStatus, "decodeStatus");
    Objects.requireNonNull(eventIdHash, "eventIdHash");
    Objects.requireNonNull(idDisplay, "idDisplay");
    Objects.requireNonNull(rawLocation, "rawLocation");
    Objects.requireNonNull(eventMicros, "eventMicros");
  }

  /**
   * Builds a row from a stored event.
   *
   * @param detail the row as the database holds it, raw location included
   */
  public static EventRow of(EventSummary summary) {
    Objects.requireNonNull(summary, "summary");
    return new EventRow(
        summary.id(),
        summary.sequence(),
        summary.eventType(),
        summary.decodeStatus(),
        summary.hasUnknownFields(),
        summary.eventIdHash(),
        summary.idDisplay(),
        summary.childCount(),
        summary.lastMessage(),
        summary.rawLocation(),
        summary.eventMicros(),
        summary.receiveMicros());
  }

  /** Convenience for callers that already hold a full detail row. */
  public static EventRow of(EventDetail detail) {
    Objects.requireNonNull(detail, "detail");
    return of(detail.summary());
  }

  /**
   * True when the record decoded, so its payload case and child count are observations rather than
   * placeholders.
   */
  public boolean hasReadableStructure() {
    return decodeStatus.hasEvent();
  }

  /** Payload length in bytes, always known: it is what the journal stored. */
  public int rawLength() {
    return rawLocation.length();
  }

  /**
   * The target label this event's id names, parsed at render time from the stored id display — an
   * explicit decision over widening the schema or the capture path. The grammar lives in {@code
   * EventIdDisplay}, next to the renderer that produced the string, so the two cannot drift apart.
   *
   * <p>Empty for the many events that are not about a target, and for a label the parse cannot
   * vouch for (cut by the display cap, or an absent marker) — the navigation actions built on this
   * show nothing rather than a fragment.
   */
  public Optional<String> targetLabel() {
    return idDisplay.flatMap(EventIdDisplay::labelOfDisplay);
  }
}
