package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV9} to a session already at version 8. */
final class V9Migration implements Migration {

  @Override
  public int version() {
    return SchemaV9.VERSION;
  }

  @Override
  public String description() {
    return "schema v9: Starlark CPU profiles";
  }

  @Override
  public void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String ddl : SchemaV9.STATEMENTS) {
        statement.execute(ddl);
      }
    }
  }
}
