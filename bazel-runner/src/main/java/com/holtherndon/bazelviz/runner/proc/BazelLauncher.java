package com.holtherndon.bazelviz.runner.proc;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.EnvironmentInheritance;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a Bazel process, streams its console output, and stops it when asked
 * (plan 8.1, 8.7).
 *
 * <h2>Direct argv, never a shell</h2>
 *
 * <p>Every element of {@link BazelCommand#toArgv()} becomes one {@code argv}
 * entry. Nothing is quoted, escaped or concatenated into a string, so a target
 * pattern containing a space or a dollar sign is passed through as itself and
 * there is no expansion for anything to be injected into (plan 22.3). Shell
 * mode exists for users who need expansion, and is a separate, labelled path
 * rather than the default one.
 */
public final class BazelLauncher {

    private static final Logger log = LoggerFactory.getLogger(BazelLauncher.class);

    /** Environment variables Bazel needs to run at all. */
    private static final Set<String> ESSENTIAL_ENVIRONMENT =
            Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "SHELL", "LANG", "LC_ALL");

    private BazelLauncher() {}

    /**
     * Starts the process and returns a handle to it.
     *
     * <p>Two pump threads begin draining stdout and stderr immediately, before
     * this returns. A pipe has a finite buffer, and Bazel fills the stderr one
     * quickly; a caller that started the process and then did something else
     * first would find the build blocked on a write nobody was reading.
     */
    public static BazelProcess start(LaunchRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        BazelCommand command = request.command();
        if (command.isEmpty()) {
            throw new IOException("there is no Bazel command to run");
        }
        if (!Files.isDirectory(command.workingDirectory())) {
            throw new IOException(
                    "the working directory does not exist: " + command.workingDirectory());
        }

        List<String> argv = command.shellMode()
                ? shellArgv(command, request.shellPath())
                : command.toArgv();

        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(command.workingDirectory().toFile());
        applyEnvironment(builder, command);

        log.info("launching {} in {}", String.join(" ", argv), command.workingDirectory());
        Process process = builder.start();
        return new BazelProcess(process, request, argv);
    }

    /**
     * The argv for shell mode.
     *
     * <p>Present so that a user who needs a wrapper, a pipe or a substitution
     * can have one, and so that what they get is exactly what the dialog showed
     * them. It is not reachable by default, and the command it builds is
     * displayed verbatim — a shell mode whose real command line the user cannot
     * read would be worse than none.
     */
    private static List<String> shellArgv(BazelCommand command, Optional<String> shellPath) {
        String shell = shellPath.orElseGet(() -> {
            String fromEnvironment = System.getenv("SHELL");
            return fromEnvironment == null || fromEnvironment.isBlank() ? "/bin/sh" : fromEnvironment;
        });
        return List.of(shell, "-c", String.join(" ", command.toArgv()));
    }

    private static void applyEnvironment(ProcessBuilder builder, BazelCommand command) {
        Map<String, String> environment = builder.environment();
        switch (command.inheritance()) {
            case INHERIT_ALL -> {
                // Nothing to do: ProcessBuilder starts from this process's
                // environment, which is the user's.
            }
            case INHERIT_ALLOWLISTED -> {
                Map<String, String> kept = new java.util.LinkedHashMap<>();
                for (String name : ESSENTIAL_ENVIRONMENT) {
                    String value = environment.get(name);
                    if (value != null) {
                        kept.put(name, value);
                    }
                }
                environment.clear();
                environment.putAll(kept);
            }
            case NONE -> environment.clear();
        }
        command.environmentOverrides().forEach((name, value) ->
                value.ifPresentOrElse(
                        present -> environment.put(name, present),
                        () -> environment.remove(name)));
        if (command.inheritance() != EnvironmentInheritance.INHERIT_ALL
                && !environment.containsKey("PATH")) {
            // Said out loud rather than silently repaired: Bazel without a PATH
            // fails for reasons that have nothing to do with the user's command,
            // and quietly adding one back would make the recorded environment a
            // lie about what ran.
            log.warn("the launched build has no PATH; Bazel will probably fail to find its tools");
        }
    }

    /**
     * A running Bazel process.
     *
     * <p>Owns the two pump threads and the cancellation ladder. Closing it does
     * not stop the process — {@link #cancel} does that — because a session may
     * legitimately outlive the object watching it, and a {@code close} that
     * killed a build would be a footgun in a try-with-resources.
     */
    public static final class BazelProcess {

        private final Process process;
        private final LaunchRequest request;
        private final List<String> argv;
        private final Thread stdout;
        private final Thread stderr;
        private final long startedNanos = System.nanoTime();

        private volatile CancellationMode cancelledWith;

        BazelProcess(Process process, LaunchRequest request, List<String> argv) {
            this.process = process;
            this.request = request;
            this.argv = List.copyOf(argv);
            this.stdout = pump(process.getInputStream(), ConsoleSink.ConsoleStream.STDOUT);
            this.stderr = pump(process.getErrorStream(), ConsoleSink.ConsoleStream.STDERR);
        }

        /** The exact argv that was executed, for the manifest. */
        public List<String> argv() {
            return argv;
        }

        public long pid() {
            return process.pid();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        /**
         * Waits for the process to exit, then keeps draining briefly.
         *
         * <p>The drain is plan 8.7's "continue draining output briefly": the
         * last lines of a failing build arrive after the client has exited, and
         * a capture that stopped reading at exit would lose exactly the error
         * the user is looking for.
         */
        public ProcessOutcome await() throws InterruptedException {
            int exitCode = process.waitFor();
            drain();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
            CancellationMode mode = cancelledWith;
            return mode == null
                    ? ProcessOutcome.exited(exitCode, elapsed)
                    : ProcessOutcome.cancelled(java.util.OptionalInt.of(exitCode), mode, elapsed);
        }

        /**
         * Stops the process, escalating through the ladder if it does not go.
         *
         * <p>{@link CancellationMode#CANCEL} sends {@code SIGINT}, which is what
         * Ctrl-C sends and what makes Bazel interrupt the build <em>and still
         * flush its event stream</em>. {@link Process#destroy()} sends
         * {@code SIGTERM} instead, which is a different and less useful ending,
         * so the interrupt is sent with {@code kill} rather than through the
         * Java API that looks like it would do it.
         *
         * @param mode the gentlest stop to try
         * @param escalate whether to continue to harsher stops when the grace
         *     period passes. False means "try this and report", which is what a
         *     first click of Cancel should do
         */
        public ProcessOutcome cancel(CancellationMode mode, boolean escalate) throws InterruptedException {
            Objects.requireNonNull(mode, "mode");
            CancellationMode current = mode;
            while (true) {
                cancelledWith = current;
                apply(current);
                Duration grace = request.gracePeriodFor(current);
                if (process.waitFor(grace.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    drain();
                    return ProcessOutcome.cancelled(
                            java.util.OptionalInt.of(process.exitValue()),
                            current,
                            Duration.ofNanos(System.nanoTime() - startedNanos));
                }
                Optional<CancellationMode> next = escalate ? current.escalation() : Optional.empty();
                if (next.isEmpty()) {
                    drain();
                    // No exit code: the process is still running, or was killed
                    // without producing one. Reporting 0 or -1 here would be an
                    // unavailable value shown as a number.
                    return ProcessOutcome.cancelled(
                            java.util.OptionalInt.empty(),
                            current,
                            Duration.ofNanos(System.nanoTime() - startedNanos));
                }
                log.info("{} did not stop within {}; escalating to {}", pid(), grace, next.get());
                current = next.get();
            }
        }

        private void apply(CancellationMode mode) {
            switch (mode) {
                case CANCEL -> sendInterrupt();
                case TERMINATE -> process.destroy();
                case FORCE_KILL -> {
                    // Descendants first, then the process itself: killing the
                    // parent first can reparent the children to init, where
                    // this handle can no longer find them.
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        }

        /**
         * Sends {@code SIGINT}, which the JDK has no API for.
         *
         * <p>Falls back to {@code SIGTERM} when {@code kill} is unavailable —
         * on a platform without it, a slightly harsher stop is better than a
         * cancel button that does nothing.
         */
        private void sendInterrupt() {
            try {
                Process kill = new ProcessBuilder("/bin/kill", "-INT", Long.toString(process.pid()))
                        .redirectErrorStream(true)
                        .start();
                if (!kill.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || kill.exitValue() != 0) {
                    log.info("could not interrupt {}; falling back to terminate", process.pid());
                    process.destroy();
                }
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.info("could not interrupt {} ({}); falling back to terminate",
                        process.pid(), failure.toString());
                process.destroy();
            }
        }

        /** Waits out the configured post-exit drain window. */
        private void drain() {
            long deadline = System.nanoTime() + request.drainAfterExit().toNanos();
            joinUntil(stdout, deadline);
            joinUntil(stderr, deadline);
        }

        private static void joinUntil(Thread thread, long deadlineNanos) {
            long remaining = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000);
            try {
                thread.join(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private Thread pump(InputStream stream, ConsoleSink.ConsoleStream which) {
            Thread thread = new Thread(() -> {
                byte[] buffer = new byte[8 * 1024];
                try (stream) {
                    int read;
                    while ((read = stream.read(buffer)) > 0) {
                        request.console().accept(which, buffer, 0, read);
                    }
                } catch (IOException closed) {
                    // The process ended and the pipe went with it. Everything
                    // read so far has already been delivered.
                    log.debug("{} pipe closed: {}", which, closed.toString());
                } catch (RuntimeException misbehaving) {
                    // A sink that throws must not take the build's output with
                    // it, and must not leave the pipe unread and the build
                    // blocked on a full buffer.
                    log.warn("the console sink failed while reading {}", which, misbehaving);
                }
            }, "bbv-console-" + which.name().toLowerCase(java.util.Locale.ROOT));
            thread.setDaemon(true);
            thread.start();
            return thread;
        }
    }

    /** The environment names kept by {@link EnvironmentInheritance#INHERIT_ALLOWLISTED}. */
    public static List<String> essentialEnvironmentNames() {
        return List.copyOf(new ArrayList<>(ESSENTIAL_ENVIRONMENT));
    }
}
