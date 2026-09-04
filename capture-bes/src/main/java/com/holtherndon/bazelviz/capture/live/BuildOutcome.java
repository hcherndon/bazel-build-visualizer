package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import java.util.Objects;
import java.util.Optional;

/** The sole, conservative classification of a managed Bazel invocation's build result. */
public enum BuildOutcome {
  NOT_STARTED,
  CANCELLED,
  UNKNOWN_PROCESS,
  UNKNOWN_BES_TRANSPORT,
  SUCCEEDED,
  FAILED;

  /** Bazel's exit code when its Build Event Protocol upload failed. */
  public static final int BES_TRANSPORT_FAILURE_EXIT = 38;

  public static BuildOutcome classify(
      Optional<ProcessOutcome> process, Optional<CaptureSummary> capture) {
    Objects.requireNonNull(process, "process");
    Objects.requireNonNull(capture, "capture");
    if (process.isEmpty()) {
      return NOT_STARTED;
    }
    ProcessOutcome outcome = process.orElseThrow();
    if (outcome.wasCancelled()) {
      return CANCELLED;
    }
    if (outcome.failure().isPresent() || outcome.exitCode().isEmpty()) {
      return UNKNOWN_PROCESS;
    }
    if (outcome.exitCode().orElseThrow() == BES_TRANSPORT_FAILURE_EXIT
        || capture.isEmpty()
        || !capture.orElseThrow().isComplete()) {
      return UNKNOWN_BES_TRANSPORT;
    }
    return outcome.exitCode().orElseThrow() == 0 ? SUCCEEDED : FAILED;
  }

  public boolean isKnown() {
    return this == SUCCEEDED || this == FAILED;
  }
}
