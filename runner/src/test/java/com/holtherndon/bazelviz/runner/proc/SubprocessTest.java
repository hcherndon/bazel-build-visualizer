package com.holtherndon.bazelviz.runner.proc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SubprocessTest {

  @Test
  @DisplayName("probe output is drained but only its bounded prefix is retained")
  void outputCaptureIsBoundedAndExplicit() throws Exception {
    Subprocess.Result result =
        Subprocess.run(
            List.of("/bin/sh", "-c", "printf '0123456789'; printf 'abcdefghij' >&2"),
            null,
            Map.of(),
            Duration.ofSeconds(5),
            4,
            5);

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).isEqualTo("0123");
    assertThat(result.stderr()).isEqualTo("abcde");
    assertThat(result.stdoutBytes()).isEqualTo(10);
    assertThat(result.stderrBytes()).isEqualTo(10);
    assertThat(result.stdoutTruncated()).isTrue();
    assertThat(result.stderrTruncated()).isTrue();
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.failureDetail())
        .contains("4-byte capture limit")
        .contains("5-byte capture limit")
        .contains("retained text is only a prefix");
  }

  @Test
  @DisplayName("complete output from a successful probe remains successful")
  void completeOutputRemainsSuccessful() throws Exception {
    Subprocess.Result result =
        Subprocess.run(
            List.of("/bin/sh", "-c", "printf 'version'; printf 'warning' >&2"),
            null,
            Map.of(),
            Duration.ofSeconds(5),
            64,
            64);

    assertThat(result.stdout()).isEqualTo("version");
    assertThat(result.stderr()).isEqualTo("warning");
    assertThat(result.outputTruncated()).isFalse();
    assertThat(result.isSuccess()).isTrue();
  }

  @Test
  @DisplayName("process-group isolation preserves every argument exactly")
  void processGroupWrapperPreservesArgv() throws Exception {
    Subprocess.Result result =
        Subprocess.run(
            List.of(
                "/bin/sh",
                "-c",
                "printf '<%s><%s>' \"$1\" \"$2\"",
                "inner-shell",
                "space value",
                "quote'\"$*"),
            null,
            Map.of(),
            Duration.ofSeconds(5),
            64,
            64);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.stdout()).isEqualTo("<space value><quote'\"$*>");
  }

  @Test
  @DisplayName("exact environments keep an option-looking executable behind env's boundary")
  void exactEnvironmentKeepsOptionLookingExecutableAsArgv() throws Exception {
    Subprocess.Result result =
        Subprocess.runWithExactEnvironment(
            List.of("-bbv-command-that-does-not-exist"), null, Map.of(), Duration.ofSeconds(5));

    assertThat(result.exitCode()).isEqualTo(127);
    assertThat(result.stderr()).contains("-bbv-command-that-does-not-exist");
  }

  @Test
  @DisplayName("a partial process-group control record is never accepted")
  void partialProcessGroupControlRecordIsPendingOrMalformed() throws Exception {
    byte[] partial = "READY 1".getBytes(StandardCharsets.US_ASCII);

    assertThat(Subprocess.parseProcessGroupControlResponse(partial, true, 999, false)).isZero();
    assertThatThrownBy(
            () -> Subprocess.parseProcessGroupControlResponse(partial, false, 999, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("incomplete");
    assertThat(
            Subprocess.parseProcessGroupControlResponse(
                "READY 123\n".getBytes(StandardCharsets.US_ASCII), true, 999, false))
        .isEqualTo(123);
  }

  @Test
  @DisplayName("an empty process-group control record is malformed after its writer exits")
  void emptyControlRecordFromDeadWriterIsMalformed() throws Exception {
    assertThat(Subprocess.parseProcessGroupControlResponse(new byte[0], true, 999, false)).isZero();
    assertThatThrownBy(
            () -> Subprocess.parseProcessGroupControlResponse(new byte[0], false, 999, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("missing process-group control response");
  }

  @Test
  @DisplayName("Linux setsid may report the ProcessBuilder root as the isolated group")
  void linuxControlRecordMayUseRootPid() throws Exception {
    byte[] rootGroup = "READY 123\n".getBytes(StandardCharsets.US_ASCII);

    assertThat(Subprocess.parseProcessGroupControlResponse(rootGroup, true, 123, true))
        .isEqualTo(123);
    assertThatThrownBy(
            () -> Subprocess.parseProcessGroupControlResponse(rootGroup, true, 123, false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("invalid process-group control response")
        .hasRootCauseMessage("unsafe process-group id 123");
  }

  @Test
  @DisplayName("the Linux command uses setsid without a forced fork")
  void linuxCommandConstructionIsDirectAndPreservesArgv() {
    List<String> command =
        Subprocess.linuxProcessGroupCommand(
            Path.of("/usr/bin/setsid"),
            Path.of("/tmp/private-control"),
            List.of("tool", "space value", "quote'\"$*"));

    assertThat(command.subList(0, 4)).containsExactly("/usr/bin/setsid", "--wait", "/bin/sh", "-c");
    assertThat(command.subList(5, command.size()))
        .containsExactly(
            "bazelviz-probe-group", "/tmp/private-control", "tool", "space value", "quote'\"$*");
    assertThat(command.subList(0, 5)).doesNotContain("-f", "--fork");
  }

  @Test
  @DisplayName("a failed Linux control-record write never executes the requested command")
  void linuxControlWriteFailureStopsBeforeExec(@TempDir Path temporary) throws Exception {
    Path marker = temporary.resolve("command-ran");
    Path unavailableControl = temporary.resolve("missing-parent").resolve("control");
    Process process =
        new ProcessBuilder(
                Subprocess.linuxSessionCommand(
                    unavailableControl,
                    List.of(
                        "/bin/sh",
                        "-c",
                        "printf ran > \"$1\"",
                        "subprocess-test-command",
                        marker.toString())))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();

    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isEqualTo(125);
    assertThat(marker).doesNotExist();
  }

  @Test
  @DisplayName("a failed macOS control-record write kills the gate before the command runs")
  void macControlWriteFailureStopsBeforeExec(@TempDir Path temporary) throws Exception {
    Path marker = temporary.resolve("command-ran");
    Path unavailableControl = temporary.resolve("missing-parent").resolve("control");
    Process process =
        new ProcessBuilder(
                Subprocess.macProcessGroupCommand(
                    unavailableControl,
                    List.of(
                        "/bin/sh",
                        "-c",
                        "printf ran > \"$1\"",
                        "subprocess-test-command",
                        marker.toString())))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();

    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isEqualTo(125);
    assertThat(marker).doesNotExist();
  }

  @Test
  @DisplayName("group signalling passes a closed signal and negative PID as separate arguments")
  void processGroupSignalCommandIsNotShellText() {
    assertThat(Subprocess.groupSignalCommand("-KILL", 123))
        .containsExactly(
            "/bin/sh", "-c", "kill \"$1\" \"$2\"", "bazelviz-probe-kill", "-KILL", "-123");
    assertThatThrownBy(() -> Subprocess.groupSignalCommand("-TERM; echo unsafe", 123))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported");
    assertThatThrownBy(() -> Subprocess.groupSignalCommand("-0", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  @DisplayName("a timed-out process with inherited pipes is stopped and joined")
  void timeoutClosesInheritedPipes() throws Exception {
    long started = System.nanoTime();

    Subprocess.Result result =
        Subprocess.run(
            List.of("/bin/sh", "-c", "sleep 30 & printf 'started'; wait"),
            null,
            Map.of(),
            Duration.ofMillis(100),
            64,
            64);

    assertThat(result.timedOut()).isTrue();
    assertThat(result.isSuccess()).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
  }

  @Test
  @DisplayName("timeout and truncation are both reported")
  void timeoutAndTruncationAreBothReported() throws Exception {
    Subprocess.Result result =
        Subprocess.run(
            List.of(
                "/bin/sh",
                "-c",
                "i=0; while [ $i -lt 100 ]; do printf x; i=$((i + 1)); done; sleep 30 & wait"),
            null,
            Map.of(),
            Duration.ofMillis(100),
            4,
            4);

    assertThat(result.timedOut()).isTrue();
    assertThat(result.stdoutTruncated()).isTrue();
    assertThat(result.failureDetail()).contains("did not finish in time").contains("capture limit");
  }

  @Test
  @DisplayName("interruption stops descendants and joins both drains")
  void interruptionCleansUpTheWholeTree(@TempDir Path temporary) throws Exception {
    Path ready = temporary.resolve("child.pid");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    Subprocess.run(
                        immediateOrphanCommand(ready, false),
                        null,
                        Map.of(),
                        Duration.ofSeconds(30),
                        64,
                        64);
                  } catch (Throwable caught) {
                    failure.set(caught);
                  }
                });

    try {
      long childPid = awaitPid(ready);
      worker.interrupt();
      worker.join(Duration.ofSeconds(8));

      assertThat(worker.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertProcessGone(childPid);
    } finally {
      worker.interrupt();
      worker.join(Duration.ofSeconds(8));
      cleanupAnnouncedProcess(ready);
    }
  }

  @Test
  @DisplayName("repeated control failures and a cleanup interrupt cannot abandon known processes")
  void cleanupSurvivesRepeatedCaptureFailureAndInterruption(@TempDir Path temporary)
      throws Exception {
    Path ready = temporary.resolve("tree.pids");
    AtomicInteger captureAttempts = new AtomicInteger();
    AtomicBoolean interruptCleanupOnce = new AtomicBoolean(true);
    CountDownLatch cleanupStarted = new CountDownLatch(1);
    CountDownLatch waitForInterrupt = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);
    Subprocess.ProbeIsolation isolation =
        new Subprocess.ProbeIsolation() {
          @Override
          public List<String> command() {
            return announcedTreeCommand(ready);
          }

          @Override
          public void capture(Process root) throws IOException {
            awaitPidValues(ready);
            captureAttempts.incrementAndGet();
            throw new IOException("injected control-record read failure");
          }

          @Override
          public void destroyForcibly() throws InterruptedException {
            cleanupStarted.countDown();
            if (interruptCleanupOnce.getAndSet(false)) {
              waitForInterrupt.await();
            }
          }

          @Override
          public boolean anyAlive() {
            return false;
          }

          @Override
          public void close() {}
        };
    Thread worker =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    Subprocess.runWithIsolationForTesting(
                        isolation, null, Map.of(), Duration.ofSeconds(30), 64, 64);
                  } catch (Throwable caught) {
                    failure.set(caught);
                  } finally {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                  }
                });

    long[] pids = new long[0];
    try {
      assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
      pids = awaitPids(ready);
      worker.interrupt();
      worker.join(Duration.ofSeconds(8));

      assertThat(worker.isAlive()).isFalse();
      assertThat(failure.get())
          .isInstanceOf(IOException.class)
          .hasMessageContaining("injected control-record read failure");
      assertThat(failure.get().getSuppressed())
          .anyMatch(suppressed -> suppressed instanceof InterruptedException);
      assertThat(interruptRestored.get()).isTrue();
      assertThat(captureAttempts.get()).isGreaterThanOrEqualTo(2);
      for (long pid : pids) {
        assertProcessGone(pid);
      }
    } finally {
      worker.interrupt();
      worker.join(Duration.ofSeconds(8));
      for (long pid : pids) {
        cleanupProcess(pid);
      }
      cleanupAnnouncedProcesses(ready);
    }
  }

  @Test
  @DisplayName("an immediate parent exit cannot hide an inherited-pipe child")
  void normalExitStillReapsInheritedPipeChild(@TempDir Path temporary) throws Exception {
    Path ready = temporary.resolve("child.pid");
    try {
      Subprocess.Result outcome =
          Subprocess.run(
              immediateOrphanCommand(ready, false), null, Map.of(), Duration.ofSeconds(10), 64, 64);
      long childPid = awaitPid(ready);

      assertThat(outcome.outputTruncated()).isTrue();
      assertProcessGone(childPid);
    } finally {
      cleanupAnnouncedProcess(ready);
    }
  }

  @Test
  @DisplayName("a successful detached child with closed standard streams is left running")
  void detachedClosedStreamChildKeepsRunning(@TempDir Path temporary) throws Exception {
    Path ready = temporary.resolve("detached.pid");
    long childPid = -1;
    try {
      Subprocess.Result result =
          Subprocess.run(detachedCommand(ready), null, Map.of(), Duration.ofSeconds(5), 64, 64);
      childPid = awaitPid(ready);

      assertThat(result.isSuccess()).isTrue();
      assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isTrue();
    } finally {
      cleanupAnnouncedProcess(ready);
      if (childPid > 0) {
        assertProcessGone(childPid);
      }
    }
  }

  @Test
  @DisplayName("redirected probes report timeout and bounded stderr together")
  void redirectedTimeoutAndTruncation(@TempDir Path temporary) throws Exception {
    Subprocess.Result result =
        Subprocess.runRedirectingStdout(
            List.of(
                "/bin/sh",
                "-c",
                "i=0; while [ $i -lt 100 ]; do printf e >&2; i=$((i + 1)); done; sleep 30 & wait"),
            null,
            Map.of(),
            Duration.ofMillis(100),
            temporary.resolve("stdout.bin"),
            4);

    assertThat(result.timedOut()).isTrue();
    assertThat(result.stderrTruncated()).isTrue();
    assertThat(result.failureDetail()).contains("did not finish in time").contains("capture limit");
  }

  @Test
  @DisplayName("interrupting a redirected probe reaps its descendants")
  void redirectedInterruptionCleansUp(@TempDir Path temporary) throws Exception {
    Path ready = temporary.resolve("child.pid");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread worker =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    Subprocess.runRedirectingStdout(
                        immediateOrphanCommand(ready, true),
                        null,
                        Map.of(),
                        Duration.ofSeconds(30),
                        temporary.resolve("stdout.bin"),
                        64);
                  } catch (Throwable caught) {
                    failure.set(caught);
                  }
                });

    try {
      long childPid = awaitPid(ready);
      worker.interrupt();
      worker.join(Duration.ofSeconds(8));

      assertThat(worker.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(InterruptedException.class);
      assertProcessGone(childPid);
    } finally {
      worker.interrupt();
      worker.join(Duration.ofSeconds(8));
      cleanupAnnouncedProcess(ready);
    }
  }

  private static List<String> immediateOrphanCommand(Path ready, boolean writeStderr) {
    String output = writeStderr ? "printf e >&2" : "printf x";
    return List.of(
        "/bin/sh",
        "-c",
        "sh -c 'trap \"\" PIPE; while :; do "
            + output
            + "; sleep 1; done' & child=$!; printf '%s' \"$child\" > \"$1\"; exit 0",
        "subprocess-test",
        ready.toString());
  }

  private static List<String> detachedCommand(Path ready) {
    return List.of(
        "/bin/sh",
        "-c",
        "sleep 30 </dev/null >/dev/null 2>&1 & child=$!; printf '%s' \"$child\" > \"$1\";"
            + " exit 0",
        "subprocess-test",
        ready.toString());
  }

  private static List<String> announcedTreeCommand(Path ready) {
    return List.of(
        "/bin/sh",
        "-c",
        "sleep 30 & child=$!; printf '%s %s' \"$$\" \"$child\" > \"$1\"; wait",
        "subprocess-test",
        ready.toString());
  }

  private static String[] awaitPidValues(Path file) throws IOException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(file)) {
        String[] values = Files.readString(file).strip().split(" ");
        if (values.length == 2) {
          try {
            if (Long.parseLong(values[0]) > 0 && Long.parseLong(values[1]) > 0) {
              return values;
            }
          } catch (NumberFormatException incomplete) {
            // The shell may still be completing its one small regular-file write.
          }
        }
      }
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    throw new IOException("timed out waiting for a complete hostile process-tree record");
  }

  private static long awaitPid(Path file) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!Files.exists(file) && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    assertThat(Files.exists(file)).as("child process announced itself").isTrue();
    return Long.parseLong(Files.readString(file));
  }

  private static long[] awaitPids(Path file) throws Exception {
    String[] values = awaitPidValues(file);
    return new long[] {Long.parseLong(values[0]), Long.parseLong(values[1])};
  }

  private static void assertProcessGone(long pid) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        && System.nanoTime() < deadline) {
      LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
    }
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  private static void cleanupAnnouncedProcess(Path file) {
    try {
      if (!Files.exists(file)) {
        return;
      }
      long pid = Long.parseLong(Files.readString(file));
      ProcessHandle.of(pid)
          .ifPresent(
              process -> {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
              });
    } catch (RuntimeException | IOException ignored) {
      // Best-effort test cleanup must not hide the product assertion that failed.
    }
  }

  private static void cleanupAnnouncedProcesses(Path file) {
    try {
      if (!Files.exists(file)) {
        return;
      }
      for (String value : Files.readString(file).strip().split(" ")) {
        cleanupProcess(Long.parseLong(value));
      }
    } catch (RuntimeException | IOException ignored) {
      // Best-effort test cleanup must not hide the product assertion that failed.
    }
  }

  private static void cleanupProcess(long pid) {
    ProcessHandle.of(pid)
        .ifPresent(
            process -> {
              process.descendants().forEach(ProcessHandle::destroyForcibly);
              process.destroyForcibly();
            });
  }
}
