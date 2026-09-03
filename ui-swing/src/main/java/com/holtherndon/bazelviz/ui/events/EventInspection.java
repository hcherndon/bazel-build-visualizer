package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.ui.session.RawPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the raw-protobuf inspector is showing right now.
 *
 * <p>Four states rather than a nullable payload, because "nothing selected", "loading", "loaded"
 * and "could not be loaded" call for four different panes. Collapsing the last two — showing an
 * empty inspector when a journal read fails — would look identical to an event whose payload is
 * genuinely empty.
 *
 * @param state which of the four situations this is
 * @param eventId the row being inspected, empty only in {@link State#NONE}
 * @param row the event's stored columns, present once they have been read
 * @param payload the verbatim bytes, present once they have been read
 * @param rendered the readable form and any disclosures about it
 * @param hexDump the byte dump, already capped and annotated
 * @param loadFailure why nothing could be shown, present only in {@link State#FAILED}
 */
public record EventInspection(
    State state,
    OptionalLong eventId,
    Optional<EventRow> row,
    Optional<RawPayload> payload,
    Optional<RawPayloadRenderer.Rendered> rendered,
    Optional<String> hexDump,
    Optional<String> loadFailure) {

  /** Which of the inspector's four situations applies. */
  public enum State {
    /** No event is selected. */
    NONE,
    /** An event is selected and its bytes are being read. */
    LOADING,
    /** The event and its bytes are available. */
    LOADED,
    /** The event or its bytes could not be read; the reason is carried. */
    FAILED
  }

  public EventInspection {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(rendered, "rendered");
    Objects.requireNonNull(hexDump, "hexDump");
    Objects.requireNonNull(loadFailure, "loadFailure");
    if (state == State.FAILED && loadFailure.isEmpty()) {
      throw new IllegalArgumentException("a FAILED inspection must say why");
    }
    if (state == State.LOADED && (row.isEmpty() || payload.isEmpty())) {
      throw new IllegalArgumentException("a LOADED inspection must carry its row and bytes");
    }
  }

  /** Nothing selected. */
  public static EventInspection none() {
    return new EventInspection(
        State.NONE,
        OptionalLong.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /** Selected, bytes on the way. */
  public static EventInspection loading(long eventId) {
    return new EventInspection(
        State.LOADING,
        OptionalLong.of(eventId),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /** Selected and read. */
  public static EventInspection loaded(
      EventRow row, RawPayload payload, RawPayloadRenderer.Rendered rendered, String hexDump) {
    return new EventInspection(
        State.LOADED,
        OptionalLong.of(row.id()),
        Optional.of(row),
        Optional.of(payload),
        Optional.of(rendered),
        Optional.of(hexDump),
        Optional.empty());
  }

  /** Selected, and something went wrong reading it. */
  public static EventInspection failed(long eventId, String reason) {
    return new EventInspection(
        State.FAILED,
        OptionalLong.of(eventId),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(Objects.requireNonNull(reason, "reason")));
  }

  /**
   * The target label the inspected event is about, when that is known.
   *
   * <p>Preferred source first: the label read structurally from the decoded payload's id ({@link
   * RawPayloadRenderer.Rendered#targetLabel}). When the renderer had nothing structural — a JSON
   * record it does not re-decode — the stored id display is parsed instead. Empty whenever neither
   * can vouch for a label, which is what makes the inspector's label actions honestly absent rather
   * than blank.
   */
  public Optional<String> targetLabel() {
    Optional<String> structured = rendered.flatMap(RawPayloadRenderer.Rendered::targetLabel);
    if (structured.isPresent()) {
      return structured;
    }
    return row.flatMap(EventRow::targetLabel);
  }

  /**
   * True when the record's own bytes are on screen even though they could not be interpreted — the
   * case plan 21.5 exists for.
   */
  public boolean showsUndecodableBytes() {
    return state == State.LOADED
        && rendered.map(value -> value.decodeFailure().isPresent()).orElse(false);
  }
}
