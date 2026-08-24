package com.holtherndon.bazelviz.runner.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ladder against a real process, with the real signals.
 *
 * <h2>Deliberately not Bazel</h2>
 *
 * <p>Everything worth proving here is about the operating system, not about
 * Bazel: that {@code /bin/kill -INT} really sends {@code SIGINT} and not the
 * {@code SIGTERM} that {@link Process#destroy()} would have sent, and that a
 * process which ignores both is still dead at the end of the ladder. A shell
 * with a {@code trap} proves both in about a second, without a Bazel server, a
 * workspace, or a gigabyte of heap.
 *
 * <p>That matters beyond speed. A Bazel server sizes its JVM from the machine's
 * RAM, and this project has crashed a laptop by having several alive at once, so
 * a real-Bazel test is a cost paid deliberately and only where nothing cheaper
 * will do. Signal delivery is not one of those places.
 */
class RealSubprocessLadderTest {

    /** Three of these is the whole ladder, so the test costs about a second. */
    private static final Duration GRACE = Duration.ofMillis(400);

    /** What the scripted client prints once its signal handlers are in place. */
    private static final String READY = "bbv-ready";

    /**
     * A client that takes {@code SIGINT} the way the Bazel client does.
     *
     * <p>{@code exec} matters, and cost this test a rewrite to find out why.
     * A {@code /bin/sh} left in the picture does <em>not</em> die of a
     * {@code SIGINT} sent to it alone while it waits on a child — measured here
     * on macOS, where the shell noted the signal, went on looping, and died of
     * the {@code SIGTERM} on the next rung instead. {@code exec} replaces the
     * shell with {@code sleep}, which has the default disposition and dies as a
     * Bazel client would.
     */
    private static final String DIES_ON_INTERRUPT = "exec sleep 30";

    /** A client that ignores both signals a user's first two clicks send. */
    private static final String IGNORES_EVERYTHING_BUT_KILL =
            "trap '' INT TERM\nwhile :; do sleep 0.2; done";

    @Test
    @DisplayName("the gentle rung really is SIGINT: a process that dies on it never sees the rest")
    void interruptEndsAWillingProcess() throws Exception {
        assumeTrue(canSignal(), "needs /bin/sh and /bin/kill");
        BazelLauncher.BazelProcess process = launch(DIES_ON_INTERRUPT);

        ProcessOutcome outcome = process.cancel(CancellationMode.CANCEL, true);

        assertThat(process.isAlive()).isFalse();
        // The rung it died on is the assertion. Were /bin/kill not reached, or
        // reached with SIGTERM instead, the ladder would have had to take the
        // next rung to end it, and this would say TERMINATE.
        assertThat(process.stoppedBy()).contains(CancellationMode.CANCEL);
        assertThat(outcome.terminatedBy()).contains(CancellationMode.CANCEL);
        assertThat(outcome.exitCode()).isPresent();
    }

    @Test
    @DisplayName("a client that ignores INT and TERM is still dead when the ladder ends")
    void theLadderOutlastsAProcessThatIgnoresIt() throws Exception {
        assumeTrue(canSignal(), "needs /bin/sh and /bin/kill");
        // The shape of the bug this task exists for: a client that does not go
        // when it is asked. Its Bazel equivalent holds the workspace's command
        // lock for as long as it lives, so every later command in that
        // workspace — 'clean' first among them — waits on a process nobody can
        // see.
        BazelLauncher.BazelProcess process = launch(IGNORES_EVERYTHING_BUT_KILL);

        ProcessOutcome outcome = process.cancel(CancellationMode.CANCEL, true);

        assertThat(process.isAlive())
                .describedAs("the ladder must not return with the client still running")
                .isFalse();
        assertThat(process.stoppedBy()).contains(CancellationMode.FORCE_KILL);
        assertThat(outcome.terminatedBy()).contains(CancellationMode.FORCE_KILL);
    }

    @Test
    @DisplayName("one rung is not enough for a client that ignores it, which is why the ladder is asked for")
    void oneRungIsNotEnoughForAClientThatIgnoresIt() throws Exception {
        assumeTrue(canSignal(), "needs /bin/sh and /bin/kill");
        BazelLauncher.BazelProcess process = launch(IGNORES_EVERYTHING_BUT_KILL);
        try {
            ProcessOutcome outcome = process.cancel(CancellationMode.CANCEL, false);

            // Measured rather than assumed, because it is the reason
            // CaptureCoordinator asks to escalate. A single rung is a request,
            // not a guarantee, and the caller with nobody to click a second
            // button needs the guarantee: a Bazel client that survives its
            // cancellation holds the workspace lock against every later
            // command in that workspace.
            assertThat(process.isAlive()).isTrue();
            assertThat(outcome.exitCode()).isEmpty();
        } finally {
            process.cancel(CancellationMode.FORCE_KILL, false);
        }
    }

    // ------------------------------------------------------------------ setup

    private static boolean canSignal() {
        return Files.isExecutable(Path.of("/bin/sh")) && Files.isExecutable(Path.of("/bin/kill"));
    }

    /**
     * Starts a scripted client and returns only once it has said it is ready.
     *
     * <h2>The handshake is not ceremony</h2>
     *
     * <p>Written first without one, this test failed — and failed by
     * reproducing, exactly, the race that
     * {@code BazelLauncher.SIGNAL_READY_MILLIS} exists to avoid. The shell had
     * been exec'd but had not yet reached its {@code trap} when the interrupt
     * arrived, so a process written to ignore {@code SIGINT} died of
     * {@code SIGINT}. Production waits a flat second for that window to pass; a
     * test that waited a flat second three times over would be slower and still
     * be guessing, so the script says when it is ready and this waits until it
     * has.
     *
     * @param body what the client does once it is ready; the first line may
     *     install signal handlers, since the announcement follows it
     */
    private static BazelLauncher.BazelProcess launch(String body) throws Exception {
        int firstBreak = body.indexOf('\n');
        String handlers = firstBreak < 0 ? "" : body.substring(0, firstBreak + 1);
        String loop = firstBreak < 0 ? body : body.substring(firstBreak + 1);
        String script = handlers + "echo " + READY + "\n" + loop;
        List<String> argv = List.of("/bin/sh", "-c", script);
        Process client = new ProcessBuilder(argv).start();

        CountDownLatch ready = new CountDownLatch(1);
        StringBuilder seen = new StringBuilder();
        BazelCommand command = new CommandLineParser()
                .parse(Path.of("/usr/bin/bazel"), Path.of("/"), List.of("build", "//..."));
        LaunchRequest request = new LaunchRequest(
                command,
                (stream, data, offset, length) -> {
                    seen.append(new String(data, offset, length, StandardCharsets.UTF_8));
                    if (seen.indexOf(READY) >= 0) {
                        ready.countDown();
                    }
                },
                Optional.of(GRACE),
                Duration.ofMillis(200),
                Optional.empty());
        BazelLauncher.BazelProcess process = new BazelLauncher.BazelProcess(
                client, request, argv, OsStopSignals.INSTANCE, 0L);
        if (!ready.await(10, TimeUnit.SECONDS)) {
            process.cancel(CancellationMode.FORCE_KILL, false);
            throw new IllegalStateException("the scripted client never reported itself ready");
        }
        return process;
    }
}
