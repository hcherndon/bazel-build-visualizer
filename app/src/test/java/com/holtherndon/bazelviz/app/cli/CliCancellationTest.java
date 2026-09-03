package com.holtherndon.bazelviz.app.cli;

import static com.holtherndon.bazelviz.app.cli.CliHarness.number;
import static com.holtherndon.bazelviz.app.cli.CliHarness.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ctrl-C leaves a resumable session, not a corrupt one.
 *
 * <p>The shutdown hook is triggered through an injected registry rather than by sending a signal: a
 * test that sent a real SIGINT would kill the test JVM, and one that raced a background thread
 * against a fast import would be flaky. The registry fires the hook the moment the command installs
 * it, so the cancellation flag is up before the importer reads its first record and the run always
 * takes the cancelled path.
 *
 * <p>The claim being tested is the Phase 1 exit criterion "restart resumes interrupted indexing",
 * stated in the terms the CLI exposes: exit code 4, then a {@code --resume} that finishes the file
 * and ends with exactly the event count an uninterrupted import of the same file produces.
 */
class CliCancellationTest {

  private static final int EVENT_COUNT = 120;

  @TempDir Path workspace;

  @Test
  void cancellingLeavesAResumableSessionThatResumesToTheSameCounts() throws IOException {
    Path source = workspace.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(EVENT_COUNT));
    Path sessionsRoot = workspace.resolve("sessions");

    CliHarness cli = new CliHarness();
    cli.hooks().fireOnRegister(true);
    CliHarness.Result cancelled =
        cli.run("import", source.toString(), "--sessions-root", sessionsRoot.toString(), "--json");

    assertThat(cancelled.code())
        .as("an interrupted import has its own exit code; stderr was:%n%s", cancelled.err())
        .isEqualTo(ExitCode.CANCELLED.code());
    JsonObject cancelledSummary = cancelled.json();
    assertThat(string(cancelledSummary, "outcome")).isEqualTo("CANCELLED");
    assertThat(string(cancelledSummary, "sourceCompleteness"))
        .as("cancelling says nothing about the bytes not yet read")
        .isEqualTo("UNKNOWN");
    assertThat(CliHarness.bool(cancelledSummary, "resumable")).isTrue();
    assertThat(cancelled.err()).contains("interrupted");

    // The hook waited for the command instead of letting the process die
    // mid-record, and the command removed it again on the way out.
    assertThat(cli.hooks().registered()).hasSize(1);
    assertThat(cli.hooks().removed()).hasSize(1);

    Path sessionDirectory = CliHarness.sessionDirectory(cancelledSummary);

    // Now finish it. A second harness, with the hook disarmed.
    CliHarness resumeCli = new CliHarness();
    CliHarness.Result resumed =
        resumeCli.run(
            "import",
            source.toString(),
            "--sessions-root",
            sessionsRoot.toString(),
            "--resume",
            "--json");

    assertThat(resumed.code()).as("stderr was:%n%s", resumed.err()).isZero();
    JsonObject resumedSummary = resumed.json();
    assertThat(string(resumedSummary, "outcome")).isEqualTo("COMPLETE");
    assertThat(CliHarness.sessionDirectory(resumedSummary))
        .as("the resume continued the interrupted session rather than starting a new one")
        .isEqualTo(sessionDirectory);
    assertThat(CliHarness.bool(resumedSummary, "resumed"))
        .as("the summary says out loud that this run continued an earlier one")
        .isTrue();

    // Reference: an uninterrupted import of the same bytes.
    CliHarness reference = new CliHarness();
    JsonObject uninterrupted =
        reference
            .run(
                "import",
                source.toString(),
                "--sessions-root",
                workspace.resolve("reference").toString(),
                "--json")
            .json();

    assertThat(number(resumedSummary, "eventCount"))
        .as("resuming produces the same session an uninterrupted run would")
        .isEqualTo(number(uninterrupted, "eventCount"))
        .isEqualTo(EVENT_COUNT);
    assertThat(string(resumedSummary, "sourceCompleteness")).isEqualTo("COMPLETE");
  }

  @Test
  void theHookRaisesTheFlagAndWaitsForTheCommandToSettle() throws InterruptedException {
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    CliHarness.RecordingHooks hooks = new CliHarness.RecordingHooks();

    CountDownLatch hookReturned = new CountDownLatch(1);
    try (CancellationGuard guard = CancellationGuard.install(hooks, err, 5_000)) {
      assertThat(guard.getAsBoolean()).isFalse();

      Thread hook = hooks.registered().get(0);
      hook.start();

      // The flag goes up promptly…
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!guard.getAsBoolean() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }
      assertThat(guard.getAsBoolean()).isTrue();

      // Only now start the watcher. Thread.join() on a thread that has
      // not started yet returns at once, so a watcher started before
      // hook.start() can win the race and report the hook as returned
      // before it has run — which under load made the assertion below
      // fail for a reason that had nothing to do with the guard.
      Thread watcher =
          new Thread(
              () -> {
                try {
                  hook.join();
                  hookReturned.countDown();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
      watcher.start();

      // …but the hook does not return, because returning is what lets the
      // JVM halt, and halting here is exactly the corruption being avoided.
      assertThat(hookReturned.await(150, TimeUnit.MILLISECONDS)).isFalse();

      guard.settled();
      assertThat(hookReturned.await(5, TimeUnit.SECONDS))
          .as("the hook releases the JVM once the command has finished writing")
          .isTrue();
      watcher.join(5_000);
    }

    assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("interrupted");
    assertThat(hooks.removed()).hasSize(1);
  }
}
