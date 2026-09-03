package com.holtherndon.bazelviz.core.graph;

/** How an auxiliary graph query chose the targets it analysed. */
public enum GraphTargetScope {

  /** The complete BEP confirms that its completed labels are the invocation's exact label scope. */
  EXACT_BEP_TARGETS,

  /** No completed BEP target was available, so the query reused the requested target patterns. */
  REQUESTED_PATTERNS,

  /** Scope evidence is absent, failed, migrated, or came from an incomplete BEP. */
  UNKNOWN;

  /** True only when the query's target population is confirmed to be this build's. */
  public boolean permitsExactClaim() {
    return this == EXACT_BEP_TARGETS;
  }

  /** Default user-facing explanation when no more specific detail was recorded. */
  public String describe() {
    return switch (this) {
      case EXACT_BEP_TARGETS ->
          "The query used completed top-level target labels that this build's complete BEP"
              + " confirms are its exact target scope.";
      case REQUESTED_PATTERNS ->
          "The build reported no completed top-level targets, so the query reused the requested"
              + " patterns. Its target scope may be wider than this build.";
      case UNKNOWN ->
          "How this query chose its target scope was not recorded, so its scope is"
              + " unverified.";
    };
  }
}
