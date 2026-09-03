package com.holtherndon.bazelviz.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A failed batch must leave nothing behind. Restoring auto-commit commits any open transaction, so
 * without an explicit rollback a failed batch is silently made durable — the exact "never silently
 * override" failure the project forbids.
 */
class BatchedInsertFailureTest {

  @TempDir Path tempDir;

  private Path dbFile;

  @BeforeEach
  void setUp() {
    dbFile = tempDir.resolve("failure.db");
  }

  private static void createTable(SessionDatabase db) throws SQLException {
    try (Statement statement = db.writerConnection().createStatement()) {
      statement.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)");
    }
  }

  private static long countRows(Path file) throws SQLException {
    try (SessionDatabase db = SessionDatabase.open(file);
        Statement statement = db.newReadConnection().createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM t")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void failedBatchIsNotCommittedWhenCloseRestoresAutoCommit() throws SQLException {
    try (SessionDatabase db = SessionDatabase.open(dbFile)) {
      createTable(db);
      BatchedInsert insert =
          new BatchedInsert(db.writerConnection(), "INSERT INTO t (id, v) VALUES (?, ?)", 1000);
      for (int i = 0; i < 5; i++) {
        insert.statement().setLong(1, i);
        insert.statement().setString(2, "row" + i);
        insert.add();
      }
      // Duplicate primary key: the batch cannot succeed.
      insert.statement().setLong(1, 0);
      insert.statement().setString(2, "duplicate");
      insert.add();

      assertThatThrownBy(insert::close).isInstanceOf(SQLException.class);
      assertThat(insert.rowCount()).isZero();
    }

    assertThat(countRows(dbFile))
        .as("a batch that threw must not be silently committed by close()")
        .isZero();
  }

  @Test
  void rowCountAgreesWithDatabaseAfterARecoveredFailure() throws SQLException {
    try (SessionDatabase db = SessionDatabase.open(dbFile)) {
      createTable(db);
      try (BatchedInsert insert =
          new BatchedInsert(db.writerConnection(), "INSERT INTO t (id, v) VALUES (?, ?)", 1000)) {
        for (int i = 0; i < 5; i++) {
          insert.statement().setLong(1, i);
          insert.statement().setString(2, "row" + i);
          insert.add();
        }
        insert.statement().setLong(1, 0);
        insert.statement().setString(2, "duplicate");
        insert.add();
        assertThatThrownBy(insert::flush).isInstanceOf(SQLException.class);

        // The caller recovers and continues with a fresh batch.
        insert.statement().setLong(1, 100);
        insert.statement().setString(2, "after recovery");
        insert.add();
        insert.flush();

        assertThat(insert.rowCount())
            .as("the failed batch's rows must not be counted")
            .isEqualTo(1);
      }
    }

    assertThat(countRows(dbFile)).isEqualTo(1);
  }

  @Test
  void connectionIsUsableAfterAFailedBatch() throws SQLException {
    try (SessionDatabase db = SessionDatabase.open(dbFile)) {
      createTable(db);
      Connection connection = db.writerConnection();
      try (BatchedInsert insert =
          new BatchedInsert(connection, "INSERT INTO t (id, v) VALUES (?, ?)", 1000)) {
        insert.statement().setLong(1, 1);
        insert.statement().setString(2, "first");
        insert.add();
        insert.statement().setLong(1, 1);
        insert.statement().setString(2, "duplicate");
        insert.add();
        assertThatThrownBy(insert::flush).isInstanceOf(SQLException.class);
      }
      // No transaction should still be open holding the write lock.
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO t (id, v) VALUES (42, 'later')");
      }
    }

    assertThat(countRows(dbFile)).isEqualTo(1);
  }
}
