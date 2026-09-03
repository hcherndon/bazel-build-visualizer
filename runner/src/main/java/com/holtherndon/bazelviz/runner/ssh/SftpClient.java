package com.holtherndon.bazelviz.runner.ssh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** File transfer through the system SFTP client and an existing control master. */
final class SftpClient {

  private static final Logger log = LoggerFactory.getLogger(SftpClient.class);

  private final SshTarget target;
  private final Path sftp;
  private final Path controlSocket;
  private final BooleanSupplier sessionOpen;

  SftpClient(SshTarget target, Path sftp, Path controlSocket) {
    this(target, sftp, controlSocket, () -> true);
  }

  SftpClient(SshTarget target, Path sftp, Path controlSocket, BooleanSupplier sessionOpen) {
    this.target = Objects.requireNonNull(target, "target");
    this.sftp = Objects.requireNonNull(sftp, "sftp");
    this.controlSocket = Objects.requireNonNull(controlSocket, "controlSocket");
    this.sessionOpen = Objects.requireNonNull(sessionOpen, "sessionOpen");
  }

  void download(String remote, Path local, long expectedMaximumBytes)
      throws IOException, InterruptedException {
    transfer(
        "download",
        "get " + quoteBatchPath(remote) + " " + quoteBatchPath(local.toString()),
        expectedMaximumBytes);
  }

  void upload(Path local, String remote, long expectedMaximumBytes)
      throws IOException, InterruptedException {
    transfer(
        "upload",
        "put " + quoteBatchPath(local.toString()) + " " + quoteBatchPath(remote),
        expectedMaximumBytes);
  }

  void checkAvailable() throws IOException, InterruptedException {
    long startedNanos = System.nanoTime();
    log.debug("SFTP availability check started target={}", target.displayName());
    OpenSshProcess.Result result =
        OpenSshProcess.run(
            arguments(), Duration.ofSeconds(10), "pwd\nquit\n".getBytes(StandardCharsets.UTF_8));
    if (!result.isSuccess()) {
      log.warn(
          "SFTP availability check failed target={} exitCode={} timedOut={}" + " durationMs={}",
          target.displayName(),
          result.exitCode(),
          result.timedOut(),
          elapsedMillis(startedNanos));
      throw new IOException(
          "the SSH server's SFTP subsystem is unavailable: " + result.failureDetail());
    }
    log.info(
        "SFTP subsystem available target={} durationMs={}",
        target.displayName(),
        elapsedMillis(startedNanos));
  }

  private void transfer(String operation, String instruction, long expectedMaximumBytes)
      throws IOException, InterruptedException {
    if (!sessionOpen.getAsBoolean()) {
      throw new IOException("the SSH control session is closed");
    }
    long startedNanos = System.nanoTime();
    log.debug(
        "SFTP transfer started operation={} target={} maximumBytes={}",
        operation,
        target.displayName(),
        Math.max(0, expectedMaximumBytes));
    String batch = instruction + "\nquit\n";
    OpenSshProcess.Result result =
        OpenSshProcess.run(
            arguments(),
            transferTimeout(expectedMaximumBytes),
            batch.getBytes(StandardCharsets.UTF_8));
    if (!result.isSuccess()) {
      log.warn(
          "SFTP transfer failed operation={} target={} exitCode={} timedOut={}" + " durationMs={}",
          operation,
          target.displayName(),
          result.exitCode(),
          result.timedOut(),
          elapsedMillis(startedNanos));
      throw new IOException("SFTP transfer failed: " + result.failureDetail());
    }
    log.debug(
        "SFTP transfer completed operation={} target={} durationMs={}",
        operation,
        target.displayName(),
        elapsedMillis(startedNanos));
  }

  List<String> arguments() {
    List<String> argv = new ArrayList<>();
    argv.add(sftp.toString());
    argv.add("-q");
    argv.add("-b");
    argv.add("-");
    option(argv, "BatchMode=yes");
    option(argv, "ControlPath=" + controlSocket);
    option(argv, "ClearAllForwardings=yes");
    option(argv, "ForwardAgent=no");
    option(argv, "ForwardX11=no");
    option(argv, "PermitLocalCommand=no");
    option(argv, "RemoteCommand=none");
    option(argv, "ControlPersist=no");
    if (target.port().isPresent()) {
      argv.add("-P");
      argv.add(Integer.toString(target.port().getAsInt()));
    }
    argv.add(target.destination());
    return List.copyOf(argv);
  }

  static String quoteBatchPath(String value) {
    Objects.requireNonNull(value, "value");
    if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("SFTP batch paths cannot contain NUL or newlines");
    }
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\'
          || character == '"'
          || character == '*'
          || character == '?'
          || character == '['
          || character == ']') {
        escaped.append('\\');
      }
      escaped.append(character);
    }
    return escaped.append('"').toString();
  }

  private static Duration transferTimeout(long bytes) {
    long bounded = Math.max(0, bytes);
    long seconds = 60 + Math.min(21_540, bounded / (64 * 1024));
    return Duration.ofSeconds(seconds);
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  private static void option(List<String> argv, String value) {
    argv.add("-o");
    argv.add(value);
  }
}
