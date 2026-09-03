package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.bes.BesStreamTracker.Decision;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The sequence bookkeeping that decides what may be acknowledged, and when. */
class BesStreamTrackerTest {

  private static final BesStreamKey KEY = new BesStreamKey("build-1", "inv-1", "TOOL");

  @Test
  @DisplayName("an in-order stream acknowledges each event as it is journaled")
  void inOrderStream() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);

    for (long sequence = 1; sequence <= 3; sequence++) {
      assertThat(tracker.accept(sequence, sequence * 100)).isEqualTo(Decision.ACCEPTED);
      BesStreamTracker.AckRange range = tracker.journaled(sequence);
      assertThat(range.from()).isEqualTo(sequence);
      assertThat(range.to()).isEqualTo(sequence);
    }

    BesStreamState state = tracker.snapshot();
    assertThat(state.highestReceived()).isEqualTo(3);
    assertThat(state.highestContiguous()).isEqualTo(3);
    assertThat(state.highestAcknowledged()).isEqualTo(3);
    assertThat(state.hasGap()).isFalse();
    assertThat(state.duplicateCount()).isZero();
  }

  @Test
  @DisplayName("a retransmitted sequence is a duplicate, counted, and not journaled again")
  void duplicatesAreIdempotent() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    tracker.accept(1, 100);
    tracker.journaled(1);

    assertThat(tracker.accept(1, 200)).isEqualTo(Decision.DUPLICATE);
    assertThat(tracker.accept(1, 300)).isEqualTo(Decision.DUPLICATE);

    BesStreamState state = tracker.snapshot();
    assertThat(state.duplicateCount()).isEqualTo(2);
    // The duplicates changed nothing else: one event was accepted, one
    // journaled, one acknowledged.
    assertThat(state.eventsAccepted()).isEqualTo(1);
    assertThat(state.highestReceived()).isEqualTo(1);
    assertThat(state.highestAcknowledged()).isEqualTo(1);
  }

  @Test
  @DisplayName("a resend of an event still in flight is a duplicate, not a second copy")
  void resendWhileStillJournalingIsADuplicate() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    tracker.accept(1, 100);

    // Arrives again before the first copy has reached the journal. Treating
    // this as new would write the same event twice.
    assertThat(tracker.accept(1, 110)).isEqualTo(Decision.DUPLICATE);
    assertThat(tracker.snapshot().eventsAccepted()).isEqualTo(1);
  }

  @Test
  @DisplayName("nothing is acknowledged past a gap")
  void acknowledgementsStopAtAGap() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    tracker.accept(1, 100);
    tracker.accept(3, 300);
    tracker.journaled(1);

    // 3 is journaled but 2 never arrived. Acknowledging 3 would tell Bazel
    // we hold an event we do not.
    BesStreamTracker.AckRange afterThree = tracker.journaled(3);
    assertThat(afterThree.isEmpty()).isTrue();

    BesStreamState state = tracker.snapshot();
    assertThat(state.highestReceived()).isEqualTo(3);
    assertThat(state.highestContiguous()).isEqualTo(1);
    assertThat(state.highestAcknowledged()).isEqualTo(1);
    assertThat(state.hasGap()).isTrue();
  }

  @Test
  @DisplayName("filling a gap releases every acknowledgement it was holding back")
  void fillingAGapReleasesTheRun() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    tracker.accept(1, 100);
    tracker.accept(3, 300);
    tracker.accept(4, 400);
    tracker.accept(2, 200);
    tracker.journaled(1);
    tracker.journaled(3);
    tracker.journaled(4);

    BesStreamTracker.AckRange released = tracker.journaled(2);

    assertThat(released.from()).isEqualTo(2);
    assertThat(released.to()).isEqualTo(4);
    assertThat(released.count()).isEqualTo(3);
    assertThat(tracker.snapshot().hasGap()).isFalse();
  }

  @Test
  @DisplayName("a sequence below one is rejected rather than journaled at a made-up position")
  void sequencesStartAtOne() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);

    assertThat(tracker.accept(0, 100)).isEqualTo(Decision.INVALID);
    assertThat(tracker.accept(-1, 100)).isEqualTo(Decision.INVALID);
    assertThat(tracker.snapshot().eventsAccepted()).isZero();
  }

  @Test
  @DisplayName("events held above an unfilled gap are bounded, and the bound refuses")
  void disorderIsBounded() {
    BesStreamTracker tracker = new BesStreamTracker(KEY, 3);

    // 1 never arrives, so nothing below these can become contiguous and each
    // one that reaches the journal is genuinely held.
    for (long sequence = 2; sequence <= 4; sequence++) {
      assertThat(tracker.accept(sequence, 100)).isEqualTo(Decision.ACCEPTED);
      tracker.journaled(sequence);
    }

    assertThat(tracker.accept(5, 100)).isEqualTo(Decision.TOO_FAR_AHEAD);
    assertThat(tracker.snapshot().outOfOrderBuffered()).isEqualTo(3);
  }

  @Test
  @DisplayName("a deep pipeline is not disorder, and does not trip the bound")
  void pipelineDepthIsNotDisorder() {
    BesStreamTracker tracker = new BesStreamTracker(KEY, 3);

    // Ten events accepted in order, none journaled yet: the pipeline is
    // simply behind. An earlier version counted this as disorder and
    // refused the stream with FAILED_PRECONDITION at exactly the moment
    // backpressure was doing its job — which a real 200,000-event burst
    // reproduced and a single-message-in-flight test could never have.
    for (long sequence = 1; sequence <= 10; sequence++) {
      assertThat(tracker.accept(sequence, 100))
          .describedAs("sequence %d", sequence)
          .isEqualTo(Decision.ACCEPTED);
    }

    // Draining them in order acknowledges the whole run.
    for (long sequence = 1; sequence <= 10; sequence++) {
      tracker.journaled(sequence);
    }
    BesStreamState state = tracker.snapshot();
    assertThat(state.highestAcknowledged()).isEqualTo(10);
    assertThat(state.hasGap()).isFalse();
    assertThat(state.outOfOrderBuffered()).isZero();
  }

  @Test
  @DisplayName("the first ending is the one recorded")
  void firstEndingWins() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    tracker.end(BesStreamState.Completion.ABORTED, "the socket died");
    tracker.end(BesStreamState.Completion.FINISHED, null);

    // A cancel handler and an onError both firing must not turn an aborted
    // stream into a complete one.
    assertThat(tracker.snapshot().completion()).isEqualTo(BesStreamState.Completion.ABORTED);
    assertThat(tracker.snapshot().error()).contains("the socket died");
  }

  @Test
  @DisplayName("a state that acknowledges past its contiguous watermark cannot be constructed")
  void acknowledgingPastTheWatermarkIsNotRepresentable() {
    assertThatThrownBy(
            () ->
                new BesStreamState(
                    KEY,
                    5,
                    3,
                    4,
                    4,
                    0,
                    2,
                    OptionalLong.of(1),
                    OptionalLong.of(2),
                    BesStreamState.Completion.OPEN,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("acknowledging past a gap");
  }
}
