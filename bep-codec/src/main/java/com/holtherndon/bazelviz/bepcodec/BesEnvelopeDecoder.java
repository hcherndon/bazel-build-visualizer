package com.holtherndon.bazelviz.bepcodec;

import com.google.devtools.build.v1.BuildEvent;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishLifecycleEventRequest;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads the BES request that a journal frame of kind {@code BES_ENVELOPE} or {@code BES_LIFECYCLE}
 * contains.
 *
 * <p>The journal stores the request exactly as it arrived on the wire, so this is where those bytes
 * become meaning. It stops at the envelope: the BEP event inside is handed back as bytes for {@link
 * BepEventDecoder}, which owns every decision about BEP decoding including the unknown-field scan.
 *
 * <h2>Size limits</h2>
 *
 * <p>Parsing is bounded by the same maximum the journal enforces (plan 21.3, "limit protobuf
 * message size"). The limit matters more here than for a file import: these bytes came off a
 * socket, and a length that says 2 GB is a reason to stop rather than an instruction to allocate.
 *
 * <p>Stateless and safe to share between threads.
 */
public final class BesEnvelopeDecoder {

  private final int maxMessageBytes;

  public BesEnvelopeDecoder(int maxMessageBytes) {
    if (maxMessageBytes <= 0) {
      throw new IllegalArgumentException(
          "maxMessageBytes must be positive, got " + maxMessageBytes);
    }
    this.maxMessageBytes = maxMessageBytes;
  }

  /**
   * The outcome of reading one envelope.
   *
   * @param status {@link DecodeStatus#OK} when the envelope parsed, {@link DecodeStatus#FAILED}
   *     when it did not. Unknown fields in the <em>envelope</em> are not reported: the envelope is
   *     a transport wrapper, and a newer BES adding a field to it says nothing about whether the
   *     build event inside is fully understood, which is what {@code has_unknown_fields} is asked
   *     about
   * @param envelope the parsed envelope, absent on failure
   * @param failureDetail why it failed, present only on failure
   */
  public record Result(
      DecodeStatus status, Optional<BesEnvelope> envelope, Optional<String> failureDetail) {

    public Result {
      Objects.requireNonNull(status, "status");
      envelope = Objects.requireNonNull(envelope, "envelope");
      failureDetail = Objects.requireNonNull(failureDetail, "failureDetail");
      if ((status == DecodeStatus.FAILED) == envelope.isPresent()) {
        throw new IllegalArgumentException(
            "status " + status + " disagrees with the envelope's presence");
      }
    }

    static Result ok(BesEnvelope envelope) {
      return new Result(DecodeStatus.OK, Optional.of(envelope), Optional.empty());
    }

    static Result failed(String detail) {
      return new Result(DecodeStatus.FAILED, Optional.empty(), Optional.of(detail));
    }

    public boolean isFailed() {
      return status == DecodeStatus.FAILED;
    }
  }

  /** Reads a {@code PublishBuildToolEventStreamRequest}. */
  public Result decodeToolEvent(byte[] payload, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, payload.length);
    if (length > maxMessageBytes) {
      return Result.failed(
          "BES envelope of "
              + length
              + " bytes exceeds the "
              + maxMessageBytes
              + "-byte limit; refusing to parse it");
    }
    try {
      CodedInputStream input = CodedInputStream.newInstance(payload, offset, length);
      input.setSizeLimit(maxMessageBytes);
      PublishBuildToolEventStreamRequest request =
          PublishBuildToolEventStreamRequest.parseFrom(input);
      return Result.ok(fromOrdered(request.getOrderedBuildEvent()));
    } catch (InvalidProtocolBufferException malformed) {
      return Result.failed("not a PublishBuildToolEventStreamRequest: " + malformed.getMessage());
    } catch (IOException | RuntimeException failure) {
      return Result.failed("could not read the BES envelope: " + failure);
    }
  }

  /** Reads a {@code PublishLifecycleEventRequest}. */
  public Result decodeLifecycle(byte[] payload, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, payload.length);
    if (length > maxMessageBytes) {
      return Result.failed(
          "BES lifecycle request of "
              + length
              + " bytes exceeds the "
              + maxMessageBytes
              + "-byte limit; refusing to parse it");
    }
    try {
      CodedInputStream input = CodedInputStream.newInstance(payload, offset, length);
      input.setSizeLimit(maxMessageBytes);
      PublishLifecycleEventRequest request = PublishLifecycleEventRequest.parseFrom(input);
      BesEnvelope envelope = fromOrdered(request.getBuildEvent());
      // A lifecycle request never carries a BEP event, whatever its oneof
      // arm turns out to be. Reporting the arm as LIFECYCLE keeps the
      // caller from having to know which arms are lifecycle arms.
      return Result.ok(
          new BesEnvelope(
              BesEnvelope.Kind.LIFECYCLE,
              envelope.buildId(),
              envelope.invocationId(),
              envelope.component(),
              envelope.sequence(),
              Optional.empty(),
              envelope.eventTimeMicros()));
    } catch (InvalidProtocolBufferException malformed) {
      return Result.failed("not a PublishLifecycleEventRequest: " + malformed.getMessage());
    } catch (IOException | RuntimeException failure) {
      return Result.failed("could not read the BES lifecycle request: " + failure);
    }
  }

  private static BesEnvelope fromOrdered(OrderedBuildEvent ordered) {
    StreamId streamId = ordered.getStreamId();
    BuildEvent event = ordered.getEvent();
    BesEnvelope.Kind kind = kindOf(event);
    return new BesEnvelope(
        kind,
        nonEmpty(streamId.getBuildId()),
        nonEmpty(streamId.getInvocationId()),
        nonEmpty(streamId.getComponent().name()),
        ordered.getSequenceNumber(),
        kind.carriesBuildEvent() ? Optional.of(event.getBazelEvent().getValue()) : Optional.empty(),
        eventTimeMicros(event));
  }

  private static BesEnvelope.Kind kindOf(BuildEvent event) {
    return switch (event.getEventCase()) {
      case BAZEL_EVENT -> BesEnvelope.Kind.BAZEL_EVENT;
      case COMPONENT_STREAM_FINISHED -> BesEnvelope.Kind.COMPONENT_STREAM_FINISHED;
      case CONSOLE_OUTPUT -> BesEnvelope.Kind.CONSOLE_OUTPUT;
      case INVOCATION_ATTEMPT_STARTED,
          INVOCATION_ATTEMPT_FINISHED,
          BUILD_ENQUEUED,
          BUILD_FINISHED ->
          BesEnvelope.Kind.LIFECYCLE;
      default -> BesEnvelope.Kind.OTHER;
    };
  }

  /**
   * The envelope's own timestamp. Absent when unset rather than reported as the epoch: Bazel leaves
   * {@code event_time} unset on some envelopes, and 1970 shown in a timeline is an unavailable
   * value rendered as a number (plan 11.4).
   */
  private static OptionalLong eventTimeMicros(BuildEvent event) {
    if (!event.hasEventTime()) {
      return OptionalLong.empty();
    }
    Timestamp time = event.getEventTime();
    if (time.getSeconds() == 0 && time.getNanos() == 0) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(time.getSeconds() * 1_000_000L + time.getNanos() / 1_000L);
  }

  private static Optional<String> nonEmpty(String value) {
    return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
  }
}
