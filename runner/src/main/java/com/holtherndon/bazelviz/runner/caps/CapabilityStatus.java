package com.holtherndon.bazelviz.runner.caps;

/**
 * Whether a {@link Capability} was observed on a particular binary.
 *
 * <p>Three values, not two. {@link #UNKNOWN} is the state a capability is in when the probe itself
 * failed — the binary would not run, the help output was unparseable, the process timed out — and
 * it is deliberately different from {@link #UNSUPPORTED}. Collapsing them would let a probe failure
 * be displayed as a fact about the user's Bazel (plan 11.4: unavailable is never rendered as a
 * value), and would let the planner quietly drop instrumentation because a subprocess was slow.
 *
 * <p>The planner treats {@code UNKNOWN} as "do not inject, and say why", which is the same action
 * as {@code UNSUPPORTED} but a different sentence.
 */
public enum CapabilityStatus {

  /** The binary accepts the flag; observed, not inferred. */
  SUPPORTED,

  /** The binary was probed successfully and does not accept the flag. */
  UNSUPPORTED,

  /** The probe did not produce an answer. Not the same as unsupported. */
  UNKNOWN;

  public boolean isSupported() {
    return this == SUPPORTED;
  }

  /** True when the planner must not inject the flag. */
  public boolean blocksInjection() {
    return this != SUPPORTED;
  }
}
