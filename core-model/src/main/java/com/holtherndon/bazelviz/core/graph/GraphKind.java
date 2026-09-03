package com.holtherndon.bazelviz.core.graph;

import com.holtherndon.bazelviz.core.source.DataSource;

/**
 * Which of the six graphs a relationship belongs to (plan 2.1).
 *
 * <h2>Why this exists as a type</h2>
 *
 * <p>Plan 2.1 lists six distinct graphs and ends with a requirement: "every UI label must name the
 * graph explicitly". They are genuinely different objects that happen to share vertices, and
 * conflating them is the most damaging thing this application could do — a user shown a dependency
 * edge that is really a BEP parent/child announcement, or a temporal overlap presented as
 * causation, would draw conclusions the data does not support and act on them.
 *
 * <p>Making it an enum rather than a documentation note means an edge cannot be stored, queried or
 * displayed without saying which graph it is from.
 */
public enum GraphKind {

  /**
   * Parent events announcing child event identifiers.
   *
   * <p>Useful for protocol completeness and for debugging the stream. It is <em>not</em> a
   * dependency graph, and the resemblance is the trap: a target's completion event announces its
   * actions, which looks like "target depends on action" and is really "this event introduced that
   * event".
   */
  BEP_EVENTS("BEP event graph", DataSource.BEP),

  /** Unconfigured target relationships, from {@code query}. */
  TARGETS("target graph", DataSource.QUERY),

  /** Configuration-aware target relationships, from {@code cquery}. */
  CONFIGURED_TARGETS("configured-target graph", DataSource.CQUERY),

  /**
   * Declared actions with their declared inputs and outputs, from {@code aquery}. What Bazel
   * <em>intended</em> to run.
   */
  DECLARED_ACTIONS("declared action graph", DataSource.AQUERY),

  /**
   * Actions and attempts that actually executed or were checked against a cache, from execution
   * logs and observed artifacts. What Bazel <em>did</em>.
   */
  OBSERVED_EXECUTION("observed execution graph", DataSource.EXECUTION_LOG),

  /**
   * Action overlap over time, derived from recorded timestamps.
   *
   * <p>Plan 2.1 is explicit that temporal overlap must not be presented as dependency, and {@link
   * #impliesDependency()} is how that is enforced rather than remembered: two actions running at
   * the same moment tell you about machine capacity, not about what needed what.
   */
  TEMPORAL("temporal execution view", DataSource.DERIVED);

  private final String displayName;
  private final DataSource primarySource;

  GraphKind(String displayName, DataSource primarySource) {
    this.displayName = displayName;
    this.primarySource = primarySource;
  }

  /** The name a UI must show alongside anything drawn from this graph. */
  public String displayName() {
    return displayName;
  }

  /** Where this graph normally comes from. */
  public DataSource primarySource() {
    return primarySource;
  }

  /**
   * Whether an edge in this graph means "needed by".
   *
   * <p>False for the event graph and for the temporal view, which are the two that look most like
   * dependencies and are not.
   */
  public boolean impliesDependency() {
    return switch (this) {
      case TARGETS, CONFIGURED_TARGETS, DECLARED_ACTIONS, OBSERVED_EXECUTION -> true;
      case BEP_EVENTS, TEMPORAL -> false;
    };
  }

  /**
   * Whether this graph is complete enough to answer "what does X depend on" without qualification.
   *
   * <p>Only the declared action graph is, and only when {@code aquery} supplied it. Plan rule 13
   * forbids claiming a graph is complete unless its source supports the claim, and the BEP alone
   * never does.
   */
  public boolean canClaimCompleteness() {
    return this == DECLARED_ACTIONS || this == CONFIGURED_TARGETS || this == TARGETS;
  }
}
