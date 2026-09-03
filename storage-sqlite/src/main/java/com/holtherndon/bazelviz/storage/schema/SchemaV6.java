package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/**
 * Schema version 6: cquery configuration metadata and effective option values.
 *
 * <p>The BEP configuration table remains the record of what the build reported. These tables are
 * separate because they come from the post-build cquery and therefore carry their own {@code
 * graph_sources} provenance. Joining the two by checksum is measured in Q6 of {@code
 * docs/aquery-and-cquery.md}.
 */
final class SchemaV6 {

  private SchemaV6() {}

  public static final int VERSION = 6;

  public static final List<String> STATEMENTS =
      List.of(
          """
          CREATE TABLE queried_configurations (
            id                INTEGER PRIMARY KEY,
            source_id         INTEGER NOT NULL REFERENCES graph_sources(id),
            graph_id          INTEGER NOT NULL,
            checksum          TEXT    NOT NULL,
            mnemonic          TEXT,
            platform_name     TEXT,
            is_tool           INTEGER NOT NULL,
            options_available INTEGER NOT NULL,
            UNIQUE (source_id, graph_id),
            UNIQUE (source_id, checksum)
          )
          """,
          "CREATE INDEX ix_queried_configurations_checksum"
              + " ON queried_configurations (checksum)",
          // An empty option_set_name is an explicit fragment with no option
          // sets, not an unknown value. It keeps that fragment visible while
          // retaining a non-null composite key.
          """
          CREATE TABLE queried_configuration_fragments (
            configuration_id INTEGER NOT NULL
                REFERENCES queried_configurations(id) ON DELETE CASCADE,
            fragment_name     TEXT    NOT NULL,
            option_set_name   TEXT    NOT NULL,
            ordinal           INTEGER NOT NULL,
            PRIMARY KEY (configuration_id, fragment_name, option_set_name)
          )
          """,
          """
          CREATE TABLE queried_configuration_options (
            configuration_id INTEGER NOT NULL
                REFERENCES queried_configurations(id) ON DELETE CASCADE,
            option_set_name   TEXT    NOT NULL,
            option_name       TEXT    NOT NULL,
            option_value      TEXT,
            redacted          INTEGER NOT NULL DEFAULT 0,
            ordinal           INTEGER NOT NULL,
            PRIMARY KEY (configuration_id, option_set_name, ordinal)
          )
          """,
          "CREATE INDEX ix_queried_configuration_options_name"
              + " ON queried_configuration_options"
              + " (configuration_id, option_set_name, option_name)");
}
