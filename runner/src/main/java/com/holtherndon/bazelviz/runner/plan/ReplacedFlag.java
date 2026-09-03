package com.holtherndon.bazelviz.runner.plan;

import java.util.Objects;

/**
 * A user-supplied option the plan removes or overrides, and the confirmation that permitted it.
 *
 * <p>Plan 8.4: "user-supplied explicit options take precedence unless the user confirms
 * replacement". A replacement therefore cannot be constructed without naming the {@link
 * PlanConflict.Resolution} the user chose — {@link #approvedBy} is not optional. That makes it
 * impossible to build a plan that quietly drops one of the user's flags: the type will not let you.
 *
 * @param original the user's argument, verbatim
 * @param replacement what takes its place, or empty when it is simply removed
 * @param approvedBy the id of the resolution the user selected
 * @param reason why the replacement was necessary
 */
public record ReplacedFlag(String original, String replacement, String approvedBy, String reason) {

  public ReplacedFlag {
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(replacement, "replacement");
    Objects.requireNonNull(approvedBy, "approvedBy");
    Objects.requireNonNull(reason, "reason");
    if (approvedBy.isBlank()) {
      throw new IllegalArgumentException(
          "replacing the user's option " + original + " requires an explicit approval id");
    }
  }

  /** True when the original was dropped rather than substituted. */
  public boolean isRemoval() {
    return replacement.isEmpty();
  }
}
