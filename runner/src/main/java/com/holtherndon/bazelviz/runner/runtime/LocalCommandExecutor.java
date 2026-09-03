package com.holtherndon.bazelviz.runner.runtime;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
    requireTimeout(timeout);
    Process process = builder(request).start();
    closeQuietly(process.getOutputStream());
    Drain stdout = Drain.start(process.getInputStream(), "bbv-command-stdout");
    Drain stderr = Drain.start(process.getErrorStream(), "bbv-command-stderr");
    boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      closeQuietly(process.getInputStream());
      closeQuietly(process.getErrorStream());
      return result(-1, stdout, stderr, true);
    }
    return result(process.exitValue(), stdout, stderr, false);
  }

  @Override
  public CommandResult runRedirectingStdout(
      CommandRequest request, Duration timeout, Path localOutputFile)
      throws IOException, InterruptedException {
    requireTimeout(timeout);
    ProcessBuilder builder =
        builder(request).redirectOutput(ProcessBuilder.Redirect.to(localOutputFile.toFile()));
    Process process = builder.start();
    closeQuietly(process.getOutputStream());
    Drain stderr = Drain.start(process.getErrorStream(), "bbv-command-stderr");
    boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
      closeQuietly(process.getErrorStream());
      String error = stderr.awaitText();
      if (stderr.overflowed()) {
        throw new IOException("the command produced too much diagnostic output");
      }
      return new CommandResult(-1, "", error, true);
    }
    String error = stderr.awaitText();
    if (stderr.overflowed()) {
      throw new IOException("the command produced too much diagnostic output");
    }
    return new CommandResult(process.exitValue(), "", error, false);
  }

  @Override
  public RunningCommand start(CommandRequest request) throws IOException {
    if (request.forceTty()) {
      throw new IOException("the local command executor does not allocate a pseudo-terminal");
    }
    return new LocalRunningCommand(builder(request).start());
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

  private static ProcessBuilder builder(CommandRequest request) throws IOException {
    if (request.forceTty()) {
      throw new IOException("the local command executor does not allocate a pseudo-terminal");
    }
    ProcessBuilder builder = new ProcessBuilder(request.argv());
    if (request.workingDirectory().isPresent()) {
      Path directory = Path.of(request.workingDirectory().get());
      if (!Files.isDirectory(directory)) {
        throw new IOException("the working directory does not exist: " + directory);
      }
      builder.directory(directory.toFile());
    }
    applyEnvironment(builder.environment(), request);
    return builder;
  }

  private static CommandResult result(int exitCode, Drain stdout, Drain stderr, boolean timedOut)
      throws IOException, InterruptedException {
    String out = stdout.awaitText();
    String err = stderr.awaitText();
    if (stdout.overflowed() || stderr.overflowed()) {
      throw new IOException("the command produced too much probe output");
    }
    return new CommandResult(exitCode, out, err, timedOut);
  }

  private static void requireTimeout(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("command timeout must be positive");
    }
  }

  private static void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The process already closed its side.
    }
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The process already closed its side.
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

    private final Process process;

    private LocalRunningCommand(Process process) {
      this.process = process;
    }

    @Override
    public InputStream stdout() {
      return process.getInputStream();
    }

    @Override
    public InputStream stderr() {
      return process.getErrorStream();
    }

    @Override
    public OutputStream stdin() {
      return process.getOutputStream();
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
      return process.waitFor();
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
      return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int exitValue() {
      return process.exitValue();
    }

    @Override
    public void signal(CancellationMode mode) throws IOException, InterruptedException {
      switch (mode) {
        case CANCEL -> interrupt(process);
        case TERMINATE -> {
          if (process.isAlive()) {
            process.destroy();
          }
        }
        case FORCE_KILL -> {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
        }
      }
    }

    private static void interrupt(Process process) throws IOException, InterruptedException {
      if (!process.isAlive()) {
        return;
      }
      Process kill =
          new ProcessBuilder("/bin/kill", "-INT", Long.toString(process.pid()))
              .redirectErrorStream(true)
              .start();
      if (!kill.waitFor(5, TimeUnit.SECONDS) || kill.exitValue() != 0) {
        process.destroy();
      }
    }
  }

  private static final class Drain {

    private final Thread thread;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile boolean overflowed;

    private Drain(InputStream stream, String name) {
      thread =
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
                        int remaining = 16 * 1024 * 1024 - bytes.size();
                        if (remaining > 0) {
                          bytes.write(buffer, 0, Math.min(read, remaining));
                        }
                        if (read > remaining) {
                          overflowed = true;
                        }
                      }
                    } catch (IOException closed) {
                      // The process ended while the pipe was being drained.
                    }
                  });
    }

    static Drain start(InputStream stream, String name) {
      Drain drain = new Drain(stream, name);
      drain.thread.start();
      return drain;
    }

    String awaitText() throws InterruptedException {
      thread.join(TimeUnit.SECONDS.toMillis(5));
      return bytes.toString(StandardCharsets.UTF_8);
    }

    boolean overflowed() {
      return overflowed;
    }
  }
}
