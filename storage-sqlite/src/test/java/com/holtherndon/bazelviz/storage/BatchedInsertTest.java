package com.holtherndon.bazelviz.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BatchedInsertTest {

  @TempDir Path tempDir;

  private SessionDatabase db;

  @BeforeEach
  void openDatabase() throws Exception {
    db = SessionDatabase.open(tempDir.resolve("insert.db"));
    try (Statement statement = db.writerConnection().createStatement()) {
      statement.execute("CREATE TABLE rows (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
    }
  }

  @AfterEach
  void closeDatabase() throws Exception {
    db.close();
  }

  @Test
  void tenThousandRowsVisibleToConcurrentReaderAfterFlush() throws Exception {
    int rows = 10_000;
    // The read connection is open for the whole write: WAL must let it
    // read while the writer connection holds the database.
    try (Connection read = db.newReadConnection()) {
      try (BatchedInsert insert =
          new BatchedInsert(
              db.writerConnection(), "INSERT INTO rows (id, value) VALUES (?, ?)", 1_000)) {
        for (int i = 0; i < rows; i++) {
          insert.statement().setLong(1, i);
          insert.statement().setString(2, "value-" + i);
          insert.add();
        }
        insert.flush();
        assertThat(insert.rowCount()).isEqualTo(rows);
      }
      assertThat(countRows(read)).isEqualTo(rows);
      try (PreparedStatement lookup =
          read.prepareStatement("SELECT value FROM rows WHERE id = ?")) {
        lookup.setLong(1, 4_242);
        try (ResultSet rs = lookup.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString(1)).isEqualTo("value-4242");
        }
      }
    }
  }

  @Test
  void commitsOnlyAtBatchBoundaries() throws Exception {
    try (Connection read = db.newReadConnection();
        BatchedInsert insert =
            new BatchedInsert(
                db.writerConnection(), "INSERT INTO rows (id, value) VALUES (?, ?)", 5)) {
      for (int i = 0; i < 4; i++) {
        insert.statement().setLong(1, i);
        insert.statement().setString(2, "v");
        insert.add();
      }
      // Four rows pending, batch size five: nothing committed yet, so a
      // second connection must see an empty table.
      assertThat(countRows(read)).isZero();
      assertThat(insert.rowCount()).isZero();

      insert.statement().setLong(1, 4);
      insert.statement().setString(2, "v");
      insert.add();
      // Fifth row crossed the boundary: the whole batch commits at once.
      assertThat(countRows(read)).isEqualTo(5);
      assertThat(insert.rowCount()).isEqualTo(5);
    }
  }

  @Test
  void explicitFlushCommitsPartialBatch() throws Exception {
    try (Connection read = db.newReadConnection();
        BatchedInsert insert =
            new BatchedInsert(
                db.writerConnection(), "INSERT INTO rows (id, value) VALUES (?, ?)", 100)) {
      insert.statement().setLong(1, 1);
      insert.statement().setString(2, "v");
      insert.add();
      assertThat(countRows(read)).isZero();
      insert.flush();
      assertThat(countRows(read)).isEqualTo(1);
    }
  }

  @Test
  void closeFlushesPendingRowsAndRestoresAutoCommit() throws Exception {
    Connection writer = db.writerConnection();
    assertThat(writer.getAutoCommit()).isTrue();
    BatchedInsert insert =
        new BatchedInsert(writer, "INSERT INTO rows (id, value) VALUES (?, ?)", 100);
    assertThat(writer.getAutoCommit()).isFalse();
    insert.statement().setLong(1, 7);
    insert.statement().setString(2, "pending");
    insert.add();
    insert.close();

    assertThat(writer.getAutoCommit()).isTrue();
    try (Connection read = db.newReadConnection()) {
      assertThat(countRows(read)).isEqualTo(1);
    }
  }

  private static long countRows(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM rows")) {
      assertThat(rs.next()).isTrue();
      return rs.getLong(1);
    }
  }
}
