package com.holtherndon.bazelviz.storage.query;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * What one submitted statement turned out to be: its columns, how many rows it
 * matches, and how many of them the grid may address.
 *
 * <h2>Why the count is exact and taken up front</h2>
 *
 * <p>{@code PagedTableModel} needs a row count at construction and never asks
 * again, because {@code getRowCount()} is read on the EDT during paint. So a
 * count is required, and there are only three ways to produce one: run
 * {@code SELECT COUNT(*) FROM (…)}, estimate from the query plan, or refuse to
 * say. An estimate presented in a status line is a guess presented as a total,
 * which rule 11 forbids; refusing to say leaves nothing to size the scrollbar
 * with. So the count is run — one extra pass, cancellable like any other, and
 * its cost is stated in {@link #elapsedNanos()} so the user can see what the
 * count itself cost.
 *
 * @param statement the validated statement, exactly as the user typed it
 * @param shape whether it can be wrapped in a subquery
 * @param columns result column labels from {@code ResultSetMetaData}
 * @param matchedRows rows the statement produces in total; empty only when the
 *     statement is {@link ReadOnlySql.Shape#DIRECT} and produced more rows than
 *     the cap, in which case the total was never counted and must not be
 *     invented
 * @param visibleRows rows the grid may address: {@code min(matchedRows, rowLimit)}
 * @param rowLimit the cap in force when this was described
 * @param elapsedNanos wall time spent describing (columns plus count)
 * @param materialized rows already read, for a DIRECT statement; empty for a
 *     TABULAR one, which is paged in SQL and never materialized
 */
public record QueryOutline(
        String statement,
        ReadOnlySql.Shape shape,
        List<String> columns,
        OptionalLong matchedRows,
        long visibleRows,
        long rowLimit,
        long elapsedNanos,
        List<QueryRow> materialized) {

    public QueryOutline {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(shape, "shape");
        columns = List.copyOf(columns);
        Objects.requireNonNull(matchedRows, "matchedRows");
        materialized = List.copyOf(materialized);
        if (visibleRows < 0) {
            throw new IllegalArgumentException("visibleRows must be >= 0: " + visibleRows);
        }
    }

    /**
     * True when the statement matches more rows than the grid will show.
     *
     * <p>When {@link #matchedRows()} is empty this is still true: not knowing
     * the total is precisely the case where more rows exist than were read.
     */
    public boolean isCapped() {
        return matchedRows.isEmpty() || matchedRows.getAsLong() > visibleRows;
    }
}
