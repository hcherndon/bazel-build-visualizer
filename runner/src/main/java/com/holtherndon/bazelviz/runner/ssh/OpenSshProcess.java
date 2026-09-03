package com.holtherndon.bazelviz.runner.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
    boolean exited = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    String out = stdout.await();
    String err = stderr.await();
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

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  static final class Capture {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final Thread thread;
    private volatile boolean overflowed;

    private Capture(InputStream stream, String name) {
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
      thread.join(TimeUnit.SECONDS.toMillis(5));
      return bytes.toString(StandardCharsets.UTF_8);
    }

    boolean overflowed() {
      return overflowed;
    }
  }
}
