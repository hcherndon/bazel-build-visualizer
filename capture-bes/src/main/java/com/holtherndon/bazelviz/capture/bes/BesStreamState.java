package com.holtherndon.bazelviz.capture.bes;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * An immutable snapshot of one BES stream's progress (plan 9.2).
 *
 * <p>Every field the plan lists is here, and three of them are the ones that make "no accepted
 * event is silently dropped" checkable rather than asserted: {@link #highestReceived}, {@link
 * #highestContiguous} and {@link #highestAcknowledged}. If they agree at the end of a stream,
 * nothing was lost between the wire and the journal. If they do not, the difference names exactly
 * what is missing, and {@link #hasGap()} says so out loud instead of the session quietly containing
 * fewer events than Bazel sent.
 *
 * <p>Snapshots are what the UI and the manifest see. The mutable tracking lives behind the server,
 * on one thread per stream, because a state that could be observed mid-update would report a gap
 * that does not exist.
 *
 * @param key the stream's identity
 * @param highestReceived the largest sequence number accepted from the wire
 * @param highestContiguous the largest sequence with no gap beneath it; this is the honest "we have
 *     everything up to here" watermark
 * @param highestAcknowledged the largest sequence acknowledged to Bazel, which is never above what
 *     has been appended to the journal (plan 9.3, balanced durability)
 * @param eventsAccepted how many envelopes were accepted, duplicates excluded
 * @param duplicateCount retransmissions seen and discarded idempotently
 * @param outOfOrderBuffered how many events are currently held back waiting for an earlier sequence
 * @param firstReceiveMicros when the first event arrived, absent before any did
 * @param lastReceiveMicros when the most recent event arrived
 * @param completion how the stream ended, if it has
 * @param error the error that ended it, when one did
 */
public record BesStreamState(
    BesStreamKey key,
    long highestReceived,
    long highestContiguous,
    long highestAcknowledged,
    long eventsAccepted,
    long duplicateCount,
    int outOfOrderBuffered,
    OptionalLong firstReceiveMicros,
    OptionalLong lastReceiveMicros,
    Completion completion,
    Optional<String> error) {

  /** How a stream ended (plan 9.2 "completion state" and "error state"). */
  public enum Completion {
    /** Still receiving. */
    OPEN,
    /**
     * Bazel sent its {@code component_stream_finished} event and half-closed. The only ending that
     * means the stream is complete.
     */
    FINISHED,
    /** The RPC ended without a finish event: the build died, or the socket did. */
    ABORTED,
    /** The pipeline rejected the stream — a journal write failed, or capture was cancelled. */
    FAILED;

    public boolean isTerminal() {
      return this != OPEN;
    }
  }

  /**
   * The sequence number Bazel's first event carries. The BES contract says a stream's sequence
   * numbers are "consecutive natural numbers starting from one", so a stream whose contiguous
   * watermark is still 0 has received nothing usable, and the first gap is detectable from the very
   * first event rather than only from the second.
   */
  public static final long FIRST_SEQUENCE = 1L;

  public BesStreamState {
    Objects.requireNonNull(key, "key");
    firstReceiveMicros = Objects.requireNonNull(firstReceiveMicros, "firstReceiveMicros");
    lastReceiveMicros = Objects.requireNonNull(lastReceiveMicros, "lastReceiveMicros");
    Objects.requireNonNull(completion, "completion");
    error = Objects.requireNonNull(error, "error");
    if (highestContiguous > highestReceived) {
      throw new IllegalArgumentException(
          "contiguous watermark "
              + highestContiguous
              + " cannot exceed highest received "
              + highestReceived);
    }
    if (highestAcknowledged > highestContiguous) {
      throw new IllegalArgumentException(
          "acknowledged "
              + highestAcknowledged
              + " cannot exceed the contiguous watermark "
              + highestContiguous
              + "; acknowledging past a gap tells Bazel we have data we do not");
    }
  }

  /** A stream that has just been opened and has received nothing. */
  public static BesStreamState opened(BesStreamKey key) {
    return new BesStreamState(
        key,
        0,
        0,
        0,
        0,
        0,
        0,
        OptionalLong.empty(),
        OptionalLong.empty(),
        Completion.OPEN,
        Optional.empty());
  }

  /** True when something arrived out of order and has not been filled in. */
  public boolean hasGap() {
    return highestContiguous < highestReceived;
  }

  /** Events accepted but not yet acknowledged: the visible capture backlog. */
  public long acknowledgementBacklog() {
    return highestContiguous - highestAcknowledged;
  }

  /**
   * True when this stream ended with everything it received acknowledged and no gaps — the
   * per-stream form of the Phase 2 exit criterion "no accepted event is silently dropped".
   */
  public boolean isCleanlyComplete() {
    return completion == Completion.FINISHED
        && !hasGap()
        && highestAcknowledged == highestReceived
        && outOfOrderBuffered == 0;
  }
}
