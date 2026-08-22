package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV5} to a session already at version 4. */
final class V5Migration implements Migration {

    @Override
    public int version() {
        return SchemaV5.VERSION;
    }

    @Override
    public String description() {
        return "schema v5: the declared action graph, the configured-target graph and edges";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SchemaV5.STATEMENTS) {
                statement.execute(ddl);
            }
        }
    }
}
