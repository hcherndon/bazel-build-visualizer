package com.holtherndon.bazelviz.storage.query;

import java.util.List;

/**
 * One table or view in the open session database, read from
 * {@code sqlite_master} and {@code PRAGMA table_info} at runtime rather than
 * from a generated list.
 *
 * <p>Runtime is the only honest source. The session database carries many
 * schema versions' worth of migrations and tables, a session may have been
 * written by a build whose DDL differs from this one's, and there is no
 * generated schema page to fall back on — so the browser reads what the file
 * actually contains.
 *
 * <p>No row count. Counting every table on a five-million-action session is a
 * scan per table, and showing a count that had not been taken yet as {@code 0}
 * would break rule 11. Ask for one with {@code SELECT COUNT(*) FROM …}.
 *
 * @param name table or view name
 * @param kind {@code "table"} or {@code "view"}, exactly as sqlite_master spells it
 * @param schema {@code "main"} for the session file's own objects, or
 *     {@code "temp"} for this connection's temporary views — which exist on
 *     this connection alone and vanish when it closes, so the browser has to
 *     say which is which or a temp view reads as part of the session
 * @param columns its columns in declaration order
 * @param ddl the CREATE statement sqlite_master stores, or empty when SQLite
 *     stores none (it does not for some internal tables)
 */
public record SchemaTable(
        String name, String kind, String schema, List<SchemaColumn> columns, String ddl) {

    public SchemaTable {
        columns = List.copyOf(columns);
    }

    public boolean isView() {
        return "view".equalsIgnoreCase(kind);
    }

    /** True for a temporary view: per-connection, and never in the session file. */
    public boolean isTemp() {
        return "temp".equalsIgnoreCase(schema);
    }
}
