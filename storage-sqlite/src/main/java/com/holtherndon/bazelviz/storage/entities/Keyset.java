package com.holtherndon.bazelviz.storage.entities;

/**
 * Keyset paging over a sort column that may be NULL and may repeat.
 *
 * <h2>Why not OFFSET</h2>
 *
 * <p>{@code OFFSET} makes SQLite walk and discard every skipped row, so its
 * cost grows with how far the user has scrolled — 11 ms mean and 23 ms worst
 * case at 2M rows in the Phase 0 spike, against 0.23 ms for the keyset form at
 * any depth (docs/performance.md). Plan 10.9 makes keyset the rule.
 *
 * <h2>Two ways to lose that, both measured</h2>
 *
 * <p><b>Sorting on a null flag.</b> Half the sortable columns are legitimately
 * unknown, and SQL comparison against NULL yields NULL, so a plain
 * {@code WHERE col > ?} silently drops every unknown row from every page after
 * the first. The tempting fix is to sort on {@code (col IS NULL, col)} so
 * unknowns land at one end whichever way the sort runs. That is an expression
 * no ordinary index supplies, so SQLite answers every page with a full scan and
 * a temporary b-tree: measured at 200,000 actions, 0.19 ms at the head of the
 * table and 18.8 ms at the tail — the shape of {@code OFFSET}, reached from a
 * different direction.
 *
 * <p><b>One predicate for the whole seek.</b> Both {@code (col, id) > (?, ?)}
 * and its expansion {@code col > ? OR (col = ? AND id > ?)} select the right
 * rows, and neither seeks reliably. SQLite uses only the leading term of a row
 * value as an index bound, so on a column with few distinct values it lands at
 * the start of the anchor's group and walks: 0.08 ms at the head, 8.2 ms at the
 * tail of one 199,800-row group. The {@code OR} form is worse because it is
 * unpredictable — the same query planned three different ways at three depths,
 * the worst of them 26 ms.
 *
 * <h2>What this does instead</h2>
 *
 * <p>It takes SQLite's own NULL ordering — unknowns first ascending, last
 * descending, which an ordinary index on the column already supplies — and
 * builds a page out of segments, each of which is a range SQLite can seek:
 *
 * <ol>
 *   <li>the rest of the anchor's own value group, {@code col = ? AND id > ?};
 *   <li>everything past that group, {@code col > ?};
 *   <li>the unknowns, {@code col IS NULL}, optionally from an id.
 * </ol>
 *
 * <p>A page is drawn from those in order until it is full. Almost every page
 * comes entirely from one segment; only a page sitting on a boundary costs two
 * queries, or three at the one place where a value boundary and the
 * known/unknown boundary coincide. Measured flat at 0.06 ms per segment from
 * the first page to the last, on both a near-unique column and one with two
 * distinct values.
 *
 * <p>The price is the ordering convention: unknowns come first ascending rather
 * than last. That is SQL's convention, it is consistent in both directions, and
 * it is the only version of it that seeks.
 */
final class Keyset {

    private Keyset() {}

    /** One seekable range that a page can be drawn from, in page order. */
    enum Segment {
        /** No anchor: the ordering's own beginning. Binds nothing. */
        FROM_START,

        /** The rest of the anchor's value group. Binds the value, then the id. */
        SAME_VALUE,

        /** Everything past the anchor's value group. Binds the value. */
        PAST_VALUE,

        /** Every row that has a value, from the first of them. Binds nothing. */
        VALUE_SIDE,

        /** The unknowns after an id. Binds the id. */
        NULL_SIDE_AFTER,

        /** Every unknown, from the first of them. Binds nothing. */
        NULL_SIDE,
    }

    /** The {@code WHERE} fragment for a segment. */
    static String where(Segment segment, String column, String idColumn, boolean descending) {
        String beyond = descending ? " < " : " > ";
        return switch (segment) {
            case FROM_START -> "";
            case SAME_VALUE -> column.equals(idColumn)
                    // A row id is unique, so its value group holds only itself
                    // and this segment can never contribute. The pager skips it.
                    ? " AND 0"
                    : " AND " + column + " = ? AND " + idColumn + beyond + "?";
            case PAST_VALUE -> column.equals(idColumn)
                    ? " AND " + idColumn + beyond + "?"
                    : " AND " + column + beyond + "?";
            case VALUE_SIDE -> " AND " + column + " IS NOT NULL";
            case NULL_SIDE_AFTER -> " AND " + column + " IS NULL AND " + idColumn + beyond + "?";
            case NULL_SIDE -> " AND " + column + " IS NULL";
        };
    }

    /**
     * The {@code ORDER BY} for a segment.
     *
     * <p>Inside one value group, and among the unknowns, every row ties on the
     * sort column and the id is the whole ordering. Saying only that is what
     * lets SQLite answer from the index instead of sorting.
     */
    static String orderBy(Segment segment, String column, String idColumn, boolean descending) {
        String direction = descending ? " DESC" : " ASC";
        boolean idOnly = segment == Segment.SAME_VALUE
                || segment == Segment.NULL_SIDE_AFTER
                || segment == Segment.NULL_SIDE
                || column.equals(idColumn);
        return idOnly
                ? " ORDER BY " + idColumn + direction
                : " ORDER BY " + column + direction + ", " + idColumn + direction;
    }
}
