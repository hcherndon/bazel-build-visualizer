package com.holtherndon.bazelviz.enrich.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Both graph-query scopes come from what the build reported, not wider wildcard semantics. */
final class ConfiguredTargetQueryFileTest {

  @TempDir Path directory;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void openDatabase() throws Exception {
    database = SessionDatabase.open(directory.resolve("session.sqlite"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
  }

  @AfterEach
  void closeDatabase() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("exact BEP targets are quoted and streamed into the dependency closure")
  void writesExactReportedTargets() throws Exception {
    execute("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'CLOSED')");
    execute(
        "INSERT INTO build_invocation"
            + " (singleton, stream_id, overall_success, saw_last_message)"
            + " VALUES (1, 1, 1, 1)");
    execute(
        "INSERT INTO labels (id, value) VALUES"
            + " (1, '//pkg:regular'), (2, '//pkg:name+with+plus')");
    execute(
        "INSERT INTO targets (label_id, aspect, outcome) VALUES"
            + " (1, '', 'COMPLETED'), (2, '', 'COMPLETED')");
    execute("INSERT INTO configurations (id, stream_id, bep_id) VALUES (1, 1, 'cfg')");
    execute(
        "INSERT INTO configured_targets (target_id, configuration_id, outcome) VALUES"
            + " (1, 1, 'BUILT'), (2, 1, 'BUILT')");
    Path query = directory.resolve("raw/cquery.query");

    BepTargetQueryFile.Result result =
        new BepTargetQueryFile(connection).write(query, "deps(//...)");

    assertThat(result.topLevelLabels()).isEqualTo(2);
    assertThat(result.usedRequestedPatterns()).isFalse();
    assertThat(result.scope()).isEqualTo(GraphTargetScope.EXACT_BEP_TARGETS);
    assertThat(result.detail()).contains("2 distinct completed top-level target labels");
    assertThat(Files.readString(query))
        .isEqualTo("deps(set(\"//pkg:name+with+plus\" \"//pkg:regular\"))\n");
  }

  @Test
  @DisplayName("configured-only incompatible labels are not replayed as explicit query targets")
  void excludesConfiguredOnlyTargets() throws Exception {
    execute("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'CLOSED')");
    execute(
        "INSERT INTO build_invocation"
            + " (singleton, stream_id, overall_success, saw_last_message)"
            + " VALUES (1, 1, 1, 1)");
    execute(
        "INSERT INTO labels (id, value) VALUES" + " (1, '//pkg:built'), (2, '//pkg:windows-only')");
    execute(
        "INSERT INTO targets (id, label_id, aspect, outcome) VALUES"
            + " (1, 1, '', 'CONFIGURED'), (2, 2, '', 'CONFIGURED')");
    execute("INSERT INTO configurations (id, stream_id, bep_id) VALUES (1, 1, 'cfg')");
    execute(
        "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
            + " VALUES (1, 1, 'BUILT'), (2, 1, 'CONFIGURED')");
    Path query = directory.resolve("raw/aquery.query");

    BepTargetQueryFile.Result result =
        new BepTargetQueryFile(connection).write(query, "deps(//pkg/...)");

    assertThat(result.topLevelLabels()).isEqualTo(1);
    assertThat(result.scope()).isEqualTo(GraphTargetScope.EXACT_BEP_TARGETS);
    assertThat(result.detail())
        .contains("successful invocation")
        .contains("1 configured or aborted label")
        .contains("not replayed as explicit query targets");
    assertThat(Files.readString(query)).isEqualTo("deps(set(\"//pkg:built\"))\n");
  }

  @Test
  @DisplayName("a failed build's completed subset is not exact when an aborted label was omitted")
  void failedBuildWithOmittedAbortedLabelIsUntrusted() throws Exception {
    execute("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'CLOSED')");
    execute(
        "INSERT INTO build_invocation"
            + " (singleton, stream_id, overall_success, saw_last_message)"
            + " VALUES (1, 1, 0, 1)");
    execute("INSERT INTO labels (id, value) VALUES" + " (1, '//pkg:built'), (2, '//pkg:aborted')");
    execute(
        "INSERT INTO targets (id, label_id, aspect, outcome) VALUES"
            + " (1, 1, '', 'CONFIGURED'), (2, 2, '', 'ABORTED')");
    execute("INSERT INTO configurations (id, stream_id, bep_id) VALUES (1, 1, 'cfg')");
    execute(
        "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
            + " VALUES (1, 1, 'BUILT'), (2, 1, 'ABORTED')");
    Path query = directory.resolve("raw/aquery.query");

    BepTargetQueryFile.Result result =
        new BepTargetQueryFile(connection).write(query, "deps(//pkg/...)");

    assertThat(result.topLevelLabels()).isEqualTo(1);
    assertThat(result.scope()).isEqualTo(GraphTargetScope.UNKNOWN);
    assertThat(result.detail())
        .contains("omitted 1 configured or aborted label")
        .contains("invocation failed")
        .contains("cannot be confirmed");
    assertThat(Files.readString(query)).isEqualTo("deps(set(\"//pkg:built\"))\n");
  }

  @Test
  @DisplayName("a build with no reported target keeps the requested-pattern fallback explicit")
  void fallsBackWhenNoTargetWasReported() throws Exception {
    Path query = directory.resolve("raw/cquery.query");

    BepTargetQueryFile.Result result =
        new BepTargetQueryFile(connection).write(query, "deps(//pkg/... except //pkg:manual)");

    assertThat(result.usedRequestedPatterns()).isTrue();
    assertThat(result.scope()).isEqualTo(GraphTargetScope.REQUESTED_PATTERNS);
    assertThat(result.detail()).contains("may be wider");
    assertThat(Files.readString(query)).isEqualTo("deps(//pkg/... except //pkg:manual)\n");
  }

  @Test
  @DisplayName("a nonempty label set from an incomplete BEP is not called exact")
  void incompleteBepTargetSetIsUntrusted() throws Exception {
    execute("INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'OPEN')");
    execute(
        "INSERT INTO build_invocation (singleton, stream_id, saw_last_message)"
            + " VALUES (1, 1, 0)");
    execute("INSERT INTO labels (id, value) VALUES (1, '//pkg:seen-before-truncation')");
    execute("INSERT INTO targets (label_id, aspect, outcome)" + " VALUES (1, '', 'COMPLETED')");
    execute("INSERT INTO configurations (id, stream_id, bep_id) VALUES (1, 1, 'cfg')");
    execute(
        "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
            + " VALUES (1, 1, 'BUILT')");
    Path query = directory.resolve("raw/aquery.query");

    BepTargetQueryFile.Result result =
        new BepTargetQueryFile(connection).write(query, "deps(//...)");

    assertThat(result.scope()).isEqualTo(GraphTargetScope.UNKNOWN);
    assertThat(result.detail()).contains("no final marker").contains("may be incomplete");
    assertThat(Files.readString(query)).isEqualTo("deps(set(\"//pkg:seen-before-truncation\"))\n");
  }

  private void execute(String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
