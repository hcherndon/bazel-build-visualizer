package com.holtherndon.bazelviz.runner.ssh;

import com.holtherndon.bazelviz.runner.proc.Subprocess;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Small, bounded local process helpers for the system OpenSSH clients. */
final class OpenSshProcess {

  private static final Logger log = LoggerFactory.getLogger(OpenSshProcess.class);

  private OpenSshProcess() {}

  record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
    boolean isSuccess() {
      return !timedOut && exitCode == 0;
    }

    String failureDetail() {
      if (timedOut) {
        return "OpenSSH did not finish in time";
      }
      String text = stderr.isBlank() ? stdout : stderr;
      return text.isBlank() ? "OpenSSH exited " + exitCode : text.strip();
    }
  }

  static Result run(List<String> argv, Duration timeout) throws IOException, InterruptedException {
    return run(argv, timeout, null);
  }

  static Result run(List<String> argv, Duration timeout, byte[] stdin)
      throws IOException, InterruptedException {
    return run(argv, timeout, stdin, null);
  }

  static Result run(List<String> argv, Duration timeout, byte[] stdin, BooleanSupplier abort)
      throws IOException, InterruptedException {
    Objects.requireNonNull(argv, "argv");
    Objects.requireNonNull(timeout, "timeout");
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("argv must not be empty");
    }
    long startedNanos = System.nanoTime();
    log.trace(
        "OpenSSH subprocess starting argumentCount={} inputBytes={} timeoutMs={}",
        argv.size(),
        stdin == null ? 0 : stdin.length,
        timeout.toMillis());
    Process process = new ProcessBuilder(argv).start();
    log.trace("OpenSSH subprocess started processId={}", process.pid());
    Capture stdout = Capture.start(process.getInputStream(), "bbv-openssh-stdout");
    Capture stderr = Capture.start(process.getErrorStream(), "bbv-openssh-stderr");
    IOException inputFailure = null;
    try (var input = process.getOutputStream()) {
      if (stdin != null) {
        input.write(stdin);
      }
    } catch (IOException closedEarly) {
      inputFailure = closedEarly;
    }
    boolean exited = false;
    boolean aborted = false;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!exited) {
      if (abort != null && abort.getAsBoolean()) {
        aborted = true;
        destroyDescendants(process);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        break;
      }
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        break;
      }
      exited =
          process.waitFor(
              Math.max(
                  1,
                  Math.min(
                      TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                      SftpClient.TRANSFER_POLL_INTERVAL_MILLIS)),
              TimeUnit.MILLISECONDS);
    }
    if (!exited) {
      if (!aborted) {
        destroyDescendants(process);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
    String out = stdout.await();
    if (stdout.incomplete()) {
      destroyDescendants(process);
    }
    String err = stderr.await();
    if (stderr.incomplete()) {
      destroyDescendants(process);
    }
    if (stdout.incomplete() || stderr.incomplete()) {
      throw new IOException("OpenSSH output could not be drained to completion");
    }
    if (stdout.overflowed() || stderr.overflowed()) {
      throw new IOException("OpenSSH produced more diagnostic output than can be retained");
    }
    int exitCode = exited ? process.exitValue() : -1;
    if (inputFailure != null && exitCode == 0) {
      throw new IOException("could not send input to OpenSSH", inputFailure);
    }
    Result result = new Result(exitCode, out, err, !exited);
    log.trace(
        "OpenSSH subprocess completed exitCode={} timedOut={} durationMs={}"
            + " stdoutBytes={} stderrBytes={}",
        result.exitCode(),
        result.timedOut(),
        elapsedMillis(startedNanos),
        out.getBytes(StandardCharsets.UTF_8).length,
        err.getBytes(StandardCharsets.UTF_8).length);
    return result;
  }

  private static void destroyDescendants(Process process) {
    try {
      process
          .descendants()
          .limit(Subprocess.MAX_TRACKED_DESCENDANTS)
          .forEach(ProcessHandle::destroyForcibly);
    } catch (RuntimeException ignored) {
      // The root is still terminated below; descendant cleanup is best effort here.
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  static final class Capture {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final InputStream stream;
    private final Thread thread;
    private volatile boolean overflowed;
    private volatile boolean incomplete;

    private Capture(InputStream stream, String name) {
      this.stream = stream;
      thread =
          Thread.ofVirtual()
              .name(name)
              .unstarted(
                  () -> {
                    byte[] buffer = new byte[8 * 1024];
                    try (stream) {
                      int read;
                      while ((read = stream.read(buffer)) >= 0) {
                        if (read == 0) {
                          continue;
                        }
                        int remaining = 256 * 1024 - bytes.size();
                        if (remaining > 0) {
                          bytes.write(buffer, 0, Math.min(remaining, read));
                        }
                        if (read > remaining) {
                          overflowed = true;
                        }
                      }
                    } catch (IOException closed) {
                      // The process and its pipe end together. Retain what arrived.
                    }
                  });
    }

    static Capture start(InputStream stream, String name) {
      Capture capture = new Capture(stream, name);
      capture.thread.start();
      return capture;
    }

    String await() throws InterruptedException {
      thread.join(Subprocess.OUTPUT_DRAIN_GRACE_MILLIS);
      if (thread.isAlive()) {
        incomplete = true;
        try {
          stream.close();
        } catch (IOException ignored) {
          // The process already closed its side.
        }
        thread.join(Subprocess.OUTPUT_DRAIN_GRACE_MILLIS);
      }
      return bytes.toString(StandardCharsets.UTF_8);
    }

    boolean overflowed() {
      return overflowed;
    }

    boolean incomplete() {
      return incomplete || thread.isAlive();
    }
  }
}
