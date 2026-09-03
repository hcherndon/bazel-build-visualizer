package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.bes.BesStreamKey;
import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What "complete" is allowed to mean. */
class CaptureSummaryTest {

  private static final BesStreamKey KEY = new BesStreamKey("build-1", "inv-1", "TOOL");

  @Test
  @DisplayName("a capture that received nothing is not complete")
  void emptyCaptureIsNotComplete() {
    CaptureSummary empty = new CaptureSummary(0, 0, 0, 0, 0, 0, List.of(), false, Optional.empty());

    // Every arithmetic clause is true at zero -- 0 == 0, 0 == 0 + 0, and
    // allMatch over an empty list -- so this used to report complete. A
    // build that died during option parsing, or one whose events went to
    // somebody else's backend, produced a session claiming a COMPLETE
    // capture source with no events in it, and exited 0.
    assertThat(empty.capturedAnything()).isFalse();
    assertThat(empty.isComplete()).isFalse();
    assertThat(empty.discrepancies())
        .anyMatch(problem -> problem.contains("no build events arrived at all"));
  }

  @Test
  @DisplayName("a capture that kept everything it received is complete")
  void fullCaptureIsComplete() {
    CaptureSummary full =
        new CaptureSummary(10, 10, 8, 2, 0, 4096, List.of(finished(10)), false, Optional.empty());

    assertThat(full.capturedAnything()).isTrue();
    assertThat(full.isComplete()).isTrue();
    assertThat(full.discrepancies()).isEmpty();
  }

  @Test
  @DisplayName("an event that never reached the journal makes the capture incomplete")
  void unjournaledEventIsADiscrepancy() {
    CaptureSummary lost =
        new CaptureSummary(10, 9, 7, 2, 0, 4096, List.of(finished(10)), false, Optional.empty());

    assertThat(lost.isComplete()).isFalse();
    assertThat(lost.discrepancies())
        .anyMatch(problem -> problem.contains("accepted but never journaled"));
  }

  @Test
  @DisplayName("a frame that became neither a row nor a stream-control envelope is a discrepancy")
  void unaccountedFrameIsADiscrepancy() {
    CaptureSummary lost =
        new CaptureSummary(10, 10, 7, 2, 0, 4096, List.of(finished(10)), false, Optional.empty());

    assertThat(lost.isComplete()).isFalse();
    assertThat(lost.discrepancies()).anyMatch(problem -> problem.contains("never normalized"));
  }

  @Test
  @DisplayName("a stream that ended badly makes the capture incomplete even when the counts add up")
  void abortedStreamIsNotComplete() {
    BesStreamState aborted =
        new BesStreamState(
            KEY,
            10,
            10,
            10,
            10,
            0,
            0,
            OptionalLong.of(1),
            OptionalLong.of(2),
            BesStreamState.Completion.ABORTED,
            Optional.of("the socket died"));
    CaptureSummary summary =
        new CaptureSummary(10, 10, 8, 2, 0, 4096, List.of(aborted), false, Optional.empty());

    assertThat(summary.isComplete()).isFalse();
    assertThat(summary.discrepancies()).anyMatch(problem -> problem.contains("ABORTED"));
  }

  private static BesStreamState finished(long events) {
    return new BesStreamState(
        KEY,
        events,
        events,
        events,
        events,
        0,
        0,
        OptionalLong.of(1),
        OptionalLong.of(2),
        BesStreamState.Completion.FINISHED,
        Optional.empty());
  }
}
