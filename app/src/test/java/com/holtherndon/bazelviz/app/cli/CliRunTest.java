package com.holtherndon.bazelviz.app.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code bbv run}: the headless face of Phase 2. */
class CliRunTest {

    private final CliHarness cli = new CliHarness();

    @Test
    @DisplayName("a Bazel command is required, and the message says where it goes")
    void missingCommandIsAUsageError(@TempDir Path directory) {
        CliHarness.Result result = cli.run("run", "--sessions-root=" + directory);

        assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.err()).contains("after '--'").contains("bbv run -- build //...");
    }

    @Test
    @DisplayName("--replace-bes and --keep-bes cannot both be given")
    void contradictoryResolutionsAreRefused(@TempDir Path directory) {
        CliHarness.Result result = cli.run(
                "run", "--sessions-root=" + directory, "--replace-bes", "--keep-bes",
                "--", "build", "//...");

        assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.err()).contains("opposite things");
    }

    @Test
    @DisplayName("an unknown preset lists the ones that exist")
    void unknownPresetIsRefused(@TempDir Path directory) {
        CliHarness.Result result = cli.run(
                "run", "--sessions-root=" + directory, "--preset=everything", "--", "build", "//...");

        assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.err()).contains("unknown preset 'everything'")
                .contains("live-essentials")
                .contains("performance-diagnostics");
    }

    @Test
    @DisplayName("a Bazel that is not there is a usage error, not a stack trace")
    void missingBazelIsAUsageError(@TempDir Path directory) {
        CliHarness.Result result = cli.run(
                "run", "--sessions-root=" + directory, "--bazel=/nonexistent/bazel",
                "--cwd=" + directory, "--", "build", "//...");

        assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.err()).contains("/nonexistent/bazel");
        assertThat(result.err()).doesNotContain("\tat ");
    }

    @Test
    @DisplayName("the help names the exit codes and where the Bazel command goes")
    void helpExplainsTheContract() {
        CliHarness.Result result = cli.run("run", "--help");

        assertThat(result.code()).isZero();
        assertThat(result.out())
                .contains("bbv run [options] -- <bazel command>")
                .contains("describes the capture, not the build");
    }

    @Test
    @Tag("real-bazel")
    @DisplayName("--dry-run plans against a real Bazel and launches nothing")
    void dryRunPlansWithoutLaunching(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 2);
        Path sessionsRoot = directory.resolve("sessions");

        CliHarness.Result result = cli.run(
                "run", "--json", "--dry-run",
                "--sessions-root=" + sessionsRoot,
                "--bazel=" + bazel.orElseThrow(),
                "--cwd=" + workspace.root(),
                "--", "build", "//...");

        assertThat(result.code()).isZero();
        var json = result.json();
        assertThat(json.member("canLaunch")).isPresent();
        assertThat(json.member("besEndpoint").orElseThrow().toString()).contains("127.0.0.1");
        assertThat(json.member("capabilityDetection").orElseThrow().toString())
                .contains("FLAGS_PROTO");
        // Nothing was launched, so nothing was created.
        assertThat(java.nio.file.Files.exists(sessionsRoot)).isFalse();
    }

    @Test
    @Tag("real-bazel")
    @DisplayName("a real build is captured and the summary is machine-readable")
    void realBuildIsCaptured(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 3);
        Path sessionsRoot = directory.resolve("sessions");

        CliHarness.Result result = cli.run(
                "run", "--json", "--quiet",
                "--sessions-root=" + sessionsRoot,
                "--bazel=" + bazel.orElseThrow(),
                "--cwd=" + workspace.root(),
                "--preset=live-essentials",
                "--", "build", "//...");

        assertThat(result.code())
                .describedAs("stderr:%n%s", result.err())
                .isZero();

        // stdout is the document and nothing else. A log line or a progress
        // message landing here would make it unparseable, which is the whole
        // reason logging goes to stderr.
        var json = result.json();
        assertThat(json.member("state").orElseThrow().toString()).contains("READY");
        var capture = (com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject)
                json.member("capture").orElseThrow();
        assertThat(capture.member("complete").orElseThrow().toString()).contains("true");
    }

    @Test
    @Tag("real-bazel")
    @DisplayName("a command with its own BES backend stops and prints the choices")
    void besConflictStopsAndExplains(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 2);
        Path sessionsRoot = directory.resolve("sessions");

        CliHarness.Result result = cli.run(
                "run",
                "--sessions-root=" + sessionsRoot,
                "--bazel=" + bazel.orElseThrow(),
                "--cwd=" + workspace.root(),
                "--", "build", "--bes_backend=grpc://corp.example:443", "//...");

        assertThat(result.code()).isEqualTo(ExitCode.USAGE.code());
        assertThat(result.err())
                .contains("already sends its results somewhere")
                .contains("--replace-bes")
                .contains("--keep-bes");
        // Each choice states what it costs, so neither is picked blind.
        assertThat(result.err()).contains("will not receive this build");
        assertThat(java.nio.file.Files.exists(sessionsRoot)).isFalse();
    }
}
