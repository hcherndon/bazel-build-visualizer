package com.holtherndon.bazelviz.runner.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which signal goes out, in which order, when the ladder is driven from more
 * than one thread.
 *
 * <h2>Why this is a fake process</h2>
 *
 * <p>Every rung of the ladder is an operating-system signal, and the gentlest is
 * sent by running {@code /bin/kill}. Asserting on the sequence therefore used to
 * require a real Bazel and a stopwatch — a test nobody runs — which is how a
 * ladder could be started three times over, by three threads, each timing its
 * own grace period, without a single test noticing.
 *
 * <p>{@link StopSignals} makes the delivery observable, so a scripted client and
 * a recorder can prove the properties that matter: one signal per rung, never
 * the same rung twice, never a gentler rung after a harsher one, and never a
 * second ladder. {@link RealSubprocessLadderTest} then proves the same ladder
 * against a process that really does ignore the first two signals.
 */
class CancellationLadderTest {

    /** Short enough to run a whole ladder inside a test, long enough not to flake. */
    private static final Duration GRACE = Duration.ofMillis(120);

    @Test
    @DisplayName("the documented ladder is the ladder: INT, then TERM, then KILL, once each")
    void escalatesThroughEveryRungOnce() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals();
        BazelLauncher.BazelProcess process = processFor(client, signals);

        ProcessOutcome outcome = process.cancel(CancellationMode.CANCEL, true);

        assertThat(signals.delivered()).containsExactly(
                CancellationMode.CANCEL, CancellationMode.TERMINATE, CancellationMode.FORCE_KILL);
        assertThat(outcome.terminatedBy()).contains(CancellationMode.FORCE_KILL);
        // The client never went, so there is no exit code and none is invented.
        assertThat(outcome.exitCode()).isEmpty();
        assertThat(process.stoppedBy()).contains(CancellationMode.FORCE_KILL);
    }

    @Test
    @DisplayName("a client that goes on the first signal is never escalated to")
    void stopsAtTheRungThatWorked() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals().exitingOn(CancellationMode.CANCEL, 8);
        BazelLauncher.BazelProcess process = processFor(client, signals);

        ProcessOutcome outcome = process.cancel(CancellationMode.CANCEL, true);

        assertThat(signals.delivered()).containsExactly(CancellationMode.CANCEL);
        assertThat(outcome.exitCode()).hasValue(8);
        assertThat(outcome.terminatedBy()).contains(CancellationMode.CANCEL);
    }

    @Test
    @DisplayName("without escalation, one rung goes out and the ladder stops there")
    void oneRungWhenEscalationIsNotAskedFor() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals();
        BazelLauncher.BazelProcess process = processFor(client, signals);

        ProcessOutcome outcome = process.cancel(CancellationMode.TERMINATE, false);

        assertThat(signals.delivered()).containsExactly(CancellationMode.TERMINATE);
        assertThat(outcome.terminatedBy()).contains(CancellationMode.TERMINATE);
        assertThat(client.isAlive()).isTrue();
    }

    @Test
    @DisplayName("requestStop delivers the signal and does not sit out the grace period")
    void requestStopDoesNotWait() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals();
        BazelLauncher.BazelProcess process = processFor(client, signals);

        long startedAt = System.nanoTime();
        process.requestStop(CancellationMode.FORCE_KILL);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(signals.delivered()).containsExactly(CancellationMode.FORCE_KILL);
        // The capture thread is already in await(); a second thread waiting out
        // a grace period it will never read is what made Terminate look dead.
        assertThat(elapsed).isLessThan(GRACE);
    }

    @Test
    @DisplayName("a second, harsher stop mid-ladder does not start a second ladder")
    void twoRapidStopsDoNotInterleave() throws Exception {
        ScriptedClient client = new ScriptedClient();
        // The recorder holds the lock inside the first delivery, which is the
        // window a second click really lands in: the user clicked Cancel and,
        // seeing nothing happen, clicked Force Kill.
        RecordingSignals signals = new RecordingSignals().blockingOn(CancellationMode.CANCEL);
        BazelLauncher.BazelProcess process = processFor(client, signals);

        AtomicReference<ProcessOutcome> ladderOutcome = new AtomicReference<>();
        Thread ladder = run("ladder", () ->
                ladderOutcome.set(process.cancel(CancellationMode.CANCEL, true)));
        assertThat(signals.awaitBlocked()).isTrue();

        Thread secondClick = run("second-click", () ->
                process.requestStop(CancellationMode.FORCE_KILL));

        // While the first delivery is in flight the second cannot overtake it.
        // Two threads writing signals in whatever order they woke up in is the
        // defect; serialized delivery is the fix.
        Thread.sleep(150);
        assertThat(signals.delivered()).containsExactly(CancellationMode.CANCEL);

        signals.release();
        secondClick.join(TimeUnit.SECONDS.toMillis(5));
        ladder.join(TimeUnit.SECONDS.toMillis(10));

        // FORCE_KILL has already gone out, so the ladder does not then send the
        // TERMINATE it was going to send next: a gentler signal after a harsher
        // one is noise at best, and at worst a rung sent to a reused pid.
        assertThat(signals.delivered()).containsExactly(
                CancellationMode.CANCEL, CancellationMode.FORCE_KILL);
        assertThat(ladderOutcome.get().terminatedBy()).contains(CancellationMode.FORCE_KILL);
        assertThat(process.stoppedBy()).contains(CancellationMode.FORCE_KILL);
    }

    @Test
    @DisplayName("the same rung is never sent twice, however many times it is asked for")
    void aRungIsSentOnlyOnce() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals();
        BazelLauncher.BazelProcess process = processFor(client, signals);

        process.requestStop(CancellationMode.CANCEL);
        process.requestStop(CancellationMode.CANCEL);
        // A softer stop after a harder one is not a stop at all.
        process.requestStop(CancellationMode.TERMINATE);
        process.requestStop(CancellationMode.CANCEL);

        assertThat(signals.delivered())
                .containsExactly(CancellationMode.CANCEL, CancellationMode.TERMINATE);
    }

    @Test
    @DisplayName("an interrupted ladder force-kills rather than walking away from the client")
    void interruptTakesTheLastRung() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals();
        // Long enough that the interrupt certainly lands inside the grace period.
        BazelLauncher.BazelProcess process =
                processFor(client, signals, Duration.ofSeconds(30));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread ladder = new Thread(() -> {
            thrown.set(catchThrowable(() -> process.cancel(CancellationMode.CANCEL, true)));
        }, "interrupted-ladder");
        ladder.setDaemon(true);
        ladder.start();

        signals.awaitDelivery(CancellationMode.CANCEL);
        Thread.sleep(100);
        ladder.interrupt();
        ladder.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        // The client was ignoring SIGINT and the thread that was going to
        // escalate has just been told to stop. Leaving it alive is what holds
        // the workspace lock for ever.
        assertThat(signals.delivered()).containsExactly(
                CancellationMode.CANCEL, CancellationMode.FORCE_KILL);
    }

    @Test
    @DisplayName("a stop that arrives after the client has gone is not recorded as a cancellation")
    void stoppingAFinishedClientIsNotACancellation() throws Exception {
        ScriptedClient client = new ScriptedClient();
        RecordingSignals signals = new RecordingSignals();
        BazelLauncher.BazelProcess process = processFor(client, signals);
        client.exitWith(0);

        process.cancel(CancellationMode.CANCEL, true);

        // Nothing was signalled, because there was nothing to signal — and the
        // build that finished on its own still says so. A session marked
        // CANCELLED for a build that completed is a lie about what happened.
        assertThat(signals.delivered()).isEmpty();
        assertThat(process.stoppedBy()).isEmpty();
        assertThat(process.await().terminatedBy()).isEmpty();
        assertThat(process.await().exitCode()).hasValue(0);
    }

    @Test
    @DisplayName("the grace periods and the escalation order are the documented ones")
    void theLadderIsWhatTheEnumSays() {
        assertThat(CancellationMode.CANCEL.defaultGracePeriod()).isEqualTo(Duration.ofSeconds(30));
        assertThat(CancellationMode.TERMINATE.defaultGracePeriod()).isEqualTo(Duration.ofSeconds(10));
        assertThat(CancellationMode.FORCE_KILL.defaultGracePeriod()).isEqualTo(Duration.ofSeconds(5));

        assertThat(CancellationMode.CANCEL.escalation()).contains(CancellationMode.TERMINATE);
        assertThat(CancellationMode.TERMINATE.escalation()).contains(CancellationMode.FORCE_KILL);
        assertThat(CancellationMode.FORCE_KILL.escalation()).isEmpty();
    }

    // ------------------------------------------------------------------ setup

    private static BazelLauncher.BazelProcess processFor(
            ScriptedClient client, RecordingSignals signals) {
        return processFor(client, signals, GRACE);
    }

    private static BazelLauncher.BazelProcess processFor(
            ScriptedClient client, RecordingSignals signals, Duration grace) {
        // Zero readiness delay: the one-second hold before the first signal is
        // about a real client installing a real handler, and waiting it out in
        // every test here would buy nothing but seconds.
        return new BazelLauncher.BazelProcess(
                client, launchRequest(grace), List.of("bazel", "build", "//..."), signals, 0L);
    }

    private static LaunchRequest launchRequest(Duration grace) {
        BazelCommand command = new CommandLineParser()
                .parse(Path.of("/usr/bin/bazel"), Path.of("/"), List.of("build", "//..."));
        return new LaunchRequest(
                command,
                ConsoleSink.discarding(),
                Optional.of(grace),
                Duration.ofMillis(50),
                Optional.empty());
    }

    private static Thread run(String name, ThrowingRunnable body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private interface ThrowingRunnable {
        void run() throws InterruptedException;
    }

    /** Records every rung, in order, and can stall or end the client. */
    private static final class RecordingSignals implements StopSignals {

        private final List<CancellationMode> delivered =
                java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private CancellationMode blockOn;
        private CancellationMode exitOn;
        private int exitCode;

        RecordingSignals blockingOn(CancellationMode mode) {
            this.blockOn = mode;
            return this;
        }

        RecordingSignals exitingOn(CancellationMode mode, int code) {
            this.exitOn = mode;
            this.exitCode = code;
            return this;
        }

        @Override
        public void deliver(CancellationMode mode, Process process) {
            delivered.add(mode);
            if (mode == exitOn) {
                ((ScriptedClient) process).exitWith(exitCode);
            }
            if (mode == blockOn) {
                blocked.countDown();
                try {
                    released.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        List<CancellationMode> delivered() {
            synchronized (delivered) {
                return List.copyOf(delivered);
            }
        }

        boolean awaitBlocked() throws InterruptedException {
            return blocked.await(5, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        void awaitDelivery(CancellationMode mode) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!delivered().contains(mode) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
        }
    }

    /**
     * A process that exists only to be signalled.
     *
     * <p>It never exits on its own, which is the interesting case: a client that
     * ignores what it is sent is the one the ladder has to outlast.
     */
    private static final class ScriptedClient extends Process {

        private final CountDownLatch exited = new CountDownLatch(1);
        private volatile int exitCode;

        void exitWith(int code) {
            this.exitCode = code;
            exited.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            exited.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public boolean isAlive() {
            return exited.getCount() > 0;
        }

        @Override
        public long pid() {
            return 4242;
        }

        @Override
        public void destroy() {
            throw new AssertionError("the ladder must send signals through StopSignals,"
                    + " so that what it sent can be seen");
        }
    }
}
