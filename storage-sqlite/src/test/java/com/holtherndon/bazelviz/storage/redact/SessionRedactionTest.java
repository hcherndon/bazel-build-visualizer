package com.holtherndon.bazelviz.storage.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sensitive-field inventory, made executable.
 *
 * <p>The test that matters here is the last one: every {@code TEXT} column the schema declares must
 * appear either in the redaction list or in the list of columns deliberately left alone. A document
 * describing which columns are sensitive is checkable by reading it; this is checkable by running
 * it, and a column added by a future migration fails until somebody decides which it is.
 */
final class SessionRedactionTest {

  @TempDir Path tempDir;

  private SessionDatabase open(String name) throws Exception {
    SessionDatabase database = SessionDatabase.open(tempDir.resolve(name));
    MigrationRunner.standard().migrate(database);
    return database;
  }

  private static void exec(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Test
  @DisplayName("a redacted copy loses the secrets and keeps the shape")
  void copyIsRedacted() throws Exception {
    Path original = tempDir.resolve("session.sqlite");
    try (SessionDatabase database = open("session.sqlite")) {
      Connection writer = database.writerConnection();
      exec(
          writer,
          "INSERT INTO event_streams (id, stream_key, state)" + " VALUES (1, 's', 'CLOSED')");
      exec(
          writer,
          "INSERT INTO build_invocation (singleton, stream_id, command,"
              + " working_directory, workspace_directory, options_description,"
              + " saw_last_message) VALUES (1, 1, 'build', '/Users/someone/code/p',"
              + " '/Users/someone/code/p', '--remote_header=Bearer ghp_supersecret1', 1)");
      exec(
          writer,
          "INSERT INTO enrichment_tasks (id, kind, state)" + " VALUES (1, 'EXEC_LOG', 'DONE')");
      exec(
          writer,
          "INSERT INTO action_attempts (id, task_id, log_entry_index, correlation)"
              + " VALUES (1, 1, 0, 'MATCHED_BY_OUTPUT')");
      exec(
          writer,
          "INSERT INTO attempt_env_vars (attempt_id, name, value)"
              + " VALUES (1, 'GITHUB_TOKEN', 'ghp_anothersecret'),"
              + " (1, 'PATH', '/usr/bin')");
      exec(
          writer,
          "INSERT INTO strings (id, value)"
              + " VALUES (1, 'compiling /Users/someone/code/p/a.cc')");
    }

    Path redacted = tempDir.resolve("redacted.sqlite");
    SessionRedaction.Result result =
        SessionRedaction.copyRedacted(
            original,
            redacted,
            RedactionPolicy.forExport().withPathPrefix("/Users/someone/code/p", "[workspace]"));

    List<String> values = new ArrayList<>();
    try (SessionDatabase copy = SessionDatabase.open(redacted);
        Statement statement = copy.writerConnection().createStatement()) {
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT options_description, working_directory FROM build_invocation")) {
        rows.next();
        values.add(rows.getString(1));
        values.add(rows.getString(2));
      }
      try (ResultSet rows =
          statement.executeQuery("SELECT name, value FROM attempt_env_vars ORDER BY name")) {
        while (rows.next()) {
          values.add(rows.getString(1) + "=" + rows.getString(2));
        }
      }
      try (ResultSet rows = statement.executeQuery("SELECT value FROM strings")) {
        rows.next();
        values.add(rows.getString(1));
      }
    }

    assertThat(values.get(0)).doesNotContain("ghp_supersecret1").contains("--remote_header=");
    assertThat(values.get(1)).isEqualTo("[workspace]");
    assertThat(values.get(2)).startsWith("GITHUB_TOKEN=[redacted:");
    assertThat(values.get(3)).isEqualTo("PATH=/usr/bin");
    // The dictionary is where progress output and command text live, so a
    // path inside it is redacted like any other.
    assertThat(values.get(4)).isEqualTo("compiling [workspace]/a.cc");
    assertThat(result.report().redactions()).isGreaterThan(0);
    assertThat(result.report().byField())
        .containsKeys(
            "build_invocation.options_description", "attempt_env_vars.value", "strings.value");
  }

  @Test
  @DisplayName("the original database is untouched")
  void theOriginalSurvives() throws Exception {
    Path original = tempDir.resolve("session.sqlite");
    try (SessionDatabase database = open("session.sqlite")) {
      exec(
          database.writerConnection(),
          "INSERT INTO strings (id, value) VALUES (1, '/Users/someone/x')");
    }

    SessionRedaction.copyRedacted(
        original, tempDir.resolve("copy.sqlite"), RedactionPolicy.forExport());

    try (SessionDatabase database = SessionDatabase.open(original);
        Statement statement = database.writerConnection().createStatement();
        ResultSet rows = statement.executeQuery("SELECT value FROM strings")) {
      rows.next();
      // ADR-004: the session on disk is the user's own record of their own
      // build and stays exactly as captured.
      assertThat(rows.getString(1)).isEqualTo("/Users/someone/x");
    }
  }

  @Test
  @DisplayName("a linked source is rejected without creating a redacted destination")
  void linkedSourcesAreRejected() throws Exception {
    Path original = tempDir.resolve("outside.sqlite");
    try (SessionDatabase ignored = open("outside.sqlite")) {
      // A valid SQLite source exists outside the path supplied to the redactor.
    }
    Path linked = tempDir.resolve("linked.sqlite");
    Files.createSymbolicLink(linked, original.getFileName());
    Path target = tempDir.resolve("should-not-exist.sqlite");

    assertThatThrownBy(
            () -> SessionRedaction.copyRedacted(linked, target, RedactionPolicy.forExport()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no-follow");
    assertThat(target).doesNotExist();
  }

  @Test
  @DisplayName("post-copy policy failures clean the owner-only database snapshot")
  void factoryFailuresCleanTheSnapshot() throws Exception {
    Path original = tempDir.resolve("source.sqlite");
    try (SessionDatabase ignored = open("source.sqlite")) {
      // Schema setup is enough for a valid source.
    }
    Path target = tempDir.resolve("failed.sqlite");

    assertThatThrownBy(
            () ->
                SessionRedaction.copyRedacted(
                    original,
                    target,
                    copied -> {
                      assertThat(copied).exists();
                      throw new IOException("policy failed");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("policy failed");
    assertThat(target).doesNotExist();
  }

  @Test
  @DisplayName("the supplied Redactor instance owns the database pseudonyms")
  void callerRedactorIsReused() throws Exception {
    Path original = tempDir.resolve("shared-source.sqlite");
    try (SessionDatabase database = open("shared-source.sqlite")) {
      exec(
          database.writerConnection(),
          "INSERT INTO strings (id, value) VALUES (1, 'Bearer shared-secret-value')");
    }
    Redactor shared = new Redactor(RedactionPolicy.forExport());

    SessionRedaction.copyRedacted(original, tempDir.resolve("shared-copy.sqlite"), shared);
    String elsewhere = shared.text("Bearer shared-secret-value", "manifest.warning");

    assertThat(elsewhere).startsWith("Bearer [redacted:");
    assertThat(shared.report().byField()).containsKeys("strings.value", "manifest.warning");
  }

  @Test
  @DisplayName("labels survive a redacted export unless the policy says otherwise")
  void labelsAreOptOut() throws Exception {
    Path original = tempDir.resolve("session.sqlite");
    try (SessionDatabase database = open("session.sqlite")) {
      exec(
          database.writerConnection(),
          "INSERT INTO labels (id, value) VALUES (1, '//src/main:lib')");
    }

    SessionRedaction.copyRedacted(
        original, tempDir.resolve("keep.sqlite"), RedactionPolicy.forExport());
    SessionRedaction.copyRedacted(
        original, tempDir.resolve("hide.sqlite"), RedactionPolicy.forExport().redactingLabels());

    assertThat(label(tempDir.resolve("keep.sqlite"))).isEqualTo("//src/main:lib");
    assertThat(label(tempDir.resolve("hide.sqlite"))).startsWith("[redacted:");
  }

  @Test
  @DisplayName("configuration option values use their Bazel flag name during export")
  void optionValuesAreRedactedByFlagName() throws Exception {
    Path original = tempDir.resolve("options.sqlite");
    try (SessionDatabase database = open("options.sqlite")) {
      Connection writer = database.writerConnection();
      exec(
          writer,
          "INSERT INTO graph_sources"
              + " (id, kind, state, configuration_match)"
              + " VALUES (1, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT')");
      exec(
          writer,
          "INSERT INTO queried_configurations"
              + " (id, source_id, graph_id, checksum, is_tool, options_available)"
              + " VALUES (1, 1, 1, 'abc', 0, 1)");
      exec(
          writer,
          "INSERT INTO queried_configuration_options"
              + " (configuration_id, option_set_name, option_name, option_value, ordinal)"
              + " VALUES (1, 'Remote', 'remote_header', 'Bearer secret-value', 0)");
    }

    Path copy = tempDir.resolve("options-redacted.sqlite");
    SessionRedaction.copyRedacted(original, copy, RedactionPolicy.forExport());

    try (SessionDatabase database = SessionDatabase.open(copy);
        Statement statement = database.writerConnection().createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT option_value FROM queried_configuration_options")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).doesNotContain("secret-value").startsWith("[redacted:");
    }
  }

  private static String label(Path database) throws Exception {
    try (SessionDatabase open = SessionDatabase.open(database);
        Statement statement = open.writerConnection().createStatement();
        ResultSet rows = statement.executeQuery("SELECT value FROM labels")) {
      rows.next();
      return rows.getString(1);
    }
  }

  @Test
  @DisplayName("every TEXT column in the schema has been decided about")
  void theInventoryCoversTheSchema() throws Exception {
    Set<String> handled = new LinkedHashSet<>();
    SessionRedaction.sensitiveColumns().forEach(column -> handled.add(column.field()));
    handled.addAll(SessionRedaction.deliberatelyNotSensitive());

    Set<String> undecided = new TreeSet<>();
    try (SessionDatabase database = open("schema.sqlite");
        Statement tables = database.writerConnection().createStatement();
        ResultSet tableRows =
            tables.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
                    + " AND name NOT LIKE 'sqlite_%'")) {
      List<String> names = new ArrayList<>();
      while (tableRows.next()) {
        names.add(tableRows.getString(1));
      }
      for (String table : names) {
        try (Statement columns = database.writerConnection().createStatement();
            ResultSet columnRows =
                columns.executeQuery("SELECT name, type FROM pragma_table_info('" + table + "')")) {
          while (columnRows.next()) {
            if (!"TEXT".equalsIgnoreCase(columnRows.getString(2))) {
              continue;
            }
            String field = table + "." + columnRows.getString(1);
            if (!handled.contains(field)) {
              undecided.add(field);
            }
          }
        }
      }
    }

    // A column in neither list is a column nobody decided about. Add it to
    // SessionRedaction.sensitiveColumns() with a treatment, or to
    // deliberatelyNotSensitive() with a reason, and say which in
    // docs/database-schema.md.
    assertThat(undecided).as("TEXT columns with no redaction decision").isEmpty();
  }

  @Test
  @DisplayName("every column named in either list still exists in the schema")
  void theInventoryHasNoStaleEntries() throws Exception {
    // The other direction, and it is not symmetric. Schema v3 renamed two
    // columns with ALTER TABLE RENAME COLUMN, which a check that reads only
    // CREATE TABLE statements cannot see -- so a list built by reading the
    // DDL can name a column that no longer exists and go on passing.
    Set<String> existing = new LinkedHashSet<>();
    try (SessionDatabase database = open("stale.sqlite");
        Statement tables = database.writerConnection().createStatement();
        ResultSet tableRows =
            tables.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
                    + " AND name NOT LIKE 'sqlite_%'")) {
      List<String> names = new ArrayList<>();
      while (tableRows.next()) {
        names.add(tableRows.getString(1));
      }
      for (String table : names) {
        try (Statement columns = database.writerConnection().createStatement();
            ResultSet columnRows =
                columns.executeQuery("SELECT name FROM pragma_table_info('" + table + "')")) {
          while (columnRows.next()) {
            existing.add(table + "." + columnRows.getString(1));
          }
        }
      }
    }

    Set<String> named = new TreeSet<>(SessionRedaction.deliberatelyNotSensitive());
    SessionRedaction.sensitiveColumns().forEach(column -> named.add(column.field()));
    named.removeAll(existing);
    assertThat(named).as("columns named in the inventory that the schema does not have").isEmpty();
  }

  @Test
  @DisplayName("no column is in both lists")
  void theListsDoNotOverlap() {
    Set<String> sensitive = new LinkedHashSet<>();
    SessionRedaction.sensitiveColumns().forEach(column -> sensitive.add(column.field()));

    assertThat(sensitive).doesNotContainAnyElementsOf(SessionRedaction.deliberatelyNotSensitive());
  }
}
