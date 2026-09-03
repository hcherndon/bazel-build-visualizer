package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV6} to a session already at version 5. */
final class V6Migration implements Migration {

  @Override
  public int version() {
    return SchemaV6.VERSION;
  }

  @Override
  public String description() {
    return "schema v6: cquery configuration metadata and effective options";
  }

  @Override
  public void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String ddl : SchemaV6.STATEMENTS) {
        statement.execute(ddl);
      }
    }
  }
}
