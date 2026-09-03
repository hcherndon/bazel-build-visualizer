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
public record RawBesEvent(
    SourceKind sourceKind, BesStreamKey stream, long sequence, long receiveMicros, byte[] payload) {

  public RawBesEvent {
    Objects.requireNonNull(sourceKind, "sourceKind");
    Objects.requireNonNull(stream, "stream");
    Objects.requireNonNull(payload, "payload");
    if (sourceKind != SourceKind.BES_ENVELOPE && sourceKind != SourceKind.BES_LIFECYCLE) {
      throw new IllegalArgumentException(
          "a BES event carries a BES source kind, got " + sourceKind);
    }
  }

  public int payloadLength() {
    return payload.length;
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
        + payload.length
        + "B]";
  }
}
