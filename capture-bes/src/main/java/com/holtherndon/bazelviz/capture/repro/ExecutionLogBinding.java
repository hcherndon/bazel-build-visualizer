package com.holtherndon.bazelviz.capture.repro;

import com.holtherndon.bazelviz.capture.live.CaptureCoordinator.ExecutionLogReceipt;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison.Verification;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.repro.ReproducibilityPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Binding policy for live, app-controlled evidence; never authorizes an imported log or command.
 */
final class ExecutionLogBinding {
  enum Identity {
    EMBEDDED_INVOCATION_ID,
    CAPTURE_BOUND_7_4;

    String notice(String version, String build) {
      return this == EMBEDDED_INVOCATION_ID
          ? "Build " + build + ": the compact log's embedded invocation ID matches its BES capture."
          : "Build "
              + build
              + ": capture-bound evidence for Bazel "
              + version
              + ": the compact log has no embedded invocation ID. Its fresh private path,"
              + " successful complete BES capture, successful preservation and SHA-256 bind it to"
              + " this build; an embedded identity was not verified.";
    }
  }

  private ExecutionLogBinding() {}

  static Path plannedPath(InstrumentationPlan plan) throws IOException {
    var flags =
        plan.appliedFlags().stream()
            .filter(flag -> flag.capability() == Capability.EXECUTION_LOG_COMPACT)
            .toList();
    if (flags.size() != 1 || flags.getFirst().writesFile().isEmpty()) {
      throw new IOException("Both builds require an app-injected compact execution-log output.");
    }
    Path path = flags.getFirst().writesFile().orElseThrow();
    String expectedFlag = "--execution_log_compact_file=" + path;
    List<String> logArgs =
        plan.effective().commandArgs().stream()
            .filter(ExecutionLogBinding::isOutputArgument)
            .toList();
    if (!path.isAbsolute()
        || !path.normalize().equals(path)
        || !path.getFileName().toString().equals(InstrumentationPlanner.EXECUTION_LOG_FILE)
        || !flags.getFirst().argv().equals(expectedFlag)
        || !logArgs.equals(List.of(expectedFlag))
        || plan.original().commandArgs().stream().anyMatch(ExecutionLogBinding::isOutputArgument)
        || plan.expectedOutputs().stream().filter(path::equals).count() != 1) {
      throw new IOException(
          "The managed execution-log output does not match its reviewed injected flag.");
    }
    return path;
  }

  private static boolean isOutputArgument(String argument) {
    return List.of(
            "--execution_log_compact_file",
            "--experimental_execution_log_compact_file",
            "--execution_log_binary_file",
            "--execution_log_json_file")
        .contains(argument.split("=", 2)[0]);
  }

  static String singleInvocationId(CaptureResult capture) throws IOException {
    if (!capture.buildSucceeded() || !capture.captureComplete() || capture.wasCancelled()) {
      throw new IOException("Execution-log binding requires a successful, complete build capture.");
    }
    List<String> identities =
        capture.capture().orElseThrow().streams().stream()
            .map(stream -> stream.key().invocationId())
            .distinct()
            .toList();
    if (identities.size() != 1 || identities.getFirst().isBlank()) {
      throw new IOException("The build did not establish one unambiguous BES invocation identity.");
    }
    return identities.getFirst();
  }

  static Identity verify(
      String version,
      CaptureResult capture,
      Optional<ExecutionLogReceipt> receipt,
      Verification verified,
      Optional<ExecutionLogReceipt> previous)
      throws IOException {
    String invocation = singleInvocationId(capture);
    if (!ReproducibilityPlan.supportsVersion(version)) {
      throw new IOException(
          "The reviewed Bazel version does not support managed evidence binding.");
    }
    ExecutionLogReceipt preserved =
        receipt.orElseThrow(
            () ->
                new IOException(
                    "No fresh, successfully preserved controlled execution-log receipt is"
                        + " available."));
    Path local =
        ReproducibilityCoordinator.executionLogPath(capture)
            .orElseThrow(
                () -> new IOException("The managed execution-log destination is ambiguous."));
    if (!preserved.executionPath().equals(plannedPath(capture.plan()))
        || !preserved.localPath().equals(local)
        || !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)
        || Files.size(local) == 0
        || !verified.sha256().matches("[0-9a-f]{64}")
        || previous
            .filter(
                old ->
                    old.executionPath().equals(preserved.executionPath())
                        || old.localPath().equals(preserved.localPath()))
            .isPresent()) {
      throw new IOException(
          "The preserved execution log is not at a distinct verified managed path.");
    }
    if (!verified.compactFormat() || !verified.invocationHeaderPresent()) {
      throw new IOException("Managed evidence requires a complete compact-log invocation header.");
    }
    if (verified.invocationMatched()
        && verified.embeddedInvocationId().filter(invocation::equals).isPresent()) {
      return Identity.EMBEDDED_INVOCATION_ID;
    }
    if (verified.embeddedInvocationId().isPresent()) {
      throw new IOException("The embedded execution-log invocation ID does not match this build.");
    }
    if (ReproducibilityPlan.allowsCaptureBoundIdentity(version)) {
      return Identity.CAPTURE_BOUND_7_4;
    }
    throw new IOException("This Bazel release requires an embedded execution-log invocation ID.");
  }
}
