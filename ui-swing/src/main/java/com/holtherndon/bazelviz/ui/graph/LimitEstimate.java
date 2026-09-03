package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import java.util.Optional;

/**
 * How big a drawing would be, worked out before anyone waits for it.
 *
 * <p>Plan 13.6's task list asks for "limit estimation and warnings". The estimate is cheap — a CSR
 * index knows its node and edge counts without being walked — and it is what lets the view say
 * "this will not fit" while the user is still choosing, rather than after a traversal has run and
 * been refused.
 *
 * <h2>Exact for the whole graph, an upper bound for a traversal</h2>
 *
 * <p>Only a traversal knows how far a traversal reaches, so for a neighbourhood this reports the
 * graph's totals as a ceiling and says that is what it is. Presenting a bound as a measurement is
 * the kind of small dishonesty that makes every other number suspect, so {@link #exact} travels
 * with the numbers and {@link #describe} words them differently.
 *
 * @param exact true when these counts are what would be drawn, false when they are only an upper
 *     bound on it
 * @param fits true when a detailed drawing is possible within the limits
 */
public record LimitEstimate(
    GraphExtract.Mode mode,
    long totalNodes,
    long totalEdges,
    boolean exact,
    boolean fits,
    int nodeLimit,
    int edgeLimit) {

  /** Works out what a request would cost, from the graph's size alone. */
  public static LimitEstimate of(
      GraphExtract.Mode mode, long nodes, long edges, int nodeLimit, int edgeLimit) {
    boolean exact = mode == GraphExtract.Mode.WHOLE;
    boolean fits = nodes <= nodeLimit && edges <= edgeLimit;
    return new LimitEstimate(mode, nodes, edges, exact, fits, nodeLimit, edgeLimit);
  }

  /**
   * The warning, or empty when there is nothing to warn about.
   *
   * <p>Empty rather than a reassuring sentence: a standing "this will fit" notice trains a user to
   * stop reading the place the real warning appears.
   */
  public Optional<String> warning() {
    if (fits) {
      return Optional.empty();
    }
    return Optional.of(describe());
  }

  /** What the view says when this will not fit. */
  public String describe() {
    String size = totalNodes + " actions and " + totalEdges + " dependencies";
    if (exact) {
      return "This build has "
          + size
          + ", more than the "
          + nodeLimit
          + "-action and "
          + edgeLimit
          + "-dependency limit for a detailed drawing.";
    }
    return "This graph holds "
        + size
        + " in total, more than the "
        + nodeLimit
        + "-action limit, so this view may stop before it has drawn everything"
        + " it could reach.";
  }
}
