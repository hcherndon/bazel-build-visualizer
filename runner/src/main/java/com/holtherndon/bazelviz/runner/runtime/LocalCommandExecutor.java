package com.holtherndon.bazelviz.runner.runtime;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.Subprocess;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The local {@link ProcessBuilder} implementation of {@link CommandExecutor}. */
public final class LocalCommandExecutor implements CommandExecutor {

  private static final Logger log = LoggerFactory.getLogger(LocalCommandExecutor.class);

  public static final LocalCommandExecutor INSTANCE = new LocalCommandExecutor();

  private static final Set<String> ESSENTIAL_ENVIRONMENT =
      Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "SHELL", "LANG", "LC_ALL");

  private LocalCommandExecutor() {}

  @Override
  public CommandResult run(CommandRequest request, Duration timeout)
      throws IOException, InterruptedException {
    requireNonTty(request);
    Subprocess.Result result =
        Subprocess.runWithExactEnvironment(
            request.argv(),
            request.workingDirectory().map(Path::of).orElse(null),
            environment(request),
            timeout);
    return commandResult(result);
  }

  @Override
  public CommandResult runRedirectingStdout(
      CommandRequest request, Duration timeout, Path localOutputFile)
      throws IOException, InterruptedException {
    requireTimeout(timeout);
    requireNonTty(request);
    Path destination = localOutputFile.toAbsolutePath().normalize();
    Path parent = destination.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new IOException("the output directory does not exist: " + parent);
    }
    Path temporary = Files.createTempFile(parent, ".bbv-command-output-", ".tmp");
    boolean moved = false;
    try {
      Subprocess.Result result =
          Subprocess.runRedirectingStdoutWithExactEnvironment(
              request.argv(),
              request.workingDirectory().map(Path::of).orElse(null),
              environment(request),
              timeout,
              temporary);
      if (result.isSuccess()) {
        moveReplacement(temporary, destination);
        moved = true;
      }
      return commandResult(result);
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  @Override
  public RunningCommand start(CommandRequest request) throws IOException {
    requireNonTty(request);
    Subprocess.ManagedProcess process =
        Subprocess.startManaged(
            request.argv(),
            request.workingDirectory().map(Path::of).orElse(null),
            environment(request),
            Subprocess.EnvironmentInheritance.REPLACE,
            false);
    try {
      process.awaitIsolationReady(Duration.ofSeconds(5));
      return new LocalRunningCommand(process);
    } catch (IOException | InterruptedException | RuntimeException failure) {
      cleanupManagedStart(process, failure);
      if (failure instanceof InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted while starting the local command", interrupted);
      }
      if (failure instanceof IOException io) {
        throw io;
      }
      throw (RuntimeException) failure;
    }
  }

  @Override
  public InteractiveChannel openTerminal(String workingDirectory) throws IOException {
    String shell = System.getenv("SHELL");
    if (shell == null || shell.isBlank()) {
      shell = "/bin/sh";
    }
    return openTerminal(workingDirectory, shell, System.getenv());
  }

  /** Package seam for proving PTY behavior with a deterministic login shell. */
  InteractiveChannel openTerminal(
      String workingDirectory, String shell, Map<String, String> inheritedEnvironment)
      throws IOException {
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    Objects.requireNonNull(shell, "shell");
    Objects.requireNonNull(inheritedEnvironment, "inheritedEnvironment");
    if (workingDirectory.isBlank() || workingDirectory.indexOf('\0') >= 0) {
      throw new IOException("the terminal working directory is blank or invalid");
    }
    if (shell.isBlank() || shell.indexOf('\0') >= 0) {
      throw new IOException("the terminal login shell is blank or invalid");
    }

    Path directory;
    try {
      directory = Path.of(workingDirectory).toAbsolutePath().normalize();
    } catch (InvalidPathException invalid) {
      throw new IOException("the terminal working directory is invalid", invalid);
    }
    if (!Files.isDirectory(directory)) {
      throw new IOException("the working directory does not exist: " + directory);
    }

    Map<String, String> environment = new HashMap<>(inheritedEnvironment);
    environment.put("TERM", "xterm-256color");
    long startedNanos = System.nanoTime();
    log.info("local terminal opening workingDirectorySet=true loginShellConfigured=true");
    try {
      PtyProcess process =
          new PtyProcessBuilder(new String[] {shell, "-l"})
              .setEnvironment(environment)
              .setDirectory(directory.toString())
              .setConsole(false)
              .setRedirectErrorStream(true)
              .setInitialColumns(80)
              .setInitialRows(24)
              .start();
      log.info(
          "local terminal opened processId={} durationMs={}",
          process.pid(),
          elapsedMillis(startedNanos));
      return new LocalTerminalChannel(process);
    } catch (IOException | RuntimeException failure) {
      log.warn(
          "local terminal open failed durationMs={} failureType={}",
          elapsedMillis(startedNanos),
          failure.getClass().getSimpleName());
      throw failure;
    }
  }

  private static void moveReplacement(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      throw new IOException("atomic command-output replacement is not supported", unsupported);
    }
  }

  private static void requireNonTty(CommandRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    if (request.forceTty()) {
      throw new IOException("the local command executor does not allocate a pseudo-terminal");
    }
  }

  private static void requireTimeout(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("command timeout must be positive");
    }
  }

  private static void cleanupManagedStart(
      Subprocess.ManagedProcess process, Throwable originalFailure) {
    boolean restoreInterrupt = Thread.interrupted();
    try {
      process.terminate();
    } catch (IOException | InterruptedException | RuntimeException cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
      restoreInterrupt |= cleanupFailure instanceof InterruptedException;
      Thread.interrupted();
    }
    try {
      process.close();
    } catch (IOException | RuntimeException cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
    }
    if (restoreInterrupt) {
      Thread.currentThread().interrupt();
    }
  }

  private static CommandResult commandResult(Subprocess.Result result) throws IOException {
    if (result.outputTruncated()) {
      throw new IOException(result.failureDetail());
    }
    return new CommandResult(
        result.exitCode(), result.stdout(), result.stderr(), result.timedOut());
  }

  private static Map<String, String> environment(CommandRequest request) {
    Map<String, String> environment = new HashMap<>(System.getenv());
    applyEnvironment(environment, request);
    return environment;
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The process already closed its side.
    }
  }

  private static void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // Cleanup is best effort after the terminal closes.
    }
  }

  private static void applyEnvironment(Map<String, String> environment, CommandRequest request) {
    switch (request.environment()) {
      case INHERIT_ALL -> {
        // ProcessBuilder already copied this process's environment.
      }
      case INHERIT_ESSENTIAL -> {
        Map<String, String> retained = new LinkedHashMap<>();
        for (String name : ESSENTIAL_ENVIRONMENT) {
          String value = environment.get(name);
          if (value != null) {
            retained.put(name, value);
          }
        }
        environment.clear();
        environment.putAll(retained);
      }
      case NONE -> environment.clear();
    }
    request
        .environmentOverrides()
        .forEach(
            (name, value) ->
                value.ifPresentOrElse(
                    present -> environment.put(name, present), () -> environment.remove(name)));
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  /** A local login shell over a real, resizable Pty4J pseudo-terminal. */
  private static final class LocalTerminalChannel implements InteractiveChannel {

    private final PtyProcess process;
    private final InputStream input;
    private final OutputStream output;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long startedNanos = System.nanoTime();

    private LocalTerminalChannel(PtyProcess process) {
      this.process = Objects.requireNonNull(process, "process");
      input = process.getInputStream();
      output = process.getOutputStream();
    }

    @Override
    public InputStream input() {
      return input;
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, data.length);
      if (closed.get() || !process.isAlive()) {
        throw new IOException("the local terminal is closed");
      }
      output.write(data, offset, length);
      output.flush();
    }

    @Override
    public synchronized void resize(TerminalSize size) throws IOException {
      Objects.requireNonNull(size, "size");
      if (closed.get() || !process.isAlive()) {
        throw new IOException("the local terminal is closed");
      }
      try {
        process.setWinSize(new WinSize(size.columns(), size.rows()));
      } catch (RuntimeException failure) {
        throw new IOException("the local terminal could not be resized", failure);
      }
    }

    @Override
    public boolean isOpen() {
      return !closed.get() && process.isAlive();
    }

    @Override
    public int awaitExit() throws InterruptedException {
      return process.waitFor();
    }

    @Override
    public synchronized void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      closeQuietly(output);
      closeQuietly(input);

      boolean interrupted = false;
      if (process.isAlive()) {
        process.destroy();
        try {
          // Keep terminal teardown bounded without adding another public limit.
          if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
          }
        } catch (InterruptedException stopInterrupted) {
          interrupted = true;
          if (process.isAlive()) {
            process.destroyForcibly();
          }
          try {
            process.waitFor(1, TimeUnit.SECONDS);
          } catch (InterruptedException forceInterrupted) {
            interrupted = true;
          }
        }
      }
      String exit = process.isAlive() ? "unavailable" : Integer.toString(process.exitValue());
      log.info(
          "local terminal closed processId={} exitCode={} durationMs={}",
          process.pid(),
          exit,
          elapsedMillis(startedNanos));
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class LocalRunningCommand implements RunningCommand {

    private final Subprocess.ManagedProcess process;

    private LocalRunningCommand(Subprocess.ManagedProcess process) {
      this.process = process;
    }

    @Override
    public InputStream stdout() {
      return process.stdout();
    }

    @Override
    public InputStream stderr() {
      return process.stderr();
    }

    @Override
    public OutputStream stdin() {
      return process.stdin();
    }

    @Override
    public boolean streamsMerged() {
      return false;
    }

    @Override
    public long pid() {
      return process.pid();
    }

    @Override
    public OptionalLong processGroupId() {
      return OptionalLong.empty();
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
      try {
        return process.waitForRoot();
      } finally {
        closeQuietly();
      }
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
      boolean exited = process.waitForRoot(timeout);
      if (exited) {
        closeQuietly();
      }
      return exited;
    }

    @Override
    public int exitValue() {
      return process.exitValue();
    }

    @Override
    public void signal(CancellationMode mode) throws IOException, InterruptedException {
      process.signal(mode);
    }

    private void closeQuietly() {
      try {
        process.close();
      } catch (IOException ignored) {
        // The process has exited; this only removes its private control file.
      }
    }
  }
}
