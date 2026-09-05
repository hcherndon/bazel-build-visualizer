package com.holtherndon.bazelviz.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

/**
 * A session's SQLite database file: one long-lived writer connection plus a factory for additional
 * read connections.
 *
 * <p>Operating rules (plan section 10.9): WAL journal mode so readers never block the writer,
 * {@code synchronous=NORMAL} (safe with WAL, far faster than FULL), a busy timeout so contended
 * connections wait instead of failing immediately, and foreign keys enforced. SQLite allows exactly
 * one writer at a time; all writes must go through {@link #writerConnection()}. Read connections
 * from {@link #newReadConnection()} may be used from any thread (one connection per thread —
 * connections themselves are not thread-safe).
 *
 * <p>{@link #newReadConnection()} is a <em>convention</em>: nothing about the connection it returns
 * stops a caller writing through it, and every caller in this codebase is trusted not to. {@link
 * #newQueryConnection()} is the connection for statements this codebase did not write — the ad hoc
 * query view — and it is read-only in fact rather than by agreement: opened with SQLite's {@code
 * SQLITE_OPEN_READONLY} flag and with {@code PRAGMA query_only = ON}.
 *
 * <p>Explicit SQL only; there is deliberately no ORM in this codebase.
 */
public final class SessionDatabase implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SessionDatabase.class);

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
    long startedNanos = System.nanoTime();
    log.debug("opening session database {}", file);
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
    log.debug("opened session database {} in {} ms", file, elapsedMillis(startedNanos));
    return new SessionDatabase(file, writer);
  }

  /** The single connection all writes must go through. Owned by this object; do not close it. */
  public Connection writerConnection() {
    return writer;
  }

  /**
   * Opens a new read connection. The caller should close it when done; any still open when this
   * database is closed are closed then.
   */
  public Connection newReadConnection() throws SQLException {
    long startedNanos = System.nanoTime();
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
    log.trace(
        "opened read connection for {} in {} ms ({} tracked)",
        file,
        elapsedMillis(startedNanos),
        readConnections.size());
    return connection;
  }

  /**
   * Opens a reader for graph validation and metadata. SQLite may need large legacy-database sorts
   * for these operations, so temporary b-trees are file-backed and cannot bypass the graph heap
   * admission policy as unbounded native memory.
   */
  public Connection newGraphReadConnection() throws SQLException {
    Connection connection = newReadConnection();
    boolean ok = false;
    try (Statement statement = connection.createStatement()) {
      // Graph bodies, models and operation scratch have their own aggregate budget. Keep the
      // native SQLite page cache for each independently opened graph reader small and fixed so it
      // cannot become a second graph-sized allowance outside that admission boundary.
      statement.execute("PRAGMA cache_size=-" + GRAPH_READER_PAGE_CACHE_KIB);
      statement.execute("PRAGMA temp_store=FILE");
      ok = true;
      return connection;
    } finally {
      if (!ok) {
        connection.close();
      }
    }
  }

  /**
   * Opens a connection that is incapable of writing, for statements this codebase did not author
   * (plan rules 15 and 22.4).
   *
   * <p>{@code SQLITE_OPEN_READONLY} at open time is the guarantee: a write fails in the VFS with
   * {@code SQLITE_READONLY} whatever the statement is, and the flag is fixed for the life of the
   * connection — no SQL can reach it.
   *
   * <p>{@code PRAGMA query_only = ON} is set as well, and it is a second refusal rather than a
   * second guarantee. It is connection state, so SQL <em>can</em> reach it: {@code EXPLAIN PRAGMA
   * query_only = OFF} clears it at prepare time. It is kept in place by the statement filter in
   * {@code ReadOnlySql} and re-checked before every execution by {@code AdHocQueries}; what it buys
   * is that a defect in that filter still has to get past a second thing, and is noticed when it
   * does.
   *
   * <p>A read-only open works against this database even while the writer holds it and the journal
   * is in WAL mode: SQLite needs write <em>permission</em> on the {@code -shm} file, which the
   * process has, rather than a read-write connection.
   *
   * <p>The writer-side pragmas are deliberately not applied here. {@code journal_mode}, {@code
   * synchronous} and {@code wal_autocheckpoint} describe how writes reach the disk, and this
   * connection performs none. {@code query_only} is set last, after the settings that are allowed
   * to change.
   *
   * <p>Tracked like any other read connection, so {@link #close()} releases it even if the caller
   * does not.
   */
  public Connection newQueryConnection() throws SQLException {
    long startedNanos = System.nanoTime();
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
    log.debug(
        "opened read-only query connection for {} in {} ms", file, elapsedMillis(startedNanos));
    return connection;
  }

  public Path file() {
    return file;
  }

  @Override
  public void close() throws SQLException {
    long startedNanos = System.nanoTime();
    int trackedReaders = readConnections.size();
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
    log.debug(
        "closed session database {} with {} tracked reader(s) in {} ms",
        file,
        trackedReaders,
        elapsedMillis(startedNanos));
  }

  /**
   * Page cache per connection, in kibibytes.
   *
   * <p>128 MB. Large enough to hold the working set of a multi-gigabyte table's insert path, small
   * enough that several open readers plus the writer stay far inside the application's own budget —
   * plan 20.2 allows 4 GB for a Tier 3 session and this is at most a few hundred megabytes across
   * every connection a session opens.
   */
  private static final int PAGE_CACHE_KIB = 128 * 1024;

  /** Native SQLite page cache retained by each graph-only read connection. */
  private static final int GRAPH_READER_PAGE_CACHE_KIB = 1_024;

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

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
