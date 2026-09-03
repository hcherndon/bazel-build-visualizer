package com.holtherndon.bazelviz.runner.runtime;

import java.util.Arrays;
import java.util.Objects;

/** The bounded text result of a short command. */
public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {

  public CommandResult {
    Objects.requireNonNull(stdout, "stdout");
    Objects.requireNonNull(stderr, "stderr");
  }

  public boolean isSuccess() {
    return !timedOut && exitCode == 0;
  }

  /** A concise explanation suitable for a preflight error. */
  public String failureDetail() {
    if (timedOut) {
      return "the command did not finish in time";
    }
    String detail = stderr.isBlank() ? stdout : stderr;
    String trimmed = detail.strip();
    if (trimmed.isEmpty()) {
      return "exit code " + exitCode + " with no output";
    }
    String[] lines = trimmed.split("\n", 6);
    return "exit code "
        + exitCode
        + ": "
        + String.join("\n", Arrays.copyOf(lines, Math.min(lines.length, 5)));
  }
}
