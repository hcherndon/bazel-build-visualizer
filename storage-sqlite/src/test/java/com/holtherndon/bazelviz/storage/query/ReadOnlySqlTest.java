package com.holtherndon.bazelviz.storage.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The app-layer half of "this connection cannot write". No database involved. */
final class ReadOnlySqlTest {

  @Test
  @DisplayName("a plain SELECT is tabular and comes back unchanged")
  void selectIsTabular() {
    ReadOnlySql.Statement checked = ReadOnlySql.check("  SELECT id FROM actions  ");
    assertThat(checked.sql()).isEqualTo("SELECT id FROM actions");
    assertThat(checked.shape()).isEqualTo(ReadOnlySql.Shape.TABULAR);
  }

  @Test
  void aTrailingSemicolonIsNotASecondStatement() {
    assertThat(ReadOnlySql.check("SELECT 1;").sql()).isEqualTo("SELECT 1");
    assertThat(ReadOnlySql.check("SELECT 1 ;  \n -- done\n").sql()).isEqualTo("SELECT 1");
  }

  @Test
  @DisplayName("two statements are refused, because query_only would have run both")
  void twoStatementsAreRefused() {
    assertThatThrownBy(() -> ReadOnlySql.check("SELECT 1; DROP TABLE actions"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("Run one statement at a time")
        .hasMessageContaining("SELECT 1")
        .hasMessageContaining("DROP TABLE actions");
  }

  @Test
  @DisplayName("a semicolon inside a string literal is not a statement boundary")
  void semicolonInsideALiteralIsData() {
    ReadOnlySql.Statement checked =
        ReadOnlySql.check("SELECT * FROM actions WHERE mnemonic = 'a;b'");
    assertThat(checked.sql()).isEqualTo("SELECT * FROM actions WHERE mnemonic = 'a;b'");
  }

  @Test
  @DisplayName("a writing keyword inside a string literal is data, not a write")
  void writingWordInsideALiteralIsData() {
    assertThat(ReadOnlySql.check("SELECT * FROM t WHERE x LIKE '%delete me%'").sql())
        .contains("delete me");
    assertThat(ReadOnlySql.check("SELECT * FROM t WHERE x = \"drop\"").shape())
        .isEqualTo(ReadOnlySql.Shape.TABULAR);
  }

  @Test
  @DisplayName("a comment cannot hide a second statement")
  void commentsAreStripped() {
    assertThat(ReadOnlySql.check("-- a note; with a semicolon\nSELECT 1").sql())
        .isEqualTo("-- a note; with a semicolon\nSELECT 1");
    assertThat(ReadOnlySql.check("/* x; y */ SELECT 2").sql()).isEqualTo("/* x; y */ SELECT 2");
    assertThatThrownBy(() -> ReadOnlySql.check("-- only a comment"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("only a comment");
  }

  @Test
  @DisplayName("a leading WITH does not make the statement a read")
  void withThenDeleteIsRefused() {
    // SQLite really does accept this shape, which is why the leading
    // keyword alone cannot be the check.
    assertThatThrownBy(
            () ->
                ReadOnlySql.check(
                    "WITH doomed AS (SELECT id FROM actions)"
                        + " DELETE FROM actions WHERE id IN (SELECT id FROM doomed)"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("DELETE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "DELETE FROM actions",
        "UPDATE actions SET id = 1",
        "INSERT INTO actions VALUES (1)",
        "REPLACE INTO actions VALUES (1)",
        "DROP TABLE actions",
        "CREATE TABLE evil (x)",
        "ALTER TABLE actions RENAME TO gone",
        "ATTACH DATABASE '/tmp/other.db' AS o",
        "DETACH DATABASE o",
        "VACUUM",
        "REINDEX",
        "ANALYZE",
        "BEGIN",
        "COMMIT",
        "ROLLBACK",
        "SAVEPOINT s",
      })
  void writingStatementsAreRefused(String sql) {
    assertThatThrownBy(() -> ReadOnlySql.check(sql)).isInstanceOf(SqlNotAllowedException.class);
  }

  @Test
  @DisplayName("replace() the function still works; REPLACE INTO does not")
  void replaceTheFunctionIsAllowed() {
    assertThat(ReadOnlySql.check("SELECT replace(label, 'a', 'b') FROM targets").shape())
        .isEqualTo(ReadOnlySql.Shape.TABULAR);
    // The writing-word scan runs before the statement is classified, so
    // REPLACE INTO is named as what it is rather than as an unrecognised
    // opening keyword -- wherever in the statement it appears.
    assertThatThrownBy(() -> ReadOnlySql.check("REPLACE INTO targets VALUES (1)"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("REPLACE INTO");
    assertThatThrownBy(
            () ->
                ReadOnlySql.check("WITH c AS (SELECT 1 AS x) REPLACE INTO targets SELECT x FROM c"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("REPLACE INTO");
  }

  @Test
  void withAndValuesAreTabular() {
    assertThat(ReadOnlySql.check("WITH c AS (SELECT 1 AS x) SELECT * FROM c").shape())
        .isEqualTo(ReadOnlySql.Shape.TABULAR);
    assertThat(ReadOnlySql.check("VALUES (1), (2)").shape()).isEqualTo(ReadOnlySql.Shape.TABULAR);
  }

  @Test
  void explainAndIntrospectionPragmasAreDirect() {
    assertThat(ReadOnlySql.check("EXPLAIN QUERY PLAN SELECT * FROM actions").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(ReadOnlySql.check("PRAGMA table_info(actions)").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(ReadOnlySql.check("PRAGMA main.index_list(actions)").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
  }

  @Test
  @DisplayName("a pragma with a setter spelling is refused even in its reading form")
  void settablePragmasAreRefused() {
    // PRAGMA user_version reads harmlessly, but PRAGMA user_version(5)
    // writes -- and no equals sign appears in it. The allowlist holds only
    // pragmas that have no setter form at all.
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA user_version"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("user_version");
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA journal_mode = DELETE"))
        .isInstanceOf(SqlNotAllowedException.class);
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA writable_schema(1)"))
        .isInstanceOf(SqlNotAllowedException.class);
  }

  @Test
  @DisplayName("EXPLAIN is transparent, so it cannot smuggle a pragma past the allowlist")
  void explainCannotRouteAroundThePragmaAllowlist() {
    // The bypass this test exists for: EXPLAIN used to be terminal, so the
    // allowlist was never consulted for what came after it. SQLite applies
    // flag pragmas in sqlite3Pragma() at PREPARE time and EXPLAIN does not
    // suppress that, so `EXPLAIN PRAGMA query_only=OFF` really does clear
    // query_only on a SQLITE_OPEN_READONLY connection -- turning the second
    // refusal off for the life of the connection with four extra
    // characters typed in front of a statement this class already refused.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN PRAGMA query_only=OFF"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("PRAGMA query_only is not one of the introspection pragmas");
    // A prefix rule on "EXPLAIN " alone would not have been enough.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN QUERY PLAN PRAGMA query_only=OFF"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("PRAGMA query_only is not one of the introspection pragmas");
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN PRAGMA writable_schema=ON"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("PRAGMA writable_schema is not one");
    // sqlite3_soft_heap_limit64 is process-global: this one would degrade
    // the writer connection ingesting a build, from the query card.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN PRAGMA soft_heap_limit=1"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("PRAGMA soft_heap_limit is not one");
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN PRAGMA journal_mode=DELETE"))
        .isInstanceOf(SqlNotAllowedException.class);
    // Nesting does not help either.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN EXPLAIN PRAGMA query_only=OFF"))
        .isInstanceOf(SqlNotAllowedException.class);
    assertThatThrownBy(
            () -> ReadOnlySql.check("EXPLAIN QUERY PLAN EXPLAIN PRAGMA writable_schema(1)"))
        .isInstanceOf(SqlNotAllowedException.class);
  }

  @Test
  @DisplayName("EXPLAIN still works over everything it is meant to")
  void explainStillExplainsWhatIsAllowed() {
    // Transparent, not banned: what EXPLAIN is put in front of is checked
    // by the same rules that would apply without it.
    assertThat(ReadOnlySql.check("EXPLAIN PRAGMA table_info(actions)").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(ReadOnlySql.check("EXPLAIN QUERY PLAN PRAGMA index_list(actions)").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(ReadOnlySql.check("EXPLAIN SELECT * FROM actions").shape())
        .as("an explained SELECT yields the plan, not the rows, so it cannot be wrapped")
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(ReadOnlySql.check("EXPLAIN QUERY PLAN SELECT * FROM actions").sql())
        .isEqualTo("EXPLAIN QUERY PLAN SELECT * FROM actions");
    assertThat(ReadOnlySql.check("EXPLAIN WITH c AS (SELECT 1 AS x) SELECT * FROM c").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
  }

  @Test
  @DisplayName("EXPLAIN in front of a write is still a write")
  void explainDoesNotLaunderAWrite() {
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN DELETE FROM actions"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("DELETE");
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN QUERY PLAN DROP TABLE actions"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("DROP");
  }

  @Test
  @DisplayName("EXPLAIN with nothing after it is refused rather than run")
  void explainAloneIsRefused() {
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("nothing at all");
    // QUERY is consumed only when PLAN follows; anything else fails the
    // classification, which is the safe direction.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN QUERY SELECT 1"))
        .isInstanceOf(SqlNotAllowedException.class);
  }

  @Test
  void anUnterminatedLiteralIsNamed() {
    assertThatThrownBy(() -> ReadOnlySql.check("SELECT 'oops"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("string literal")
        .hasMessageContaining("never closed");
  }

  @Test
  void blankTextIsRefusedRatherThanRun() {
    assertThatThrownBy(() -> ReadOnlySql.check("   \n  "))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("no statement to run");
  }

  @Test
  void theRefusalCarriesTheTextBack() {
    String typed = "DROP TABLE actions";
    assertThatThrownBy(() -> ReadOnlySql.check(typed))
        .isInstanceOfSatisfying(
            SqlNotAllowedException.class,
            refused -> assertThat(refused.submitted()).isEqualTo(typed));
  }

  @Test
  @DisplayName("a column whose name contains a keyword is not mistaken for one")
  void wordBoundariesAreRespected() {
    assertThat(ReadOnlySql.check("SELECT created_at, updated_by FROM t").shape())
        .isEqualTo(ReadOnlySql.Shape.TABULAR);
    assertThat(ReadOnlySql.check("SELECT * FROM deleted_rows").shape())
        .isEqualTo(ReadOnlySql.Shape.TABULAR);
  }

  // ------------------------------------------------------ quoted pragma names

  @Test
  @DisplayName("a quoted pragma name is refused by rule, not by accident")
  void quotedPragmaNamesAreRefusedExplicitly() {
    // The hole the explicit rule closes: PRAGMA "query_only" = table_info
    // is legal SQLite — the quotes name the pragma and the bare word is its
    // VALUE. A check that read the name out of the skeleton (where quoted
    // identifiers are blanked) would have judged this statement by its
    // value, and a value that happens to be an allowlisted name would have
    // sailed through to clear query_only.
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA \"query_only\" = table_info"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA `query_only` = table_info"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA [query_only] = table_info"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
    // And through EXPLAIN, which is where clearing query_only would land.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN PRAGMA \"query_only\" = table_info"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
    // A quoted spelling of an ALLOWED pragma is refused too: the allowlist
    // admits bare names only, so there is no spelling to reason about.
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA \"table_info\"(actions)"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA main.\"table_info\"(actions)"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA \"main\".table_info(actions)"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("quoted pragma name");
  }

  @Test
  @DisplayName("bare pragma spellings still work exactly as before")
  void barePragmasAreUnchanged() {
    assertThat(ReadOnlySql.check("PRAGMA table_info(actions)").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThat(ReadOnlySql.check("PRAGMA main.index_list(actions)").shape())
        .isEqualTo(ReadOnlySql.Shape.DIRECT);
    assertThatThrownBy(() -> ReadOnlySql.check("PRAGMA soft_heap_limit=1"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("soft_heap_limit is not one");
  }

  // ------------------------------------------------------- CREATE TEMP VIEW

  @Test
  @DisplayName("CREATE TEMP VIEW name AS SELECT is the one admitted CREATE")
  void createTempViewIsAdmitted() {
    ReadOnlySql.Statement checked =
        ReadOnlySql.check("CREATE TEMP VIEW slow AS SELECT * FROM actions");
    assertThat(checked.shape()).isEqualTo(ReadOnlySql.Shape.DEFINE);
    assertThat(checked.sql()).isEqualTo("CREATE TEMP VIEW slow AS SELECT * FROM actions");
    assertThat(checked.tempViewName()).isEqualTo("slow");
    assertThat(checked.tempViewSelect()).isEqualTo("SELECT * FROM actions");

    assertThat(
            ReadOnlySql.check(
                    "CREATE TEMPORARY VIEW v AS WITH c AS (SELECT 1 AS x) SELECT * FROM c")
                .shape())
        .isEqualTo(ReadOnlySql.Shape.DEFINE);
    assertThat(ReadOnlySql.check("create temp view v as values (1), (2)").shape())
        .isEqualTo(ReadOnlySql.Shape.DEFINE);
  }

  @Test
  @DisplayName("a quoted or bracketed view name round-trips unquoted")
  void quotedViewNamesAreParsed() {
    assertThat(ReadOnlySql.check("CREATE TEMP VIEW \"my view\" AS SELECT 1").tempViewName())
        .isEqualTo("my view");
    assertThat(ReadOnlySql.check("CREATE TEMP VIEW [my view] AS SELECT 1").tempViewName())
        .isEqualTo("my view");
    assertThat(ReadOnlySql.check("CREATE TEMP VIEW `my view` AS SELECT 1").tempViewName())
        .isEqualTo("my view");
    // A doubled quote inside a quoted name is one literal quote.
    assertThat(ReadOnlySql.check("CREATE TEMP VIEW \"say \"\"hi\"\"\" AS SELECT 1").tempViewName())
        .isEqualTo("say \"hi\"");
    // A quoted name cannot smuggle the statement's meaning: the body is
    // still checked, whatever the name looks like.
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW \"v\" AS DELETE FROM actions"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("DELETE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "CREATE TABLE evil (x)",
        "CREATE VIEW v AS SELECT * FROM actions",
        "CREATE INDEX idx ON actions (id)",
        "CREATE UNIQUE INDEX idx ON actions (id)",
        "CREATE TRIGGER trg AFTER INSERT ON actions BEGIN SELECT 1; END",
        "CREATE VIRTUAL TABLE ft USING fts5(content)",
        "CREATE TEMP TABLE evil (x)",
        "CREATE TEMPORARY TABLE evil (x)",
        "CREATE TEMP TRIGGER trg AFTER INSERT ON actions BEGIN SELECT 1; END",
        "CREATE TEMP VIRTUAL TABLE ft USING fts5(content)",
      })
  @DisplayName("every other CREATE stays banned, temp spellings included")
  void everyOtherCreateIsRefused(String sql) {
    assertThatThrownBy(() -> ReadOnlySql.check(sql)).isInstanceOf(SqlNotAllowedException.class);
  }

  @Test
  @DisplayName("the view definition's edges are all refusals, not skips")
  void tempViewEdgesAreRefused() {
    // EXPLAIN in front of a definition: EXPLAIN is transparent for reads,
    // and a definition is not a read.
    assertThatThrownBy(() -> ReadOnlySql.check("EXPLAIN CREATE TEMP VIEW v AS SELECT 1"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("CREATE");
    // A schema qualifier: temp views always land in the temp schema.
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW temp.v AS SELECT 1"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("schema qualifier");
    // IF NOT EXISTS: redefinition is what running the statement again does.
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW IF NOT EXISTS v AS SELECT 1"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("IF NOT EXISTS");
    // A column list; the SELECT is where columns get named.
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW v (a, b) AS SELECT 1, 2"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("column list");
    // No name, no AS, no body.
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW"))
        .isInstanceOf(SqlNotAllowedException.class);
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW v"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("Expected AS");
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW v AS"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("no body");
    // A body that is not tabular.
    assertThatThrownBy(() -> ReadOnlySql.check("CREATE TEMP VIEW v AS PRAGMA table_info(actions)"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("SELECT, WITH or VALUES");
    // A second statement after the definition.
    assertThatThrownBy(
            () -> ReadOnlySql.check("CREATE TEMP VIEW v AS SELECT 1; DROP TABLE actions"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("Run one statement at a time");
    // A writing word buried in the body via a CTE.
    assertThatThrownBy(
            () ->
                ReadOnlySql.check("CREATE TEMP VIEW v AS WITH c AS (SELECT 1) DELETE FROM actions"))
        .isInstanceOf(SqlNotAllowedException.class)
        .hasMessageContaining("DELETE");
  }
}
