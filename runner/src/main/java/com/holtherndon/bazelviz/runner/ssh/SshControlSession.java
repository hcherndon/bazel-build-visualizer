package com.holtherndon.bazelviz.runner.ssh;

import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.Subprocess;
import com.holtherndon.bazelviz.runner.proc.Subprocess.EnvironmentInheritance;
import com.holtherndon.bazelviz.runner.proc.Subprocess.ManagedProcess;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One private OpenSSH control master shared by commands, forwards, and SFTP. */
public final class SshControlSession implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SshControlSession.class);

  private final SshTarget target;
  private final OpenSshBinaries binaries;
  private final Path controlDirectory;
  private final Path controlSocket;
  private final ManagedProcess master;
  private final Subprocess.BoundedDrain masterOutput;
  private final Subprocess.BoundedDrain masterErrors;
  private final SshCommandExecutor commandExecutor;
  private final ExecutionFileSystem fileSystem;
  private final List<SshReverseForward> forwards = new CopyOnWriteArrayList<>();
  private final AtomicBoolean closed;

  /** Connects using the system OpenSSH client and the user's normal host-key policy. */
  public static SshControlSession connect(SshTarget target, Duration timeout)
      throws IOException, InterruptedException {
    return connect(target, timeout, OpenSshBinaries.SYSTEM);
  }

  static SshControlSession connect(SshTarget target, Duration timeout, OpenSshBinaries binaries)
      throws IOException, InterruptedException {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(binaries, "binaries");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("SSH connection timeout must be positive");
    }
    requireExecutable(binaries.ssh(), "ssh");
    requireExecutable(binaries.sftp(), "sftp");

    long startedNanos = System.nanoTime();
    log.info(
        "SSH connection started target={} timeoutMs={}", target.displayName(), timeout.toMillis());
    Path directory = Files.createTempDirectory("bbv-ssh-");
    makePrivate(directory);
    Path socket = directory.resolve("c");
    List<String> argv = masterArguments(binaries.ssh(), socket, target);
    ManagedProcess process = null;
    try {
      process =
          Subprocess.startManaged(argv, null, Map.of(), EnvironmentInheritance.INHERIT, false);
      process.stdin().close();
      process.awaitIsolationReady(Duration.ofSeconds(5));
    } catch (IOException | InterruptedException | RuntimeException failure) {
      if (process != null) {
        MasterCleanup cleanup = cleanupMaster(process, null, null);
        if (cleanup.failure() != null) {
          failure.addSuppressed(cleanup.failure());
        }
        if (cleanup.terminated()) {
          deletePrivateDirectory(directory, socket);
        }
      } else {
        deletePrivateDirectory(directory, socket);
      }
      log.warn(
          "SSH connection failed target={} durationMs={} failureType={}",
          target.displayName(),
          elapsedMillis(startedNanos),
          failureType(failure));
      throw failure;
    }
    log.debug(
        "SSH control master started target={} processId={}", target.displayName(), process.pid());
    Subprocess.BoundedDrain output = null;
    Subprocess.BoundedDrain errors = null;
    boolean handedOff = false;
    Throwable connectionFailure = null;
    try {
      output = Subprocess.BoundedDrain.start(process.stdout(), "bbv-ssh-master-stdout", 256 * 1024);
      errors = Subprocess.BoundedDrain.start(process.stderr(), "bbv-ssh-master-stderr", 256 * 1024);
      long deadline = System.nanoTime() + timeout.toNanos();
      boolean ready = false;
      while (System.nanoTime() < deadline) {
        if (!process.isAlive()) {
          Subprocess.CapturedOutput standardOutput = output.await();
          Subprocess.CapturedOutput diagnostics = errors.await();
          String detail = diagnosticText(standardOutput, diagnostics);
          if (standardOutput.incomplete() || diagnostics.incomplete()) {
            throw new IOException("SSH connection diagnostics could not be drained to completion");
          }
          if (standardOutput.truncated() || diagnostics.truncated()) {
            throw new IOException("SSH connection diagnostics exceeded the retained output limit");
          }
          throw new IOException("SSH connection failed" + (detail.isEmpty() ? "" : ": " + detail));
        }
        OpenSshProcess.Result check =
            OpenSshProcess.run(
                controlArguments(binaries.ssh(), socket, target, "check", List.of()),
                Duration.ofNanos(
                    Math.max(
                        1,
                        Math.min(Duration.ofSeconds(2).toNanos(), deadline - System.nanoTime()))));
        if (check.isSuccess()) {
          ready = true;
          log.debug("SSH control master ready target={}", target.displayName());
          break;
        }
        Thread.sleep(40);
      }
      if (!ready) {
        throw new IOException("SSH connection did not become ready in " + timeout);
      }
      SshControlSession session =
          finishConnection(target, binaries, directory, socket, process, output, errors, timeout);
      handedOff = true;
      log.info(
          "SSH connection ready target={} durationMs={}",
          target.displayName(),
          elapsedMillis(startedNanos));
      return session;
    } catch (IOException | InterruptedException | RuntimeException failure) {
      connectionFailure = failure;
      log.warn(
          "SSH connection failed target={} durationMs={} failureType={}",
          target.displayName(),
          elapsedMillis(startedNanos),
          failureType(failure));
      throw failure;
    } finally {
      if (!handedOff) {
        MasterCleanup cleanup = cleanupMaster(process, output, errors);
        if (connectionFailure != null && cleanup.failure() != null) {
          connectionFailure.addSuppressed(cleanup.failure());
        }
        if (cleanup.terminated()) {
          deletePrivateDirectory(directory, socket);
        }
      }
    }
  }

  private static SshControlSession finishConnection(
      SshTarget target,
      OpenSshBinaries binaries,
      Path directory,
      Path socket,
      ManagedProcess process,
      Subprocess.BoundedDrain output,
      Subprocess.BoundedDrain errors,
      Duration timeout)
      throws IOException, InterruptedException {
    AtomicBoolean closed = new AtomicBoolean();
    BooleanSupplier open = () -> !closed.get() && process.isAlive();
    SshCommandExecutor executor = new SshCommandExecutor(target, binaries.ssh(), socket, open);
    CommandResult pwd =
        executor.run(
            new CommandRequest(
                List.of("/bin/pwd", "-P"),
                Optional.empty(),
                Map.of(),
                RuntimeEnvironment.INHERIT_ALL,
                false),
            timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10));
    if (!pwd.isSuccess()) {
      throw new IOException(
          "connected over SSH but could not determine the remote directory: "
              + pwd.failureDetail());
    }
    String directoryOnTarget = RemoteText.singleLine(pwd.stdout(), "remote pwd");
    if (!directoryOnTarget.startsWith("/")) {
      throw new IOException("remote pwd did not return an absolute Linux path");
    }
    log.debug("SSH remote working directory verified target={}", target.displayName());
    CommandResult tools =
        executor.run(
            CommandRequest.of(
                List.of(
                    "/bin/sh",
                    "-c",
                    "for bbv_tool do [ -x \"$bbv_tool\" ] || { "
                        + "printf 'missing required remote tool: %s\\n' \"$bbv_tool\" >&2; "
                        + "exit 69; }; done",
                    "bbv-tools",
                    "/bin/chmod",
                    "/bin/bash",
                    "/bin/kill",
                    "/bin/ln",
                    "/bin/mv",
                    "/bin/rm",
                    "/bin/rmdir",
                    "/bin/sh",
                    "/usr/bin/env",
                    "/usr/bin/awk",
                    "/usr/bin/find",
                    "/usr/bin/flock",
                    "/usr/bin/head",
                    "/usr/bin/mktemp",
                    "/usr/bin/ps",
                    "/usr/bin/readlink",
                    "/usr/bin/setsid",
                    "/usr/bin/sha256sum",
                    "/usr/bin/sort",
                    "/usr/bin/stat",
                    "/usr/bin/stty",
                    "/usr/bin/tail",
                    "/usr/bin/tr"),
                directoryOnTarget),
            Duration.ofSeconds(10));
    if (!tools.isSuccess()) {
      throw new IOException(
          "the SSH host is missing a required Linux tool: " + tools.failureDetail());
    }
    log.debug("SSH remote toolchain verified target={}", target.displayName());
    SftpClient sftp = new SftpClient(target, binaries.sftp(), socket, open);
    sftp.checkAvailable();
    SshExecutionFileSystem files =
        new SshExecutionFileSystem("ssh-" + UUID.randomUUID(), directoryOnTarget, executor, sftp);
    return new SshControlSession(
        target, binaries, directory, socket, process, output, errors, executor, files, closed);
  }

  private SshControlSession(
      SshTarget target,
      OpenSshBinaries binaries,
      Path controlDirectory,
      Path controlSocket,
      ManagedProcess master,
      Subprocess.BoundedDrain masterOutput,
      Subprocess.BoundedDrain masterErrors,
      SshCommandExecutor commandExecutor,
      ExecutionFileSystem fileSystem,
      AtomicBoolean closed) {
    this.target = target;
    this.binaries = binaries;
    this.controlDirectory = controlDirectory;
    this.controlSocket = controlSocket;
    this.master = master;
    this.masterOutput = masterOutput;
    this.masterErrors = masterErrors;
    this.commandExecutor = commandExecutor;
    this.fileSystem = fileSystem;
    this.closed = closed;
  }

  public SshTarget target() {
    return target;
  }

  public String displayName() {
    return target.displayName();
  }

  public SshCommandExecutor commandExecutor() {
    ensureOpen();
    return commandExecutor;
  }

  public ExecutionFileSystem fileSystem() {
    ensureOpen();
    return fileSystem;
  }

  /** Requests a loopback-only remote port chosen by OpenSSH. */
  public SshReverseForward openReverseForward(int localPort)
      throws IOException, InterruptedException {
    ensureOpen();
    if (localPort < 1 || localPort > 65_535) {
      throw new IllegalArgumentException("local port must be between 1 and 65535");
    }
    long startedNanos = System.nanoTime();
    log.info("SSH reverse forward opening target={} localPort={}", target.displayName(), localPort);
    String specification = "127.0.0.1:0:127.0.0.1:" + localPort;
    OpenSshProcess.Result result =
        OpenSshProcess.run(
            controlArguments(
                binaries.ssh(), controlSocket, target, "forward", List.of("-R", specification)),
            Duration.ofSeconds(10));
    if (!result.isSuccess()) {
      throw new IOException("could not open the reverse SSH tunnel: " + result.failureDetail());
    }
    int remotePort = parseAllocatedPort(result.stdout());
    final SshReverseForward[] holder = new SshReverseForward[1];
    SshReverseForward forward =
        new SshReverseForward(
            localPort,
            remotePort,
            () -> {
              cancelForward(localPort, remotePort);
              forwards.remove(holder[0]);
            });
    holder[0] = forward;
    forwards.add(forward);
    log.info(
        "SSH reverse forward ready target={} localPort={} remotePort={} durationMs={}",
        target.displayName(),
        localPort,
        remotePort,
        elapsedMillis(startedNanos));
    return forward;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    long startedNanos = System.nanoTime();
    log.info(
        "SSH connection closing target={} reverseForwardCount={}",
        target.displayName(),
        forwards.size());
    for (SshReverseForward forward : List.copyOf(forwards)) {
      forward.close();
    }
    try {
      OpenSshProcess.Result exit =
          OpenSshProcess.run(
              controlArguments(binaries.ssh(), controlSocket, target, "exit", List.of()),
              Duration.ofSeconds(3));
      log.debug(
          "SSH control master exit requested target={} exitCode={} timedOut={}",
          target.displayName(),
          exit.exitCode(),
          exit.timedOut());
    } catch (IOException | InterruptedException | RuntimeException failure) {
      log.debug(
          "SSH control master exit request failed target={} failureType={}",
          target.displayName(),
          failureType(failure));
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
    MasterCleanup cleanup = cleanupMaster(master, masterOutput, masterErrors);
    if (cleanup.terminated()) {
      deletePrivateDirectory(controlDirectory, controlSocket);
    }
    if (cleanup.failure() != null) {
      log.warn(
          "SSH control master cleanup incomplete target={} failureType={}",
          target.displayName(),
          failureType(cleanup.failure()));
    }
    log.info(
        "SSH connection closed target={} durationMs={}",
        target.displayName(),
        elapsedMillis(startedNanos));
  }

  private void cancelForward(int localPort, int remotePort) {
    long startedNanos = System.nanoTime();
    log.trace(
        "SSH reverse forward closing target={} localPort={} remotePort={}",
        target.displayName(),
        localPort,
        remotePort);
    String specification = "127.0.0.1:" + remotePort + ":127.0.0.1:" + localPort;
    try {
      OpenSshProcess.Result result =
          OpenSshProcess.run(
              controlArguments(
                  binaries.ssh(), controlSocket, target, "cancel", List.of("-R", specification)),
              Duration.ofSeconds(5));
      log.trace(
          "SSH reverse forward closed target={} localPort={} remotePort={}"
              + " exitCode={} timedOut={} durationMs={}",
          target.displayName(),
          localPort,
          remotePort,
          result.exitCode(),
          result.timedOut(),
          elapsedMillis(startedNanos));
    } catch (IOException | InterruptedException | RuntimeException ignored) {
      log.debug(
          "SSH reverse forward close failed target={} failureType={}",
          target.displayName(),
          failureType(ignored));
      if (ignored instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  void ensureOpen() {
    if (closed.get() || !master.isAlive()) {
      throw new IllegalStateException("the SSH control session is closed");
    }
  }

  static List<String> masterArguments(Path ssh, Path socket, SshTarget target) {
    List<String> argv = commonArguments(ssh, socket, target, true);
    argv.addAll(1, List.of("-M", "-N", "-T"));
    argv.add(target.destination());
    return List.copyOf(argv);
  }

  static List<String> commandArguments(
      Path ssh, Path socket, SshTarget target, boolean tty, String remoteCommand) {
    List<String> argv = commonArguments(ssh, socket, target, true);
    if (tty) {
      argv.add("-tt");
      argv.add("-e");
      argv.add("none");
    } else {
      argv.add("-T");
    }
    argv.add(target.destination());
    argv.add(remoteCommand);
    return List.copyOf(argv);
  }

  private static List<String> controlArguments(
      Path ssh, Path socket, SshTarget target, String operation, List<String> extra) {
    List<String> argv = commonArguments(ssh, socket, target, false);
    argv.add("-O");
    argv.add(operation);
    argv.addAll(extra);
    argv.add(target.destination());
    return List.copyOf(argv);
  }

  private static List<String> commonArguments(
      Path ssh, Path socket, SshTarget target, boolean clearForwardings) {
    List<String> argv = new ArrayList<>();
    argv.add(ssh.toString());
    argv.add("-S");
    argv.add(socket.toString());
    option(argv, "BatchMode=yes");
    option(argv, "ForwardAgent=no");
    option(argv, "ForwardX11=no");
    option(argv, "PermitLocalCommand=no");
    option(argv, "RemoteCommand=none");
    option(argv, "ControlPersist=no");
    option(argv, "ServerAliveInterval=15");
    option(argv, "ServerAliveCountMax=3");
    if (clearForwardings) {
      option(argv, "ClearAllForwardings=yes");
    }
    if (target.port().isPresent()) {
      argv.add("-p");
      argv.add(Integer.toString(target.port().getAsInt()));
    }
    return argv;
  }

  private static void option(List<String> argv, String value) {
    argv.add("-o");
    argv.add(value);
  }

  private static int parseAllocatedPort(String output) throws IOException {
    for (String line : output.strip().split("\\R")) {
      String candidate = line.strip();
      if (candidate.matches("[0-9]{1,5}")) {
        int port = Integer.parseInt(candidate);
        if (port > 0 && port <= 65_535) {
          return port;
        }
      }
    }
    throw new IOException("OpenSSH did not report the allocated reverse-forward port");
  }

  private static void requireExecutable(Path binary, String name) throws IOException {
    if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
      throw new IOException("system " + name + " client is not executable: " + binary);
    }
  }

  private static void makePrivate(Path directory) throws IOException {
    try {
      Files.setPosixFilePermissions(
          directory,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException ignored) {
      // OpenSSH applies its own control-socket ownership checks on non-POSIX filesystems.
    }
  }

  private static void deletePrivateDirectory(Path directory, Path socket) {
    try {
      Files.deleteIfExists(socket);
    } catch (IOException ignored) {
      // Best effort after the owning master has stopped.
    }
    try {
      Files.deleteIfExists(directory);
    } catch (IOException ignored) {
      // It contains only this session's known socket.
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private record MasterCleanup(boolean terminated, IOException failure) {}

  private static MasterCleanup cleanupMaster(
      ManagedProcess process,
      Subprocess.BoundedDrain standardOutput,
      Subprocess.BoundedDrain diagnostics) {
    boolean restoreInterrupt = Thread.interrupted();
    IOException failure = null;
    try {
      if (process.isAlive()) {
        process.signal(CancellationMode.TERMINATE);
        if (!process.awaitExit(Duration.ofSeconds(2))) {
          process.terminate();
        }
      }
    } catch (IOException | InterruptedException | RuntimeException gracefulFailure) {
      restoreInterrupt |= gracefulFailure instanceof InterruptedException;
      Thread.interrupted();
      log.debug(
          "SSH control master graceful cleanup failed failureType={}",
          failureType(gracefulFailure));
    }
    boolean terminated = false;
    try {
      process.terminate();
      terminated = true;
    } catch (IOException | InterruptedException | RuntimeException forcedFailure) {
      restoreInterrupt |= forcedFailure instanceof InterruptedException;
      Thread.interrupted();
      failure =
          appendCleanupFailure(
              failure, "could not terminate the SSH control master", forcedFailure);
    }
    for (Subprocess.BoundedDrain drain :
        new Subprocess.BoundedDrain[] {standardOutput, diagnostics}) {
      if (drain == null) {
        continue;
      }
      try {
        Subprocess.CapturedOutput captured = drain.await();
        if (captured.incomplete()) {
          failure =
              appendCleanupFailure(
                  failure,
                  "SSH control master output could not be drained to completion",
                  new IOException("incomplete control-master output"));
        }
        if (captured.truncated()) {
          failure =
              appendCleanupFailure(
                  failure,
                  "SSH control master output exceeded the retained limit",
                  new IOException("truncated control-master output"));
        }
      } catch (IOException | InterruptedException drainFailure) {
        restoreInterrupt |= drainFailure instanceof InterruptedException;
        Thread.interrupted();
        drain.close();
        try {
          drain.awaitCleanup();
        } catch (IOException | InterruptedException joinFailure) {
          restoreInterrupt |= joinFailure instanceof InterruptedException;
          Thread.interrupted();
          drainFailure.addSuppressed(joinFailure);
        }
        failure =
            appendCleanupFailure(
                failure, "could not clean up SSH control master output", drainFailure);
      }
    }
    try {
      process.close();
    } catch (IOException closeFailure) {
      failure =
          appendCleanupFailure(
              failure, "could not remove SSH control master isolation state", closeFailure);
    }
    if (restoreInterrupt) {
      Thread.currentThread().interrupt();
    }
    return new MasterCleanup(terminated, failure);
  }

  private static IOException appendCleanupFailure(
      IOException aggregate, String message, Throwable failure) {
    IOException next = failure instanceof IOException io ? io : new IOException(message, failure);
    if (aggregate == null) {
      return next;
    }
    aggregate.addSuppressed(next);
    return aggregate;
  }

  private static String diagnosticText(
      Subprocess.CapturedOutput standardOutput, Subprocess.CapturedOutput standardError) {
    String output = standardOutput.text().strip();
    String error = standardError.text().strip();
    if (output.isEmpty()) {
      return error;
    }
    if (error.isEmpty()) {
      return output;
    }
    return output + "\n" + error;
  }

  private static String failureType(Throwable failure) {
    return failure.getClass().getSimpleName();
  }
}
