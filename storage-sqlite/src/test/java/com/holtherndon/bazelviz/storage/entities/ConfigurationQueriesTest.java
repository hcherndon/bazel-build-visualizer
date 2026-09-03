package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigurationQueriesTest {

  @TempDir Path tempDir;
  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void createSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    exec("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'CLOSED')");
    exec(
        "INSERT INTO configurations"
            + " (id, stream_id, bep_id, declared, mnemonic, platform_name, cpu, is_tool)"
            + " VALUES (1, 1, 'aaa', 1, 'darwin_arm64-fastbuild', '//platform:mac',"
            + " 'darwin_arm64', 0)");
    exec(
        "INSERT INTO graph_sources"
            + " (id, kind, state, configuration_match)"
            + " VALUES (1, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT')");
    exec(
        "INSERT INTO queried_configurations"
            + " (id, source_id, graph_id, checksum, mnemonic, platform_name, is_tool,"
            + " options_available) VALUES"
            + " (1, 1, 1, 'aaa', 'darwin_arm64-fastbuild', '//platform:mac', 0, 1),"
            + " (2, 1, 2, 'bbb', 'darwin_arm64-fastbuild', '//platform:mac', 0, 1)");
    exec(
        "INSERT INTO queried_configuration_fragments"
            + " (configuration_id, fragment_name, option_set_name, ordinal) VALUES"
            + " (1, 'Core', 'CoreOptions', 0), (2, 'Core', 'CoreOptions', 0)");
    exec(
        "INSERT INTO queried_configuration_options"
            + " (configuration_id, option_set_name, option_name, option_value,"
            + " redacted, ordinal) VALUES"
            + " (1, 'CoreOptions', 'compilation_mode', 'fastbuild', 0, 0),"
            + " (1, 'CoreOptions', 'define', 'A=1', 0, 1),"
            + " (2, 'CoreOptions', 'compilation_mode', 'opt', 0, 0),"
            + " (2, 'CoreOptions', 'remote_header', NULL, 1, 1)");
  }

  @Test
  @DisplayName("summaries join BEP identity to cquery option metadata without dropping either")
  void summariesJoinSources() throws Exception {
    try {
      ConfigurationQueries queries = new ConfigurationQueries(connection);

      assertThat(queries.count()).isEqualTo(2);
      assertThat(queries.page(0, 10))
          .extracting(ConfigurationQueries.Summary::checksum)
          .containsExactly("aaa", "bbb");
      assertThat(queries.position("aaa")).hasValue(0);
      assertThat(queries.position("bbb")).hasValue(1);
      assertThat(queries.position("missing")).isEmpty();
      ConfigurationQueries.Summary first = queries.summary("aaa").orElseThrow();
      assertThat(first.bepReported()).isTrue();
      assertThat(first.bepDeclared()).isTrue();
      assertThat(first.queryReported()).isTrue();
      assertThat(first.optionsAvailable()).isTrue();
      assertThat(first.options()).isEqualTo(2);

      ConfigurationQueries.Summary queryOnly = queries.summary("bbb").orElseThrow();
      assertThat(queryOnly.bepReported()).isFalse();
      assertThat(queryOnly.bepDeclared()).isFalse();
      assertThat(queryOnly.queryReported()).isTrue();
    } finally {
      database.close();
    }
  }

  @Test
  @DisplayName("comparison distinguishes changed, one-sided and withheld option values")
  void differencesAreExplicit() throws Exception {
    try {
      ConfigurationQueries queries = new ConfigurationQueries(connection);
      assertThat(queries.differenceCount("aaa", "bbb")).isEqualTo(3);

      var differences = queries.differences("aaa", "bbb", 0, 10);
      assertThat(differences)
          .extracting(ConfigurationQueries.Difference::change)
          .containsExactlyInAnyOrder("Changed", "Only baseline", "Unknown (withheld)");
      assertThat(differences)
          .filteredOn(value -> value.name().equals("remote_header"))
          .singleElement()
          .satisfies(
              value -> {
                assertThat(value.candidateWithheld()).isTrue();
                assertThat(value.baselinePresent()).isFalse();
              });
    } finally {
      database.close();
    }
  }

  @Test
  @DisplayName("detail values are exact and retain withheld presence")
  void valuesRetainUnknowns() throws Exception {
    try {
      ConfigurationQueries queries = new ConfigurationQueries(connection);
      assertThat(queries.valueCount("bbb")).isEqualTo(2);
      assertThat(queries.values("bbb", 0, 10))
          .filteredOn(value -> value.name().equals("remote_header"))
          .singleElement()
          .satisfies(
              value -> {
                assertThat(value.withheld()).isTrue();
                assertThat(value.value()).isEmpty();
              });
    } finally {
      database.close();
    }
  }

  private void exec(String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
