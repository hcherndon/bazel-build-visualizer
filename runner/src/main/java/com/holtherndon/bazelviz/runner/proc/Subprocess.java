package com.holtherndon.bazelviz.runner.proc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

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

  /** Maximum live descendant handles retained while a short probe runs. */
  public static final int MAX_TRACKED_DESCENDANTS = 4_096;

  /** Time allowed for a pipe to reach EOF after the root process exits. */
  public static final long OUTPUT_DRAIN_GRACE_MILLIS = 1_000L;

  private static final Path POSIX_SHELL = Path.of("/bin/sh");
  private static final Path USR_BIN_SETSID = Path.of("/usr/bin/setsid");
  private static final Path BIN_SETSID = Path.of("/bin/setsid");
  private static final String PROCESS_GROUP_CONTROL_PREFIX = "bazelviz-probe-group-";
  private static final Set<PosixFilePermission> PRIVATE_CONTROL_PERMISSIONS =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
  private static final String POSIX_GROUP_WRAPPER =
      "control=$1; shift; set -m; "
          + "(while ! IFS= read -r gate < \"$control\"; do :; done; exec \"$@\") "
          + "& child=$!; "
          + "set +m; "
          + "if kill -0 \"-$child\" 2>/dev/null; then "
          + "printf 'READY %s\\n' \"$child\" > \"$control\"; "
          + "else printf 'UNAVAILABLE\\n' > \"$control\"; fi; "
          + "wait \"$child\"";
  private static final String LINUX_SESSION_WRAPPER =
      "control=$1; shift; printf 'READY %s\\n' \"$$\" > \"$control\"; exec \"$@\"";
  private static final String GROUP_SIGNAL_WRAPPER = "kill \"$1\" \"$2\"";

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
      if (timedOut || outputTruncated()) {
        StringBuilder detail = new StringBuilder();
        if (timedOut) {
          detail.append("the command did not finish in time");
        }
        if (outputTruncated()) {
          if (!detail.isEmpty()) {
            detail.append("; ");
          }
          detail.append(truncationDetail());
        }
        return detail.toString();
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
    try (ProcessIsolation isolation = ProcessIsolation.prepare(argv)) {
      ProcessBuilder builder = builder(isolation.command(), workingDirectory, environment);
      Process process = builder.start();
      closeQuietly(process.getOutputStream());

      StreamDrain out =
          StreamDrain.start(process.getInputStream(), "subprocess-stdout", stdoutLimit);
      StreamDrain err =
          StreamDrain.start(process.getErrorStream(), "subprocess-stderr", stderrLimit);
      DescendantTracker descendants = new DescendantTracker();
      try {
        boolean exited = awaitExit(process, timeout, descendants, isolation);
        if (!exited) {
          forceAndVerify(process, descendants, isolation);
        }
        return result(
            exited ? process.exitValue() : -1,
            process,
            descendants,
            isolation,
            out,
            err,
            !exited,
            stdoutLimit,
            stderrLimit);
      } catch (IOException | InterruptedException | RuntimeException failure) {
        cleanupAfterFailure(process, descendants, isolation, failure, out, err);
        throw failure;
      }
    }
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
    return runRedirectingStdout(
        argv, workingDirectory, environment, timeout, outputFile, MAX_STDERR_BYTES);
  }

  /** Package seam for testing redirected probes with a small stderr limit. */
  static Result runRedirectingStdout(
      List<String> argv,
      Path workingDirectory,
      Map<String, String> environment,
      Duration timeout,
      Path outputFile,
      int stderrLimit)
      throws IOException, InterruptedException {
    Objects.requireNonNull(argv, "argv");
    Objects.requireNonNull(outputFile, "outputFile");
    Objects.requireNonNull(environment, "environment");
    requirePositiveTimeout(timeout);
    requirePositiveLimit("stderrLimit", stderrLimit);
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("argv must not be empty");
    }
    try (ProcessIsolation isolation = ProcessIsolation.prepare(argv)) {
      ProcessBuilder builder = builder(isolation.command(), workingDirectory, environment);
      builder.redirectOutput(ProcessBuilder.Redirect.to(outputFile.toFile()));
      Process process = builder.start();
      closeQuietly(process.getOutputStream());

      StreamDrain err =
          StreamDrain.start(process.getErrorStream(), "subprocess-stderr", stderrLimit);
      DescendantTracker descendants = new DescendantTracker();
      try {
        boolean exited = awaitExit(process, timeout, descendants, isolation);
        if (!exited) {
          forceAndVerify(process, descendants, isolation);
        }
        Captured error = err.await();
        if (error.incomplete()) {
          forceAndVerify(process, descendants, isolation);
        }
        return new Result(
            exited ? process.exitValue() : -1,
            "",
            error.text(),
            !exited,
            false,
            error.truncated() || error.incomplete(),
            0,
            error.totalBytes(),
            MAX_STDOUT_BYTES,
            stderrLimit);
      } catch (IOException | InterruptedException | RuntimeException failure) {
        cleanupAfterFailure(process, descendants, isolation, failure, err);
        throw failure;
      }
    }
  }

  private static ProcessBuilder builder(
      List<String> command, Path workingDirectory, Map<String, String> environment) {
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    builder.environment().putAll(environment);
    return builder;
  }

  private static Result result(
      int exitCode,
      Process process,
      DescendantTracker descendants,
      ProcessIsolation isolation,
      StreamDrain stdout,
      StreamDrain stderr,
      boolean timedOut,
      int stdoutLimit,
      int stderrLimit)
      throws IOException, InterruptedException {
    Captured out = stdout.await();
    if (out.incomplete()) {
      forceAndVerify(process, descendants, isolation);
    }
    Captured err = stderr.await();
    if (err.incomplete()) {
      forceAndVerify(process, descendants, isolation);
    }
    return new Result(
        exitCode,
        out.text(),
        err.text(),
        timedOut,
        out.truncated() || out.incomplete() || out.totalBytes() > stdoutLimit,
        err.truncated() || err.incomplete() || err.totalBytes() > stderrLimit,
        out.totalBytes(),
        err.totalBytes(),
        stdoutLimit,
        stderrLimit);
  }

  private static void cleanupAfterFailure(
      Process process,
      DescendantTracker descendants,
      ProcessIsolation isolation,
      Throwable failure,
      StreamDrain... drains) {
    try {
      forceAndVerify(process, descendants, isolation);
    } catch (IOException | InterruptedException | RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
    for (StreamDrain drain : drains) {
      drain.close();
    }
    for (StreamDrain drain : drains) {
      try {
        drain.awaitCleanup();
      } catch (IOException | InterruptedException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }

  private static boolean awaitExit(
      Process process, Duration timeout, DescendantTracker descendants, ProcessIsolation isolation)
      throws IOException, InterruptedException {
    long timeoutNanos;
    try {
      timeoutNanos = timeout.toNanos();
    } catch (ArithmeticException tooLarge) {
      timeoutNanos = Long.MAX_VALUE;
    }
    long started = System.nanoTime();
    while (true) {
      isolation.capture(process);
      descendants.capture(process);
      if (descendants.overflowed()) {
        throw new IOException(
            "subprocess spawned more than "
                + MAX_TRACKED_DESCENDANTS
                + " descendants; it was stopped so cleanup remains bounded");
      }
      long elapsed = System.nanoTime() - started;
      long remaining = timeoutNanos - Math.min(timeoutNanos, elapsed);
      if (remaining <= 0) {
        return !process.isAlive();
      }
      long waitNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20));
      long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(waitNanos));
      if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
        isolation.capture(process);
        descendants.capture(process);
        return true;
      }
    }
  }

  private static void forceAndVerify(
      Process process, DescendantTracker descendants, ProcessIsolation isolation)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      isolation.capture(process);
      descendants.capture(process);
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      descendants.destroyAll();
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      isolation.destroyForcibly();
      if (!process.isAlive() && !descendants.anyAlive() && !isolation.anyAlive()) {
        return;
      }
      if (System.nanoTime() >= deadline) {
        throw new IOException("could not terminate every process in the subprocess tree");
      }
      if (Thread.interrupted()) {
        throw new InterruptedException("interrupted while reaping a subprocess tree");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
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

  /** Package seam for proving that a partial process-group handshake is never accepted. */
  static long parseProcessGroupControlResponse(
      byte[] response, boolean writerAlive, long rootPid, boolean rootMayOwnGroup)
      throws IOException {
    if (response.length > 64) {
      throw new IOException("process-group control response exceeds 64 bytes");
    }
    if (response.length == 0) {
      return 0;
    }
    if (response[response.length - 1] != '\n') {
      if (!writerAlive) {
        throw new IOException("incomplete process-group control response");
      }
      return 0;
    }
    String state = new String(response, 0, response.length - 1, StandardCharsets.US_ASCII);
    if (state.indexOf('\n') >= 0 || state.indexOf('\r') >= 0) {
      throw new IOException("invalid multiline process-group control response");
    }
    if (state.equals("UNAVAILABLE")) {
      return -1;
    }
    if (state.startsWith("READY ")) {
      try {
        long candidate = Long.parseLong(state.substring("READY ".length()));
        if (candidate <= 0
            || (!rootMayOwnGroup && candidate == rootPid)
            || candidate == ProcessHandle.current().pid()) {
          throw new NumberFormatException("unsafe process-group id " + candidate);
        }
        return candidate;
      } catch (NumberFormatException malformed) {
        throw new IOException("invalid process-group control response: " + state, malformed);
      }
    }
    throw new IOException("invalid process-group control response: " + state);
  }

  /** Package seam for checking the Linux session wrapper without requiring Linux locally. */
  static List<String> linuxProcessGroupCommand(Path setsid, Path controlFile, List<String> argv) {
    List<String> wrapped = new ArrayList<>(argv.size() + 7);
    wrapped.add(setsid.toString());
    wrapped.add("--wait");
    wrapped.add(POSIX_SHELL.toString());
    wrapped.add("-c");
    wrapped.add(LINUX_SESSION_WRAPPER);
    wrapped.add("bazelviz-probe-group");
    wrapped.add(controlFile.toString());
    wrapped.addAll(argv);
    return List.copyOf(wrapped);
  }

  /** Package seam for checking that process-group signalling cannot become a shell command. */
  static List<String> groupSignalCommand(String signal, long groupId) {
    if (!signal.equals("-0") && !signal.equals("-KILL")) {
      throw new IllegalArgumentException("unsupported process-group signal: " + signal);
    }
    if (groupId <= 0) {
      throw new IllegalArgumentException("process-group id must be positive: " + groupId);
    }
    return List.of(
        POSIX_SHELL.toString(),
        "-c",
        GROUP_SIGNAL_WRAPPER,
        "bazelviz-probe-kill",
        signal,
        "-" + groupId);
  }

  private record Captured(String text, long totalBytes, boolean truncated, boolean incomplete) {}

  /**
   * Gives a probe its own POSIX process group so a child remains identifiable after reparenting.
   *
   * <p>{@link ProcessHandle#descendants()} cannot recover a child after a fast parent exits. On
   * macOS, the wrapper creates a monitored shell job. On Linux, the standard {@code setsid} helper
   * creates a new session, then a shell gate records its own group before executing the exact
   * argument vector. The private control file is the only out-of-band channel; stdout and stderr
   * remain byte-for-byte command streams. An unverified host uses only {@link DescendantTracker}.
   */
  private static final class ProcessIsolation implements AutoCloseable {

    private final List<String> command;
    private final Path controlFile;
    private final boolean rootMayOwnGroup;
    private long groupId;
    private boolean unavailable;

    private ProcessIsolation(List<String> command, Path controlFile, boolean rootMayOwnGroup) {
      this.command = List.copyOf(command);
      this.controlFile = controlFile;
      this.rootMayOwnGroup = rootMayOwnGroup;
    }

    static ProcessIsolation prepare(List<String> argv) throws IOException {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (!supportsPosixControls()) {
        return direct(argv);
      }
      Path linuxSetsid = os.contains("linux") ? linuxSetsid() : null;
      boolean linux = os.contains("linux") && linuxSetsid != null;
      boolean mac = os.startsWith("mac") || os.contains("darwin");
      if (!linux && !mac) {
        return direct(argv);
      }
      Path control =
          Files.createTempFile(
              PROCESS_GROUP_CONTROL_PREFIX,
              ".control",
              PosixFilePermissions.asFileAttribute(PRIVATE_CONTROL_PERMISSIONS));
      try {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(control);
        if (!permissions.equals(PRIVATE_CONTROL_PERMISSIONS)) {
          throw new IOException(
              "process-group control file is not private: "
                  + PosixFilePermissions.toString(permissions));
        }
        if (linux) {
          return new ProcessIsolation(
              linuxProcessGroupCommand(linuxSetsid, control, argv), control, true);
        }
        List<String> wrapped = new ArrayList<>(argv.size() + 5);
        wrapped.add(POSIX_SHELL.toString());
        wrapped.add("-c");
        wrapped.add(POSIX_GROUP_WRAPPER);
        wrapped.add("bazelviz-probe-group");
        wrapped.add(control.toString());
        wrapped.addAll(argv);
        return new ProcessIsolation(wrapped, control, false);
      } catch (IOException | RuntimeException failure) {
        try {
          Files.deleteIfExists(control);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
        throw failure;
      }
    }

    List<String> command() {
      return command;
    }

    void capture(Process root) throws IOException {
      if (controlFile == null || groupId > 0 || unavailable) {
        return;
      }
      byte[] response;
      try (InputStream input = Files.newInputStream(controlFile)) {
        response = input.readNBytes(65);
      }
      long state =
          parseProcessGroupControlResponse(response, root.isAlive(), root.pid(), rootMayOwnGroup);
      if (state < 0) {
        unavailable = true;
        return;
      }
      if (state > 0) {
        groupId = state;
      }
    }

    void destroyForcibly() throws IOException, InterruptedException {
      if (groupId > 0) {
        signal("-KILL");
      }
    }

    boolean anyAlive() throws IOException, InterruptedException {
      return groupId > 0 && signal("-0") == 0;
    }

    private int signal(String signal) throws IOException, InterruptedException {
      Process sender =
          new ProcessBuilder(groupSignalCommand(signal, groupId))
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      closeQuietly(sender.getOutputStream());
      try {
        if (!sender.waitFor(1, TimeUnit.SECONDS)) {
          sender.destroyForcibly();
          sender.onExit().join();
          throw new IOException("timed out while signalling subprocess process group " + groupId);
        }
        return sender.exitValue();
      } catch (InterruptedException interrupted) {
        sender.destroyForcibly();
        try {
          sender.onExit().join();
        } catch (RuntimeException cleanupFailure) {
          interrupted.addSuppressed(cleanupFailure);
        }
        throw interrupted;
      }
    }

    @Override
    public void close() throws IOException {
      if (controlFile != null) {
        Files.deleteIfExists(controlFile);
      }
    }

    private static ProcessIsolation direct(List<String> argv) {
      return new ProcessIsolation(argv, null, false);
    }

    private static boolean supportsPosixControls() {
      return FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
          && Files.isExecutable(POSIX_SHELL);
    }

    private static Path linuxSetsid() {
      if (Files.isExecutable(USR_BIN_SETSID)) {
        return USR_BIN_SETSID;
      }
      return Files.isExecutable(BIN_SETSID) ? BIN_SETSID : null;
    }
  }

  /** Fixed-capacity history of descendants that may outlive and lose their root-process parent. */
  private static final class DescendantTracker {

    private final ProcessHandle[] handles = new ProcessHandle[MAX_TRACKED_DESCENDANTS];
    private int size;
    private boolean overflowed;

    void capture(Process process) {
      process.descendants().forEach(this::remember);
    }

    void destroyAll() {
      for (int index = 0; index < size; index++) {
        ProcessHandle handle = handles[index];
        if (handle != null && handle.isAlive()) {
          handle.destroyForcibly();
        }
      }
    }

    boolean anyAlive() {
      for (int index = 0; index < size; index++) {
        ProcessHandle handle = handles[index];
        if (handle != null && handle.isAlive()) {
          return true;
        }
      }
      return false;
    }

    boolean overflowed() {
      return overflowed;
    }

    private void remember(ProcessHandle handle) {
      for (int index = 0; index < size; index++) {
        ProcessHandle known = handles[index];
        if (known != null && known.pid() == handle.pid()) {
          handles[index] = handle;
          return;
        }
      }
      for (int index = 0; index < size; index++) {
        if (!handles[index].isAlive()) {
          handles[index] = handle;
          return;
        }
      }
      if (size < handles.length) {
        handles[size++] = handle;
        return;
      }
      overflowed = true;
    }
  }

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
      thread.join(OUTPUT_DRAIN_GRACE_MILLIS);
      boolean incomplete = thread.isAlive();
      if (thread.isAlive()) {
        truncated = true;
        close();
        thread.join(OUTPUT_DRAIN_GRACE_MILLIS);
      }
      if (thread.isAlive()) {
        thread.interrupt();
        throw new IOException("could not finish draining subprocess output");
      }
      if (failure != null) {
        throw new IOException("could not drain subprocess output", failure);
      }
      return new Captured(
          retained.toString(StandardCharsets.UTF_8), totalBytes, truncated, incomplete);
    }

    void awaitCleanup() throws IOException, InterruptedException {
      thread.join(OUTPUT_DRAIN_GRACE_MILLIS);
      if (thread.isAlive()) {
        thread.interrupt();
        thread.join(OUTPUT_DRAIN_GRACE_MILLIS);
      }
      if (thread.isAlive()) {
        throw new IOException("could not join subprocess output drain during cleanup");
      }
      if (failure != null) {
        throw new IOException("could not drain subprocess output during cleanup", failure);
      }
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
