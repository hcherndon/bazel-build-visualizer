package com.holtherndon.bazelviz.capture.bes;

import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.util.Objects;

/**
 * One envelope taken off the wire, still in its original bytes.
 *
 * <p>ADR-004 is the reason this record holds a {@code byte[]} and not a parsed message: the journal
 * must contain what Bazel sent, byte for byte, so that a later build with newer protos can
 * re-derive everything from the same session. The bytes here are the exact serialized request —
 * obtained through a raw gRPC marshaller, not by re-serializing a parsed object, because a protobuf
 * round-trip is not guaranteed to reproduce the input and "verbatim" would then be a claim the
 * format could not keep.
 *
 * <p>{@link #payload} is owned by this record and must not be mutated by anyone. It is not copied
 * on construction: these are allocated once per event at up to 100,000 events per second, and a
 * defensive copy at that rate is a measurable cost for a buffer that has exactly one producer and
 * one consumer.
 *
 * @param sourceKind {@link SourceKind#BES_ENVELOPE} for a build-tool event, {@link
 *     SourceKind#BES_LIFECYCLE} for a lifecycle request
 * @param stream which stream it belongs to
 * @param sequence the BES sequence number; for lifecycle requests this is the sequence carried by
 *     the lifecycle event itself, which is numbered independently of the tool stream
 * @param receiveMicros when this process received it, epoch micros — a receive time, deliberately
 *     distinct from any timestamp inside the payload (plan 11.5)
 * @param payload the serialized request, verbatim
 */
public final class RawBesEvent implements AutoCloseable {

  private final SourceKind sourceKind;
  private final BesStreamKey stream;
  private final long sequence;
  private final long receiveMicros;
  private final RawPayloadLease payload;

  /** Creates an unbudgeted event for direct imports and tests. */
  public RawBesEvent(
      SourceKind sourceKind,
      BesStreamKey stream,
      long sequence,
      long receiveMicros,
      byte[] payload) {
    this(
        sourceKind,
        stream,
        sequence,
        receiveMicros,
        RawPayloadLease.unbudgeted(Objects.requireNonNull(payload, "payload")));
  }

  /** Takes ownership of the transport's sole retained-payload lease without copying its bytes. */
  RawBesEvent(
      SourceKind sourceKind,
      BesStreamKey stream,
      long sequence,
      long receiveMicros,
      RawPayloadLease payload) {
    RawPayloadLease ownedPayload = Objects.requireNonNull(payload, "payload");
    try {
      this.sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
      this.stream = Objects.requireNonNull(stream, "stream");
      if (sourceKind != SourceKind.BES_ENVELOPE && sourceKind != SourceKind.BES_LIFECYCLE) {
        throw new IllegalArgumentException(
            "a BES event carries a BES source kind, got " + sourceKind);
      }
    } catch (RuntimeException invalid) {
      ownedPayload.close();
      throw invalid;
    }
    this.payload = ownedPayload;
    this.sequence = sequence;
    this.receiveMicros = receiveMicros;
  }

  public SourceKind sourceKind() {
    return sourceKind;
  }

  public BesStreamKey stream() {
    return stream;
  }

  public long sequence() {
    return sequence;
  }

  public long receiveMicros() {
    return receiveMicros;
  }

  /** The original serialized request. The receiver must not mutate this array. */
  public byte[] payload() {
    return payload.bytes();
  }

  public int payloadLength() {
    return payload.length();
  }

  /** Releases this payload's aggregate budget reservation. Idempotent. */
  @Override
  public void close() {
    payload.close();
  }

  /**
   * Identity is by reference. Records normally derive {@code equals} from their components, and for
   * a {@code byte[]} component that means array identity — which is nearly always the wrong answer
   * and is never asked for here. Stated explicitly so nobody has to work it out from the array's
   * presence.
   */
  @Override
  public boolean equals(Object other) {
    return this == other;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "RawBesEvent["
        + sourceKind
        + " "
        + stream
        + " #"
        + sequence
        + " "
        + payload.length()
        + "B]";
  }
}
