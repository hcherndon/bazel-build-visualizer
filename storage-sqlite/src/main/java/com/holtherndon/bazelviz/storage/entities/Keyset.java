package com.holtherndon.bazelviz.storage.entities;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/**
 * Keyset paging over a sort column that may be NULL.
 *
 * <h2>Why not OFFSET</h2>
 *
 * <p>{@code OFFSET} makes SQLite walk and discard every skipped row, so its
 * cost grows with how far the user has scrolled — 11 ms mean and 23 ms worst
 * case at 2M rows in the Phase 0 spike, against 0.23 ms for the keyset form at
 * any depth (docs/performance.md). Plan 10.9 makes keyset the rule for every
 * UI-facing query, and this class is what makes that rule usable for the
 * sortable columns rather than only for id order.
 *
 * <h2>Why NULL needs its own term</h2>
 *
 * <p>Half the sortable columns here are legitimately unknown: an action Bazel
 * 7 never timed has no start, and an action with no label has no label. SQL
 * comparison against NULL yields NULL, which is not true, so a plain
 * {@code WHERE col > ?} silently drops every unknown row from every page after
 * the first — the table would appear to contain fewer rows than its own count
 * says, and the missing ones would be exactly the ones the "unknown values are
 * visibly unknown" criterion is about.
 *
 * <p>So the sort is on the pair {@code (col IS NULL, col)} with the row id
 * breaking ties, and the seek predicate spells out all three cases. NULLs sort
 * last in ascending order and first in descending, which is the same convention
 * a spreadsheet uses and keeps them together rather than scattered.
 *
 * <p>{@code IS} rather than {@code =} for the tie-break comparison: it is
 * null-safe in SQLite, so an anchor whose sort value is NULL still matches the
 * rows beside it instead of matching nothing.
 */
final class Keyset {

    private Keyset() {}

    /**
     * The {@code ORDER BY} clause for a sort column, tie-broken by row id.
     *
     * @param column a column expression, already qualified with its table alias
     * @param idColumn the unique tie-breaker
     */
    static String orderBy(String column, String idColumn, boolean descending) {
        String direction = descending ? "DESC" : "ASC";
        // "col IS NULL" is 0 or 1, so ordering by it ascending puts known
        // values first; descending flips both terms together, which keeps the
        // reverse of a page exactly the page in reverse.
        return " ORDER BY (" + column + " IS NULL) " + direction
                + ", " + column + " " + direction
                + ", " + idColumn + " " + direction;
    }

    /**
     * The seek predicate for "rows after this anchor", or empty for the first
     * page. Binds three parameters: the anchor's null flag, its value, and its
     * id — in that order, twice for the value.
     */
    static String seek(String column, String idColumn, boolean descending) {
        String beyond = descending ? "<" : ">";
        String nullBeyond = descending ? "<" : ">";
        return " AND (("
                + "(" + column + " IS NULL) " + nullBeyond + " ?"
                + ") OR ("
                + "(" + column + " IS NULL) = ? AND ("
                + column + " " + beyond + " ? OR ("
                + column + " IS ? AND " + idColumn + " " + beyond + " ?)))"
                + ")";
    }

    /**
     * Binds the five parameters {@link #seek} declares.
     *
     * @param sortValue the anchor row's value for the sort column, empty when
     *     that row's value is NULL
     * @return the next free parameter index
     */
    static int bindSeek(
            PreparedStatement statement, int index, Optional<Object> sortValue, long anchorId)
            throws SQLException {
        int nullFlag = sortValue.isPresent() ? 0 : 1;
        statement.setInt(index, nullFlag);
        statement.setInt(index + 1, nullFlag);
        bindValue(statement, index + 2, sortValue);
        bindValue(statement, index + 3, sortValue);
        statement.setLong(index + 4, anchorId);
        return index + 5;
    }

    private static void bindValue(PreparedStatement statement, int index, Optional<Object> value)
            throws SQLException {
        if (value.isEmpty()) {
            statement.setNull(index, Types.OTHER);
        } else if (value.get() instanceof Long number) {
            statement.setLong(index, number);
        } else if (value.get() instanceof Integer number) {
            statement.setInt(index, number);
        } else {
            statement.setString(index, value.get().toString());
        }
    }
}
