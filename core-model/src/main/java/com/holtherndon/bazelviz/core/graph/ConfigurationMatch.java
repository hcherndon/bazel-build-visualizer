package com.holtherndon.bazelviz.core.graph;

import java.util.List;
import java.util.Optional;

/**
 * Whether an auxiliary query reproduced the configuration the build actually ran under.
 *
 * <h2>Why this decides what a graph may claim</h2>
 *
 * <p>Plan 8.6 ends with "never claim an exact graph match unless the configuration equivalence has
 * been verified", and plan 12.4 requires a graph that could not reproduce the primary invocation's
 * configuration to be marked {@code PARTIAL_OR_MISMATCHED} with the changed options listed. An
 * {@code aquery} run minutes after a build, with options the query rejects stripped out, may
 * analyse a different graph than the one that executed — and the difference is silent, because both
 * are valid action graphs.
 *
 * <p>The check is possible because {@code Configuration.checksum} in {@code aquery}'s output equals
 * the configuration id the build event stream published: measured on all four supported Bazel
 * versions, every checksum matched, none left over (finding Q6 in {@code
 * docs/aquery-and-cquery.md}). So "did this query see the build's configurations" is a set
 * comparison rather than a judgement.
 */
public enum ConfigurationMatch {

  /**
   * Every configuration the query reported was one the build declared.
   *
   * <p>The only state in which the graph may be presented as the build's.
   */
  EXACT,

  /**
   * The query reported a configuration the build never declared.
   *
   * <p>The graph describes some other analysis. Its actions are real and its edges are real, and
   * they are not necessarily this build's.
   */
  MISMATCHED,

  /**
   * Some of the build's configurations are missing from the query's output.
   *
   * <p>Not wrong, but not everything: the graph covers part of what ran.
   */
  PARTIAL,

  /**
   * The comparison could not be made.
   *
   * <p>Reached when the session has no configurations to compare against — an imported graph with
   * no build, for instance. Distinct from {@link #MISMATCHED}, which is a checked negative.
   */
  UNKNOWN;

  /** True when the graph may be described as this build's action graph. */
  public boolean permitsExactClaim() {
    return this == EXACT;
  }

  /**
   * The sentence the UI shows, given the configurations involved.
   *
   * @param missing configurations the build declared and the query did not report
   * @param extra configurations the query reported and the build never declared
   */
  public String describe(List<String> missing, List<String> extra) {
    return switch (this) {
      case EXACT ->
          "This graph was analysed under the same configurations the build ran" + " under.";
      case MISMATCHED ->
          "This graph includes "
              + extra.size()
              + " configuration"
              + (extra.size() == 1 ? "" : "s")
              + " the build never used, so it is not"
              + " this build's action graph. The actions and edges in it are real;"
              + " which of them this build would have run is not established.";
      case PARTIAL ->
          "This graph is missing "
              + missing.size()
              + " of the"
              + " configurations the build ran under, so it covers part of what"
              + " happened and not all of it.";
      case UNKNOWN ->
          "There is nothing to compare this graph's configurations against,"
              + " so whether it matches the build is unknown.";
    };
  }

  /**
   * The verdict for a query that reported {@code queried} configurations against a build that
   * declared {@code declared}.
   *
   * @param declared configuration ids from the build event stream; empty makes the answer {@link
   *     #UNKNOWN} rather than a guess
   */
  public static ConfigurationMatch of(List<String> declared, List<String> queried) {
    if (declared.isEmpty() || queried.isEmpty()) {
      return UNKNOWN;
    }
    boolean extra = queried.stream().anyMatch(id -> !declared.contains(id));
    if (extra) {
      // Checked first: a graph carrying a configuration the build never
      // used is describing something else, and that matters more than
      // whether it also covers everything.
      return MISMATCHED;
    }
    return queried.containsAll(declared) ? EXACT : PARTIAL;
  }

  /** The state to record when the query itself failed. */
  public static Optional<ConfigurationMatch> forFailedQuery() {
    return Optional.of(UNKNOWN);
  }
}
