package com.holtherndon.bazelviz.runner.ssh;

import com.holtherndon.bazelviz.runner.proc.Subprocess;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    Subprocess.Result subprocess =
        Subprocess.runWithInput(
            argv,
            null,
            Map.of(),
            timeout,
            stdin == null ? new byte[0] : stdin,
            256 * 1024,
            256 * 1024);
    if (subprocess.outputTruncated()) {
      boolean exceeded =
          subprocess.stdoutBytes() > subprocess.stdoutLimit()
              || subprocess.stderrBytes() > subprocess.stderrLimit();
      throw new IOException(
          exceeded
              ? "OpenSSH produced more diagnostic output than can be retained"
              : "OpenSSH output could not be drained to completion");
    }
    Result result =
        new Result(
            subprocess.exitCode(), subprocess.stdout(), subprocess.stderr(), subprocess.timedOut());
    log.trace(
        "OpenSSH subprocess completed exitCode={} timedOut={} durationMs={}"
            + " stdoutBytes={} stderrBytes={}",
        result.exitCode(),
        result.timedOut(),
        elapsedMillis(startedNanos),
        subprocess.stdoutBytes(),
        subprocess.stderrBytes());
    return result;
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
