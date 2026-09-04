package com.holtherndon.bazelviz.runner.proc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short command and collects its output.
 *
 * <p>For probes — {@code bazel --version}, {@code bazel help flags-as-proto}, {@code bazel info
 * workspace} — not for builds. A build needs streaming output, cancellation and a process tree;
 * this needs an answer and a timeout.
 *
 * <h2>Why the streams are drained on separate threads</h2>
 *
 * <p>A pipe has a finite buffer. Reading stdout to completion before touching stderr deadlocks the
 * moment the child fills the stderr buffer — and Bazel writes a great deal to stderr, including on
 * a successful probe. Merging the two streams would avoid the deadlock but corrupt the answer:
 * {@code help flags-as-proto} writes base64 to stdout and warnings to stderr, and interleaving them
 * produces something that is not base64 any more.
 */
public final class Subprocess {

  /** Maximum stdout retained from one short probe. The stream is still drained after this. */
  public static final int MAX_STDOUT_BYTES = 16 * 1024 * 1024;

  /** Maximum stderr retained from one short probe. The stream is still drained after this. */
  public static final int MAX_STDERR_BYTES = 16 * 1024 * 1024;

  /** What a probe produced. */
  public record Result(
      int exitCode,
      String stdout,
      String stderr,
      boolean timedOut,
      boolean stdoutTruncated,
      boolean stderrTruncated,
      long stdoutBytes,
      long stderrBytes,
      int stdoutLimit,
      int stderrLimit) {

    /** Source-compatible constructor for callers that already hold complete output strings. */
    public Result(int exitCode, String stdout, String stderr, boolean timedOut) {
      this(
          exitCode,
          stdout,
          stderr,
          timedOut,
          false,
          false,
          stdout.getBytes(StandardCharsets.UTF_8).length,
          stderr.getBytes(StandardCharsets.UTF_8).length,
          MAX_STDOUT_BYTES,
          MAX_STDERR_BYTES);
    }

    public Result {
      Objects.requireNonNull(stdout, "stdout");
      Objects.requireNonNull(stderr, "stderr");
      if (stdoutBytes < 0 || stderrBytes < 0) {
        throw new IllegalArgumentException("captured byte counts must not be negative");
      }
      requirePositiveLimit("stdoutLimit", stdoutLimit);
      requirePositiveLimit("stderrLimit", stderrLimit);
    }

    public boolean isSuccess() {
      return !timedOut && !outputTruncated() && exitCode == 0;
    }

    /** True when either retained string is only a prefix of what the command produced. */
    public boolean outputTruncated() {
      return stdoutTruncated || stderrTruncated;
    }

    /** The best single explanation of a failure, for a message to the user. */
    public String failureDetail() {
      if (timedOut) {
        return "the command did not finish in time";
      }
      if (outputTruncated()) {
        return truncationDetail();
      }
      String detail = stderr.isBlank() ? stdout : stderr;
      String trimmed = detail.strip();
      if (trimmed.isEmpty()) {
        return "exit code " + exitCode + " with no output";
      }
      return "exit code " + exitCode + ": " + firstLines(trimmed, 5);
    }

    private String truncationDetail() {
      StringBuilder detail = new StringBuilder("the command's ");
      if (stdoutTruncated) {
        appendTruncation(detail, "stdout", stdoutBytes, stdoutLimit);
      }
      if (stdoutTruncated && stderrTruncated) {
        detail.append("; ");
      }
      if (stderrTruncated) {
        appendTruncation(detail, "stderr", stderrBytes, stderrLimit);
      }
      detail.append("; the retained text is only a prefix");
      return detail.toString();
    }

    private static void appendTruncation(
        StringBuilder detail, String stream, long receivedBytes, int limit) {
      detail.append(stream);
      if (receivedBytes > limit) {
        detail
            .append(" exceeded the ")
            .append(limit)
            .append("-byte capture limit after receiving ");
      } else {
        detail
            .append(" could not be drained to completion (the capture limit was ")
            .append(limit)
            .append(" bytes) after receiving ");
      }
      detail.append(receivedBytes).append(" bytes");
    }

    private static String firstLines(String text, int limit) {
      String[] lines = text.split("\n", limit + 1);
      int count = Math.min(lines.length, limit);
      return String.join("\n", Arrays.copyOf(lines, count));
    }
  }

  private Subprocess() {}

  /**
   * Runs {@code argv} in {@code workingDirectory} and waits up to {@code timeout}.
   *
   * <p>A process that outlives the timeout is destroyed forcibly, not merely abandoned: a probe
   * that hangs would otherwise leave a Bazel server starting up behind the user's back for the rest
   * of the session.
   *
   * @param environment variables to set; the rest of this process's environment is inherited
   */
  public static Result run(
      List<String> argv, Path workingDirectory, Map<String, String> environment, Duration timeout)
      throws IOException, InterruptedException {
    return run(argv, workingDirectory, environment, timeout, MAX_STDOUT_BYTES, MAX_STDERR_BYTES);
  }

  /** Package seam for exercising the same bounded collector with small limits. */
  static Result run(
      List<String> argv,
      Path workingDirectory,
      Map<String, String> environment,
      Duration timeout,
      int stdoutLimit,
      int stderrLimit)
      throws IOException, InterruptedException {
    Objects.requireNonNull(argv, "argv");
    Objects.requireNonNull(environment, "environment");
    requirePositiveTimeout(timeout);
    requirePositiveLimit("stdoutLimit", stdoutLimit);
    requirePositiveLimit("stderrLimit", stderrLimit);
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("argv must not be empty");
    }
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    builder.environment().putAll(environment);
    Process process = builder.start();
    closeQuietly(process.getOutputStream());

    StreamDrain out = StreamDrain.start(process.getInputStream(), "subprocess-stdout", stdoutLimit);
    StreamDrain err = StreamDrain.start(process.getErrorStream(), "subprocess-stderr", stderrLimit);

    boolean exited;
    try {
      exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      stop(process, out, err);
      finishAfterInterruption(interrupted, out, err);
      throw interrupted;
    }
    if (!exited) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      awaitForcedTermination(process, out, err);
      return result(-1, out, err, true, stdoutLimit, stderrLimit);
    }
    return result(process.exitValue(), out, err, false, stdoutLimit, stderrLimit);
  }

  /**
   * Runs {@code argv} with its standard output written straight to a file.
   *
   * <p>For a subprocess whose output is binary. {@link #run} decodes stdout as UTF-8, which is
   * right for {@code bazel help} and destructive for {@code aquery --output=proto}: every byte
   * sequence that is not valid UTF-8 becomes a replacement character, and the damage is not
   * recoverable. Redirecting to a file moves the bytes without the JVM looking at them.
   *
   * <p>stderr is still captured as text, because that is a message for a person.
   *
   * @param outputFile overwritten; its parent must exist
   * @return the exit code and stderr; {@link Result#stdout()} is always empty because the output
   *     went to the file
   */
  public static Result runRedirectingStdout(
      List<String> argv,
      Path workingDirectory,
      Map<String, String> environment,
      Duration timeout,
      Path outputFile)
      throws IOException, InterruptedException {
    Objects.requireNonNull(argv, "argv");
    Objects.requireNonNull(outputFile, "outputFile");
    Objects.requireNonNull(environment, "environment");
    requirePositiveTimeout(timeout);
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("argv must not be empty");
    }
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    builder.environment().putAll(environment);
    builder.redirectOutput(ProcessBuilder.Redirect.to(outputFile.toFile()));
    Process process = builder.start();
    closeQuietly(process.getOutputStream());

    StreamDrain err =
        StreamDrain.start(process.getErrorStream(), "subprocess-stderr", MAX_STDERR_BYTES);

    boolean exited;
    try {
      exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      err.close();
      finishAfterInterruption(interrupted, err);
      throw interrupted;
    }
    if (!exited) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      awaitForcedTermination(process, err);
      Captured error = err.await();
      return new Result(
          -1,
          "",
          error.text(),
          true,
          false,
          error.truncated(),
          0,
          error.totalBytes(),
          MAX_STDOUT_BYTES,
          MAX_STDERR_BYTES);
    }
    Captured error = err.await();
    return new Result(
        process.exitValue(),
        "",
        error.text(),
        false,
        false,
        error.truncated(),
        0,
        error.totalBytes(),
        MAX_STDOUT_BYTES,
        MAX_STDERR_BYTES);
  }

  private static Result result(
      int exitCode,
      StreamDrain stdout,
      StreamDrain stderr,
      boolean timedOut,
      int stdoutLimit,
      int stderrLimit)
      throws IOException, InterruptedException {
    Captured out = stdout.await();
    Captured err = stderr.await();
    return new Result(
        exitCode,
        out.text(),
        err.text(),
        timedOut,
        out.truncated() || out.totalBytes() > stdoutLimit,
        err.truncated() || err.totalBytes() > stderrLimit,
        out.totalBytes(),
        err.totalBytes(),
        stdoutLimit,
        stderrLimit);
  }

  private static void stop(Process process, StreamDrain stdout, StreamDrain stderr) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    stdout.close();
    stderr.close();
  }

  private static void awaitForcedTermination(Process process, StreamDrain... drains)
      throws InterruptedException {
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      for (StreamDrain drain : drains) {
        drain.close();
      }
      finishAfterInterruption(interrupted, drains);
      throw interrupted;
    } finally {
      for (StreamDrain drain : drains) {
        drain.close();
      }
    }
  }

  private static void finishAfterInterruption(
      InterruptedException interrupted, StreamDrain... drains) {
    for (StreamDrain drain : drains) {
      try {
        drain.await();
      } catch (IOException | InterruptedException cleanupFailure) {
        interrupted.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void requirePositiveTimeout(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  private static void requirePositiveLimit(String name, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException(name + " must be positive: " + limit);
    }
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The child already closed its side.
    }
  }

  private record Captured(String text, long totalBytes, boolean truncated) {}

  /** Reads one stream to completion while retaining only a bounded prefix. */
  private static final class StreamDrain {

    private final InputStream stream;
    private final int limit;
    private final Thread thread;
    private final ByteArrayOutputStream retained;
    private volatile long totalBytes;
    private volatile boolean truncated;
    private volatile boolean ownerClosed;
    private volatile IOException failure;

    private StreamDrain(InputStream stream, String name, int limit) {
      this.stream = stream;
      this.limit = limit;
      this.retained = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
      this.thread =
          Thread.ofVirtual()
              .name(name)
              .unstarted(
                  () -> {
                    try (stream) {
                      byte[] buffer = new byte[16 * 1024];
                      int read;
                      while ((read = stream.read(buffer)) >= 0) {
                        if (read == 0) {
                          continue;
                        }
                        totalBytes = saturatedAdd(totalBytes, read);
                        int remaining = limit - retained.size();
                        if (remaining > 0) {
                          retained.write(buffer, 0, Math.min(read, remaining));
                        }
                        if (read > remaining) {
                          truncated = true;
                        }
                      }
                    } catch (IOException readFailure) {
                      if (!ownerClosed) {
                        failure = readFailure;
                      }
                    }
                  });
    }

    static StreamDrain start(InputStream stream, String name, int limit) {
      StreamDrain drain = new StreamDrain(stream, name, limit);
      drain.thread.start();
      return drain;
    }

    Captured await() throws IOException, InterruptedException {
      thread.join(TimeUnit.SECONDS.toMillis(5));
      if (thread.isAlive()) {
        truncated = true;
        close();
        thread.join(TimeUnit.SECONDS.toMillis(5));
      }
      if (thread.isAlive()) {
        thread.interrupt();
        throw new IOException("could not finish draining subprocess output");
      }
      if (failure != null) {
        throw new IOException("could not drain subprocess output", failure);
      }
      return new Captured(retained.toString(StandardCharsets.UTF_8), totalBytes, truncated);
    }

    void close() {
      ownerClosed = true;
      try {
        stream.close();
      } catch (IOException ignored) {
        // The process already closed its side.
      }
    }

    private static long saturatedAdd(long left, int right) {
      if (left > Long.MAX_VALUE - right) {
        return Long.MAX_VALUE;
      }
      return left + right;
    }
  }
}
