package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteStarlarkProfileReaderTest {

  @TempDir Path tempDir;

  @Test
  void readsSummaryPagedAggregatesEdgesAndBoundedFlameRows() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("profile.db"))) {
      MigrationRunner.standard().migrate(database);
      insertProfile(database.writerConnection());

      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        StarlarkProfileReader.Summary summary = reader.summary();
        assertThat(summary.availability()).isEqualTo(StarlarkProfileReader.Availability.AVAILABLE);
        assertThat(summary.sampledCpuMicros()).hasValue(30_000);
        assertThat(summary.wallDurationMicros()).hasValue(50_000);
        assertThat(summary.samplePeriodMicros()).hasValue(10_000);
        assertThat(summary.functionCount()).hasValue(2);
        assertThat(summary.fileCount()).hasValue(2);
        assertThat(summary.functionAttribution().attributedCpuMicros()).hasValue(30_000);
        assertThat(summary.functionAttribution().unattributedCpuMicros()).hasValue(0);
        assertThat(summary.fileAttribution().attributedCpuMicros()).hasValue(20_000);
        assertThat(summary.fileAttribution().unattributedCpuMicros()).hasValue(10_000);
        assertThat(summary.fileAttribution().attributedSampleRecords()).hasValue(2);
        assertThat(summary.fileAttribution().unattributedSampleRecords()).hasValue(1);
        assertThat(summary.contextAttribution().attributedCpuMicros()).hasValue(10_000);
        assertThat(summary.contextAttribution().unattributedCpuMicros()).hasValue(20_000);

        var functions =
            reader.hotFunctions(
                new StarlarkProfileReader.FunctionQuery(
                    "slow", StarlarkProfileReader.FunctionSort.SELF_CPU, true),
                0,
                10);
        assertThat(
                reader.hotFunctionCount(
                    new StarlarkProfileReader.FunctionQuery(
                        "slow", StarlarkProfileReader.FunctionSort.SELF_CPU, true)))
            .isEqualTo(1);
        assertThat(functions)
            .singleElement()
            .satisfies(
                function -> {
                  assertThat(function.name()).isEqualTo("slow_rule");
                  assertThat(function.selfCpuMicros()).hasValue(20_000);
                  assertThat(function.contextCount()).hasValue(1);
                  assertThat(function.source())
                      .get()
                      .satisfies(
                          source -> {
                            assertThat(source.path()).isEqualTo("rules/slow.bzl");
                            assertThat(source.line()).hasValue(17);
                          });
                });
        var allFunctions =
            new StarlarkProfileReader.FunctionQuery(
                "", StarlarkProfileReader.FunctionSort.SELF_CPU, true);
        assertThat(reader.hotFunctionCount(allFunctions)).isEqualTo(2);
        assertThat(reader.hotFunctions(allFunctions, 0, 10)).hasSize(2);

        var matchingFiles =
            new StarlarkProfileReader.FileQuery(
                "rules", StarlarkProfileReader.FileSort.CUMULATIVE_CPU, true);
        assertThat(reader.sourceFileCount(matchingFiles)).isEqualTo(2);
        assertThat(reader.sourceFiles(matchingFiles, 0, 1))
            .singleElement()
            .satisfies(
                file -> {
                  assertThat(file.path()).isEqualTo("rules/slow.bzl");
                  assertThat(file.functionCount()).hasValue(1);
                });
        var allFiles =
            new StarlarkProfileReader.FileQuery(
                "", StarlarkProfileReader.FileSort.CUMULATIVE_CPU, true);
        assertThat(reader.sourceFileCount(allFiles)).isEqualTo(2);
        assertThat(reader.sourceFiles(allFiles, 0, 10)).hasSize(2);

        assertThat(reader.callEdges(11, StarlarkProfileReader.CallDirection.CALLEES, 0, 10))
            .singleElement()
            .satisfies(
                edge -> {
                  assertThat(edge.relatedFunction()).isEqualTo("helper");
                  assertThat(edge.cpuMicros()).hasValue(10_000);
                });
        assertThat(reader.callEdgeCount(12, StarlarkProfileReader.CallDirection.CALLERS))
            .isEqualTo(1);

        StarlarkProfileReader.DirectedCallGraph graph =
            reader.directedCallGraph(OptionalLong.of(12), 10, 10);
        assertThat(graph.totalFunctionCount()).isEqualTo(2);
        assertThat(graph.omittedFunctionCount()).isZero();
        assertThat(graph.visibleEdgeCount()).isEqualTo(1);
        assertThat(graph.omittedVisibleEdgeCount()).isZero();
        assertThat(graph.totalCpuMicros()).hasValue(30_000);
        assertThat(graph.nodes())
            .extracting(StarlarkProfileReader.CallGraphNode::functionId)
            .containsExactly(12L, 11L);
        assertThat(graph.edges())
            .singleElement()
            .satisfies(
                edge -> {
                  assertThat(edge.callerFunctionId()).isEqualTo(11);
                  assertThat(edge.calleeFunctionId()).isEqualTo(12);
                  assertThat(edge.cpuMicros()).hasValue(10_000);
                });

        StarlarkProfileReader.DirectedCallGraph boundedGraph =
            reader.directedCallGraph(OptionalLong.empty(), 1, 10);
        assertThat(boundedGraph.totalFunctionCount()).isEqualTo(2);
        assertThat(boundedGraph.omittedFunctionCount()).isEqualTo(1);
        assertThat(boundedGraph.nodes()).hasSize(1);
        assertThat(boundedGraph.visibleEdgeCount()).isZero();

        StarlarkProfileReader.FlameSlice bounded = reader.flameRows(OptionalLong.empty(), 1);
        assertThat(bounded.totalNodeCount()).isEqualTo(2);
        assertThat(bounded.omittedNodeCount()).isEqualTo(1);
        assertThat(bounded.totalCpuMicros()).hasValue(30_000);
        assertThat(bounded.nodes())
            .singleElement()
            .satisfies(
                node -> {
                  assertThat(node.function()).isEqualTo("slow_rule");
                  assertThat(node.parentId()).isEmpty();
                  assertThat(node.depth()).isZero();
                });

        StarlarkProfileReader.FlameSlice focused = reader.flameRows(OptionalLong.of(2), 10);
        assertThat(focused.nodes())
            .extracting(StarlarkProfileReader.FlameNode::function)
            .containsExactly("slow_rule", "helper");
        assertThat(focused.nodes().get(0).parentId()).isEmpty();
        assertThat(focused.nodes().get(1).parentId()).hasValue(2);
      }

      exec(
          database.writerConnection(),
          "UPDATE enrichment_tasks SET"
              + " state = 'FAILED', error_excerpt = 'replacement was malformed'"
              + " WHERE kind = 'STARLARK_CPU_PROFILE'");
      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        assertThat(reader.summary().availability())
            .isEqualTo(StarlarkProfileReader.Availability.IMPORT_FAILED);
        assertThat(reader.summary().detail()).isEqualTo("replacement was malformed");
      }
    }
  }

  @Test
  void distinguishesNotCapturedFromFailedImport() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("missing.db"))) {
      MigrationRunner.standard().migrate(database);
      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        assertThat(reader.summary().availability())
            .isEqualTo(StarlarkProfileReader.Availability.NOT_CAPTURED);
      }
      exec(
          database.writerConnection(),
          "INSERT INTO enrichment_tasks"
              + " (id, kind, state, error_excerpt, retriable) VALUES"
              + " (1, 'STARLARK_CPU_PROFILE', 'FAILED', 'bad pprof', 1)");
      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        assertThat(reader.summary().availability())
            .isEqualTo(StarlarkProfileReader.Availability.IMPORT_FAILED);
        assertThat(reader.summary().detail()).isEqualTo("bad pprof");
      }
    }
  }

  @Test
  void neverExposesTheRawPeriodAsMicrosecondsWhenNormalizationIsUnavailable() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("period.db"))) {
      MigrationRunner.standard().migrate(database);
      insertProfile(database.writerConnection());
      exec(
          database.writerConnection(),
          "UPDATE starlark_profile_metadata" + " SET normalized_period_micros = NULL");

      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        assertThat(reader.summary().samplePeriodMicros()).isEmpty();
        assertThat(reader.summary().sampledCpuMicros()).hasValue(30_000);
      }
    }
  }

  @Test
  void distinguishesUnsupportedFromReviewSkippedProfiles() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("skipped.db"))) {
      MigrationRunner.standard().migrate(database);
      exec(
          database.writerConnection(),
          "INSERT INTO enrichment_tasks"
              + " (id, kind, state, exit_status, retriable) VALUES"
              + " (1, 'STARLARK_CPU_PROFILE', 'SKIPPED',"
              + " 'UNSUPPORTED: this Bazel does not accept the flag', 0)");

      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        assertThat(reader.summary().availability())
            .isEqualTo(StarlarkProfileReader.Availability.UNSUPPORTED);
        assertThat(reader.summary().detail()).isEqualTo("this Bazel does not accept the flag");
      }

      exec(
          database.writerConnection(),
          "UPDATE enrichment_tasks SET"
              + " exit_status = 'SKIPPED: disabled in the launch review'"
              + " WHERE kind = 'STARLARK_CPU_PROFILE'");
      try (StarlarkProfileReader reader =
          new SqliteStarlarkProfileReader("fixture", database.newReadConnection())) {
        assertThat(reader.summary().availability())
            .isEqualTo(StarlarkProfileReader.Availability.SKIPPED);
        assertThat(reader.summary().detail()).isEqualTo("disabled in the launch review");
      }
    }
  }

  @Test
  void pageQueriesUseMaterializedCountsAndScaleIndexes() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("plans.db"))) {
      MigrationRunner.standard().migrate(database);
      Connection connection = database.writerConnection();
      insertProfile(connection);

      var functionQuery =
          new StarlarkProfileReader.FunctionQuery(
              "", StarlarkProfileReader.FunctionSort.SELF_CPU, true);
      List<String> functionPlan =
          queryPlan(
              connection, SqliteStarlarkProfileReader.hotFunctionsSql(functionQuery), 200, 0L);
      assertThat(functionPlan)
          .anyMatch(detail -> detail.contains("ix_starlark_function_metrics_self"))
          .noneMatch(
              detail -> detail.contains("CORRELATED") || detail.contains("starlark_call_nodes"));

      var fileQuery =
          new StarlarkProfileReader.FileQuery(
              "", StarlarkProfileReader.FileSort.CUMULATIVE_CPU, true);
      List<String> filePlan =
          queryPlan(connection, SqliteStarlarkProfileReader.sourceFilesSql(fileQuery), 200, 0L);
      assertThat(filePlan)
          .anyMatch(detail -> detail.contains("ix_starlark_file_metrics_cumulative"))
          .noneMatch(
              detail ->
                  detail.contains("CORRELATED") || detail.contains("starlark_profile_functions"));

      List<String> flamePlan =
          queryPlan(connection, SqliteStarlarkProfileReader.rootFlameRowsSql(), 5_000);
      assertThat(flamePlan)
          .anyMatch(detail -> detail.contains("ix_starlark_call_nodes_depth"))
          .noneMatch(detail -> detail.contains("USE TEMP B-TREE FOR ORDER BY"));
    }
  }

  private static void insertProfile(Connection connection) throws Exception {
    exec(
        connection,
        "INSERT INTO enrichment_tasks"
            + " (id, kind, source_path, state, retriable) VALUES"
            + " (1, 'STARLARK_CPU_PROFILE', 'raw/starlark-cpu.pprof.gz', 'SUCCEEDED', 0)");
    exec(
        connection,
        "INSERT INTO starlark_profile_strings VALUES"
            + " (0, ''), (1, 'slow_rule'), (2, 'rules/slow.bzl'),"
            + " (3, 'helper'), (4, 'rules/helper.bzl')");
    exec(
        connection,
        "INSERT INTO starlark_profile_functions VALUES" + " (11, 1, 1, 2, 17), (12, 3, 3, 4, 9)");
    exec(
        connection,
        "INSERT INTO starlark_profile_locations VALUES" + " (21, NULL, 0, 0), (22, NULL, 0, 0)");
    exec(
        connection,
        "INSERT INTO starlark_profile_location_lines VALUES"
            + " (21, 0, 11, 40, 0), (22, 0, 12, 30, 0)");
    exec(
        connection,
        "INSERT INTO starlark_profile_metadata"
            + " (id, task_id, format, compressed_bytes, uncompressed_bytes,"
            + " profile_time_nanos, duration_nanos, period, normalized_period_micros,"
            + " selected_sample_type_ordinal,"
            + " sample_count, mapping_count, location_count, function_count, string_count,"
            + " max_stack_depth, total_value,"
            + " function_attributed_value,function_attributed_samples,"
            + " file_attributed_value,file_attributed_samples,"
            + " context_attributed_value,context_attributed_samples,"
            + " validation_state, validation_detail) VALUES"
            + " (1, 1, 'gzip-pprof', 100, 200, 1000000, 50000000, 10000000, 10000, 0,"
            + " 3, 0, 2, 2, 5, 2, 30000, 30000, 3, 20000, 2, 10000, 1,"
            + " 'VALID', 'CPU / microseconds')");
    exec(
        connection,
        "INSERT INTO starlark_function_metrics"
            + " (function_id, self_value, cumulative_value, self_samples,"
            + " cumulative_samples, context_count) VALUES"
            + " (11, 20000, 30000, 2, 3, 1), (12, 10000, 10000, 1, 1, 1)");
    exec(
        connection,
        "INSERT INTO starlark_file_metrics"
            + " (filename_string_index, self_value, cumulative_value, self_samples,"
            + " cumulative_samples, function_count) VALUES"
            + " (2, 20000, 30000, 2, 3, 1), (4, 10000, 10000, 1, 1, 1)");
    exec(
        connection,
        "INSERT INTO starlark_call_nodes VALUES"
            + " (1, NULL, NULL, 0, 30000, 0, 3, 0),"
            + " (2, 1, 21, 1, 30000, 20000, 3, 2),"
            + " (3, 2, 22, 2, 10000, 10000, 1, 1)");
    exec(connection, "INSERT INTO starlark_call_edges VALUES (21, 22, 10000, 1)");
  }

  private static void exec(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<String> queryPlan(Connection connection, String sql, Object... parameters)
      throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        List<String> plan = new ArrayList<>();
        while (rows.next()) {
          plan.add(rows.getString("detail"));
        }
        return List.copyOf(plan);
      }
    }
  }
}
