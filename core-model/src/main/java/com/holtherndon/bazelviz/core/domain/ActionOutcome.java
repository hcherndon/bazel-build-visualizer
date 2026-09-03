package com.holtherndon.bazelviz.core.domain;

/**
 * What is known about a logical action's result.
 *
 * <h2>Completion-oriented, deliberately</h2>
 *
 * <p>Plan 2.1 is explicit that the BEP does not promise to announce an action when it starts:
 * events arrive when Bazel publishes them, which for an action is normally when it finishes. So
 * there is no {@code RUNNING} constant here, and there is no way to spell one.
 *
 * <p>That is not an omission. Inferring "running" from an announced event identifier is named in
 * the plan as the thing not to do, and a UI given a {@code RUNNING} state would infer it — an
 * action announced as a child of a target's completion has not necessarily started, may never
 * start, and may already have finished. {@link #RECEIVED} is the honest name for "we have an event
 * about this action", and every label built from these values says "received" or "completed" rather
 * than "started".
 */
public enum ActionOutcome {

  /**
   * An event named this action but did not say how it ended.
   *
   * <p>Reached, for example, when a target's completion announces an action whose own event never
   * arrived — the action is known to exist and nothing more.
   */
  RECEIVED,

  /** The action completed and reported success. */
  SUCCEEDED,

  /** The action completed and reported failure. */
  FAILED;

  /** True when the action's own event was seen and carried a result. */
  public boolean isResolved() {
    return this != RECEIVED;
  }

  /**
   * The outcome Bazel's {@code ActionExecuted.success} flag implies.
   *
   * <p>A boolean is enough here precisely because the event only exists for an action that
   * finished; the third state comes from actions that have no event at all.
   */
  public static ActionOutcome ofSuccessFlag(boolean success) {
    return success ? SUCCEEDED : FAILED;
  }
}
