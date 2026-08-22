package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the frozen schema-v1 tables exactly as {@link SchemaV1#TABLES}
 * declares them.
 *
 * <p>Indexes are deliberately <em>not</em> created here. The Phase 0 SQLite
 * spike measured bulk loading into an unindexed table then building the
 * indexes afterwards as substantially cheaper than maintaining four B-trees
 * per inserted row, so index creation is an explicit finalize step —
 * {@link SchemaIndexes#createAll(Connection)}.
 */
final class V1Migration implements Migration {

    @Override
    public int version() {
        return SchemaV1.VERSION;
    }

    @Override
    public String description() {
        return "schema v1: sessions, streams, raw BEP events, event ids, announced-child edges,"
                + " string dictionary and import diagnostics";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SchemaV1.TABLES) {
                statement.execute(ddl);
            }
        }
    }
}
