package com.holtherndon.bazelviz.enrich.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.DepSetOfFiles;
import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Importing an action graph Bazel actually produced.
 *
 * <p>The fixtures are {@code aquery --output=proto} output from real Bazel 6.5.0, 7.6.1, 8.4.1 and
 * 9.2.0. The session around them reproduces what Phase 3 would have written for the same build: the
 * four genrule actions and the two configurations the build event stream published.
 */
final class ActionGraphImporterTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;

  private static final String OUT = "bazel-out/darwin_arm64-fastbuild/bin/pkg/";

  /** The configuration checksums the probe build actually published. */
  private static final Map<String, List<String>> CONFIGURATIONS =
      Map.of(
          "bazel650",
              List.of(
                  "9cd96869affcbadf499d664d349aab0a56d17a75de5bc5fa99e4d5d7a601840c",
                  "3b270167ad09e1b14e1cecd3ecac79b255a5a5eb6162dc1c3e64c83ef54484da"),
          "bazel920",
              List.of(
                  "1a589d14ca3886895c1228db75ec6c30d0c253d2c9f4c3070e5f3535de94c607",
                  "2d8934052f1445fdec9fefac5a616f1fb9d9dea67b8c1b3f6e1572370634272c"));

  @BeforeEach
  void buildSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
    for (String name : List.of("a", "b", "slow", "big")) {
      exec(
          "INSERT INTO actions (primary_output, outcome) VALUES ('"
              + OUT
              + name
              + ".txt', 'SUCCEEDED')");
    }
  }

  @AfterEach
  void closeSession() throws Exception {
    database.close();
  }

  @ParameterizedTest(name = "Bazel {0}")
  @ValueSource(strings = {"bazel650", "bazel761", "bazel841", "bazel920"})
  @DisplayName("every version's graph imports with every artifact path resolved")
  void everyVersionImports(String fixture) throws Exception {
    ActionGraphImporter.Result result = importFixture(fixture);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.declaredActions()).isGreaterThanOrEqualTo(14);
    assertThat(result.depsets()).isPositive();
    // Paths are built from a fragment tree whose parents are sometimes
    // declared after their children (Q4). Any unresolved artifact means
    // the two-pass resolution did not happen.
    assertThat(result.unresolvedArtifacts()).isZero();
    assertThat(result.unresolvedDepsetReferences()).isZero();
    assertThat(
            scalar(
                "SELECT unresolved_artifacts FROM graph_sources"
                    + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isZero();
    assertThat(
            scalar(
                "SELECT unresolved_depset_references FROM graph_sources"
                    + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isZero();
    assertThat(text("SELECT target_scope FROM graph_sources" + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isEqualTo("EXACT_BEP_TARGETS");
    assertThat(scalar("SELECT count(*) FROM declared_actions" + " WHERE primary_output_id IS NULL"))
        .isZero();
  }

  @Test
  @DisplayName("an unresolved artifact is retained as exact graph completeness metadata")
  void unresolvedArtifactCountIsPersisted() throws Exception {
    Path graph = tempDir.resolve("unresolved.pb");
    ActionGraphContainer container =
        ActionGraphContainer.newBuilder()
            .addArtifacts(Artifact.newBuilder().setId(1).setPathFragmentId(404))
            .build();
    Files.write(graph, container.toByteArray());

    ActionGraphImporter.Result result =
        new ActionGraphImporter(connection).importFrom(graph, List.of("bazel", "aquery", "//..."));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.unresolvedArtifacts()).isEqualTo(1);
    assertThat(
            scalar(
                "SELECT unresolved_artifacts FROM graph_sources"
                    + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("dangling artifact and depset references cannot disappear during import")
  void danglingReferencesArePersisted() throws Exception {
    Path graph = tempDir.resolve("dangling.pb");
    ActionGraphContainer container =
        ActionGraphContainer.newBuilder()
            .addDepSetOfFiles(
                DepSetOfFiles.newBuilder()
                    .setId(1)
                    .addTransitiveDepSetIds(91)
                    .addDirectArtifactIds(81))
            .addActions(
                Action.newBuilder().addInputDepSetIds(92).addOutputIds(82).setPrimaryOutputId(83))
            .build();
    Files.write(graph, container.toByteArray());

    ActionGraphImporter.Result result =
        new ActionGraphImporter(connection).importFrom(graph, List.of("bazel", "aquery", "//..."));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.unresolvedArtifacts()).isEqualTo(3);
    assertThat(result.unresolvedDepsetReferences()).isEqualTo(2);
    assertThat(
            scalar(
                "SELECT unresolved_artifacts FROM graph_sources"
                    + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isEqualTo(3);
    assertThat(
            scalar(
                "SELECT unresolved_depset_references FROM graph_sources"
                    + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a path assembled from the fragment tree is the path Bazel meant")
  void pathsAreReconstructedCorrectly() throws Exception {
    importFixture("bazel920");

    // 36 to 40 fragments describe 19 to 25 artifacts, because directory
    // prefixes are shared. If the chain were walked wrongly the paths would
    // be plausible and wrong, so this asserts an exact one.
    assertThat(paths()).contains(OUT + "a.txt", OUT + "big.txt");
  }

  @Test
  @DisplayName("declared actions link to the executed ones by primary output")
  void declaredActionsCorrelate() throws Exception {
    ActionGraphImporter.Result result = importFixture("bazel920");

    // The session holds the four genrules; the graph holds sixteen actions.
    assertThat(result.correlatedActions()).isEqualTo(4);
    assertThat(
            scalar(
                "SELECT count(*) FROM declared_actions da"
                    + " JOIN actions a ON a.id = da.action_id"))
        .isEqualTo(4);
  }

  @Test
  @DisplayName("actions that were declared and never ran are kept, unlinked")
  void declaredButNotExecutedSurvive() throws Exception {
    ActionGraphImporter.Result result = importFixture("bazel920");

    // aquery declares the TestRunner action of every test; a `build`
    // invocation runs none of them (Q7). Dropping them would make the graph
    // disagree with the analysis it came from.
    long unlinked = result.declaredActions() - result.correlatedActions();
    assertThat(unlinked).isPositive();
    assertThat(
            text(
                "SELECT m.value FROM declared_actions da"
                    + " JOIN mnemonics m ON m.id = da.mnemonic_id"
                    + " WHERE da.action_id IS NULL AND m.value = 'TestRunner' LIMIT 1"))
        .isEqualTo("TestRunner");
  }

  @Test
  @DisplayName("the depset DAG is stored as a DAG, not flattened")
  void depsetsStayADag() throws Exception {
    importFixture("bazel920");

    assertThat(scalar("SELECT count(*) FROM graph_depsets")).isPositive();
    assertThat(scalar("SELECT count(*) FROM graph_depset_artifacts")).isPositive();
    assertThat(scalar("SELECT count(*) FROM declared_action_inputs")).isPositive();
    try (Statement s = connection.createStatement();
        ResultSet rows = s.executeQuery("PRAGMA foreign_key_check")) {
      assertThat(rows.next()).as("a foreign key violation exists").isFalse();
    }
  }

  @Test
  @DisplayName("a graph whose configurations are the build's is called exact")
  void matchingConfigurationsAreExact() throws Exception {
    declareConfigurations(CONFIGURATIONS.get("bazel920"));

    ActionGraphImporter.Result result = importFixture("bazel920");

    assertThat(result.configurationMatch()).isEqualTo(ConfigurationMatch.EXACT);
    assertThat(result.matchesTheBuild()).isTrue();
    assertThat(text("SELECT configuration_match FROM graph_sources")).isEqualTo("EXACT");
  }

  @Test
  @DisplayName("a graph from another build is imported, and marked, not hidden")
  void mismatchedConfigurationsAreMarked() throws Exception {
    declareConfigurations(List.of("a-configuration-this-build-never-used"));

    ActionGraphImporter.Result result = importFixture("bazel920");

    // Plan 12.4: do not silently attach uncertain graph data -- and do not
    // silently discard it either. The actions are real.
    assertThat(result.succeeded()).isTrue();
    assertThat(result.declaredActions()).isPositive();
    assertThat(result.configurationMatch()).isEqualTo(ConfigurationMatch.MISMATCHED);
    assertThat(result.matchesTheBuild()).isFalse();
    assertThat(text("SELECT mismatch_detail FROM graph_sources"))
        .contains("actions and edges in it are real");
  }

  @Test
  @DisplayName("no configurations to compare against reads as unknown, never exact")
  void noConfigurationsIsUnknown() throws Exception {
    ActionGraphImporter.Result result = importFixture("bazel920");

    assertThat(result.configurationMatch()).isEqualTo(ConfigurationMatch.UNKNOWN);
    assertThat(result.matchesTheBuild()).isFalse();
  }

  @Test
  @DisplayName("an empty query output is a failure, not an empty graph")
  void emptyOutputIsAFailure() throws Exception {
    Path empty = Files.createFile(tempDir.resolve("empty.proto"));

    ActionGraphImporter.Result result =
        new ActionGraphImporter(connection).importFrom(empty, List.of("aquery"));

    // A query naming a target that does not exist exits non-zero and writes
    // zero bytes on all four versions (Q8).
    assertThat(result.succeeded()).isFalse();
    assertThat(result.error())
        .hasValueSatisfying(
            message ->
                assertThat(message).contains("failed query rather than a build with no actions"));
  }

  @Test
  @DisplayName("a failed import leaves the executed actions exactly as they were")
  void failureLeavesTheSessionUsable() throws Exception {
    long before = scalar("SELECT count(*) FROM actions");
    Path garbage = tempDir.resolve("garbage.proto");
    Files.write(garbage, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});

    ActionGraphImporter.Result result =
        new ActionGraphImporter(connection).importFrom(garbage, List.of("aquery"));

    assertThat(result.succeeded()).isFalse();
    assertThat(scalar("SELECT count(*) FROM actions")).isEqualTo(before);
    assertThat(scalar("SELECT count(*) FROM declared_actions")).isZero();
    assertThat(text("SELECT state FROM graph_sources")).isEqualTo("FAILED");
    assertThat(text("SELECT error_excerpt FROM graph_sources")).isNotBlank();
    assertThat(text("SELECT unresolved_artifacts FROM graph_sources")).isNull();
  }

  @Test
  @DisplayName("a failed replacement cannot expose indexes from the preceding action graph")
  void failedReplacementInvalidatesActionIndexes() throws Exception {
    importFixture("bazel920");
    new ActionEdgeDeriver(connection).deriveAll();
    Path indexDirectory = tempDir.resolve("indexes");
    GraphIndexBuilder indexes = new GraphIndexBuilder(connection, indexDirectory);
    GraphIndexBuilder.Result declared = indexes.build(EdgeDerivation.DECLARED).orElseThrow();
    assertThat(indexes.build(EdgeDerivation.OBSERVED)).isPresent();
    assertThat(indexes.descriptor(EdgeDerivation.DECLARED, "FORWARD")).isPresent();
    assertThat(indexes.descriptor(EdgeDerivation.OBSERVED, "FORWARD")).isPresent();

    Path garbage = tempDir.resolve("replacement-garbage.proto");
    Files.write(garbage, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff});

    ActionGraphImporter.Result result =
        new ActionGraphImporter(connection).importFrom(garbage, List.of("aquery"));

    assertThat(result.succeeded()).isFalse();
    assertThat(
            scalar(
                "SELECT count(*) FROM graph_indexes" + " WHERE kind IN ('DECLARED', 'OBSERVED')"))
        .isZero();
    assertThat(indexes.descriptor(EdgeDerivation.DECLARED, "FORWARD")).isEmpty();
    assertThat(indexes.descriptor(EdgeDerivation.OBSERVED, "FORWARD")).isEmpty();
    // Files are harmless without a registry row and can be atomically
    // replaced by the next successful index build.
    assertThat(declared.forwardFile()).exists();
  }

  @Test
  @DisplayName("a failed aquery process invalidates old indexes and marks the source failed")
  void processFailureInvalidatesActionIndexes() throws Exception {
    importFixture("bazel920");
    new ActionEdgeDeriver(connection).deriveAll();
    GraphIndexBuilder indexes =
        new GraphIndexBuilder(connection, tempDir.resolve("process-failure-indexes"));
    assertThat(indexes.build(EdgeDerivation.DECLARED)).isPresent();
    assertThat(indexes.build(EdgeDerivation.OBSERVED)).isPresent();
    Path empty = Files.createFile(tempDir.resolve("failed-aquery.proto"));

    ActionGraphImporter.Result result =
        new ActionGraphImporter(connection)
            .recordFailure(empty, List.of("bazel", "aquery"), "analysis failed on //bad");

    assertThat(result.succeeded()).isFalse();
    assertThat(
            scalar(
                "SELECT count(*) FROM graph_indexes" + " WHERE kind IN ('DECLARED', 'OBSERVED')"))
        .isZero();
    assertThat(text("SELECT state FROM graph_sources WHERE kind = 'DECLARED_ACTIONS'"))
        .isEqualTo("FAILED");
    assertThat(text("SELECT error_excerpt FROM graph_sources" + " WHERE kind = 'DECLARED_ACTIONS'"))
        .contains("analysis failed on //bad");
    assertThat(
            text("SELECT raw_output_path FROM graph_sources" + " WHERE kind = 'DECLARED_ACTIONS'"))
        .isEqualTo(empty.toString());
  }

  @Test
  @DisplayName("staging tables do not survive the import")
  void stagingIsTemporary() throws Exception {
    importFixture("bazel920");

    try (Statement s = connection.createStatement();
        ResultSet rows =
            s.executeQuery("SELECT count(*) FROM sqlite_temp_master WHERE name LIKE 'stage_%'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isZero();
    }
  }

  @Test
  @DisplayName("is_executable is true or unknown, and never stored false")
  void isExecutableIsNeverFalse() throws Exception {
    importFixture("bazel920");

    // proto3 erased the difference between "not executable" and "this
    // version never says" before the parser saw it (Q9), so a 0 would be a
    // distinction the data does not carry.
    assertThat(scalar("SELECT count(*) FROM declared_actions WHERE is_executable = 0")).isZero();
    assertThat(scalar("SELECT count(*) FROM declared_actions WHERE is_executable = 1"))
        .isPositive();
  }

  @Test
  @DisplayName("6.5.0 marks nothing executable, which is the version and not the build")
  void sixFiveMarksNothingExecutable() throws Exception {
    importFixture("bazel650");

    assertThat(scalar("SELECT count(*) FROM declared_actions WHERE is_executable IS NOT NULL"))
        .isZero();
  }

  // ---------------------------------------------------------------- helpers

  private ActionGraphImporter.Result importFixture(String name) throws Exception {
    return new ActionGraphImporter(connection)
        .importFrom(
            fixture(name + "-aquery.proto"),
            List.of("aquery", "//pkg:all"),
            GraphTargetScope.EXACT_BEP_TARGETS,
            "fixture uses the build's exact top-level labels");
  }

  /**
   * Declares configurations the way a real build does: with a configured target actually built in
   * each.
   *
   * <p>The check compares against the configurations targets were built in, not every configuration
   * the event stream mentioned — Bazel publishes a `none` placeholder that no query can report, and
   * comparing against it made EXACT unreachable.
   */
  private void declareConfigurations(List<String> checksums) throws SQLException {
    exec("INSERT INTO labels (value) VALUES ('//pkg:configured') ON CONFLICT DO NOTHING");
    exec(
        "INSERT INTO targets (label_id, aspect, outcome) VALUES"
            + " ((SELECT id FROM labels WHERE value = '//pkg:configured'), '', 'COMPLETED')"
            + " ON CONFLICT DO NOTHING");
    int ordinal = 0;
    for (String checksum : checksums) {
      exec(
          "INSERT INTO configurations (stream_id, bep_id, declared)"
              + " VALUES (1, '"
              + checksum
              + "', 1)");
      exec(
          "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
              + " VALUES ((SELECT id FROM targets LIMIT 1),"
              + " (SELECT id FROM configurations WHERE bep_id = '"
              + checksum
              + "'),"
              + " 'BUILT')");
      ordinal++;
    }
    // A `none` placeholder alongside them, which nothing is built in --
    // exactly what a real session holds, and what the first version of the
    // check tripped over.
    if (ordinal > 0) {
      exec("INSERT INTO configurations (stream_id, bep_id, declared)" + " VALUES (1, 'none', 1)");
    }
  }

  private List<String> paths() throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement s = connection.createStatement();
        ResultSet rows = s.executeQuery("SELECT path FROM artifacts")) {
      while (rows.next()) {
        out.add(rows.getString(1));
      }
    }
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

  private String text(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }
}
