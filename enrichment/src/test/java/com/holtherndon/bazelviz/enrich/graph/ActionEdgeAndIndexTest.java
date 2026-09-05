package com.holtherndon.bazelviz.enrich.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.Bfs;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.ActionEdgeDeriver;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * From a real action graph to a queryable one.
 *
 * <p>The fixture's four genrules form a chain — {@code gen_a} produces {@code a.txt}, which {@code
 * gen_b} consumes to produce {@code b.txt}, which {@code gen_slow} consumes — so the derived edges
 * have a shape that can be checked rather than merely counted.
 */
final class ActionEdgeAndIndexTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void importGraph() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
    new ActionGraphImporter(connection)
        .importFrom(fixture("bazel920-aquery.proto"), List.of("aquery", "//pkg:all"));
  }

  @AfterEach
  void closeSession() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("the genrule chain becomes producer-to-consumer edges")
  void edgesFollowTheChain() throws Exception {
    ActionEdgeDeriver.Result result = new ActionEdgeDeriver(connection).deriveAll();

    assertThat(result.declaredEdges()).isPositive();
    // gen_a produces a.txt; gen_b consumes it. That is one edge, in that
    // direction, and it is the shape of every real dependency.
    assertThat(edgeLabels())
        .contains("//pkg:gen_a -> //pkg:gen_b", "//pkg:gen_b -> //pkg:gen_slow");
  }

  @Test
  @DisplayName("a source file with no producing action makes no edge")
  void sourceFilesAreExcluded() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    // Plan 13.1 step 4. An action's inputs include source files and tool
    // binaries from other repositories; only inputs some action in this
    // graph produced become edges, which the inner join enforces.
    assertThat(
            scalar(
                "SELECT count(*) FROM action_edges e"
                    + " WHERE NOT EXISTS (SELECT 1 FROM declared_actions d"
                    + "                   WHERE d.id = e.producer_id)"))
        .isZero();
  }

  @Test
  @DisplayName("an edge is recorded once however many artifacts justify it")
  void edgesAreDeduplicated() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    // Plan 13.1 step 5. Two actions can be joined by several artifacts and
    // are still one dependency.
    assertThat(
            scalar(
                "SELECT count(*) FROM ("
                    + " SELECT producer_id, consumer_id, derivation, count(*) AS n"
                    + " FROM action_edges GROUP BY 1, 2, 3 HAVING n > 1)"))
        .isZero();
  }

  @Test
  @DisplayName("an action never depends on itself")
  void noSelfEdges() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();

    // An action's outputs can appear in its own input closure through a
    // shared depset; that is not a dependency and would make the graph
    // cyclic at every node.
    assertThat(scalar("SELECT count(*) FROM action_edges" + " WHERE producer_id = consumer_id"))
        .isZero();
  }

  @Test
  @DisplayName("forward and reverse indexes are built and agree")
  void indexesAgree() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, tempDir.resolve("index"));

    Optional<GraphIndexBuilder.Result> built = builder.build(EdgeDerivation.DECLARED);

    assertThat(built).isPresent();
    try (CsrGraph forward =
            CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD").orElseThrow());
        CsrGraph reverse =
            CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "REVERSE").orElseThrow())) {
      // Plan 24's exit criterion, checked rather than assumed: every forward
      // edge is a reverse edge with its ends swapped.
      assertThat(reverse.edgeCount()).isEqualTo(forward.edgeCount());
      assertThat(reverse.nodeCount()).isEqualTo(forward.nodeCount());
      for (int node = 0; node < forward.nodeCount(); node++) {
        for (int neighbor : neighbors(forward, node)) {
          assertThat(neighbors(reverse, neighbor))
              .as("reverse edge %d <- %d", neighbor, node)
              .contains(node);
        }
      }
    }
  }

  @Test
  @DisplayName("dependencies and reverse dependencies are both reachable")
  void bothDirectionsAreQueryable() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, tempDir.resolve("index"));
    builder.build(EdgeDerivation.DECLARED);

    int genA = nodeIndexOf("//pkg:gen_a", "Genrule");
    try (CsrGraph forward =
            CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD").orElseThrow());
        CsrGraph reverse =
            CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "REVERSE").orElseThrow())) {
      // Forward from gen_a reaches gen_b and gen_slow down the chain.
      assertThat(new Bfs(forward).run(genA, 1000, 10)).isGreaterThan(1);
      // gen_a is at the head of the chain, so nothing produces its inputs.
      assertThat(neighbors(reverse, genA)).isEmpty();
    }
  }

  @Test
  @DisplayName("a neighbourhood can be bounded by depth and by node count")
  void traversalsAreBudgeted() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, tempDir.resolve("index"));
    builder.build(EdgeDerivation.DECLARED);
    int genA = nodeIndexOf("//pkg:gen_a", "Genrule");

    try (CsrGraph forward =
        CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD").orElseThrow())) {
      // Plan 13.3: depth-limited and node-budget-limited traversal, so a
      // neighbourhood view never walks a whole build.
      long depthOne = new Bfs(forward).run(genA, 1000, 1);
      long unlimited = new Bfs(forward).run(genA, 1000, 100);
      assertThat(depthOne).isLessThanOrEqualTo(unlimited);
      assertThat(new Bfs(forward).run(genA, 1, 100)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an index whose file no longer matches the registry is refused")
  void staleIndexesAreRefused() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();
    Path directory = tempDir.resolve("index");
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, directory);
    builder.build(EdgeDerivation.DECLARED);

    // The registry still describes the old file. A stale index answers
    // rather than refusing, which is why this is checked before use.
    exec(
        "UPDATE graph_indexes SET edge_count = edge_count + 1"
            + " WHERE kind = 'DECLARED' AND direction = 'FORWARD'");

    assertThatThrownBy(() -> builder.descriptor(EdgeDerivation.DECLARED, "FORWARD"))
        .isInstanceOf(GraphIndexBuilder.StaleIndexException.class)
        .hasMessageContaining("stale");
  }

  @Test
  @DisplayName("a session with no graph builds no index and does not fail")
  void noGraphIsNotAFailure() throws Exception {
    // In foreign-key order. SQLite refuses the shortcut, which is the
    // constraint doing its job: nothing may leave a declared_action_inputs
    // row pointing at an action that no longer exists.
    exec("DELETE FROM action_edges");
    exec("DELETE FROM declared_action_inputs");
    exec("DELETE FROM declared_action_outputs");
    exec("DELETE FROM declared_actions");

    assertThat(
            new GraphIndexBuilder(connection, tempDir.resolve("empty"))
                .build(EdgeDerivation.DECLARED))
        .isEmpty();
  }

  @Test
  @DisplayName("rebuilding replaces the registered index rather than adding one")
  void rebuildReplaces() throws Exception {
    new ActionEdgeDeriver(connection).deriveAll();
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, tempDir.resolve("index"));
    builder.build(EdgeDerivation.DECLARED);
    builder.build(EdgeDerivation.DECLARED);

    assertThat(scalar("SELECT count(*) FROM graph_indexes")).isEqualTo(2);
  }

  @Test
  @DisplayName("re-importing the graph replaces it rather than doubling it")
  void reimportReplaces() throws Exception {
    long first = scalar("SELECT count(*) FROM declared_actions");

    new ActionGraphImporter(connection)
        .importFrom(fixture("bazel920-aquery.proto"), List.of("aquery", "//pkg:all"));

    assertThat(scalar("SELECT count(*) FROM declared_actions")).isEqualTo(first);
    // And the dense node numbering starts again at zero, because a CSR is
    // two arrays indexed from zero with no room for gaps.
    assertThat(scalar("SELECT min(node_index) FROM declared_actions")).isZero();
    assertThat(scalar("SELECT max(node_index) FROM declared_actions")).isEqualTo(first - 1);
  }

  // ---------------------------------------------------------------- helpers

  private int nodeIndexOf(String label, String mnemonic) throws SQLException {
    return Math.toIntExact(
        scalar(
            "SELECT da.node_index FROM declared_actions da"
                + " JOIN labels l ON l.id = da.label_id"
                + " JOIN mnemonics m ON m.id = da.mnemonic_id"
                + " WHERE l.value = '"
                + label
                + "' AND m.value = '"
                + mnemonic
                + "'"));
  }

  private List<String> edgeLabels() throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement s = connection.createStatement();
        ResultSet rows =
            s.executeQuery(
                "SELECT pl.value || ' -> ' || cl.value FROM action_edges e"
                    + " JOIN declared_actions p ON p.id = e.producer_id"
                    + " JOIN declared_actions c ON c.id = e.consumer_id"
                    + " JOIN labels pl ON pl.id = p.label_id"
                    + " JOIN labels cl ON cl.id = c.label_id"
                    + " WHERE e.derivation = 'DECLARED'")) {
      while (rows.next()) {
        out.add(rows.getString(1));
      }
    }
    return out;
  }

  private static List<Integer> neighbors(CsrGraph graph, int node) {
    List<Integer> out = new ArrayList<>();
    graph.forEachNeighbor(node, out::add);
    return out;
  }

  private Path fixture(String name) throws IOException {
    Path target = tempDir.resolve(name);
    try (InputStream in = getClass().getResourceAsStream("/graph/" + name)) {
      if (in == null) {
        throw new IOException("missing fixture " + name);
      }
      Files.write(target, in.readAllBytes());
    }
    return target;
  }

  private void exec(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long scalar(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1L;
    }
  }
}
