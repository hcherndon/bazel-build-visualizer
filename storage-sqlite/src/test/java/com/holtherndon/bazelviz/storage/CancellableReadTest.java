package com.holtherndon.bazelviz.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CancellableReadTest {

  @TempDir Path temporary;

  @Test
  void statementCancellationLeavesSwingEventThreadBeforeCallingJdbc() throws Exception {
    CountDownLatch cancelled = new CountDownLatch(1);
    AtomicBoolean cancelledOnEventThread = new AtomicBoolean(true);
    Statement statement =
        (Statement)
            Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("cancel")) {
                    cancelledOnEventThread.set(SwingUtilities.isEventDispatchThread());
                    cancelled.countDown();
                    return null;
                  }
                  if (method.getName().equals("toString")) {
                    return "CancellationStatement";
                  }
                  throw new UnsupportedOperationException(method.getName());
                });

    SwingUtilities.invokeAndWait(() -> SqlCancellation.request(statement, "test-sql-cancel"));

    assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(cancelledOnEventThread).isFalse();
  }

  @Test
  void cancellationStopsTheRestOfOneRequestButNotTheNext() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(temporary.resolve("cancel.db"));
        Connection connection = database.newReadConnection()) {
      CancellableRead read = new CancellableRead(connection);
      AtomicBoolean secondStatementRan = new AtomicBoolean();

      assertThatThrownBy(
              () ->
                  read.snapshot(
                      scope -> {
                        assertThat(queryOne(scope)).isEqualTo(1);
                        read.cancel();
                        return scope.statement(
                            "SELECT 2",
                            statement -> {
                              secondStatementRan.set(true);
                              try (ResultSet rows = statement.executeQuery()) {
                                return rows.next() ? rows.getInt(1) : 0;
                              }
                            });
                      }))
          .isInstanceOf(SQLTransientException.class)
          .hasMessageContaining("cancelled");

      assertThat(secondStatementRan).isFalse();
      assertThat(connection.getAutoCommit()).isTrue();
      assertThat(read.snapshot(CancellableReadTest::queryOne)).isEqualTo(1);
    }
  }

  @Test
  void cancellationDuringTheLastStatementDoesNotPublishItsResult() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(temporary.resolve("last-statement.db"));
        Connection connection = database.newReadConnection()) {
      CancellableRead read = new CancellableRead(connection);

      assertThatThrownBy(
              () ->
                  read.snapshot(
                      scope ->
                          scope.statement(
                              "SELECT 1",
                              statement -> {
                                try (ResultSet rows = statement.executeQuery()) {
                                  assertThat(rows.next()).isTrue();
                                  int value = rows.getInt(1);
                                  read.cancel();
                                  return value;
                                }
                              })))
          .isInstanceOf(SQLTransientException.class)
          .hasMessageContaining("cancelled");

      assertThat(connection.getAutoCommit()).isTrue();
    }
  }

  @Test
  void allStatementsSeeOneDatabaseSnapshot() throws Exception {
    try (SessionDatabase database = SessionDatabase.open(temporary.resolve("snapshot.db"));
        Connection connection = database.newReadConnection()) {
      try (Statement statement = database.writerConnection().createStatement()) {
        statement.execute("CREATE TABLE values_for_test (value INTEGER NOT NULL)");
        statement.execute("INSERT INTO values_for_test VALUES (1)");
      }
      CancellableRead read = new CancellableRead(connection);

      List<Integer> counts =
          read.snapshot(
              scope -> {
                int before = rowCount(scope);
                try (Statement statement = database.writerConnection().createStatement()) {
                  statement.execute("INSERT INTO values_for_test VALUES (2)");
                }
                return List.of(before, rowCount(scope));
              });

      assertThat(counts).containsExactly(1, 1);
      assertThat(read.snapshot(CancellableReadTest::rowCount)).isEqualTo(2);
      assertThat(connection.getAutoCommit()).isTrue();
    }
  }

  private static int queryOne(CancellableRead.Scope scope) throws SQLException {
    return scope.statement(
        "SELECT 1",
        statement -> {
          try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
          }
        });
  }

  private static int rowCount(CancellableRead.Scope scope) throws SQLException {
    return scope.statement(
        "SELECT COUNT(*) FROM values_for_test",
        statement -> {
          try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
          }
        });
  }
}
