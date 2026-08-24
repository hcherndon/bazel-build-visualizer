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
 * <h2>One guarantee, and the guards that keep the rest true</h2>
 *
 * <p><b>The guarantee.</b> The connection is opened with SQLite's
 * {@code SQLITE_OPEN_READONLY} flag ({@link SessionDatabase#newQueryConnection()}),
 * so every write is refused by the VFS with {@code SQLITE_READONLY} whatever
 * the statement is. That flag is fixed at open time and is not reachable from
 * SQL: nothing typed into the query card can change a byte of the session.
 *
 * <p><b>The guards.</b> {@code PRAGMA query_only = ON} is also set, and it is
 * a genuinely useful second refusal — but it is <em>connection state</em>, and
 * connection state is reachable from SQL. {@code EXPLAIN PRAGMA query_only =
 * OFF} clears it, because SQLite applies flag pragmas in
 * {@code sqlite3Pragma()} at prepare time and {@code EXPLAIN} does not
 * suppress that. So {@code query_only} is only as durable as the statement
 * filter in front of it, and two things follow:
 *
 * <ol>
 *   <li>{@link ReadOnlySql} treats {@code EXPLAIN} as transparent and checks
 *       what follows it against the pragma allowlist. It also refuses text
 *       that is not a <em>single</em> statement, which neither the open mode
 *       nor the pragma would have done — {@code Statement.execute} runs
 *       everything in a semicolon-joined string.
 *   <li>{@link #requireStillReadOnly()} re-reads {@code query_only} before
 *       <em>every</em> execution rather than once at construction. A one-time
 *       assertion about a value a later statement can change is not a
 *       refusal; a per-execution one is.
 * </ol>
 *
 * <p>The constructor still refuses a connection that can write at all — handed
 * the writer, or a plain {@link SessionDatabase#newReadConnection()}, it
 * throws. Rule 22.4 asks for incapable, not trusted.
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

    /**
     * Names of the temp views {@link #applyTempViews} currently maintains on
     * this connection, so a replay can drop exactly what it created — and only
     * that: views the user typed into the editor are not its to drop.
     */
    private final List<String> appliedTempViews = new ArrayList<>();

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

    /**
     * Re-establishes, before every execution, that this connection is still
     * refusing writes.
     *
     * <p>The constructor's check is an assertion about a moment.
     * {@code query_only} is connection state and a prepared statement can
     * clear it — {@code EXPLAIN PRAGMA query_only = OFF} does, at prepare
     * time — so a check made once is a claim about the past. This makes it a
     * claim about now.
     *
     * <p>It does not <em>prevent</em> the clearing; {@link ReadOnlySql} does
     * that. What it does is refuse to keep running on a connection whose
     * second refusal has gone missing, and put the refusal back before it
     * throws, so a defect in the filter surfaces as a loud failure on the next
     * statement instead of a quietly weakened connection. The write guarantee
     * is unaffected either way: the open mode is not reachable from SQL.
     *
     * <p>Costs one flag read per execution — no I/O, no page cache traffic.
     */
    private void requireStillReadOnly() {
        boolean openedReadOnly;
        String queryOnly;
        try {
            openedReadOnly = connection.isReadOnly();
            queryOnly = scalarText(connection, "PRAGMA query_only");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "cannot establish that this connection is still read-only", e);
        }
        if (openedReadOnly && "1".equals(queryOnly)) {
            return;
        }
        String restored = "not attempted";
        if (openedReadOnly) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                restored = "it has been switched back on";
            } catch (SQLException e) {
                restored = "and it could not be switched back on: " + e.getMessage();
            }
        }
        throw new IllegalStateException("this connection stopped refusing writes between"
                + " statements: opened read-only = " + openedReadOnly
                + ", PRAGMA query_only = " + queryOnly + " (" + restored + "). Nothing was run."
                + " The session file itself was never at risk -- it is open"
                + " SQLITE_OPEN_READONLY -- but some statement got past the read-only"
                + " statement filter, which is a defect worth reporting.");
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
            case DEFINE -> describeDefine(
                    checked.sql(), checked.tempViewName(), rowLimit, started);
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
     * Runs a validated {@code CREATE TEMP VIEW}, redefining an existing view
     * of the same name rather than refusing it.
     *
     * <p>The temp schema is a different database from the session file — it is
     * per-connection, it dies with the connection, and it stays writable even
     * though the main database is opened {@code SQLITE_OPEN_READONLY}. What
     * does refuse it is {@code query_only}, whose refusal is connection-wide
     * rather than per-schema, so this method lifts that one flag for exactly
     * the DROP-and-CREATE pair and puts it back in a {@code finally}. The
     * write <em>guarantee</em> is untouched throughout: the open mode is not
     * reachable from SQL, and {@code AdHocQueriesTest} proves a main-schema
     * CREATE still fails while the flag is down.
     *
     * <p>The DROP is qualified {@code temp.} so it can never name a main
     * schema view — not that the open mode would let it drop one.
     */
    private QueryOutline describeDefine(String sql, String viewName, long rowLimit, long started) {
        execute(QueryFailedException.Stage.DEFINE, sql, statement -> {
            withTempSchemaWritable(statement, () -> {
                statement.execute("DROP VIEW IF EXISTS temp." + quoteIdentifier(viewName));
                statement.execute(sql);
            });
            return null;
        });
        return new QueryOutline(
                sql,
                ReadOnlySql.Shape.DEFINE,
                List.of(),
                OptionalLong.of(0),
                0,
                rowLimit,
                System.nanoTime() - started,
                List.of());
    }

    /**
     * Replaces this connection's replayed temp views with {@code views}.
     *
     * <p>Called when a reader opens (replaying the saved definitions) and when
     * the saved set changes (a rename, an edit, a delete). Every view this
     * method defined previously is dropped first, so the connection ends up
     * holding exactly the given set — a rename does not leave its old name
     * behind. Views the user defined by typing {@code CREATE TEMP VIEW} into
     * the editor are not tracked here and are left alone.
     *
     * <p>A definition that cannot be applied — a body that fails
     * {@link ReadOnlySql}, or one SQLite refuses — is reported in the returned
     * list and skipped, rather than failing the rest: saved views are user
     * data, and one broken file must not take the whole query surface down.
     *
     * @return one human-readable problem per definition that was not applied;
     *     empty when all of them were
     */
    public List<String> applyTempViews(List<TempViewDefinition> views) {
        Objects.requireNonNull(views, "views");
        List<String> problems = new ArrayList<>();
        List<TempViewDefinition> valid = new ArrayList<>();
        for (TempViewDefinition view : views) {
            try {
                ReadOnlySql.Statement checked = ReadOnlySql.check(view.select());
                if (checked.shape() != ReadOnlySql.Shape.TABULAR) {
                    problems.add("view \"" + view.name()
                            + "\": its body must be a SELECT, WITH or VALUES,"
                            + " not a statement of its own");
                    continue;
                }
                valid.add(view);
            } catch (SqlNotAllowedException refused) {
                problems.add("view \"" + view.name() + "\": " + refused.getMessage());
            }
        }
        execute(QueryFailedException.Stage.DEFINE, "replaying saved temp views", statement -> {
            withTempSchemaWritable(statement, () -> {
                for (String name : appliedTempViews) {
                    statement.execute("DROP VIEW IF EXISTS temp." + quoteIdentifier(name));
                }
                appliedTempViews.clear();
                for (TempViewDefinition view : valid) {
                    try {
                        statement.execute(
                                "DROP VIEW IF EXISTS temp." + quoteIdentifier(view.name()));
                        statement.execute("CREATE TEMP VIEW " + quoteIdentifier(view.name())
                                + " AS " + view.select());
                        appliedTempViews.add(view.name());
                    } catch (SQLException refused) {
                        problems.add("view \"" + view.name() + "\": " + refused.getMessage());
                    }
                }
            });
            return null;
        });
        return List.copyOf(problems);
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    /**
     * Runs {@code work} with {@code query_only} lifted, and puts it back
     * whatever happens.
     *
     * <p>The one guarantee — the main database cannot be written — is the open
     * mode and holds throughout; {@code query_only} is the defence-in-depth
     * refusal, and it is connection-wide, so writing the (legitimately
     * writable) temp schema requires lifting it briefly. Restoring in
     * {@code finally} means even a failed CREATE leaves the refusal in place;
     * and if the restore itself failed, {@link #requireStillReadOnly()} refuses
     * the next statement loudly rather than running on a weakened connection.
     */
    private void withTempSchemaWritable(Statement statement, SqlWork work) throws SQLException {
        statement.execute("PRAGMA query_only=OFF");
        try {
            work.run();
        } finally {
            statement.execute("PRAGMA query_only=ON");
        }
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
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
        // Temp views live in temp.sqlite_master, a different catalog from the
        // session's own: they exist on this connection alone and vanish with
        // it. Listing them here is what lets the schema browser show the views
        // this tab's connection actually has — and only views, because views
        // are the one temp object this surface can create.
        String listing = "SELECT name, type, COALESCE(sql, ''), 'main' FROM sqlite_master"
                + " WHERE type IN ('table', 'view')"
                + " UNION ALL"
                + " SELECT name, type, COALESCE(sql, ''), 'temp' FROM temp.sqlite_master"
                + " WHERE type = 'view'"
                + " ORDER BY 4, 2, 1";
        record Entry(String name, String kind, String schema, String ddl) { }
        List<Entry> entries = execute(QueryFailedException.Stage.SCHEMA, listing, statement -> {
            try (ResultSet rows = statement.executeQuery(listing)) {
                List<Entry> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(new Entry(rows.getString(1), rows.getString(2),
                            rows.getString(4), rows.getString(3)));
                }
                return found;
            }
        });
        List<SchemaTable> tables = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            tables.add(new SchemaTable(entry.name(), entry.kind(), entry.schema(),
                    columnsOf(entry.name(), entry.schema()), entry.ddl()));
        }
        return List.copyOf(tables);
    }

    private List<SchemaColumn> columnsOf(String table, String schema) {
        // table_info takes no bind parameter, so the name is quoted into the
        // statement. It came from sqlite_master a moment ago, and doubling any
        // embedded quote is what keeps that true even for a table somebody
        // named with one. The schema qualifier is this codebase's own literal
        // ('main' or 'temp', from the UNION above), and it matters: a temp
        // view can shadow a main table's name, and an unqualified table_info
        // would then describe the shadow both times.
        String sql = "PRAGMA " + schema + ".table_info(\""
                + table.replace("\"", "\"\"") + "\")";
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
        requireStillReadOnly();
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
