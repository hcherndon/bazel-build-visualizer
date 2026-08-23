package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilityDetector;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.EventQueries;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A real instrumented build on each supported Bazel, end to end.
 *
 * <h2>Why this is tagged out of the default build</h2>
 *
 * <p>Bazel sizes its server JVM from the machine's RAM, and four servers alive
 * at once on a laptop has crashed one — repeatedly, at over 120 GB. Two things
 * keep that from happening: {@code BazelWorkspaceFixture.FIXTURE_RC} caps each
 * server at {@code -Xmx1g} with {@code max_idle_secs=15}, and this test is
 * parameterized rather than parallel, so exactly one version is alive at a
 * time.
 *
 * <p>It is still tagged {@code bazel-sweep} and excluded from
 * {@code ./gradlew build}, because a full four-version sweep downloads and
 * starts four Bazel servers and takes minutes. Run it deliberately:
 *
 * <pre>./gradlew :capture-bes:test -Pbbv.bazelSweep=true --tests '*BazelVersionMatrixTest*'</pre>
 *
 * <p>{@code RealBazelCaptureTest} keeps a single-version end-to-end capture in
 * the default suite, so the path is exercised on every build; this is the one
 * that says the same thing about all four.
 *
 * <h2>What a row of the matrix means</h2>
 *
 * <p>Each version gets one build of the same fixture workspace, captured
 * through the embedded BES server, and each assertion below is a column of
 * {@code docs/bazel-compatibility.md}'s release matrix. The printed line is
 * what goes into that table — measured, not remembered.
 */
@Tag("bazel-sweep")
class BazelVersionMatrixTest {

    @ParameterizedTest(name = "Bazel {0}")
    @ValueSource(strings = {"6.5.0", "7.6.1", "8.4.1", "9.2.0"})
    @DisplayName("an instrumented build is captured completely on every supported Bazel")
    void capturesOnEverySupportedVersion(String version, @TempDir Path directory)
            throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 6);
        // bazelisk reads .bazelversion, which is how a specific version is
        // exercised without this test knowing where any binary lives.
        Files.writeString(workspace.root().resolve(".bazelversion"), version + "\n");
        Path sessionsRoot = directory.resolve("sessions");

        CaptureRequest request = CaptureRequest.of(
                        sessionsRoot, "test", bazel.orElseThrow().toString(),
                        workspace.root(), hermetic("build", "//..."))
                .withPreset(CapturePreset.LIVE_ESSENTIALS);

        CaptureResult result;
        try (CaptureCoordinator coordinator = new CaptureCoordinator(
                request,
                new SessionManager(sessionsRoot, "test"),
                new BazelCapabilityDetector(),
                Clock.systemUTC())) {
            Preflight preflight = coordinator.preflight();
            // The version bazelisk actually resolved, from the executable the
            // preflight probed -- not from the .bazelversion file, which is a
            // request rather than an answer.
            assumeTrue(
                    preflight.executable().bazelVersion().map(version::equals).orElse(false),
                    "this machine resolved "
                            + preflight.executable().bazelVersion().orElse("no version")
                            + " rather than " + version);
            assertThat(preflight.canLaunch()).isTrue();
            result = coordinator.run();
        }

        CaptureSummary summary = result.capture().orElseThrow();
        SessionManifest manifest = new SessionManager(sessionsRoot, "test")
                .readManifest(result.sessionRoot());
        long rows;
        try (SessionDatabase database = SessionDatabase.open(
                        ManagedSessionLayout.at(result.sessionRoot()).databaseFile());
                EventQueries queries = new EventQueries(database.newReadConnection())) {
            rows = queries.eventCount();
        }

        // The printed row is the matrix. Everything asserted below is a column
        // in docs/bazel-compatibility.md.
        System.out.printf(
                "MATRIX | %-6s | build %-7s | capture %-8s | %,7d received | %,7d journaled"
                        + " | %,7d rows | version %s%n",
                version,
                result.buildSucceeded() ? "ok" : "FAILED",
                result.captureComplete() ? "complete" : "PARTIAL",
                summary.received(), summary.journaled(), rows,
                manifest.bazelVersion().orElse("absent"));

        assertThat(result.buildSucceeded())
                .describedAs("warnings: %s", result.warnings())
                .isTrue();
        assertThat(result.captureComplete())
                .describedAs("warnings: %s", result.warnings())
                .isTrue();
        assertThat(result.state()).isEqualTo(SessionState.READY);

        // No accepted event is silently dropped, on any version.
        assertThat(summary.received()).isPositive();
        assertThat(summary.received()).isEqualTo(summary.journaled());
        assertThat(summary.journaled())
                .isEqualTo(summary.normalized() + summary.nonEventEnvelopes());
        assertThat(rows).isEqualTo(summary.normalized());

        // The manifest can answer what ran, on any version.
        assertThat(manifest.bazelVersion()).contains(version);
        assertThat(manifest.injectedFlags().orElseThrow())
                .anyMatch(flag -> flag.startsWith("--bes_backend="));
        assertThat(Files.exists(
                ManagedSessionLayout.at(result.sessionRoot()).journalSegment(0))).isTrue();
    }

    private static List<String> hermetic(String... command) {
        List<String> argv =
                new java.util.ArrayList<>(BazelWorkspaceFixture.hermeticStartupOptions());
        argv.addAll(List.of(command));
        return List.copyOf(argv);
    }
}
