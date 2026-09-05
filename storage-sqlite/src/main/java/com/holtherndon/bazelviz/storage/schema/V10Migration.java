package com.holtherndon.bazelviz.storage.schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Applies {@link SchemaV10} to a session already at version 9. */
final class V10Migration implements Migration {

  @Override
  public int version() {
    return SchemaV10.VERSION;
  }

  @Override
  public String description() {
    return "schema v10: unique graph node indexes";
  }

  @Override
  public void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String ddl : SchemaV10.STATEMENTS) {
        statement.execute(ddl);
      }
    }
  }
}
