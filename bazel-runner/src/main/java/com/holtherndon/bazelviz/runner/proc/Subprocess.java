package com.holtherndon.bazelviz.runner.proc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short command and collects its output.
 *
 * <p>For probes — {@code bazel --version}, {@code bazel help flags-as-proto},
 * {@code bazel info workspace} — not for builds. A build needs streaming
 * output, cancellation and a process tree; this needs an answer and a timeout.
 *
 * <h2>Why the streams are drained on separate threads</h2>
 *
 * <p>A pipe has a finite buffer. Reading stdout to completion before touching
 * stderr deadlocks the moment the child fills the stderr buffer — and Bazel
 * writes a great deal to stderr, including on a successful probe. Merging the
 * two streams would avoid the deadlock but corrupt the answer:
 * {@code help flags-as-proto} writes base64 to stdout and warnings to stderr,
 * and interleaving them produces something that is not base64 any more.
 */
public final class Subprocess {

    /** What a probe produced. */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {

        public Result {
            Objects.requireNonNull(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
        }

        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }

        /** The best single explanation of a failure, for a message to the user. */
        public String failureDetail() {
            if (timedOut) {
                return "the command did not finish in time";
            }
            String detail = stderr.isBlank() ? stdout : stderr;
            String trimmed = detail.strip();
            if (trimmed.isEmpty()) {
                return "exit code " + exitCode + " with no output";
            }
            return "exit code " + exitCode + ": " + firstLines(trimmed, 5);
        }

        private static String firstLines(String text, int limit) {
            String[] lines = text.split("\n", limit + 1);
            int count = Math.min(lines.length, limit);
            return String.join("\n", java.util.Arrays.copyOf(lines, count));
        }
    }

    private Subprocess() {}

    /**
     * Runs {@code argv} in {@code workingDirectory} and waits up to
     * {@code timeout}.
     *
     * <p>A process that outlives the timeout is destroyed forcibly, not merely
     * abandoned: a probe that hangs would otherwise leave a Bazel server
     * starting up behind the user's back for the rest of the session.
     *
     * @param environment variables to set; the rest of this process's
     *     environment is inherited
     */
    public static Result run(
            List<String> argv, Path workingDirectory, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(environment);
        Process process = builder.start();

        StreamDrain out = StreamDrain.start(process.getInputStream(), "subprocess-stdout");
        StreamDrain err = StreamDrain.start(process.getErrorStream(), "subprocess-stderr");

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new Result(-1, out.awaitText(), err.awaitText(), true);
        }
        return new Result(process.exitValue(), out.awaitText(), err.awaitText(), false);
    }

    /** Reads one stream to completion on its own thread. */
    private static final class StreamDrain {

        private final Thread thread;
        private volatile String text = "";

        private StreamDrain(InputStream stream, String name) {
            this.thread = new Thread(() -> {
                try (stream) {
                    text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException closed) {
                    // The process died mid-read. Whatever arrived is what there
                    // is; an empty result here is reported as empty output, not
                    // as a failure of the probe itself.
                    text = "";
                }
            }, name);
            this.thread.setDaemon(true);
        }

        static StreamDrain start(InputStream stream, String name) {
            StreamDrain drain = new StreamDrain(stream, name);
            drain.thread.start();
            return drain;
        }

        String awaitText() throws InterruptedException {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            return text;
        }
    }
}
