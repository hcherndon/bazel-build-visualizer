package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilityDetector;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 end to end: a real build, its graph queried and imported without being asked twice.
 *
 * <p>This is the test that proves the phase is not a library nobody calls. The coordinator plans
 * the queries from the build's own command line, runs them after the build, imports both graphs,
 * and the configuration check passes because the query really did analyse what the build ran.
 *
 * <h2>One Bazel, deliberately</h2>
 *
 * <p>Earlier phases prove their version compatibility by sweeping 6.5.0, 7.6.1, 8.4.1 and 9.2.0
 * through a real capture. This one does not, and the reason is measured rather than stylistic: each
 * version needs its own output base and therefore its own Bazel server, and four servers alongside
 * the test JVMs took a development machine past 120 GB of resident memory.
 *
 * <p>What that sweep would have proved is proved without it. The version- specific behaviour of the
 * graph — forward references in the file, the path fragment tree, the configuration checksums — is
 * pinned by {@code ActionGraphImporterTest} against checked-in {@code aquery} output from all four
 * versions, which costs no Bazel server at all. This test exists for the one thing a fixture cannot
 * show: that the coordinator really does plan, run and import the queries without being asked.
 *
 * <p>Skipped, not failed, when no Bazel is on the machine.
 */
@Tag("real-bazel")
class RealBazelGraphTest {

  @Test
  @DisplayName("a captured build gets its action graph, correlated and configuration-checked")
  void graphsAreCapturedAutomatically(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 4);
    CaptureResult result = capture(directory, bazel.orElseThrow(), workspace, Optional.empty());
    assertThat(result.buildSucceeded()).describedAs("warnings: %s", result.warnings()).isTrue();

    // The queries actually ran and wrote something.
    Path raw = ManagedSessionLayout.at(result.sessionRoot()).rawDirectory();
    assertThat(Files.size(raw.resolve("aquery.proto"))).isPositive();
    assertThat(Files.size(raw.resolve("cquery.proto"))).isPositive();
    SessionManifest manifest =
        new SessionManager(directory.resolve("sessions"), "test")
            .readManifest(result.sessionRoot());
    assertThat(manifest.auxiliaryCommands())
        .hasValueSatisfying(
            commands -> {
              assertThat(commands)
                  .anySatisfy(
                      command -> {
                        assertThat(command.label()).isEqualTo("aquery");
                        assertThat(command.argv())
                            .contains(
                                "--query_file="
                                    + raw.resolve(AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE));
                      });
              assertThat(commands)
                  .anySatisfy(
                      command -> {
                        assertThat(command.label()).isEqualTo("cquery");
                        assertThat(command.argv())
                            .contains(
                                "--query_file="
                                    + raw.resolve(AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE));
                      });
            });
    assertThat(Files.readString(raw.resolve(AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE)))
        .isEqualTo("deps(set(\"//:t3\"))\n");
    assertThat(Files.readString(raw.resolve(AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE)))
        .isEqualTo("deps(set(\"//:t3\"))\n");

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      assertThat(scalar(c, "SELECT count(*) FROM declared_actions")).isPositive();
      assertThat(scalar(c, "SELECT count(*) FROM configured_target_nodes"))
          .describedAs("cquery includes the configured dependency closure")
          .isGreaterThan(1);
      assertThat(
              scalar(
                  c,
                  "SELECT count(*) FROM configured_target_nodes n"
                      + " JOIN labels l ON l.id = n.label_id WHERE l.value = '//:t0'"))
          .describedAs("the leaf dependency appears in All Targets")
          .isPositive();

      // The whole point of the configuration check: this query analysed
      // the build that just ran, so it is the one state that permits the
      // graph to be called this build's (Q6).
      assertThat(
              text(
                  c,
                  "SELECT configuration_match FROM graph_sources"
                      + " WHERE kind = 'DECLARED_ACTIONS'"))
          .isEqualTo("EXACT");
      assertThat(
              scalar(
                  c,
                  "SELECT count(*) FROM graph_sources"
                      + " WHERE target_scope = 'EXACT_BEP_TARGETS'"))
          .isEqualTo(2);

      // Declared actions link to executed ones, and some do not, because
      // neither population contains the other (Q7).
      long declared = scalar(c, "SELECT count(*) FROM declared_actions");
      long correlated =
          scalar(c, "SELECT count(*) FROM declared_actions WHERE action_id IS NOT NULL");
      assertThat(correlated).isPositive().isLessThanOrEqualTo(declared);

      // And nothing was dropped to keep the schema tidy.
      try (Statement s = c.createStatement();
          ResultSet rows = s.executeQuery("PRAGMA foreign_key_check")) {
        assertThat(rows.next()).describedAs("a foreign key violation exists").isFalse();
      }

      // The capture derived edges and built the CSR indexes without
      // being asked. Before this was wired, every real session had
      // declared_actions rows and no index, so the graph view opened to
      // "no action graph" forever — the whole Phase 5/7 stack was only
      // reachable from tests.
      assertThat(scalar(c, "SELECT count(*) FROM action_edges" + " WHERE derivation = 'DECLARED'"))
          .isPositive();
      assertThat(scalar(c, "SELECT count(*) FROM graph_indexes" + " WHERE kind = 'DECLARED'"))
          .describedAs("capture warnings: %s", result.warnings())
          .isEqualTo(2);
      // The configured-target label graph got its index too — the graph
      // closest to `bazel query deps(//foo)`, and a dead end until now.
      assertThat(
              scalar(
                  c, "SELECT count(*) FROM graph_indexes" + " WHERE kind = 'CONFIGURED_TARGETS'"))
          .describedAs("capture warnings: %s", result.warnings())
          .isEqualTo(2);
    }

    // And the indexes a reopened session loads really answer: the exact
    // path the graph view takes, from label search to bounded traversal.
    try (SessionDatabase database = open(result);
        GraphQueries queries =
            new GraphQueries(
                database.newGraphReadConnection(),
                ManagedSessionLayout.at(result.sessionRoot()).indexesDirectory())) {
      var forwardNodes =
          queries.withIndex(EdgeDerivation.DECLARED, true, graph -> graph.nodeCount());
      assertThat(forwardNodes).describedAs("the forward CSR index is loadable").isPresent();
      assertThat(forwardNodes.orElseThrow()).isPositive();
      assertThat(queries.withIndex(EdgeDerivation.DECLARED, false, graph -> graph.nodeCount()))
          .isPresent();

      var found = queries.search("%:t1%", 1);
      assertThat(found).describedAs("a target label seeds a graph node").isNotEmpty();
      // t1 consumes t0's output: the reverse index answers "what
      // does this need" with at least t0.
      var producers =
          queries.neighbours(EdgeDerivation.DECLARED, found.getFirst().nodeIndex(), false, 10);
      assertThat(producers).describedAs("the reverse CSR index is loadable").isPresent();
      assertThat(producers.orElseThrow())
          .describedAs("the fixture chain has a producer for t1")
          .isNotEmpty();

      // The label graph answers the same questions over labels: a label
      // seeds a node, and t1's rule inputs include t0.
      var labelKind = GraphKind.CONFIGURED_TARGETS;
      assertThat(queries.withIndex(labelKind, true, graph -> graph.nodeCount()))
          .describedAs("the configured-target label index is loadable")
          .isPresent();
      var t1 = queries.search(labelKind, "%:t1", 1);
      assertThat(t1).describedAs("a label seeds a label-graph node").isNotEmpty();
      var labelProducers = queries.neighbours(labelKind, t1.getFirst().nodeIndex(), false, 10);
      assertThat(labelProducers).describedAs("the reverse label index is loadable").isPresent();
      assertThat(labelProducers.orElseThrow())
          .describedAs("t1 names t0 as a rule input")
          .anySatisfy(node -> assertThat(node.label().orElse("")).endsWith(":t0"));
    }
  }

  @Test
  @DisplayName("both graph queries reuse the wildcard targets Bazel actually selected")
  void wildcardScopeUsesRecordedTargets(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
    BazelWorkspaceFixture workspace =
        BazelWorkspaceFixture.withBrokenManualTarget(directory.resolve("ws"));

    CaptureResult result =
        capture(
            directory, bazel.orElseThrow(), workspace, Optional.empty(), List.of("build", "//..."));

    assertThat(result.buildSucceeded()).isTrue();
    Path raw = ManagedSessionLayout.at(result.sessionRoot()).rawDirectory();
    assertThat(Files.readString(raw.resolve(AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE)))
        .isEqualTo("deps(set(\"//:good\"))\n");
    assertThat(Files.readString(raw.resolve(AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE)))
        .isEqualTo("deps(set(\"//:good\"))\n");
    assertThat(Files.size(raw.resolve("aquery.proto"))).isPositive();
    assertThat(Files.size(raw.resolve("cquery.proto"))).isPositive();
    try (SessionDatabase database = open(result)) {
      Connection connection = database.newReadConnection();
      assertThat(
              text(
                  connection,
                  "SELECT state FROM graph_sources" + " WHERE kind = 'DECLARED_ACTIONS'"))
          .isEqualTo("SUCCEEDED");
      assertThat(
              text(
                  connection,
                  "SELECT state FROM graph_sources" + " WHERE kind = 'CONFIGURED_TARGETS'"))
          .isEqualTo("SUCCEEDED");
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM graph_sources"
                      + " WHERE target_scope = 'EXACT_BEP_TARGETS'"))
          .isEqualTo(2);
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM declared_actions d"
                      + " JOIN labels l ON l.id = d.label_id"
                      + " WHERE l.value = '//:manual_broken'"))
          .isZero();
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM configured_target_nodes n"
                      + " JOIN labels l ON l.id = n.label_id WHERE l.value = '//:good'"))
          .isPositive();
      assertThat(
              scalar(
                  connection,
                  "SELECT count(*) FROM configured_target_nodes n"
                      + " JOIN labels l ON l.id = n.label_id"
                      + " WHERE l.value = '//:manual_broken'"))
          .isZero();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static CaptureResult capture(
      Path directory, Path bazel, BazelWorkspaceFixture workspace, Optional<String> version)
      throws Exception {
    return capture(directory, bazel, workspace, version, List.of("build", "//:t3"));
  }

  private static CaptureResult capture(
      Path directory,
      Path bazel,
      BazelWorkspaceFixture workspace,
      Optional<String> version,
      List<String> command)
      throws Exception {
    Path sessionsRoot = directory.resolve("sessions");
    CaptureRequest request =
        CaptureRequest.of(sessionsRoot, "test", bazel.toString(), workspace.root(), command)
            .withPreset(CapturePreset.FULL_GRAPH_DIAGNOSTICS)
            .withEnvironment(BazelBinary.VERSION_ENV, version);
    try (CaptureCoordinator coordinator =
        new CaptureCoordinator(
            request,
            new SessionManager(sessionsRoot, request.appVersion()),
            new BazelCapabilityDetector(),
            Clock.systemUTC())) {
      coordinator.preflight();
      return coordinator.run();
    }
  }

  private static SessionDatabase open(CaptureResult result) throws SQLException {
    return SessionDatabase.open(ManagedSessionLayout.at(result.sessionRoot()).databaseFile());
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1L;
    }
  }

  private static String text(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }
}
