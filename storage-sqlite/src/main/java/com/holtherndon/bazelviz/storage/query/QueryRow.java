package com.holtherndon.bazelviz.storage.query;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * One row of an ad hoc query's result.
 *
 * <p>A {@code null} element is a SQL NULL and nothing else. It is deliberately
 * not mapped to {@code ""} or to {@code 0} on the way out of the database —
 * rule 11 — so that the layer which renders it can say "NULL" and mean it. An
 * empty string column and a NULL column are different facts about a build and
 * must not arrive here looking the same.
 *
 * <p>Rows are created per page and discarded with it. A page is a couple of
 * hundred rows, so this is the bounded end of rule 9: nothing accumulates.
 */
public final class QueryRow {

    private final Object[] values;

    /** Takes ownership of {@code values}; callers must not retain the array. */
    QueryRow(Object[] values) {
        this.values = values;
    }

    /** For tests and callers that build rows without a database. */
    public static QueryRow of(Object... values) {
        return new QueryRow(values.clone());
    }

    /**
     * Reads the current row, mapping SQL NULL to {@code null} and BLOB to
     * {@link BlobValue}.
     */
    static QueryRow read(ResultSet rows, int columnCount) throws SQLException {
        Object[] values = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            Object value = rows.getObject(i + 1);
            if (rows.wasNull()) {
                value = null;
            } else if (value instanceof byte[] blob) {
                value = new BlobValue(blob.length);
            }
            values[i] = value;
        }
        return new QueryRow(values);
    }

    public int size() {
        return values.length;
    }

    /** The value at {@code index}, or {@code null} for SQL NULL. */
    public Object value(int index) {
        return values[index];
    }

    public boolean isNull(int index) {
        return values[index] == null;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("QueryRow[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(values[i] == null ? "NULL" : values[i]);
        }
        return text.append(']').toString();
    }
}
