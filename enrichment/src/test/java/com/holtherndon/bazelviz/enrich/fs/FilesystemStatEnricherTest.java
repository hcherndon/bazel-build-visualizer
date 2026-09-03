package com.holtherndon.bazelviz.enrich.fs;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading output sizes off disk, and the three ways that can go.
 *
 * <p>A file that is there, a file that is not — the normal case for a remote build — and a path
 * that points somewhere it should not.
 */
final class FilesystemStatEnricherTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;
  private Path root;

  @BeforeEach
  void buildSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    root = Files.createDirectories(tempDir.resolve("execroot"));
  }

  @AfterEach
  void closeSession() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("a size with no other source is read from disk")
  void holesAreFilled() throws Exception {
    Files.createDirectories(root.resolve("bazel-out/bin"));
    Files.writeString(root.resolve("bazel-out/bin/a.txt"), "hello");
    exec("INSERT INTO artifacts (path) VALUES ('bazel-out/bin/a.txt')");

    FilesystemStatEnricher.Result result = new FilesystemStatEnricher(connection).enrich(root);

    assertThat(result.filled()).isEqualTo(1);
    assertThat(scalar("SELECT size_bytes FROM artifacts WHERE path = 'bazel-out/bin/a.txt'"))
        .isEqualTo(5);
  }

  @Test
  @DisplayName("a size Bazel already reported is never overwritten")
  void reportedSizesWin() throws Exception {
    Files.createDirectories(root.resolve("bazel-out/bin"));
    Files.writeString(root.resolve("bazel-out/bin/b.txt"), "rebuilt since then");
    exec("INSERT INTO artifacts (path, size_bytes) VALUES ('bazel-out/bin/b.txt', 3)");

    FilesystemStatEnricher.Result result = new FilesystemStatEnricher(connection).enrich(root);

    // A stat taken later, of a file that may have been rebuilt, is a
    // different measurement from the one recorded at execution time.
    assertThat(result.considered()).isZero();
    assertThat(scalar("SELECT size_bytes FROM artifacts WHERE path = 'bazel-out/bin/b.txt'"))
        .isEqualTo(3);
  }

  @Test
  @DisplayName("a file that is not there is counted, not treated as zero bytes")
  void absentFilesAreCountedNotZeroed() throws Exception {
    exec("INSERT INTO artifacts (path) VALUES ('bazel-out/bin/remote-only.txt')");

    FilesystemStatEnricher.Result result = new FilesystemStatEnricher(connection).enrich(root);

    // The expected answer for an artifact that only ever existed on a
    // remote executor. Writing 0 would put an empty file in the totals.
    assertThat(result.absent()).isEqualTo(1);
    assertThat(result.filled()).isZero();
    assertThat(result.nothingWasLocal()).isTrue();
    assertThat(isNull("SELECT size_bytes FROM artifacts")).isTrue();
  }

  @Test
  @DisplayName("a path escaping the output tree is refused, not statted")
  void escapesAreRefused() throws Exception {
    Files.writeString(tempDir.resolve("outside.txt"), "not part of the build");
    exec("INSERT INTO artifacts (path) VALUES ('../outside.txt')");
    exec("INSERT INTO artifacts (path) VALUES ('/etc/hosts')");

    FilesystemStatEnricher.Result result = new FilesystemStatEnricher(connection).enrich(root);

    // Session paths are text this application did not write, and an
    // imported session is untrusted outright (plan 22.4).
    assertThat(result.refused()).isEqualTo(2);
    assertThat(result.filled()).isZero();
  }

  @Test
  @DisplayName("a symlink pointing outside the tree is refused even though the link is inside")
  void symlinksAreResolvedBeforeChecking() throws Exception {
    Path outside = tempDir.resolve("secret.txt");
    Files.writeString(outside, "0123456789");
    Files.createDirectories(root.resolve("bazel-out/bin"));
    try {
      Files.createSymbolicLink(root.resolve("bazel-out/bin/link.txt"), outside);
    } catch (UnsupportedOperationException | IOException noSymlinks) {
      return;
    }
    exec("INSERT INTO artifacts (path) VALUES ('bazel-out/bin/link.txt')");

    FilesystemStatEnricher.Result result = new FilesystemStatEnricher(connection).enrich(root);

    // Bazel's output tree is full of symlinks, so checking the link's own
    // path rather than its target would pass this every time.
    assertThat(result.refused()).isEqualTo(1);
    assertThat(result.filled()).isZero();
  }

  @Test
  @DisplayName("directories are left alone")
  void treeArtifactsAreSkipped() throws Exception {
    Files.createDirectories(root.resolve("bazel-out/bin/tree"));
    exec("INSERT INTO artifacts (path, is_directory) VALUES ('bazel-out/bin/tree', 1)");

    FilesystemStatEnricher.Result result = new FilesystemStatEnricher(connection).enrich(root);

    // A directory's size on disk is not the size of the outputs in it, and
    // reporting one as the other would be a number that means nothing.
    assertThat(result.considered()).isZero();
  }

  private void exec(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private long scalar(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1L;
    }
  }

  private boolean isNull(String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        return false;
      }
      rows.getLong(1);
      return rows.wasNull();
    }
  }
}
