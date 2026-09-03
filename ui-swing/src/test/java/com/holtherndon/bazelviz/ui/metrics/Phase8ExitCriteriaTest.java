package com.holtherndon.bazelviz.ui.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.CriticalPaths;
import com.holtherndon.bazelviz.analysis.Finding;
import com.holtherndon.bazelviz.analysis.FindingRules;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.MetricSeries;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.metrics.SessionMetrics;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 24's five Phase 8 exit criteria, one test each, against a real session database with a real
 * action graph.
 *
 * <p>The fixture is a chain of eight actions across two packages, each with an execution-log
 * attempt, plus an aquery graph and a CSR index over it — the smallest session in which both
 * critical paths exist at once, which is what the second criterion is about.
 */
final class Phase8ExitCriteriaTest {

  private static final int ACTIONS = 8;

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection writer;

  @BeforeEach
  void buildSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    writer = database.writerConnection();

    exec("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'CLOSED')");
    exec(
        "INSERT INTO build_invocation (singleton, stream_id, build_tool_version,"
            + " started_micros, finished_micros, saw_last_message)"
            + " VALUES (1, 1, '8.4.1', 1000, 4_001_000, 1)");
    // Bazel's own critical path, from the profile, stored untouched.
    exec("INSERT INTO build_metrics (singleton, critical_path_micros)" + " VALUES (1, 3_800_000)");
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'action ''Compiling a.cc''', 3800000)");
    exec(
        "INSERT INTO bep_events (id, stream_id, sequence, event_type, raw_segment,"
            + " raw_offset, raw_length, decode_status, event_micros, receive_micros)"
            + " VALUES (1, 1, 1, 3, 0, 0, 8, 'OK', 1000, 1200)");
    exec("INSERT INTO mnemonics (id, value) VALUES (1, 'Javac'), (2, 'CppCompile')");
    exec(
        "INSERT INTO enrichment_tasks (id, kind, state)"
            + " VALUES (1, 'EXECUTION_LOG', 'SUCCEEDED')");
    exec(
        "INSERT INTO graph_sources"
            + " (id, kind, state, configuration_match, target_scope, unresolved_artifacts,"
            + " unresolved_depset_references)"
            + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT',"
            + " 'EXACT_BEP_TARGETS', 0, 0)");

    for (int i = 0; i < ACTIONS; i++) {
      long id = i + 1;
      long actionStart = 10_000 + i * 100_000L;
      exec(
          "INSERT INTO labels (id, value) VALUES ("
              + id
              + ", '//pkg"
              + (i % 2)
              + ":target"
              + i
              + "')");
      exec(
          "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome,"
              + " start_micros, end_micros)"
              + " VALUES ("
              + id
              + ", 'out/"
              + i
              + ".o', "
              + id
              + ", "
              + (i % 2 + 1)
              + ", 'SUCCESS', "
              + actionStart
              + ", "
              + (actionStart + 50_000)
              + ")");
      // Sequential spawns, each 400 ms, so the chain and the clock agree.
      long start = 1_000 + i * 400_000L;
      exec(
          "INSERT INTO action_attempts (id, task_id, log_entry_index, action_id,"
              + " correlation, runner, cache_hit, start_micros, total_micros, input_bytes,"
              + " queue_micros, cacheable, remotable) VALUES ("
              + id
              + ", 1, "
              + i
              + ", "
              + id
              + ", 'MATCHED_BY_OUTPUT', 'remote', 0, "
              + start
              + ", 400000, "
              + (1024 * id)
              + ", "
              + (i == 3 ? 380_000 : 1_000)
              + ", 1, 1)");
      exec(
          "INSERT INTO declared_actions (id, source_id, graph_id, action_id, label_id,"
              + " mnemonic_id, node_index) VALUES ("
              + id
              + ", 1, "
              + i
              + ", "
              + id
              + ", "
              + id
              + ", "
              + (i % 2 + 1)
              + ", "
              + i
              + ")");
    }
    for (int i = 0; i + 1 < ACTIONS; i++) {
      exec(
          "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
              + " VALUES ("
              + (i + 1)
              + ", "
              + (i + 2)
              + ", 'DECLARED')");
    }
    new GraphIndexBuilder(writer, tempDir.resolve("indexes")).build(EdgeDerivation.DECLARED);

    exec(
        "INSERT INTO artifacts (id, path, size_bytes, is_directory, is_source)"
            + " VALUES (1, 'out/0.o', 4096, 0, 0), (2, 'out/1.o', NULL, 0, 0)");
    exec(
        "INSERT INTO attempt_outputs (id, attempt_id, artifact_id, kind, produced)"
            + " VALUES (1, 1, 1, 'FILE', 1)");
  }

  @AfterEach
  void close() throws Exception {
    database.close();
  }

  private void exec(String sql) throws Exception {
    try (Statement statement = writer.createStatement()) {
      statement.execute(sql);
    }
  }

  private SessionMetrics collect() throws Exception {
    return collect(null);
  }

  private SessionMetrics collect(CriticalPath.DurationSource requestedSource) throws Exception {
    return collect(requestedSource, MetricQueries.DEFAULT_CANDIDATE_LIMIT);
  }

  private SessionMetrics collect(CriticalPath.DurationSource requestedSource, int candidateLimit)
      throws Exception {
    try (MetricQueries queries =
        new MetricQueries(
            database.newReadConnection(),
            new GraphQueries(database.newReadConnection(), tempDir.resolve("indexes")))) {
      CriticalPath.DurationSource source =
          requestedSource == null ? queries.bestDurationSource() : requestedSource;
      return queries.collect(
          new MetricQueries.Request(
              source,
              Set.of(GroupAggregate.Dimension.values()),
              MetricQueries.DEFAULT_GROUP_LIMIT,
              candidateLimit));
    }
  }

  // --- criterion 1 -------------------------------------------------------

  @Test
  @DisplayName("criterion 1: every displayed metric reports its source and its completeness")
  void everyMetricReportsSourceAndCompleteness() throws Exception {
    SessionMetrics metrics = collect();

    for (GroupAggregate.Table table : metrics.aggregates().values()) {
      for (GroupAggregate group : table.groups()) {
        for (MetricSeries series : List.of(group.duration(), group.inputBytes())) {
          assertThat(series.source()).as("%s source", series.name()).isNotNull();
          assertThat(series.completeness()).as("%s completeness", series.name()).isNotNull();
          // The sentence a view must print beside the number names
          // both, and says how much of the group reported.
          assertThat(series.describe())
              .as("%s in %s", series.name(), group.displayKey())
              .contains(series.name())
              .containsAnyOf("reported", "nothing to measure");
        }
      }
    }

    // Coverage is stated for every source the session could have had, and
    // every incomplete one says why.
    Coverage.Report coverage = metrics.invocation().coverage();
    assertThat(coverage.entries()).hasSizeGreaterThanOrEqualTo(8);
    for (Coverage entry : coverage.incomplete()) {
      assertThat(entry.reason()).as("%s must say why", entry.name()).isNotEmpty();
    }

    // And every number inside a finding carries its own provenance.
    for (Finding finding : findings(metrics)) {
      for (Finding.MetricValue metric : finding.metrics()) {
        assertThat(metric.source()).as("%s: %s", finding.ruleId(), metric.name()).isNotBlank();
      }
    }

    // The card the user actually reads says which measurement fed it, and
    // every invocation metric on it carries what it could not count.
    FindingsView view = new FindingsView();
    view.show(new MetricsService.Result(metrics, findings(metrics), FindingThresholds.defaults()));
    assertThat(view.summaryTextForTest()).contains("Duration source").contains("Timing coverage");
    assertThat(view.catalogTextForTest())
        .contains("Wall time")
        .contains("reported none")
        .contains("not reported")
        .contains("not CPU utilization")
        .contains("a measure of this application, not of Bazel")
        .contains("timed)");
  }

  // --- criterion 2 -------------------------------------------------------

  @Test
  @DisplayName("criterion 2: the Bazel-reported and derived critical paths remain distinct")
  void criticalPathsRemainDistinct() throws Exception {
    SessionMetrics metrics = collect();
    CriticalPaths paths = metrics.invocation().criticalPaths();

    // Both exist here, and they disagree: Bazel measured 3.8 s, the
    // dependency chain implies 3.2 s. That disagreement is the point.
    assertThat(paths.bazelReportedMicros().value()).hasValue(3_800_000L);
    assertThat(paths.derived()).isPresent();
    assertThat(paths.derived().orElseThrow().outcome()).isEqualTo(CriticalPath.Outcome.COMPUTED);
    assertThat(paths.derived().orElseThrow().makespanMicros()).isEqualTo(3_200_000L);
    assertThat(paths.bothAvailable()).isTrue();
    assertThat(paths.schedulingGapMicros()).hasValue(600_000L);
    assertThat(paths.describe())
        .contains("Bazel-reported critical path")
        .contains("Visualizer-computed dependency critical path")
        .contains("they measure different things");

    // Structural, not editorial: there is no accessor that hands back "the"
    // critical path, so no caller can show one without naming which.
    for (Method method : CriticalPaths.class.getMethods()) {
      if (method.getDeclaringClass() != CriticalPaths.class) {
        continue;
      }
      String name = method.getName().toLowerCase(Locale.ROOT);
      assertThat(name)
          .as("CriticalPaths.%s", method.getName())
          .isNotEqualTo("criticalpath")
          .isNotEqualTo("path")
          .isNotEqualTo("micros")
          .isNotEqualTo("best");
    }

    // And the screen shows two rows, not one.
    FindingsView view = new FindingsView();
    view.show(new MetricsService.Result(metrics, findings(metrics), FindingThresholds.defaults()));
    String summary = view.summaryTextForTest();
    assertThat(summary).contains("Bazel-reported critical path");
    assertThat(summary).contains("Visualizer-computed dependency critical path");
    assertThat(summary.lines().filter(line -> line.equals("Critical path"))).isEmpty();
  }

  @Test
  @DisplayName("dependency contributors use the same duration source as the path")
  void dependencyContributorsKeepTheSelectedDurationSource() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.BEP_ACTION);

    assertThat(metrics.invocation().criticalPaths().derived().orElseThrow().makespanMicros())
        .isEqualTo(400_000L);
    assertThat(metrics.criticalPathActions()).hasSize(ACTIONS);
    assertThat(metrics.criticalPathActions())
        .allSatisfy(action -> assertThat(action.durationMicros()).hasValue(50_000L));
    assertThat(
            metrics.criticalPathActions().stream()
                .filter(action -> action.actionId() == 1L)
                .findFirst()
                .orElseThrow()
                .startMicros())
        .hasValue(10_000L);
  }

  @Test
  @DisplayName("execution path weights do not sum raced attempts but action totals do")
  void dependencyAttemptWeightsDoNotSumRacedWork() throws Exception {
    exec(
        "INSERT INTO action_attempts (id, task_id, log_entry_index, action_id,"
            + " correlation, runner, cache_hit, start_micros, total_micros, input_bytes,"
            + " queue_micros, setup_micros, execution_wall_micros, cacheable, remotable)"
            + " VALUES (100, 1, 100, 1, 'MATCHED_BY_OUTPUT', 'local', 0, 1200,"
            + " 800000, 2048, 700000, 5000, 95000, 1, 1)");

    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    assertThat(metrics.invocation().criticalPaths().derived().orElseThrow().makespanMicros())
        .isEqualTo(3_200_000L);
    var contributor =
        metrics.criticalPathActions().stream()
            .filter(action -> action.actionId() == 1L)
            .findFirst()
            .orElseThrow();
    assertThat(contributor.durationMicros()).hasValue(1_200_000L);
    assertThat(contributor.queueMicros()).hasValue(701_000L);
    assertThat(contributor.attempts()).isEqualTo(2);
    // The original attempt did not report every component. The aggregate
    // must not subtract only the known subset and call the remainder exact.
    assertThat(contributor.unaccountedMicros()).isEmpty();

    var catalogAction =
        metrics.candidates().stream()
            .filter(action -> action.actionId() == 1L)
            .findFirst()
            .orElseThrow();
    assertThat(catalogAction.durationMicros()).hasValue(1_200_000L);
    assertThat(catalogAction.queueMicros()).hasValue(701_000L);
  }

  @Test
  @DisplayName("dependency contributors retain only the bounded heaviest path weights")
  void dependencyContributorsUseABoundedTopN() throws Exception {
    for (int id = 1; id <= ACTIONS; id++) {
      exec("UPDATE action_attempts SET total_micros = " + (id * 100_000L) + " WHERE id = " + id);
    }

    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT, 3);

    assertThat(metrics.criticalPathActions())
        .extracting(action -> action.actionId())
        .containsExactly(8L, 7L, 6L);
  }

  @Test
  @DisplayName("an untrusted graph is never used as this invocation's critical path")
  void untrustedGraphsDoNotProduceDependencyPaths() throws Exception {
    exec(
        "UPDATE graph_sources SET configuration_match = 'MISMATCHED',"
            + " mismatch_detail = 'query used another configuration' WHERE id = 1");

    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    assertThat(metrics.invocation().criticalPaths().derived()).isEmpty();
    assertThat(metrics.criticalPathActions()).isEmpty();
    assertThat(metrics.invocation().criticalPaths().schedulingGapMicros()).isEmpty();
    assertThat(metrics.candidates())
        .allSatisfy(
            action -> {
              assertThat(action.directDependencies()).isEmpty();
              assertThat(action.directConsumers()).isEmpty();
            });
  }

  @Test
  @DisplayName("an incomplete declared graph cannot produce a dependency path")
  void incompleteGraphsDoNotProduceDependencyPaths() throws Exception {
    exec("UPDATE graph_sources SET unresolved_artifacts = 3 WHERE id = 1");

    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    assertThat(metrics.invocation().criticalPaths().derived()).isEmpty();
    assertThat(metrics.criticalPathActions()).isEmpty();
    assertThat(metrics.invocation().criticalPaths().schedulingGapMicros()).isEmpty();
    assertThat(metrics.invocation().criticalPaths().derivedUnavailableReason())
        .hasValueSatisfying(
            reason ->
                assertThat(reason)
                    .contains("3 artifact paths were unresolved")
                    .contains("dependency edges"));
    assertThat(metrics.invocation().coverage().find("Action-graph completeness"))
        .hasValueSatisfying(
            coverage -> {
              assertThat(coverage.isComplete()).isFalse();
              assertThat(coverage.reason())
                  .hasValueSatisfying(
                      reason -> assertThat(reason).contains("3 artifact paths were unresolved"));
            });
  }

  @Test
  @DisplayName("missing graph indexes leave dependency degrees unknown")
  void missingIndexesDoNotBecomeZeroDegree() throws Exception {
    exec("DELETE FROM graph_indexes");

    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    assertThat(metrics.invocation().criticalPaths().derived()).isEmpty();
    assertThat(metrics.candidates())
        .isNotEmpty()
        .allSatisfy(
            action -> {
              assertThat(action.directDependencies()).isEmpty();
              assertThat(action.directConsumers()).isEmpty();
            });
  }

  @Test
  @DisplayName("overflowing dependency duration arithmetic is reported as unavailable")
  void overflowingDerivedPathIsUnavailable() throws Exception {
    exec("UPDATE action_attempts SET total_micros = " + Long.MAX_VALUE);

    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    assertThat(metrics.invocation().criticalPaths().derived()).isEmpty();
    assertThat(metrics.invocation().criticalPaths().derivedUnavailableReason())
        .hasValueSatisfying(reason -> assertThat(reason).contains("overflowed"));
  }

  // --- criterion 3 -------------------------------------------------------

  @Test
  @DisplayName("criterion 3: every finding links to records that exist in this session")
  void findingsLinkToSupportingRecords() throws Exception {
    SessionMetrics metrics = collect();
    List<Finding> findings = findings(metrics);

    assertThat(findings).as("the fixture must produce something to check").isNotEmpty();
    for (Finding finding : findings) {
      assertThat(finding.evidence()).as("%s", finding.ruleId()).isNotEmpty();
      assertThat(finding.links()).as("%s", finding.ruleId()).isNotEmpty();
      for (Finding.Evidence evidence : finding.evidence()) {
        if (evidence.kind() != Finding.Evidence.Kind.ACTION) {
          continue;
        }
        // The id is not decorative: it has to name a row.
        assertThat(rowExists(evidence.id().orElseThrow()))
            .as("%s points at action %d", finding.ruleId(), evidence.id().orElseThrow())
            .isTrue();
      }
    }
  }

  @Test
  @DisplayName("criterion 3, continued: a link that says it draws the chain carries the chain")
  void theChainLinkAsksForTheChain() throws Exception {
    SessionMetrics metrics = collect();
    Finding chain =
        findings(metrics).stream()
            .filter(finding -> finding.ruleId().equals("long-critical-chain"))
            .findFirst()
            .orElseThrow();

    // The Phase 7 audit found three links whose words promised something
    // the code did not do. This one carries a kind rather than a
    // description, so a destination that cannot honour it fails to compile.
    assertThat(chain.links())
        .extracting(Finding.Link::kind)
        .contains(Finding.Link.Kind.DERIVED_CRITICAL_PATH);
    // And the chain it would draw is the one the finding describes: the
    // path's own node indices, from the collection that weighted it.
    assertThat(metrics.invocation().criticalPaths().derived().orElseThrow().path())
        .hasSize(ACTIONS);
    // Plan 16.1 asks a chain finding to show slack and graph completeness.
    assertThat(chain.evidence())
        .extracting(Finding.Evidence::detail)
        .allMatch(detail -> detail.contains("slack"));
    assertThat(chain.metrics())
        .extracting(Finding.MetricValue::name)
        .contains("Action-graph completeness");
  }

  private boolean rowExists(long actionId) throws Exception {
    try (Statement statement = writer.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT COUNT(*) FROM actions WHERE id = " + actionId)) {
      rows.next();
      return rows.getLong(1) == 1;
    }
  }

  // --- criterion 4 -------------------------------------------------------

  @Test
  @DisplayName("criterion 4: no finding this session produces claims causation")
  void findingsAvoidUnsupportedCausalLanguage() throws Exception {
    List<String> banned =
        List.of(
            "will improve",
            "will reduce",
            "will speed",
            "definitely",
            "guaranteed",
            "is caused by",
            "was caused by",
            "the cause is",
            "this proves",
            "root cause");

    for (Finding finding : findings(collect())) {
      String text =
          String.join(
                  " ",
                  finding.title(),
                  finding.whyItMayMatter(),
                  finding.caveats(),
                  finding.suggestedInvestigation())
              .toLowerCase(Locale.ROOT);
      for (String phrase : banned) {
        assertThat(text).as("%s uses \"%s\"", finding.ruleId(), phrase).doesNotContain(phrase);
      }
    }
  }

  // --- criterion 5 -------------------------------------------------------

  @Test
  @DisplayName("criterion 5: the same session collected twice gives the same numbers")
  void formulasAreDeterministic() throws Exception {
    SessionMetrics first = collect();
    SessionMetrics second = collect();

    // The aggregates and the findings are records all the way down, so
    // equality here compares every count, every sketch bucket and every
    // sentence.
    assertThat(second.aggregates()).isEqualTo(first.aggregates());
    assertThat(findings(second)).isEqualTo(findings(first));
    assertThat(second.invocation().work()).isEqualTo(first.invocation().work());
    assertThat(second.invocation().coverage()).isEqualTo(first.invocation().coverage());
    assertThat(second.candidates()).isEqualTo(first.candidates());
    // The sweep is a class holding an array, so its fields are compared one
    // at a time rather than by equals.
    assertThat(second.concurrency().peakActive()).isEqualTo(first.concurrency().peakActive());
    assertThat(second.concurrency().idleMicros()).isEqualTo(first.concurrency().idleMicros());
    assertThat(second.concurrency().totalSpanMicros())
        .isEqualTo(first.concurrency().totalSpanMicros());
    assertThat(second.invocation().criticalPaths().derived().orElseThrow().makespanMicros())
        .isEqualTo(first.invocation().criticalPaths().derived().orElseThrow().makespanMicros());
  }

  private static List<Finding> findings(SessionMetrics metrics) {
    return FindingRules.run(metrics.findingInputs(FindingThresholds.defaults()));
  }
}
