package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Derives producer-to-consumer action edges from the inputs each action names.
 *
 * <h2>Plan 13.1, in SQL</h2>
 *
 * <p>The recipe is: resolve each action's input artifacts, find each artifact's producing action,
 * emit an edge, exclude source artifacts that have no producer, and deduplicate. Every step of that
 * is a join or a {@code GROUP BY}, so it is written as one statement rather than as a loop in Java.
 *
 * <h2>Why the external sort is SQLite's and not ours</h2>
 *
 * <p>Plan 13.1 also specifies external sorting for very large edge sets — bounded buffers, sorted
 * runs, a merge, a dedup. SQLite already does exactly that: a {@code GROUP BY} it cannot satisfy
 * from an index becomes an external merge sort spilling to its temp store, with the same
 * bounded-buffer shape and a great deal more testing behind it than a fresh implementation would
 * have.
 *
 * <p>So this delegates rather than reimplements, and says so. What matters for plan 24's "large
 * graph construction is bounded-memory" is that no step holds the edge set, and none does: the
 * derivation never leaves the database, and the CSR build streams the result twice.
 *
 * <h2>Expanding the depset DAG without flattening it</h2>
 *
 * <p>An action names input <em>sets</em>, and the sets form a DAG with shared subtrees — which is
 * why plan 10.7 forbids storing the flattened form. Getting from sets to artifacts still requires
 * walking it, and the recursive CTE below uses {@code UNION} rather than {@code UNION ALL} so a
 * subtree reachable by a thousand paths is visited once. With {@code UNION ALL} this is exponential
 * in the sharing, which is precisely what a build's dependency graph has a lot of.
 */
public final class ActionEdgeDeriver {

  /**
   * Every (action, depset) pair reachable from the action's declared inputs.
   *
   * <p>{@code UNION} deduplicates on the way, so the shared subtrees of the DAG are traversed once
   * each rather than once per path to them.
   */
  private static final String REACHABLE_DEPSETS =
      "WITH RECURSIVE reach(action_row_id, depset_id) AS ("
          + "   SELECT action_row_id, depset_id FROM declared_action_inputs"
          + "   UNION"
          + "   SELECT r.action_row_id, c.child_id"
          + "     FROM reach r JOIN graph_depset_children c ON c.parent_id = r.depset_id)";

  /**
   * The declared edges.
   *
   * <p>An artifact with no producing action is a source file, and step 4 of plan 13.1 excludes it —
   * which the inner join does by construction rather than by a filter that could be forgotten.
   *
   * <p>{@code via_artifact_id} is kept only when exactly one artifact explains the pair. When
   * several do, no single one is the answer, and the {@code CASE} returns null rather than
   * whichever the sort happened to put first.
   *
   * <p>Producers are looked up through {@code declared_action_outputs}, not through {@code
   * primary_output_id}: an action's non-primary outputs are real outputs and something consumes
   * them.
   */
  private static final String DERIVE_DECLARED =
      REACHABLE_DEPSETS
          + " INSERT INTO action_edges (producer_id, consumer_id, derivation,"
          + " via_artifact_id)"
          + " SELECT producer, consumer, '"
          + "DECLARED"
          + "',"
          + "   CASE WHEN count(DISTINCT artifact) = 1 THEN min(artifact) END"
          + " FROM ("
          + "   SELECT o.action_row_id AS producer, r.action_row_id AS consumer,"
          + "          ga.artifact_id AS artifact"
          + "     FROM reach r"
          + "     JOIN graph_depset_artifacts ga ON ga.depset_id = r.depset_id"
          + "     JOIN declared_action_outputs o ON o.artifact_id = ga.artifact_id"
          + "    WHERE o.action_row_id <> r.action_row_id)"
          + " GROUP BY producer, consumer"
          + " ON CONFLICT DO NOTHING";

  /**
   * The observed edges: what the spawns actually read.
   *
   * <p>A different graph from the declared one, and both are true — an action that declares a
   * hundred inputs and reads three has a hundred declared edges and three observed ones (plan 13.1
   * step 7). Only actions that ran appear, which is about a third of them (finding K1).
   *
   * <p>Endpoints are {@code declared_actions} rows either way, so an observed edge exists only
   * where both ends were also declared. An execution the graph does not know about — {@code
   * stable-status.txt}, which aquery never declares (Q7) — contributes no edge, and that is
   * recorded as a coverage fact rather than papered over with a synthetic node.
   */
  private static final String DERIVE_OBSERVED =
      "WITH RECURSIVE reach(attempt_id, input_set_id) AS ("
          + "   SELECT id, input_set_id FROM action_attempts"
          + "    WHERE input_set_id IS NOT NULL"
          + "   UNION"
          + "   SELECT r.attempt_id, c.child_id"
          + "     FROM reach r JOIN input_set_children c ON c.parent_id = r.input_set_id)"
          + " INSERT INTO action_edges (producer_id, consumer_id, derivation,"
          + " via_artifact_id)"
          + " SELECT producer, consumer, '"
          + "OBSERVED"
          + "',"
          + "   CASE WHEN count(DISTINCT artifact) = 1 THEN min(artifact) END"
          + " FROM ("
          + "   SELECT po.action_row_id AS producer, cd.id AS consumer,"
          + "          isf.artifact_id AS artifact"
          + "     FROM reach r"
          + "     JOIN input_set_files isf ON isf.input_set_id = r.input_set_id"
          + "     JOIN declared_action_outputs po ON po.artifact_id = isf.artifact_id"
          + "     JOIN action_attempts att ON att.id = r.attempt_id"
          + "     JOIN declared_actions cd ON cd.action_id = att.action_id"
          + "    WHERE po.action_row_id <> cd.id)"
          + " GROUP BY producer, consumer"
          + " ON CONFLICT DO NOTHING";

  private final Connection connection;

  public ActionEdgeDeriver(Connection connection) {
    this.connection = connection;
  }

  /**
   * Derives every edge of both kinds.
   *
   * @return how many edges of each kind now exist
   */
  public Result deriveAll() throws SQLException {
    long declared = derive(EdgeDerivation.DECLARED);
    long observed = derive(EdgeDerivation.OBSERVED);
    return new Result(declared, observed);
  }

  /** Derives edges of one kind, replacing any previously derived. */
  public long derive(EdgeDerivation derivation) throws SQLException {
    try (PreparedStatement delete =
        connection.prepareStatement("DELETE FROM action_edges WHERE derivation = ?")) {
      delete.setString(1, derivation.name());
      delete.executeUpdate();
    }
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          derivation == EdgeDerivation.DECLARED ? DERIVE_DECLARED : DERIVE_OBSERVED);
    }
    return count(derivation);
  }

  /** How many edges of this kind exist. */
  public long count(EdgeDerivation derivation) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM action_edges WHERE derivation = ?")) {
      statement.setString(1, derivation.name());
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : 0;
      }
    }
  }

  /**
   * Edges derived, by kind.
   *
   * <p>The two are reported separately and never summed: the same producer/consumer pair usually
   * appears in both, and adding them would count one dependency twice.
   */
  public record Result(long declaredEdges, long observedEdges) {}
}
