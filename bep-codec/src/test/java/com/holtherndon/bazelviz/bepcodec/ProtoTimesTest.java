package com.holtherndon.bazelviz.bepcodec.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ActionExecuted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.holtherndon.bazelviz.bepcodec.BesEnvelopeDecoder;
import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ProtoTimesTest {

  @Test
  @DisplayName("valid protobuf times and legacy milliseconds convert exactly")
  void validValuesConvertExactly() {
    assertThat(ProtoTimes.timestampMicros(timestamp(1, 234_567_890))).hasValue(1_234_567L);
    assertThat(ProtoTimes.durationMicros(duration(-2, -345_678_000))).hasValue(-2_345_678L);
    assertThat(ProtoTimes.millisMicros(123)).hasValue(123_000L);
  }

  @Test
  @DisplayName("malformed and overflowing times become unavailable")
  void invalidValuesAreUnavailable() {
    Timestamp invalidTimestamp = timestamp(Long.MAX_VALUE, 0);
    Duration invalidDuration = duration(1, -1);

    assertThat(ProtoTimes.timestampMicros(invalidTimestamp)).isEmpty();
    assertThat(ProtoTimes.durationMicros(invalidDuration)).isEmpty();
    assertThat(ProtoTimes.millisMicros(Long.MAX_VALUE)).isEmpty();
    assertThat(ProtoTimes.micros(true, invalidTimestamp, 123)).isEmpty();
    assertThatThrownBy(() -> ProtoTimes.micros(invalidTimestamp))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("entity translation keeps an invalid action time unavailable")
  void entityTranslationDoesNotWrapInvalidActionTime() {
    BuildEvent event =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setActionCompleted(
                        BuildEventId.ActionCompletedId.newBuilder()
                            .setPrimaryOutput("bazel-out/bin/result")
                            .setConfiguration(
                                BuildEventId.ConfigurationId.newBuilder().setId("cfg"))))
            .setAction(
                ActionExecuted.newBuilder()
                    .setSuccess(true)
                    .setStartTime(timestamp(Long.MAX_VALUE, 0))
                    .setEndTime(timestamp(10, 0)))
            .build();

    List<EntityCommand> commands = new EntityTranslator().translate(event);

    assertThat(commands).hasSize(1);
    assertThat(commands.getFirst()).isInstanceOf(EntityCommand.ActionCompleted.class);
    ActionTiming timing = ((EntityCommand.ActionCompleted) commands.getFirst()).timing();
    assertThat(timing.startMicros()).isEmpty();
    assertThat(timing.endMicros()).hasValue(10_000_000L);
    assertThat(timing.unknownReason()).hasValue(ActionTiming.INVALID_REPORTED_VALUE);
  }

  @Test
  @DisplayName("entity translation still distinguishes timestamps that were absent")
  void entityTranslationKeepsAbsentActionTimesDistinct() {
    BuildEvent event =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setActionCompleted(
                        BuildEventId.ActionCompletedId.newBuilder()
                            .setPrimaryOutput("bazel-out/bin/result")
                            .setConfiguration(
                                BuildEventId.ConfigurationId.newBuilder().setId("cfg"))))
            .setAction(ActionExecuted.newBuilder().setSuccess(true))
            .build();

    EntityCommand.ActionCompleted action =
        (EntityCommand.ActionCompleted) new EntityTranslator().translate(event).getFirst();

    assertThat(action.timing().startMicros()).isEmpty();
    assertThat(action.timing().endMicros()).isEmpty();
    assertThat(action.timing().unknownReason()).hasValue(ActionTiming.NOT_REPORTED);
  }

  @Test
  @DisplayName("an invalid BES envelope event time is unavailable")
  void envelopeTimeDoesNotWrap() {
    com.google.devtools.build.v1.BuildEvent envelopeEvent =
        com.google.devtools.build.v1.BuildEvent.newBuilder()
            .setEventTime(timestamp(Long.MAX_VALUE, 0))
            .setBazelEvent(Any.newBuilder().setValue(ByteString.copyFromUtf8("raw bep payload")))
            .build();
    byte[] request =
        PublishBuildToolEventStreamRequest.newBuilder()
            .setOrderedBuildEvent(
                OrderedBuildEvent.newBuilder()
                    .setStreamId(StreamId.newBuilder().setBuildId("build"))
                    .setSequenceNumber(1)
                    .setEvent(envelopeEvent))
            .build()
            .toByteArray();

    BesEnvelopeDecoder.Result decoded =
        new BesEnvelopeDecoder(1_024).decodeToolEvent(request, 0, request.length);

    assertThat(decoded.isFailed()).isFalse();
    assertThat(decoded.envelope().orElseThrow().eventTimeMicros()).isEmpty();
  }

  private static Timestamp timestamp(long seconds, int nanos) {
    return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static Duration duration(long seconds, int nanos) {
    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }
}
