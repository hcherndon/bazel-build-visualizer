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

/** Scope provenance remains honest across migration and new captures. */
final class SchemaV8Test {

  @TempDir Path tempDir;

  @Test
  @DisplayName("migrating a v7 graph preserves it and leaves target scope unknown")
  void oldGraphIsPreservedWithoutInventingScope() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("old.db"))) {
      Connection connection = database.writerConnection();
      assertThat(throughV7().migrate(database)).isEqualTo(7);
      exec(
          connection,
          "INSERT INTO graph_sources"
              + " (id, kind, state, configuration_match) VALUES"
              + " (17, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT')");

      assertThat(MigrationRunner.standard().migrate(database))
          .isEqualTo(MigrationRunner.LATEST_VERSION);

      try (Statement statement = connection.createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT id, target_scope, target_scope_detail FROM graph_sources")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong("id")).isEqualTo(17);
        assertThat(row.getString("target_scope")).isEqualTo("UNKNOWN");
        assertThat(row.getString("target_scope_detail")).isNull();
      }
    }
  }

  @Test
  @DisplayName("scope accepts only the three recorded provenance states")
  void scopeStatesAreConstrained() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(tempDir.resolve("values.db"))) {
      Connection connection = database.writerConnection();
      MigrationRunner.standard().migrate(database);
      exec(
          connection,
          "INSERT INTO graph_sources"
              + " (kind, state, configuration_match, target_scope) VALUES"
              + " ('exact', 'SUCCEEDED', 'EXACT', 'EXACT_BEP_TARGETS'),"
              + " ('fallback', 'SUCCEEDED', 'EXACT', 'REQUESTED_PATTERNS'),"
              + " ('unknown', 'SUCCEEDED', 'EXACT', 'UNKNOWN')");

      assertThat(
              text(connection, "SELECT target_scope FROM graph_sources" + " WHERE kind = 'exact'"))
          .isEqualTo("EXACT_BEP_TARGETS");
      assertThatThrownBy(
              () ->
                  exec(
                      connection,
                      "INSERT INTO graph_sources"
                          + " (kind, state, configuration_match, target_scope) VALUES"
                          + " ('invalid', 'SUCCEEDED', 'EXACT', 'GUESSED')"))
          .isInstanceOf(SQLException.class);
    }
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

  private static String text(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }
}
