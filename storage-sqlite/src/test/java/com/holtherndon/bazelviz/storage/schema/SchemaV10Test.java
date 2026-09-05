package com.holtherndon.bazelviz.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The graph node index is unique when present and is its own ordered covering index. */
final class SchemaV10Test {

  @TempDir Path tempDir;

  @Test
  @DisplayName("v9 sessions gain a unique node-index covering index without losing rows")
  void migrationAddsUniqueNodeIndex() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("v9.db"))) {
      Connection connection = database.writerConnection();
      throughV9().migrate(database);
      exec(
          connection,
          "INSERT INTO graph_sources (id, kind, state, configuration_match)"
              + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT')");
      exec(
          connection,
          "INSERT INTO declared_actions (source_id, graph_id, node_index)"
              + " VALUES (1, 1, 0), (1, 2, 1), (1, 3, NULL), (1, 4, NULL)");

      assertThat(MigrationRunner.standard().migrate(database)).isEqualTo(10);
      assertThat(indexes(connection)).contains("ix_declared_actions_node_index");
      assertThat(
              plan(
                  connection,
                  "SELECT node_index FROM declared_actions"
                      + " WHERE node_index IS NOT NULL ORDER BY node_index"))
          .contains("ix_declared_actions_node_index")
          .doesNotContain("TEMP B-TREE");
      assertThatThrownBy(
              () ->
                  exec(
                      connection,
                      "INSERT INTO declared_actions (source_id, graph_id, node_index)"
                          + " VALUES (1, 5, 1)"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  @DisplayName("a legacy duplicate node index refuses migration atomically")
  void duplicateLegacyIndexIsRefused() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("duplicate.db"))) {
      Connection connection = database.writerConnection();
      throughV9().migrate(database);
      exec(
          connection,
          "INSERT INTO graph_sources (id, kind, state, configuration_match)"
              + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT')");
      exec(
          connection,
          "INSERT INTO declared_actions (source_id, graph_id, node_index)"
              + " VALUES (1, 1, 0), (1, 2, 0)");

      assertThatThrownBy(() -> MigrationRunner.standard().migrate(database))
          .isInstanceOf(SQLException.class);
      assertThat(MigrationRunner.currentVersion(connection)).isEqualTo(9);
      assertThat(indexes(connection)).doesNotContain("ix_declared_actions_node_index");
    }
  }

  private static MigrationRunner throughV9() {
    return new MigrationRunner(
        List.of(
            new V1Migration(),
            new V2Migration(),
            new V3Migration(),
            new V4Migration(),
            new V5Migration(),
            new V6Migration(),
            new V7Migration(),
            new V8Migration(),
            new V9Migration()));
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String indexes(Connection connection) throws SQLException {
    StringBuilder out = new StringBuilder();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
      while (rows.next()) {
        out.append(rows.getString(1)).append('\n');
      }
    }
    return out.toString();
  }

  private static String plan(Connection connection, String sql) throws SQLException {
    StringBuilder out = new StringBuilder();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
      while (rows.next()) {
        out.append(rows.getString("detail")).append('\n');
      }
    }
    return out.toString();
  }
}
