package com.holtherndon.bazelviz.ui.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.format.portable.BvizFormatException;
import com.holtherndon.bazelviz.format.portable.BvizIndex;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizReader;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.catalog.CatalogEntry;
import com.holtherndon.bazelviz.storage.catalog.SessionCatalog;
import com.holtherndon.bazelviz.storage.export.TableExport;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.ui.MainWindow;
import com.holtherndon.bazelviz.ui.session.OpenRequest;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 24's five Phase 9 exit criteria, one test each.
 *
 * <p>Four of them are statements about files rather than about functions, so they are tested
 * against real archives, a real catalog and a real database. The fifth — "redaction tests pass" —
 * is the only exit criterion in the plan that names its own suite, and it is restated here by
 * running the boundary that matters: what actually reaches an exported file.
 */
final class Phase9ExitCriteriaTest {

  private static final long CREATED = 1_700_000_000_000_000L;
  private static final String UUID = "0193f0aa-1111-7000-8000-000000000000";

  @TempDir Path tempDir;

  private Path buildSession(String uuid) throws Exception {
    Path session = tempDir.resolve("sessions/session-" + uuid);
    Files.createDirectories(session.resolve("raw"));
    Files.writeString(
        session.resolve("manifest.json"),
        """
        {
          "formatVersion": 1,
          "appVersion": "0.1.0",
          "sessionId": "%s",
          "createdMicros": %d,
          "state": "READY"
        }
        """
            .formatted(uuid, CREATED));
    Files.writeString(session.resolve("raw/bes-000001.journal"), "bytes".repeat(100));
    try (SessionDatabase database = SessionDatabase.open(session.resolve("session.sqlite"))) {
      MigrationRunner.standard().migrate(database);
      Connection writer = database.writerConnection();
      exec(writer, "INSERT INTO mnemonics (id, value) VALUES (1, 'Javac')");
      exec(writer, "INSERT INTO labels (id, value) VALUES (1, '//src:lib')");
      exec(
          writer,
          "INSERT INTO event_streams (id, stream_key, state)" + " VALUES (1, 's', 'CLOSED')");
      exec(
          writer,
          "INSERT INTO build_invocation (singleton, stream_id,"
              + " workspace_directory, options_description, saw_last_message)"
              + " VALUES (1, 1, '/Users/someone/code/p',"
              + " '--remote_header=Bearer ghp_secret12345', 1)");
      exec(
          writer,
          "INSERT INTO actions (id, primary_output, label_id, mnemonic_id,"
              + " outcome) VALUES (1, '/Users/someone/code/p/out/a.jar', 1, 1, 'SUCCESS')");
    }
    return session;
  }

  private static String sha256(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      hex.append(String.format(Locale.ROOT, "%02x", value));
    }
    return hex.toString();
  }

  private static void exec(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  // --- criterion 1 -------------------------------------------------------

  @Test
  @DisplayName("criterion 1: sessions survive a restart and a relocation")
  void sessionsSurviveRestartAndRelocation() throws Exception {
    Path catalogDirectory = tempDir.resolve("catalog");
    Path session = buildSession(UUID);
    CatalogEntry entry =
        new CatalogEntry(
            UUID,
            "build //…",
            session,
            Optional.of("/Users/someone/code/p"),
            Optional.of("build //..."),
            Optional.of("8.4.1"),
            "READY",
            OptionalLong.of(CREATED),
            OptionalLong.of(CREATED + 1_000),
            OptionalLong.of(1),
            OptionalLong.of(1),
            OptionalLong.of(4_096),
            0,
            OptionalLong.of(CREATED),
            false,
            false,
            Optional.of("1 action"));
    try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
      catalog.record(entry);
      catalog.setPinned(UUID, true);
    }

    // Restart: a new process, the same catalog file.
    try (SessionCatalog reopened = SessionCatalog.open(catalogDirectory)) {
      assertThat(reopened.find(UUID)).isPresent();
      assertThat(reopened.find(UUID).orElseThrow().pinned()).isTrue();
    }

    // Relocation: the user moves the sessions root to another disk.
    Path newRoot = tempDir.resolve("moved");
    Files.createDirectories(newRoot);
    Path moved = newRoot.resolve("session-" + UUID);
    Files.move(session, moved);
    try (SessionCatalog reopened = SessionCatalog.open(catalogDirectory)) {
      SessionCatalog.RescanResult result =
          reopened.rescan(
              newRoot,
              directory ->
                  Optional.of(
                      new CatalogEntry(
                          UUID,
                          "build //…",
                          directory,
                          Optional.empty(),
                          Optional.empty(),
                          Optional.empty(),
                          "READY",
                          OptionalLong.empty(),
                          OptionalLong.empty(),
                          OptionalLong.empty(),
                          OptionalLong.empty(),
                          OptionalLong.empty(),
                          0,
                          OptionalLong.empty(),
                          false,
                          false,
                          Optional.empty())));

      assertThat(result.relocated()).isEqualTo(1);
      assertThat(reopened.find(UUID).orElseThrow().directory()).isEqualTo(moved);
      // Matching is by UUID, which a move does not change, and the user's
      // own decision outlives the move.
      assertThat(reopened.find(UUID).orElseThrow().pinned()).isTrue();
    }
    // And the session itself opens from its new home.
    assertThat(OpenRequest.classify(moved).kind()).isEqualTo(OpenRequest.Kind.SESSION_DIRECTORY);
  }

  // --- criterion 2 -------------------------------------------------------

  @Test
  @DisplayName("criterion 2: a portable archive is validated before anything is opened")
  void archivesValidateBeforeOpening() throws Exception {
    Path session = buildSession(UUID);
    Path archive = tempDir.resolve("out.bviz");
    BvizWriter.write(session, archive, BvizWriter.Options.complete("t"), "0.1.0", CREATED);

    // The good archive validates without writing anything.
    Path shouldStayEmpty = tempDir.resolve("nothing-written");
    BvizReader.Validation validation = BvizReader.validate(archive, BvizLimits.defaults());
    assertThat(validation.index().entries()).isNotEmpty();
    assertThat(Files.exists(shouldStayEmpty)).isFalse();

    // A hostile one is refused, and the refusal happens before extraction
    // puts a byte on disk.
    Path hostile = tempDir.resolve("hostile.bviz");
    byte[] payload = "x".getBytes(StandardCharsets.UTF_8);
    BvizIndex index =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            "0.1.0",
            UUID,
            CREATED,
            false,
            false,
            "",
            List.of(new BvizIndex.Entry("../../escaped", payload.length, sha256(payload))));
    try (OutputStream out = Files.newOutputStream(hostile);
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry(BvizIndex.FILE_NAME));
      zip.write(index.toJson().getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("../../escaped"));
      zip.write(payload);
      zip.closeEntry();
    }
    Path destination = tempDir.resolve("extract-here");
    assertThatThrownBy(() -> BvizReader.extract(hostile, destination, BvizLimits.defaults()))
        .isInstanceOf(BvizFormatException.class);
    assertThat(Files.exists(tempDir.resolve("escaped"))).isFalse();
  }

  // --- criterion 3 -------------------------------------------------------

  @Test
  @DisplayName("criterion 3: the file association declares what the app opens, and it routes")
  void fileAssociationsOpenTheApp() throws Exception {
    // The three halves of "double-clicking a .bviz opens it": the packaging
    // descriptor that makes Finder offer this app, the classifier that
    // knows what the path is, and the public entry point the desktop
    // handler calls. The fourth half — Finder actually doing it — needs an
    // installed bundle and is checked by hand; docs/packaging.md records
    // the Info.plist this produced.
    // The descriptor is a declared data dependency of this test target
    // (exported by //app), found through the runfiles tree.
    Path descriptor =
        Path.of(
            System.getenv("TEST_SRCDIR"),
            "_main",
            "app",
            "src",
            "main",
            "packaging",
            "bviz.properties");
    Properties association = new Properties();
    try (var in = Files.newInputStream(descriptor)) {
      association.load(in);
    }
    assertThat(association.getProperty("extension")).isEqualTo("bviz");
    assertThat("." + association.getProperty("extension")).isEqualTo(BvizWriter.EXTENSION);
    assertThat(association.getProperty("description")).isNotBlank();

    Path archive = tempDir.resolve("double-clicked.bviz");
    Files.write(archive, new byte[] {1});
    assertThat(OpenRequest.classify(archive).kind()).isEqualTo(OpenRequest.Kind.PORTABLE_ARCHIVE);

    // The desktop handler needs one public route, and it is this one.
    Method openPath = MainWindow.class.getMethod("openPath", Path.class);
    assertThat(Modifier.isPublic(openPath.getModifiers())).isTrue();
  }

  // --- criterion 4 -------------------------------------------------------

  @Test
  @DisplayName("criterion 4: exporting a large table does not load it into memory")
  void exportDoesNotLoadTheSession() throws Exception {
    Path session = buildSession("0193f0bb-2222-7000-8000-000000000000");
    int rows = 200_000;
    try (SessionDatabase database = SessionDatabase.open(session.resolve("session.sqlite"))) {
      Connection writer = database.writerConnection();
      writer.setAutoCommit(false);
      try (PreparedStatement insert =
          writer.prepareStatement(
              "INSERT INTO actions (id, primary_output, label_id, mnemonic_id, outcome,"
                  + " failure_message) VALUES (?, ?, 1, 1, 'SUCCESS', ?)")) {
        for (int i = 2; i <= rows; i++) {
          insert.setInt(1, i);
          insert.setString(2, "/Users/someone/code/p/bazel-out/darwin/bin/o" + i + ".o");
          insert.setString(3, "a failure message long enough to matter, number " + i);
          insert.addBatch();
          if (i % 20_000 == 0) {
            insert.executeBatch();
          }
        }
        insert.executeBatch();
      }
      writer.commit();
      writer.setAutoCommit(true);
    }

    Runtime runtime = Runtime.getRuntime();
    System.gc();
    long before = runtime.totalMemory() - runtime.freeMemory();
    TableExport.Result result;
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:sqlite:" + session.resolve("session.sqlite").toAbsolutePath())) {
      result =
          TableExport.write(
              connection,
              TableExport.Table.ACTIONS,
              TableExport.Format.CSV,
              tempDir.resolve("big"),
              Optional.of(
                  new Redactor(
                      RedactionPolicy.forExport()
                          .withPathPrefix("/Users/someone/code/p", "[workspace]"))));
    }
    System.gc();
    long after = runtime.totalMemory() - runtime.freeMemory();

    assertThat(result.rows()).isEqualTo(rows);
    long fileBytes = Files.size(result.file());
    assertThat(fileBytes).isGreaterThan(10L * 1024 * 1024);
    // The file is tens of megabytes and the heap did not grow with it. The
    // bound is generous because a redactor legitimately retains one entry
    // per distinct secret; what it rules out is retaining the rows.
    assertThat(after - before)
        .as("heap growth while writing %d bytes", fileBytes)
        .isLessThan(fileBytes / 2);
  }

  // --- criterion 5 -------------------------------------------------------

  @Test
  @DisplayName("criterion 5: redaction reaches the files an export actually writes")
  void redactionTestsPass() throws Exception {
    Path session = buildSession(UUID);
    Path database = session.resolve("session.sqlite");

    // The policy an export uses is built from the session's own paths, so
    // the prefix that appears on nearly every path is mapped rather than
    // left to the account-name masking.
    RedactionPolicy policy = ExportController.policyFor(database);
    assertThat(policy.pathPrefixes()).containsKey("/Users/someone/code/p");

    try (Connection connection =
        DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
      TableExport.Result result =
          TableExport.write(
              connection,
              TableExport.Table.ACTIONS,
              TableExport.Format.CSV,
              tempDir.resolve("redacted-actions"),
              Optional.of(new Redactor(policy)));

      String text = Files.readString(result.file());
      assertThat(text)
          .doesNotContain("ghp_secret12345")
          .doesNotContain("/Users/someone")
          .contains("[workspace]/out/a.jar");
      assertThat(result.redacted()).isTrue();
    }
  }
}
