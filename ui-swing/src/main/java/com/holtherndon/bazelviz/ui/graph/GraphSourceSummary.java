package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the graph-source selector says about a graph before anyone reads it.
 *
 * <p>Pure, so the wording — which is the whole job — is testable without a screen. Plan 8.6: never
 * claim an exact graph match unless the configuration equivalence has been verified, and plan 12.4:
 * do not silently attach uncertain graph data. Both come down to this sentence being right.
 */
public final class GraphSourceSummary {

  private GraphSourceSummary() {}

  /** The label for one source in the selector. */
  public static String label(GraphQueries.GraphSource source) {
    String name = source.displayName();
    if (!source.state().equals("SUCCEEDED")) {
      return name + " — unavailable";
    }
    if (!source.targetScope().permitsExactClaim()) {
      return name
          + (source.targetScope() == GraphTargetScope.REQUESTED_PATTERNS
              ? " — wider target scope"
              : " — scope unverified");
    }
    if (source.configurationMatch().permitsExactClaim()
        && source.actionGraphCompletenessProblem().isPresent()) {
      return name
          + (source.unresolvedArtifacts().isPresent()
                  && source.unresolvedDepsetReferences().isPresent()
              ? " — incomplete"
              : " — completeness unverified");
    }
    return switch (source.configurationMatch()) {
      case EXACT -> name;
      case PARTIAL -> name + " — partial";
      case MISMATCHED -> name + " — different configuration";
      case UNKNOWN -> name + " — unverified";
    };
  }

  /**
   * The sentence shown under the selector.
   *
   * <p>Three separate things a reader needs, and conflating any two of them misleads: whether the
   * graph loaded at all, whether it describes this build, and how much of the build it covers.
   */
  public static String describe(GraphQueries.GraphSource source) {
    if (!source.state().equals("SUCCEEDED")) {
      return "This query did not produce a graph"
          + source.error().map(error -> ": " + error).orElse(".")
          + " Everything else in this session is unaffected.";
    }

    StringBuilder text =
        new StringBuilder(source.configurationMatch().describe(List.of(), List.of()));
    source
        .mismatchDetail()
        .filter(detail -> !source.configurationMatch().permitsExactClaim())
        .ifPresent(
            detail -> {
              text.setLength(0);
              text.append(detail);
            });
    text.append(' ')
        .append(
            source
                .targetScopeDetail()
                .filter(detail -> !detail.isBlank())
                .orElseGet(() -> source.targetScope().describe()));

    boolean labelGraph =
        source.graphKind().filter(kind -> kind == GraphKind.CONFIGURED_TARGETS).isPresent();
    source
        .declaredActions()
        .ifPresent(
            declared -> {
              if (labelGraph) {
                // The importer stores its node count in the same column, and
                // a configured target is not an action; calling it one here
                // would conflate the two graphs this selector exists to keep
                // apart.
                text.append(' ').append(declared).append(" configured targets were analysed.");
                return;
              }
              text.append(' ').append(declared).append(" actions were declared");
              source
                  .correlatedActions()
                  .ifPresent(
                      correlated ->
                          text.append(", of which ")
                              .append(correlated)
                              .append(" also ran in this build"));
              text.append('.');
            });
    if (source.kind().equals("DECLARED_ACTIONS")) {
      if (source.unresolvedArtifacts().isEmpty() || source.unresolvedDepsetReferences().isEmpty()) {
        text.append(
            " Structural completeness was not recorded for this session,"
                + " so dependency-graph completeness is unknown.");
      } else if (source.unresolvedArtifacts().getAsLong() == 0
          && source.unresolvedDepsetReferences().getAsLong() == 0) {
        text.append(
            " Every artifact path and depset reference resolved, so the"
                + " imported dependency structure is complete.");
      } else {
        source
            .actionGraphCompletenessProblem()
            .ifPresent(
                problem ->
                    text.append(' ')
                        .append(Character.toUpperCase(problem.charAt(0)))
                        .append(problem.substring(1))
                        .append('.'));
      }
    }
    // What a node means, said where the graph is chosen: the two graphs
    // share vertices' names and nothing else, and rule 13 forbids letting
    // a user believe one is the other.
    if (labelGraph) {
      text.append(
          " A node is one target label; an edge is a rule input,"
              + " collapsed across configurations. Source files and labels the"
              + " analysis did not cover are not nodes here.");
    } else if (source.graphKind().isPresent()) {
      text.append(
          " A node is one declared action; an edge is a produced"
              + " input, so several actions may serve one target label.");
    }
    return text.toString();
  }

  /**
   * A warning to show above the graph, or empty when there is nothing to warn about.
   *
   * <p>Separate from {@link #describe} because a description is read once and a warning has to
   * survive being ignored: it is what stops a mismatched graph being taken for the build's after
   * the selector scrolls away.
   */
  public static Optional<String> warning(GraphQueries.GraphSource source) {
    if (!source.state().equals("SUCCEEDED")) {
      return Optional.of("No graph. " + source.error().orElse("The query did not run."));
    }
    if (!source.targetScope().permitsExactClaim()) {
      return Optional.of(
          "This graph is not confirmed to have this build's target scope — "
              + source.targetScopeProblem().orElse("target scope is unverified"));
    }
    if (!source.configurationMatch().permitsExactClaim()) {
      String warning =
          "This graph is not confirmed to be this build's — "
              + source.configurationMatch().name().toLowerCase(Locale.ROOT)
              + " configuration match.";
      return Optional.of(
          source
              .actionGraphCompletenessProblem()
              .map(problem -> warning + " It is also not confirmed complete — " + problem + ".")
              .orElse(warning));
    }
    return source
        .actionGraphCompletenessProblem()
        .map(problem -> "This graph is not confirmed complete — " + problem + ".");
  }

  /**
   * The graph a session should show first.
   *
   * <p>A trustworthy one if there is one, otherwise the first that loaded at all, otherwise
   * nothing. Preferring a trustworthy graph is not hiding the others — they stay in the selector —
   * it is choosing which one a user who makes no choice ends up reading.
   */
  public static Optional<GraphQueries.GraphSource> preferred(
      List<GraphQueries.GraphSource> sources) {
    return sources.stream()
        .filter(GraphQueries.GraphSource::isTrustworthy)
        .findFirst()
        .or(
            () ->
                sources.stream().filter(source -> source.state().equals("SUCCEEDED")).findFirst());
  }
}
