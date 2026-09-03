package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TargetQueriesTest {

  @TempDir Path temporary;

  private SessionDatabase database;
  private Connection connection;
  private long streamId;
  private long sequence;

  @BeforeEach
  void setUp() throws Exception {
    database = SessionDatabase.open(temporary.resolve("targets.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    execute("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'OPEN')");
    streamId = scalar("SELECT last_insert_rowid()");

    try (EntityWriter writer = new EntityWriter(connection)) {
      configure(writer, "//b:z");
      configure(writer, "//a:b");
      configure(writer, "//a:a");
      complete(writer, "//a:a", "cfg-b");
      complete(writer, "//a:a", "cfg-a");
      writer.flush();
    }
    execute(
        "INSERT INTO graph_sources (id, kind, state, configuration_match)"
            + " VALUES (1, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT')");
    configured("//a:a", "cfg-a", "java_library");
    configured("//a:a", "cfg-b", "java_library");
    configured("//a:b", "cfg-a", "java_library");
    configured("//b:z", "cfg-a", "java_library");
    configured("//dep:transitive", "cfg-a", "java_library");
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("distinct labels page once and report their configuration counts")
  void labelPagesGroupConfigurations() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      assertThat(queries.labelCount()).isEqualTo(4);

      List<TargetQueries.LabelSummary> first = queries.firstLabelPage(2);
      List<TargetQueries.LabelSummary> rest = queries.labelPageAfter(first.getLast().label(), 2);

      assertThat(first)
          .containsExactly(
              new TargetQueries.LabelSummary("//a:a", 2, 2),
              new TargetQueries.LabelSummary("//a:b", 1, 1));
      assertThat(rest)
          .containsExactly(
              new TargetQueries.LabelSummary("//b:z", 1, 1),
              new TargetQueries.LabelSummary("//dep:transitive", 1, 1));
      assertThat(queries.configuredByLabel("//a:a"))
          .extracting(row -> row.configuration().orElseThrow())
          .containsExactly("cfg-a", "cfg-b");
      assertThat(queries.configuredSource())
          .get()
          .extracting(
              TargetQueries.ConfiguredSource::state,
              TargetQueries.ConfiguredSource::configurationMatch)
          .containsExactly("SUCCEEDED", "EXACT");
      assertThat(queries.byLabel("//dep:transitive")).isEmpty();
      assertThat(queries.configuredByLabel("//dep:transitive")).hasSize(1);
    }
  }

  @Test
  @DisplayName("top-level labels page independently from cquery-only dependencies")
  void topLevelLabelPagesStayInTheBepPopulation() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      assertThat(queries.topLevelLabelCount()).isEqualTo(3);
      List<String> first = queries.firstTopLevelLabelPage(2);
      assertThat(first).containsExactly("//a:a", "//a:b");
      assertThat(queries.topLevelLabelPageAfter(first.getLast(), 2)).containsExactly("//b:z");
      assertThat(queries.firstTopLevelLabelPage(10)).doesNotContain("//dep:transitive");
    }
  }

  @Test
  @DisplayName("label filters keep exact counts and keyset boundaries")
  void filteredLabelPagesStayExact() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      assertThat(queries.topLevelLabelCount("//a:")).isEqualTo(2);
      assertThat(queries.firstTopLevelLabelPage("//a:", 1)).containsExactly("//a:a");
      assertThat(queries.topLevelLabelPageAfter("//a:", "//a:a", 2)).containsExactly("//a:b");

      assertThat(queries.labelCount("//a:")).isEqualTo(2);
      assertThat(queries.firstLabelPage("//a:", 1))
          .containsExactly(new TargetQueries.LabelSummary("//a:a", 2, 2));
      assertThat(queries.labelPageAfter("//a:", "//a:a", 2))
          .containsExactly(new TargetQueries.LabelSummary("//a:b", 1, 1));

      assertThat(queries.packages(":a"))
          .singleElement()
          .satisfies(summary -> assertThat(summary.path()).isEqualTo("//a"));
      assertThat(queries.inPackage("//a", ":a")).extracting(TargetRow::label).containsOnly("//a:a");
    }
  }

  @Test
  @DisplayName("percent and underscore in label filters are literal")
  void filterWildcardsAreEscaped() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      assertThat(queries.topLevelLabelCount("_")).isZero();
      assertThat(queries.firstTopLevelLabelPage("%", 10)).isEmpty();
      assertThat(queries.labelCount("_")).isZero();
      assertThat(queries.firstLabelPage("%", 10)).isEmpty();
      assertThat(queries.packages("_")).isEmpty();
    }
  }

  private void configured(String label, String configuration, String ruleClass) throws Exception {
    try (var labelStatement =
        connection.prepareStatement(
            "INSERT INTO labels (value) VALUES (?) ON CONFLICT DO NOTHING")) {
      labelStatement.setString(1, label);
      labelStatement.executeUpdate();
    }
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO configured_target_nodes"
                + " (source_id, label_id, configuration_checksum, rule_class)"
                + " VALUES (1, (SELECT id FROM labels WHERE value = ?), ?, ?)")) {
      statement.setString(1, label);
      statement.setString(2, configuration);
      statement.setString(3, ruleClass);
      statement.executeUpdate();
    }
  }

  private void configure(EntityWriter writer, String label) throws Exception {
    writer.apply(
        streamId,
        event(),
        new EntityCommand.TargetConfigured(
            label,
            Optional.empty(),
            Optional.of("java_library rule"),
            Optional.empty(),
            List.of()));
  }

  private void complete(EntityWriter writer, String label, String configuration) throws Exception {
    writer.apply(
        streamId,
        event(),
        new EntityCommand.TargetCompleted(
            label,
            Optional.empty(),
            configuration,
            true,
            List.of(),
            OptionalLong.empty(),
            List.of(),
            List.of(),
            Optional.empty()));
  }

  private long event() throws Exception {
    long next = ++sequence;
    execute(
        "INSERT INTO bep_events (stream_id, sequence, event_type, raw_segment, raw_offset,"
            + " raw_length, decode_status, receive_micros) VALUES ("
            + streamId
            + ", "
            + next
            + ", 1, 0, 0, 0, 'OK', 0)");
    return next;
  }

  private void execute(String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long scalar(String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1;
    }
  }
}
