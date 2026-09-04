package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.bes.BesStreamTracker.Decision;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The sequence bookkeeping that decides what may be acknowledged, and when. */
class BesStreamTrackerTest {

  private static final BesStreamKey KEY = new BesStreamKey("build-1", "inv-1", "TOOL");

  @Test
  @DisplayName("an in-order stream acknowledges each event as it is journaled")
  void inOrderStream() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection connection = tracker.openConnection();

    for (long sequence = 1; sequence <= 3; sequence++) {
      assertThat(tracker.accept(connection, sequence, sequence * 100)).isEqualTo(Decision.ACCEPTED);
      BesStreamTracker.AckRange range = tracker.journaled(sequence).contiguousRange();
      assertThat(range.from()).isEqualTo(sequence);
      assertThat(range.to()).isEqualTo(sequence);
      tracker.acknowledged(sequence);
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
    BesStreamTracker.Connection connection = tracker.openConnection();
    tracker.accept(connection, 1, 100);
    tracker.journaled(1);
    tracker.acknowledged(1);

    assertThat(tracker.accept(connection, 1, 200)).isEqualTo(Decision.DUPLICATE_ACK_NOW);
    assertThat(tracker.accept(connection, 1, 300)).isEqualTo(Decision.DUPLICATE_ACK_NOW);

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
    BesStreamTracker.Connection original = tracker.openConnection();
    BesStreamTracker.Connection replay = tracker.openConnection();
    tracker.accept(original, 1, 100);

    // Arrives again before the first copy has reached the journal. Treating
    // this as new would write the same event twice.
    assertThat(tracker.accept(replay, 1, 110)).isEqualTo(Decision.DUPLICATE_WAIT);
    assertThat(tracker.snapshot().eventsAccepted()).isEqualTo(1);
    assertThat(tracker.journaled(1).readyAcks())
        .containsExactly(
            new BesStreamTracker.PendingAck(original, 1, false),
            new BesStreamTracker.PendingAck(replay, 1));
  }

  @Test
  @DisplayName("each repeated pending duplicate keeps its own acknowledgement obligation")
  void repeatedPendingDuplicatesPreserveMultiplicity() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection original = tracker.openConnection();
    BesStreamTracker.Connection replay = tracker.openConnection();
    assertThat(tracker.accept(original, 1, 100)).isEqualTo(Decision.ACCEPTED);
    assertThat(tracker.accept(replay, 1, 110)).isEqualTo(Decision.DUPLICATE_WAIT);
    assertThat(tracker.accept(replay, 1, 120)).isEqualTo(Decision.DUPLICATE_WAIT);

    assertThat(tracker.journaled(1).readyAcks())
        .containsExactly(
            new BesStreamTracker.PendingAck(original, 1, false),
            new BesStreamTracker.PendingAck(replay, 1),
            new BesStreamTracker.PendingAck(replay, 1));
  }

  @Test
  @DisplayName("nothing is acknowledged past a gap")
  void acknowledgementsStopAtAGap() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection connection = tracker.openConnection();
    tracker.accept(connection, 1, 100);
    tracker.accept(connection, 3, 300);
    tracker.journaled(1);
    tracker.acknowledged(1);

    // 3 is journaled but 2 never arrived. Acknowledging 3 would tell Bazel
    // we hold an event we do not.
    BesStreamTracker.AckRange afterThree = tracker.journaled(3).contiguousRange();
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
    BesStreamTracker.Connection connection = tracker.openConnection();
    tracker.accept(connection, 1, 100);
    tracker.accept(connection, 3, 300);
    tracker.accept(connection, 4, 400);
    tracker.accept(connection, 2, 200);
    tracker.journaled(1);
    tracker.acknowledged(1);
    tracker.journaled(3);
    tracker.journaled(4);

    BesStreamTracker.AckRange released = tracker.journaled(2).contiguousRange();

    assertThat(released.from()).isEqualTo(2);
    assertThat(released.to()).isEqualTo(4);
    assertThat(released.count()).isEqualTo(3);
    assertThat(tracker.snapshot().hasGap()).isFalse();
  }

  @Test
  @DisplayName("a sequence below one is rejected rather than journaled at a made-up position")
  void sequencesStartAtOne() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection connection = tracker.openConnection();

    assertThat(tracker.accept(connection, 0, 100)).isEqualTo(Decision.INVALID);
    assertThat(tracker.accept(connection, -1, 100)).isEqualTo(Decision.INVALID);
    assertThat(tracker.snapshot().eventsAccepted()).isZero();
  }

  @Test
  @DisplayName("events held above an unfilled gap are bounded, and the bound refuses")
  void disorderIsBounded() {
    BesStreamTracker tracker = new BesStreamTracker(KEY, 3);
    BesStreamTracker.Connection connection = tracker.openConnection();

    // 1 never arrives, so nothing below these can become contiguous and each
    // one that reaches the journal is genuinely held.
    for (long sequence = 2; sequence <= 4; sequence++) {
      assertThat(tracker.accept(connection, sequence, 100)).isEqualTo(Decision.ACCEPTED);
      tracker.journaled(sequence);
    }

    assertThat(tracker.accept(connection, 5, 100)).isEqualTo(Decision.TOO_FAR_AHEAD);
    assertThat(tracker.snapshot().outOfOrderBuffered()).isEqualTo(3);
  }

  @Test
  @DisplayName("accepted in-flight tracker state is bounded and recovers after journaling")
  void inFlightTrackerStateIsBounded() {
    BesStreamTracker tracker = new BesStreamTracker(KEY, 3);
    BesStreamTracker.Connection connection = tracker.openConnection();

    for (long sequence = 1; sequence <= 3; sequence++) {
      assertThat(tracker.accept(connection, sequence, 100))
          .describedAs("sequence %d", sequence)
          .isEqualTo(Decision.ACCEPTED);
    }
    assertThat(tracker.accept(connection, 4, 100)).isEqualTo(Decision.TOO_FAR_AHEAD);

    for (long sequence = 1; sequence <= 3; sequence++) {
      tracker.journaled(sequence);
      tracker.acknowledged(sequence);
    }
    assertThat(tracker.accept(connection, 4, 100)).isEqualTo(Decision.ACCEPTED);
    tracker.journaled(4);
    tracker.acknowledged(4);
    BesStreamState state = tracker.snapshot();
    assertThat(state.highestAcknowledged()).isEqualTo(4);
    assertThat(state.hasGap()).isFalse();
    assertThat(state.outOfOrderBuffered()).isZero();
  }

  @Test
  @DisplayName("overlapping connections end only when quiescent and use safe precedence")
  void overlappingConnectionsUsePrecedenceAtQuiescence() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection first = tracker.openConnection();
    BesStreamTracker.Connection second = tracker.openConnection();

    assertThat(tracker.end(first, BesStreamState.Completion.ABORTED, "the socket died")).isEmpty();
    assertThat(tracker.snapshot().completion()).isEqualTo(BesStreamState.Completion.OPEN);
    assertThat(tracker.end(second, BesStreamState.Completion.FINISHED, null)).isPresent();

    assertThat(tracker.snapshot().completion()).isEqualTo(BesStreamState.Completion.FINISHED);
    assertThat(tracker.snapshot().error()).isEmpty();
  }

  @Test
  @DisplayName("a failed overlapping generation takes precedence over a finished one")
  void failureHasHighestPrecedence() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection first = tracker.openConnection();
    BesStreamTracker.Connection second = tracker.openConnection();

    assertThat(tracker.end(first, BesStreamState.Completion.FAILED, "journal failed")).isEmpty();
    BesStreamState state =
        tracker.end(second, BesStreamState.Completion.FINISHED, null).orElseThrow();

    assertThat(state.completion()).isEqualTo(BesStreamState.Completion.FAILED);
    assertThat(state.error()).contains("journal failed");
  }

  @Test
  @DisplayName("an ended connection publishes only after its accepted append settles")
  void terminalWaitsForAcceptedDurability() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection connection = tracker.openConnection();
    assertThat(tracker.accept(connection, 1, 100)).isEqualTo(Decision.ACCEPTED);

    assertThat(tracker.end(connection, BesStreamState.Completion.ABORTED, "socket closed"))
        .isEmpty();
    assertThat(tracker.snapshot().completion()).isEqualTo(BesStreamState.Completion.OPEN);

    BesStreamState terminal = tracker.journaled(1).terminalState().orElseThrow();
    assertThat(terminal.completion()).isEqualTo(BesStreamState.Completion.ABORTED);
    assertThat(terminal.highestContiguous()).isEqualTo(1);
  }

  @Test
  @DisplayName("a late journal rejection upgrades a cancelled epoch to failed")
  void journalRejectionUpgradesCancelledEpoch() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection connection = tracker.openConnection();
    assertThat(tracker.accept(connection, 1, 100)).isEqualTo(Decision.ACCEPTED);
    assertThat(tracker.end(connection, BesStreamState.Completion.ABORTED, "socket closed"))
        .isEmpty();

    BesStreamState terminal = tracker.rejected(1, "journal failed").terminalState().orElseThrow();
    assertThat(terminal.completion()).isEqualTo(BesStreamState.Completion.FAILED);
    assertThat(terminal.error()).contains("journal failed");
  }

  @Test
  @DisplayName("a reconnect waits until the prior terminal callback is published")
  void reconnectWaitsForTerminalPublication() throws Exception {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection first = tracker.openConnection();
    assertThat(tracker.end(first, BesStreamState.Completion.FINISHED, null)).isPresent();
    var opened = new AtomicReference<BesStreamTracker.Connection>();
    Thread reconnect =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    opened.set(tracker.openConnectionInterruptibly());
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                  }
                });

    Thread.sleep(50);
    assertThat(opened).hasValue(null);
    tracker.terminalPublished();
    reconnect.join(2_000);
    assertThat(reconnect.isAlive()).isFalse();
    assertThat(opened.get().generation()).isGreaterThan(first.generation());
  }

  @Test
  @DisplayName("the acknowledged watermark moves only after a response succeeds")
  void visibleAcknowledgementIsSeparateFromDurability() {
    BesStreamTracker tracker = new BesStreamTracker(KEY);
    BesStreamTracker.Connection connection = tracker.openConnection();
    tracker.accept(connection, 1, 100);

    assertThat(tracker.journaled(1).contiguousRange())
        .isEqualTo(new BesStreamTracker.AckRange(1, 1));
    assertThat(tracker.snapshot().highestAcknowledged()).isZero();

    tracker.acknowledged(1);
    assertThat(tracker.snapshot().highestAcknowledged()).isEqualTo(1);
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
