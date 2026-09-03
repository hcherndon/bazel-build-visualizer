package com.holtherndon.bazelviz.capture.normalize;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.holtherndon.bazelviz.bepcodec.BepEventDecoder;
import com.holtherndon.bazelviz.bepcodec.BepPayloadType;
import com.holtherndon.bazelviz.bepcodec.BesEnvelope;
import com.holtherndon.bazelviz.bepcodec.BesEnvelopeDecoder;
import com.holtherndon.bazelviz.bepcodec.DecodeResult;
import com.holtherndon.bazelviz.bepcodec.EventIdDisplay;
import com.holtherndon.bazelviz.bepcodec.EventIdKey;
import com.holtherndon.bazelviz.capture.file.json.JsonBuildEventDecoder;
import com.holtherndon.bazelviz.capture.file.json.JsonDecodeResult;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.format.journal.JournalLocation;
import com.holtherndon.bazelviz.storage.events.EventIdentity;
import com.holtherndon.bazelviz.storage.events.EventRecord;
import com.holtherndon.bazelviz.storage.events.NormalizedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Turns one journaled payload into the rows the schema wants: the {@code bep_events} row, its
 * canonical identity, and the hashes of the children it announces.
 *
 * <h2>Why this is one class used by two paths</h2>
 *
 * <p>Normalization runs both while reading a source and while replaying a journal after an
 * interruption. If those two paths derived event types, id hashes or decode statuses by separate
 * code, a resumed import could produce different rows from an uninterrupted one — and "the final
 * state equals an uninterrupted import" is a Phase 1 exit criterion. So both call exactly this,
 * with the same inputs: the payload bytes, the journal location, and the ordinal recorded in the
 * frame.
 *
 * <h2>A failed decode still produces a row</h2>
 *
 * <p>When decoding fails there is no event type, no id and no children, so those columns take their
 * unknown values ({@code event_type = 0}, {@code event_id_hash} NULL) — but the row is written
 * anyway, carrying the raw location. The bytes remain in the journal, verbatim, so a later build
 * with newer protos can reindex the same session and interpret what this one could not (plan 21.5).
 * Dropping the row would erase the evidence that anything was there at all.
 */
public final class EventNormalizer {

  private final BepEventDecoder binaryDecoder;
  private final JsonBuildEventDecoder jsonDecoder;
  private final BesEnvelopeDecoder envelopeDecoder;

  public EventNormalizer(int maxMessageBytes) {
    this.binaryDecoder =
        new BepEventDecoder(maxMessageBytes, BepEventDecoder.UnknownFieldScan.DEEP);
    this.jsonDecoder = new JsonBuildEventDecoder();
    this.envelopeDecoder = new BesEnvelopeDecoder(maxMessageBytes);
  }

  /**
   * @param sourceKind how to interpret the payload — binary protobuf or a JSON record. Taken from
   *     the journal frame rather than assumed, so a session that ever mixes sources stays readable
   * @param payload buffer holding the payload; not retained
   * @param ordinal the import ordinal, which is the event's {@code sequence}
   * @param location where the payload lives in the journal
   */
  public Normalization normalize(
      SourceKind sourceKind,
      byte[] payload,
      int offset,
      int length,
      long streamId,
      long ordinal,
      JournalLocation location,
      long receiveMicros) {
    Objects.requireNonNull(sourceKind, "sourceKind");
    Decoded decoded = decode(sourceKind, payload, offset, length);
    return build(decoded, streamId, ordinal, location, receiveMicros);
  }

  private Decoded decode(SourceKind sourceKind, byte[] payload, int offset, int length) {
    return switch (sourceKind) {
      case BEP_BINARY -> {
        DecodeResult result = binaryDecoder.decode(payload, offset, length);
        yield new Decoded(
            result.status(), result.event().orElse(null), result.failureDetail().orElse(null));
      }
      case BEP_JSON_RECORD -> {
        JsonDecodeResult result = jsonDecoder.decode(payload, offset, length);
        yield new Decoded(result.status(), result.event(), result.message());
      }
      case BES_ENVELOPE, BES_LIFECYCLE ->
          throw new IllegalArgumentException(
              "a "
                  + sourceKind
                  + " frame is a BES request, not a bare BuildEvent; "
                  + "use normalizeBesEnvelope so the envelope is unwrapped first");
    };
  }

  /**
   * Normalizes a journaled BES request.
   *
   * <p>Returns empty for the envelopes that carry no build event — lifecycle transitions, console
   * output, and the stream terminator. Those are accepted BES traffic and stay in the journal, but
   * they are not BEP events and must not become {@code bep_events} rows: a row with event type
   * "none" claims a build event arrived and could not be understood, which is a different statement
   * from "this was never a build event", and the two would be indistinguishable afterwards.
   *
   * <p>A malformed envelope <em>does</em> produce a row. The bytes were accepted, journaled and
   * acknowledged to Bazel, so the session has to account for them; the row carries {@link
   * DecodeStatus#FAILED} and the raw location, exactly as a malformed BEP record does.
   *
   * @param sourceKind {@link SourceKind#BES_ENVELOPE} or {@link SourceKind#BES_LIFECYCLE}, taken
   *     from the frame
   */
  public Optional<Normalization> normalizeBesEnvelope(
      SourceKind sourceKind,
      byte[] payload,
      int offset,
      int length,
      long streamId,
      long ordinal,
      JournalLocation location,
      long receiveMicros) {
    BesEnvelopeDecoder.Result result =
        switch (sourceKind) {
          case BES_ENVELOPE -> envelopeDecoder.decodeToolEvent(payload, offset, length);
          case BES_LIFECYCLE -> envelopeDecoder.decodeLifecycle(payload, offset, length);
          case BEP_BINARY, BEP_JSON_RECORD ->
              throw new IllegalArgumentException(
                  "a " + sourceKind + " frame is not a BES envelope; use normalize");
        };

    if (result.isFailed()) {
      return Optional.of(
          build(
              new Decoded(
                  DecodeStatus.FAILED,
                  null,
                  result.failureDetail().orElse("unreadable BES envelope")),
              streamId,
              ordinal,
              location,
              receiveMicros));
    }

    BesEnvelope envelope = result.envelope().orElseThrow();
    if (!envelope.kind().carriesBuildEvent()) {
      return Optional.empty();
    }

    ByteString inner = envelope.bazelEventBytes().orElseThrow();
    byte[] innerBytes = inner.toByteArray();
    DecodeResult decoded = binaryDecoder.decode(innerBytes, 0, innerBytes.length);
    return Optional.of(
        build(
            new Decoded(
                decoded.status(),
                decoded.event().orElse(null),
                decoded.failureDetail().orElse(null)),
            streamId,
            ordinal,
            location,
            receiveMicros));
  }

  /** Normalizes an event that has already been decoded, without decoding it again. */
  public Normalization fromDecoded(
      DecodeStatus status,
      BuildEvent event,
      String detail,
      long streamId,
      long ordinal,
      JournalLocation location,
      long receiveMicros) {
    return build(new Decoded(status, event, detail), streamId, ordinal, location, receiveMicros);
  }

  private Normalization build(
      Decoded decoded, long streamId, long ordinal, JournalLocation location, long receiveMicros) {
    BuildEvent event = decoded.event();
    DecodeStatus status = decoded.status();
    if (status == DecodeStatus.NOT_ATTEMPTED) {
      // Never persisted: a row claiming NOT_ATTEMPTED would say the import
      // declined to look, which is not what happened here.
      throw new IllegalStateException("normalization requires a decode to have been attempted");
    }

    int eventType = event == null ? BepPayloadType.NONE : BepPayloadType.of(event);
    boolean lastMessage = event != null && event.getLastMessage();
    int childCount = event == null ? 0 : event.getChildrenCount();

    OptionalLong idHash = OptionalLong.empty();
    Optional<EventIdentity> identity = Optional.empty();
    if (event != null && event.hasId()) {
      EventIdKey key = EventIdKey.of(event.getId());
      idHash = OptionalLong.of(key.eventIdHash());
      identity =
          Optional.of(
              new EventIdentity(
                  key.eventIdHash(),
                  key.idKind(),
                  key.idBytes().toByteArray(),
                  EventIdDisplay.of(event.getId())));
    }

    List<Long> children = List.of();
    if (event != null && childCount > 0) {
      List<Long> hashes = new ArrayList<>(childCount);
      for (BuildEventId child : event.getChildrenList()) {
        hashes.add(EventIdKey.of(child).eventIdHash());
      }
      children = hashes;
    }

    EventRecord record =
        new EventRecord(
            streamId,
            ordinal,
            eventType,
            idHash,
            lastMessage,
            childCount,
            location.segmentIndex(),
            location.frameOffset(),
            location.payloadLength(),
            status,
            status == DecodeStatus.UNKNOWN_FIELDS,
            eventMicros(event),
            receiveMicros);

    return new Normalization(
        new NormalizedEvent(record, identity, children),
        status,
        decoded.detail(),
        invocationId(event),
        Optional.ofNullable(event));
  }

  /**
   * The event's own timestamp, when it carries one.
   *
   * <p>{@code BuildEvent} has no timestamp field: only two payloads state a time this build can
   * read without interpretation — {@code BuildStarted} and {@code BuildFinished}. Every other event
   * reports unknown rather than borrowing the receive time, which is a different measurement from a
   * different source (plan 11.5) and would be indistinguishable afterwards.
   *
   * <p>The deprecated {@code *_time_millis} fields are read deliberately: they are what Bazel 6 and
   * 7 populate, and declining to read them would report "no timestamp" for every build produced by
   * a version this application explicitly supports.
   */
  @SuppressWarnings("deprecation") // start_time_millis / finish_time_millis, read on purpose
  private static OptionalLong eventMicros(BuildEvent event) {
    if (event == null) {
      return OptionalLong.empty();
    }
    return switch (event.getPayloadCase()) {
      case STARTED ->
          micros(
              event.getStarted().hasStartTime() ? event.getStarted().getStartTime() : null,
              event.getStarted().getStartTimeMillis());
      case FINISHED ->
          micros(
              event.getFinished().hasFinishTime() ? event.getFinished().getFinishTime() : null,
              event.getFinished().getFinishTimeMillis());
      default -> OptionalLong.empty();
    };
  }

  /**
   * Prefers the {@code Timestamp} field over the deprecated millis field, and treats a zero millis
   * value as absent rather than as the epoch: Bazel leaves the deprecated field unset when it
   * writes the new one, and reading that as 1970 would be an unavailable value shown as a number
   * (plan 11.4).
   */
  private static OptionalLong micros(Timestamp timestamp, long fallbackMillis) {
    if (timestamp != null && (timestamp.getSeconds() != 0 || timestamp.getNanos() != 0)) {
      return OptionalLong.of(timestamp.getSeconds() * 1_000_000L + timestamp.getNanos() / 1_000L);
    }
    return fallbackMillis != 0 ? OptionalLong.of(fallbackMillis * 1_000L) : OptionalLong.empty();
  }

  /** The invocation id, which only the {@code BuildStarted} event carries. */
  private static Optional<String> invocationId(BuildEvent event) {
    if (event == null || event.getPayloadCase() != BuildEvent.PayloadCase.STARTED) {
      return Optional.empty();
    }
    String uuid = event.getStarted().getUuid();
    return uuid.isEmpty() ? Optional.empty() : Optional.of(uuid);
  }

  /** The decode outcome, kept as three fields so a null event cannot be mistaken for success. */
  private record Decoded(DecodeStatus status, BuildEvent event, String detail) {}

  /**
   * One normalized record plus what the caller has to act on: the decode status (for a diagnostic),
   * the invocation id (for {@code event_streams}), and the decoded event itself.
   *
   * <p>The event is carried so that entity normalization (Phase 3) can run from the same decode
   * rather than parsing the payload a second time. Empty when the decode failed, which is why it is
   * an {@code Optional} rather than a nullable field: there is no event to hand on, and the
   * distinction has to survive being passed around.
   */
  public record Normalization(
      NormalizedEvent normalized,
      DecodeStatus status,
      String failureDetail,
      Optional<String> invocationId,
      Optional<BuildEvent> event) {}
}
