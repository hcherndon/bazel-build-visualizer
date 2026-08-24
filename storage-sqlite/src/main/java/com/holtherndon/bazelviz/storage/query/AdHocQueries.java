package com.holtherndon.bazelviz.storage.query;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs one user-written read-only statement against an open session database
 * and pages its results — the query surface Perfetto's query page provides for
 * traces, over the 59 tables a captured build normalizes into.
 *
 * <h2>The connection cannot write, three ways</h2>
 *
 * <ol>
 *   <li>The connection is opened with SQLite's {@code SQLITE_OPEN_READONLY}
 *       flag ({@link SessionDatabase#newQueryConnection()}), so a write is
 *       refused by the VFS with {@code SQLITE_READONLY} before the statement
 *       ever runs.
 *   <li>{@code PRAGMA query_only = ON} is set on it, which refuses a write at
 *       statement-prepare time even if the open mode were ever relaxed.
 *   <li>{@link ReadOnlySql} refuses text that is not a single read-only
 *       statement, because neither of the first two stops
 *       {@code Statement.execute} from running a second statement out of a
 *       semicolon-joined string.
 *   </ol>
 *
 * <p>The constructor <em>verifies</em> the first two rather than assuming them:
 * handed the writer connection, it throws. Rule 22.4 asks for incapable, not
 * trusted.
 *
 * <h2>Paging: LIMIT/OFFSET, deliberately</h2>
 *
 * <p>The actions and events tables page by keyset, because {@code OFFSET} makes
 * SQLite walk and discard every skipped row and their cost then grows with
 * scroll depth (docs/performance.md). That technique needs a known sort key and
 * a unique tiebreaker, and an arbitrary user query exposes neither: its
 * {@code ORDER BY} may be over an expression, over an alias, or absent
 * altogether. There is no anchor to seek to, so there is no keyset form to
 * write. {@code SELECT * FROM (…) LIMIT ? OFFSET ?} is therefore not the lazy
 * choice, it is the only correct one — and the cost model is honest for the use
 * case, which is one human reading one query rather than a viewport being
 * scrubbed.
 *
 * <p>Wrapping the user's statement in a subquery rather than appending to it
 * also means a query that already ends in its own {@code LIMIT} still pages
 * correctly, and an {@code ORDER BY} inside the subquery is preserved across
 * pages.
 *
 * <h2>Threading</h2>
 *
 * <p>One instance wraps one connection and is not thread-safe; give each reader
 * thread its own. The exceptions are {@link #cancel()} and the runaway-query
 * deadline, both of which interrupt from another thread — the same arrangement
 * {@code EventQueries} uses. None of this may run on the Swing EDT.
 */
public final class AdHocQueries implements AutoCloseable {

    /**
     * Rows an ad hoc query's grid will address before it says it is capped.
     *
     * <p>One million. Not a memory bound — nothing is materialized, so memory
     * is set by the page cache and not by this — but a bound on how much of a
     * result the grid claims to be a view of, and on how far {@code OFFSET} is
     * ever asked to walk. A million rows is past anything a person reads and
     * far short of {@code Integer.MAX_VALUE}, which is where {@code JTable}'s
     * int-based row geometry stops working at all. When a query matches more,
     * the panel says so with both numbers and offers to raise the cap; nothing
     * is silently dropped (rule 12).
     */
    public static final int DEFAULT_ROW_LIMIT = 1_000_000;

    /**
     * Seconds a single execution may run before it is interrupted.
     *
     * <p>Sixty. A count over a five-million-action join can legitimately take
     * tens of seconds and killing it at five would make the feature useless; a
     * query still running after a minute is one the person who typed it has
     * stopped waiting for. The deadline is not a substitute for the Cancel
     * button, it is the backstop for a window nobody is watching, and both
     * report themselves distinguishably from a SQL error
     * ({@link QueryFailedException#wasStopped()}).
     *
     * <p>Implemented with a scheduled {@link Statement#cancel()}, which
     * sqlite-jdbc maps onto {@code sqlite3_interrupt}. JDBC's own
     * {@code setQueryTimeout} is deliberately not used: in sqlite-jdbc
     * 3.53.2.1 it only sets the <em>busy</em> timeout, which bounds how long a
     * statement waits for a lock and does nothing at all to a statement that is
     * running.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Rows the driver is asked to buffer per fetch, as {@code TableExport} does. */
    private static final int FETCH = 1_000;

    /**
     * One daemon thread for every deadline in the process.
     *
     * <p>A deadline fires {@code sqlite3_interrupt}, which returns immediately,
     * so this thread never does work of its own and one is enough. Daemon
     * because a pending deadline must not keep the application alive.
     */
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "bbv-query-deadline");
                thread.setDaemon(true);
                return thread;
            });

    private final Connection connection;
    private final int timeoutSeconds;

    private volatile Statement active;
    private volatile boolean cancelRequested;
    private volatile boolean deadlineFired;
    private boolean closed;

    /** Wraps a caller-owned read-only connection with the default deadline. */
    public AdHocQueries(Connection connection) {
        this(connection, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Wraps a caller-owned connection.
     *
     * @param timeoutSeconds deadline for one execution; 0 disables it
     * @throws IllegalArgumentException if the connection can write
     */
    public AdHocQueries(Connection connection, int timeoutSeconds) {
        this.connection = Objects.requireNonNull(connection, "connection");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 0: " + timeoutSeconds);
        }
        this.timeoutSeconds = timeoutSeconds;
        requireReadOnly(connection);
    }

    /**
     * Refuses a connection that could write.
     *
     * <p>Checked, not assumed. The one-writer rule elsewhere in this codebase
     * is a convention enforced by callers; here the whole point is that the
     * statement is written by somebody who is not a caller.
     */
    private static void requireReadOnly(Connection connection) {
        boolean openedReadOnly;
        String queryOnly;
        try {
            openedReadOnly = connection.isReadOnly();
            queryOnly = scalarText(connection, "PRAGMA query_only");
        } catch (SQLException e) {
            throw new IllegalArgumentException(
                    "cannot establish that this connection is read-only", e);
        }
        if (!openedReadOnly) {
            throw new IllegalArgumentException("ad hoc queries need a connection opened read-only;"
                    + " this one was not. Use SessionDatabase.newQueryConnection().");
        }
        if (!"1".equals(queryOnly)) {
            throw new IllegalArgumentException("ad hoc queries need PRAGMA query_only = ON;"
                    + " this connection reports " + queryOnly
                    + ". Use SessionDatabase.newQueryConnection().");
        }
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    /**
     * Interrupts whatever is running, from another thread.
     *
     * <p>Cheap and non-blocking: it is {@code sqlite3_interrupt}, so calling it
     * from the EDT is safe and is how the Cancel button works. The interrupted
     * call throws {@link QueryFailedException} with
     * {@link QueryFailedException#wasStopped()} true; the connection stays
     * usable for the next query.
     */
    public void cancel() {
        cancelRequested = true;
        Statement running = active;
        if (running != null) {
            try {
                running.cancel();
            } catch (SQLException ignored) {
                // It finished between the read and the cancel. Nothing to stop
                // and nothing a caller could do about it.
            }
        }
    }

    /**
     * Validates {@code submitted}, reads its result columns and counts its
     * rows.
     *
     * <p>Blocking, and the slow half of the feature: the count is a full pass.
     *
     * @param rowLimit rows the grid may address; see {@link #DEFAULT_ROW_LIMIT}
     * @throws SqlNotAllowedException if the text is not one read-only statement
     * @throws QueryFailedException if SQLite refuses it or it is stopped
     */
    public QueryOutline describe(String submitted, long rowLimit) {
        if (rowLimit <= 0) {
            throw new IllegalArgumentException("rowLimit must be positive: " + rowLimit);
        }
        ReadOnlySql.Statement checked = ReadOnlySql.check(submitted);
        long started = System.nanoTime();
        return switch (checked.shape()) {
            case TABULAR -> describeTabular(checked.sql(), rowLimit, started);
            case DIRECT -> describeDirect(checked.sql(), rowLimit, started);
        };
    }

    private QueryOutline describeTabular(String sql, long rowLimit, long started) {
        String probe = "SELECT * FROM (" + sql + ") LIMIT 0";
        List<String> columns = execute(QueryFailedException.Stage.COLUMNS, probe, statement -> {
            try (ResultSet rows = statement.executeQuery(probe)) {
                return labelsOf(rows.getMetaData());
            }
        });
        String counting = "SELECT COUNT(*) FROM (" + sql + ")";
        long matched = execute(QueryFailedException.Stage.COUNT, counting, statement -> {
            try (ResultSet rows = statement.executeQuery(counting)) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        });
        return new QueryOutline(
                sql,
                ReadOnlySql.Shape.TABULAR,
                columns,
                OptionalLong.of(matched),
                Math.min(matched, rowLimit),
                rowLimit,
                System.nanoTime() - started,
                List.of());
    }

    /**
     * Runs an EXPLAIN or an introspection PRAGMA and holds its rows.
     *
     * <p>These cannot be wrapped in {@code SELECT … FROM (…)}, so there is no
     * way to count them without producing them and no way to page them without
     * re-running the statement. Holding them is bounded because their size
     * comes from the schema or from the query text — one row per column, one
     * row per VM instruction — never from how large the build was. The cap
     * still applies, and a statement that reaches it reports its total as
     * unknown rather than as the number of rows that happened to be read.
     */
    private QueryOutline describeDirect(String sql, long rowLimit, long started) {
        record Held(List<String> columns, List<QueryRow> rows, boolean more) { }
        Held held = execute(QueryFailedException.Stage.PAGE, sql, statement -> {
            try (ResultSet rows = statement.executeQuery(sql)) {
                List<String> columns = labelsOf(rows.getMetaData());
                List<QueryRow> read = new ArrayList<>();
                while (read.size() < rowLimit && rows.next()) {
                    read.add(QueryRow.read(rows, columns.size()));
                }
                return new Held(columns, read, rows.next());
            }
        });
        return new QueryOutline(
                sql,
                ReadOnlySql.Shape.DIRECT,
                held.columns(),
                held.more() ? OptionalLong.empty() : OptionalLong.of(held.rows().size()),
                held.rows().size(),
                rowLimit,
                System.nanoTime() - started,
                held.rows());
    }

    /**
     * The rows of {@code outline} from {@code offset}, at most {@code limit} of
     * them.
     *
     * <p>Blocking. Returns fewer rows than asked for at the end of the result,
     * and none at all past it.
     */
    public List<QueryRow> page(QueryOutline outline, long offset, int limit) {
        Objects.requireNonNull(outline, "outline");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0: " + offset);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        // Never past the cap the outline was sized for: the grid addresses
        // exactly visibleRows rows and a page beyond it would be rows the
        // status line already said were not being shown.
        long remaining = outline.visibleRows() - offset;
        if (remaining <= 0) {
            return List.of();
        }
        int want = (int) Math.min(limit, remaining);
        if (outline.shape() == ReadOnlySql.Shape.DIRECT) {
            int from = (int) Math.min(offset, outline.materialized().size());
            int to = (int) Math.min((long) from + want, outline.materialized().size());
            return List.copyOf(outline.materialized().subList(from, to));
        }
        String sql = "SELECT * FROM (" + outline.statement() + ") LIMIT ? OFFSET ?";
        return execute(QueryFailedException.Stage.PAGE, sql, statement -> {
            try (PreparedStatement prepared = connection.prepareStatement(sql)) {
                prepared.setFetchSize(FETCH);
                prepared.setInt(1, want);
                prepared.setLong(2, offset);
                adopt(prepared);
                try (ResultSet rows = prepared.executeQuery()) {
                    int columns = rows.getMetaData().getColumnCount();
                    List<QueryRow> page = new ArrayList<>(want);
                    while (rows.next()) {
                        page.add(QueryRow.read(rows, columns));
                    }
                    return page;
                }
            }
        });
    }

    /**
     * Every table and view in the open database, with its columns, read from
     * {@code sqlite_master} and {@code PRAGMA table_info}.
     *
     * <p>Blocking, but bounded by the schema rather than the build: 59 tables
     * and a few hundred columns on a current session.
     */
    public List<SchemaTable> schema() {
        String listing = "SELECT name, type, COALESCE(sql, '') FROM sqlite_master"
                + " WHERE type IN ('table', 'view') ORDER BY type, name";
        record Entry(String name, String kind, String ddl) { }
        List<Entry> entries = execute(QueryFailedException.Stage.SCHEMA, listing, statement -> {
            try (ResultSet rows = statement.executeQuery(listing)) {
                List<Entry> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Entry(rows.getString(1), rows.getString(2), rows.getString(3)));
                }
                return found;
            }
        });
        List<SchemaTable> tables = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            tables.add(new SchemaTable(
                    entry.name(), entry.kind(), columnsOf(entry.name()), entry.ddl()));
        }
        return List.copyOf(tables);
    }

    private List<SchemaColumn> columnsOf(String table) {
        // table_info takes no bind parameter, so the name is quoted into the
        // statement. It came from sqlite_master a moment ago, and doubling any
        // embedded quote is what keeps that true even for a table somebody
        // named with one.
        String sql = "PRAGMA table_info(\"" + table.replace("\"", "\"\"") + "\")";
        return execute(QueryFailedException.Stage.SCHEMA, sql, statement -> {
            try (ResultSet rows = statement.executeQuery(sql)) {
                List<SchemaColumn> columns = new ArrayList<>();
                while (rows.next()) {
                    columns.add(new SchemaColumn(
                            rows.getString("name"),
                            valueOrEmpty(rows.getString("type")),
                            rows.getInt("notnull") != 0,
                            rows.getInt("pk")));
                }
                return columns;
            }
        });
    }

    private static String valueOrEmpty(String text) {
        return text == null ? "" : text;
    }

    private static List<String> labelsOf(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add(meta.getColumnLabel(i));
        }
        return labels;
    }

    @Override
    public void close() {
        closed = true;
    }

    // ------------------------------------------------------------- execution

    @FunctionalInterface
    private interface Body<T> {
        T run(Statement statement) throws SQLException;
    }

    /**
     * Runs {@code body} with a live deadline and a cancellable statement.
     *
     * <p>The {@link Statement} handed to the body is the one {@link #cancel()}
     * interrupts. A body that prepares its own statement replaces it with
     * {@link #adopt}, so the deadline and the button both point at whatever is
     * really running.
     */
    private <T> T execute(QueryFailedException.Stage stage, String sql, Body<T> body) {
        if (closed) {
            throw new IllegalStateException("these queries are closed");
        }
        cancelRequested = false;
        deadlineFired = false;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(FETCH);
            active = statement;
            ScheduledFuture<?> deadline = scheduleDeadline();
            try {
                return body.run(statement);
            } finally {
                if (deadline != null) {
                    deadline.cancel(false);
                }
                active = null;
            }
        } catch (SQLException failure) {
            throw new QueryFailedException(
                    stage, sql, cancelRequested || deadlineFired, failure);
        }
    }

    /** Points {@link #cancel()} and the deadline at a body's own statement. */
    private void adopt(Statement statement) {
        active = statement;
        if (cancelRequested) {
            // Cancel arrived between createStatement and here; without this the
            // interrupt would land on a statement that never ran.
            cancel();
        }
    }

    private ScheduledFuture<?> scheduleDeadline() {
        if (timeoutSeconds <= 0) {
            return null;
        }
        return DEADLINES.schedule(() -> {
            deadlineFired = true;
            Statement running = active;
            if (running != null) {
                try {
                    running.cancel();
                } catch (SQLException ignored) {
                    // Finished on its own; the deadline has nothing to do.
                }
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    /** For messages: the deadline this instance enforces, in seconds. */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "AdHocQueries[timeout=%ds]", timeoutSeconds);
    }
}
