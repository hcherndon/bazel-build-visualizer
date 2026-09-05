package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.storage.CountedPage;
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
      CountedPage<TargetQueries.LabelSummary, String> first =
          queries.labelPage("", Optional.empty(), 2);
      CountedPage<TargetQueries.LabelSummary, String> rest =
          queries.labelPage("", first.nextAnchor(), 2);

      assertThat(first.totalRows()).isEqualTo(4);
      assertThat(first.remainingRows()).isEqualTo(2);
      assertThat(first.rows())
          .containsExactly(
              new TargetQueries.LabelSummary("//a:a", 2, 2),
              new TargetQueries.LabelSummary("//a:b", 1, 1));
      assertThat(rest.rows())
          .containsExactly(
              new TargetQueries.LabelSummary("//b:z", 1, 1),
              new TargetQueries.LabelSummary("//dep:transitive", 1, 1));
      assertThat(queries.configurationGroupPage("//a:a", Optional.empty(), 10).rows())
          .extracting(group -> group.configuration().orElseThrow())
          .containsExactly("cfg-a", "cfg-b");
      assertThat(queries.configuredSource())
          .get()
          .extracting(
              TargetQueries.ConfiguredSource::state,
              TargetQueries.ConfiguredSource::configurationMatch)
          .containsExactly("SUCCEEDED", "EXACT");
      assertThat(queries.targetsByLabelPage("//dep:transitive", Optional.empty(), 10).rows())
          .isEmpty();
      assertThat(
              queries.configurationGroupPage("//dep:transitive", Optional.empty(), 10).totalRows())
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("top-level labels page independently from cquery-only dependencies")
  void topLevelLabelPagesStayInTheBepPopulation() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      CountedPage<String, String> first = queries.topLevelLabelPage("", Optional.empty(), 2);
      assertThat(first.totalRows()).isEqualTo(3);
      assertThat(first.rows()).containsExactly("//a:a", "//a:b");
      assertThat(queries.topLevelLabelPage("", first.nextAnchor(), 2).rows())
          .containsExactly("//b:z");
      assertThat(queries.topLevelLabelPage("", Optional.empty(), 10).rows())
          .doesNotContain("//dep:transitive");
    }
  }

  @Test
  @DisplayName("label filters keep exact counts and keyset boundaries")
  void filteredLabelPagesStayExact() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      CountedPage<String, String> topLevel = queries.topLevelLabelPage("//a:", Optional.empty(), 1);
      assertThat(topLevel.totalRows()).isEqualTo(2);
      assertThat(topLevel.rows()).containsExactly("//a:a");
      assertThat(queries.topLevelLabelPage("//a:", topLevel.nextAnchor(), 2).rows())
          .containsExactly("//a:b");

      CountedPage<TargetQueries.LabelSummary, String> labels =
          queries.labelPage("//a:", Optional.empty(), 1);
      assertThat(labels.totalRows()).isEqualTo(2);
      assertThat(labels.rows()).containsExactly(new TargetQueries.LabelSummary("//a:a", 2, 2));
      assertThat(queries.labelPage("//a:", labels.nextAnchor(), 2).rows())
          .containsExactly(new TargetQueries.LabelSummary("//a:b", 1, 1));

      assertThat(queries.packagePage(":a", Optional.empty(), 10).rows())
          .singleElement()
          .satisfies(summary -> assertThat(summary.path()).isEqualTo("//a"));
      assertThat(queries.targetsInPackagePage("//a", ":a", Optional.empty(), 10).rows())
          .extracting(TargetRow::label)
          .containsOnly("//a:a");
    }
  }

  @Test
  @DisplayName("percent and underscore in label filters are literal")
  void filterWildcardsAreEscaped() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      assertThat(queries.topLevelLabelPage("_", Optional.empty(), 10).totalRows()).isZero();
      assertThat(queries.topLevelLabelPage("%", Optional.empty(), 10).rows()).isEmpty();
      assertThat(queries.labelPage("_", Optional.empty(), 10).totalRows()).isZero();
      assertThat(queries.labelPage("%", Optional.empty(), 10).rows()).isEmpty();
      assertThat(queries.packagePage("_", Optional.empty(), 10).rows()).isEmpty();
    }
  }

  @Test
  @DisplayName("packages and package children page without gaps and keep exact counts")
  void packagesAndChildrenUseCountedKeysets() throws Exception {
    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      CountedPage<TargetQueries.PackageSummary, String> firstPackage =
          queries.packagePage("", Optional.empty(), 1);
      assertThat(firstPackage.rows())
          .extracting(TargetQueries.PackageSummary::path)
          .containsExactly("//a");
      assertThat(firstPackage.totalRows()).isEqualTo(2);
      assertThat(firstPackage.shownThrough()).isEqualTo(1);
      assertThat(firstPackage.remainingRows()).isEqualTo(1);

      CountedPage<TargetQueries.PackageSummary, String> secondPackage =
          queries.packagePage("", firstPackage.nextAnchor(), 1);
      assertThat(secondPackage.rows())
          .extracting(TargetQueries.PackageSummary::path)
          .containsExactly("//b");
      assertThat(secondPackage.totalRows()).isEqualTo(2);
      assertThat(secondPackage.remainingRows()).isZero();
      assertThat(secondPackage.nextAnchor()).isEmpty();

      CountedPage<TargetRow, TargetQueries.TargetAnchor> firstTargets =
          queries.targetsInPackagePage("//a", "", Optional.empty(), 2);
      assertThat(firstTargets.rows())
          .extracting(row -> row.label() + "@" + row.configurationId().orElse("none"))
          .containsExactly("//a:a@cfg-a", "//a:a@cfg-b");
      assertThat(firstTargets.totalRows()).isEqualTo(3);
      assertThat(firstTargets.remainingRows()).isEqualTo(1);

      CountedPage<TargetRow, TargetQueries.TargetAnchor> secondTargets =
          queries.targetsInPackagePage("//a", "", firstTargets.nextAnchor(), 2);
      assertThat(secondTargets.rows()).extracting(TargetRow::label).containsExactly("//a:b");
      assertThat(secondTargets.totalRows()).isEqualTo(3);
      assertThat(secondTargets.remainingRows()).isZero();

      CountedPage<TargetRow, TargetQueries.TargetAnchor> exactLabel =
          queries.targetsByLabelPage("//a:a", Optional.empty(), 1);
      assertThat(exactLabel.rows()).hasSize(1);
      assertThat(exactLabel.totalRows()).isEqualTo(2);
      assertThat(exactLabel.remainingRows()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("configuration pages keep null and empty checksums distinct")
  void configurationGroupsPreserveNullableIdentity() throws Exception {
    configured("//mixed:x", null, "null-one");
    configured("//mixed:x", null, "null-two");
    configured("//mixed:x", "", "empty");
    configured("//mixed:x", "cfg", "known");

    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      TargetQueries.LabelSummary summary =
          queries.labelPage("//mixed:x", Optional.empty(), 10).rows().getFirst();
      assertThat(summary.configurations()).isEqualTo(3);
      assertThat(summary.rows()).isEqualTo(4);

      CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor> first =
          queries.configurationGroupPage("//mixed:x", Optional.empty(), 1);
      assertThat(first.totalRows()).isEqualTo(3);
      assertThat(first.remainingRows()).isEqualTo(2);
      assertThat(first.rows())
          .containsExactly(new TargetQueries.ConfigurationGroup(Optional.empty(), 2));

      CountedPage<TargetQueries.ConfigurationGroup, TargetQueries.ConfigurationAnchor> rest =
          queries.configurationGroupPage("//mixed:x", first.nextAnchor(), 2);
      assertThat(rest.rows())
          .extracting(TargetQueries.ConfigurationGroup::configuration)
          .containsExactly(Optional.of(""), Optional.of("cfg"));
      assertThat(rest.remainingRows()).isZero();

      CountedPage<TargetQueries.ConfiguredTarget, Long> nullVariants =
          queries.configuredTargetPage("//mixed:x", Optional.empty(), OptionalLong.empty(), 1);
      assertThat(nullVariants.totalRows()).isEqualTo(2);
      assertThat(nullVariants.remainingRows()).isEqualTo(1);
      CountedPage<TargetQueries.ConfiguredTarget, Long> lastNullVariant =
          queries.configuredTargetPage(
              "//mixed:x",
              Optional.empty(),
              OptionalLong.of(nullVariants.nextAnchor().orElseThrow()),
              1);
      assertThat(lastNullVariant.rows()).hasSize(1);
      assertThat(lastNullVariant.remainingRows()).isZero();
    }
  }

  @Test
  @DisplayName("tags and output groups expose stable counted detail pages")
  void targetDetailsAreCountedAndPaged() throws Exception {
    long target =
        scalar(
            "SELECT t.id FROM targets t JOIN labels l ON l.id = t.label_id"
                + " WHERE l.value = '//a:a'");
    long configuredTarget =
        scalar("SELECT MIN(id) FROM configured_targets WHERE target_id = " + target);
    execute(
        "INSERT INTO target_tags (target_id, tag, from_event) VALUES"
            + " ("
            + target
            + ", 'manual', 'configured'),"
            + " ("
            + target
            + ", 'small', 'completed')");
    execute(
        "INSERT INTO target_output_groups"
            + " (configured_target_id, name, incomplete, ordinal) VALUES"
            + " ("
            + configuredTarget
            + ", 'default', 0, 2),"
            + " ("
            + configuredTarget
            + ", 'files', 1, 7)");

    try (TargetQueries queries = new TargetQueries(database.newReadConnection())) {
      CountedPage<TargetQueries.Tag, TargetQueries.TagAnchor> tags =
          queries.tagPage(target, Optional.empty(), 1);
      assertThat(tags.rows()).containsExactly(new TargetQueries.Tag("small", "completed"));
      assertThat(tags.totalRows()).isEqualTo(2);
      assertThat(tags.remainingRows()).isEqualTo(1);
      assertThat(queries.tagPage(target, tags.nextAnchor(), 1).rows())
          .containsExactly(new TargetQueries.Tag("manual", "configured"));

      CountedPage<TargetQueries.OutputGroup, Long> groups =
          queries.outputGroupPage(configuredTarget, OptionalLong.empty(), 1);
      assertThat(groups.rows())
          .extracting(TargetQueries.OutputGroup::name, TargetQueries.OutputGroup::ordinal)
          .containsExactly(tuple("default", 2L));
      assertThat(groups.totalRows()).isEqualTo(2);
      assertThat(groups.remainingRows()).isEqualTo(1);
      assertThat(
              queries
                  .outputGroupPage(
                      configuredTarget, OptionalLong.of(groups.nextAnchor().orElseThrow()), 1)
                  .rows())
          .extracting(TargetQueries.OutputGroup::name)
          .containsExactly("files");
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
