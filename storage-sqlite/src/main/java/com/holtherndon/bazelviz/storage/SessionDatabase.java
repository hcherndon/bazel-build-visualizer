package com.holtherndon.bazelviz.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.sqlite.SQLiteConfig;

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
 * <p>{@link #newReadConnection()} is a <em>convention</em>: nothing about the
 * connection it returns stops a caller writing through it, and every caller in
 * this codebase is trusted not to. {@link #newQueryConnection()} is the
 * connection for statements this codebase did not write — the ad hoc query
 * view — and it is read-only in fact rather than by agreement: opened with
 * SQLite's {@code SQLITE_OPEN_READONLY} flag and with
 * {@code PRAGMA query_only = ON}.
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

    /**
     * Opens a connection that is incapable of writing, for statements this
     * codebase did not author (plan rules 15 and 22.4).
     *
     * <p>Two independent refusals, because either alone is one mistake away
     * from being undone:
     *
     * <ul>
     *   <li>{@code SQLITE_OPEN_READONLY} at open time, so a write fails in the
     *       VFS with {@code SQLITE_READONLY} whatever the statement is;
     *   <li>{@code PRAGMA query_only = ON}, which refuses a write when the
     *       statement is prepared, and which survives being handed to code that
     *       only has the {@link Connection}.
     * </ul>
     *
     * <p>A read-only open works against this database even while the writer
     * holds it and the journal is in WAL mode: SQLite needs write
     * <em>permission</em> on the {@code -shm} file, which the process has,
     * rather than a read-write connection.
     *
     * <p>The writer-side pragmas are deliberately not applied here.
     * {@code journal_mode}, {@code synchronous} and {@code wal_autocheckpoint}
     * describe how writes reach the disk, and this connection performs none.
     * {@code query_only} is set last, after the settings that are allowed to
     * change.
     *
     * <p>Tracked like any other read connection, so {@link #close()} releases
     * it even if the caller does not.
     */
    public Connection newQueryConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection(jdbcUrl(file), config.toProperties());
        boolean ok = false;
        try {
            applyQueryPragmas(connection);
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

    /**
     * Page cache per connection, in kibibytes.
     *
     * <p>128 MB. Large enough to hold the working set of a multi-gigabyte
     * table's insert path, small enough that several open readers plus the
     * writer stay far inside the application's own budget — plan 20.2 allows
     * 4 GB for a Tier 3 session and this is at most a few hundred megabytes
     * across every connection a session opens.
     */
    private static final int PAGE_CACHE_KIB = 128 * 1024;

    /** WAL pages between automatic checkpoints; 4,000 pages is about 16 MB. */
    private static final int WAL_AUTOCHECKPOINT_PAGES = 4_000;

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
            // Page cache, in kibibytes (the negative form). SQLite's default is
            // about 2 MB, which is fine for a small session and is the reason a
            // large one slows down: once the b-tree stops fitting, every insert
            // is a random read of a page that has been evicted. Measured on the
            // capture path at three million events -- see docs/performance.md.
            statement.execute("PRAGMA cache_size=-" + PAGE_CACHE_KIB);
            // Sorts and temporary b-trees stay in memory rather than becoming
            // files in the system temp directory, which the index build at
            // finalization is the heaviest user of.
            statement.execute("PRAGMA temp_store=MEMORY");
            // The default checkpoint every 1,000 pages (about 4 MB) means a
            // capture writing gigabytes checkpoints thousands of times, each
            // one a pass over the WAL. Sixteen megabytes is still bounded and
            // is a quarter of the checkpoints.
            statement.execute("PRAGMA wal_autocheckpoint=" + WAL_AUTOCHECKPOINT_PAGES);
        }
    }

    private static void applyQueryPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            statement.execute("PRAGMA cache_size=-" + PAGE_CACHE_KIB);
            statement.execute("PRAGMA temp_store=MEMORY");
            // Last. Everything above it changes connection state, which is
            // exactly what query_only stops.
            statement.execute("PRAGMA query_only=ON");
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
