package com.holtherndon.bazelviz.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Starlark pprof schema remains additive, relational, and directly queryable. */
final class SchemaV9Test {

  @TempDir Path tempDir;

  @Test
  @DisplayName("migrating a v8 session preserves its existing data")
  void migrationIsAdditive() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("old.db"))) {
      Connection connection = database.writerConnection();
      assertThat(throughV8().migrate(database)).isEqualTo(8);
      exec(connection, "INSERT INTO strings (value) VALUES ('kept')");

      assertThat(MigrationRunner.standard().migrate(database)).isEqualTo(10);

      assertThat(scalar(connection, "SELECT count(*) FROM strings")).isEqualTo(1);
      assertThat(objects(connection, "table"))
          .contains(
              "starlark_profile_metadata",
              "starlark_profile_strings",
              "starlark_profile_sample_types",
              "starlark_profile_mappings",
              "starlark_profile_functions",
              "starlark_profile_locations",
              "starlark_profile_location_lines",
              "starlark_profile_samples",
              "starlark_profile_sample_values",
              "starlark_profile_sample_frames",
              "starlark_profile_sample_labels",
              "starlark_call_nodes",
              "starlark_function_metrics",
              "starlark_file_metrics",
              "starlark_call_edges");
      assertThat(objects(connection, "view"))
          .contains(
              "starlark_hot_functions",
              "starlark_hot_files",
              "starlark_resolved_call_edges",
              "starlark_resolved_call_nodes");
      assertThat(objects(connection, "index"))
          .contains("ix_starlark_profile_functions_filename", "ix_starlark_call_nodes_depth");
      assertThat(columns(connection, "starlark_profile_metadata"))
          .contains(
              "normalized_period_micros",
              "function_attributed_value",
              "function_attributed_samples",
              "file_attributed_value",
              "file_attributed_samples",
              "context_attributed_value",
              "context_attributed_samples");
    }
  }

  @Test
  @DisplayName("resolved views expose symbols and exact derived values")
  void resolvedViewsAreQueryable() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("views.db"))) {
      Connection connection = database.writerConnection();
      MigrationRunner.standard().migrate(database);
      exec(
          connection,
          "INSERT INTO enrichment_tasks"
              + " (id, kind, state, retriable) VALUES"
              + " (1, 'STARLARK_CPU_PROFILE', 'RUNNING', 0)");
      exec(
          connection,
          "INSERT INTO starlark_profile_strings VALUES"
              + " (0, ''), (1, '_slow'), (2, 'rules/slow.bzl')");
      exec(connection, "INSERT INTO starlark_profile_functions VALUES" + " (11, 1, 1, 2, 17)");
      exec(connection, "INSERT INTO starlark_profile_locations VALUES" + " (21, NULL, 21, 0)");
      exec(
          connection, "INSERT INTO starlark_profile_location_lines VALUES" + " (21, 0, 11, 19, 3)");
      exec(
          connection,
          "INSERT INTO starlark_function_metrics"
              + " (function_id, self_value, cumulative_value, self_samples,"
              + " cumulative_samples, context_count)"
              + " VALUES (11, 20000, 50000, 2, 5, 1)");
      exec(
          connection,
          "INSERT INTO starlark_file_metrics"
              + " (filename_string_index, self_value, cumulative_value, self_samples,"
              + " cumulative_samples, function_count)"
              + " VALUES (2, 20000, 50000, 2, 5, 1)");
      exec(
          connection,
          "INSERT INTO starlark_call_nodes VALUES"
              + " (1, NULL, NULL, 0, 50000, 0, 5, 0),"
              + " (2, 1, 21, 1, 50000, 20000, 5, 2)");

      assertThat(text(connection, "SELECT function_name FROM starlark_hot_functions"))
          .isEqualTo("_slow");
      assertThat(text(connection, "SELECT filename FROM starlark_hot_files"))
          .isEqualTo("rules/slow.bzl");
      assertThat(scalar(connection, "SELECT context_count" + " FROM starlark_hot_functions"))
          .isEqualTo(1);
      assertThat(scalar(connection, "SELECT function_count" + " FROM starlark_hot_files"))
          .isEqualTo(1);
      assertThat(
              scalar(
                  connection,
                  "SELECT inclusive_value"
                      + " FROM starlark_resolved_call_nodes WHERE node_id = 2"))
          .isEqualTo(50000);
    }
  }

  @Test
  @DisplayName("pprof IDs remain nonzero and call nodes have unique children")
  void integrityConstraintsRejectAmbiguousRows() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("constraints.db"))) {
      Connection connection = database.writerConnection();
      MigrationRunner.standard().migrate(database);

      assertThatThrownBy(
              () ->
                  exec(connection, "INSERT INTO starlark_profile_functions VALUES (0, 0, 0, 0, 0)"))
          .isInstanceOf(SQLException.class);

      exec(connection, "INSERT INTO starlark_profile_locations VALUES" + " (1, NULL, 0, 0)");
      exec(
          connection,
          "INSERT INTO starlark_call_nodes VALUES"
              + " (1, NULL, NULL, 0, 0, 0, 0, 0),"
              + " (2, 1, 1, 1, 0, 0, 0, 0)");
      assertThatThrownBy(
              () ->
                  exec(
                      connection,
                      "INSERT INTO starlark_call_nodes VALUES" + " (3, 1, 1, 1, 0, 0, 0, 0)"))
          .isInstanceOf(SQLException.class);
    }
  }

  private static MigrationRunner throughV8() {
    return new MigrationRunner(
        List.of(
            new V1Migration(),
            new V2Migration(),
            new V3Migration(),
            new V4Migration(),
            new V5Migration(),
            new V6Migration(),
            new V7Migration(),
            new V8Migration()));
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private static String text(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private static List<String> objects(Connection connection, String kind) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = ?")) {
      statement.setString(1, kind);
      try (ResultSet rows = statement.executeQuery()) {
        var names = new ArrayList<String>();
        while (rows.next()) {
          names.add(rows.getString(1));
        }
        return names;
      }
    }
  }

  private static List<String> columns(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      var names = new ArrayList<String>();
      while (rows.next()) {
        names.add(rows.getString("name"));
      }
      return names;
    }
  }
}
