package com.holtherndon.bazelviz.bepcodec;

import com.google.protobuf.ByteString;
import com.holtherndon.bazelviz.bepcodec.entity.ProtoTimes;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a BES request said, once its outer layers are peeled off.
 *
 * <h2>Why the inner bytes and not the inner message</h2>
 *
 * <p>{@link #bazelEventBytes} is the untouched {@code Any.value} carrying a {@code
 * build_event_stream.BuildEvent}. It is handed back as bytes so that the same {@link
 * BepEventDecoder} that reads a BEP file reads a BES event too — one decoder, one unknown-field
 * scan, one set of rules for what counts as a failure. Parsing it here as well would create a
 * second path that could drift from the first, and "a captured session and an imported session
 * produce identical rows" is a property this project checks.
 *
 * <h2>Not every envelope carries an event</h2>
 *
 * <p>The lifecycle requests, the console-output events and the final {@code
 * component_stream_finished} marker are all real, accepted BES traffic with no BEP payload inside
 * them. They are journaled like everything else (ADR-004) and they move stream state, but they
 * produce no {@code bep_events} row — a row whose event type was "none" would claim a build event
 * arrived and could not be decoded, which is a different and worse statement than "this was not a
 * build event".
 *
 * @param kind which arm of the envelope's oneof was set
 * @param buildId from {@code StreamId}, absent when the request carried none
 * @param invocationId from {@code StreamId}
 * @param component the {@code StreamId.BuildComponent} name
 * @param sequence the envelope's own sequence number
 * @param bazelEventBytes the inner BEP event, present only for {@link Kind#BAZEL_EVENT}
 * @param eventTimeMicros the envelope's {@code event_time}, which is Bazel's clock rather than ours
 *     and is therefore kept apart from receive time (plan 11.5)
 * @param eventTimeState whether that time was absent, validly present, or malformed
 */
public record BesEnvelope(
    Kind kind,
    Optional<String> buildId,
    Optional<String> invocationId,
    Optional<String> component,
    long sequence,
    Optional<ByteString> bazelEventBytes,
    OptionalLong eventTimeMicros,
    ProtoTimes.State eventTimeState) {

  /** Which arm of {@code google.devtools.build.v1.BuildEvent}'s oneof was set. */
  public enum Kind {
    /** Carries a BEP {@code BuildEvent}. The only kind that becomes a row. */
    BAZEL_EVENT,
    /** The stream's terminator. */
    COMPONENT_STREAM_FINISHED,
    /** Console text or bytes, published separately from the BEP stream. */
    CONSOLE_OUTPUT,
    /** A build or invocation lifecycle transition. */
    LIFECYCLE,
    /**
     * Something this build does not model — including an arm added by a newer Bazel. Reported
     * rather than treated as an error, because the bytes are already journaled and a future build
     * can interpret them (plan 21.5).
     */
    OTHER;

    /** True when an envelope of this kind contains a BEP event to normalize. */
    public boolean carriesBuildEvent() {
      return this == BAZEL_EVENT;
    }
  }

  public BesEnvelope {
    Objects.requireNonNull(kind, "kind");
    buildId = Objects.requireNonNull(buildId, "buildId");
    invocationId = Objects.requireNonNull(invocationId, "invocationId");
    component = Objects.requireNonNull(component, "component");
    bazelEventBytes = Objects.requireNonNull(bazelEventBytes, "bazelEventBytes");
    eventTimeMicros = Objects.requireNonNull(eventTimeMicros, "eventTimeMicros");
    eventTimeState = Objects.requireNonNull(eventTimeState, "eventTimeState");
    if (kind.carriesBuildEvent() != bazelEventBytes.isPresent()) {
      throw new IllegalArgumentException(
          "envelope kind " + kind + " and the presence of inner event bytes disagree");
    }
    if ((eventTimeState == ProtoTimes.State.PRESENT) != eventTimeMicros.isPresent()) {
      throw new IllegalArgumentException("event time state disagrees with its value");
    }
  }

  /** Compatibility constructor for callers predating explicit malformed-time state. */
  public BesEnvelope(
      Kind kind,
      Optional<String> buildId,
      Optional<String> invocationId,
      Optional<String> component,
      long sequence,
      Optional<ByteString> bazelEventBytes,
      OptionalLong eventTimeMicros) {
    this(
        kind,
        buildId,
        invocationId,
        component,
        sequence,
        bazelEventBytes,
        eventTimeMicros,
        eventTimeMicros.isPresent() ? ProtoTimes.State.PRESENT : ProtoTimes.State.ABSENT);
  }
}
