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

/** The unknown-value and preservation decisions added with schema v7. */
final class SchemaV7Test {

  @TempDir Path tempDir;

  @Test
  @DisplayName("migrating a v6 graph preserves it and leaves completeness unknown")
  void oldGraphIsPreservedWithoutInventingCompleteness() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("old.db"))) {
      Connection connection = database.writerConnection();
      assertThat(throughV6().migrate(database)).isEqualTo(6);
      exec(
          connection,
          "INSERT INTO graph_sources"
              + " (id, kind, command, state, configuration_match, mismatch_detail,"
              + " declared_actions, correlated_actions) VALUES"
              + " (17, 'DECLARED_ACTIONS', 'bazel aquery //...', 'SUCCEEDED', 'EXACT',"
              + " 'kept detail', 23, 5)");

      assertThat(throughV7().migrate(database)).isEqualTo(7);

      try (Statement statement = connection.createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT id, command, state, configuration_match, mismatch_detail,"
                      + " declared_actions, correlated_actions,"
                      + " unresolved_artifacts, unresolved_depset_references"
                      + " FROM graph_sources")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong("id")).isEqualTo(17);
        assertThat(row.getString("command")).isEqualTo("bazel aquery //...");
        assertThat(row.getString("state")).isEqualTo("SUCCEEDED");
        assertThat(row.getString("configuration_match")).isEqualTo("EXACT");
        assertThat(row.getString("mismatch_detail")).isEqualTo("kept detail");
        assertThat(row.getLong("declared_actions")).isEqualTo(23);
        assertThat(row.getLong("correlated_actions")).isEqualTo(5);
        row.getLong("unresolved_artifacts");
        assertThat(row.wasNull()).isTrue();
        row.getLong("unresolved_depset_references");
        assertThat(row.wasNull()).isTrue();
      }
    }
  }

  @Test
  @DisplayName("completeness distinguishes resolved, unresolved, and unknown artifacts")
  void completenessIsExactAndNonNegative() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("values.db"))) {
      Connection connection = database.writerConnection();
      MigrationRunner.standard().migrate(database);
      exec(
          connection,
          "INSERT INTO graph_sources"
              + " (kind, state, configuration_match, unresolved_artifacts,"
              + " unresolved_depset_references) VALUES"
              + " ('resolved', 'SUCCEEDED', 'EXACT', 0, 0),"
              + " ('unresolved', 'SUCCEEDED', 'EXACT', 3, 2),"
              + " ('unknown', 'SUCCEEDED', 'EXACT', NULL, NULL)");

      assertThat(
              number(
                  connection,
                  "SELECT unresolved_artifacts FROM graph_sources" + " WHERE kind = 'resolved'"))
          .isZero();
      assertThat(
              number(
                  connection,
                  "SELECT unresolved_artifacts FROM graph_sources" + " WHERE kind = 'unresolved'"))
          .isEqualTo(3);
      assertThat(
              number(
                  connection,
                  "SELECT unresolved_depset_references"
                      + " FROM graph_sources WHERE kind = 'unresolved'"))
          .isEqualTo(2);
      assertThat(
              isNull(
                  connection,
                  "SELECT unresolved_artifacts FROM graph_sources" + " WHERE kind = 'unknown'"))
          .isTrue();
      assertThatThrownBy(
              () ->
                  exec(
                      connection,
                      "INSERT INTO graph_sources"
                          + " (kind, state, configuration_match, unresolved_artifacts)"
                          + " VALUES ('invalid', 'SUCCEEDED', 'EXACT', -1)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(
              () ->
                  exec(
                      connection,
                      "INSERT INTO graph_sources"
                          + " (kind, state, configuration_match, unresolved_depset_references)"
                          + " VALUES ('invalid-depset', 'SUCCEEDED', 'EXACT', -1)"))
          .isInstanceOf(SQLException.class);
    }
  }

  private static MigrationRunner throughV6() {
    return new MigrationRunner(
        List.of(
            new V1Migration(),
            new V2Migration(),
            new V3Migration(),
            new V4Migration(),
            new V5Migration(),
            new V6Migration()));
  }

  private static MigrationRunner throughV7() {
    return new MigrationRunner(
        List.of(
            new V1Migration(),
            new V2Migration(),
            new V3Migration(),
            new V4Migration(),
            new V5Migration(),
            new V6Migration(),
            new V7Migration()));
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long number(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private static boolean isNull(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      rows.getObject(1);
      return rows.wasNull();
    }
  }
}
