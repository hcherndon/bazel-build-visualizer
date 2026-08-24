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
        assertThatThrownBy(() ->
                ReadOnlySql.check("WITH doomed AS (SELECT id FROM actions)"
                        + " DELETE FROM actions WHERE id IN (SELECT id FROM doomed)"))
                .isInstanceOf(SqlNotAllowedException.class)
                .hasMessageContaining("DELETE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
        assertThatThrownBy(() -> ReadOnlySql.check(sql))
                .isInstanceOf(SqlNotAllowedException.class);
    }

    @Test
    @DisplayName("replace() the function still works; REPLACE INTO does not")
    void replaceTheFunctionIsAllowed() {
        assertThat(ReadOnlySql.check("SELECT replace(label, 'a', 'b') FROM targets").shape())
                .isEqualTo(ReadOnlySql.Shape.TABULAR);
        // Leading REPLACE never reaches the two-word rule: it is not one of the
        // allowed opening keywords in the first place.
        assertThatThrownBy(() -> ReadOnlySql.check("REPLACE INTO targets VALUES (1)"))
                .isInstanceOf(SqlNotAllowedException.class)
                .hasMessageContaining("\"REPLACE\" is not one");
        // Behind a WITH it does, which is the case the two-word rule exists for.
        assertThatThrownBy(() ->
                ReadOnlySql.check("WITH c AS (SELECT 1 AS x) REPLACE INTO targets SELECT x FROM c"))
                .isInstanceOf(SqlNotAllowedException.class)
                .hasMessageContaining("REPLACE INTO");
    }

    @Test
    void withAndValuesAreTabular() {
        assertThat(ReadOnlySql.check("WITH c AS (SELECT 1 AS x) SELECT * FROM c").shape())
                .isEqualTo(ReadOnlySql.Shape.TABULAR);
        assertThat(ReadOnlySql.check("VALUES (1), (2)").shape())
                .isEqualTo(ReadOnlySql.Shape.TABULAR);
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
                .isInstanceOfSatisfying(SqlNotAllowedException.class,
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
}
