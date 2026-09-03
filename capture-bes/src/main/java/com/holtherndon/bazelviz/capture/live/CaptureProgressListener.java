package com.holtherndon.bazelviz.capture.live;

/**
 * Receives capture progress, no faster than the configured interval (plan 9.3: UI aggregate
 * publication no faster than four times per second).
 *
 * <p>Called from the pipeline threads, never the EDT. An implementation that updates Swing must hop
 * threads itself, and must not block: the thread it is called on is the one writing the journal.
 */
@FunctionalInterface
public interface CaptureProgressListener {

  void progressed(CaptureProgress progress);

  /** A listener that ignores everything, for headless callers. */
  static CaptureProgressListener ignoring() {
    return progress -> {};
  }
}
