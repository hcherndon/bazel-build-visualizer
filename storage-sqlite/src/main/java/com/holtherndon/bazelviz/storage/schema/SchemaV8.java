package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/** Schema version 8: graph-query target-scope provenance. */
final class SchemaV8 {

  private SchemaV8() {}

  public static final int VERSION = 8;

  /** Older captures are migrated to explicit UNKNOWN; migration never invents exact evidence. */
  public static final List<String> STATEMENTS =
      List.of(
          "ALTER TABLE graph_sources ADD COLUMN target_scope TEXT"
              + " CHECK (target_scope IS NULL OR target_scope IN"
              + " ('EXACT_BEP_TARGETS', 'REQUESTED_PATTERNS', 'UNKNOWN'))",
          "ALTER TABLE graph_sources ADD COLUMN target_scope_detail TEXT",
          "UPDATE graph_sources SET target_scope = 'UNKNOWN' WHERE target_scope IS NULL");
}
