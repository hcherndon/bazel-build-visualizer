package com.holtherndon.bazelviz.capture.repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuditSafetyTest {
  @Test
  void unknownSshOutcomeSurvivesLocalJournalFailureAndRetainsPrivateBase(@TempDir Path temporary)
      throws Exception {
    var request = idleRequest(temporary);
    var audit =
        new ReproducibilityCoordinator(request, temporary.resolve("audits"), () -> {}, step -> {});
    AuditJournal journal = new AuditJournal(temporary.resolve("audits"));
    OwnedAuditBase base =
        OwnedAuditBase.allocate(
            new LocalExecutionFileSystem(),
            LocalCommandExecutor.INSTANCE,
            UUID.randomUUID().toString(),
            directory -> {});
    setField(audit, "journal", journal);
    setField(audit, "owned", base);
    setField(audit, "cleanup", ReproducibilityCoordinator.Cleanup.PENDING);
    var command =
        BazelCommand.builder(Path.of("/usr/bin/bazel"), temporary).command("build").build();
    var plan =
        new InstrumentationPlan(
            command,
            command,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new SourceAvailability(Map.of()),
            CapturePreset.defaultPreset());
    Path session = Files.createDirectories(temporary.resolve("incomplete-session"));
    var result =
        new CaptureResult(
            session,
            SessionId.random(),
            SessionState.INCOMPLETE,
            plan,
            Optional.of(ProcessOutcome.exited(255, Duration.ZERO)),
            Optional.empty(),
            List.of());
    Path record = journal.directory().resolve("operation.properties");
    Path backup = journal.directory().resolve("operation.saved");
    Files.move(record, backup);
    Files.createDirectory(record);
    try {
      assertThatThrownBy(() -> audit.recordCaptureResult(result, "A"))
          .isInstanceOf(IOException.class);
      assertThat(getField(audit, "clientOutcomeUnknown")).isEqualTo(true);
      assertThat(getField(audit, "capturedA")).isSameAs(result);
      // Local storage recovers before close. That must not authorize remote cleanup retroactively.
      Files.delete(record);
      Files.move(backup, record);
      audit.close();
      assertThat(Path.of(base.outputBase())).isDirectory();
      assertThat(ReproducibilityCoordinator.readSavedOperation(journal.directory()).cleanup())
          .isEqualTo("NEEDS_REVIEW");
    } finally {
      try {
        audit.close();
      } finally {
        if (Files.exists(Path.of(base.directory()))) {
          // This fixture never contacts Bazel: remove only its known empty, test-owned base.
          base.removeAfterShutdown();
        }
      }
    }
  }

  @Test
  void closeStillClosesBothCapturesAndRestoresInterruptWhenJournalWritingFails(
      @TempDir Path temporary) throws Exception {
    CaptureRequest request = idleRequest(temporary);
    CaptureCoordinator first = new CaptureCoordinator(request);
    CaptureCoordinator second = new CaptureCoordinator(request);
    AtomicInteger released = new AtomicInteger();
    var audit =
        new ReproducibilityCoordinator(
            request, temporary.resolve("audits"), released::incrementAndGet, step -> {});
    AuditJournal journal = new AuditJournal(temporary.resolve("audits"));
    // Preserve the original record, then make publishing its replacement fail deterministically.
    Files.move(
        journal.directory().resolve("operation.properties"),
        journal.directory().resolve("operation.saved"));
    Files.createDirectory(journal.directory().resolve("operation.properties"));
    setField(audit, "journal", journal);
    setField(audit, "a", first);
    setField(audit, "b", second);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(audit::close)
          .isInstanceOf(IOException.class)
          .satisfies(failure -> assertThat(failure.getSuppressed()).isNotEmpty());
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(getField(first, "closed")).isEqualTo(true);
      assertThat(getField(second, "closed")).isEqualTo(true);
      assertThat(released.get()).isEqualTo(1);
    } finally {
      Thread.interrupted();
      audit.close();
      first.close();
      second.close();
    }
  }

  @Test
  void aCloseFailureCannotSkipBOrLoseLaterCleanupFailures(@TempDir Path temporary)
      throws Exception {
    CaptureRequest request = idleRequest(temporary);
    RuntimeException firstFailure = new IllegalStateException("first capture close");
    RuntimeException secondFailure = new IllegalStateException("second capture close");
    CaptureCoordinator first = failingCloseCapture(temporary, firstFailure);
    CaptureCoordinator second = failingCloseCapture(temporary, secondFailure);
    IOException leaseFailure = new IOException("lease close");
    AtomicInteger released = new AtomicInteger();
    var audit =
        new ReproducibilityCoordinator(
            request,
            temporary.resolve("audits"),
            () -> {
              released.incrementAndGet();
              throw leaseFailure;
            },
            step -> {});
    setField(audit, "a", first);
    setField(audit, "b", second);
    assertThatThrownBy(audit::close)
        .isSameAs(firstFailure)
        .satisfies(
            failure -> {
              assertThat(failure.getSuppressed()).hasSize(2);
              assertThat(failure.getSuppressed()[0]).isSameAs(secondFailure);
              assertThat(failure.getSuppressed()[1].getCause()).isSameAs(leaseFailure);
            });
    assertThat(getField(first, "closed")).isEqualTo(true);
    assertThat(getField(second, "closed")).isEqualTo(true);
    assertThat(released.get()).isEqualTo(1);
    audit.close();
    assertThat(released.get()).isEqualTo(1);
  }

  private static CaptureRequest idleRequest(Path temporary) {
    return CaptureRequest.of(
        temporary.resolve("sessions"), "test", "bazel", temporary, List.of("build", "//:fixture"));
  }

  private static CaptureCoordinator failingCloseCapture(Path temporary, RuntimeException failure)
      throws Exception {
    var capture =
        new CaptureCoordinator(
            CaptureRequest.remote(
                temporary.resolve("sessions"),
                "test",
                "bazel",
                "/repository",
                List.of("build", "//:fixture"),
                SshTarget.of("unused-fixture")));
    // No SSH connection or deletion is performed: the injected executor throws on the cleanup call.
    var executor =
        (CommandExecutor)
            Proxy.newProxyInstance(
                CommandExecutor.class.getClassLoader(),
                new Class<?>[] {CommandExecutor.class},
                (proxy, method, arguments) -> {
                  throw failure;
                });
    setField(capture, "commandExecutor", executor);
    setField(capture, "remoteStagingDirectory", "/tmp/bbv-capture.TestState123");
    return capture;
  }

  private static void setField(Object owner, String name, Object value) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(owner, value);
  }

  private static Object getField(Object owner, String name) throws Exception {
    var field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  @Test
  void anOversizedRecordDoesNotPoisonLaterFailureAndCleanupState(@TempDir Path temporary)
      throws Exception {
    AuditJournal journal = new AuditJournal(temporary);
    journal.put("state", "RUNNING");
    assertThatThrownBy(
            () -> journal.put("failure", "x".repeat((int) AuditJournal.MAX_RECORD_BYTES)))
        .isInstanceOf(IOException.class);
    journal.put("cleanup", "NEEDS_REVIEW");
    assertThat(AuditJournal.read(journal.directory()))
        .containsEntry("state", "RUNNING")
        .containsEntry("cleanup", "NEEDS_REVIEW");
    assertThat(ReproducibilityCoordinator.readSavedOperation(journal.directory()).sessionA())
        .isEmpty();
  }

  @Test
  void sourceFingerprintTracksUntrackedBytesAndNamesButNotMtime(@TempDir Path temporary)
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Path file = Files.writeString(root.resolve("untracked.txt"), "one");
    var files = new LocalExecutionFileSystem();
    var first =
        RepositorySnapshot.capture(files, files.path(root), temporary.resolve("a"), () -> false);
    Files.writeString(file, "one");
    var same =
        RepositorySnapshot.capture(files, files.path(root), temporary.resolve("b"), () -> false);
    assertThat(first.fingerprint()).isEqualTo(same.fingerprint());
    Files.writeString(file, "two");
    var changed =
        RepositorySnapshot.capture(files, files.path(root), temporary.resolve("c"), () -> false);
    assertThat(first.fingerprint()).isNotEqualTo(changed.fingerprint());
    Files.move(file, root.resolve("renamed.txt"));
    var renamed =
        RepositorySnapshot.capture(files, files.path(root), temporary.resolve("d"), () -> false);
    assertThat(changed.fingerprint()).isNotEqualTo(renamed.fingerprint());
  }

  @Test
  void restartRecordKeepsFailureCleanupAndSourceEvidence(@TempDir Path temporary) throws Exception {
    AuditJournal journal = new AuditJournal(temporary);
    journal.put("state", "FAILED");
    journal.put("step", "PRESERVE_A");
    journal.put("failure", "Remote termination unknown");
    journal.put("cleanupFailure", "Kept private base for review");
    journal.put("source.before.fingerprint", "abc123");
    journal.put("source.before.entries", "12");
    journal.put("source.before.bytes", "900");
    var saved = ReproducibilityCoordinator.readSavedOperation(journal.directory());
    assertThat(saved.step()).isEqualTo("PRESERVE_A");
    assertThat(saved.notices())
        .containsExactly(
            "Audit failure: Remote termination unknown",
            "Cleanup failure: Kept private base for review",
            "Source check before: 12 entries, 900 bytes; fingerprint recorded.");
  }

  @Test
  void observedRemoteOrCachedExecutionCannotPassTheControlledProtocol() throws Exception {
    assertThat(ReproducibilityCoordinator.executionScopeNotes(0, 0, 0)).isEmpty();
    assertThat(ReproducibilityCoordinator.executionScopeNotes(0, 0, 3))
        .singleElement()
        .asString()
        .contains("not verified");
    assertThatThrownBy(() -> ReproducibilityCoordinator.executionScopeNotes(1, 0, 0))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("1 cached");
    assertThatThrownBy(() -> ReproducibilityCoordinator.executionScopeNotes(0, 2, 0))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("2 remote-execution");
  }

  @Test
  void operationReaderRefusesDirectoriesSymlinksAndPipes(@TempDir Path temporary) throws Exception {
    Path record = temporary.resolve("operation.properties");
    Files.createDirectory(record);
    assertThatThrownBy(() -> AuditJournal.read(temporary))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("regular file");
    Files.delete(record);
    Path target = Files.writeString(temporary.resolve("other.properties"), "format=1\n");
    Files.createSymbolicLink(record, target);
    assertThatThrownBy(() -> AuditJournal.read(temporary))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("regular file");
    Files.delete(record);
    Process makeFifo = new ProcessBuilder("mkfifo", record.toString()).start();
    assertThat(makeFifo.waitFor()).isZero();
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            assertThatThrownBy(() -> AuditJournal.read(temporary))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("regular file"));
  }

  @Test
  void excludesKnownConvenienceLinksButRefusesOtherSourceSymlinks(@TempDir Path temporary)
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    Path outside = Files.createDirectory(temporary.resolve("outside"));
    Files.createSymbolicLink(root.resolve("bazel-bin"), outside);
    Files.createSymbolicLink(root.resolve("bazel-workspace"), outside);
    var files = new LocalExecutionFileSystem();
    var snapshot =
        RepositorySnapshot.capture(files, files.path(root), temporary.resolve("a"), () -> false);
    assertThat(snapshot.files()).isZero();
    Files.createSymbolicLink(root.resolve("bazel-source"), outside);
    assertThatThrownBy(
            () ->
                RepositorySnapshot.capture(
                    files, files.path(root), temporary.resolve("b"), () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("symlink");
  }

  @Test
  void sourceReadRefusesSizeAndCancellationRatherThanDroppingRecords(@TempDir Path temporary)
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("workspace"));
    try (var channel =
        FileChannel.open(
            root.resolve("large"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(RepositorySnapshot.MAX_FILE_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {1}));
    }
    var files = new LocalExecutionFileSystem();
    assertThatThrownBy(
            () ->
                RepositorySnapshot.capture(
                    files, files.path(root), temporary.resolve("a"), () -> false))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cannot bound");
    assertThatThrownBy(
            () ->
                RepositorySnapshot.capture(
                    files, files.path(root), temporary.resolve("b"), () -> true))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("cancelled");
  }

  @Test
  void outputBaseCanOnlyBeRemovedWithItsOriginalMarker() throws Exception {
    var files = new LocalExecutionFileSystem();
    AtomicReference<String> allocated = new AtomicReference<>();
    String marker = UUID.randomUUID().toString();
    OwnedAuditBase base =
        OwnedAuditBase.allocate(files, LocalCommandExecutor.INSTANCE, marker, allocated::set);
    Path owner = Path.of(base.directory()).resolve("owner");
    try {
      assertThat(allocated).hasValue(base.directory());
      Files.writeString(owner, "changed");
      assertThatThrownBy(base::removeAfterShutdown)
          .isInstanceOf(IOException.class)
          .hasMessageContaining("marker");
      assertThat(Files.exists(Path.of(base.outputBase()))).isTrue();
    } finally {
      Files.writeString(owner, marker);
      base.removeAfterShutdown();
    }
    assertThat(Files.exists(Path.of(base.directory()))).isFalse();
  }
}
