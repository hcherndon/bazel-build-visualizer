package com.holtherndon.bazelviz.storage.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Temporary tables that hold an action graph exactly as the file carried it,
 * before any reference is resolved.
 *
 * <h2>Why staging exists at all</h2>
 *
 * <p>{@code aquery} output references entities before it declares them —
 * actions before depsets, depsets before artifacts, path fragments before their
 * parents — on three of the four supported Bazel versions (finding Q4 in
 * {@code docs/aquery-and-cquery.md}). So resolution cannot happen during the
 * read, and everything the file says has to exist somewhere first.
 *
 * <h2>Why in SQLite rather than in Java</h2>
 *
 * <p>"Somewhere" could have been a few hash maps. At the plan's five-million
 * action target that is tens of millions of path fragments and artifacts, and
 * holding them as objects is exactly what plan 19.4 forbids and what
 * {@code CsrGraph} exists to avoid. SQLite's temporary tables spill to disk on
 * their own, so the memory bound is the page cache rather than the graph.
 *
 * <p>They are {@code TEMP} tables: they live in a separate database attached to
 * this connection alone and vanish when it closes, so a crashed import leaves
 * no half-staged rows in the session.
 *
 * <p>Path resolution is a recursive CTE rather than a Java loop, for the same
 * reason: walking millions of parent chains in Java allocates a string per
 * level, and SQLite can do it without leaving the database.
 */
public final class GraphStaging implements AutoCloseable {

    private static final List<String> CREATE = List.of(
            "CREATE TEMP TABLE stage_fragment (id INTEGER PRIMARY KEY, label TEXT NOT NULL,"
                    + " parent INTEGER NOT NULL)",
            "CREATE TEMP TABLE stage_artifact (id INTEGER PRIMARY KEY, fragment INTEGER NOT NULL,"
                    + " is_tree INTEGER NOT NULL)",
            "CREATE TEMP TABLE stage_target (id INTEGER PRIMARY KEY, label TEXT NOT NULL,"
                    + " rule_class_id INTEGER)",
            "CREATE TEMP TABLE stage_rule_class (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            "CREATE TEMP TABLE stage_config (id INTEGER PRIMARY KEY, checksum TEXT,"
                    + " mnemonic TEXT, platform TEXT)",
            "CREATE TEMP TABLE stage_depset (id INTEGER PRIMARY KEY)",
            "CREATE TEMP TABLE stage_depset_child (parent INTEGER NOT NULL,"
                    + " child INTEGER NOT NULL, PRIMARY KEY (parent, child))",
            "CREATE TEMP TABLE stage_depset_artifact (depset INTEGER NOT NULL,"
                    + " artifact INTEGER NOT NULL, PRIMARY KEY (depset, artifact))",
            "CREATE TEMP TABLE stage_action (ordinal INTEGER PRIMARY KEY, target_id INTEGER,"
                    + " mnemonic TEXT, config_id INTEGER, primary_output INTEGER,"
                    + " execution_platform TEXT, action_key TEXT, discovers_inputs INTEGER,"
                    + " is_executable INTEGER)",
            "CREATE TEMP TABLE stage_action_input (ordinal INTEGER NOT NULL,"
                    + " depset INTEGER NOT NULL, PRIMARY KEY (ordinal, depset))",
            "CREATE TEMP TABLE stage_action_output (ordinal INTEGER NOT NULL,"
                    + " artifact INTEGER NOT NULL, PRIMARY KEY (ordinal, artifact))",
            // Resolved paths land here, once, after everything is staged.
            "CREATE TEMP TABLE stage_path (artifact INTEGER PRIMARY KEY, path TEXT NOT NULL)");

    private static final List<String> DROP = List.of(
            "stage_path", "stage_action_output", "stage_action_input", "stage_action",
            "stage_depset_artifact", "stage_depset_child", "stage_depset", "stage_config",
            "stage_rule_class", "stage_target", "stage_artifact", "stage_fragment");

    /**
     * Builds every artifact's path by walking the fragment chain to its root.
     *
     * <p>A recursive CTE, seeded at the fragments with no parent and walking
     * down. {@code parent = 0} is the root marker: proto3 omits a zero, so an
     * absent {@code parent_id} reads as zero, and no real fragment has id zero.
     *
     * <p>Runs after every fragment is staged, which is the whole point — 2 to 4
     * fragments per file name their parent before it is declared (Q4).
     */
    /**
     * How deep a path may be before the chain is treated as malformed.
     *
     * <p>A path fragment chain is a tree, so it terminates. A file that made it
     * a cycle would make this CTE run until the disk filled, and an imported
     * session is untrusted outright (plan 22.4). The bound is far above any
     * real path — the deepest in the probe was six — and turns a hostile file
     * into missing paths, which {@link #unresolvedArtifacts()} then counts.
     */
    private static final int MAX_PATH_DEPTH = 256;

    private static final String RESOLVE_PATHS =
            "INSERT INTO stage_path (artifact, path)"
                    + " WITH RECURSIVE chain(id, path, depth) AS ("
                    + "   SELECT id, label, 1 FROM stage_fragment WHERE parent = 0"
                    + "   UNION ALL"
                    + "   SELECT f.id, chain.path || '/' || f.label, chain.depth + 1"
                    + "     FROM stage_fragment f JOIN chain ON f.parent = chain.id"
                    + "     WHERE chain.depth < " + MAX_PATH_DEPTH + ")"
                    + " SELECT a.id, c.path FROM stage_artifact a JOIN chain c ON c.id = a.fragment";

    private final Connection connection;

    public GraphStaging(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            for (String ddl : CREATE) {
                statement.execute(ddl);
            }
        }
    }

    /** Resolves every artifact's path. Call once, after the whole file is read. */
    public void resolvePaths() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(RESOLVE_PATHS);
        }
    }

    /**
     * Artifacts whose path could not be resolved.
     *
     * <p>Non-zero means the file referenced a path fragment it never declared,
     * which makes every path reached through it unknown rather than merely
     * absent. Counted so the importer can report it instead of quietly
     * producing a smaller graph.
     */
    public long unresolvedArtifacts() throws SQLException {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT count(*) FROM stage_artifact a"
                                + " WHERE NOT EXISTS (SELECT 1 FROM stage_path p"
                                + "                   WHERE p.artifact = a.id)")) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /** A prepared insert into one staging table. */
    public PreparedStatement insert(String sql) throws SQLException {
        return connection.prepareStatement(sql);
    }

    /** The connection the staging tables live on. */
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : DROP) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
