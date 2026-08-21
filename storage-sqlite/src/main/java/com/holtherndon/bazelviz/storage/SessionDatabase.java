package com.holtherndon.bazelviz.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A session's SQLite database file: one long-lived writer connection plus a
 * factory for additional read connections.
 *
 * <p>Operating rules (plan section 10.9): WAL journal mode so readers never
 * block the writer, {@code synchronous=NORMAL} (safe with WAL, far faster than
 * FULL), a busy timeout so contended connections wait instead of failing
 * immediately, and foreign keys enforced. SQLite allows exactly one writer at
 * a time; all writes must go through {@link #writerConnection()}. Read
 * connections from {@link #newReadConnection()} may be used from any thread
 * (one connection per thread — connections themselves are not thread-safe).
 *
 * <p>Explicit SQL only; there is deliberately no ORM in this codebase.
 */
public final class SessionDatabase implements AutoCloseable {

    private static final int BUSY_TIMEOUT_MILLIS = 10_000;

    private final Path file;
    private final Connection writer;
    // Read connections are tracked so close() can release the file even if a
    // caller forgot to close one; CopyOnWriteArrayList because reads are opened
    // from arbitrary threads.
    private final List<Connection> readConnections = new CopyOnWriteArrayList<>();

    private SessionDatabase(Path file, Connection writer) {
        this.file = file;
        this.writer = writer;
    }

    /** Opens (creating if absent) the database at {@code file} and applies the standard pragmas. */
    public static SessionDatabase open(Path file) throws SQLException {
        Connection writer = DriverManager.getConnection(jdbcUrl(file));
        boolean ok = false;
        try {
            applyPragmas(writer);
            ok = true;
        } finally {
            if (!ok) {
                writer.close();
            }
        }
        return new SessionDatabase(file, writer);
    }

    /** The single connection all writes must go through. Owned by this object; do not close it. */
    public Connection writerConnection() {
        return writer;
    }

    /**
     * Opens a new read connection. The caller should close it when done; any
     * still open when this database is closed are closed then.
     */
    public Connection newReadConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl(file));
        boolean ok = false;
        try {
            applyPragmas(connection);
            readConnections.add(connection);
            ok = true;
        } finally {
            if (!ok) {
                connection.close();
            }
        }
        return connection;
    }

    public Path file() {
        return file;
    }

    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        for (Connection read : readConnections) {
            try {
                if (!read.isClosed()) {
                    read.close();
                }
            } catch (SQLException e) {
                failure = suppress(failure, e);
            }
        }
        readConnections.clear();
        try {
            if (!writer.isClosed()) {
                writer.close();
            }
        } catch (SQLException e) {
            failure = suppress(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String jdbcUrl(Path file) {
        return "jdbc:sqlite:" + file.toAbsolutePath();
    }

    private static void applyPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // journal_mode is persistent per database file but setting it on
            // every connection is harmless and covers the very first open.
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            statement.execute("PRAGMA foreign_keys=ON");
        }
    }

    private static SQLException suppress(SQLException existing, SQLException next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }
}
