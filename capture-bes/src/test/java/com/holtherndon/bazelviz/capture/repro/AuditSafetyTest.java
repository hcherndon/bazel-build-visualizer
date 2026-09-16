package com.holtherndon.bazelviz.capture.repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.holtherndon.bazelviz.capture.bes.BesStreamKey;
import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureCoordinator.ExecutionLogReceipt;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.CaptureSummary;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison.Verification;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.CapabilityStatus;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.Overhead;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AuditSafetyTest {
  @Test
  void defaultLocalAndRemoteCaptureDoNotInventMissingLogBlockers(@TempDir Path temporary) {
    PlanRequest request =
        reviewRequest(temporary, reviewCapabilities(true), CapturePreset.defaultPreset());
    for (PlanRequest variant : List.of(request, request.withRemoteDestinations())) {
      assertThat(
              ExecutionLogBinding.reviewBlocker(
                  variant, new InstrumentationPlanner().plan(variant)))
          .isEmpty();
    }
  }

  @Test
  void validCompactFlagCannotOverrideUnplannedOrMissingSourceMetadata(@TempDir Path temporary)
      throws Exception {
    PlanRequest request =
        reviewRequest(temporary, reviewCapabilities(true), CapturePreset.defaultPreset());
    InstrumentationPlan planned = new InstrumentationPlanner().plan(request);
    List<SourceAvailability> unavailable = new ArrayList<>();
    unavailable.add(new SourceAvailability(Map.of()));
    for (SourceAvailability.Availability state :
        List.of(
            SourceAvailability.Availability.UNKNOWN,
            SourceAvailability.Availability.UNAVAILABLE,
            SourceAvailability.Availability.DECLINED)) {
      unavailable.add(
          new SourceAvailability(
              Map.of(
                  DataSource.EXECUTION_LOG,
                  new SourceAvailability.Entry(state, "test source " + state, Optional.empty()))));
    }
    for (SourceAvailability source : unavailable) {
      InstrumentationPlan inconsistent =
          new InstrumentationPlan(
              planned.original(),
              planned.effective(),
              planned.addedFlags(),
              planned.replacedFlags(),
              planned.auxiliaryCommands(),
              planned.conflicts(),
              planned.warnings(),
              planned.errors(),
              planned.expectedOutputs(),
              source,
              planned.preset());
      assertThat(ExecutionLogBinding.plannedPath(inconsistent))
          .isEqualTo(ExecutionLogBinding.plannedPath(planned));
      assertThat(ExecutionLogBinding.reviewBlocker(request, inconsistent))
          .hasValueSatisfying(
              message ->
                  assertThat(message)
                      .contains("not PLANNED", source.entry(DataSource.EXECUTION_LOG).reason()));
    }
  }

  @Test
  void reviewExplainsVetoAndDoesNotRepeatGenericMissingLogMessage(@TempDir Path temporary) {
    PlanRequest request =
        reviewRequest(temporary, reviewCapabilities(true), CapturePreset.defaultPreset());
    for (Capability veto :
        List.of(Capability.EXECUTION_LOG_COMPACT, Capability.EXECUTION_LOG_BINARY)) {
      PlanRequest disabled = request.vetoing(veto);
      assertThat(
              ExecutionLogBinding.reviewBlocker(
                  disabled, new InstrumentationPlanner().plan(disabled)))
          .hasValueSatisfying(
              message ->
                  assertThat(message)
                      .contains("turned off", "Enable execution logs")
                      .doesNotContain("app-injected", "UNSUPPORTED"));
      PlanRequest restored =
          disabled
              .enabling(Capability.EXECUTION_LOG_COMPACT)
              .enabling(Capability.EXECUTION_LOG_BINARY);
      assertThat(
              ExecutionLogBinding.reviewBlocker(
                  restored, new InstrumentationPlanner().plan(restored)))
          .isEmpty();
    }
  }

  @Test
  void reviewDistinguishesProbeFailureFromConfirmedUnsupportedBinaryFallback(
      @TempDir Path temporary) {
    PlanRequest unknown =
        reviewRequest(
            temporary,
            BazelCapabilities.unprobed("SSH probe timed out"),
            CapturePreset.defaultPreset());
    assertThat(
            ExecutionLogBinding.reviewBlocker(unknown, new InstrumentationPlanner().plan(unknown)))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .contains("UNKNOWN", "probe: NONE", "SSH probe timed out", "Cancel and retry")
                    .doesNotContain("turned off", "UNSUPPORTED"));

    PlanRequest binary =
        reviewRequest(temporary, reviewCapabilities(false), CapturePreset.defaultPreset());
    assertThat(ExecutionLogBinding.reviewBlocker(binary, new InstrumentationPlanner().plan(binary)))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .contains(
                        "Bazel 7.4.1",
                        "UNSUPPORTED",
                        "FLAGS_PROTO",
                        "Only a binary execution log",
                        "Select a supported Bazel executable")
                    .doesNotContain("turned off", "Enable execution logs"));
  }

  @Test
  void reviewIdentifiesPresetAndExistingOutputOptionSeparately(@TempDir Path temporary) {
    PlanRequest omitted =
        reviewRequest(temporary, reviewCapabilities(true), CapturePreset.LIVE_ESSENTIALS);
    assertThat(
            ExecutionLogBinding.reviewBlocker(omitted, new InstrumentationPlanner().plan(omitted)))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .contains(
                        "Live Essentials", "does not request", "start Hermeticity diagnostic"));

    PlanRequest existing =
        reviewRequest(temporary, reviewCapabilities(true), CapturePreset.defaultPreset())
            .withEffectiveOptions(
                Optional.of(List.of("--execution_log_compact_file=/wrapper/log.zst")));
    assertThat(
            ExecutionLogBinding.reviewBlocker(
                existing, new InstrumentationPlanner().plan(existing)))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .contains(
                        "your command already writes an execution log",
                        "--execution_log_compact_file",
                        "Remove that execution-log output option",
                        "wrapper")
                    .doesNotContain("UNSUPPORTED", "Enable execution logs"));
  }

  @Test
  void reviewFlagsAnUnexpectedMalformedPlanWithoutBlamingTheUser(@TempDir Path temporary)
      throws Exception {
    CaptureResult capture = bindingCapture(temporary);
    InstrumentationPlan malformed =
        bindingPlan(
            capture.plan().original(),
            capture.plan().effective(),
            List.of(),
            capture.plan().expectedOutputs());
    PlanRequest request =
        reviewRequest(temporary, reviewCapabilities(true), CapturePreset.defaultPreset());
    assertThat(ExecutionLogBinding.reviewBlocker(request, malformed))
        .hasValueSatisfying(
            message ->
                assertThat(message)
                    .contains(
                        "could not be prepared",
                        "SUPPORTED",
                        "report these capture-plan details",
                        "do not add an execution-log output flag manually")
                    .doesNotContain("turned off"));
  }

  private static PlanRequest reviewRequest(
      Path temporary, BazelCapabilities capabilities, CapturePreset preset) {
    return PlanRequest.initial(
            BazelCommand.builder(Path.of("/bazel"), temporary).command("build").build(),
            capabilities,
            preset,
            temporary.resolve("pending-raw"),
            Optional.of("grpc://127.0.0.1:12345"))
        .withEffectiveOptions(Optional.of(List.of()));
  }

  private static BazelCapabilities reviewCapabilities(boolean compact) {
    Map<String, FlagSpec> flags = new LinkedHashMap<>();
    for (String name : List.of("bes_backend", "execution_log_binary_file")) {
      flags.put(name, FlagSpec.of(name, Set.of("build")));
    }
    if (compact) {
      flags.put(
          "execution_log_compact_file", FlagSpec.of("execution_log_compact_file", Set.of("build")));
    }
    return BazelCapabilities.fromFlags(
        "bazel 7.4.1",
        Optional.of("7.4.1"),
        BazelCapabilities.DetectionMethod.FLAGS_PROTO,
        flags,
        List.of());
  }

  @Test
  void cleanAndShutdownRequireTheExactSupportedReviewedVersion() throws Exception {
    for (String version : List.of("7.4.0", "7.4.1", "9.2.0")) {
      ReproducibilityCoordinator.requireReviewedVersion(
          version, "Bazelisk version: v1.27.0\nbazel " + version + "\n");
      for (String reported :
          List.of(
              "bazel 7.4.2",
              "bazel 9.2.1",
              "bazel " + version + "-fork",
              "bazel " + version + "\nbazel " + version,
              "unknown")) {
        assertThatThrownBy(
                () -> ReproducibilityCoordinator.requireReviewedVersion(version, reported))
            .isInstanceOf(IOException.class);
      }
    }
    assertThatThrownBy(
            () -> ReproducibilityCoordinator.requireReviewedVersion("7.4.0rc1", "bazel 7.4.0rc1"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void missingEmbeddedIdentityUsesOnly74CompactCaptureBoundEvidence(@TempDir Path temporary)
      throws Exception {
    CaptureResult capture = bindingCapture(temporary);
    Optional<ExecutionLogReceipt> receipt = Optional.of(receipt(capture));
    Verification legacy = verification(true, true, Optional.empty(), false);
    for (String version : List.of("7.4.0", "7.4.1")) {
      var binding = ExecutionLogBinding.verify(version, capture, receipt, legacy, Optional.empty());
      assertThat(binding).isEqualTo(ExecutionLogBinding.Identity.CAPTURE_BOUND_7_4);
      assertThat(binding.notice(version, "A"))
          .contains("capture-bound", "no embedded invocation ID");
    }
    for (String version : List.of("9.2.0", "7.3.2", "7.4.0rc1")) {
      assertThatThrownBy(
              () -> ExecutionLogBinding.verify(version, capture, receipt, legacy, Optional.empty()))
          .isInstanceOf(IOException.class);
    }
    for (Verification unsupported :
        List.of(
            verification(false, false, Optional.empty(), false),
            verification(true, false, Optional.empty(), false),
            verification(true, true, Optional.of("wrong-invocation"), false))) {
      assertThatThrownBy(
              () ->
                  ExecutionLogBinding.verify(
                      "7.4.1", capture, receipt, unsupported, Optional.empty()))
          .isInstanceOf(IOException.class);
    }
    for (String version : List.of("9.2.0", "7.4.1")) {
      assertThat(
              ExecutionLogBinding.verify(
                  version,
                  capture,
                  receipt,
                  verification(true, true, Optional.of("invocation"), true),
                  Optional.empty()))
          .isEqualTo(ExecutionLogBinding.Identity.EMBEDDED_INVOCATION_ID);
    }
  }

  @Test
  void bindingRequiresMatchingFreshReceiptAndDistinctRunPaths(@TempDir Path temporary)
      throws Exception {
    CaptureResult capture = bindingCapture(temporary);
    Verification legacy = verification(true, true, Optional.empty(), false);
    ExecutionLogReceipt receipt = receipt(capture);
    assertThatThrownBy(
            () ->
                ExecutionLogBinding.verify(
                    "7.4.1", capture, Optional.empty(), legacy, Optional.empty()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("receipt");
    for (ExecutionLogReceipt wrong :
        List.of(
            new ExecutionLogReceipt(temporary.resolve("other-source"), receipt.localPath()),
            new ExecutionLogReceipt(receipt.executionPath(), temporary.resolve("other-local")))) {
      assertThatThrownBy(
              () ->
                  ExecutionLogBinding.verify(
                      "7.4.1", capture, Optional.of(wrong), legacy, Optional.empty()))
          .isInstanceOf(IOException.class);
    }
    for (ExecutionLogReceipt previous :
        List.of(
            receipt,
            new ExecutionLogReceipt(receipt.executionPath(), temporary.resolve("previous-local")),
            new ExecutionLogReceipt(temporary.resolve("previous-source"), receipt.localPath()))) {
      assertThatThrownBy(
              () ->
                  ExecutionLogBinding.verify(
                      "7.4.1", capture, Optional.of(receipt), legacy, Optional.of(previous)))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("distinct");
    }
    Path saved = temporary.resolve("saved-log");
    Files.move(receipt.localPath(), saved);
    Files.createSymbolicLink(receipt.localPath(), saved);
    assertThatThrownBy(
            () ->
                ExecutionLogBinding.verify(
                    "7.4.1", capture, Optional.of(receipt), legacy, Optional.empty()))
        .isInstanceOf(IOException.class);
    Files.delete(receipt.localPath());
    assertThatThrownBy(
            () ->
                ExecutionLogBinding.verify(
                    "7.4.1", capture, Optional.of(receipt), legacy, Optional.empty()))
        .isInstanceOf(IOException.class);
  }

  @Test
  void bindingRejectsUnreviewedOrUserOwnedLogFlags(@TempDir Path temporary) throws Exception {
    InstrumentationPlan plan = bindingCapture(temporary).plan();
    assertThat(ExecutionLogBinding.plannedPath(plan)).isEqualTo(plan.expectedOutputs().getFirst());
    for (InstrumentationPlan invalid :
        List.of(
            bindingPlan(
                plan.original().toBuilder().commandArgs(plan.effective().commandArgs()).build(),
                plan.effective(),
                plan.addedFlags(),
                plan.expectedOutputs()),
            bindingPlan(
                plan.original(),
                plan.effective().toBuilder()
                    .commandArgs(List.of("--execution_log_compact_file=/stale/log.zst"))
                    .build(),
                plan.addedFlags(),
                plan.expectedOutputs()),
            bindingPlan(plan.original(), plan.effective(), List.of(), plan.expectedOutputs()),
            bindingPlan(plan.original(), plan.effective(), plan.addedFlags(), List.of()))) {
      assertThatThrownBy(() -> ExecutionLogBinding.plannedPath(invalid))
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  void bindingRequiresKnownSuccessfulCompleteSingleInvocation(@TempDir Path temporary)
      throws Exception {
    CaptureResult capture = bindingCapture(temporary);
    var receipt = Optional.of(receipt(capture));
    Verification legacy = verification(true, true, Optional.empty(), false);
    for (Optional<ProcessOutcome> process :
        List.of(
            Optional.<ProcessOutcome>empty(),
            Optional.of(ProcessOutcome.exited(1, Duration.ZERO)),
            Optional.of(ProcessOutcome.exited(255, Duration.ZERO)))) {
      CaptureResult invalid =
          new CaptureResult(
              capture.sessionRoot(),
              capture.sessionId(),
              capture.state(),
              capture.plan(),
              process,
              capture.capture(),
              List.of());
      assertThatThrownBy(
              () -> ExecutionLogBinding.verify("7.4.1", invalid, receipt, legacy, Optional.empty()))
          .isInstanceOf(IOException.class);
    }
    for (CaptureSummary summary :
        List.of(
            new CaptureSummary(
                2, 1, 1, 0, 0, 1, List.of(finishedStream("invocation")), false, Optional.empty()),
            summary(List.of(finishedStream("invocation"), finishedStream("other"))),
            summary(List.of(finishedStream("invocation"), finishedStream(""))))) {
      CaptureResult invalid =
          new CaptureResult(
              capture.sessionRoot(),
              capture.sessionId(),
              capture.state(),
              capture.plan(),
              capture.process(),
              Optional.of(summary),
              List.of());
      assertThatThrownBy(
              () -> ExecutionLogBinding.verify("7.4.1", invalid, receipt, legacy, Optional.empty()))
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  void reopenedOperationRetainsCaptureBoundIdentityDisclosure(@TempDir Path temporary)
      throws Exception {
    AuditJournal journal = new AuditJournal(temporary);
    String notice = ExecutionLogBinding.Identity.CAPTURE_BOUND_7_4.notice("7.4.1", "A");
    journal.put("execution.PRESERVE_A.evidenceBinding", "CAPTURE_BOUND_7_4");
    journal.put("execution.PRESERVE_A.evidenceBindingNotice", notice);
    assertThat(ReproducibilityCoordinator.readSavedOperation(journal.directory()).notices())
        .contains(notice);
  }

  private static CaptureResult bindingCapture(Path session) throws IOException {
    Path raw = Files.createDirectories(ManagedSessionLayout.at(session).rawDirectory());
    Path log =
        Files.writeString(raw.resolve(InstrumentationPlanner.EXECUTION_LOG_FILE), "test evidence");
    BazelCommand command =
        BazelCommand.builder(Path.of("/bazel"), session).command("build").build();
    String argument = "--execution_log_compact_file=" + log;
    AddedFlag flag =
        new AddedFlag(
            argument,
            AddedFlag.Placement.COMMAND,
            Capability.EXECUTION_LOG_COMPACT,
            CapabilityStatus.SUPPORTED,
            "test",
            DataSource.EXECUTION_LOG,
            Overhead.MEDIUM,
            Optional.of(log),
            true,
            true);
    InstrumentationPlan plan =
        bindingPlan(
            command,
            command.toBuilder().commandArgs(List.of(argument)).build(),
            List.of(flag),
            List.of(log));
    return new CaptureResult(
        session,
        SessionId.random(),
        SessionState.READY,
        plan,
        Optional.of(ProcessOutcome.exited(0, Duration.ZERO)),
        Optional.of(summary(List.of(finishedStream("invocation")))),
        List.of());
  }

  private static InstrumentationPlan bindingPlan(
      BazelCommand original, BazelCommand effective, List<AddedFlag> flags, List<Path> outputs) {
    return new InstrumentationPlan(
        original,
        effective,
        flags,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        outputs,
        new SourceAvailability(Map.of()),
        CapturePreset.defaultPreset());
  }

  private static CaptureSummary summary(List<BesStreamState> streams) {
    return new CaptureSummary(
        streams.size(), streams.size(), streams.size(), 0, 0, 1, streams, false, Optional.empty());
  }

  private static BesStreamState finishedStream(String invocation) {
    return new BesStreamState(
        new BesStreamKey("build", invocation, "TOOL"),
        1,
        1,
        1,
        1,
        0,
        0,
        OptionalLong.of(1),
        OptionalLong.of(2),
        BesStreamState.Completion.FINISHED,
        Optional.empty());
  }

  private static ExecutionLogReceipt receipt(CaptureResult capture) {
    Path path = ReproducibilityCoordinator.executionLogPath(capture).orElseThrow();
    return new ExecutionLogReceipt(path, path);
  }

  private static Verification verification(
      boolean compact, boolean header, Optional<String> identity, boolean matched) {
    return new Verification(
        1, 1, "ab".repeat(32), matched, compact, header, identity, 0, 0, 0, List.of());
  }

  @Test
  void timedOutAndDisconnectedConfigurationProbesBlockFurtherDispatch(@TempDir Path temporary)
      throws Exception {
    for (CommandResult outcome :
        List.of(
            new CommandResult(0, "", "probe timed out", true),
            new CommandResult(255, "", "SSH disconnected", false))) {
      AtomicInteger dispatches = new AtomicInteger();
      try (var audit = configurationProbeAudit(temporary, dispatches, () -> outcome)) {
        assertThat(inspectConfiguration(audit, temporary)).isEmpty();
        assertThat(getField(audit, "clientOutcomeUnknown")).isEqualTo(true);
        assertThat(inspectConfiguration(audit, temporary)).isEmpty();
        assertThat(dispatches.get()).isEqualTo(1);
      }
      assertThat(dispatches.get()).isEqualTo(1);
    }
  }

  @Test
  void sshConfigurationIoFailureBlocksFurtherDispatch(@TempDir Path temporary) throws Exception {
    AtomicInteger dispatches = new AtomicInteger();
    try (var audit =
        configurationProbeAudit(
            temporary,
            dispatches,
            () -> {
              throw new IOException("SSH transport lost after probe dispatch");
            })) {
      assertThat(inspectConfiguration(audit, temporary)).isEmpty();
      assertThat(getField(audit, "clientOutcomeUnknown")).isEqualTo(true);
      assertThat(inspectConfiguration(audit, temporary)).isEmpty();
      assertThat(dispatches.get()).isEqualTo(1);
    }
    assertThat(dispatches.get()).isEqualTo(1);
  }

  @Test
  void knownConfigurationErrorAllowsAnotherProbeAfterCorrection(@TempDir Path temporary)
      throws Exception {
    AtomicInteger dispatches = new AtomicInteger();
    try (var audit =
        configurationProbeAudit(
            temporary,
            dispatches,
            () ->
                dispatches.get() == 1
                    ? new CommandResult(2, "", "Unrecognized option: --bad_rc_option", false)
                    : new CommandResult(0, "--keep_going\n", "", false))) {
      assertThat(inspectConfiguration(audit, temporary)).isEmpty();
      assertThat(getField(audit, "clientOutcomeUnknown")).isEqualTo(false);
      assertThat(inspectConfiguration(audit, temporary))
          .isEqualTo(Optional.of(List.of("--keep_going")));
      assertThat(getField(audit, "clientOutcomeUnknown")).isEqualTo(false);
      assertThat(dispatches.get()).isEqualTo(2);
    }
    assertThat(dispatches.get()).isEqualTo(2);
  }

  private static ReproducibilityCoordinator configurationProbeAudit(
      Path temporary, AtomicInteger dispatches, ConfigurationProbe outcome) throws Exception {
    var audit =
        new ReproducibilityCoordinator(
            idleRequest(temporary), temporary.resolve("audits"), () -> {}, step -> {});
    // Inject remote request metadata and a fake executor without connecting to an SSH host.
    setField(
        audit,
        "original",
        CaptureRequest.remote(
            temporary.resolve("sessions"),
            "test",
            "bazel",
            "/repository",
            List.of("build", "//:fixture"),
            SshTarget.of("unused-fixture")));
    CommandExecutor executor =
        (CommandExecutor)
            Proxy.newProxyInstance(
                CommandExecutor.class.getClassLoader(),
                new Class<?>[] {CommandExecutor.class},
                (proxy, method, arguments) -> {
                  assertThat(method.getName()).isEqualTo("run");
                  dispatches.incrementAndGet();
                  return outcome.run();
                });
    setField(audit, "executor", executor);
    return audit;
  }

  private static Optional<?> inspectConfiguration(ReproducibilityCoordinator audit, Path temporary)
      throws Exception {
    var method =
        ReproducibilityCoordinator.class.getDeclaredMethod(
            "inspectConfiguration", BazelCommand.class);
    method.setAccessible(true);
    return (Optional<?>)
        method.invoke(
            audit, BazelCommand.builder(Path.of("/bazel"), temporary).command("build").build());
  }

  @FunctionalInterface
  private interface ConfigurationProbe {
    CommandResult run() throws IOException;
  }

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
            "Rc files are ignored for both builds and helpers. This is not the workspace's normal"
                + " configured build.",
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
