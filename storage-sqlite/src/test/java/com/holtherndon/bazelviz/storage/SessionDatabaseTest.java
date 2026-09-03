package com.holtherndon.bazelviz.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionDatabaseTest {

  @TempDir Path tempDir;

  @Test
  void appliesPragmasOnWriterAndReadConnections() throws Exception {
    try (SessionDatabase db = SessionDatabase.open(tempDir.resolve("session.db"))) {
      assertPragmas(db.writerConnection());
      try (Connection read = db.newReadConnection()) {
        assertPragmas(read);
      }
    }
  }

  @Test
  void createsFileOnOpen() throws Exception {
    Path file = tempDir.resolve("created.db");
    assertThat(Files.exists(file)).isFalse();
    try (SessionDatabase db = SessionDatabase.open(file)) {
      assertThat(Files.exists(file)).isTrue();
      assertThat(db.file()).isEqualTo(file);
    }
  }

  @Test
  void closeReleasesFileEvenWithUnclosedReadConnection() throws Exception {
    Path file = tempDir.resolve("release.db");
    SessionDatabase db = SessionDatabase.open(file);
    try (Statement statement = db.writerConnection().createStatement()) {
      statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
    }
    Connection leakedRead = db.newReadConnection();
    db.close();

    assertThat(leakedRead.isClosed()).isTrue();
    assertThat(db.writerConnection().isClosed()).isTrue();
    // With every connection closed, WAL sidecar files are cleaned up and
    // the database can be deleted — the practical definition of "released".
    assertThat(Files.exists(file.resolveSibling("release.db-wal"))).isFalse();
    assertThat(Files.deleteIfExists(file)).isTrue();
  }

  @Test
  void theQueryConnectionIsReadOnlyAndStaysTracked() throws Exception {
    Path file = tempDir.resolve("query.db");
    SessionDatabase db = SessionDatabase.open(file);
    try (Statement statement = db.writerConnection().createStatement()) {
      statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY)");
      statement.execute("INSERT INTO t VALUES (1)");
    }

    Connection query = db.newQueryConnection();
    assertThat(query.isReadOnly()).isTrue();
    assertThat(pragma(query, "query_only")).isEqualTo("1");
    // Reads work while the writer still holds the file in WAL mode.
    try (Statement statement = query.createStatement();
        ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM t")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isEqualTo(1L);
    }

    // Not closed by the caller: close() must still release it, exactly as
    // it does for a read connection.
    db.close();
    assertThat(query.isClosed()).isTrue();
  }

  private static void assertPragmas(Connection connection) throws SQLException {
    assertThat(pragma(connection, "journal_mode")).isEqualToIgnoringCase("wal");
    // synchronous=NORMAL reports as 1.
    assertThat(pragma(connection, "synchronous")).isEqualTo("1");
    assertThat(pragma(connection, "foreign_keys")).isEqualTo("1");
    assertThat(Integer.parseInt(pragma(connection, "busy_timeout"))).isPositive();
  }

  private static String pragma(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("PRAGMA " + name)) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }
}
