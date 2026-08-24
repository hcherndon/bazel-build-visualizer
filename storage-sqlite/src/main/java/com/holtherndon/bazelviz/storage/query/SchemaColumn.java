package com.holtherndon.bazelviz.storage.query;

/**
 * One column of one table, as {@code PRAGMA table_info} reports it.
 *
 * @param name column name
 * @param declaredType the declared type, which SQLite does not enforce; empty
 *     when the DDL declared none
 * @param notNull whether a NOT NULL constraint is declared
 * @param primaryKeyPosition 1-based position in the primary key, or 0 when the
 *     column is not part of one
 */
public record SchemaColumn(
        String name, String declaredType, boolean notNull, int primaryKeyPosition) {

    public boolean isPrimaryKey() {
        return primaryKeyPosition > 0;
    }
}
