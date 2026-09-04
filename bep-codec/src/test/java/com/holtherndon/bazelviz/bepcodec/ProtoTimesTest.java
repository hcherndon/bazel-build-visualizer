package com.holtherndon.bazelviz.bepcodec.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ActionExecuted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetComplete;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestSummary;
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
  @DisplayName("checked conversions distinguish absent, epoch zero, and malformed values")
  void checkedConversionsPreserveAllThreeStates() {
    assertThat(ProtoTimes.checkedMillisMicros(0).state()).isEqualTo(ProtoTimes.State.ABSENT);
    assertThat(ProtoTimes.checkedTimestampMicros(timestamp(0, 0)).state())
        .isEqualTo(ProtoTimes.State.PRESENT);
    assertThat(ProtoTimes.checkedTimestampMicros(timestamp(0, 0)).micros()).hasValue(0L);
    assertThat(ProtoTimes.checkedTimestampMicros(timestamp(Long.MAX_VALUE, 0)).state())
        .isEqualTo(ProtoTimes.State.INVALID);
  }

  @Test
  @DisplayName("a present epoch invocation time wins over a legacy fallback")
  void presentEpochDoesNotFallBack() {
    BuildEvent event =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setStarted(BuildEventId.BuildStartedId.getDefaultInstance()))
            .setStarted(
                BuildStarted.newBuilder().setStartTime(timestamp(0, 0)).setStartTimeMillis(123))
            .build();

    EntityTranslator.Translation translated = new EntityTranslator().translateChecked(event);
    EntityCommand.InvocationStarted started =
        (EntityCommand.InvocationStarted) translated.commands().getFirst();

    assertThat(started.startMicros()).hasValue(0L);
    assertThat(translated.timeAnomalies()).isEmpty();
  }

  @Test
  @DisplayName("test timeouts validate Duration structure and nonnegative integral seconds")
  void testTimeoutsAreFullyValidated() {
    EntityCommand.TargetCompleted maximum = completedWithTimeout(duration(315_576_000_000L, 0));
    assertThat(maximum.testTimeoutSeconds()).hasValue(315_576_000_000L);

    for (Duration invalid :
        List.of(duration(315_576_000_001L, 0), duration(1, -1), duration(-1, 0), duration(1, 1))) {
      BuildEvent event = targetCompleted(invalid);
      EntityTranslator.Translation translated = new EntityTranslator().translateChecked(event);
      EntityCommand.TargetCompleted completed =
          (EntityCommand.TargetCompleted) translated.commands().getFirst();
      assertThat(completed.testTimeoutSeconds()).as("%s", invalid).isEmpty();
      assertThat(translated.timeAnomalies()).as("%s", invalid).hasSize(1);
    }

    assertThat(completedWithTimeout(duration(0, 0)).testTimeoutSeconds()).hasValue(0L);

    BuildEvent negativeLegacy =
        targetCompleted(duration(0, 0)).toBuilder()
            .setCompleted(TargetComplete.newBuilder().setSuccess(true).setTestTimeoutSeconds(-1))
            .build();
    EntityTranslator.Translation translated =
        new EntityTranslator().translateChecked(negativeLegacy);
    assertThat(
            ((EntityCommand.TargetCompleted) translated.commands().getFirst()).testTimeoutSeconds())
        .isEmpty();
    assertThat(translated.timeAnomalies())
        .singleElement()
        .asString()
        .contains("test_timeout_seconds");
  }

  @Test
  @DisplayName("an invalid critical-path duration is unavailable and diagnosed")
  void invalidCriticalPathDurationIsDiagnosed() {
    BuildEvent event =
        BuildEvent.newBuilder()
            .setBuildMetrics(
                BuildMetrics.newBuilder()
                    .setTimingMetrics(
                        BuildMetrics.TimingMetrics.newBuilder()
                            .setCriticalPathTime(duration(0, -1))))
            .build();

    EntityTranslator.Translation translated = new EntityTranslator().translateChecked(event);
    EntityCommand.BuildMetricsReported metrics =
        (EntityCommand.BuildMetricsReported) translated.commands().getFirst();

    assertThat(metrics.criticalPathMicros()).isEmpty();
    assertThat(translated.timeAnomalies())
        .singleElement()
        .asString()
        .contains("critical_path_time");
  }

  @Test
  @DisplayName("test times preserve malformed, absent, and present-zero states")
  void testTimesPreserveAllAvailabilityStates() {
    BuildEvent attemptEvent =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setTestResult(
                        BuildEventId.TestResultId.newBuilder()
                            .setLabel("//pkg:test")
                            .setConfiguration(
                                BuildEventId.ConfigurationId.newBuilder().setId("cfg"))
                            .setRun(1)
                            .setShard(1)
                            .setAttempt(1)))
            .setTestResult(
                TestResult.newBuilder()
                    .setTestAttemptStart(timestamp(Long.MAX_VALUE, 0))
                    .setTestAttemptDuration(duration(-1, 0)))
            .build();
    EntityTranslator.Translation attemptTranslation =
        new EntityTranslator().translateChecked(attemptEvent);
    EntityCommand.TestAttemptCompleted attempt =
        (EntityCommand.TestAttemptCompleted) attemptTranslation.commands().getFirst();

    assertThat(attempt.startMicros()).isEmpty();
    assertThat(attempt.durationMicros()).isEmpty();
    assertThat(attemptTranslation.timeAnomalies())
        .anyMatch(message -> message.contains("test_attempt_start"))
        .anyMatch(message -> message.contains("test_attempt_duration"));

    BuildEvent summaryEvent =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setTestSummary(
                        BuildEventId.TestSummaryId.newBuilder()
                            .setLabel("//pkg:test")
                            .setConfiguration(
                                BuildEventId.ConfigurationId.newBuilder().setId("cfg"))))
            .setTestSummary(
                TestSummary.newBuilder()
                    .setFirstStartTime(timestamp(0, 0))
                    .setTotalRunDuration(duration(-1, 0)))
            .build();
    EntityTranslator.Translation summaryTranslation =
        new EntityTranslator().translateChecked(summaryEvent);
    EntityCommand.TestSummarized summary =
        (EntityCommand.TestSummarized) summaryTranslation.commands().getFirst();

    assertThat(summary.firstStartMicros()).hasValue(0L);
    assertThat(summary.lastStopMicros()).isEmpty();
    assertThat(summary.bazelReportedDurationMicros()).isEmpty();
    assertThat(summaryTranslation.timeAnomalies())
        .singleElement()
        .asString()
        .contains("total_run_duration");
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
    assertThat(decoded.envelope().orElseThrow().eventTimeState())
        .isEqualTo(ProtoTimes.State.INVALID);
  }

  @Test
  @DisplayName("a present epoch BES envelope time remains present")
  void envelopeEpochIsPresent() {
    com.google.devtools.build.v1.BuildEvent envelopeEvent =
        com.google.devtools.build.v1.BuildEvent.newBuilder()
            .setEventTime(Timestamp.getDefaultInstance())
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

    var envelope =
        new BesEnvelopeDecoder(1_024)
            .decodeToolEvent(request, 0, request.length)
            .envelope()
            .orElseThrow();

    assertThat(envelope.eventTimeMicros()).hasValue(0L);
    assertThat(envelope.eventTimeState()).isEqualTo(ProtoTimes.State.PRESENT);
  }

  private static Timestamp timestamp(long seconds, int nanos) {
    return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static Duration duration(long seconds, int nanos) {
    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private static EntityCommand.TargetCompleted completedWithTimeout(Duration timeout) {
    return (EntityCommand.TargetCompleted)
        new EntityTranslator().translate(targetCompleted(timeout)).getFirst();
  }

  private static BuildEvent targetCompleted(Duration timeout) {
    return BuildEvent.newBuilder()
        .setId(
            BuildEventId.newBuilder()
                .setTargetCompleted(
                    BuildEventId.TargetCompletedId.newBuilder()
                        .setLabel("//pkg:test")
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId("cfg"))))
        .setCompleted(TargetComplete.newBuilder().setSuccess(true).setTestTimeout(timeout))
        .build();
  }
}
