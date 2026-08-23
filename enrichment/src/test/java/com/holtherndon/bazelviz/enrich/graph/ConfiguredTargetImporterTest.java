package com.holtherndon.bazelviz.enrich.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Importing a configured-target graph Bazel actually produced.
 *
 * <p>The fixtures are {@code cquery --output=proto} output from real Bazel
 * 7.6.1 and 9.2.0 — the two sides of the change that made {@code cquery} carry
 * every configuration's full option set (finding Q11).
 */
final class ConfiguredTargetImporterTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;

    @BeforeEach
    void buildSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
    }

    @AfterEach
    void closeSession() throws Exception {
        database.close();
    }

    @ParameterizedTest(name = "Bazel {0}")
    @ValueSource(strings = {"bazel761", "bazel920"})
    @DisplayName("the six configured targets and their dependencies import")
    void everyVersionImports(String fixture) throws Exception {
        ConfiguredTargetImporter.Result result = importFixture(fixture);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.nodes()).isEqualTo(6);
        // 22 rule_input entries across the six rules, on every version.
        assertThat(result.edges()).isPositive();
    }

    @Test
    @DisplayName("an edge points at a label, and that label need not be a node")
    void edgesReachSourceFiles() throws Exception {
        importFixture("bazel920");

        // A rule's inputs include its source files, which are not configured
        // targets and never appear as nodes. An importer that only stored
        // edges between known nodes would drop most of the graph.
        assertThat(scalar("SELECT count(*) FROM configured_target_edges e"
                + " WHERE NOT EXISTS (SELECT 1 FROM configured_target_nodes n"
                + "                   WHERE n.label_id = e.to_label_id)")).isPositive();
    }

    @Test
    @DisplayName("the dependency's own configuration is absent, because Bazel never sends it")
    void dependencyConfigurationIsNeverKnown() throws Exception {
        importFixture("bazel920");

        // Rule.configured_rule_input exists and carries a configuration
        // checksum; Bazel populates it zero times on all four versions, with
        // and without --proto:include_configurations (Q12). The table has no
        // column for it, so the absence is structural rather than a null
        // nobody explains.
        assertThat(columns("configured_target_edges"))
                .containsExactlyInAnyOrder("from_node_id", "to_label_id", "attribute");
    }

    @Test
    @DisplayName("a node carries the configuration it was analysed in")
    void nodesCarryTheirConfiguration() throws Exception {
        importFixture("bazel920");

        assertThat(scalar("SELECT count(*) FROM configured_target_nodes"
                + " WHERE configuration_checksum IS NOT NULL")).isEqualTo(6);
        assertThat(text("SELECT configuration_checksum FROM configured_target_nodes LIMIT 1"))
                .hasSize(64);
    }

    @Test
    @DisplayName("the rule class is kept, so a node knows what kind of target it is")
    void ruleClassesSurvive() throws Exception {
        importFixture("bazel920");

        assertThat(ruleClasses()).contains("genrule");
    }

    @Test
    @DisplayName("a graph whose configurations are the build's is called exact")
    void matchingConfigurationsAreExact() throws Exception {
        for (String checksum : List.of(
                "1a589d14ca3886895c1228db75ec6c30d0c253d2c9f4c3070e5f3535de94c607",
                "2d8934052f1445fdec9fefac5a616f1fb9d9dea67b8c1b3f6e1572370634272c")) {
            exec("INSERT INTO configurations (stream_id, bep_id, declared)"
                    + " VALUES (1, '" + checksum + "', 1)");
        }

        assertThat(importFixture("bazel920").configurationMatch())
                .isEqualTo(ConfigurationMatch.EXACT);
    }

    @Test
    @DisplayName("a failed import leaves the session usable and says what happened")
    void failureIsContained() throws Exception {
        Path garbage = tempDir.resolve("garbage.proto");
        Files.write(garbage, new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff});

        ConfiguredTargetImporter.Result result =
                new ConfiguredTargetImporter(connection).importFrom(garbage, List.of("cquery"));

        assertThat(result.succeeded()).isFalse();
        assertThat(scalar("SELECT count(*) FROM configured_target_nodes")).isZero();
        assertThat(text("SELECT state FROM graph_sources WHERE kind = 'CONFIGURED_TARGETS'"))
                .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("the action graph and the configured-target graph are separate sources")
    void twoGraphsTwoSources() throws Exception {
        importFixture("bazel920");
        new ActionGraphImporter(connection)
                .importFrom(fixture("bazel920-aquery.proto"), List.of("aquery"));

        // Plan 24: a failed auxiliary query leaves the rest usable, which needs
        // the two to be independent rows rather than one status.
        assertThat(scalar("SELECT count(*) FROM graph_sources")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM graph_sources WHERE state = 'SUCCEEDED'"))
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private ConfiguredTargetImporter.Result importFixture(String name) throws Exception {
        return new ConfiguredTargetImporter(connection)
                .importFrom(fixture(name + "-cquery.proto"), List.of("cquery", "//pkg:all"));
    }

    private Path fixture(String name) throws IOException {
        Path target = tempDir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/graph/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private List<String> ruleClasses() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery(
                        "SELECT DISTINCT rule_class FROM configured_target_nodes")) {
            while (rows.next()) {
                out.add(rows.getString(1));
            }
        }
        return out;
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = connection.createStatement();
                ResultSet rows = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                out.add(rows.getString("name"));
            }
        }
        return out;
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1L;
        }
    }

    private String text(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
