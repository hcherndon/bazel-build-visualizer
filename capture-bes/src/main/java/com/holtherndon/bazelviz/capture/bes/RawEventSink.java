package com.holtherndon.bazelviz.capture.bes;

/**
 * The handoff from the gRPC server to the capture pipeline (plan 9.3, the arrow from "gRPC
 * callback" to "bounded receive queue").
 *
 * <h2>The contract that keeps events from disappearing</h2>
 *
 * <p>There are exactly two outcomes for a submitted event: it is accepted and will be journaled, or
 * {@link #submit} throws. There is no third outcome, and in particular there is no boolean return
 * that a caller could ignore. That is how plan 9.2's "never drop accepted events silently" is made
 * structural — an event this application declines is one Bazel is told about through a failed RPC,
 * which Bazel reports to the user, rather than one that vanishes between a full queue and a log
 * line nobody reads.
 *
 * <h2>Backpressure is blocking, on purpose</h2>
 *
 * <p>When the pipeline is behind, {@link #submit} blocks. The gRPC callback is required to return
 * quickly (plan 9.3), and blocking in it looks like the opposite of that — but the alternative is
 * to buffer without bound, and an unbounded queue in front of a slower disk is how a
 * 5-million-action build runs the capture out of memory. Blocking propagates the pressure to the
 * one place that can absorb it: Bazel stops sending. The server implementation keeps the callback
 * responsive by disabling automatic flow control and requesting the next message only after the
 * previous one has been submitted, so the block happens between messages rather than inside a read.
 *
 * <h2>Acknowledgement ordering</h2>
 *
 * <p>{@code onJournaled} runs after the frame has been appended to the journal channel, and never
 * before. Acknowledging earlier would tell Bazel a build event is safe while it is still only in
 * this process's memory, and a crash at that moment produces a session that is missing events Bazel
 * believes it delivered. It may run on the pipeline thread, so it must not block.
 */
public interface RawEventSink {

  /**
   * Hands one envelope to the pipeline, blocking while it is saturated.
   *
   * @param event the envelope; its payload array is handed over and must not be touched by the
   *     caller afterwards
   * @param onJournaled run once the frame is in the journal, which is when the event may be
   *     acknowledged to Bazel
   * @throws InterruptedException if the calling thread is interrupted while waiting for room; the
   *     event was not accepted
   * @throws CaptureRejectedException if the pipeline cannot accept the event at all — it has failed
   *     or been shut down. The event was not accepted, and the caller must fail the RPC rather than
   *     continue
   */
  void submit(RawBesEvent event, SubmissionCallback callback)
      throws InterruptedException, CaptureRejectedException;

  /** Compatibility convenience for sinks whose caller only needs successful journal notice. */
  default void submit(RawBesEvent event, Runnable onJournaled)
      throws InterruptedException, CaptureRejectedException {
    submit(
        event,
        new SubmissionCallback() {
          @Override
          public void onJournaled() {
            onJournaled.run();
          }

          @Override
          public void onRejected(Throwable failure) {}
        });
  }

  /** Completion of an accepted submission, exactly once in either direction. */
  interface SubmissionCallback {

    void onJournaled();

    void onRejected(Throwable failure);
  }

  /**
   * Reports that a stream ended. Called exactly once per stream, including when it ended badly, so
   * the session records how each stream finished rather than inferring completeness from the
   * absence of an error.
   */
  void streamEnded(BesStreamState finalState);

  /** Reports a stream this server has begun receiving. */
  void streamOpened(BesStreamKey key);

  /** Thrown when the pipeline will not accept any further events. */
  class CaptureRejectedException extends Exception {

    private static final long serialVersionUID = 1L;

    public CaptureRejectedException(String message) {
      super(message);
    }

    public CaptureRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
