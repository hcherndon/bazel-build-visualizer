package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/** Schema version 7: exact action-graph structural completeness metadata. */
final class SchemaV7 {

  private SchemaV7() {}

  public static final int VERSION = 7;

  /**
   * Nullable on purpose: sessions imported before v7 did not retain this measurement, and unknown
   * must not be rewritten as a confirmed zero.
   */
  public static final List<String> STATEMENTS =
      List.of(
          "ALTER TABLE graph_sources ADD COLUMN unresolved_artifacts INTEGER"
              + " CHECK (unresolved_artifacts IS NULL OR unresolved_artifacts >= 0)",
          "ALTER TABLE graph_sources ADD COLUMN unresolved_depset_references INTEGER"
              + " CHECK (unresolved_depset_references IS NULL"
              + " OR unresolved_depset_references >= 0)");
}
