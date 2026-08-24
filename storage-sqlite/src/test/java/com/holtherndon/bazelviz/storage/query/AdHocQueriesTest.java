package com.holtherndon.bazelviz.storage.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ad hoc query path against a real session database.
 *
 * <p>The write-refusal tests here matter more than the rest put together: the
 * feature's whole premise is that somebody else's SQL cannot damage the
 * session, and that is a claim about a running connection rather than about a
 * regex.
 */
final class AdHocQueriesTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection queryConnection;
    private AdHocQueries queries;

    /** A statement that runs for far longer than any test wants to wait. */
    private static final String SLOW_QUERY =
            "WITH RECURSIVE counter(x) AS ("
                    + " SELECT 1 UNION ALL SELECT x + 1 FROM counter WHERE x < 2000000000)"
                    + " SELECT COUNT(*) FROM counter";

    @BeforeEach
    void setUp() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        try (Statement statement = database.writerConnection().createStatement()) {
            statement.execute(
                    "CREATE TABLE actions (id INTEGER PRIMARY KEY, mnemonic TEXT NOT NULL,"
                            + " duration_micros INTEGER, note TEXT, payload BLOB)");
            statement.execute("CREATE VIEW slow_actions AS SELECT * FROM actions"
                    + " WHERE duration_micros > 100");
            for (int i = 1; i <= 20; i++) {
                statement.execute("INSERT INTO actions VALUES (" + i + ", 'Javac', "
                        + (i * 10) + ", " + (i % 2 == 0 ? "NULL" : "''") + ", NULL)");
            }
            statement.execute("UPDATE actions SET payload = x'0102030405' WHERE id = 1");
        }
        queryConnection = database.newQueryConnection();
        queries = new AdHocQueries(queryConnection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (queries != null) {
            queries.close();
        }
        if (database != null) {
            database.close();
        }
    }

    // ------------------------------------------------- the connection cannot write

    @Test
    @DisplayName("an INSERT on the query connection is refused by SQLite itself")
    void theQueryConnectionRefusesAWrite() throws Exception {
        // Not through the validator: straight at the connection, which is the
        // only thing that proves the pragma and the open mode, rather than the
        // regex, are doing the work.
        try (Statement statement = queryConnection.createStatement()) {
            assertThatThrownBy(() ->
                    statement.execute("INSERT INTO actions VALUES (999, 'Evil', 1, NULL, NULL)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
        try (Statement statement = queryConnection.createStatement()) {
            assertThatThrownBy(() ->
                    statement.execute("UPDATE actions SET mnemonic = 'Evil'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
        try (Statement statement = queryConnection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("DROP TABLE actions"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
        // And nothing changed.
        assertThat(scalar("SELECT COUNT(*) FROM actions")).isEqualTo(20L);
        assertThat(scalar("SELECT COUNT(*) FROM actions WHERE mnemonic = 'Evil'")).isZero();
    }

    @Test
    @DisplayName("both refusals are in place, not just one")
    void bothRefusalsAreInPlace() throws Exception {
        assertThat(queryConnection.isReadOnly())
                .as("SQLITE_OPEN_READONLY at open time")
                .isTrue();
        try (Statement statement = queryConnection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA query_only")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).as("PRAGMA query_only").isEqualTo("1");
        }
    }

    @Test
    @DisplayName("a writable connection is refused rather than trusted")
    void aWritableConnectionIsRefused() throws Exception {
        assertThatThrownBy(() -> new AdHocQueries(database.writerConnection()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
        try (Connection plainRead = database.newReadConnection()) {
            // newReadConnection is a convention, not a guarantee, and this is
            // where that distinction is made load-bearing.
            assertThatThrownBy(() -> new AdHocQueries(plainRead))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("read-only");
        }
    }

    @Test
    @DisplayName("the app layer refuses a semicolon-joined write the pragma would have run")
    void twoStatementsAreRefusedBeforeExecution() {
        assertThatThrownBy(() -> queries.describe("SELECT 1; DROP TABLE actions", 100))
                .isInstanceOf(SqlNotAllowedException.class)
                .hasMessageContaining("Run one statement at a time");
        assertThatThrownBy(() -> queries.describe("DELETE FROM actions", 100))
                .isInstanceOf(SqlNotAllowedException.class);
    }

    // ---------------------------------------------------------------- describing

    @Test
    void describeReportsColumnsAndAnExactCount() {
        QueryOutline outline = queries.describe(
                "SELECT id, mnemonic, duration_micros FROM actions WHERE id > 5", 1000);

        assertThat(outline.columns()).containsExactly("id", "mnemonic", "duration_micros");
        assertThat(outline.matchedRows()).isEqualTo(OptionalLong.of(15L));
        assertThat(outline.visibleRows()).isEqualTo(15L);
        assertThat(outline.isCapped()).isFalse();
        assertThat(outline.shape()).isEqualTo(ReadOnlySql.Shape.TABULAR);
        assertThat(outline.materialized()).isEmpty();
        assertThat(outline.elapsedNanos()).isPositive();
    }

    @Test
    @DisplayName("a query that matches more rows than the cap says so and does not lie about it")
    void theCapIsReportedWithBothNumbers() {
        QueryOutline outline = queries.describe("SELECT id FROM actions ORDER BY id", 4);

        assertThat(outline.matchedRows()).isEqualTo(OptionalLong.of(20L));
        assertThat(outline.visibleRows()).isEqualTo(4L);
        assertThat(outline.rowLimit()).isEqualTo(4L);
        assertThat(outline.isCapped()).isTrue();
        // The grid addresses four rows and no page reaches past them.
        assertThat(queries.page(outline, 0, 10)).hasSize(4);
        assertThat(queries.page(outline, 4, 10)).isEmpty();
    }

    // -------------------------------------------------------------------- paging

    @Test
    @DisplayName("pages tile the result and an ORDER BY survives the wrapping")
    void pagesTileTheResultInOrder() {
        QueryOutline outline = queries.describe(
                "SELECT id FROM actions ORDER BY id DESC", AdHocQueries.DEFAULT_ROW_LIMIT);

        assertThat(first(queries.page(outline, 0, 3))).containsExactly(20L, 19L, 18L);
        assertThat(first(queries.page(outline, 3, 3))).containsExactly(17L, 16L, 15L);
        assertThat(first(queries.page(outline, 18, 5)))
                .as("the last page is short rather than padded")
                .containsExactly(2L, 1L);
        assertThat(queries.page(outline, 20, 5)).isEmpty();
    }

    @Test
    @DisplayName("a query with its own LIMIT still pages, because the wrapping is a subquery")
    void aQueryWithItsOwnLimitStillPages() {
        QueryOutline outline = queries.describe(
                "SELECT id FROM actions ORDER BY id LIMIT 5", AdHocQueries.DEFAULT_ROW_LIMIT);

        assertThat(outline.matchedRows()).isEqualTo(OptionalLong.of(5L));
        assertThat(first(queries.page(outline, 0, 3))).containsExactly(1L, 2L, 3L);
        assertThat(first(queries.page(outline, 3, 3))).containsExactly(4L, 5L);
    }

    // ------------------------------------------------------------- rule 11: NULL

    @Test
    @DisplayName("a SQL NULL arrives as null, not as 0 and not as an empty string")
    void nullIsNotZeroAndNotEmpty() {
        QueryOutline outline = queries.describe(
                "SELECT id, note, payload FROM actions WHERE id IN (1, 2) ORDER BY id",
                AdHocQueries.DEFAULT_ROW_LIMIT);
        List<QueryRow> rows = queries.page(outline, 0, 10);

        QueryRow odd = rows.get(0);
        QueryRow even = rows.get(1);
        assertThat(odd.isNull(1)).as("id 1 stores an empty string, which is not NULL").isFalse();
        assertThat(odd.value(1)).isEqualTo("");
        assertThat(even.isNull(1)).as("id 2 stores NULL").isTrue();
        assertThat(even.value(1)).isNull();
        // A NULL integer column must not arrive as 0 either.
        assertThat(even.value(2)).isNull();
        assertThat(even.isNull(2)).isTrue();
    }

    @Test
    @DisplayName("a BLOB reports its exact length rather than rendering as [B@...")
    void blobsCarryTheirLength() {
        QueryOutline outline = queries.describe(
                "SELECT payload FROM actions WHERE id = 1", AdHocQueries.DEFAULT_ROW_LIMIT);
        Object value = queries.page(outline, 0, 1).get(0).value(0);

        assertThat(value).isInstanceOf(BlobValue.class);
        assertThat(((BlobValue) value).byteLength()).isEqualTo(5L);
        assertThat(value.toString()).contains("5 bytes").doesNotContain("[B@");
    }

    // ------------------------------------------------------- EXPLAIN and PRAGMA

    @Test
    void explainAndPragmaAreRunOnceAndHeld() {
        QueryOutline plan = queries.describe(
                "EXPLAIN QUERY PLAN SELECT * FROM actions WHERE id = 3",
                AdHocQueries.DEFAULT_ROW_LIMIT);
        assertThat(plan.shape()).isEqualTo(ReadOnlySql.Shape.DIRECT);
        assertThat(plan.columns()).contains("detail");
        assertThat(plan.visibleRows()).isPositive();
        assertThat(queries.page(plan, 0, 100)).hasSize((int) plan.visibleRows());

        QueryOutline info = queries.describe(
                "PRAGMA table_info(actions)", AdHocQueries.DEFAULT_ROW_LIMIT);
        assertThat(info.columns()).contains("name", "type", "notnull", "pk");
        assertThat(info.matchedRows()).isEqualTo(OptionalLong.of(5L));
    }

    @Test
    @DisplayName("a direct statement over the cap reports its total as unknown, never as the cap")
    void aCappedDirectStatementDoesNotInventATotal() {
        QueryOutline info = queries.describe("PRAGMA table_info(actions)", 2);

        assertThat(info.visibleRows()).isEqualTo(2L);
        assertThat(info.matchedRows())
                .as("five columns exist but only two were read; the total was never taken")
                .isEmpty();
        assertThat(info.isCapped()).isTrue();
    }

    // -------------------------------------------------------------------- schema

    @Test
    @DisplayName("the schema is read from the file at runtime, tables and views alike")
    void schemaComesFromSqliteMaster() {
        List<SchemaTable> schema = queries.schema();

        SchemaTable actions = schema.stream()
                .filter(table -> table.name().equals("actions"))
                .findFirst()
                .orElseThrow();
        assertThat(actions.isView()).isFalse();
        assertThat(actions.columns()).extracting(SchemaColumn::name)
                .containsExactly("id", "mnemonic", "duration_micros", "note", "payload");
        assertThat(actions.columns().get(0).isPrimaryKey()).isTrue();
        assertThat(actions.columns().get(1).notNull()).isTrue();
        assertThat(actions.columns().get(1).declaredType()).isEqualTo("TEXT");
        assertThat(actions.ddl()).contains("CREATE TABLE actions");

        assertThat(schema).anySatisfy(table -> {
            assertThat(table.name()).isEqualTo("slow_actions");
            assertThat(table.isView()).isTrue();
        });
    }

    // -------------------------------------------------------------------- cancel

    @Test
    @DisplayName("a running query can be abandoned from another thread")
    void cancelStopsARunningQuery() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            started.countDown();
            try {
                queries.describe(SLOW_QUERY, AdHocQueries.DEFAULT_ROW_LIMIT);
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        });

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(400);
        queries.cancel();
        running.get(30, TimeUnit.SECONDS);

        assertThat(thrown.get()).isInstanceOf(QueryFailedException.class);
        QueryFailedException failure = (QueryFailedException) thrown.get();
        assertThat(failure.wasStopped())
                .as("a cancelled query is not a broken one and must not read as an error")
                .isTrue();
        assertThat(failure.statement()).contains("counter");

        // The connection survives: the next query runs normally.
        assertThat(queries.describe("SELECT id FROM actions", 100).matchedRows())
                .isEqualTo(OptionalLong.of(20L));
    }

    @Test
    @DisplayName("a runaway query is stopped by the deadline with nobody watching")
    void theDeadlineStopsARunawayQuery() throws Exception {
        try (Connection ownConnection = database.newQueryConnection();
                AdHocQueries deadlined = new AdHocQueries(ownConnection, 1)) {
            assertThat(deadlined.timeoutSeconds()).isEqualTo(1);
            long started = System.nanoTime();
            assertThatThrownBy(() ->
                    deadlined.describe(SLOW_QUERY, AdHocQueries.DEFAULT_ROW_LIMIT))
                    .isInstanceOfSatisfying(QueryFailedException.class,
                            failure -> assertThat(failure.wasStopped()).isTrue());
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                    .as("stopped at the deadline rather than running to completion")
                    .isLessThan(30L);
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    @DisplayName("a SQL error names SQLite's own message and the statement that produced it")
    void aSqlErrorIsActionable() {
        assertThatThrownBy(() -> queries.describe("SELECT * FROM no_such_table", 100))
                .isInstanceOfSatisfying(QueryFailedException.class, failure -> {
                    assertThat(failure.wasStopped()).isFalse();
                    assertThat(failure.getMessage()).contains("no such table");
                    assertThat(failure.statement())
                            .as("the wrapped text, because that is what SQLite was asked")
                            .contains("SELECT * FROM (SELECT * FROM no_such_table)");
                    assertThat(failure.stage())
                            .isEqualTo(QueryFailedException.Stage.COLUMNS);
                });
    }

    // ------------------------------------------------------------------- helpers

    private static List<Long> first(List<QueryRow> rows) {
        return rows.stream().map(row -> ((Number) row.value(0)).longValue()).toList();
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = database.writerConnection().createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }
}
