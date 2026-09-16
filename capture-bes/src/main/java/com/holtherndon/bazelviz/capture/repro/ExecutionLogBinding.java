package com.holtherndon.bazelviz.capture.repro;

import com.holtherndon.bazelviz.capture.live.CaptureCoordinator.ExecutionLogReceipt;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison.Verification;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.CapabilityStatus;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
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

  /** One actionable cause, not a second generic message for the same missing evidence. */
  static Optional<String> reviewBlocker(PlanRequest request, InstrumentationPlan plan) {
    SourceAvailability.Entry source = plan.sourceAvailability().entry(DataSource.EXECUTION_LOG);
    try {
      plannedPath(plan);
      if (!plan.sourceAvailability().isPlanned(DataSource.EXECUTION_LOG)) {
        throw new IOException(
            "Execution-log source availability is " + source.availability() + ", not PLANNED.");
      }
      return Optional.empty();
    } catch (IOException invalid) {
      if (request.vetoed().contains(Capability.EXECUTION_LOG_COMPACT)
          || request.vetoed().contains(Capability.EXECUTION_LOG_BINARY)) {
        return Optional.of(
            "Execution-log capture is turned off. Choose Enable execution logs to restore the"
                + " required log for both builds; the diagnostic cannot run without it.");
      }
      if (!request.preset().requestedCapabilities().contains(Capability.EXECUTION_LOG_COMPACT)) {
        return Optional.of(
            "The "
                + request.preset().displayName()
                + " capture preset does not request execution"
                + " logs. Cancel and start Hermeticity diagnostic again to use its required capture"
                + " settings.");
      }
      CapabilityStatus status = request.capabilities().status(Capability.EXECUTION_LOG_COMPACT);
      String probe =
          "Capability: " + status + "; probe: " + request.capabilities().detection() + ".";
      String warnings =
          request.capabilities().probeWarnings().isEmpty()
              ? ""
              : " Probe details: " + String.join("; ", request.capabilities().probeWarnings());
      if (status == CapabilityStatus.UNKNOWN) {
        return Optional.of(
            "Bazel's flag probe could not confirm support for --execution_log_compact_file. "
                + probe
                + warnings
                + " Cancel and retry after checking the selected Bazel executable and, for a remote"
                + " workspace, its SSH connection. Do not add a log flag manually.");
      }
      if (status == CapabilityStatus.UNSUPPORTED) {
        boolean binaryPlanned =
            plan.appliedFlags().stream()
                .anyMatch(flag -> flag.capability() == Capability.EXECUTION_LOG_BINARY);
        return Optional.of(
            "Bazel "
                + request.capabilities().bazelVersion().orElse("(version unknown)")
                + " did not report support for --execution_log_compact_file. "
                + probe
                + (binaryPlanned
                    ? " Only a binary execution log was planned; the managed diagnostic"
                        + " requires compact logs to bind each file to its build."
                    : "")
                + warnings
                + " Select a supported Bazel executable with compact-log support and retry.");
      }
      if (source.availability() == SourceAvailability.Availability.DECLINED
          && source.enabledBy().filter(ExecutionLogBinding::isOutputArgument).isPresent()) {
        return Optional.of(
            "Execution-log capture was not added: "
                + source.reason()
                + " ("
                + source.enabledBy().orElseThrow()
                + "). Remove that execution-log output option"
                + " from the command or its wrapper and retry. The diagnostic must choose its own"
                + " fresh private output files.");
      }
      return Optional.of(
          "Compact execution-log capture could not be prepared: "
              + invalid.getMessage()
              + " Capture plan: "
              + source.reason()
              + ". "
              + probe
              + " Cancel and restart the diagnostic. If this persists, report these capture-plan"
              + " details; do not add an execution-log output flag manually.");
    }
  }

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
