package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV7} to a session already at version 6. */
final class V7Migration implements Migration {

  @Override
  public int version() {
    return SchemaV7.VERSION;
  }

  @Override
  public String description() {
    return "schema v7: exact action-graph structural completeness metadata";
  }

  @Override
  public void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String ddl : SchemaV7.STATEMENTS) {
        statement.execute(ddl);
      }
    }
  }
}
