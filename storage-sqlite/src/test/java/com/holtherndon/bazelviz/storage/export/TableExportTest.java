package com.holtherndon.bazelviz.storage.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.core.text.Csv;
import com.holtherndon.bazelviz.core.text.Json;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The table exports: the shape of the file, and the redaction that must be in it. */
final class TableExportTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection writer;

  @BeforeEach
  void buildSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.sqlite"));
    MigrationRunner.standard().migrate(database);
    writer = database.writerConnection();
    exec("INSERT INTO mnemonics (id, value) VALUES (1, 'Javac')");
    exec("INSERT INTO labels (id, value) VALUES (1, '//src/main:lib')");
    exec(
        "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome,"
            + " failure_message) VALUES (1, '/Users/someone/code/p/out/a.jar', 1, 1,"
            + " 'FAILED', 'cc failed at /Users/someone/code/p/a.cc, header Bearer ghp_x1y2z3')");
    exec(
        "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome)"
            + " VALUES (2, 'out/b,c.jar', 1, 1, 'SUCCESS')");
  }

  @AfterEach
  void close() throws Exception {
    database.close();
  }

  private void exec(String sql) throws Exception {
    try (Statement statement = writer.createStatement()) {
      statement.execute(sql);
    }
  }

  private Redactor exporting() {
    return new Redactor(
        RedactionPolicy.forExport().withPathPrefix("/Users/someone/code/p", "[workspace]"));
  }

  @Test
  @DisplayName("CSV carries a header, quotes what needs quoting, and redacts")
  void csvExport() throws Exception {
    TableExport.Result result =
        TableExport.write(
            writer,
            TableExport.Table.ACTIONS,
            TableExport.Format.CSV,
            tempDir.resolve("actions"),
            Optional.of(exporting()));

    List<String> lines = Files.readAllLines(result.file());
    assertThat(result.rows()).isEqualTo(2);
    assertThat(result.file().getFileName().toString()).isEqualTo("actions.csv");
    assertThat(lines.getFirst()).startsWith("id,label,mnemonic,outcome,primary_output");
    // A comma in a value is quoted rather than splitting the row.
    assertThat(lines).anyMatch(line -> line.contains("\"out/b,c.jar\""));
    // Redaction reached the file.
    assertThat(String.join("\n", lines))
        .doesNotContain("ghp_x1y2z3")
        .doesNotContain("/Users/someone")
        .contains("[workspace]/out/a.jar");
    assertThat(result.describe()).contains("redacted.");
  }

  @Test
  @DisplayName("JSON is a real array with nulls for what the session does not have")
  void jsonExport() throws Exception {
    TableExport.Result result =
        TableExport.write(
            writer,
            TableExport.Table.ACTIONS,
            TableExport.Format.JSON,
            tempDir.resolve("actions"),
            Optional.of(exporting()));

    String text = Files.readString(result.file());
    assertThat(text).startsWith("[\n").endsWith("]\n");
    assertThat(text).contains("\"label\": \"//src/main:lib\"");
    // Rule 11 reaches the export: an unknown timestamp is null, never 0.
    assertThat(text).contains("\"start_micros\": null");
    assertThat(text).doesNotContain("ghp_x1y2z3");
  }

  @Test
  @DisplayName("an unredacted export says loudly that it is one")
  void unredactedExportsSaySo() throws Exception {
    TableExport.Result result =
        TableExport.write(
            writer,
            TableExport.Table.ACTIONS,
            TableExport.Format.CSV,
            tempDir.resolve("raw-actions"),
            Optional.empty());

    assertThat(result.redacted()).isFalse();
    assertThat(result.describe()).contains("NOT redacted");
    assertThat(Files.readString(result.file())).contains("ghp_x1y2z3");
  }

  @Test
  @DisplayName("every offered table exports without error")
  void everyTableExports() throws Exception {
    for (TableExport.Table table : TableExport.Table.values()) {
      for (TableExport.Format format : TableExport.Format.values()) {
        TableExport.Result result =
            TableExport.write(
                writer,
                table,
                format,
                tempDir.resolve(table.fileStem() + "-" + format.name()),
                Optional.of(exporting()));
        assertThat(Files.exists(result.file())).as("%s %s", table, format).isTrue();
      }
    }
  }

  @Test
  @DisplayName("an interrupted export leaves nothing that looks finished")
  void exportIsAtomic() throws Exception {
    Path target = tempDir.resolve("blocked.csv");
    Files.createDirectories(target);

    assertThatThrownBy(
            () ->
                TableExport.write(
                    writer,
                    TableExport.Table.ACTIONS,
                    TableExport.Format.CSV,
                    target,
                    Optional.of(exporting())))
        .isInstanceOf(IOException.class);
    assertThat(Files.exists(target.resolveSibling("blocked.csv.partial"))).isFalse();
  }

  @Test
  @DisplayName("the shared quoting follows RFC 4180 and the JSON escaping is JavaScript-safe")
  void sharedEscaping() {
    assertThat(Csv.field("plain")).isEqualTo("plain");
    assertThat(Csv.field("a,b")).isEqualTo("\"a,b\"");
    // Doubling, not a backslash: a backslash is what a programmer expects
    // and what a spreadsheet renders literally.
    assertThat(Csv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    assertThat(Csv.field(null)).isEmpty();

    assertThat(Json.string(null)).isEqualTo("null");
    assertThat(Json.string("a\"b")).isEqualTo("\"a\\\"b\"");
    assertThat(Json.string("a\nb")).isEqualTo("\"a\\nb\"");
    // Legal in JSON, a line terminator in JavaScript, and exactly the kind
    // of character a build's progress output contains.
    assertThat(Json.string("a" + (char) 0x2028 + "b")).isEqualTo("\"a\\u2028b\"");
  }
}
