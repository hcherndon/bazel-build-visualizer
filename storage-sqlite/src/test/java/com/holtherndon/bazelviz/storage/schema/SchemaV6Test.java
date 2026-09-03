package com.holtherndon.bazelviz.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The provenance and unknown-value decisions added with schema v6. */
final class SchemaV6Test {

  @TempDir Path tempDir;

  @Test
  @DisplayName("queried configurations retain cquery provenance and option availability")
  void configurationsAreSourceSpecific() throws Exception {
    try (SessionDatabase database = migrated("configurations.db")) {
      Connection connection = database.writerConnection();
      exec(
          connection,
          "INSERT INTO graph_sources"
              + " (id, kind, state, configuration_match)"
              + " VALUES (1, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT')");
      exec(
          connection,
          "INSERT INTO queried_configurations"
              + " (source_id, graph_id, checksum, is_tool, options_available)"
              + " VALUES (1, 7, 'abc', 0, 0)");

      assertThat(
              text(connection, "SELECT checksum FROM queried_configurations WHERE source_id = 1"))
          .isEqualTo("abc");
      assertThat(number(connection, "SELECT options_available FROM queried_configurations"))
          .isZero();
    }
  }

  @Test
  @DisplayName("a withheld option is distinct from an option whose value is empty")
  void optionRedactionHasPresence() throws Exception {
    try (SessionDatabase database = migrated("redaction.db")) {
      Connection connection = database.writerConnection();
      exec(
          connection,
          "INSERT INTO graph_sources"
              + " (id, kind, state, configuration_match)"
              + " VALUES (1, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT')");
      exec(
          connection,
          "INSERT INTO queried_configurations"
              + " (id, source_id, graph_id, checksum, is_tool, options_available)"
              + " VALUES (1, 1, 7, 'abc', 0, 1)");
      exec(
          connection,
          "INSERT INTO queried_configuration_options"
              + " (configuration_id, option_set_name, option_name, option_value,"
              + " redacted, ordinal) VALUES"
              + " (1, 'core', 'remote_header', NULL, 1, 0),"
              + " (1, 'core', 'define', '', 0, 1)");

      assertThat(
              number(
                  connection,
                  "SELECT redacted"
                      + " FROM queried_configuration_options WHERE option_name='remote_header'"))
          .isEqualTo(1);
      assertThat(
              text(
                  connection,
                  "SELECT option_value"
                      + " FROM queried_configuration_options WHERE option_name='define'"))
          .isEmpty();
    }
  }

  private SessionDatabase migrated(String name) throws Exception {
    SessionDatabase database = SessionDatabase.open(tempDir.resolve(name));
    MigrationRunner.standard().migrate(database);
    return database;
  }

  private static void exec(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static long number(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1;
    }
  }

  private static String text(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }
}
