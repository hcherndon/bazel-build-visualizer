package com.holtherndon.bazelviz.runner.plan;

/**
 * The relative cost of one piece of instrumentation (plan 4.3 "relative overhead: low, medium, or
 * high").
 *
 * <p>Deliberately three coarse buckets and not a percentage. The real cost of {@code
 * --build_event_publish_all_actions} depends on how many actions the build runs, which is exactly
 * what the user does not know before running it, and a fabricated "+3%" would be a number with no
 * measurement behind it (plan 11.4, and rule 14 on causal claims). Buckets say what is known: this
 * one costs more than that one.
 */
public enum Overhead {

  /** Negligible: a flag that changes where existing data goes. */
  LOW,

  /** Noticeable on large builds: more events, another file being written. */
  MEDIUM,

  /** Another analysis pass, or output proportional to the whole action graph. */
  HIGH;

  public String displayName() {
    return switch (this) {
      case LOW -> "low";
      case MEDIUM -> "medium";
      case HIGH -> "high";
    };
  }
}
