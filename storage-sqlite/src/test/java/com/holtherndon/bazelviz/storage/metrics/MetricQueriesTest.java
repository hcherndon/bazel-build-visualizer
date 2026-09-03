package com.holtherndon.bazelviz.storage.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.ActionMetrics;
import com.holtherndon.bazelviz.analysis.ConcurrencySweep;
import com.holtherndon.bazelviz.analysis.Coverage;
import com.holtherndon.bazelviz.analysis.CriticalPath;
import com.holtherndon.bazelviz.analysis.FindingRules;
import com.holtherndon.bazelviz.analysis.FindingThresholds;
import com.holtherndon.bazelviz.analysis.GroupAggregate;
import com.holtherndon.bazelviz.analysis.InvocationMetrics;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The metric read path over a database with the awkwardness a real session has: actions with no
 * execution-log record, an action whose spawns ran under two different runners, an action Bazel
 * timed as instantaneous, and a cache state that was never reported.
 *
 * <p>Every one of those is a place a metric can quietly become wrong in the plausible direction —
 * an unreported cache state counted as a miss, an instantaneous action counted as a duration of
 * zero — so each has a test that says what the number must be instead.
 */
final class MetricQueriesTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void buildFixture() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("metrics.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();

    exec("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'OPEN')");
    exec(
        "INSERT INTO build_invocation (singleton, stream_id, started_micros,"
            + " finished_micros, saw_last_message) VALUES (1, 1, 1000, 9000, 1)");
    exec("INSERT INTO build_metrics (singleton, critical_path_micros) VALUES (1, 4321)");
    exec(
        "INSERT INTO bep_events (id, stream_id, sequence, event_type, raw_segment,"
            + " raw_offset, raw_length, decode_status, event_micros, receive_micros)"
            + " VALUES (1, 1, 1, 3, 0, 0, 10, 'OK', 1100, 1400)");
    exec(
        "INSERT INTO bep_events (id, stream_id, sequence, event_type, raw_segment,"
            + " raw_offset, raw_length, decode_status, event_micros, receive_micros)"
            + " VALUES (2, 1, 2, 3, 0, 10, 10, 'FAILED', 1200, 1600)");

    exec("INSERT INTO mnemonics (id, value) VALUES (1, 'CppCompile'), (2, 'Javac')");
    exec("INSERT INTO labels (id, value) VALUES (1, '//pkg/a:lib'), (2, '//pkg/b:app')");
    exec(
        "INSERT INTO enrichment_tasks (id, kind, state)"
            + " VALUES (1, 'EXECUTION_LOG', 'SUCCEEDED')");

    // 1: timed by both sources, one remote spawn, a cache miss.
    action(1, "out/a.o", 1, 1, "SUCCESS", 1_000L, 3_000L);
    attempt(1, 1, 1, "remote", 0, 1_050L, 1_900L, 4_096L);
    // 2: timed by the log only; two spawns under different runners.
    action(2, "out/b.o", 1, 1, "SUCCESS", null, null);
    attempt(2, 2, 2, "local", 0, 2_000L, 500L, 1_024L);
    attempt(3, 3, 2, "worker", 0, 2_600L, 400L, 2_048L);
    // 3: an action Bazel reported with identical start and end, the 8.4.1
    // shape, and no execution-log record at all.
    action(3, "out/c.jar", 2, 2, "SUCCESS", 5_000L, 5_000L);
    // 4: a failure with no timing anywhere and no attempt.
    action(4, "out/d.jar", 2, 2, "FAILED", null, null);
    // 5: a cache hit reported by the log.
    action(5, "out/e.o", 1, 1, "SUCCESS", null, null);
    attempt(4, 5, 5, "remote cache hit", 1, 6_000L, 100L, null);

    exec(
        "INSERT INTO artifacts (id, path, size_bytes, is_directory, is_source)"
            + " VALUES (1, 'out/a.o', 500, 0, 0), (2, 'out/b.o', NULL, 0, 0),"
            + " (3, 'out/tree', NULL, 1, 0)");
    exec("INSERT INTO targets (id, label_id, outcome) VALUES (1, 1, 'BUILT')");
    exec("INSERT INTO configurations (id, stream_id, bep_id) VALUES (1, 1, 'cfg')");
    exec(
        "INSERT INTO configured_targets (id, target_id, configuration_id, outcome)"
            + " VALUES (1, 1, 1, 'BUILT')");
    exec(
        "INSERT INTO tests (id, configured_target_id, overall_status, total_num_cached)"
            + " VALUES (1, 1, 'FLAKY', 0)");
  }

  @AfterEach
  void close() throws Exception {
    database.close();
  }

  private void exec(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private void action(
      long id, String output, long mnemonic, long label, String outcome, Long start, Long end)
      throws SQLException {
    exec(
        "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome,"
            + " start_micros, end_micros) VALUES ("
            + id
            + ", '"
            + output
            + "', "
            + label
            + ", "
            + mnemonic
            + ", '"
            + outcome
            + "', "
            + text(start)
            + ", "
            + text(end)
            + ")");
  }

  private void attempt(
      long id,
      long entry,
      long actionId,
      String runner,
      int cacheHit,
      Long start,
      Long total,
      Long inputBytes)
      throws SQLException {
    exec(
        "INSERT INTO action_attempts (id, task_id, log_entry_index, action_id, correlation,"
            + " runner, cache_hit, start_micros, total_micros, input_bytes) VALUES ("
            + id
            + ", 1, "
            + entry
            + ", "
            + actionId
            + ", 'MATCHED_BY_OUTPUT', '"
            + runner
            + "', "
            + cacheHit
            + ", "
            + text(start)
            + ", "
            + text(total)
            + ", "
            + text(inputBytes)
            + ")");
  }

  private static String text(Long value) {
    return value == null ? "NULL" : value.toString();
  }

  private SessionMetrics collect(CriticalPath.DurationSource source) throws SQLException {
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      return queries.collect(MetricQueries.Request.everything(source));
    }
  }

  @Test
  @DisplayName("the duration source is chosen by measuring what each one covers")
  void durationSourceIsMeasuredNotGuessed() throws Exception {
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      // Two actions have a usable BEP span; four have execution-log
      // timings. The log wins, and it wins because it was counted.
      assertThat(queries.bestDurationSource())
          .isEqualTo(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    }
  }

  @Test
  @DisplayName("an action Bazel timed as instantaneous is untimed, not a duration of zero")
  void instantaneousActionsAreNotZero() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.BEP_ACTION);

    GroupAggregate.Table byMnemonic =
        metrics.aggregate(GroupAggregate.Dimension.MNEMONIC).orElseThrow();
    GroupAggregate javac =
        byMnemonic.groups().stream()
            .filter(group -> "Javac".equals(group.key()))
            .findFirst()
            .orElseThrow();

    // Action 3 has start == end and action 4 has neither: two Javac
    // actions, neither of them timed, and a minimum of zero would be a
    // claim that one of them took no time.
    assertThat(javac.actions()).isEqualTo(2);
    assertThat(javac.duration().observed()).isZero();
    assertThat(javac.duration().unavailable()).isEqualTo(2);
    assertThat(javac.duration().distribution().min()).isEmpty();
    assertThat(javac.duration().observedSum()).isEmpty();
  }

  @Test
  @DisplayName("the sweep still sees an instantaneous span, and says it occupies no time")
  void instantaneousSpansReachTheSweep() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.BEP_ACTION);

    ConcurrencySweep.Result sweep = metrics.concurrency();
    assertThat(sweep.instantaneousSpans()).isEqualTo(1);
    assertThat(sweep.sweptSpans()).isEqualTo(1);
    assertThat(sweep.untimedSpans()).isEqualTo(3);
    assertThat(sweep.describe()).contains("same instant");
  }

  @Test
  @DisplayName("invocation concurrency is unavailable when no span occupies time")
  void invocationConcurrencyIsAbsentWithoutUsableSpans() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.NONE);

    assertThat(metrics.concurrency().sweptSpans()).isZero();
    assertThat(metrics.invocation().concurrency()).isEmpty();
  }

  @Test
  @DisplayName("an action whose spawns ran under different runners has no runner")
  void mixedRunnersAreNotResolved() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    GroupAggregate.Table byRunner =
        metrics.aggregate(GroupAggregate.Dimension.RUNNER).orElseThrow();
    GroupAggregate unknown =
        byRunner.groups().stream().filter(GroupAggregate::isUnknownKey).findFirst().orElseThrow();

    // Action 2 ran local and worker; actions 3 and 4 never spawned at all.
    assertThat(unknown.actions()).isEqualTo(3);
    assertThat(unknown.displayKey()).isEqualTo("(not recorded)");
    assertThat(byRunner.groups())
        .extracting(GroupAggregate::key)
        .contains("remote", "remote cache hit");
  }

  @Test
  @DisplayName("an unreported cache state is neither a hit nor a miss")
  void unreportedCacheStateIsItsOwnAnswer() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    InvocationMetrics.Work work = metrics.invocation().work();

    // Actions 1 and 2 missed, action 5 hit, actions 3 and 4 never spawned
    // a subprocess and so reported nothing at all.
    assertThat(work.cacheHits()).isEqualTo(1);
    assertThat(work.cacheMisses()).isEqualTo(2);
    assertThat(work.cacheStateUnknown()).isEqualTo(2);
    // The rate is over the three that reported, never over all five.
    assertThat(work.cacheHitRate()).hasValue(1.0 / 3.0);
    assertThat(
            metrics.invocation().coverage().find("Cache-state coverage").orElseThrow().describe())
        .contains("60.0%");
  }

  @Test
  @DisplayName("every aggregate accounts for every action")
  void aggregatesAccountForEverything() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    for (GroupAggregate.Dimension dimension : GroupAggregate.Dimension.values()) {
      GroupAggregate.Table table = metrics.aggregate(dimension).orElseThrow();
      assertThat(table.totalActions()).as("%s total", dimension).isEqualTo(5);
      assertThat(table.groups().stream().mapToLong(GroupAggregate::actions).sum())
          .as("%s grouped", dimension)
          .isEqualTo(5);
    }
  }

  @Test
  @DisplayName("package grouping reuses the label parser rather than inventing another")
  void packagesComeFromLabels() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    assertThat(metrics.aggregate(GroupAggregate.Dimension.PACKAGE).orElseThrow().groups())
        .extracting(GroupAggregate::key)
        .containsExactlyInAnyOrder("//pkg/a", "//pkg/b");
  }

  @Test
  @DisplayName("the two critical paths are both reported and neither stands in for the other")
  void criticalPathsStayApart() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    var paths = metrics.invocation().criticalPaths();
    assertThat(paths.bazelReportedMicros().value()).hasValue(4321L);
    assertThat(paths.bazelReportedMicros().source()).isEqualTo(DataSource.BEP);
    // No graph was imported here, so there is no derived path -- and
    // Bazel's number does not move into the empty slot.
    assertThat(paths.derived()).isEmpty();
    assertThat(paths.bothAvailable()).isFalse();
    assertThat(paths.schedulingGapMicros()).isEmpty();
    assertThat(paths.observedActionLowerBound())
        .hasValueSatisfying(
            fallback -> {
              assertThat(fallback.actionId()).isEqualTo(1);
              assertThat(fallback.primaryOutput()).isEqualTo("out/a.o");
              assertThat(fallback.targetLabel()).hasValue("//pkg/a:lib");
              assertThat(fallback.mnemonic()).hasValue("CppCompile");
              assertThat(fallback.durationMicros()).isEqualTo(2_000);
              assertThat(fallback.timedActions()).isEqualTo(1);
              assertThat(fallback.totalActions()).isEqualTo(5);
            });
    assertThat(paths.describe())
        .contains("Bazel-reported critical path")
        .contains("Visualizer-computed dependency critical path")
        .contains("no imported action graph");
  }

  @Test
  @DisplayName("profile components are identified as the fallback critical-path source")
  void profileFallbackKeepsItsProvenance() throws Exception {
    exec("DELETE FROM build_metrics");
    trustedProfile();
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'first', 1000), (2, 1, 'second', 2500)");

    var paths = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT).invocation().criticalPaths();
    var reported = paths.bazelReportedMicros();

    assertThat(reported.value()).hasValue(3_500L);
    assertThat(reported.source()).isEqualTo(DataSource.PROFILE);
    assertThat(paths.bazelComponentCount()).isEqualTo(2);
    assertThat(paths.bazelComponents()).isEmpty();

    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(queries.bazelCriticalPathComponents(0, 1))
          .singleElement()
          .satisfies(
              component -> {
                assertThat(component.ordinal()).isZero();
                assertThat(component.description()).isEqualTo("first");
                assertThat(component.durationMicros()).hasValue(1_000L);
              });
      assertThat(queries.bazelCriticalPathComponents(1, 10))
          .singleElement()
          .satisfies(
              component -> {
                assertThat(component.ordinal()).isEqualTo(1);
                assertThat(component.description()).isEqualTo("second");
                assertThat(component.durationMicros()).hasValue(2_500L);
              });
      assertThat(queries.bazelCriticalPathComponents(2, 10)).isEmpty();
    }
  }

  @Test
  @DisplayName("a valid profile fallback supersedes an invalid BuildMetrics duration")
  void validProfileSupersedesInvalidBuildMetrics() throws Exception {
    exec("UPDATE build_metrics SET critical_path_micros = -5");
    trustedProfile();
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'valid profile value', 2500)");

    var reported =
        collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT)
            .invocation()
            .criticalPaths()
            .bazelReportedMicros();

    assertThat(reported.value()).hasValue(2_500L);
    assertThat(reported.source()).isEqualTo(DataSource.PROFILE);
  }

  @Test
  @DisplayName("an incomplete profile component total stays unavailable")
  void incompleteProfileTotalIsUnavailable() throws Exception {
    exec("DELETE FROM build_metrics");
    trustedProfile();
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'known', 1000), (2, 1, 'unknown', NULL)");

    var reported =
        collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT)
            .invocation()
            .criticalPaths()
            .bazelReportedMicros();

    assertThat(reported.value()).isEmpty();
    assertThat(reported.warning())
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("component durations are incomplete"));
  }

  @Test
  @DisplayName("negative and overflowing profile totals stay unavailable")
  void invalidProfileTotalsAreUnavailable() throws Exception {
    exec("DELETE FROM build_metrics");
    trustedProfile();
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'negative', -1)");

    var negative =
        collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT)
            .invocation()
            .criticalPaths()
            .bazelReportedMicros();
    assertThat(negative.value()).isEmpty();
    assertThat(negative.warning())
        .hasValueSatisfying(reason -> assertThat(reason).contains("negative"));

    exec("DELETE FROM bazel_critical_path");
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'huge', "
            + Long.MAX_VALUE
            + "),"
            + " (2, 1, 'overflow', 1)");
    var overflow =
        collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT)
            .invocation()
            .criticalPaths()
            .bazelReportedMicros();
    assertThat(overflow.value()).isEmpty();
    assertThat(overflow.warning())
        .hasValueSatisfying(reason -> assertThat(reason).contains("overflowed"));
  }

  @Test
  @DisplayName("noncontiguous profile component ordinals cannot drive paging")
  void noncontiguousProfileComponentsAreWithheld() throws Exception {
    exec("DELETE FROM build_metrics");
    trustedProfile();
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'first', 1000), (2, 2, 'gap', 2500)");

    var paths = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT).invocation().criticalPaths();

    assertThat(paths.bazelReportedMicros().value()).isEmpty();
    assertThat(paths.bazelReportedMicros().warning())
        .hasValueSatisfying(reason -> assertThat(reason).contains("ordinals are not contiguous"));
    assertThat(paths.bazelComponentCount()).isEqualTo(2);
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(queries.bazelCriticalPathComponents(0, 10)).isEmpty();
    }
  }

  @Test
  @DisplayName("profile components from another build are withheld")
  void mismatchedProfileComponentsAreWithheld() throws Exception {
    exec("INSERT INTO enrichment_tasks (id, kind, state)" + " VALUES (2, 'PROFILE', 'SUCCEEDED')");
    exec(
        "INSERT INTO profile_metadata (id, task_id, build_id_matches, anchor_meaning)"
            + " VALUES (1, 2, 0, 'EXACT_START')");
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'another build', 1000)");

    var paths = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT).invocation().criticalPaths();

    assertThat(paths.bazelReportedMicros().value()).hasValue(4_321L);
    assertThat(paths.bazelReportedMicros().warning())
        .hasValueSatisfying(reason -> assertThat(reason).contains("different build"));
    assertThat(paths.bazelComponents()).isEmpty();
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(queries.bazelCriticalPathComponents(0, 10)).isEmpty();
    }
  }

  @Test
  @DisplayName("profile rows retained after a failed retry are withheld")
  void failedProfileDoesNotExposeStaleComponents() throws Exception {
    exec("DELETE FROM build_metrics");
    exec("INSERT INTO enrichment_tasks (id, kind, state)" + " VALUES (2, 'PROFILE', 'FAILED')");
    exec(
        "INSERT INTO profile_metadata (id, task_id, build_id_matches, anchor_meaning)"
            + " VALUES (1, 2, 1, 'EXACT_START')");
    exec(
        "INSERT INTO bazel_critical_path (id, ordinal, description, duration_micros)"
            + " VALUES (1, 0, 'retained stale row', 1000)");

    var paths = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT).invocation().criticalPaths();

    assertThat(paths.bazelReportedMicros().value()).isEmpty();
    assertThat(paths.bazelReportedMicros().warning())
        .hasValueSatisfying(
            reason -> assertThat(reason).contains("trace-profile import state is FAILED"));
    assertThat(paths.bazelComponents()).isEmpty();
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(queries.bazelCriticalPathComponents(0, 10)).isEmpty();
    }
  }

  @Test
  @DisplayName("critical-path component pages reject unbounded or invalid requests")
  void criticalPathComponentPageBoundsAreExplicit() throws Exception {
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(Assertions.catchThrowable(() -> queries.bazelCriticalPathComponents(-1, 1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("first ordinal");
      assertThat(Assertions.catchThrowable(() -> queries.bazelCriticalPathComponents(0, 0)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limit");
    }
  }

  @Test
  @DisplayName("failed execution-log retries do not expose retained attempts")
  void failedExecutionLogDoesNotExposeStaleAttempts() throws Exception {
    exec("UPDATE enrichment_tasks SET state = 'FAILED' WHERE id = 1");

    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(queries.bestDurationSource()).isEqualTo(CriticalPath.DurationSource.BEP_ACTION);
    }
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    assertThat(metrics.invocation().work().attempts()).isZero();
    assertThat(metrics.invocation().coverage().find("Correlation coverage"))
        .hasValueSatisfying(
            coverage -> {
              assertThat(coverage.covered()).isZero();
              assertThat(coverage.reason())
                  .hasValueSatisfying(
                      reason ->
                          assertThat(reason).contains("execution-log import state is FAILED"));
            });
  }

  @Test
  @DisplayName("an action is execution-log timed only when every attempt has a duration")
  void mixedAttemptDurationsDoNotCountAsTimed() throws Exception {
    attempt(5, 6, 1, "retry", 0, 3_100L, null, 100L);
    exec("UPDATE action_attempts SET total_micros = NULL WHERE action_id IN (2, 5)");

    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      assertThat(queries.bestDurationSource()).isEqualTo(CriticalPath.DurationSource.BEP_ACTION);
    }
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    assertThat(metrics.invocation().coverage().find("Timing coverage"))
        .hasValueSatisfying(coverage -> assertThat(coverage.covered()).isZero());
  }

  @Test
  @DisplayName("declared-action rows retained after a failed import do not report correlation")
  void failedActionGraphDoesNotExposeStaleCorrelation() throws Exception {
    exec(
        "INSERT INTO graph_sources"
            + " (id, kind, state, configuration_match, target_scope, error_excerpt)"
            + " VALUES (1, 'DECLARED_ACTIONS', 'FAILED', 'EXACT',"
            + " 'EXACT_BEP_TARGETS', 'retry failed')");
    exec(
        "INSERT INTO declared_actions (id, source_id, graph_id, action_id, node_index)"
            + " VALUES (1, 1, 0, 1, 0)");

    Coverage correlation =
        collect(CriticalPath.DurationSource.BEP_ACTION)
            .invocation()
            .coverage()
            .find("Action-graph correlation")
            .orElseThrow();

    assertThat(correlation.covered()).isZero();
    assertThat(correlation.reason())
        .hasValueSatisfying(
            reason ->
                assertThat(reason)
                    .contains("current aquery import state is FAILED")
                    .contains("retry failed"));
  }

  private void trustedProfile() throws SQLException {
    exec("INSERT INTO enrichment_tasks (id, kind, state)" + " VALUES (2, 'PROFILE', 'SUCCEEDED')");
    exec(
        "INSERT INTO profile_metadata (id, task_id, build_id_matches, anchor_meaning)"
            + " VALUES (1, 2, 1, 'EXACT_START')");
  }

  @Test
  @DisplayName("coverage names every source and explains every hole")
  void coverageIsExplained() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    Coverage.Report coverage = metrics.invocation().coverage();

    assertThat(coverage.entries())
        .extracting(Coverage::name)
        .containsExactly(
            "Timing coverage",
            "Runner coverage",
            "Cache-state coverage",
            "Input-size coverage",
            "Output-size coverage",
            "Action-graph correlation",
            "Action-graph completeness",
            "Target-graph coverage",
            "Correlation coverage");
    assertThat(coverage.isComplete()).isFalse();
    for (Coverage entry : coverage.incomplete()) {
      assertThat(entry.reason()).as("%s must say why", entry.name()).isNotEmpty();
    }
    // No aquery or cquery ran, so those are unavailable rather than zero
    // per cent of something.
    assertThat(coverage.find("Action-graph correlation").orElseThrow().describe())
        .contains("unavailable")
        .contains("no aquery output");
    assertThat(coverage.find("Action-graph completeness").orElseThrow().describe())
        .contains("unavailable")
        .contains("no aquery output");
  }

  @Test
  @DisplayName("byte totals carry what they could not count")
  void byteTotalsAreHonest() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    InvocationMetrics.Bytes bytes = metrics.invocation().bytes();

    // Inputs: 4096 from action 1 and 1024 + 2048 from action 2's two
    // spawns. Action 5's spawn reported no size and actions 3 and 4 never
    // spawned, so three of the five contribute nothing -- and contribute
    // nothing rather than zero.
    assertThat(bytes.knownInputBytes().value()).hasValue(7_168L);
    assertThat(bytes.actionsWithoutInputBytes()).isEqualTo(3);
    // Outputs: one artifact of 500 bytes, one with no size, and the tree
    // artifact excluded because summing it with its children double-counts.
    assertThat(bytes.knownOutputBytes().value()).hasValue(500L);
    assertThat(bytes.artifactsWithoutSize()).isEqualTo(1);
    assertThat(bytes.isPartial()).isTrue();
  }

  @Test
  @DisplayName("invocation counts, tests and ingest come back from the same read")
  void invocationLevelNumbers() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);
    InvocationMetrics invocation = metrics.invocation();

    assertThat(invocation.timing().totalWallMicros().value()).hasValue(8_000L);
    assertThat(invocation.timing().timeToFirstEventMicros().value()).hasValue(400L);
    assertThat(invocation.work().actions()).isEqualTo(5);
    assertThat(invocation.work().attempts()).isEqualTo(4);
    assertThat(invocation.work().actionsFailed()).isEqualTo(1);
    assertThat(invocation.tests().total()).isEqualTo(1);
    assertThat(invocation.tests().flaky()).isEqualTo(1);
    assertThat(invocation.ingest().rawEvents()).isEqualTo(2);
    assertThat(invocation.ingest().undecodableEvents()).isEqualTo(1);
    assertThat(invocation.ingest().notAttemptedEvents()).isZero();
    assertThat(invocation.ingest().meanIngestLagMicros().value()).hasValue(350L);
    assertThat(invocation.ingest().correlationRate()).hasValue(1.0);
  }

  @Test
  @DisplayName("start and completion concurrency are attached from the same sweep")
  void perActionConcurrency() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    // Action 1 runs 1050-2950; action 2's attempts span 2000-3000. When
    // action 2 starts at 2000 both are running; a microsecond before it
    // ends at 3000, only it is.
    ActionMetrics second =
        metrics.candidates().stream()
            .filter(action -> action.actionId() == 2)
            .findFirst()
            .orElseThrow();
    assertThat(second.startConcurrency()).hasValue(2);
    assertThat(second.completionConcurrency()).hasValue(1);
    // Action 5's spawn was a cache hit with a start, so it too is placed;
    // an action with no observed start would carry neither figure.
    assertThat(metrics.candidates())
        .allSatisfy(
            action ->
                assertThat(action.startConcurrency().isPresent())
                    .isEqualTo(action.startMicros().isPresent()));
  }

  @Test
  @DisplayName("a duration series names the quantity it measured, not just 'duration'")
  void durationSeriesAreNamed() throws Exception {
    assertThat(
            collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT)
                .aggregate(GroupAggregate.Dimension.MNEMONIC)
                .orElseThrow()
                .groups()
                .getFirst()
                .duration()
                .name())
        .isEqualTo("Subprocess time");
    assertThat(
            collect(CriticalPath.DurationSource.BEP_ACTION)
                .aggregate(GroupAggregate.Dimension.MNEMONIC)
                .orElseThrow()
                .groups()
                .getFirst()
                .duration()
                .name())
        .isEqualTo("Action wall duration");
  }

  @Test
  @DisplayName("BEP wall durations are not mixed with subprocess timing components")
  void bepDurationsDoNotMixAttemptComponents() throws Exception {
    exec(
        "UPDATE action_attempts SET queue_micros = 100, setup_micros = 50,"
            + " execution_wall_micros = 1750, network_micros = 0,"
            + " upload_micros = 0, fetch_micros = 0 WHERE action_id = 1");

    var first =
        collect(CriticalPath.DurationSource.BEP_ACTION).candidates().stream()
            .filter(action -> action.actionId() == 1)
            .findFirst()
            .orElseThrow();

    assertThat(first.durationMicros()).hasValue(2_000L);
    assertThat(first.queueMicros()).isEmpty();
    assertThat(first.queueFraction()).isEmpty();
    assertThat(first.unaccountedMicros()).isEmpty();
  }

  @Test
  @DisplayName("the candidates are the extremes, carrying the timing breakdown a rule needs")
  void candidatesAreTheExtremes() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    // Actions 3 and 4 spawned nothing and score zero on every criterion, so
    // they are not candidates -- an action that did nothing measurable is
    // not an extreme of anything.
    assertThat(metrics.candidates())
        .extracting(ActionMetrics::actionId)
        .containsExactlyInAnyOrder(1L, 2L, 5L);
    ActionMetrics first =
        metrics.candidates().stream()
            .filter(action -> action.actionId() == 1)
            .findFirst()
            .orElseThrow();
    assertThat(first.attempts()).isEqualTo(1);
    assertThat(first.inputBytes()).hasValue(4_096L);
    assertThat(first.startConcurrency()).isPresent();
    // No graph was imported, so fan-out is unknown rather than zero.
    assertThat(first.directConsumers()).isEmpty();
    assertThat(first.slackMicros()).isEmpty();
  }

  @Test
  @DisplayName("the finding inputs carry the windows the thresholds actually found")
  void findingInputsUseTheSessionsOwnConcurrency() throws Exception {
    SessionMetrics metrics = collect(CriticalPath.DurationSource.EXECUTION_ATTEMPT);

    var inputs = metrics.findingInputs(FindingThresholds.defaults());

    assertThat(inputs.invocation()).isSameAs(metrics.invocation());
    assertThat(inputs.candidates()).isEqualTo(metrics.candidates());
    // Every rule runs over this fixture without producing a finding it
    // cannot support, which is the property that matters here.
    assertThat(FindingRules.run(inputs))
        .allSatisfy(finding -> assertThat(finding.evidence()).isNotEmpty());
  }

  @Test
  @DisplayName("a truncated aggregate says how much it left out")
  void truncationIsStated() throws Exception {
    try (MetricQueries queries = new MetricQueries(database.newReadConnection())) {
      SessionMetrics metrics =
          queries.collect(
              new MetricQueries.Request(
                  CriticalPath.DurationSource.EXECUTION_ATTEMPT,
                  Set.of(GroupAggregate.Dimension.TARGET),
                  1,
                  MetricQueries.DEFAULT_CANDIDATE_LIMIT));

      GroupAggregate.Table table = metrics.aggregate(GroupAggregate.Dimension.TARGET).orElseThrow();
      assertThat(table.groups()).hasSize(1);
      assertThat(table.totalGroups()).isEqualTo(2);
      assertThat(table.isTruncated()).isTrue();
      assertThat(table.describe()).contains("remaining 1").contains("2 actions");
    }
  }
}
