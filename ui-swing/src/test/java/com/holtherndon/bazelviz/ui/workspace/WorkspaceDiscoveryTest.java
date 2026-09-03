package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceDiscoveryTest {

  @TempDir Path settings;

  @Test
  void invokesTheStoredFileDirectlyThroughItsShebang() {
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    assertThat(
            store.save(
                """
                #!/usr/bin/awk -f
                BEGIN { print "local|Awk repository|/code/from-awk" }
                """))
        .isTrue();

    try (WorkspaceDiscovery discovery = new WorkspaceDiscovery(store)) {
      WorkspaceDiscovery.DiscoveryResult result = discovery.discover();

      assertThat(result.exitCode()).hasValue(0);
      assertThat(result.timedOut()).isFalse();
      assertThat(result.stderr()).isEmpty();
      assertThat(result.diagnostics()).isEmpty();
      assertThat(result.workspaces())
          .singleElement()
          .satisfies(
              workspace -> {
                assertThat(workspace.label()).isEqualTo("Awk repository");
                assertThat(workspace.workingDirectory()).isEqualTo("/code/from-awk");
                assertThat(workspace.bazelExecutable()).isEqualTo("bazel");
              });
    }
  }

  @Test
  void outputPrefixesAndNonzeroExitRemainVisibleWithExplicitDiagnostics() {
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    assertThat(
            store.save(
                """
                #!/bin/sh
                printf 'local|One|/one\nlocal|Two|/two\npartial-row-without-an-ending'
                printf 'abcdefghijklmnopqrstuvwxyz' >&2
                exit 7
                """))
        .isTrue();
    WorkspaceDiscovery discovery =
        new WorkspaceDiscovery(store, Duration.ofSeconds(2), 40, 8, Duration.ofMillis(250));

    WorkspaceDiscovery.DiscoveryResult result;
    try (discovery) {
      result = discovery.discover();
    }

    assertThat(result.workspaces())
        .extracting(WorkspaceProfile::label)
        .containsExactly("One", "Two");
    assertThat(result.exitCode()).hasValue(7);
    assertThat(result.timedOut()).isFalse();
    assertThat(result.stderr()).isEqualTo("abcdefgh");
    assertThat(result.diagnostics())
        .anySatisfy(message -> assertThat(message).contains("stdout exceeded 40 bytes"))
        .anySatisfy(message -> assertThat(message).contains("stderr exceeded 8 bytes"))
        .anySatisfy(message -> assertThat(message).contains("exited with status 7"));
  }

  @Test
  void timeoutStopsTheProcessTreeAndKeepsCompleteRows() {
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    assertThat(
            store.save(
                """
                #!/bin/sh
                printf 'local|Before timeout|/code/before-timeout\n'
                trap '' TERM
                while :; do sleep 1; done
                """))
        .isTrue();
    WorkspaceDiscovery discovery =
        new WorkspaceDiscovery(
            store,
            Duration.ofSeconds(1),
            WorkspaceDiscovery.MAX_STDOUT_BYTES,
            WorkspaceDiscovery.MAX_STDERR_BYTES,
            Duration.ofSeconds(1));

    long started = System.nanoTime();
    WorkspaceDiscovery.DiscoveryResult result;
    try (discovery) {
      result = discovery.discover();
    }
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(elapsedMillis).isLessThan(7_000);
    assertThat(result.timedOut()).isTrue();
    assertThat(result.workspaces())
        .extracting(WorkspaceProfile::label)
        .containsExactly("Before timeout");
    assertThat(result.diagnostics()).anySatisfy(message -> assertThat(message).contains("timeout"));
  }

  @Test
  void closeCancelsAnActiveInvocationAndPermanentlyClosesTheService() throws Exception {
    Path ready = settings.resolve("ready");
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    assertThat(
            store.save(
                """
                #!/bin/sh
                printf 'local|Before close|/code/before-close\n'
                : > %s
                trap '' TERM
                while :; do sleep 1; done
                """
                    .formatted(shellQuote(ready))))
        .isTrue();
    WorkspaceDiscovery discovery =
        new WorkspaceDiscovery(
            store,
            Duration.ofSeconds(30),
            WorkspaceDiscovery.MAX_STDOUT_BYTES,
            WorkspaceDiscovery.MAX_STDERR_BYTES,
            Duration.ofMillis(250));
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Future<WorkspaceDiscovery.DiscoveryResult> pending = worker.submit(discovery::discover);
      assertThat(waitForFile(ready, Duration.ofSeconds(3))).isTrue();

      WorkspaceDiscovery.DiscoveryResult overlapping = discovery.discover();
      assertThat(overlapping.workspaces()).isEmpty();
      assertThat(overlapping.diagnostics())
          .containsExactly("Workspace discovery is already running.");

      discovery.close();
      discovery.close();
      WorkspaceDiscovery.DiscoveryResult cancelled = pending.get(3, TimeUnit.SECONDS);

      assertThat(cancelled.timedOut()).isFalse();
      assertThat(cancelled.workspaces())
          .extracting(WorkspaceProfile::label)
          .containsExactly("Before close");
      assertThat(cancelled.diagnostics())
          .anySatisfy(message -> assertThat(message).contains("owner closed"));

      WorkspaceDiscovery.DiscoveryResult afterClose = discovery.discover();
      assertThat(afterClose.workspaces()).isEmpty();
      assertThat(afterClose.diagnostics())
          .containsExactly("Workspace discovery is closed; create a new instance to run it again.");
    } finally {
      discovery.close();
      worker.shutdownNow();
    }
  }

  @Test
  void everyInvocationReturnsFreshImmutableListsAndEdtUseIsRejected() throws Exception {
    WorkspaceDiscovery discovery =
        new WorkspaceDiscovery(new WorkspaceDiscoveryScriptStore(settings));
    try (discovery) {
      WorkspaceDiscovery.DiscoveryResult first = discovery.discover();
      WorkspaceDiscovery.DiscoveryResult second = discovery.discover();

      assertThat(first.workspaces()).isNotSameAs(second.workspaces());
      assertThat(first.diagnostics()).isNotSameAs(second.diagnostics());
      assertThatThrownBy(
              () -> first.workspaces().add(WorkspaceProfile.local("Ignored", "/ignored", "bazel")))
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> first.diagnostics().add("ignored"))
          .isInstanceOf(UnsupportedOperationException.class);

      SwingUtilities.invokeAndWait(
          () ->
              assertThatThrownBy(discovery::discover)
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("EDT"));
    }
  }

  private static boolean waitForFile(Path file, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(file)) {
        return true;
      }
      Thread.sleep(10);
    }
    return Files.exists(file);
  }

  private static String shellQuote(Path path) {
    return "'" + path.toString().replace("'", "'\"'\"'") + "'";
  }
}
