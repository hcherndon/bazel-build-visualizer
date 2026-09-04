package com.holtherndon.bazelviz.ui.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizReader;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Security regressions at the real redacted-archive controller boundary. */
final class ExportControllerTest {

  private static final String SESSION_ID = "0193f0aa-1111-7000-8000-000000000000";
  private static final String SECRET = "shared-secret-value-12345";
  private static final String SECRET_ARGUMENT = "Bearer " + SECRET;
  private static final Pattern PSEUDONYM = Pattern.compile("\\[redacted:[0-9a-f]+]");

  @TempDir Path tempDir;

  @Test
  @DisplayName("a redacted export has no secret canary anywhere in its archive")
  void wholeArchiveCanaryAndPermissions() throws Exception {
    Path session = buildRichSession("whole-archive");
    byte[] sourceManifest = Files.readAllBytes(session.resolve("manifest.json"));
    byte[] sourceDatabase = Files.readAllBytes(session.resolve("session.sqlite"));
    Path scratchParent = Files.createDirectory(tempDir.resolve("redaction-scratch"));
    Path archive = tempDir.resolve("redacted.bviz");
    ExecutorService worker = Executors.newSingleThreadExecutor();
    CompletableFuture<BvizWriter.Result> completion = new CompletableFuture<>();
    try {
      ExportController controller = new ExportController(worker, Runnable::run, scratchParent);
      controller.exportArchive(
          session,
          archive,
          true,
          ExportController.RedactionOptions.defaults(),
          "test",
          report -> {
            assertThat(report.redactions()).isGreaterThan(0);
            Path staging = onlyChild(scratchParent);
            assertOwnerOnly(staging, "rwx------");
            try (var files = Files.list(staging)) {
              assertThat(files.map(path -> path.getFileName().toString()))
                  .containsExactlyInAnyOrder("manifest.json", "session.sqlite");
            } catch (Exception failure) {
              throw new AssertionError(failure);
            }
            assertOwnerOnly(staging.resolve("manifest.json"), "r--------");
            assertOwnerOnly(staging.resolve("session.sqlite"), "r--------");
            return true;
          },
          completion::complete,
          completion::completeExceptionally);

      BvizWriter.Result result = completion.get(30, TimeUnit.SECONDS);
      assertThat(result.index().entries())
          .extracting(entry -> entry.path())
          .containsExactlyInAnyOrder("manifest.json", "session.sqlite");
      assertThat(result.index().redacted()).isTrue();
      assertThat(result.index().includesRawSources()).isFalse();
      assertDirectoryEmpty(scratchParent);
      assertNoOutputScratch();

      Path extracted = tempDir.resolve("extracted");
      BvizReader.extract(archive, extracted, BvizLimits.defaults());
      assertThat(extracted.resolve("instrumentation-plan.json")).doesNotExist();
      assertThat(extracted.resolve("raw")).doesNotExist();
      assertThat(extracted.resolve("indexes")).doesNotExist();
      assertThat(extracted.resolve("checkpoints")).doesNotExist();

      String manifest = Files.readString(extracted.resolve("manifest.json"));
      assertThat(manifest)
          .doesNotContain(SECRET)
          .doesNotContain("canary-user")
          .doesNotContain("argv-home-user")
          .doesNotContain("builder@secret.internal")
          .doesNotContain("Secret build host")
          .doesNotContain("unknown-Bearer")
          .doesNotContain("index-secret")
          .doesNotContain("867530912345")
          .doesNotContain("source-unknown-secret")
          .doesNotContain("stable-source-digest-secret")
          .contains("\"redactionState\": \"REDACTED\"")
          .contains("[workspace]/argv-original-bare")
          .contains("--output_base=/Users/[user]/argv-original-inline")
          .contains("/Users/[user]/argv-effective-bare")
          .contains("--output_base=[workspace]/argv-effective-inline")
          .contains("[workspace]/argv-injected-bare")
          .contains("--output_base=/Users/[user]/argv-injected-inline")
          .contains("/Users/[user]/argv-auxiliary-bare")
          .contains("--output_base=[workspace]/argv-auxiliary-inline");
      String databaseValue = readString(extracted.resolve("session.sqlite"));
      assertThat(databaseValue).startsWith("Bearer ");
      String sharedPseudonym = databaseValue.substring("Bearer ".length());
      assertThat(sharedPseudonym).matches(PSEUDONYM);
      assertThat(manifest).contains(sharedPseudonym);

      String everyExtractedByte = extractedBytes(extracted);
      assertThat(everyExtractedByte)
          .doesNotContain(SECRET)
          .doesNotContain("canary-user")
          .doesNotContain("argv-home-user")
          .doesNotContain("builder@secret.internal")
          .doesNotContain("instrumentation-secret")
          .doesNotContain("raw-secret")
          .doesNotContain("checkpoint-secret")
          .doesNotContain("opaque-sidecar-secret");
      assertThat(Files.readAllBytes(session.resolve("manifest.json"))).isEqualTo(sourceManifest);
      assertThat(Files.readAllBytes(session.resolve("session.sqlite"))).isEqualTo(sourceDatabase);
    } finally {
      worker.shutdownNow();
      assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("concurrent exports use separate scratch and unlinkable pseudonyms")
  void concurrentExportsHaveIsolatedKeysAndScratch() throws Exception {
    Path session = buildRichSession("concurrent");
    byte[] sourceManifest = Files.readAllBytes(session.resolve("manifest.json"));
    byte[] sourceDatabase = Files.readAllBytes(session.resolve("session.sqlite"));
    Path scratchParent = Files.createDirectory(tempDir.resolve("concurrent-scratch"));
    ExecutorService worker = Executors.newFixedThreadPool(2);
    CountDownLatch bothStaged = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<BvizWriter.Result> first = new CompletableFuture<>();
    CompletableFuture<BvizWriter.Result> second = new CompletableFuture<>();
    ExportController.Confirmer barrier =
        report -> {
          bothStaged.countDown();
          try {
            if (!release.await(20, TimeUnit.SECONDS)) {
              throw new AssertionError("concurrent export barrier timed out");
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
          }
          return true;
        };
    try {
      ExportController controller = new ExportController(worker, Runnable::run, scratchParent);
      controller.exportArchive(
          session,
          tempDir.resolve("first.bviz"),
          true,
          ExportController.RedactionOptions.defaults(),
          "test",
          barrier,
          first::complete,
          first::completeExceptionally);
      controller.exportArchive(
          session,
          tempDir.resolve("second.bviz"),
          true,
          ExportController.RedactionOptions.defaults(),
          "test",
          barrier,
          second::complete,
          second::completeExceptionally);

      assertThat(bothStaged.await(20, TimeUnit.SECONDS)).isTrue();
      try (var scratch = Files.list(scratchParent)) {
        assertThat(scratch).hasSize(2);
      }
      release.countDown();
      first.get(30, TimeUnit.SECONDS);
      second.get(30, TimeUnit.SECONDS);

      assertThat(databasePseudonym(tempDir.resolve("first.bviz"), "first-extracted"))
          .isNotEqualTo(databasePseudonym(tempDir.resolve("second.bviz"), "second-extracted"));
      assertDirectoryEmpty(scratchParent);
      assertNoOutputScratch();
      assertThat(Files.readAllBytes(session.resolve("manifest.json"))).isEqualTo(sourceManifest);
      assertThat(Files.readAllBytes(session.resolve("session.sqlite"))).isEqualTo(sourceDatabase);
    } finally {
      release.countDown();
      worker.shutdownNow();
      assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("declines and publish failures clean scratch before returning control")
  void declineAndFailureCleanup() throws Exception {
    Path session = buildRichSession("cleanup");
    Path scratchParent = Files.createDirectory(tempDir.resolve("cleanup-scratch"));
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      ExportController controller = new ExportController(worker, Runnable::run, scratchParent);
      Path declined = tempDir.resolve("declined.bviz");
      controller.exportArchive(
          session,
          declined,
          true,
          ExportController.RedactionOptions.defaults(),
          "test",
          report -> false,
          ignored -> {
            throw new AssertionError("a declined export completed");
          },
          failure -> {
            throw new AssertionError("a decline failed", failure);
          });
      worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
      assertThat(declined).doesNotExist();
      assertDirectoryEmpty(scratchParent);

      Path occupied = tempDir.resolve("occupied.bviz");
      Files.createDirectory(occupied);
      Files.writeString(occupied.resolve("mine"), "keep me");
      CompletableFuture<Throwable> failed = new CompletableFuture<>();
      controller.exportArchive(
          session,
          occupied,
          true,
          ExportController.RedactionOptions.defaults(),
          "test",
          report -> true,
          ignored ->
              failed.completeExceptionally(new AssertionError("publish unexpectedly worked")),
          failure -> {
            try {
              assertDirectoryEmpty(scratchParent);
              failed.complete(failure);
            } catch (Throwable assertion) {
              failed.completeExceptionally(assertion);
            }
          });
      assertThat(failed.get(30, TimeUnit.SECONDS)).isNotNull();
      assertThat(Files.readString(occupied.resolve("mine"))).isEqualTo("keep me");
      assertDirectoryEmpty(scratchParent);
      assertNoOutputScratch();
    } finally {
      worker.shutdownNow();
      assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("a linked session manifest is rejected before redacted scratch or target creation")
  void linkedSessionInputsAreRejected() throws Exception {
    Path session = buildRichSession("linked");
    Path outside = tempDir.resolve("outside-manifest.json");
    Files.move(session.resolve("manifest.json"), outside);
    Files.createSymbolicLink(session.resolve("manifest.json"), outside);
    Path scratchParent = Files.createDirectory(tempDir.resolve("linked-scratch"));
    Path target = tempDir.resolve("linked.bviz");
    ExecutorService worker = Executors.newSingleThreadExecutor();
    CompletableFuture<Throwable> failed = new CompletableFuture<>();
    try {
      ExportController controller = new ExportController(worker, Runnable::run, scratchParent);
      controller.exportArchive(
          session,
          target,
          true,
          ExportController.RedactionOptions.defaults(),
          "test",
          report -> true,
          ignored -> failed.completeExceptionally(new AssertionError("linked input was exported")),
          failed::complete);

      assertThat(failed.get(30, TimeUnit.SECONDS)).hasMessageContaining("no-follow");
      assertThat(target).doesNotExist();
      assertDirectoryEmpty(scratchParent);
    } finally {
      worker.shutdownNow();
      assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Path buildRichSession(String name) throws Exception {
    Path session = tempDir.resolve("session-" + name);
    Files.createDirectories(session.resolve("raw"));
    Files.createDirectories(session.resolve("indexes"));
    Files.createDirectories(session.resolve("checkpoints"));
    Files.writeString(
        session.resolve("manifest.json"),
        """
        {
          "formatVersion": 1,
          "appVersion": "test",
          "sessionId": "%s",
          "createdMicros": 1700000000000000,
          "state": "READY",
          "workingDirectory": "/Users/canary-user/private/work",
          "workspaceRoot": "/Users/canary-user/private/work",
          "executionLocation": {
            "kind": "SSH",
            "displayName": "Secret build host",
            "sshDestination": "builder@secret.internal",
            "sshPort": 2222
          },
          "bazelExecutable": "/Users/canary-user/bin/bazel",
          "originalCommand": ["bazel", "build", "--remote_header", "%s",
            "/Users/canary-user/private/work/argv-original-bare",
            "--output_base=/Users/argv-home-user/argv-original-inline"],
          "effectiveCommand": ["bazel", "build", "--remote_header=%s",
            "/Users/argv-home-user/argv-effective-bare",
            "--output_base=/Users/canary-user/private/work/argv-effective-inline"],
          "environmentCapturePolicy": "policy %s",
          "capturePreset": "preset %s",
          "injectedFlags": ["--remote_header=%s",
            "/Users/canary-user/private/work/argv-injected-bare",
            "--output_base=/Users/argv-home-user/argv-injected-inline"],
          "auxiliaryCommands": [{
            "label": "auxiliary %s",
            "argv": ["tool", "--credential", "%s",
              "/Users/argv-home-user/argv-auxiliary-bare",
              "--output_base=/Users/canary-user/private/work/argv-auxiliary-inline"]
          }],
          "sources": [{
            "kind": "source %s",
            "path": "https://ci-user:verysecretpassword@cache.example/source",
            "sha256": "stable-source-digest-secret",
            "byteSize": 12,
            "completeness": "COMPLETE",
            "note": "note %s",
            "source-unknown-secret-%s": false
          }],
          "warnings": ["warning %s"],
          "indexVersions": {"index-secret-%s": 3},
          "unknown-Bearer-%s": 867530912345,
          "unknownBooleanSecretKey-%s": true
        }
        """
            .formatted(
                SESSION_ID,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET_ARGUMENT,
                SECRET,
                SECRET_ARGUMENT,
                SECRET,
                SECRET,
                SECRET));
    Files.writeString(
        session.resolve("instrumentation-plan.json"), "Bearer instrumentation-secret");
    Files.writeString(session.resolve("raw/bes-000001.journal"), "Bearer raw-secret");
    Files.writeString(session.resolve("indexes/opaque.sidecar"), "Bearer opaque-sidecar-secret");
    Files.writeString(session.resolve("checkpoints/import.ckpt"), "Bearer checkpoint-secret");
    try (SessionDatabase database = SessionDatabase.open(session.resolve("session.sqlite"))) {
      MigrationRunner.standard().migrate(database);
      Connection writer = database.writerConnection();
      execute(writer, "INSERT INTO strings (id, value) VALUES (1, 'Bearer " + SECRET + "')");
      execute(
          writer, "INSERT INTO event_streams (id, stream_key, state) VALUES (1, 's', 'CLOSED')");
      execute(
          writer,
          "INSERT INTO build_invocation"
              + " (singleton, stream_id, working_directory, workspace_directory,"
              + " options_description, saw_last_message) VALUES"
              + " (1, 1, NULL, '/Users/canary-user/private/work',"
              + " '--remote_header=Bearer "
              + SECRET
              + "', 1)");
      execute(
          writer,
          "INSERT INTO capture_sources"
              + " (id, kind, path, completeness, note) VALUES"
              + " (1, 'BEP', '/Users/canary-user/private/work/build.bep', 'COMPLETE',"
              + " 'Bearer "
              + SECRET
              + "')");
    }
    return session;
  }

  private String databasePseudonym(Path archive, String directoryName) throws Exception {
    Path extracted = tempDir.resolve(directoryName);
    BvizReader.extract(archive, extracted, BvizLimits.defaults());
    return readString(extracted.resolve("session.sqlite"));
  }

  private static String readString(Path database) throws Exception {
    try (SessionDatabase copy = SessionDatabase.open(database);
        Statement statement = copy.writerConnection().createStatement();
        ResultSet rows = statement.executeQuery("SELECT value FROM strings WHERE id = 1")) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private static void execute(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Path onlyChild(Path directory) {
    try (var children = Files.list(directory)) {
      List<Path> values = children.toList();
      assertThat(values).hasSize(1);
      return values.getFirst();
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void assertOwnerOnly(Path path, String expected) {
    try {
      if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
        return;
      }
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
      assertThat(permissions).isEqualTo(PosixFilePermissions.fromString(expected));
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void assertDirectoryEmpty(Path directory) throws Exception {
    try (var children = Files.list(directory)) {
      assertThat(children).isEmpty();
    }
  }

  private void assertNoOutputScratch() throws Exception {
    try (var children = Files.list(tempDir)) {
      assertThat(children.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.startsWith(".bviz-export-"));
    }
  }

  private static String extractedBytes(Path root) throws Exception {
    StringBuilder bytes = new StringBuilder();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        bytes.append(new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
      }
    }
    return bytes.toString();
  }
}
