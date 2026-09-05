package com.holtherndon.bazelviz.storage.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The index builder's contract with itself: what it writes, it loads; what it cannot vouch for, it
 * refuses.
 *
 * <p>The build path is also exercised end to end by the enrichment and ui-swing suites; these are
 * the storage module's own tests for the parts those cannot isolate — the refusal of a stale file,
 * the missing-file answer, and the empty-graph answer, each of which is a different kind of "no".
 */
final class GraphIndexBuilderTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;
  private Path indexDirectory;

  @BeforeEach
  void buildFixture() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    indexDirectory = tempDir.resolve("indexes");
    exec(
        "INSERT INTO graph_sources"
            + " (id, kind, state, configuration_match, unresolved_artifacts,"
            + " unresolved_depset_references)"
            + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT', 0, 0)");
    // A diamond: 1 feeds 2 and 3, both feed 4.
    for (int i = 1; i <= 4; i++) {
      exec("INSERT INTO labels (id, value) VALUES (" + i + ", '//d:t" + i + "')");
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
        "INSERT INTO action_edges (producer_id, consumer_id, derivation) VALUES"
            + " (1, 2, 'DECLARED'), (1, 3, 'DECLARED'),"
            + " (2, 4, 'DECLARED'), (3, 4, 'DECLARED')");
  }

  @AfterEach
  void closeDatabase() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("what was built loads back, in both directions, and they agree")
  void buildAndLoadRoundTrip() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

    Optional<GraphIndexBuilder.Result> built = builder.build(EdgeDerivation.DECLARED);

    assertThat(built).isPresent();
    assertThat(built.orElseThrow().nodeCount()).isEqualTo(4);
    assertThat(built.orElseThrow().edgeCount()).isEqualTo(4);

    try (CsrGraph forward =
            CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD").orElseThrow());
        CsrGraph reverse =
            CsrFile.open(builder.descriptor(EdgeDerivation.DECLARED, "REVERSE").orElseThrow())) {
      assertThat(forward.edgeCount()).isEqualTo(reverse.edgeCount());
      // The diamond, exactly: node 0 feeds 1 and 2; node 3 is fed by both.
      assertThat(forward.degree(0)).isEqualTo(2);
      assertThat(forward.degree(3)).isZero();
      assertThat(reverse.degree(3)).isEqualTo(2);
      assertThat(reverse.degree(0)).isZero();
    }
    assertThat(
            scalar(
                "SELECT count(*) FROM graph_indexes"
                    + " WHERE kind = 'DECLARED' AND source_id = 1"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("observed action indexes point to the declared-action query source")
  void observedIndexesCarryActionSource() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

    assertThat(builder.build(EdgeDerivation.OBSERVED)).isPresent();

    assertThat(
            scalar(
                "SELECT count(*) FROM graph_indexes"
                    + " WHERE kind = 'OBSERVED' AND source_id = 1"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("an index tied to a failed graph source is unavailable")
  void failedSourceCannotServeItsIndex() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    assertThat(builder.build(EdgeDerivation.DECLARED)).isPresent();

    exec("UPDATE graph_sources SET state = 'FAILED' WHERE id = 1");

    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "REVERSE")).isEmpty();
  }

  @Test
  @DisplayName("a session with no graph builds nothing and says so with an empty")
  void emptyGraphIsNotAFailure() throws Exception {
    exec("DELETE FROM action_edges");
    exec("DELETE FROM declared_actions");
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

    assertThat(builder.build(EdgeDerivation.DECLARED)).isEmpty();
    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
  }

  @Test
  @DisplayName("an index that was never registered answers empty, not wrongly")
  void unregisteredIndexIsEmpty() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

    assertThat(builder.descriptor(EdgeDerivation.OBSERVED, "FORWARD")).isEmpty();
    assertThat(builder.configuredTargetsDescriptor("FORWARD")).isEmpty();
  }

  @Test
  @DisplayName("a registered index whose file is gone answers empty rather than throwing")
  void missingFileIsEmpty() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    GraphIndexBuilder.Result built = builder.build(EdgeDerivation.DECLARED).orElseThrow();

    Files.delete(built.forwardFile());

    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
  }

  @Test
  @DisplayName("a file that no longer matches its registration is refused, not used")
  void staleIndexIsRefused() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    builder.build(EdgeDerivation.DECLARED);

    // The registry now describes a different graph than the file holds —
    // what a re-import without a rebuild would leave behind.
    exec("UPDATE graph_indexes SET edge_count = edge_count + 1 WHERE kind = 'DECLARED'");

    // A stale index is worse than none: it answers, and its answers look
    // like the others.
    assertThatThrownBy(() -> builder.descriptor(EdgeDerivation.DECLARED, "FORWARD"))
        .isInstanceOf(GraphIndexBuilder.StaleIndexException.class)
        .hasMessageContaining("stale");
  }

  @Test
  @DisplayName("a file whose direction flag disagrees with its registry row is refused")
  void directionMismatchIsRefused() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    GraphIndexBuilder.Result built = builder.build(EdgeDerivation.DECLARED).orElseThrow();
    byte[] bytes = Files.readAllBytes(built.forwardFile());
    ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(12, CsrFile.REVERSE_DIRECTION_FLAG);
    Files.write(built.forwardFile(), bytes);

    assertThatThrownBy(() -> builder.descriptor(EdgeDerivation.DECLARED, "FORWARD"))
        .isInstanceOf(GraphIndexBuilder.StaleIndexException.class)
        .hasMessageContaining("stale");
  }

  @Test
  @DisplayName("negative, gapped, and sparse near-int-max node indexes fail before publication")
  void nonDenseNodeIndexesAreRefusedBeforePublication() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

    for (long[] indexes :
        List.of(new long[] {-1}, new long[] {0, 2}, new long[] {Integer.MAX_VALUE - 1L})) {
      replaceNodeIndices(indexes);

      assertThatThrownBy(() -> builder.build(EdgeDerivation.DECLARED))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("unique and dense");
      assertThat(scalar("SELECT count(*) FROM graph_indexes")).isZero();
      assertThat(csrFiles()).isEmpty();
    }
  }

  @Test
  @DisplayName("one generation names and registers both directions")
  void pairCarriesOneGeneration() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);

    GraphIndexBuilder.Result result = builder.build(EdgeDerivation.DECLARED).orElseThrow();

    String forward = result.forwardFile().getFileName().toString();
    String reverse = result.reverseFile().getFileName().toString();
    assertThat(forward).startsWith("declared-forward-").endsWith(".csr");
    assertThat(reverse).startsWith("declared-reverse-").endsWith(".csr");
    assertThat(forward.substring("declared-forward-".length()))
        .isEqualTo(reverse.substring("declared-reverse-".length()));
    assertThat(builder.descriptorPair(EdgeDerivation.DECLARED.name())).isPresent();
  }

  @Test
  @DisplayName("a mixed generation is refused even when counts and checksums agree")
  void mixedGenerationIsRefused() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    GraphIndexBuilder.Result result = builder.build(EdgeDerivation.DECLARED).orElseThrow();
    Path mismatched = indexDirectory.resolve("declared-reverse-different-generation.csr");
    Files.copy(result.reverseFile(), mismatched);
    exec(
        "UPDATE graph_indexes SET file_name = '"
            + mismatched.getFileName()
            + "' WHERE kind = 'DECLARED' AND direction = 'REVERSE'");

    assertThatThrownBy(() -> builder.descriptor(EdgeDerivation.DECLARED, "FORWARD"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("different generations");
  }

  @Test
  @DisplayName("a failed reverse-row publication leaves the old pair loadable")
  void failedPairPublicationPreservesOldPair() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    GraphIndexBuilder.Result old = builder.build(EdgeDerivation.DECLARED).orElseThrow();
    exec(
        "CREATE TRIGGER fail_reverse_graph_registration"
            + " BEFORE UPDATE ON graph_indexes"
            + " WHEN NEW.kind = 'DECLARED' AND NEW.direction = 'REVERSE'"
            + " BEGIN SELECT RAISE(ABORT, 'injected reverse registration failure'); END");

    assertThatThrownBy(() -> builder.build(EdgeDerivation.DECLARED))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("injected reverse registration failure");

    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD").orElseThrow().path())
        .isEqualTo(old.forwardFile().toAbsolutePath().normalize());
    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "REVERSE").orElseThrow().path())
        .isEqualTo(old.reverseFile().toAbsolutePath().normalize());
    assertThat(csrFiles()).containsExactlyInAnyOrder(old.forwardFile(), old.reverseFile());
  }

  @Test
  @DisplayName("the graph index directory itself may not be a symlink")
  void symlinkedIndexDirectoryIsRefused() throws Exception {
    Path external = tempDir.resolve("external-indexes");
    Files.createDirectory(external);
    Files.createSymbolicLink(indexDirectory, external);

    assertThatThrownBy(
            () -> new GraphIndexBuilder(connection, indexDirectory).build(EdgeDerivation.DECLARED))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("real directory")
        .hasMessageContaining("symlink");
    try (var files = Files.list(external)) {
      assertThat(files.toList()).isEmpty();
    }
    assertThat(scalar("SELECT count(*) FROM graph_indexes")).isZero();
  }

  @Test
  @DisplayName("rebuilding re-registers in place; the registry never grows a second row")
  void rebuildReplacesTheRegistration() throws Exception {
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, indexDirectory);
    GraphIndexBuilder.Result old = builder.build(EdgeDerivation.DECLARED).orElseThrow();
    GraphIndexBuilder.Result replacement = builder.build(EdgeDerivation.DECLARED).orElseThrow();

    assertThat(scalar("SELECT count(*) FROM graph_indexes WHERE kind = 'DECLARED'")).isEqualTo(2);
    assertThat(builder.descriptor(EdgeDerivation.DECLARED, "FORWARD")).isPresent();
    assertThat(old.forwardFile()).doesNotExist();
    assertThat(old.reverseFile()).doesNotExist();
    assertThat(csrFiles())
        .containsExactlyInAnyOrder(replacement.forwardFile(), replacement.reverseFile());
  }

  // ------------------------------------------------------------- plumbing

  private void exec(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long scalar(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  private void replaceNodeIndices(long... indexes) throws SQLException {
    exec("DELETE FROM action_edges");
    exec("DELETE FROM declared_actions");
    for (int i = 0; i < indexes.length; i++) {
      exec(
          "INSERT INTO declared_actions (id, source_id, graph_id, label_id, node_index) VALUES ("
              + (100 + i)
              + ", 1, "
              + (100 + i)
              + ", 1, "
              + indexes[i]
              + ")");
    }
  }

  private List<Path> csrFiles() throws Exception {
    if (!Files.isDirectory(indexDirectory)) {
      return List.of();
    }
    try (var files = Files.list(indexDirectory)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".csr"))
          .map(path -> path.toAbsolutePath().normalize())
          .sorted()
          .toList();
    }
  }
}
