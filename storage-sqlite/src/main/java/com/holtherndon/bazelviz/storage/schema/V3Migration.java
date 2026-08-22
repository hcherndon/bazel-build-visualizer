package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV3} to a session already at version 2. */
final class V3Migration implements Migration {

    @Override
    public int version() {
        return SchemaV3.VERSION;
    }

    @Override
    public String description() {
        return "schema v3: rename the test summary's timing columns to say they are Bazel's";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SchemaV3.STATEMENTS) {
                statement.execute(ddl);
            }
        }
    }
}
