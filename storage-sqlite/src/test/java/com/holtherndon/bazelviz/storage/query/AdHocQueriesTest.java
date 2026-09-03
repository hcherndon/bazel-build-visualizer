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
 * <p>The write-refusal tests here matter more than the rest put together: the feature's whole
 * premise is that somebody else's SQL cannot damage the session, and that is a claim about a
 * running connection rather than about a regex.
 */
final class AdHocQueriesTest {

  @TempDir Path tempDir;

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
      statement.execute(
          "CREATE VIEW slow_actions AS SELECT * FROM actions" + " WHERE duration_micros > 100");
      for (int i = 1; i <= 20; i++) {
        statement.execute(
            "INSERT INTO actions VALUES ("
                + i
                + ", 'Javac', "
                + (i * 10)
                + ", "
                + (i % 2 == 0 ? "NULL" : "''")
                + ", NULL)");
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
      assertThatThrownBy(
              () -> statement.execute("INSERT INTO actions VALUES (999, 'Evil', 1, NULL, NULL)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("readonly");
    }
    try (Statement statement = queryConnection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("UPDATE actions SET mnemonic = 'Evil'"))
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
    assertThat(queryConnection.isReadOnly()).as("SQLITE_OPEN_READONLY at open time").isTrue();
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

  @Test
  @DisplayName("EXPLAIN cannot smuggle a pragma past the allowlist, and query_only survives")
  void explainCannotClearQueryOnly() throws Exception {
    assertThatThrownBy(() -> queries.describe("EXPLAIN PRAGMA query_only=OFF", 100))
        .isInstanceOf(SqlNotAllowedException.class);
    assertThatThrownBy(() -> queries.describe("EXPLAIN QUERY PLAN PRAGMA soft_heap_limit=1", 100))
        .isInstanceOf(SqlNotAllowedException.class);
    assertThat(queryOnly()).as("still on, because nothing was sent").isEqualTo("1");
    // And an EXPLAIN of something allowed still works.
    assertThat(queries.describe("EXPLAIN QUERY PLAN SELECT * FROM actions", 100).shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
  }

  @Test
  @DisplayName(
      "the pragma bypass is real; the write guarantee survives it and the guard catches it")
  void theWriteGuaranteeOutlivesQueryOnly() throws Exception {
    // Deliberately going round the statement filter, because the point is
    // what the *connection* does. SQLite applies flag pragmas in
    // sqlite3Pragma() at prepare time, and EXPLAIN does not suppress that.
    assertThat(queryOnly()).isEqualTo("1");
    try (Statement statement = queryConnection.createStatement()) {
      statement.execute("EXPLAIN PRAGMA query_only=OFF");
    }
    assertThat(queryOnly())
        .as(
            "EXPLAIN really does clear it -- which is why the filter must treat"
                + " EXPLAIN as transparent rather than terminal")
        .isEqualTo("0");

    // The guarantee is the open mode, and it is not reachable from SQL.
    try (Statement statement = queryConnection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("CREATE TABLE zz (x)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("readonly");
    }
    try (Statement statement = queryConnection.createStatement()) {
      assertThatThrownBy(
              () -> statement.execute("INSERT INTO actions VALUES (999,'Evil',1,NULL,NULL)"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("readonly");
    }
    assertThat(scalar("SELECT COUNT(*) FROM actions")).isEqualTo(20L);

    // The per-execution guard refuses to keep going, says so, and puts the
    // second refusal back rather than leaving the connection weakened.
    assertThatThrownBy(() -> queries.describe("SELECT id FROM actions", 100))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stopped refusing writes between statements")
        .hasMessageContaining("Nothing was run");
    assertThat(queryOnly()).as("restored by the guard").isEqualTo("1");
    // And having restored it, the reader works again.
    assertThat(queries.describe("SELECT id FROM actions", 100).matchedRows())
        .isEqualTo(OptionalLong.of(20L));
  }

  // ---------------------------------------------------------------- describing

  @Test
  void describeReportsColumnsAndAnExactCount() {
    QueryOutline outline =
        queries.describe("SELECT id, mnemonic, duration_micros FROM actions WHERE id > 5", 1000);

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
    QueryOutline outline =
        queries.describe("SELECT id FROM actions ORDER BY id DESC", AdHocQueries.DEFAULT_ROW_LIMIT);

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
    QueryOutline outline =
        queries.describe(
            "SELECT id FROM actions ORDER BY id LIMIT 5", AdHocQueries.DEFAULT_ROW_LIMIT);

    assertThat(outline.matchedRows()).isEqualTo(OptionalLong.of(5L));
    assertThat(first(queries.page(outline, 0, 3))).containsExactly(1L, 2L, 3L);
    assertThat(first(queries.page(outline, 3, 3))).containsExactly(4L, 5L);
  }

  // ------------------------------------------------------------- rule 11: NULL

  @Test
  @DisplayName("a SQL NULL arrives as null, not as 0 and not as an empty string")
  void nullIsNotZeroAndNotEmpty() {
    QueryOutline outline =
        queries.describe(
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
    QueryOutline outline =
        queries.describe(
            "SELECT payload FROM actions WHERE id = 1", AdHocQueries.DEFAULT_ROW_LIMIT);
    Object value = queries.page(outline, 0, 1).get(0).value(0);

    assertThat(value).isInstanceOf(BlobValue.class);
    assertThat(((BlobValue) value).byteLength()).isEqualTo(5L);
    assertThat(value.toString()).contains("5 bytes").doesNotContain("[B@");
  }

  // ------------------------------------------------------- EXPLAIN and PRAGMA

  @Test
  void explainAndPragmaAreRunOnceAndHeld() {
    QueryOutline plan =
        queries.describe(
            "EXPLAIN QUERY PLAN SELECT * FROM actions WHERE id = 3",
            AdHocQueries.DEFAULT_ROW_LIMIT);
    assertThat(plan.shape()).isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(plan.columns()).contains("detail");
    assertThat(plan.visibleRows()).isPositive();
    assertThat(queries.page(plan, 0, 100)).hasSize((int) plan.visibleRows());

    QueryOutline info =
        queries.describe("PRAGMA table_info(actions)", AdHocQueries.DEFAULT_ROW_LIMIT);
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

    SchemaTable actions =
        schema.stream().filter(table -> table.name().equals("actions")).findFirst().orElseThrow();
    assertThat(actions.isView()).isFalse();
    assertThat(actions.columns())
        .extracting(SchemaColumn::name)
        .containsExactly("id", "mnemonic", "duration_micros", "note", "payload");
    assertThat(actions.columns().get(0).isPrimaryKey()).isTrue();
    assertThat(actions.columns().get(1).notNull()).isTrue();
    assertThat(actions.columns().get(1).declaredType()).isEqualTo("TEXT");
    assertThat(actions.ddl()).contains("CREATE TABLE actions");

    assertThat(schema)
        .anySatisfy(
            table -> {
              assertThat(table.name()).isEqualTo("slow_actions");
              assertThat(table.isView()).isTrue();
            });
  }

  // ---------------------------------------------------------- temp views

  @Test
  @DisplayName("a temp view can be defined, queried, redefined, and never touches the file")
  void aTempViewIsDefinedOnTheConnectionAlone() throws Exception {
    long masterRowsBefore = scalar("SELECT COUNT(*) FROM sqlite_master");

    QueryOutline defined =
        queries.describe(
            "CREATE TEMP VIEW slow AS SELECT * FROM actions WHERE duration_micros > 100", 100);
    assertThat(defined.shape()).isEqualTo(ReadOnlySql.Shape.DEFINE);
    assertThat(defined.columns()).isEmpty();

    // Queryable on this connection.
    assertThat(queryValue("SELECT COUNT(*) FROM slow")).isEqualTo(10L);
    // Redefinable by running another CREATE, not an error.
    queries.describe("CREATE TEMP VIEW slow AS SELECT * FROM actions", 100);
    assertThat(queryValue("SELECT COUNT(*) FROM slow")).isEqualTo(20L);

    // query_only survives the definition: it was lifted for the statement
    // pair and put back.
    assertThat(queryOnly()).isEqualTo("1");

    // The session file is untouched: nothing landed in the main schema.
    assertThat(scalar("SELECT COUNT(*) FROM sqlite_master")).isEqualTo(masterRowsBefore);
    // And another query connection does not see the view — it is this
    // connection's alone.
    try (Connection other = database.newQueryConnection();
        AdHocQueries otherQueries = new AdHocQueries(other)) {
      assertThatThrownBy(() -> otherQueries.describe("SELECT * FROM slow", 100))
          .isInstanceOf(QueryFailedException.class)
          .hasMessageContaining("no such table");
    }
  }

  @Test
  @DisplayName("the schema listing names the connection's temp views as temp")
  void tempViewsAppearInTheSchemaListing() {
    queries.describe("CREATE TEMP VIEW mine AS SELECT id FROM actions", 100);

    List<SchemaTable> schema = queries.schema();
    SchemaTable mine =
        schema.stream()
            .filter(SchemaTable::isTemp)
            .filter(table -> table.name().equals("mine"))
            .findFirst()
            .orElseThrow();
    assertThat(mine.isView()).isTrue();
    assertThat(mine.columns()).extracting(SchemaColumn::name).containsExactly("id");
    // The session's own objects still say main.
    assertThat(schema)
        .anySatisfy(
            table -> {
              assertThat(table.name()).isEqualTo("actions");
              assertThat(table.isTemp()).isFalse();
            });
  }

  @Test
  @DisplayName("a temp view that shadows a main table is harmless and local")
  void aShadowingTempViewIsHarmless() throws Exception {
    queries.describe("CREATE TEMP VIEW actions AS SELECT 1 AS shadow", 100);

    // On this connection the unqualified name now resolves to the shadow …
    assertThat(queries.describe("SELECT * FROM actions", 100).columns()).containsExactly("shadow");
    // … the qualified name still reaches the real table …
    assertThat(queryValue("SELECT COUNT(*) FROM main.actions")).isEqualTo(20L);
    // … the schema listing describes the real table's columns for main and
    // the shadow's for temp, because table_info is schema-qualified …
    List<SchemaTable> schema = queries.schema();
    assertThat(
            schema.stream()
                .filter(t -> t.name().equals("actions") && !t.isTemp())
                .findFirst()
                .orElseThrow()
                .columns())
        .extracting(SchemaColumn::name)
        .contains("id", "mnemonic");
    assertThat(
            schema.stream()
                .filter(t -> t.name().equals("actions") && t.isTemp())
                .findFirst()
                .orElseThrow()
                .columns())
        .extracting(SchemaColumn::name)
        .containsExactly("shadow");
    // … and the table itself, on the writer, never changed.
    assertThat(scalar("SELECT COUNT(*) FROM actions")).isEqualTo(20L);
  }

  @Test
  @DisplayName("writing through or around a temp view is refused at every layer")
  void aTempViewCannotBeWrittenThrough() throws Exception {
    queries.describe("CREATE TEMP VIEW mine AS SELECT id FROM actions", 100);

    // The filter refuses the statement before anything runs.
    assertThatThrownBy(() -> queries.describe("INSERT INTO mine VALUES (1)", 100))
        .isInstanceOf(SqlNotAllowedException.class);
    assertThatThrownBy(() -> queries.describe("DROP VIEW mine", 100))
        .isInstanceOf(SqlNotAllowedException.class);
    // And straight at the connection, SQLite refuses too: query_only is ON
    // between statements, and a plain view cannot be written through.
    try (Statement statement = queryConnection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("INSERT INTO mine VALUES (1)"))
          .isInstanceOf(SQLException.class);
    }
    // The main schema stays out of reach even while the temp schema is
    // writable: this is the open-mode guarantee, re-proved at the moment
    // query_only is down.
    try (Statement statement = queryConnection.createStatement()) {
      statement.execute("PRAGMA query_only=OFF");
      try {
        assertThatThrownBy(() -> statement.execute("CREATE TABLE zz (x)"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("readonly");
        assertThatThrownBy(() -> statement.execute("CREATE VIEW zz AS SELECT 1"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("readonly");
        assertThatThrownBy(() -> statement.execute("CREATE INDEX zz ON actions (id)"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("readonly");
        assertThatThrownBy(
                () ->
                    statement.execute(
                        "CREATE TRIGGER zz AFTER INSERT ON actions BEGIN SELECT 1; END"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("readonly");
        assertThatThrownBy(() -> statement.execute("CREATE VIRTUAL TABLE zz USING fts5(content)"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("readonly");
      } finally {
        statement.execute("PRAGMA query_only=ON");
      }
    }
    assertThat(scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'zz'")).isZero();
  }

  @Test
  @DisplayName("saved view definitions replay onto the connection, and replace cleanly")
  void savedViewsReplayAndReplace() {
    List<String> problems =
        queries.applyTempViews(
            List.of(
                new TempViewDefinition(
                    "fast", "SELECT * FROM actions WHERE duration_micros <= 100"),
                new TempViewDefinition("all_ids", "SELECT id FROM actions")));
    assertThat(problems).isEmpty();
    assertThat(queryValue("SELECT COUNT(*) FROM fast")).isEqualTo(10L);

    // Reapplying with a renamed set drops what the replay created before.
    problems =
        queries.applyTempViews(
            List.of(
                new TempViewDefinition(
                    "quick", "SELECT * FROM actions WHERE duration_micros <= 100")));
    assertThat(problems).isEmpty();
    assertThat(queryValue("SELECT COUNT(*) FROM quick")).isEqualTo(10L);
    assertThatThrownBy(() -> queries.describe("SELECT * FROM fast", 100))
        .isInstanceOf(QueryFailedException.class)
        .hasMessageContaining("no such table");
  }

  @Test
  @DisplayName("a broken saved view is reported and skipped, not fatal")
  void aBrokenSavedViewIsReportedNotFatal() {
    List<String> problems =
        queries.applyTempViews(
            List.of(
                new TempViewDefinition("bad", "DELETE FROM actions"),
                new TempViewDefinition("hollow", "SELECT * FROM no_such_table"),
                new TempViewDefinition("good", "SELECT id FROM actions")));

    // The DELETE body is refused before anything runs. The body over a
    // missing table is NOT caught here — SQLite resolves a view's tables
    // at use, not at definition — so it applies and fails at first query,
    // with SQLite's own message.
    assertThat(problems)
        .hasSize(1)
        .anySatisfy(p -> assertThat(p).contains("bad").contains("DELETE"));
    assertThatThrownBy(() -> queries.describe("SELECT * FROM hollow", 100))
        .isInstanceOf(QueryFailedException.class)
        .hasMessageContaining("no_such_table");
    assertThat(queryValue("SELECT COUNT(*) FROM good")).isEqualTo(20L);
    // Nothing was executed for the DELETE body: the rows are all there.
    assertThat(queryValue("SELECT COUNT(*) FROM actions")).isEqualTo(20L);
  }

  // -------------------------------------------------------------------- cancel

  @Test
  @DisplayName("a running query can be abandoned from another thread")
  void cancelStopsARunningQuery() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    CompletableFuture<Void> running =
        CompletableFuture.runAsync(
            () -> {
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
      assertThatThrownBy(() -> deadlined.describe(SLOW_QUERY, AdHocQueries.DEFAULT_ROW_LIMIT))
          .isInstanceOfSatisfying(
              QueryFailedException.class, failure -> assertThat(failure.wasStopped()).isTrue());
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
        .isInstanceOfSatisfying(
            QueryFailedException.class,
            failure -> {
              assertThat(failure.wasStopped()).isFalse();
              assertThat(failure.getMessage()).contains("no such table");
              assertThat(failure.statement())
                  .as("the wrapped text, because that is what SQLite was asked")
                  .contains("SELECT * FROM (SELECT * FROM no_such_table)");
              assertThat(failure.stage()).isEqualTo(QueryFailedException.Stage.COLUMNS);
            });
  }

  // ------------------------------------------------------------------- helpers

  /** The single value a one-row, one-column query produces, through the reader. */
  private long queryValue(String sql) {
    QueryOutline outline = queries.describe(sql, 100);
    return ((Number) queries.page(outline, 0, 1).get(0).value(0)).longValue();
  }

  private String queryOnly() throws SQLException {
    try (Statement statement = queryConnection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA query_only")) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

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
