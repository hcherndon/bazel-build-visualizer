package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.bes.BesStreamKey;
import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class BuildOutcomeTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("classifications")
  @DisplayName("every process, cancellation, transport, and drain state is classified honestly")
  void completeClassificationTable(
      String description,
      Optional<ProcessOutcome> process,
      Optional<CaptureSummary> capture,
      BuildOutcome expected) {
    assertThat(BuildOutcome.classify(process, capture)).isEqualTo(expected);
  }

  private static Stream<Arguments> classifications() {
    CaptureSummary drained = completeSummary();
    CaptureSummary drainFailed =
        incompleteSummary(Optional.of(new IllegalStateException("store failed")));
    CaptureSummary incomplete = incompleteSummary(Optional.empty());
    CaptureSummary aborted = streamSummary(BesStreamState.Completion.ABORTED, 1, 1, 1);
    CaptureSummary gapped = streamSummary(BesStreamState.Completion.FINISHED, 2, 1, 1);
    ProcessOutcome noExit =
        new ProcessOutcome(OptionalInt.empty(), Optional.empty(), Duration.ZERO, Optional.empty());
    return Stream.of(
        Arguments.of("not started", Optional.empty(), Optional.empty(), BuildOutcome.NOT_STARTED),
        Arguments.of(
            "cancelled even with an exit",
            Optional.of(
                ProcessOutcome.cancelled(
                    OptionalInt.of(8), CancellationMode.CANCEL, Duration.ZERO)),
            Optional.of(drained),
            BuildOutcome.CANCELLED),
        Arguments.of(
            "process wait failed",
            Optional.of(
                ProcessOutcome.failed(new IllegalStateException("wait failed"), Duration.ZERO)),
            Optional.of(drained),
            BuildOutcome.UNKNOWN_PROCESS),
        Arguments.of(
            "process returned no exit",
            Optional.of(noExit),
            Optional.of(drained),
            BuildOutcome.UNKNOWN_PROCESS),
        Arguments.of(
            "BES upload exit masks build",
            Optional.of(
                ProcessOutcome.exited(BuildOutcome.BES_TRANSPORT_FAILURE_EXIT, Duration.ZERO)),
            Optional.of(drained),
            BuildOutcome.UNKNOWN_BES_TRANSPORT),
        Arguments.of(
            "capture drain returned no summary",
            Optional.of(ProcessOutcome.exited(0, Duration.ZERO)),
            Optional.empty(),
            BuildOutcome.UNKNOWN_BES_TRANSPORT),
        Arguments.of(
            "capture drain reported failure",
            Optional.of(ProcessOutcome.exited(1, Duration.ZERO)),
            Optional.of(drainFailed),
            BuildOutcome.UNKNOWN_BES_TRANSPORT),
        Arguments.of(
            "capture drain returned incomplete arithmetic",
            Optional.of(ProcessOutcome.exited(0, Duration.ZERO)),
            Optional.of(incomplete),
            BuildOutcome.UNKNOWN_BES_TRANSPORT),
        Arguments.of(
            "capture stream aborted",
            Optional.of(ProcessOutcome.exited(1, Duration.ZERO)),
            Optional.of(aborted),
            BuildOutcome.UNKNOWN_BES_TRANSPORT),
        Arguments.of(
            "capture stream retained a sequence gap",
            Optional.of(ProcessOutcome.exited(0, Duration.ZERO)),
            Optional.of(gapped),
            BuildOutcome.UNKNOWN_BES_TRANSPORT),
        Arguments.of(
            "successful build",
            Optional.of(ProcessOutcome.exited(0, Duration.ZERO)),
            Optional.of(drained),
            BuildOutcome.SUCCEEDED),
        Arguments.of(
            "failed build",
            Optional.of(ProcessOutcome.exited(1, Duration.ZERO)),
            Optional.of(drained),
            BuildOutcome.FAILED));
  }

  private static CaptureSummary completeSummary() {
    return new CaptureSummary(1, 1, 1, 0, 0, 1, List.of(), false, Optional.empty());
  }

  private static CaptureSummary incompleteSummary(Optional<Throwable> failure) {
    return new CaptureSummary(1, 0, 0, 0, 0, 0, List.of(), false, failure);
  }

  private static CaptureSummary streamSummary(
      BesStreamState.Completion completion,
      long highestReceived,
      long highestContiguous,
      long highestAcknowledged) {
    BesStreamState stream =
        new BesStreamState(
            new BesStreamKey("build", "invocation", "TOOL"),
            highestReceived,
            highestContiguous,
            highestAcknowledged,
            highestReceived,
            0,
            (int) (highestReceived - highestContiguous),
            OptionalLong.of(1),
            OptionalLong.of(2),
            completion,
            Optional.empty());
    return new CaptureSummary(1, 1, 1, 0, 0, 1, List.of(stream), false, Optional.empty());
  }
}
