package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.storage.query.QueryOutline;
import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The ad hoc query grid's {@link RowSource}: one described query, paged in SQL.
 *
 * <p>Feeds the existing {@code PagedTableModel} unmodified. The only thing this
 * needed that the actions and events tables did not is a <em>dynamic</em>
 * column list, and {@link ColumnSpec} is already a {@code (name, extractor)}
 * pair, so the columns are built from the query's own
 * {@code ResultSetMetaData} labels at run time rather than declared.
 *
 * <h2>Paging</h2>
 *
 * <p>{@code OFFSET}, which the rest of this application avoids. The reason is
 * in {@code AdHocQueries}: keyset paging needs a sort column and a unique
 * tiebreaker, and an arbitrary user query exposes neither — its {@code ORDER
 * BY} may be over an expression or an alias, or absent entirely. There is no
 * anchor to seek to, so there is no keyset form to write.
 *
 * <h2>The row count is a snapshot</h2>
 *
 * <p>{@link #rowCount()} is whatever {@code SELECT COUNT(*)} returned when the
 * query was described, capped at the row limit, and it never changes — the same
 * contract every other row source here has, because {@code PagedTableModel}
 * reads it on the EDT and must not query. Against a session that is still being
 * captured the count is therefore a lower bound taken at a moment, which the
 * panel says rather than implies.
 */
public final class QueryRowSource implements RowSource<QueryRow> {

    /**
     * Rows per page. The same 200 the actions and events tables use — see the
     * "Table page size" row of docs/limits.md — for the same reason: a
     * screenful never spans more than two pages, and one page stays inside the
     * 100 ms interaction objective.
     */
    public static final int PAGE_SIZE = 200;

    private final QueryReader reader;
    private final QueryOutline outline;

    public QueryRowSource(QueryReader reader, QueryOutline outline) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.outline = Objects.requireNonNull(outline, "outline");
    }

    /** What the query turned out to be: its columns, its total, its cap. */
    public QueryOutline outline() {
        return outline;
    }

    @Override
    public long rowCount() {
        return outline.visibleRows();
    }

    @Override
    public Page<QueryRow> fetchPage(long pageIndex, int pageSize) {
        List<QueryRow> rows = reader.page(outline, pageIndex * pageSize, pageSize);
        return new Page<>(pageIndex, rows);
    }

    /**
     * One column per result column, in the order SQLite returned them.
     *
     * <p>The extractor hands back the raw value including {@code null}, because
     * a SQL NULL is not an empty cell and the renderer is the layer that gets
     * to say so (rule 11). Mapping it to {@code ""} here would make the two
     * indistinguishable before anything had a chance to distinguish them.
     */
    public List<ColumnSpec<QueryRow>> columns() {
        List<String> labels = outline.columns();
        List<ColumnSpec<QueryRow>> columns = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            columns.add(new ColumnSpec<>(labels.get(i), row -> row.value(index)));
        }
        return List.copyOf(columns);
    }
}
