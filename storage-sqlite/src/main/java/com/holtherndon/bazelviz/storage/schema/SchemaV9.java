package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/** Schema version 9: queryable Starlark CPU pprof data and attributed indexes. */
final class SchemaV9 {

  private SchemaV9() {}

  public static final int VERSION = 9;

  /**
   * The complete raw pprof file remains in the session's raw directory. These tables preserve its
   * queryable sample and symbol structure. Derived metrics and the physical call tree are rebuilt
   * from the sample stacks; metadata states how much CPU could be attributed to symbols and source
   * files.
   */
  public static final List<String> STATEMENTS =
      List.of(
          """
          CREATE TABLE starlark_profile_metadata (
            id                           INTEGER PRIMARY KEY CHECK (id = 1),
            task_id                      INTEGER NOT NULL REFERENCES enrichment_tasks(id),
            format                       TEXT    NOT NULL,
            compressed_bytes             INTEGER NOT NULL,
            uncompressed_bytes           INTEGER NOT NULL,
            profile_time_nanos           INTEGER,
            duration_nanos               INTEGER,
            period                       INTEGER,
            period_type_string_index     INTEGER,
            period_unit_string_index     INTEGER,
            normalized_period_micros     INTEGER,
            drop_frames_string_index     INTEGER,
            keep_frames_string_index     INTEGER,
            default_sample_type_string_index INTEGER,
            selected_sample_type_ordinal INTEGER,
            doc_url_string_index         INTEGER,
            sample_count                 INTEGER NOT NULL DEFAULT 0,
            mapping_count                INTEGER NOT NULL DEFAULT 0,
            location_count               INTEGER NOT NULL DEFAULT 0,
            function_count               INTEGER NOT NULL DEFAULT 0,
            string_count                 INTEGER NOT NULL DEFAULT 0,
            max_stack_depth              INTEGER NOT NULL DEFAULT 0,
            total_value                  INTEGER,
            function_attributed_value    INTEGER NOT NULL DEFAULT 0,
            function_attributed_samples  INTEGER NOT NULL DEFAULT 0,
            file_attributed_value        INTEGER NOT NULL DEFAULT 0,
            file_attributed_samples      INTEGER NOT NULL DEFAULT 0,
            context_attributed_value     INTEGER NOT NULL DEFAULT 0,
            context_attributed_samples   INTEGER NOT NULL DEFAULT 0,
            validation_state             TEXT    NOT NULL,
            validation_detail            TEXT
          )
          """,
          """
          CREATE TABLE starlark_profile_strings (
            string_index INTEGER PRIMARY KEY,
            value        TEXT NOT NULL
          )
          """,
          """
          CREATE TABLE starlark_profile_sample_types (
            ordinal           INTEGER PRIMARY KEY,
            type_string_index INTEGER NOT NULL,
            unit_string_index INTEGER NOT NULL
          )
          """,
          """
          CREATE TABLE starlark_profile_mappings (
            mapping_id       INTEGER PRIMARY KEY CHECK (mapping_id > 0),
            memory_start     INTEGER,
            memory_limit     INTEGER,
            file_offset      INTEGER,
            filename_string_index INTEGER,
            build_id_string_index  INTEGER,
            has_functions    INTEGER NOT NULL,
            has_filenames    INTEGER NOT NULL,
            has_line_numbers INTEGER NOT NULL,
            has_inline_frames INTEGER NOT NULL
          )
          """,
          """
          CREATE TABLE starlark_profile_functions (
            function_id             INTEGER PRIMARY KEY CHECK (function_id > 0),
            name_string_index        INTEGER NOT NULL,
            system_name_string_index INTEGER NOT NULL,
            filename_string_index    INTEGER NOT NULL,
            start_line               INTEGER
          )
          """,
          """
          CREATE TABLE starlark_profile_locations (
            location_id INTEGER PRIMARY KEY CHECK (location_id > 0),
            mapping_id  INTEGER,
            address     INTEGER,
            is_folded   INTEGER NOT NULL
          )
          """,
          """
          CREATE TABLE starlark_profile_location_lines (
            location_id INTEGER NOT NULL REFERENCES starlark_profile_locations(location_id),
            ordinal     INTEGER NOT NULL,
            function_id INTEGER NOT NULL,
            line        INTEGER,
            column      INTEGER,
            PRIMARY KEY (location_id, ordinal)
          ) WITHOUT ROWID
          """,
          """
          CREATE TABLE starlark_profile_samples (
            sample_id   INTEGER PRIMARY KEY,
            stack_depth INTEGER NOT NULL
          )
          """,
          """
          CREATE TABLE starlark_profile_sample_values (
            sample_id INTEGER NOT NULL REFERENCES starlark_profile_samples(sample_id),
            ordinal   INTEGER NOT NULL,
            value     INTEGER NOT NULL,
            PRIMARY KEY (sample_id, ordinal)
          ) WITHOUT ROWID
          """,
          """
          CREATE TABLE starlark_profile_sample_frames (
            sample_id      INTEGER NOT NULL REFERENCES starlark_profile_samples(sample_id),
            ordinal        INTEGER NOT NULL,
            location_id    INTEGER NOT NULL,
            PRIMARY KEY (sample_id, ordinal)
          ) WITHOUT ROWID
          """,
          """
          CREATE TABLE starlark_profile_sample_labels (
            sample_id               INTEGER NOT NULL REFERENCES starlark_profile_samples(sample_id),
            ordinal                 INTEGER NOT NULL,
            key_string_index        INTEGER NOT NULL,
            string_value_index      INTEGER,
            numeric_value           INTEGER,
            numeric_unit_string_index INTEGER,
            value_kind              TEXT NOT NULL,
            PRIMARY KEY (sample_id, ordinal)
          ) WITHOUT ROWID
          """,
          """
          CREATE TABLE starlark_call_nodes (
            node_id           INTEGER PRIMARY KEY,
            parent_node_id    INTEGER REFERENCES starlark_call_nodes(node_id),
            location_id       INTEGER REFERENCES starlark_profile_locations(location_id),
            depth             INTEGER NOT NULL,
            inclusive_value   INTEGER NOT NULL,
            self_value        INTEGER NOT NULL,
            sample_count      INTEGER NOT NULL,
            self_sample_count INTEGER NOT NULL,
            CHECK ((depth = 0 AND parent_node_id IS NULL AND location_id IS NULL)
                OR (depth > 0 AND parent_node_id IS NOT NULL AND location_id IS NOT NULL)),
            UNIQUE (parent_node_id, location_id)
          )
          """,
          """
          CREATE TABLE starlark_function_metrics (
            function_id       INTEGER PRIMARY KEY REFERENCES starlark_profile_functions(function_id),
            self_value        INTEGER NOT NULL,
            cumulative_value  INTEGER NOT NULL,
            self_samples      INTEGER NOT NULL,
            cumulative_samples INTEGER NOT NULL,
            context_count      INTEGER NOT NULL DEFAULT 0
          )
          """,
          """
          CREATE TABLE starlark_file_metrics (
            filename_string_index INTEGER PRIMARY KEY,
            self_value            INTEGER NOT NULL,
            cumulative_value      INTEGER NOT NULL,
            self_samples          INTEGER NOT NULL,
            cumulative_samples    INTEGER NOT NULL,
            function_count        INTEGER NOT NULL DEFAULT 0
          )
          """,
          """
          CREATE TABLE starlark_call_edges (
            caller_location_id INTEGER NOT NULL REFERENCES starlark_profile_locations(location_id),
            callee_location_id INTEGER NOT NULL REFERENCES starlark_profile_locations(location_id),
            value              INTEGER NOT NULL,
            sample_count       INTEGER NOT NULL,
            PRIMARY KEY (caller_location_id, callee_location_id)
          ) WITHOUT ROWID
          """,
          "CREATE INDEX ix_starlark_profile_strings_value" + " ON starlark_profile_strings (value)",
          "CREATE INDEX ix_starlark_profile_functions_filename"
              + " ON starlark_profile_functions (filename_string_index)",
          "CREATE INDEX ix_starlark_location_lines_function"
              + " ON starlark_profile_location_lines (function_id)",
          "CREATE INDEX ix_starlark_sample_frames_location"
              + " ON starlark_profile_sample_frames (location_id, sample_id)",
          "CREATE INDEX ix_starlark_sample_values_ordinal"
              + " ON starlark_profile_sample_values (ordinal, sample_id)",
          "CREATE INDEX ix_starlark_call_nodes_parent"
              + " ON starlark_call_nodes (parent_node_id, inclusive_value DESC)",
          "CREATE INDEX ix_starlark_call_nodes_depth" + " ON starlark_call_nodes (depth, node_id)",
          "CREATE INDEX ix_starlark_function_metrics_self"
              + " ON starlark_function_metrics (self_value DESC, function_id)",
          "CREATE INDEX ix_starlark_function_metrics_cumulative"
              + " ON starlark_function_metrics (cumulative_value DESC, function_id)",
          "CREATE INDEX ix_starlark_file_metrics_self"
              + " ON starlark_file_metrics (self_value DESC, filename_string_index)",
          "CREATE INDEX ix_starlark_file_metrics_cumulative"
              + " ON starlark_file_metrics (cumulative_value DESC, filename_string_index)",
          "CREATE INDEX ix_starlark_call_edges_callee"
              + " ON starlark_call_edges (callee_location_id, value DESC)",
          """
          CREATE VIEW starlark_hot_functions AS
          SELECT m.function_id,
                 n.value AS function_name,
                 f.value AS filename,
                 fn.start_line,
                 m.self_value,
                 m.cumulative_value,
                 m.self_samples,
                 m.cumulative_samples,
                 m.context_count
            FROM starlark_function_metrics m
            JOIN starlark_profile_functions fn ON fn.function_id = m.function_id
            LEFT JOIN starlark_profile_strings n
              ON n.string_index = fn.name_string_index
            LEFT JOIN starlark_profile_strings f
              ON f.string_index = fn.filename_string_index
          """,
          """
          CREATE VIEW starlark_hot_files AS
          SELECT m.filename_string_index,
                 f.value AS filename,
                 m.self_value,
                 m.cumulative_value,
                 m.self_samples,
                 m.cumulative_samples,
                 m.function_count
            FROM starlark_file_metrics m
            LEFT JOIN starlark_profile_strings f
              ON f.string_index = m.filename_string_index
          """,
          """
          CREATE VIEW starlark_resolved_call_edges AS
          SELECT e.caller_location_id,
                 caller_line.function_id AS caller_function_id,
                 caller_name.value AS caller_function,
                 caller_file.value AS caller_file,
                 e.callee_location_id,
                 callee_line.function_id AS callee_function_id,
                 callee_name.value AS callee_function,
                 callee_file.value AS callee_file,
                 e.value,
                 e.sample_count
            FROM starlark_call_edges e
            LEFT JOIN starlark_profile_location_lines caller_line
              ON caller_line.location_id = e.caller_location_id
             AND caller_line.ordinal = 0
            LEFT JOIN starlark_profile_functions caller_fn
              ON caller_fn.function_id = caller_line.function_id
            LEFT JOIN starlark_profile_strings caller_name
              ON caller_name.string_index = caller_fn.name_string_index
            LEFT JOIN starlark_profile_strings caller_file
              ON caller_file.string_index = caller_fn.filename_string_index
            LEFT JOIN starlark_profile_location_lines callee_line
              ON callee_line.location_id = e.callee_location_id
             AND callee_line.ordinal = 0
            LEFT JOIN starlark_profile_functions callee_fn
              ON callee_fn.function_id = callee_line.function_id
            LEFT JOIN starlark_profile_strings callee_name
              ON callee_name.string_index = callee_fn.name_string_index
            LEFT JOIN starlark_profile_strings callee_file
              ON callee_file.string_index = callee_fn.filename_string_index
          """,
          """
          CREATE VIEW starlark_resolved_call_nodes AS
          SELECT n.node_id,
                 n.parent_node_id,
                 n.location_id,
                 line.function_id,
                 name.value AS function_name,
                 file.value AS filename,
                 line.line,
                 line.column,
                 n.depth,
                 n.inclusive_value,
                 n.self_value,
                 n.sample_count,
                 n.self_sample_count
            FROM starlark_call_nodes n
            LEFT JOIN starlark_profile_location_lines line
              ON line.location_id = n.location_id AND line.ordinal = 0
            LEFT JOIN starlark_profile_functions fn
              ON fn.function_id = line.function_id
            LEFT JOIN starlark_profile_strings name
              ON name.string_index = fn.name_string_index
            LEFT JOIN starlark_profile_strings file
              ON file.string_index = fn.filename_string_index
          """);
}
