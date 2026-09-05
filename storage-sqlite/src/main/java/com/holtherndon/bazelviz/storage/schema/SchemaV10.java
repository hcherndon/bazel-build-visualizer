package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/** Schema version 10: graph node-index uniqueness and ordered lookup. */
final class SchemaV10 {

  private SchemaV10() {}

  public static final int VERSION = 10;

  /**
   * NULL means that no action graph has numbered the row yet, and SQLite permits repeated NULLs in
   * a unique index. Every assigned value is global to the one replaceable declared-action source,
   * so uniqueness is a structural CSR integrity constraint and the covering order used by the
   * separate runtime density validation.
   */
  public static final List<String> STATEMENTS =
      List.of(
          "CREATE UNIQUE INDEX ix_declared_actions_node_index"
              + " ON declared_actions (node_index) WHERE node_index IS NOT NULL");
}
