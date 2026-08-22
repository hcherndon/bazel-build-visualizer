package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Adds the schema-v2 tables (Phase 3 normalization) to a session that already
 * has schema v1.
 *
 * <p>Purely additive: no v1 table is altered and no v1 row is touched, so a
 * session captured by a Phase 2 build opens under a Phase 3 build and gains the
 * new tables empty. Whether those tables are then <em>populated</em> is a
 * separate question — the raw journal is the source of truth (ADR-004), so a
 * v1 session can be reindexed from its own bytes rather than being stuck
 * half-normalized.
 *
 * <p>Indexes are not created here, for the same reason they are not in v1: the
 * Phase 0 SQLite spike measured bulk loading into unindexed tables then
 * building indexes afterwards as substantially cheaper than maintaining a
 * B-tree per inserted row.
 */
final class V2Migration implements Migration {

    @Override
    public int version() {
        return SchemaV2.VERSION;
    }

    @Override
    public String description() {
        return "schema v2: configurations, targets, actions, artifacts, file sets, tests,"
                + " build metrics and aborted events";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SchemaV2.TABLES) {
                statement.execute(ddl);
            }
        }
    }
}
