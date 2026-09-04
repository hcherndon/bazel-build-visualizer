package com.holtherndon.bazelviz.runner.ssh;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.Subprocess;
import com.holtherndon.bazelviz.runner.proc.Subprocess.EnvironmentInheritance;
import com.holtherndon.bazelviz.runner.proc.Subprocess.ManagedProcess;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import com.holtherndon.bazelviz.runner.runtime.TerminalSize;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes commands on one host through an existing OpenSSH control master. */
public final class SshCommandExecutor implements CommandExecutor {

  private static final Logger log = LoggerFactory.getLogger(SshCommandExecutor.class);

  private static final TerminalSize INITIAL_TERMINAL_SIZE = new TerminalSize(80, 24);
  private static final String TERMINAL_TYPE = "xterm-256color";

  /** Grace given to the local OpenSSH terminal process before a forced kill. */
  public static final Duration TERMINAL_CLOSE_GRACE = Duration.ofSeconds(1);

  private static final List<String> ESSENTIAL_ENVIRONMENT =
      List.of("HOME", "LANG", "LC_ALL", "LOGNAME", "PATH", "SHELL", "TMPDIR", "USER");

  private final SshTarget target;
  private final Path ssh;
  private final Path controlSocket;
  private final BooleanSupplier sessionOpen;

  SshCommandExecutor(SshTarget target, Path ssh, Path controlSocket) {
    this(target, ssh, controlSocket, () -> true);
  }

  SshCommandExecutor(SshTarget target, Path ssh, Path controlSocket, BooleanSupplier sessionOpen) {
    this.target = Objects.requireNonNull(target, "target");
    this.ssh = Objects.requireNonNull(ssh, "ssh");
    this.controlSocket = Objects.requireNonNull(controlSocket, "controlSocket");
    this.sessionOpen = Objects.requireNonNull(sessionOpen, "sessionOpen");
  }

  public SshTarget target() {
    return target;
  }

  @Override
  public CommandResult run(CommandRequest request, Duration timeout)
      throws IOException, InterruptedException {
    requireTimeout(timeout);
    long startedNanos = System.nanoTime();
    logCommandStart("bounded", request, timeout);
    RemoteRunningCommand command = startRemote(request);
    Subprocess.BoundedDrain stdout = null;
    Subprocess.BoundedDrain stderr = null;
    try {
      closeStdinForNonInteractive(request, command);
      stdout =
          Subprocess.BoundedDrain.start(
              command.stdout(), "bbv-ssh-command-stdout", Subprocess.MAX_STDOUT_BYTES);
      stderr =
          Subprocess.BoundedDrain.start(
              command.stderr(), "bbv-ssh-command-stderr", Subprocess.MAX_STDERR_BYTES);
      boolean exited = command.awaitLocalExit(timeout);
      if (!exited) {
        stopTimedOut(command);
      }
      Subprocess.CapturedOutput capturedOut = stdout.await();
      if (capturedOut.incomplete()) {
        stopIncomplete(command);
        throw new IOException("the remote command output could not be drained to completion");
      }
      Subprocess.CapturedOutput capturedErr = stderr.await();
      if (capturedErr.incomplete()) {
        stopIncomplete(command);
        throw new IOException("the remote command output could not be drained to completion");
      }
      if (capturedOut.truncated() || capturedErr.truncated()) {
        throw new IOException("the remote command produced too much probe output");
      }
      String out = capturedOut.text();
      String err = capturedErr.text();
      CommandResult result =
          new CommandResult(exited ? command.exitValue() : -1, out, err, !exited);
      command.close();
      logCommandResult(
          "bounded",
          request,
          result,
          startedNanos,
          Integer.toString(out.getBytes(StandardCharsets.UTF_8).length),
          Integer.toString(err.getBytes(StandardCharsets.UTF_8).length));
      return result;
    } catch (IOException | InterruptedException | RuntimeException failure) {
      cleanupFailedCommandDrains(command, failure, stdout, stderr);
      throw failure;
    }
  }

  @Override
  public CommandResult runRedirectingStdout(
      CommandRequest request, Duration timeout, Path localOutputFile)
      throws IOException, InterruptedException {
    requireTimeout(timeout);
    Objects.requireNonNull(localOutputFile, "localOutputFile");
    if (request.forceTty()) {
      throw new IOException("binary output cannot be transferred through a pseudo-terminal");
    }
    long startedNanos = System.nanoTime();
    logCommandStart("redirected-output", request, timeout);
    Path destination = localOutputFile.toAbsolutePath().normalize();
    Path parent = destination.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new IOException("the output directory does not exist: " + parent);
    }
    Path temporary = Files.createTempFile(parent, ".bbv-ssh-output-", ".tmp");
    boolean moved = false;
    RemoteRunningCommand command = null;
    Subprocess.FileDrain copy = null;
    Subprocess.BoundedDrain stderr = null;
    try {
      command = startRemote(request);
      closeStdinForNonInteractive(request, command);
      stderr =
          Subprocess.BoundedDrain.start(
              command.stderr(), "bbv-ssh-command-stderr", Subprocess.MAX_STDERR_BYTES);
      copy = Subprocess.FileDrain.start(command.stdout(), temporary);
      boolean exited = command.awaitLocalExit(timeout);
      if (!exited) {
        stopTimedOut(command);
      }
      Subprocess.FileDrainResult copied = copy.await();
      Subprocess.CapturedOutput capturedErr = stderr.await();
      if (copied.incomplete() || capturedErr.incomplete()) {
        stopIncomplete(command);
        throw new IOException("the remote command output could not be drained to completion");
      }
      if (capturedErr.truncated()) {
        throw new IOException("the remote command produced too much diagnostic output");
      }
      String err = capturedErr.text();
      CommandResult result = new CommandResult(exited ? command.exitValue() : -1, "", err, !exited);
      command.close();
      if (result.isSuccess()) {
        moveReplacement(temporary, destination);
        moved = true;
      }
      logCommandResult(
          "redirected-output",
          request,
          result,
          startedNanos,
          fileSize(moved ? destination : temporary),
          Integer.toString(err.getBytes(StandardCharsets.UTF_8).length));
      return result;
    } catch (IOException | InterruptedException | RuntimeException failure) {
      if (command != null) {
        cleanupFailedCommandCopy(command, failure, stderr, copy);
      }
      throw failure;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  @Override
  public RunningCommand start(CommandRequest request) throws IOException {
    logCommandStart("streaming", request, null);
    return startRemote(request);
  }

  @Override
  public InteractiveChannel openTerminal(String workingDirectory) throws IOException {
    requireSessionOpen();
    long startedNanos = System.nanoTime();
    log.info(
        "SSH terminal opening target={} workingDirectorySet={}",
        target.displayName(),
        workingDirectory != null && !workingDirectory.isBlank());
    String command = terminalCommand(workingDirectory);
    List<String> argv =
        SshControlSession.commandArguments(ssh, controlSocket, target, true, command);
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.put("TERM", TERMINAL_TYPE);
    PtyProcess process =
        new PtyProcessBuilder(argv.toArray(String[]::new))
            .setEnvironment(environment)
            .setConsole(false)
            .setRedirectErrorStream(true)
            .setInitialColumns(INITIAL_TERMINAL_SIZE.columns())
            .setInitialRows(INITIAL_TERMINAL_SIZE.rows())
            .start();
    log.info(
        "SSH terminal opened target={} processId={} durationMs={}",
        target.displayName(),
        process.pid(),
        elapsedMillis(startedNanos));
    return new TerminalChannel(process, target.displayName());
  }

  static String terminalCommand(String workingDirectory) {
    String directory = requireRemotePath(workingDirectory, "working directory");
    // OpenSSH copies the desktop PTY's termios values to the Linux PTY.
    // Some macOS values map poorly (notably disabling ICRNL), which makes
    // JediTerm's normal carriage-return Enter key stop submitting lines.
    return "cd -- "
        + PosixShell.quote(directory)
        + " && /usr/bin/stty sane && exec \"${SHELL:-/bin/sh}\" -l";
  }

  static String remoteCommand(CommandRequest request, String marker) {
    Objects.requireNonNull(request, "request");
    if (!marker.matches("BBV_PROCESS_[0-9a-f]+:")) {
      throw new IllegalArgumentException("invalid private process marker");
    }
    StringBuilder script = new StringBuilder();
    request
        .workingDirectory()
        .ifPresent(
            directory ->
                script
                    .append("cd -- ")
                    .append(PosixShell.quote(requireRemotePath(directory, "working directory")))
                    .append(" && "));
    if (request.forceTty()) {
      // sshd already created a private session and foreground group for
      // this PTY. Keep it, so byte 0x03 remains the terminal's SIGINT.
      script
          .append("bbv_pgid=$(/usr/bin/ps -o pgid= -p \"$$\" " + "| /usr/bin/tr -d '[:space:]'); ")
          .append("case \"$bbv_pgid\" in ''|*[!0-9]*) exit 70;; esac; ")
          .append("printf '")
          .append(marker)
          .append("%s:%s\\n' \"$$\" \"$bbv_pgid\"; exec ")
          .append(environmentCommand(request));
    } else {
      String inner =
          "printf '" + marker + "%s:%s\\n' \"$$\" \"$$\"; exec " + environmentCommand(request);
      // Non-TTY helpers have no foreground group, so setsid gives each
      // command a private group that later cancellation can address.
      // setsid may fork when its caller is already a process-group leader.
      // Without --wait that parent reports success immediately, hiding a
      // helper's real exit status (for example a missing-file stat).
      script.append("exec /usr/bin/setsid --wait /bin/sh -c ").append(PosixShell.quote(inner));
    }
    return script.toString();
  }

  private RemoteRunningCommand startRemote(CommandRequest request) throws IOException {
    requireSessionOpen();
    Objects.requireNonNull(request, "request");
    long startedNanos = System.nanoTime();
    log.trace(
        "SSH command transport starting target={} tty={} argumentCount={}"
            + " workingDirectorySet={} environment={} environmentOverrideCount={}",
        target.displayName(),
        request.forceTty(),
        request.argv().size(),
        request.workingDirectory().isPresent(),
        request.environment(),
        request.environmentOverrides().size());
    String marker = "BBV_PROCESS_" + UUID.randomUUID().toString().replace("-", "") + ":";
    String remote = remoteCommand(request, marker);
    ManagedProcess process =
        Subprocess.startManaged(
            SshControlSession.commandArguments(
                ssh, controlSocket, target, request.forceTty(), remote),
            null,
            Map.of(),
            EnvironmentInheritance.INHERIT,
            request.forceTty());
    try {
      Marker parsed = readMarker(process.stdout(), marker, Duration.ofSeconds(15));
      awaitManagedReady(process);
      InputStream replay =
          new SequenceInputStream(new ByteArrayInputStream(parsed.prefix()), process.stdout());
      InputStream error = request.forceTty() ? InputStream.nullInputStream() : process.stderr();
      log.trace(
          "SSH command transport ready target={} remoteProcessId={}"
              + " processGroupId={} tty={} durationMs={}",
          target.displayName(),
          parsed.pid(),
          parsed.pgid(),
          request.forceTty(),
          elapsedMillis(startedNanos));
      return new RemoteRunningCommand(
          this, process, replay, error, request.forceTty(), parsed.pid(), parsed.pgid());
    } catch (IOException | RuntimeException failure) {
      log.debug(
          "SSH command transport failed target={} tty={} argumentCount={}"
              + " durationMs={} failureType={}",
          target.displayName(),
          request.forceTty(),
          request.argv().size(),
          elapsedMillis(startedNanos),
          failureType(failure));
      String detail = startupDiagnostic(process, request.forceTty());
      cleanupManagedProcess(process, failure);
      if (detail.isEmpty()) {
        throw failure;
      }
      throw new IOException(failure.getMessage() + ": " + detail, failure);
    }
  }

  private static String environmentCommand(CommandRequest request) {
    StringBuilder command = new StringBuilder("/usr/bin/env");
    if (request.environment() == RuntimeEnvironment.NONE
        || request.environment() == RuntimeEnvironment.INHERIT_ESSENTIAL) {
      command.append(" -i");
    }
    request.environmentOverrides().entrySet().stream()
        .filter(entry -> entry.getValue().isEmpty())
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> command.append(" -u ").append(entry.getKey()));
    // GNU env stops parsing options at the first NAME=VALUE operand. The
    // terminator therefore has to precede every assignment; placing it
    // afterwards makes env try to execute a command literally named "--".
    command.append(" --");
    if (request.environment() == RuntimeEnvironment.INHERIT_ESSENTIAL) {
      for (String name : ESSENTIAL_ENVIRONMENT) {
        if (request.environmentOverrides().containsKey(name)
            && request.environmentOverrides().get(name).isEmpty()) {
          continue;
        }
        command.append(' ').append(name).append("=\"${").append(name).append("-}\"");
      }
    }
    request.environmentOverrides().entrySet().stream()
        .filter(entry -> entry.getValue().isPresent())
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                command
                    .append(' ')
                    .append(
                        PosixShell.quote(entry.getKey() + "=" + entry.getValue().orElseThrow())));
    command.append(' ').append(PosixShell.argv(request.argv()));
    return command.toString();
  }

  private void stopTimedOut(RemoteRunningCommand command) throws IOException, InterruptedException {
    stopForcibly(command);
  }

  private static void stopIncomplete(RemoteRunningCommand command)
      throws IOException, InterruptedException {
    stopForcibly(command);
  }

  private static void stopForcibly(RemoteRunningCommand command)
      throws IOException, InterruptedException {
    Throwable signalFailure = null;
    Throwable waitFailure = null;
    Throwable localFailure = null;
    boolean restoreInterrupt = Thread.interrupted();
    if (command.localProcess.isAlive()) {
      try {
        command.owner.signal(command.remotePid, command.remotePgid, CancellationMode.FORCE_KILL);
      } catch (IOException | InterruptedException | RuntimeException failure) {
        signalFailure = failure;
        restoreInterrupt |= failure instanceof InterruptedException;
        Thread.interrupted();
      }
    }
    try {
      if (command.localProcess.isAlive()) {
        command.localProcess.awaitExit(Duration.ofSeconds(5));
      }
    } catch (IOException | InterruptedException | RuntimeException failure) {
      waitFailure = failure;
      restoreInterrupt |= failure instanceof InterruptedException;
      Thread.interrupted();
    }
    try {
      command.localProcess.terminate();
    } catch (IOException | InterruptedException | RuntimeException failure) {
      localFailure = failure;
      restoreInterrupt |= failure instanceof InterruptedException;
      Thread.interrupted();
    }
    Throwable primary =
        signalFailure != null ? signalFailure : (waitFailure != null ? waitFailure : localFailure);
    if (primary != null) {
      addSuppressed(primary, signalFailure, waitFailure, localFailure);
      if (restoreInterrupt) {
        Thread.currentThread().interrupt();
      }
      if (primary instanceof InterruptedException interrupted) {
        throw interrupted;
      }
      if (primary instanceof IOException io) {
        throw io;
      }
      throw new IOException("could not clean up the SSH command", primary);
    }
    if (restoreInterrupt) {
      Thread.currentThread().interrupt();
    }
  }

  private static void cleanupFailedCommandDrains(
      RemoteRunningCommand command,
      Throwable failure,
      Subprocess.BoundedDrain stdout,
      Subprocess.BoundedDrain stderr) {
    boolean restoreInterrupt =
        failure instanceof InterruptedException || Thread.currentThread().isInterrupted();
    Thread.interrupted();
    try {
      stopIncomplete(command);
    } catch (IOException | InterruptedException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
      restoreInterrupt |= cleanupFailure instanceof InterruptedException;
      Thread.interrupted();
    }
    if (stdout != null) {
      stdout.close();
    }
    if (stderr != null) {
      stderr.close();
    }
    if (stdout != null) {
      restoreInterrupt |= awaitDrainCleanup(stdout, failure);
    }
    if (stderr != null) {
      restoreInterrupt |= awaitDrainCleanup(stderr, failure);
    }
    try {
      command.close();
    } catch (IOException | RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
    if (restoreInterrupt) {
      Thread.currentThread().interrupt();
    }
  }

  private static void cleanupFailedCommandCopy(
      RemoteRunningCommand command,
      Throwable failure,
      Subprocess.BoundedDrain stderr,
      Subprocess.FileDrain copy) {
    boolean restoreInterrupt =
        failure instanceof InterruptedException || Thread.currentThread().isInterrupted();
    Thread.interrupted();
    cleanupFailedCommandDrains(command, failure, stderr, null);
    restoreInterrupt |= Thread.interrupted();
    if (copy != null) {
      copy.close();
      try {
        copy.awaitCleanup();
      } catch (IOException | InterruptedException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
        restoreInterrupt |= cleanupFailure instanceof InterruptedException;
        Thread.interrupted();
      }
    }
    if (restoreInterrupt) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean awaitDrainCleanup(Subprocess.BoundedDrain drain, Throwable failure) {
    try {
      drain.awaitCleanup();
      return false;
    } catch (IOException | InterruptedException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
      Thread.interrupted();
      return cleanupFailure instanceof InterruptedException;
    }
  }

  private static void addSuppressed(Throwable primary, Throwable... failures) {
    for (Throwable failure : failures) {
      if (failure != null && failure != primary) {
        primary.addSuppressed(failure);
      }
    }
  }

  private static void cleanupManagedProcess(ManagedProcess process, Throwable failure) {
    boolean restoreInterrupt =
        failure instanceof InterruptedException || Thread.currentThread().isInterrupted();
    Thread.interrupted();
    try {
      process.terminate();
    } catch (IOException | InterruptedException | RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
      restoreInterrupt |= cleanupFailure instanceof InterruptedException;
      Thread.interrupted();
    }
    try {
      process.close();
    } catch (IOException | RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
    if (restoreInterrupt) {
      Thread.currentThread().interrupt();
    }
  }

  private static void closeStdinForNonInteractive(
      CommandRequest request, RemoteRunningCommand command) {
    if (request.forceTty()) {
      return;
    }
    try {
      command.stdin().close();
    } catch (IOException ignored) {
      // A short command may have exited before its marker was consumed.
    }
  }

  private void signal(long pid, long pgid, CancellationMode mode)
      throws IOException, InterruptedException {
    requireSessionOpen();
    long startedNanos = System.nanoTime();
    log.debug(
        "SSH remote signal started target={} mode={} remoteProcessId={}" + " processGroupKnown={}",
        target.displayName(),
        mode,
        pid,
        pgid > 0);
    String signal =
        switch (mode) {
          case CANCEL -> "INT";
          case TERMINATE -> "TERM";
          case FORCE_KILL -> "KILL";
        };
    long targetId = pgid > 0 ? -pgid : pid;
    String command =
        PosixShell.argv(List.of("/bin/kill", "-" + signal, "--", Long.toString(targetId)));
    OpenSshProcess.Result result =
        OpenSshProcess.run(
            SshControlSession.commandArguments(ssh, controlSocket, target, false, command),
            Duration.ofSeconds(10));
    if (!result.isSuccess()) {
      throw new IOException(
          "could not signal remote process " + pid + ": " + result.failureDetail());
    }
    log.debug(
        "SSH remote signal completed target={} mode={} remoteProcessId={}" + " durationMs={}",
        target.displayName(),
        mode,
        pid,
        elapsedMillis(startedNanos));
  }

  private void logCommandStart(String kind, CommandRequest request, Duration timeout) {
    log.debug(
        "SSH command started kind={} target={} tty={} argumentCount={}"
            + " workingDirectorySet={} environment={} environmentOverrideCount={}"
            + " timeoutMs={}",
        kind,
        target.displayName(),
        request.forceTty(),
        request.argv().size(),
        request.workingDirectory().isPresent(),
        request.environment(),
        request.environmentOverrides().size(),
        timeout == null ? "none" : Long.toString(timeout.toMillis()));
  }

  private void logCommandResult(
      String kind,
      CommandRequest request,
      CommandResult result,
      long startedNanos,
      String stdoutBytes,
      String stderrBytes) {
    log.debug(
        "SSH command completed kind={} target={} tty={} argumentCount={}"
            + " exitCode={} timedOut={} durationMs={} stdoutBytes={} stderrBytes={}",
        kind,
        target.displayName(),
        request.forceTty(),
        request.argv().size(),
        result.exitCode(),
        result.timedOut(),
        elapsedMillis(startedNanos),
        stdoutBytes,
        stderrBytes);
  }

  private static String fileSize(Path path) {
    try {
      return Long.toString(Files.size(path));
    } catch (IOException unavailable) {
      return "unavailable";
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static String failureType(Throwable failure) {
    return failure.getClass().getSimpleName();
  }

  private static void awaitManagedReady(ManagedProcess process) throws IOException {
    try {
      process.awaitIsolationReady(Duration.ofSeconds(5));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while isolating the local OpenSSH process", interrupted);
    }
  }

  private static Marker readMarker(InputStream input, String marker, Duration timeout)
      throws IOException {
    FutureTask<Marker> task = new FutureTask<>(() -> readMarkerBytes(input, marker));
    Thread thread = Thread.ofVirtual().name("bbv-ssh-process-marker").start(task);
    try {
      return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeoutFailure) {
      thread.interrupt();
      throw new IOException("the remote process did not report its pid in time", timeoutFailure);
    } catch (InterruptedException interrupted) {
      thread.interrupt();
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while starting the remote process", interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException("could not read the remote process marker", cause);
    }
  }

  static Marker readMarkerBytes(InputStream input, String marker) throws IOException {
    byte[] markerBytes = marker.getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream pending = new ByteArrayOutputStream();
    int matched = 0;
    boolean inMarker = false;
    ByteArrayOutputStream markerLine = new ByteArrayOutputStream();
    while (pending.size() + markerLine.size() < 128 * 1024) {
      int next = input.read();
      if (next < 0) {
        String detail = pending.toString(StandardCharsets.UTF_8).strip();
        throw new IOException(
            "SSH ended before the remote process started"
                + (detail.isEmpty() ? "" : ": " + detail));
      }
      if (!inMarker) {
        pending.write(next);
        if ((byte) next == markerBytes[matched]) {
          matched++;
          if (matched == markerBytes.length) {
            byte[] all = pending.toByteArray();
            pending.reset();
            pending.write(all, 0, all.length - markerBytes.length);
            inMarker = true;
          }
        } else {
          matched = (byte) next == markerBytes[0] ? 1 : 0;
        }
      } else if (next == '\n') {
        String values = markerLine.toString(StandardCharsets.US_ASCII).strip();
        String[] parts = values.split(":", -1);
        if (parts.length != 2
            || !parts[0].matches("[1-9][0-9]*")
            || !parts[1].matches("[1-9][0-9]*")) {
          throw new IOException("the remote process reported an invalid pid marker");
        }
        return new Marker(
            Long.parseLong(parts[0]), Long.parseLong(parts[1]), pending.toByteArray());
      } else {
        markerLine.write(next);
      }
    }
    throw new IOException("SSH produced too much output before the remote process started");
  }

  private static String startupDiagnostic(ManagedProcess process, boolean merged) {
    if (merged || process.isAlive()) {
      return "";
    }
    try {
      int available = Math.min(process.stderr().available(), 8 * 1024);
      if (available == 0) {
        return "";
      }
      return new String(process.stderr().readNBytes(available), StandardCharsets.UTF_8).strip();
    } catch (IOException ignored) {
      return "";
    }
  }

  record Marker(long pid, long pgid, byte[] prefix) {
    Marker {
      prefix = prefix.clone();
    }

    @Override
    public byte[] prefix() {
      return prefix.clone();
    }
  }

  private static String requireRemotePath(String path, String description) {
    Objects.requireNonNull(path, description);
    if (path.isBlank() || path.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(description + " must not be blank or contain NUL");
    }
    return path;
  }

  private void requireSessionOpen() throws IOException {
    if (!sessionOpen.getAsBoolean()) {
      throw new IOException("the SSH control session is closed");
    }
  }

  private static void requireTimeout(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("command timeout must be positive");
    }
  }

  private static void moveReplacement(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      throw new IOException("atomic SSH output replacement is not supported", unsupported);
    }
  }

  private static final class RemoteRunningCommand implements RunningCommand, AutoCloseable {

    private final SshCommandExecutor owner;
    private final ManagedProcess localProcess;
    private final InputStream stdout;
    private final InputStream stderr;
    private final boolean merged;
    private final long remotePid;
    private final long remotePgid;

    private RemoteRunningCommand(
        SshCommandExecutor owner,
        ManagedProcess localProcess,
        InputStream stdout,
        InputStream stderr,
        boolean merged,
        long remotePid,
        long remotePgid) {
      this.owner = owner;
      this.localProcess = localProcess;
      this.stdout = stdout;
      this.stderr = stderr;
      this.merged = merged;
      this.remotePid = remotePid;
      this.remotePgid = remotePgid;
    }

    @Override
    public InputStream stdout() {
      return stdout;
    }

    @Override
    public InputStream stderr() {
      return stderr;
    }

    @Override
    public OutputStream stdin() {
      return localProcess.stdin();
    }

    @Override
    public boolean streamsMerged() {
      return merged;
    }

    @Override
    public long pid() {
      return remotePid;
    }

    @Override
    public OptionalLong processGroupId() {
      return OptionalLong.of(remotePgid);
    }

    @Override
    public boolean isAlive() {
      return localProcess.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
      try {
        return localProcess.waitForRoot();
      } finally {
        closeQuietly();
      }
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
      boolean exited = localProcess.waitForRoot(timeout);
      if (exited) {
        closeQuietly();
      }
      return exited;
    }

    @Override
    public int exitValue() {
      return localProcess.exitValue();
    }

    @Override
    public void signal(CancellationMode mode) throws IOException, InterruptedException {
      if (!localProcess.isAlive()) {
        return;
      }
      if (merged && mode == CancellationMode.CANCEL) {
        // Ctrl-C reaches the foreground group of the forced remote TTY,
        // which is Bazel's graceful interruption path.
        localProcess.stdin().write(3);
        localProcess.stdin().flush();
      } else {
        owner.signal(remotePid, remotePgid, Objects.requireNonNull(mode, "mode"));
      }
    }

    boolean awaitLocalExit(Duration timeout) throws IOException, InterruptedException {
      return localProcess.awaitExit(timeout);
    }

    @Override
    public void close() throws IOException {
      localProcess.close();
    }

    private void closeQuietly() {
      try {
        close();
      } catch (IOException ignored) {
        // The command has exited; failure to remove its private control file is non-fatal here.
      }
    }
  }

  private static final class TerminalChannel implements InteractiveChannel {

    private final PtyProcess process;
    private final InputStream input;
    private final String target;
    private final long startedNanos = System.nanoTime();

    private TerminalChannel(PtyProcess process, String target) {
      this.process = process;
      this.target = target;
      input = process.getInputStream();
    }

    @Override
    public InputStream input() {
      return input;
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, data.length);
      if (!process.isAlive()) {
        throw new IOException("the SSH terminal is closed");
      }
      process.getOutputStream().write(data, offset, length);
      process.getOutputStream().flush();
    }

    @Override
    public synchronized void resize(TerminalSize size) throws IOException {
      Objects.requireNonNull(size, "size");
      if (!process.isAlive()) {
        throw new IOException("the SSH terminal is closed");
      }
      try {
        process.setWinSize(new WinSize(size.columns(), size.rows()));
      } catch (RuntimeException failure) {
        throw new IOException("the SSH terminal could not be resized", failure);
      }
    }

    @Override
    public boolean isOpen() {
      return process.isAlive();
    }

    @Override
    public int awaitExit() throws InterruptedException {
      return process.waitFor();
    }

    @Override
    public synchronized void close() {
      try {
        process.getOutputStream().close();
      } catch (IOException ignored) {
        // The remote shell may already have exited.
      }
      try {
        input.close();
      } catch (IOException ignored) {
        // The remote shell may already have exited.
      }
      if (!process.isAlive()) {
        log.info(
            "SSH terminal closed target={} exitCode={} durationMs={}",
            target,
            process.exitValue(),
            elapsedMillis(startedNanos));
        return;
      }

      boolean interrupted = false;
      process.destroy();
      try {
        if (!process.waitFor(TERMINAL_CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(TERMINAL_CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException stopInterrupted) {
        interrupted = true;
        if (process.isAlive()) {
          process.destroyForcibly();
          try {
            process.waitFor(TERMINAL_CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
          } catch (InterruptedException forceInterrupted) {
            interrupted = true;
          }
        }
      } finally {
        String exit = process.isAlive() ? "unavailable" : Integer.toString(process.exitValue());
        log.info(
            "SSH terminal closed target={} exitCode={} durationMs={}",
            target,
            exit,
            elapsedMillis(startedNanos));
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
