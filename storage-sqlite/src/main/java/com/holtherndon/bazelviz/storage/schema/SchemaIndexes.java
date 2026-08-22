package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The contract's secondary indexes, created as an explicit finalize step after
 * bulk load rather than as part of the migration.
 *
 * <p>Why this is separate from {@link MigrationRunner}: every index present
 * during ingestion costs a B-tree insert per row, and the Phase 0 spike
 * measured building all four afterwards (1.6 s over 2M rows, index creation
 * plus {@code ANALYZE}) as far cheaper than paying that cost 2M times. Making
 * it a method rather than an implicit side effect also means a caller can see
 * — and a recovery path can repeat — exactly when the indexes exist.
 *
 * <p>All statements use {@code IF NOT EXISTS}, so calling this twice, or on a
 * session that finished importing in an earlier run, is a no-op. That is what
 * makes it safe for crash recovery to call unconditionally (plan 21.1 step 6,
 * "rebuild incomplete indexes").
 *
 * <p>A session whose import crashed before this ran is still <em>correct</em>,
 * just slower to query: every statement in {@code EventQueries} works without
 * these indexes, using the primary key or the {@code UNIQUE (stream_id,
 * sequence)} index the table definition already carries.
 */
public final class SchemaIndexes {

    private SchemaIndexes() {}

    /**
     * Creates every index in {@link SchemaV1#INDEXES}. Commits when the
     * connection is not in auto-commit mode, so an ingestion that suspended
     * auto-commit for batching does not leave the DDL uncommitted.
     */
    public static void createAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SchemaV1.INDEXES) {
                statement.execute(ddl);
            }
        }
        commitIfNeeded(connection);
    }

    /**
     * Refreshes the query planner's statistics. Plan 10.9 requires this after
     * major finalization stages: without it SQLite plans page queries against
     * the statistics of an empty table.
     */
    public static void analyze(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE");
        }
        commitIfNeeded(connection);
    }

    private static void commitIfNeeded(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }
}
