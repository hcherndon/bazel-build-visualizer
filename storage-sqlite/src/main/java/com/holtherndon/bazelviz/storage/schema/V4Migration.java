package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV4} to a session already at version 3. */
final class V4Migration implements Migration {

  @Override
  public int version() {
    return SchemaV4.VERSION;
  }

  @Override
  public String description() {
    return "schema v4: execution-log attempts and the JSON trace profile";
  }

  @Override
  public void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String ddl : SchemaV4.STATEMENTS) {
        statement.execute(ddl);
      }
    }
  }
}
