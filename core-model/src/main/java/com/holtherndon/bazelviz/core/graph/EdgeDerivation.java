package com.holtherndon.bazelviz.core.graph;

/**
 * Which inputs an action edge was derived from.
 *
 * <p>Plan 13.1 step 7: "record whether the edge came from declared or observed inputs". The two
 * produce different graphs — a declared input is one analysis said the action could read, and an
 * observed one is a file the spawn actually consumed — and an action that declares a hundred inputs
 * and reads three has a hundred declared edges and three observed ones. Presenting either as "the"
 * dependency graph would answer a different question than the user asked.
 */
public enum EdgeDerivation {

  /**
   * From {@code aquery}'s declared input depsets.
   *
   * <p>Complete with respect to what analysis knew, and an over-estimate of what execution needed.
   */
  DECLARED,

  /**
   * From the execution log's actual spawn inputs.
   *
   * <p>What was really read, and only for the actions that ran — measured at about a third of the
   * actions the build event stream publishes (finding K1).
   */
  OBSERVED;

  /** The word the UI uses. */
  public String displayName() {
    return this == DECLARED ? "declared inputs" : "observed inputs";
  }
}
