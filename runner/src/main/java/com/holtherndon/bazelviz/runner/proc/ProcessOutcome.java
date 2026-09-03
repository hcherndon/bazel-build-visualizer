package com.holtherndon.bazelviz.runner.proc;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * How a launched Bazel process ended.
 *
 * <p>{@link #exitCode} is an {@link OptionalInt} because a process this application killed, or one
 * whose wait was interrupted, has no exit code that means anything — and reporting {@code 0} or
 * {@code -1} for it would be an unavailable value rendered as a number (plan 11.4). {@link
 * #terminatedBy} says which stop was used, so a session can distinguish "the user cancelled" from
 * "Bazel failed", which are different sessions with different states.
 *
 * <p>Bazel's own exit codes are recorded but not interpreted here: 1 is a build failure, 2 a
 * command-line error, 8 an interrupt, 37 an out-of-memory abort, and the set grows between
 * versions. Mapping them belongs where the version is known, not in a record that only observed the
 * number.
 *
 * @param exitCode the process exit status, absent when it was killed or the wait did not complete
 * @param terminatedBy the stop this application applied, absent when the process ended on its own
 * @param duration wall-clock time from launch to exit
 * @param failure the exception that ended the wait, when one did
 */
public record ProcessOutcome(
    OptionalInt exitCode,
    Optional<CancellationMode> terminatedBy,
    Duration duration,
    Optional<Throwable> failure) {

  public ProcessOutcome {
    exitCode = Objects.requireNonNull(exitCode, "exitCode");
    terminatedBy = Objects.requireNonNull(terminatedBy, "terminatedBy");
    Objects.requireNonNull(duration, "duration");
    failure = Objects.requireNonNull(failure, "failure");
  }

  public static ProcessOutcome exited(int code, Duration duration) {
    return new ProcessOutcome(OptionalInt.of(code), Optional.empty(), duration, Optional.empty());
  }

  public static ProcessOutcome cancelled(
      OptionalInt code, CancellationMode mode, Duration duration) {
    return new ProcessOutcome(code, Optional.of(mode), duration, Optional.empty());
  }

  public static ProcessOutcome failed(Throwable cause, Duration duration) {
    return new ProcessOutcome(OptionalInt.empty(), Optional.empty(), duration, Optional.of(cause));
  }

  /** True when Bazel reported success on its own. */
  public boolean isSuccess() {
    return terminatedBy.isEmpty() && failure.isEmpty() && exitCode.orElse(-1) == 0;
  }

  /** True when this application stopped the process. */
  public boolean wasCancelled() {
    return terminatedBy.isPresent();
  }
}
