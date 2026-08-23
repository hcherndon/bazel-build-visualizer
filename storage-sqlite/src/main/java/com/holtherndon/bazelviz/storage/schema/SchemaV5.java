package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/**
 * Schema version 5: the declared action graph, the configured-target graph,
 * and the action edges derived from them.
 *
 * <h2>Why these are separate tables and not more columns on `actions`</h2>
 *
 * <p>Phase 3's {@code actions} table is what the build event stream said
 * <em>executed</em>. {@code aquery} reports what analysis <em>declared</em>.
 * These are two populations that overlap and neither contains the other:
 * measured, {@code aquery} declares test-log actions a {@code build} never runs,
 * and the BEP reports a {@code stable-status.txt} action {@code aquery} never
 * declares (Q7 in {@code docs/aquery-and-cquery.md}).
 *
 * <p>So a declared action gets its own row, with a nullable link to the executed
 * one. Merging them would produce a table that is the build's action count in
 * neither sense, and ADR-009 forbids collapsing two sources into one unexplained
 * number anyway.
 *
 * <p>Every non-obvious choice below cites a finding id from
 * {@code docs/aquery-and-cquery.md}.
 */
final class SchemaV5 {

    private SchemaV5() {}

    public static final int VERSION = 5;

    public static final List<String> STATEMENTS = List.of(

            // --- where a graph came from -----------------------------------------
            //
            // Plan 12.4 requires a graph that could not reproduce the primary
            // invocation's configuration to be marked PARTIAL_OR_MISMATCHED,
            // with the omitted or changed options listed, and forbids silently
            // attaching uncertain graph data. That is a row, not a convention.
            //
            // configuration_match is the answer to the one question that
            // decides it: did every configuration this query reported also
            // appear in the build's event stream? The join key is the
            // checksum, which was equal to the BEP's configuration id on all
            // four versions with none left over (Q6).
            //
            // command is kept so the user can see what ran and rerun it edited,
            // which plan 12.4 also requires. It is stored as a JSON array for
            // the same reason `actions.command_line` is: an argument can
            // contain anything, including a newline.
            """
            CREATE TABLE graph_sources (
              id                      INTEGER PRIMARY KEY,
              kind                    TEXT    NOT NULL,
              command                 TEXT,
              exit_code               INTEGER,
              state                   TEXT    NOT NULL,
              error_excerpt           TEXT,
              configuration_match     TEXT    NOT NULL,
              mismatch_detail         TEXT,
              raw_output_path         TEXT,
              raw_output_bytes        INTEGER,
              declared_actions        INTEGER,
              correlated_actions      INTEGER,
              started_micros          INTEGER,
              finished_micros         INTEGER
            )
            """,
            "CREATE UNIQUE INDEX ux_graph_sources_kind ON graph_sources (kind)",

            // --- the declared action graph ---------------------------------------
            //
            // action_id is nullable and usually set. Where it is null the
            // action was declared and never ran, which for a `build` invocation
            // is every test's TestRunner action -- normal, and not a gap.
            //
            // configuration_checksum rather than a foreign key to
            // `configurations`: aquery's own configuration ids are small
            // integers valid only inside one query's output (Q6), so storing
            // them would be storing a number that means nothing outside the
            // file it came from. The checksum is what crosses the boundary, and
            // it is the BEP's configuration id.
            //
            // is_executable holds 1 or NULL and never 0. Bazel 6.5.0 does not
            // emit the field at all, but it is a proto3 bool without explicit
            // presence, so nothing downstream of the parser can tell "this
            // version never says" from "this action is not executable" (Q9).
            // Recording only the positive keeps the column honest about a
            // distinction the encoding threw away.
            //
            // action_key is stored and not used as a join key. Nothing has
            // measured whether it is stable between two runs of the same build,
            // and section 5 of the ground truth says so.
            """
            CREATE TABLE declared_actions (
              id                     INTEGER PRIMARY KEY,
              source_id              INTEGER NOT NULL REFERENCES graph_sources(id),
              graph_id               INTEGER NOT NULL,
              action_id              INTEGER REFERENCES actions(id),
              label_id               INTEGER REFERENCES labels(id),
              mnemonic_id            INTEGER REFERENCES mnemonics(id),
              configuration_checksum TEXT,
              primary_output_id      INTEGER REFERENCES artifacts(id),
              execution_platform     TEXT,
              action_key             TEXT,
              discovers_inputs       INTEGER,
              is_executable          INTEGER,
              UNIQUE (source_id, graph_id)
            )
            """,
            "CREATE INDEX ix_declared_actions_action ON declared_actions (action_id)",
            "CREATE INDEX ix_declared_actions_output ON declared_actions (primary_output_id)",
            "CREATE INDEX ix_declared_actions_label ON declared_actions (label_id)",

            // Outputs are a list, not one primary. The primary is on the action
            // row because it is the identity; these are the rest.
            """
            CREATE TABLE declared_action_outputs (
              action_row_id INTEGER NOT NULL REFERENCES declared_actions(id),
              artifact_id   INTEGER NOT NULL REFERENCES artifacts(id),
              PRIMARY KEY (action_row_id, artifact_id)
            )
            """,
            "CREATE INDEX ix_declared_outputs_artifact"
                    + " ON declared_action_outputs (artifact_id)",

            // --- the declared input DAG ------------------------------------------
            //
            // aquery's DepSetOfFiles, kept as the DAG it is. Plan 10.7 forbids
            // eagerly flattening it into duplicated per-action input rows, and
            // at five million actions the flattened form does not fit.
            //
            // These are NOT the BEP's depsets. They describe declared inputs;
            // the BEP's describe target outputs, and the execution log's
            // input_sets describe what a spawn actually consumed. Three sources,
            // three tables, per ADR-009.
            """
            CREATE TABLE graph_depsets (
              id        INTEGER PRIMARY KEY,
              source_id INTEGER NOT NULL REFERENCES graph_sources(id),
              graph_id  INTEGER NOT NULL,
              UNIQUE (source_id, graph_id)
            )
            """,
            """
            CREATE TABLE graph_depset_children (
              parent_id INTEGER NOT NULL REFERENCES graph_depsets(id),
              child_id  INTEGER NOT NULL REFERENCES graph_depsets(id),
              PRIMARY KEY (parent_id, child_id)
            )
            """,
            """
            CREATE TABLE graph_depset_artifacts (
              depset_id   INTEGER NOT NULL REFERENCES graph_depsets(id),
              artifact_id INTEGER NOT NULL REFERENCES artifacts(id),
              PRIMARY KEY (depset_id, artifact_id)
            )
            """,
            "CREATE INDEX ix_graph_depset_artifacts_artifact"
                    + " ON graph_depset_artifacts (artifact_id)",
            """
            CREATE TABLE declared_action_inputs (
              action_row_id INTEGER NOT NULL REFERENCES declared_actions(id),
              depset_id     INTEGER NOT NULL REFERENCES graph_depsets(id),
              PRIMARY KEY (action_row_id, depset_id)
            )
            """,

            // --- the configured-target graph -------------------------------------
            //
            // From cquery. The edges come from blaze_query.Target's rule inputs,
            // which are labels rather than configured targets, so an edge here
            // is between labels within one configuration and not between
            // (label, configuration) pairs -- cquery's proto output does not say
            // which configuration a dependency resolved to.
            //
            // Recording that limit in the table's shape rather than in prose:
            // there is no to_configuration column, because there is nothing to
            // put in it.
            """
            CREATE TABLE configured_target_nodes (
              id                     INTEGER PRIMARY KEY,
              source_id              INTEGER NOT NULL REFERENCES graph_sources(id),
              label_id               INTEGER NOT NULL REFERENCES labels(id),
              configuration_checksum TEXT,
              rule_class             TEXT,
              UNIQUE (source_id, label_id, configuration_checksum)
            )
            """,
            "CREATE INDEX ix_configured_target_nodes_label"
                    + " ON configured_target_nodes (label_id)",
            """
            CREATE TABLE configured_target_edges (
              from_node_id INTEGER NOT NULL REFERENCES configured_target_nodes(id),
              to_label_id  INTEGER NOT NULL REFERENCES labels(id),
              attribute    TEXT,
              PRIMARY KEY (from_node_id, to_label_id, attribute)
            )
            """,
            "CREATE INDEX ix_configured_target_edges_to"
                    + " ON configured_target_edges (to_label_id)",

            // --- derived action edges --------------------------------------------
            //
            // Plan 13.1: for each action, resolve its input artifacts, find each
            // artifact's producing action, emit a producer-to-consumer edge,
            // exclude source artifacts with no producer, deduplicate.
            //
            // derivation records which inputs the edge came from -- declared
            // (aquery) or observed (the execution log) -- because the two
            // produce different graphs and plan 13.1 step 7 requires the
            // difference to survive.
            //
            // via_artifact_id is nullable: it is the artifact that produced the
            // edge, kept when one artifact alone explains it and dropped when
            // several do, because the pair is deduplicated and the last
            // artifact to justify it is not a meaningful answer.
            //
            // No foreign key from producer/consumer to a single table: an edge
            // is between declared_actions rows, which is where both endpoints
            // live regardless of whether either ran.
            """
            CREATE TABLE action_edges (
              producer_id     INTEGER NOT NULL REFERENCES declared_actions(id),
              consumer_id     INTEGER NOT NULL REFERENCES declared_actions(id),
              derivation      TEXT    NOT NULL,
              via_artifact_id INTEGER REFERENCES artifacts(id),
              PRIMARY KEY (producer_id, consumer_id, derivation)
            )
            """,
            "CREATE INDEX ix_action_edges_consumer ON action_edges (consumer_id)",

            // --- persisted CSR indexes -------------------------------------------
            //
            // The index itself is a file (plan 13.2): header, node and edge
            // counts, offset array, target array, checksum, memory-mapped and
            // built by atomic rename. This table is the registry that says which
            // files exist, what they were built from, and whether they are
            // still current -- a stale index is worse than none, because it
            // answers.
            //
            // edge_count and node_count are stored so a reader can check the
            // file's header against what the database says without mapping it.
            """
            CREATE TABLE graph_indexes (
              id            INTEGER PRIMARY KEY,
              kind          TEXT    NOT NULL,
              direction     TEXT    NOT NULL,
              file_name     TEXT    NOT NULL,
              format_version INTEGER NOT NULL,
              node_count    INTEGER NOT NULL,
              edge_count    INTEGER NOT NULL,
              checksum      TEXT    NOT NULL,
              built_micros  INTEGER NOT NULL,
              source_id     INTEGER REFERENCES graph_sources(id),
              UNIQUE (kind, direction)
            )
            """,

            // --- what may be claimed about a graph -------------------------------
            //
            // Rule 13: never claim a graph or total is complete unless its
            // source supports it. One row per GraphKind the session holds,
            // carrying the sentence the UI shows.
            //
            // node_count and edge_count are the graph's own size; they are NOT
            // the build's action count and nothing may present them as such.
            """
            CREATE TABLE graph_completeness (
              kind        TEXT PRIMARY KEY,
              completeness TEXT NOT NULL,
              node_count  INTEGER,
              edge_count  INTEGER,
              detail      TEXT NOT NULL
            )
            """);
}
