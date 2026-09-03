package com.holtherndon.bazelviz.storage.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edge derivation against a hand-built depset DAG.
 *
 * <p>{@code enrichment}'s {@code ActionEdgeAndIndexTest} covers the same recipe through a real
 * aquery file, which proves the import feeds it; this covers it against rows whose shape is chosen,
 * so each step of plan 13.1 — transitive depset reach, source exclusion, deduplication, the
 * self-edge guard, the via-artifact rule — has a fixture that isolates it.
 *
 * <h2>The fixture</h2>
 *
 * <p>Three actions. A produces {@code a.out}. B declares {@code a.out} through depset d1 and
 * produces {@code b.out}. C declares depset d2, whose children are d1 and whose direct artifacts
 * are {@code b.out} and the source file {@code src.c} — so C reaches {@code a.out} only
 * transitively, and the source file reaches nothing because nothing produces it.
 */
final class ActionEdgeDeriverTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void buildFixture() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    exec(
        "INSERT INTO graph_sources"
            + " (id, kind, state, configuration_match, unresolved_artifacts,"
            + " unresolved_depset_references)"
            + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT', 0, 0)");
    exec("INSERT INTO labels (id, value) VALUES (1, '//p:a'), (2, '//p:b'), (3, '//p:c')");
    exec(
        "INSERT INTO artifacts (id, path) VALUES"
            + " (1, 'bin/a.out'), (2, 'bin/b.out'), (3, 'src.c')");
    for (int i = 1; i <= 3; i++) {
      exec(
          "INSERT INTO declared_actions (id, source_id, graph_id, label_id, node_index)"
              + " VALUES ("
              + i
              + ", 1, "
              + i
              + ", "
              + i
              + ", "
              + (i - 1)
              + ")");
    }
    exec(
        "INSERT INTO declared_action_outputs (action_row_id, artifact_id)"
            + " VALUES (1, 1), (2, 2)");
    // d1 = {a.out}; d2 = {b.out, src.c} + child d1.
    exec("INSERT INTO graph_depsets (id, source_id, graph_id) VALUES (1, 1, 1), (2, 1, 2)");
    exec(
        "INSERT INTO graph_depset_artifacts (depset_id, artifact_id)"
            + " VALUES (1, 1), (2, 2), (2, 3)");
    exec("INSERT INTO graph_depset_children (parent_id, child_id) VALUES (2, 1)");
    exec(
        "INSERT INTO declared_action_inputs (action_row_id, depset_id)" + " VALUES (2, 1), (3, 2)");
  }

  @AfterEach
  void closeDatabase() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("a directly declared input becomes one producer-to-consumer edge")
  void directInputsBecomeEdges() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    assertThat(edges()).contains("1->2");
  }

  @Test
  @DisplayName("an artifact reached only through a nested depset still makes its edge")
  void transitiveDepsetsAreExpanded() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    // C's own depset holds b.out; a.out arrives only through d2 -> d1.
    // Plan 10.7 keeps the DAG unflattened in storage, so the derivation
    // must walk it — an edge set that missed this would be the graph of
    // the storage layout, not of the build.
    assertThat(edges()).contains("2->3", "1->3");
  }

  @Test
  @DisplayName("a source file with no producing action makes no edge")
  void sourceFilesMakeNoEdges() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    // src.c is in C's inputs and nothing produces it (plan 13.1 step 4).
    // Three edges, and every one has a real producer.
    assertThat(edges()).hasSize(3);
  }

  @Test
  @DisplayName("an action reaching its own output does not depend on itself")
  void selfEdgesAreExcluded() throws Exception {
    // B's input closure now also contains B's own output, as a shared
    // depset can arrange in a real graph.
    exec("INSERT INTO graph_depset_artifacts (depset_id, artifact_id) VALUES (1, 2)");

    new ActionEdgeDeriver(connection).deriveAll();

    assertThat(scalar("SELECT count(*) FROM action_edges" + " WHERE producer_id = consumer_id"))
        .isZero();
  }

  @Test
  @DisplayName("several artifacts between the same pair are one edge, with no via artifact")
  void multipleArtifactsDeduplicateToOneEdge() throws Exception {
    // A also produces a2.out, and B declares it too: two artifacts, one
    // dependency.
    exec("INSERT INTO artifacts (id, path) VALUES (4, 'bin/a2.out')");
    exec("INSERT INTO declared_action_outputs (action_row_id, artifact_id) VALUES (1, 4)");
    exec("INSERT INTO graph_depset_artifacts (depset_id, artifact_id) VALUES (1, 4)");

    new ActionEdgeDeriver(connection).deriveAll();

    assertThat(
            scalar(
                "SELECT count(*) FROM action_edges" + " WHERE producer_id = 1 AND consumer_id = 2"))
        .isEqualTo(1);
    // When several artifacts explain the pair, no single one is the
    // answer, and null is more honest than whichever sorted first.
    assertThat(
            scalar(
                "SELECT count(*) FROM action_edges"
                    + " WHERE producer_id = 1 AND consumer_id = 2"
                    + "   AND via_artifact_id IS NULL"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("one explaining artifact is kept as the edge's via artifact")
  void singleArtifactIsRecorded() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    assertThat(
            scalar(
                "SELECT via_artifact_id FROM action_edges"
                    + " WHERE producer_id = 1 AND consumer_id = 2"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("re-deriving replaces the previous edges rather than accumulating")
  void rederivingReplaces() throws Exception {
    ActionEdgeDeriver deriver = new ActionEdgeDeriver(connection);
    deriver.deriveAll();
    long first = scalar("SELECT count(*) FROM action_edges");

    deriver.deriveAll();

    assertThat(scalar("SELECT count(*) FROM action_edges")).isEqualTo(first);
  }

  @Test
  @DisplayName("observed edges come from what spawns read, and only for actions that ran")
  void observedEdgesFollowTheExecutionLog() throws Exception {
    // B ran and its spawn read a.out; C never ran. The observed graph
    // therefore has exactly the A -> B edge, however much the declared
    // graph knows about C.
    exec("INSERT INTO actions (id, primary_output, outcome) VALUES (10, 'bin/b.out', 'OK')");
    exec("UPDATE declared_actions SET action_id = 10 WHERE id = 2");
    exec(
        "INSERT INTO enrichment_tasks (id, kind, state)"
            + " VALUES (1, 'EXECUTION_LOG', 'SUCCEEDED')");
    exec("INSERT INTO input_sets (id, task_id, log_id) VALUES (1, 1, 1)");
    exec("INSERT INTO input_set_files (input_set_id, artifact_id) VALUES (1, 1)");
    exec(
        "INSERT INTO action_attempts (id, task_id, log_entry_index, action_id,"
            + " correlation, input_set_id) VALUES (1, 1, 0, 10, 'EXACT', 1)");

    ActionEdgeDeriver.Result result = new ActionEdgeDeriver(connection).deriveAll();

    assertThat(result.observedEdges()).isEqualTo(1);
    assertThat(
            scalar(
                "SELECT count(*) FROM action_edges"
                    + " WHERE derivation = 'OBSERVED'"
                    + "   AND producer_id = 1 AND consumer_id = 2"))
        .isEqualTo(1);
    // And the two derivations stay separate rows: the same dependency
    // counted once per kind, never summed (plan 13.1 step 7).
    assertThat(result.declaredEdges()).isEqualTo(3);
  }

  // ------------------------------------------------------------- plumbing

  private List<String> edges() throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT producer_id, consumer_id FROM action_edges"
                    + " WHERE derivation = 'DECLARED' ORDER BY 1, 2")) {
      while (rows.next()) {
        out.add(rows.getLong(1) + "->" + rows.getLong(2));
      }
    }
    return out;
  }

  private void exec(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long scalar(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }
}
