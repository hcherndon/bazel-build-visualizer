package com.holtherndon.bazelviz.storage.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The staging tables and their one algorithm: path resolution over the
 * fragment tree.
 *
 * <p>Covered until now only through the importer, which cannot stage a
 * malformed chain — its input is a protobuf that always declares parents
 * eventually. These tests stage the pathological shapes directly: the forward
 * reference (finding Q4), the fragment nobody declared, and the cycle a
 * hostile file could carry (plan 22.4).
 */
final class GraphStagingTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;

    @BeforeEach
    void openDatabase() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
    }

    @AfterEach
    void closeDatabase() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("a fragment chain staged child-before-parent still resolves")
    void forwardReferencesResolve() throws Exception {
        try (GraphStaging staging = new GraphStaging(connection)) {
            // The file names fragment 2's parent, 1, before declaring it —
            // three of the four supported Bazel versions do exactly this (Q4).
            exec("INSERT INTO stage_fragment (id, label, parent) VALUES (2, 'obj', 1)");
            exec("INSERT INTO stage_fragment (id, label, parent) VALUES (3, 'a.o', 2)");
            exec("INSERT INTO stage_fragment (id, label, parent) VALUES (1, 'bazel-out', 0)");
            exec("INSERT INTO stage_artifact (id, fragment, is_tree) VALUES (1, 3, 0)");

            staging.resolvePaths();

            assertThat(stagedPath(1)).isEqualTo("bazel-out/obj/a.o");
            assertThat(staging.unresolvedArtifacts()).isZero();
        }
    }

    @Test
    @DisplayName("an artifact whose fragment was never declared is counted, not invented")
    void undeclaredFragmentsAreCounted() throws Exception {
        try (GraphStaging staging = new GraphStaging(connection)) {
            exec("INSERT INTO stage_artifact (id, fragment, is_tree) VALUES (1, 42, 0)");

            staging.resolvePaths();

            // The importer reports this count rather than quietly producing a
            // smaller graph; a made-up path here would poison every join that
            // uses paths as identity.
            assertThat(staging.unresolvedArtifacts()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a cyclic fragment chain terminates and lands in the unresolved count")
    void cyclesAreBoundedNotFatal() throws Exception {
        try (GraphStaging staging = new GraphStaging(connection)) {
            // 1 -> 2 -> 1: no real aquery writes this; a hostile file could.
            exec("INSERT INTO stage_fragment (id, label, parent) VALUES (1, 'x', 2)");
            exec("INSERT INTO stage_fragment (id, label, parent) VALUES (2, 'y', 1)");
            exec("INSERT INTO stage_artifact (id, fragment, is_tree) VALUES (1, 1, 0)");

            staging.resolvePaths();

            assertThat(staging.unresolvedArtifacts()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("referenced artifact ids that were never declared are counted")
    void undeclaredArtifactReferencesAreCounted() throws Exception {
        try (GraphStaging staging = new GraphStaging(connection)) {
            exec("INSERT INTO stage_depset (id) VALUES (1)");
            exec("INSERT INTO stage_depset_artifact (depset, artifact) VALUES (1, 41)");
            exec("INSERT INTO stage_action (ordinal) VALUES (0)");
            exec("INSERT INTO stage_action_output (ordinal, artifact) VALUES (0, 42)");
            exec("INSERT INTO stage_action (ordinal, primary_output) VALUES (1, 43)");

            staging.resolvePaths();

            assertThat(staging.unresolvedArtifacts()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("undeclared action-input and child depsets are counted as missing links")
    void undeclaredDepsetReferencesAreCounted() throws Exception {
        try (GraphStaging staging = new GraphStaging(connection)) {
            exec("INSERT INTO stage_action (ordinal) VALUES (0)");
            exec("INSERT INTO stage_action_input (ordinal, depset) VALUES (0, 41)");
            exec("INSERT INTO stage_depset (id) VALUES (1)");
            exec("INSERT INTO stage_depset_child (parent, child) VALUES (1, 42)");

            assertThat(staging.unresolvedDepsetReferences()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("closing drops every staging table so a reused connection stages fresh")
    void closingDropsTheTables() throws Exception {
        try (GraphStaging staging = new GraphStaging(connection)) {
            exec("INSERT INTO stage_fragment (id, label, parent) VALUES (1, 'x', 0)");
        }

        // The tables are gone, so a second staging on the same connection
        // starts empty rather than inheriting half an import.
        try (GraphStaging staging = new GraphStaging(connection)) {
            assertThat(scalar("SELECT count(*) FROM stage_fragment")).isZero();
        }
    }

    // ------------------------------------------------------------- plumbing

    private String stagedPath(int artifact) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT path FROM stage_path WHERE artifact = " + artifact)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
