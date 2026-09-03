package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import java.util.Objects;
import java.util.Optional;

/**
 * What the capture status panel shows, as a plain value.
 *
 * <p>A record rather than mutable widget state so the panel can be tested without a display and so
 * a snapshot handed to the EDT cannot change under it while it is being painted.
 *
 * @param phase where the capture is
 * @param progress the counters, absent before anything has been received
 * @param detail one line of context: the command, the endpoint, or why it stopped
 * @param cancellable whether the stop controls should be enabled
 */
public record CaptureStatusModel(
    Phase phase, Optional<CaptureProgress> progress, String detail, boolean cancellable) {

  /** The stages a launched capture passes through, as the user sees them. */
  public enum Phase {
    IDLE("No build running"),
    PREPARING("Preparing"),
    WAITING("Waiting for Bazel"),
    CAPTURING("Capturing"),
    FINISHING("Finishing"),
    DONE("Finished"),
    CANCELLED("Cancelled"),
    FAILED("Failed");

    private final String label;

    Phase(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean isTerminal() {
      return this == DONE || this == CANCELLED || this == FAILED;
    }
  }

  public CaptureStatusModel {
    Objects.requireNonNull(phase, "phase");
    progress = Objects.requireNonNull(progress, "progress");
    Objects.requireNonNull(detail, "detail");
  }

  public static CaptureStatusModel idle() {
    return new CaptureStatusModel(Phase.IDLE, Optional.empty(), "", false);
  }

  public CaptureStatusModel withPhase(Phase next, String detail) {
    return new CaptureStatusModel(next, progress, detail, !next.isTerminal() && next != Phase.IDLE);
  }

  public CaptureStatusModel withProgress(CaptureProgress value) {
    return new CaptureStatusModel(
        phase == Phase.WAITING ? Phase.CAPTURING : phase, Optional.of(value), detail, cancellable);
  }

  /**
   * The counters as one line.
   *
   * <p>All three are shown, because the differences between them are the only visible sign of
   * pressure: received minus journaled is what a crash would lose, and journaled minus indexed is
   * why the tables lag the console.
   */
  public String counterLine() {
    return progress
        .map(
            value ->
                "%,d received · %,d journaled · %,d indexed%s"
                    .formatted(
                        value.received(),
                        value.journaled(),
                        value.normalized(),
                        value.lagged() ? "  ⚠ capture lagging" : ""))
        .orElse("no events yet");
  }
}
