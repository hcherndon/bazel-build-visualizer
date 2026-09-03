package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV8} to a session already at version 7. */
final class V8Migration implements Migration {

  @Override
  public int version() {
    return SchemaV8.VERSION;
  }

  @Override
  public String description() {
    return "schema v8: graph-query target-scope provenance";
  }

  @Override
  public void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String ddl : SchemaV8.STATEMENTS) {
        statement.execute(ddl);
      }
    }
  }
}
