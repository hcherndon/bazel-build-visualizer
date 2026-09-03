package com.holtherndon.bazelviz.core.domain;

/**
 * What is known about a target.
 *
 * <p>Three sources say something about a target and they say different things: {@code
 * TargetConfigured} says it exists and what kind it is, {@code TargetComplete} says whether
 * building it succeeded, and {@code Aborted} says the build gave up before either could be
 * established. The states below keep those apart rather than collapsing "not built" into "failed",
 * which would blame a target for a build that was cancelled.
 */
public enum TargetOutcome {

  /**
   * The target was configured. Whether it built is not yet known — either because the build is
   * still going, or because it never got there.
   */
  CONFIGURED,

  /** The target built successfully. */
  BUILT,

  /** The target failed to build. */
  FAILED,

  /**
   * The build stopped before this target reached a conclusion: cancelled, interrupted, or abandoned
   * because something else failed first.
   *
   * <p>Distinct from {@link #FAILED} on purpose. A target skipped because a dependency failed did
   * nothing wrong, and a failures view that listed it alongside the real cause would bury the one
   * thing the user needs to see.
   */
  ABORTED,

  /**
   * The target was deliberately not built — excluded by a pattern, or skipped as incompatible with
   * the target platform.
   */
  SKIPPED;

  public boolean isFailure() {
    return this == FAILED;
  }

  /** True when this target's fate is settled. */
  public boolean isResolved() {
    return this != CONFIGURED;
  }
}
