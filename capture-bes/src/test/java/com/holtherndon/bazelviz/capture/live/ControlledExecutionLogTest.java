package com.holtherndon.bazelviz.capture.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.CapabilityStatus;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.Overhead;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filesystem-only provenance checks; remote=true uses local fixtures, never SSH or Bazel. */
class ControlledExecutionLogTest {
  @TempDir Path temporary;
  private final LocalExecutionFileSystem files = new LocalExecutionFileSystem();

  @Test
  void freshLocalCaptureProducesAReceiptOnlyAfterTheFileExists() throws Exception {
    Fixture fixture = fixture(false);
    ControlledExecutionLog log = fixture.prepare(files);
    log.requireFresh();
    assertTrue(log.matches(fixture.source().toString()));
    assertFalse(log.matches(fixture.source() + ".other"));
    assertThrows(IOException.class, log::receipt);
    Files.writeString(fixture.source(), "captured log");
    assertEquals(
        new CaptureCoordinator.ExecutionLogReceipt(fixture.source(), fixture.local()),
        log.receipt());
    assertThrows(IOException.class, log::requireFresh);
  }

  @Test
  void freshRemoteCaptureRequiresSuccessfulPreservationAndRetainsItsPlannedPaths()
      throws Exception {
    Fixture fixture = fixture(true);
    ControlledExecutionLog log = fixture.prepare(files);
    Files.writeString(fixture.source(), "captured log");
    assertThrows(IOException.class, log::receipt);
    log.download(100);
    assertEquals("captured log", Files.readString(fixture.local()));
    // Capture finalization may remove staging after the transfer, before reading the receipt.
    Files.delete(fixture.source());
    Files.delete(fixture.executionRaw());
    assertEquals(
        new CaptureCoordinator.ExecutionLogReceipt(fixture.source(), fixture.local()),
        log.receipt());
  }

  @Test
  void existingFilesAndDanglingSymlinksCannotQualifyAsFreshPaths() throws Exception {
    for (boolean source : List.of(false, true)) {
      for (boolean symlink : List.of(false, true)) {
        Fixture fixture = fixture(true);
        Path existing = source ? fixture.source() : fixture.local();
        if (symlink) {
          Files.createSymbolicLink(existing, temporary.resolve("missing-target"));
        } else {
          Files.writeString(existing, "prior evidence");
        }
        assertThrows(IOException.class, () -> fixture.prepare(files));
      }
    }
  }

  @Test
  void unavailableOrInaccessibleMetadataIsNotProofOfAbsence() throws Exception {
    for (boolean unavailable : List.of(false, true)) {
      Fixture fixture = fixture(true);
      ExecutionFileSystem uncertain =
          intercept(
              "stat",
              arguments ->
                  unavailable
                      ? FileMetadata.unavailable((ExecutionPath) arguments[0], "unknown")
                      : FileMetadata.inaccessible((ExecutionPath) arguments[0], "denied"));
      assertThrows(IOException.class, () -> fixture.prepare(uncertain));
    }
  }

  @Test
  void binaryUserOwnedAndDuplicateLogArgumentsAreRejected() throws Exception {
    Fixture fixture = fixture(true);
    AddedFlag flag = compactFlag(fixture.source());
    List<InstrumentationPlan> rejected =
        List.of(
            plan(
                fixture.source(),
                List.of("--execution_log_binary_file=" + fixture.source()),
                List.of(flag)),
            plan(fixture.source(), List.of(flag.argv()), List.of()),
            plan(fixture.source(), List.of(flag.argv(), flag.argv()), List.of(flag)),
            plan(fixture.source(), List.of(flag.argv()), List.of(flag, flag)),
            plan(
                fixture.source(),
                List.of("--execution_log_compact_file=/user/log"),
                List.of(flag)));
    for (InstrumentationPlan plan : rejected) {
      assertThrows(
          IOException.class,
          () ->
              ControlledExecutionLog.prepare(
                  plan, fixture.executionRaw(), fixture.localRaw(), files, true));
    }
  }

  @Test
  void missingEmptyAndOversizedTransfersNeverProduceAReceipt() throws Exception {
    for (String contents : List.of("", "too large")) {
      Fixture fixture = fixture(true);
      ControlledExecutionLog log = fixture.prepare(files);
      assertThrows(IOException.class, () -> log.download(1));
      assertThrows(IOException.class, log::receipt);
      Files.writeString(fixture.source(), contents);
      assertThrows(IOException.class, () -> log.download(1));
      assertThrows(IOException.class, log::receipt);
    }
    Fixture local = fixture(false);
    ControlledExecutionLog log = local.prepare(files);
    Files.createFile(local.source());
    assertThrows(IOException.class, log::receipt);
  }

  @Test
  void failedTransferCannotAttestAPartialDestination() throws Exception {
    for (boolean partial : List.of(false, true)) {
      Fixture fixture = fixture(true);
      ExecutionFileSystem failing =
          intercept(
              "download",
              arguments -> {
                if (partial) {
                  // Even a misbehaving transport that leaves a partial file cannot earn a receipt.
                  Files.writeString((Path) arguments[1], "partial");
                }
                throw new IOException("transfer interrupted");
              });
      ControlledExecutionLog log = fixture.prepare(failing);
      Files.writeString(fixture.source(), "complete log");
      assertThrows(IOException.class, () -> log.download(100));
      assertThrows(IOException.class, log::receipt);
      assertEquals(partial, Files.exists(fixture.local()));
    }
  }

  @Test
  void aTransferReturningWithoutAFileDoesNotProduceAReceipt() throws Exception {
    Fixture fixture = fixture(true);
    ControlledExecutionLog log = fixture.prepare(intercept("download", arguments -> null));
    Files.writeString(fixture.source(), "complete log");
    assertThrows(IOException.class, () -> log.download(100));
    assertThrows(IOException.class, log::receipt);
  }

  @Test
  void symlinksCreatedAfterPreparationCannotBeDownloadedOrReceipted() throws Exception {
    Path target = Files.writeString(temporary.resolve("target"), "not the planned log");
    Fixture remote = fixture(true);
    ControlledExecutionLog remoteLog = remote.prepare(files);
    Files.createSymbolicLink(remote.source(), target);
    assertThrows(IOException.class, () -> remoteLog.download(100));
    assertThrows(IOException.class, remoteLog::receipt);

    Fixture local = fixture(false);
    ControlledExecutionLog localLog = local.prepare(files);
    Files.createSymbolicLink(local.source(), target);
    assertThrows(IOException.class, localLog::receipt);
  }

  @Test
  void redirectedSourceOrDestinationDirectoriesAreRefusedAfterPreparation() throws Exception {
    for (boolean source : List.of(false, true)) {
      Fixture fixture = fixture(true);
      ControlledExecutionLog log = fixture.prepare(files);
      Path parent = source ? fixture.executionRaw() : fixture.localRaw();
      redirect(parent);
      Files.writeString(fixture.source(), "log");
      assertThrows(IOException.class, log::requireFresh);
      assertThrows(IOException.class, () -> log.download(100));
      assertThrows(IOException.class, log::receipt);
    }
    Fixture local = fixture(false);
    ControlledExecutionLog log = local.prepare(files);
    redirect(local.localRaw());
    Files.writeString(local.local(), "redirected log");
    assertThrows(IOException.class, log::receipt);
  }

  private void redirect(Path directory) throws IOException {
    Path replacement = Files.createTempDirectory(directory.getParent(), "replacement-");
    Files.move(directory, directory.resolveSibling(directory.getFileName() + "-original"));
    Files.createSymbolicLink(directory, replacement);
  }

  private Fixture fixture(boolean remote) throws IOException {
    Path root = Files.createTempDirectory(temporary, "capture-").toRealPath();
    Path execution = Files.createDirectory(root.resolve("execution"));
    return new Fixture(
        execution, remote ? Files.createDirectory(root.resolve("local")) : execution, remote);
  }

  private record Fixture(Path executionRaw, Path localRaw, boolean remote) {
    Path source() {
      return executionRaw.resolve(InstrumentationPlanner.EXECUTION_LOG_FILE);
    }

    Path local() {
      return localRaw.resolve(InstrumentationPlanner.EXECUTION_LOG_FILE);
    }

    ControlledExecutionLog prepare(ExecutionFileSystem files) throws IOException {
      AddedFlag flag = compactFlag(source());
      return ControlledExecutionLog.prepare(
          plan(source(), List.of(flag.argv()), List.of(flag)),
          executionRaw,
          localRaw,
          files,
          remote);
    }
  }

  private static AddedFlag compactFlag(Path source) {
    return new AddedFlag(
        "--execution_log_compact_file=" + source,
        AddedFlag.Placement.COMMAND,
        Capability.EXECUTION_LOG_COMPACT,
        CapabilityStatus.SUPPORTED,
        "capture execution evidence",
        DataSource.EXECUTION_LOG,
        Overhead.MEDIUM,
        Optional.of(source),
        true,
        true);
  }

  private static InstrumentationPlan plan(Path source, List<String> args, List<AddedFlag> added) {
    BazelCommand original =
        BazelCommand.builder(Path.of("/unused/bazel"), source.getParent()).command("build").build();
    return new InstrumentationPlan(
        original,
        original.toBuilder().commandArgs(args).build(),
        added,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(source),
        new SourceAvailability(Map.of()),
        CapturePreset.PERFORMANCE_DIAGNOSTICS);
  }

  @FunctionalInterface
  private interface FileOperation {
    Object invoke(Object[] arguments) throws IOException;
  }

  /** Injects one filesystem failure while preserving the real local path and metadata behavior. */
  private ExecutionFileSystem intercept(String operation, FileOperation replacement) {
    return (ExecutionFileSystem)
        Proxy.newProxyInstance(
            ExecutionFileSystem.class.getClassLoader(),
            new Class<?>[] {ExecutionFileSystem.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals(operation)) {
                return replacement.invoke(arguments);
              }
              try {
                return method.invoke(files, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
