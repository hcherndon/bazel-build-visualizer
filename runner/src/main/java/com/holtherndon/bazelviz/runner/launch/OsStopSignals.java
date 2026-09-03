package com.holtherndon.bazelviz.runner.launch;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real signals, sent to the real client.
 *
 * <p>Lifted out of {@link BazelLauncher} unchanged so that the ladder that chooses the rungs and
 * the code that delivers them can be tested apart. Every decision here was measured against real
 * Bazel binaries and the reasoning is kept with the code that depends on it.
 */
final class OsStopSignals implements StopSignals {

  private static final Logger log = LoggerFactory.getLogger(OsStopSignals.class);

  static final OsStopSignals INSTANCE = new OsStopSignals();

  private OsStopSignals() {}

  @Override
  public void deliver(CancellationMode mode, Process process) {
    switch (mode) {
      case CANCEL -> sendInterrupt(process);
      case TERMINATE -> {
        if (process.isAlive()) {
          process.destroy();
        }
      }
      case FORCE_KILL -> {
        // The descendant sweep is kept for launchers that do have
        // children — a corporate wrapper script, or shell mode. It
        // is deliberately not how the Bazel server is reached,
        // because the server is not a descendant of the client: it
        // runs with PPID 1 in its own session from the first
        // millisecond, so this walk finds it neither during a cold
        // start nor mid-build. That is the intended outcome. The
        // server is shared with every other terminal using the same
        // output base, and killing it would throw away their state.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }

  /**
   * Sends {@code SIGINT}, which the JDK has no API for.
   *
   * <p>Falls back to {@code SIGTERM} when {@code kill} is unavailable — on a platform without it, a
   * slightly harsher stop is better than a cancel button that does nothing.
   */
  private static void sendInterrupt(Process process) {
    if (!process.isAlive()) {
      // The client exits within about 25 ms of the first signal, so
      // by the time an escalation rung fires it is usually already
      // gone. Signalling a bare pid then is at best a no-op and at
      // worst reaches whatever the operating system has since given
      // that number to.
      return;
    }
    try {
      Process kill =
          new ProcessBuilder("/bin/kill", "-INT", Long.toString(process.pid()))
              .redirectErrorStream(true)
              .start();
      if (!kill.waitFor(5, TimeUnit.SECONDS) || kill.exitValue() != 0) {
        log.info("could not interrupt {}; falling back to terminate", process.pid());
        process.destroy();
      }
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.info(
          "could not interrupt {}; failureType={}; falling back to terminate",
          process.pid(),
          failure.getClass().getSimpleName());
      process.destroy();
    }
  }
}
