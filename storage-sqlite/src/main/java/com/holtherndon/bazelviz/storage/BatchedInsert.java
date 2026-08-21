package com.holtherndon.bazelviz.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * High-throughput inserts: a prepared statement batched with
 * {@code addBatch}/{@code executeBatch}, wrapped in explicit transactions of a
 * configurable size. SQLite commits are the expensive part (an fsync each in
 * WAL/NORMAL only at checkpoints, but still a write barrier), so committing
 * every N rows instead of every row is the difference between ~1k and ~1M
 * rows/sec.
 *
 * <p>Rows added since the last batch boundary are invisible to other
 * connections until {@link #flush()} or the boundary commit runs. Not
 * thread-safe; use from the single writer thread only.
 */
public final class BatchedInsert implements AutoCloseable {

    private final Connection connection;
    private final PreparedStatement statement;
    private final int batchSize;
    private final boolean previousAutoCommit;

    private int pendingRows;
    private long totalRows;
    private boolean closed;

    /**
     * @param connection the writer connection; its auto-commit mode is
     *     suspended for the lifetime of this object and restored on close
     * @param sql a parameterized INSERT statement
     * @param batchSize rows per transaction; each time this many rows
     *     accumulate they are executed and committed as one transaction
     */
    public BatchedInsert(Connection connection, String sql, int batchSize) throws SQLException {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
        }
        this.connection = connection;
        this.batchSize = batchSize;
        this.previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        this.statement = connection.prepareStatement(sql);
    }

    /** The statement to bind the next row's parameters on before calling {@link #add()}. */
    public PreparedStatement statement() {
        return statement;
    }

    /**
     * Enqueues the currently bound parameters as one row. When {@code batchSize}
     * rows have accumulated, executes and commits them as a single transaction.
     */
    public void add() throws SQLException {
        statement.addBatch();
        pendingRows++;
        if (pendingRows >= batchSize) {
            flush();
        }
    }

    /**
     * Executes and commits any pending rows. No-op when nothing is pending.
     *
     * <p>On failure the transaction is rolled back before the exception is
     * rethrown. Leaving it open would be silent data corruption twice over: a
     * later successful {@link #flush()} would commit this batch's partial work
     * alongside the new rows, and {@link #close()} restoring auto-commit would
     * commit it outright, because JDBC commits the in-flight transaction when
     * auto-commit is re-enabled.
     */
    public void flush() throws SQLException {
        if (pendingRows == 0) {
            return;
        }
        int flushing = pendingRows;
        try {
            statement.executeBatch();
            connection.commit();
        } catch (SQLException failure) {
            pendingRows = 0;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            try {
                statement.clearBatch();
            } catch (SQLException clearFailure) {
                failure.addSuppressed(clearFailure);
            }
            throw failure;
        }
        totalRows += flushing;
        pendingRows = 0;
    }

    /** Rows committed so far; rows pending in the current batch are not counted. */
    public long rowCount() {
        return totalRows;
    }

    /** Flushes pending rows, closes the statement, and restores the connection's auto-commit mode. */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } finally {
            try {
                statement.close();
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }
}
