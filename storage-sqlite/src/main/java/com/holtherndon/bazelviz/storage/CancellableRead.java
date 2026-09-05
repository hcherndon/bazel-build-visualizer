package com.holtherndon.bazelviz.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a short SQLite read snapshot whose active statement can be cancelled from another thread.
 */
public final class CancellableRead {

  private final Connection connection;
  private final AtomicLong cancellationEpoch = new AtomicLong();
  private final AtomicReference<Statement> running = new AtomicReference<>();

  public CancellableRead(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  /** Invalidates the read immediately and cancels its captured statement off the caller. */
  public void cancel() {
    cancellationEpoch.incrementAndGet();
    Statement statement = running.get();
    SqlCancellation.request(statement, "bbv-sql-cancel");
  }

  /** Runs {@code work} with count and page statements in the same SQLite read transaction. */
  public <T> T snapshot(Work<T> work) throws SQLException {
    Objects.requireNonNull(work, "work");
    long expectedEpoch = cancellationEpoch.get();
    boolean ownsTransaction = connection.getAutoCommit();
    if (ownsTransaction) {
      connection.setAutoCommit(false);
    }
    try {
      Scope scope = new Scope(expectedEpoch);
      scope.checkCurrent();
      T result = work.run(scope);
      // Do not publish a page when cancellation raced with its final statement.
      scope.checkCurrent();
      return result;
    } finally {
      if (ownsTransaction) {
        try {
          connection.rollback();
        } finally {
          connection.setAutoCommit(true);
        }
      }
    }
  }

  /** The cancellation-aware statement scope for one snapshot. */
  public final class Scope {
    private final long expectedEpoch;

    private Scope(long expectedEpoch) {
      this.expectedEpoch = expectedEpoch;
    }

    /** Prepares, registers and executes one statement-producing operation. */
    public <T> T statement(String sql, StatementWork<T> work) throws SQLException {
      Objects.requireNonNull(sql, "sql");
      Objects.requireNonNull(work, "work");
      checkCurrent();
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        if (!running.compareAndSet(null, statement)) {
          throw new SQLException("another statement is already using this SQLite reader");
        }
        try {
          // Closes the race where cancel() lands after the first check but before registration.
          checkCurrent();
          return work.run(statement);
        } finally {
          running.compareAndSet(statement, null);
        }
      }
    }

    /** Fails before another statement starts when this request was cancelled between statements. */
    public void checkCurrent() throws SQLException {
      if (cancellationEpoch.get() != expectedEpoch) {
        throw new SQLTransientException("SQLite read query was cancelled");
      }
    }
  }

  @FunctionalInterface
  public interface Work<T> {
    T run(Scope scope) throws SQLException;
  }

  @FunctionalInterface
  public interface StatementWork<T> {
    T run(PreparedStatement statement) throws SQLException;
  }
}
