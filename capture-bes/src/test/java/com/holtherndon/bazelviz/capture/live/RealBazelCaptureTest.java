package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilityDetector;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.EventQueries;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 2 exit criteria, end to end, against a real Bazel.
 *
 * <p>Everything else in this module tests a component. This tests the claim the
 * phase is actually making: that pressing Launch on a real workspace produces a
 * session containing what the build did, and that pressing Cancel produces a
 * session containing what it had done so far.
 */
@Tag("real-bazel")
class RealBazelCaptureTest {

    @Test
    @DisplayName("a launched build produces a complete, queryable session")
    void launchedBuildProducesASession(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 6);
        Path sessionsRoot = directory.resolve("sessions");

        CaptureRequest request = CaptureRequest.of(
                        sessionsRoot,
                        "test",
                        bazel.orElseThrow().toString(),
                        workspace.root(),
                        List.of("build", "//..."))
                .withPreset(CapturePreset.LIVE_ESSENTIALS);

        CaptureResult result;
        try (CaptureCoordinator coordinator = coordinatorFor(request, sessionsRoot)) {
            Preflight preflight = coordinator.preflight();

            // ADR-007: the user sees the real command, with the real port, and
            // the plan explains every difference from what they typed.
            assertThat(preflight.canLaunch()).isTrue();
            assertThat(preflight.endpoint().besBackendUri()).startsWith("grpc://127.0.0.1:");
            assertThat(preflight.plan().effective().toArgv())
                    .contains("--bes_backend=" + preflight.endpoint().besBackendUri());
            assertThat(preflight.plan().original().toArgv()).doesNotContain(
                    "--bes_backend=" + preflight.endpoint().besBackendUri());

            result = coordinator.run();
        }

        assertThat(result.buildSucceeded())
                .describedAs("warnings: %s", result.warnings())
                .isTrue();
        assertThat(result.captureComplete())
                .describedAs("warnings: %s", result.warnings())
                .isTrue();
        assertThat(result.state()).isEqualTo(SessionState.READY);

        CaptureSummary summary = result.capture().orElseThrow();
        assertThat(summary.received()).isPositive();
        // Exit criterion: no accepted event is silently dropped. Everything
        // received reached the journal, and every frame is accounted for either
        // as a row or as an envelope that legitimately has none.
        assertThat(summary.received()).isEqualTo(summary.journaled());
        assertThat(summary.journaled()).isEqualTo(summary.normalized() + summary.nonEventEnvelopes());
        assertThat(summary.streams()).allSatisfy(stream ->
                assertThat(stream.isCleanlyComplete()).isTrue());

        ManagedSessionLayout layout = ManagedSessionLayout.at(result.sessionRoot());
        assertThat(Files.exists(layout.journalSegment(0))).isTrue();
        assertThat(Files.exists(layout.instrumentationPlanFile())).isTrue();
        assertThat(Files.size(layout.stderrLog())).isPositive();

        // The rows are really there and really queryable.
        try (SessionDatabase database = SessionDatabase.open(layout.databaseFile());
                EventQueries queries = new EventQueries(database.newReadConnection())) {
            assertThat(queries.eventCount()).isEqualTo(summary.normalized());
            assertThat(queries.eventCount()).isPositive();
        }

        // The manifest records both commands, so the session can answer what
        // was run and what the user asked for.
        SessionManifest manifest = new SessionManager(sessionsRoot, "test")
                .readManifest(result.sessionRoot());
        assertThat(manifest.originalCommand()).isPresent();
        assertThat(manifest.effectiveCommand()).isPresent();
        assertThat(manifest.injectedFlags().orElseThrow())
                .anyMatch(flag -> flag.startsWith("--bes_backend="));
        assertThat(manifest.bazelVersion()).isPresent();
        assertThat(manifest.eventCount().orElse(-1)).isEqualTo(summary.normalized());
    }

    @Test
    @DisplayName("a cancelled build leaves an inspectable partial session")
    void cancelledBuildIsStillInspectable(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

        // Chained sleeps, so the build is reliably still running when cancelled
        // rather than racing to finish first.
        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.slow(directory.resolve("ws"), 6, 4);
        Path sessionsRoot = directory.resolve("sessions");

        CaptureRequest request = CaptureRequest.of(
                        sessionsRoot,
                        "test",
                        bazel.orElseThrow().toString(),
                        workspace.root(),
                        List.of("build", "//..."))
                .withPreset(CapturePreset.LIVE_ESSENTIALS);

        CaptureResult result;
        try (CaptureCoordinator coordinator = coordinatorFor(request, sessionsRoot)) {
            coordinator.preflight();

            Thread canceller = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (!coordinator.isRunning() && System.nanoTime() < deadline) {
                        Thread.sleep(20);
                    }
                    // Far enough in that the stream has real content, well
                    // short of the build finishing on its own.
                    Thread.sleep(6_000);
                    coordinator.cancel(CancellationMode.CANCEL);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "canceller");
            canceller.setDaemon(true);
            canceller.start();

            result = coordinator.run();
            canceller.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertThat(result.wasCancelled())
                .describedAs("state was %s; warnings: %s", result.state(), result.warnings())
                .isTrue();
        assertThat(result.state()).isEqualTo(SessionState.CANCELLED);

        // The point of the criterion: what was captured before the stop is
        // still there, still complete as far as it goes, and still queryable.
        CaptureSummary summary = result.capture().orElseThrow();
        assertThat(summary.received()).isPositive();
        assertThat(summary.received()).isEqualTo(summary.journaled());

        ManagedSessionLayout layout = ManagedSessionLayout.at(result.sessionRoot());
        assertThat(Files.exists(layout.journalSegment(0))).isTrue();
        try (SessionDatabase database = SessionDatabase.open(layout.databaseFile());
                EventQueries queries = new EventQueries(database.newReadConnection())) {
            assertThat(queries.eventCount()).isPositive();
            // A cancelled capture says so in its diagnostics rather than
            // looking like a build that simply ended.
            assertThat(queries.diagnosticsPage(java.util.OptionalLong.empty(), 100))
                    .anyMatch(entry -> entry.diagnostic().code()
                            .equals(CaptureDiagnosticCodes.CAPTURE_CANCELLED));
        }

        SessionManifest manifest = new SessionManager(sessionsRoot, "test")
                .readManifest(result.sessionRoot());
        assertThat(manifest.state()).isEqualTo(SessionState.CANCELLED);
        assertThat(manifest.effectiveCommand()).isPresent();
    }

    @Test
    @DisplayName("a failing build still produces a complete session about the failure")
    void failingBuildIsCapturedCompletely(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.withFailure(directory.resolve("ws"), 3);
        Path sessionsRoot = directory.resolve("sessions");

        CaptureResult result;
        try (CaptureCoordinator coordinator = coordinatorFor(
                CaptureRequest.of(
                                sessionsRoot, "test", bazel.orElseThrow().toString(),
                                workspace.root(), List.of("build", "//..."))
                        .withPreset(CapturePreset.LIVE_ESSENTIALS),
                sessionsRoot)) {
            coordinator.preflight();
            result = coordinator.run();
        }

        // The build failed and the capture did not. These are different facts
        // and the session states both.
        assertThat(result.buildSucceeded()).isFalse();
        assertThat(result.captureComplete())
                .describedAs("warnings: %s", result.warnings())
                .isTrue();
        assertThat(result.state()).isEqualTo(SessionState.READY);
        assertThat(result.capture().orElseThrow().received()).isPositive();
    }

    @Test
    @DisplayName("a command with its own BES backend will not launch until the user decides")
    void existingBesBackendRequiresADecision(@TempDir Path directory) throws Exception {
        Optional<Path> bazel = BazelBinary.find();
        assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

        BazelWorkspaceFixture workspace =
                BazelWorkspaceFixture.simple(directory.resolve("ws"), 2);
        Path sessionsRoot = directory.resolve("sessions");

        CaptureRequest request = CaptureRequest.of(
                sessionsRoot, "test", bazel.orElseThrow().toString(), workspace.root(),
                List.of("build", "--bes_backend=grpc://corp.example:443", "//..."));

        try (CaptureCoordinator coordinator = coordinatorFor(request, sessionsRoot)) {
            Preflight preflight = coordinator.preflight();

            assertThat(preflight.canLaunch()).isFalse();
            assertThat(preflight.plan().mandatoryConflicts())
                    .anySatisfy(conflict -> assertThat(conflict.kind()).isEqualTo(
                            com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind.EXISTING_BES_BACKEND));

            // Launching anyway is refused rather than quietly redirecting the
            // user's build results away from a backend their team relies on.
            assertThat(org.assertj.core.api.Assertions.catchThrowable(coordinator::run))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already sends its results somewhere");

            // Nothing was created for a build that never ran.
            assertThat(Files.exists(sessionsRoot) && Files.list(sessionsRoot).findAny().isPresent())
                    .isFalse();
        }
    }

    private static CaptureCoordinator coordinatorFor(CaptureRequest request, Path sessionsRoot) {
        return new CaptureCoordinator(
                request,
                new SessionManager(sessionsRoot, request.appVersion()),
                new BazelCapabilityDetector(),
                Clock.systemUTC());
    }
}
