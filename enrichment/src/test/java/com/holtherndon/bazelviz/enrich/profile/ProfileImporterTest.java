package com.holtherndon.bazelviz.enrich.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Importing a real profile into a session that already has BEP data. */
final class ProfileImporterTest {

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;

    private static final String OUT = "bazel-out/darwin_arm64-fastbuild/bin/pkg/";

    @BeforeEach
    void buildSession() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("session.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'CLOSED')");
        exec("INSERT INTO build_invocation (singleton, stream_id, invocation_id)"
                + " VALUES (1, 1, '6ffb3a6a-30a2-4197-8280-fc52be58cc75')");
        for (String name : List.of("a", "b", "slow", "big")) {
            exec("INSERT INTO actions (primary_output, outcome) VALUES ('"
                    + OUT + name + ".txt', 'SUCCEEDED')");
        }
    }

    @AfterEach
    void closeSession() throws Exception {
        database.close();
    }

    @Test
    @DisplayName("phases, spans, counters and the critical path all land")
    void everythingSelectedIsWritten() throws Exception {
        ProfileImporter.Result result = importFixture("bazel841-start-ts.json");

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(scalar("SELECT count(*) FROM build_phases")).isEqualTo(5);
        assertThat(scalar("SELECT count(*) FROM profile_spans")).isPositive();
        assertThat(scalar("SELECT count(*) FROM profile_counters")).isPositive();
        assertThat(scalar("SELECT count(*) FROM bazel_critical_path")).isPositive();
        assertThat(scalar("SELECT count(*) FROM profile_threads")).isPositive();
    }

    @Test
    @DisplayName("the anchor is stored with its meaning and its uncertainty")
    void anchorKeepsItsMeaning() throws Exception {
        importFixture("bazel841-start-ts.json");

        assertThat(text("SELECT anchor_meaning FROM profile_metadata")).isEqualTo("EXACT_START");
        assertThat(text("SELECT anchor_source_key FROM profile_metadata"))
                .isEqualTo("profile_start_ts");
        assertThat(scalar("SELECT uncertainty_micros FROM profile_metadata")).isZero();
    }

    @Test
    @DisplayName("a 6.5.0 profile records a one-second uncertainty rather than pretending")
    void flooredAnchorCarriesItsError() throws Exception {
        exec("UPDATE build_invocation SET invocation_id ="
                + " '8b0095f2-52cb-4d81-9999-28295b8a09a6'");
        importFixture("bazel650-finish-ts.json");

        assertThat(text("SELECT anchor_meaning FROM profile_metadata"))
                .isEqualTo("START_FLOORED_TO_SECOND");
        assertThat(text("SELECT anchor_source_key FROM profile_metadata"))
                .isEqualTo("profile_finish_ts");
        // Read by its name this value would put every span a build-length late.
        assertThat(scalar("SELECT uncertainty_micros FROM profile_metadata"))
                .isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("a span whose primary output names an action is joined to it")
    void spansJoinToActions() throws Exception {
        importFixture("bazel841-start-ts.json");

        assertThat(scalar("SELECT count(*) FROM profile_spans WHERE action_id IS NOT NULL"))
                .isPositive();
        // And one whose output no action claims stays unjoined rather than
        // attaching to whatever happened to be nearby.
        assertThat(scalar("SELECT count(*) FROM profile_spans"
                + " WHERE primary_output IS NOT NULL AND action_id IS NULL")).isPositive();
    }

    @Test
    @DisplayName("phase ends are derived and marked as derived; the last has none")
    void phaseEndsAreDerived() throws Exception {
        importFixture("bazel841-start-ts.json");

        assertThat(scalar("SELECT count(*) FROM build_phases WHERE end_is_derived = 1"))
                .isEqualTo(5);
        // The profile states starts and never ends, so the last phase's end is
        // not known -- and the trace maximum would be a boundary nothing
        // measured.
        assertThat(scalar("SELECT count(*) FROM build_phases WHERE end_micros IS NULL"))
                .isEqualTo(1);
        assertThat(scalar("SELECT start_micros FROM build_phases WHERE ordinal = 0"))
                .isNegative();
    }

    @Test
    @DisplayName("the build id is checked and the answer recorded")
    void buildIdIsChecked() throws Exception {
        ProfileImporter.Result result = importFixture("bazel841-start-ts.json");

        assertThat(result.buildIdMatches()).hasValue(true);
        assertThat(scalar("SELECT build_id_matches FROM profile_metadata")).isEqualTo(1);
    }

    @Test
    @DisplayName("a profile from another build imports, flagged, rather than being refused")
    void anotherBuildsProfileIsFlagged() throws Exception {
        exec("UPDATE build_invocation SET invocation_id = 'a-different-build'");

        ProfileImporter.Result result = importFixture("bazel841-start-ts.json");

        // Unlike an execution log, whose timings would attach to the wrong
        // actions, a profile's phases and counters are about the machine. The
        // flag is what stops it being an accident.
        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(result.buildIdMatches()).hasValue(false);
        assertThat(scalar("SELECT build_id_matches FROM profile_metadata")).isZero();
    }

    @Test
    @DisplayName("an unreadable profile fails without touching the BEP rows")
    void failureDoesNotInvalidateTheBep() throws Exception {
        long actionsBefore = scalar("SELECT count(*) FROM actions");
        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "{\"traceEvents\": [ {\"ph\":");

        ProfileImporter.Result result = new ProfileImporter(connection).importFrom(broken);

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
        assertThat(scalar("SELECT count(*) FROM actions")).isEqualTo(actionsBefore);
        assertThat(scalar("SELECT count(*) FROM profile_spans")).isZero();
        assertThat(scalar("SELECT count(*) FROM build_phases")).isZero();

        EnrichmentTask task = new EnrichmentTaskStore(connection).all().getFirst();
        assertThat(task.state()).isEqualTo(EnrichmentTask.State.FAILED);
        assertThat(task.retriable()).isTrue();
        assertThat(task.unavailableMetrics()).anyMatch(line -> line.contains("critical path"));
    }

    @Test
    @DisplayName("a successful profile retry replaces every old critical-path component")
    void successfulRetryReplacesTheProfile() throws Exception {
        new ProfileImporter(connection).importFrom(criticalPathProfile("first", 3, 100));

        ProfileImporter.Result replacement = new ProfileImporter(connection)
                .importFrom(criticalPathProfile("replacement", 1, 900));

        assertThat(replacement.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(scalar("SELECT count(*) FROM bazel_critical_path")).isEqualTo(1);
        assertThat(text("SELECT description FROM bazel_critical_path WHERE ordinal = 0"))
                .isEqualTo("replacement-0");
        assertThat(scalar("SELECT duration_micros FROM bazel_critical_path WHERE ordinal = 0"))
                .isEqualTo(900);
    }

    @Test
    @DisplayName("a failed profile retry rolls replacement back but marks retained rows unusable")
    void failedRetryRetainsLastCompleteRowsBehindFailedState() throws Exception {
        new ProfileImporter(connection).importFrom(criticalPathProfile("complete", 3, 100));
        Path broken = tempDir.resolve("retry-broken.json");
        Files.writeString(broken, "{\"traceEvents\":[{\"cat\":");

        ProfileImporter.Result retry = new ProfileImporter(connection).importFrom(broken);

        assertThat(retry.state()).isEqualTo(EnrichmentTask.State.FAILED);
        assertThat(scalar("SELECT count(*) FROM bazel_critical_path")).isEqualTo(3);
        assertThat(text("SELECT description FROM bazel_critical_path WHERE ordinal = 0"))
                .isEqualTo("complete-0");
        assertThat(new EnrichmentTaskStore(connection).all().getFirst().state())
                .isEqualTo(EnrichmentTask.State.FAILED);
    }

    @Test
    @DisplayName("a failed profile import leaves an execution-log import alone")
    void tasksAreIndependent() throws Exception {
        // Plan 21.4: each enrichment task is independent.
        new EnrichmentTaskStore(connection).begin(
                EnrichmentTask.Kind.EXECUTION_LOG, java.util.Optional.of("/tmp/exec.log"), 1);
        new EnrichmentTaskStore(connection).finish(
                1, EnrichmentTask.State.SUCCEEDED, java.util.Optional.of("4 attempts"),
                java.util.Optional.empty(), false, List.of(),
                java.util.OptionalLong.of(4), java.util.OptionalLong.empty(), 2);

        Path broken = tempDir.resolve("broken.json");
        Files.writeString(broken, "not json at all");
        new ProfileImporter(connection).importFrom(broken);

        List<EnrichmentTask> tasks = new EnrichmentTaskStore(connection).all();
        assertThat(tasks).hasSize(2);
        assertThat(tasks).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(EnrichmentTask.Kind.EXECUTION_LOG);
            assertThat(task.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        });
        assertThat(tasks).anySatisfy(task -> {
            assertThat(task.kind()).isEqualTo(EnrichmentTask.Kind.PROFILE);
            assertThat(task.state()).isEqualTo(EnrichmentTask.State.FAILED);
        });
    }

    @Test
    @DisplayName("every profile table survives a foreign-key check")
    void referentialIntegrity() throws Exception {
        importFixture("bazel841-start-ts.json");

        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            assertThat(rows.next()).as("a foreign key violation exists").isFalse();
        }
    }

    @Test
    @DisplayName("a profile larger than one batch imports every row")
    void batchesAreFlushedRatherThanAccumulated() throws Exception {
        // ProfileWriter used to add every span to a single JDBC batch and
        // execute it once at the end, which is fine for the fifteen spans a
        // six-target build produces and unbounded for a real profile. This
        // exercises more rows than the batch threshold so a regression to the
        // old shape shows up as missing rows rather than as memory nobody
        // measures.
        int spans = 12_000;
        Path big = tempDir.resolve("big.json");
        try (java.io.BufferedWriter out = Files.newBufferedWriter(big)) {
            out.write("{\"otherData\":{\"profile_start_ts\":1787434091653},\"traceEvents\":[");
            for (int i = 0; i < spans; i++) {
                if (i > 0) {
                    out.write(',');
                }
                out.write("{\"ph\":\"X\",\"cat\":\"action processing\",\"name\":\"a" + i
                        + "\",\"ts\":" + (i * 10) + ",\"dur\":5,\"tid\":1,\"out\":\"out/"
                        + i + "\"}");
            }
            out.write("]}");
        }

        ProfileImporter.Result result = new ProfileImporter(connection).importFrom(big);

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(result.spansWritten()).isEqualTo(spans);
        assertThat(scalar("SELECT count(*) FROM profile_spans")).isEqualTo(spans);
    }

    @Test
    @DisplayName("a critical path larger than one JDBC batch imports every component")
    void criticalPathBatchesAreFlushed() throws Exception {
        int components = 6_000;

        ProfileImporter.Result result = new ProfileImporter(connection)
                .importFrom(criticalPathProfile("component", components, 1));

        assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
        assertThat(scalar("SELECT count(*) FROM bazel_critical_path"))
                .isEqualTo(components);
    }

    // ---------------------------------------------------------------- helpers

    private ProfileImporter.Result importFixture(String name) throws Exception {
        return new ProfileImporter(connection).importFrom(fixture(name));
    }

    private Path fixture(String name) throws IOException {
        Path target = tempDir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/profile/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Path criticalPathProfile(String prefix, int components, long duration)
            throws IOException {
        Path profile = tempDir.resolve(prefix + "-critical-path.json");
        try (java.io.BufferedWriter out = Files.newBufferedWriter(profile)) {
            out.write("{\"otherData\":{\"profile_start_ts\":1},\"traceEvents\":[");
            for (int i = 0; i < components; i++) {
                if (i > 0) {
                    out.write(',');
                }
                out.write("{\"cat\":\"critical path component\",\"name\":\""
                        + prefix + "-" + i + "\",\"ph\":\"X\",\"ts\":" + (i * 10L)
                        + ",\"dur\":" + duration + ",\"tid\":7}");
            }
            out.write("]}");
        }
        return profile;
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
