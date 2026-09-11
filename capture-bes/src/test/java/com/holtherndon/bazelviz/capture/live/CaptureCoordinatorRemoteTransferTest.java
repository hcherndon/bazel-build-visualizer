package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CaptureCoordinatorRemoteTransferTest {

  private static final Path REMOTE_WORKING_DIRECTORY = Path.of("/srv/repository");
  private static final Path REMOTE_STAGING_DIRECTORY = Path.of("/tmp/bbv-capture-123");
  private static final String LOCAL_BES = "grpc://127.0.0.1:43123";

  @Test
  void remoteFilesAreUntouchableAfterDispatchUntilAKnownRemoteExit() {
    var termination = new CaptureCoordinator.RemoteTermination();
    assertThat(termination.isKnown()).isTrue(); // Preparation can be cleaned before dispatch.
    termination.dispatched();
    assertThat(termination.isKnown()).isFalse(); // Includes start/await throwing.
    termination.completed(ProcessOutcome.exited(255, Duration.ZERO));
    assertThat(termination.isKnown()).isFalse(); // Reconnecting does not change this state.
    var unknown =
        CaptureCoordinator.RemoteTermination.remoteOutcome(
            ProcessOutcome.exited(255, Duration.ZERO));
    assertThat(unknown.exitCode()).hasValue(255);
    assertThat(unknown.failure())
        .hasValueSatisfying(failure -> assertThat(failure).hasMessageContaining("unknown"));
    assertThat(BuildOutcome.classify(Optional.of(unknown), Optional.empty()).isKnown()).isFalse();
    termination.completed(ProcessOutcome.failed(new IllegalStateException("lost"), Duration.ZERO));
    assertThat(termination.isKnown()).isFalse();
    termination.completed(null);
    assertThat(termination.isKnown()).isFalse();
  }

  @Test
  void cancellingTheLocalSshClientDoesNotAuthorizeRemoteCleanup() {
    var termination = new CaptureCoordinator.RemoteTermination();
    termination.dispatched();
    termination.completed(
        ProcessOutcome.cancelled(OptionalInt.of(0), CancellationMode.FORCE_KILL, Duration.ZERO));
    assertThat(termination.isKnown()).isFalse();
  }

  @Test
  void knownSuccessfulAndFailedRemoteBuildsCanPreserveTheirFiles() {
    for (int status : List.of(0, 1, 2, 8, 37)) {
      var termination = new CaptureCoordinator.RemoteTermination();
      termination.dispatched();
      termination.completed(ProcessOutcome.exited(status, Duration.ZERO));
      assertThat(termination.isKnown()).as("exit %s", status).isTrue();
    }
  }

  @Test
  void unknownTerminationRetainsStagingDuringFinalizationAndLaterClose(@TempDir Path sessions)
      throws Exception {
    CaptureRequest request =
        CaptureRequest.remote(
            sessions,
            "test",
            "bazel",
            "/repository",
            List.of("build", "//:target"),
            SshTarget.of("unused-fixture"));
    CaptureCoordinator coordinator = new CaptureCoordinator(request);
    // Deliberately use the normal local executor on an empty, test-owned staging directory.
    // If either guard regresses, the actual cleanup path removes it and this assertion fails.
    Path staging = Files.createTempDirectory(Path.of("/tmp"), "bbv-capture.");
    try {
      var stagingField = CaptureCoordinator.class.getDeclaredField("remoteStagingDirectory");
      stagingField.setAccessible(true);
      stagingField.set(coordinator, staging.toString());
      var terminationField = CaptureCoordinator.class.getDeclaredField("remoteTermination");
      terminationField.setAccessible(true);
      var termination = (CaptureCoordinator.RemoteTermination) terminationField.get(coordinator);
      termination.dispatched();
      termination.completed(ProcessOutcome.exited(255, Duration.ZERO));
      var cleanup =
          CaptureCoordinator.class.getDeclaredMethod(
              "cleanupRemoteStagingQuietly", InstrumentationPlan.class, List.class, Set.class);
      cleanup.setAccessible(true);
      List<String> warnings = new ArrayList<>();
      cleanup.invoke(coordinator, null, warnings, Set.of());
      assertThat(warnings)
          .singleElement()
          .asString()
          .contains("retained", "unknown", staging.toString());
      assertThat(staging).isDirectory();
      coordinator.close();
      assertThat(staging).isDirectory();
    } finally {
      coordinator.close();
      Files.deleteIfExists(staging);
    }
  }

  @Test
  @DisplayName("a relative user-owned BEP is copied into stable managed storage")
  void relativeUserOwnedBepIsCopied(@TempDir Path localRawDirectory) {
    InstrumentationPlan plan = userOwnedBepPlan("reports/current-build.bep");

    assertThat(plan.expectedOutputs()).isEmpty();
    assertThat(CaptureCoordinator.remoteCaptureTransfers(plan, localRawDirectory))
        .containsExactly(
            new CaptureCoordinator.RemoteCaptureTransfer(
                "reports/current-build.bep",
                localRawDirectory.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE)));
  }

  @Test
  @DisplayName("an absolute user-owned BEP remains an execution-host path")
  void absoluteUserOwnedBepIsCopied(@TempDir Path localRawDirectory) {
    InstrumentationPlan plan = userOwnedBepPlan("/var/tmp/build-events/current.bep");

    assertThat(CaptureCoordinator.remoteCaptureTransfers(plan, localRawDirectory))
        .containsExactly(
            new CaptureCoordinator.RemoteCaptureTransfer(
                "/var/tmp/build-events/current.bep",
                localRawDirectory.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE)));
  }

  @Test
  @DisplayName("a separately-valued user-owned BEP flag is copied too")
  void separateValueUserOwnedBepIsCopied(@TempDir Path localRawDirectory) {
    InstrumentationPlan plan =
        userOwnedBepPlanWithArguments("--build_event_binary_file", "reports/separate-value.bep");

    assertThat(plan.expectedOutputs()).isEmpty();
    assertThat(CaptureCoordinator.remoteCaptureTransfers(plan, localRawDirectory))
        .containsExactly(
            new CaptureCoordinator.RemoteCaptureTransfer(
                "reports/separate-value.bep",
                localRawDirectory.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE)));
  }

  @Test
  @DisplayName("the app-owned remote fallback is copied without adding a user-owned path")
  void appOwnedFallbackIsCopied(@TempDir Path localRawDirectory) {
    InstrumentationPlan plan =
        new InstrumentationPlanner()
            .plan(
                baseRequest(command("--bes_backend=grpc://company.example:443"))
                    .resolving(
                        PlanConflict.Kind.EXISTING_BES_BACKEND,
                        PlanConflict.RESOLUTION_KEEP_BES_USE_FILE));

    Path remoteOutput =
        REMOTE_STAGING_DIRECTORY.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE).toAbsolutePath();
    assertThat(plan.expectedOutputs()).containsExactly(remoteOutput);
    assertThat(CaptureCoordinator.remoteCaptureTransfers(plan, localRawDirectory))
        .containsExactly(
            new CaptureCoordinator.RemoteCaptureTransfer(
                remoteOutput.toString(),
                localRawDirectory.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE)));
  }

  @Test
  @DisplayName("an app-owned Starlark CPU profile is copied from SSH staging")
  void starlarkCpuProfileIsCopied(@TempDir Path localRawDirectory) {
    InstrumentationPlan plan =
        new InstrumentationPlanner()
            .plan(
                PlanRequest.initial(
                        command(),
                        capabilities(),
                        CapturePreset.PERFORMANCE_DIAGNOSTICS,
                        REMOTE_STAGING_DIRECTORY,
                        Optional.of(LOCAL_BES))
                    .withRemoteDestinations());

    Path remoteOutput =
        REMOTE_STAGING_DIRECTORY
            .resolve(InstrumentationPlanner.STARLARK_CPU_PROFILE_FILE)
            .toAbsolutePath();
    assertThat(plan.expectedOutputs()).containsExactly(remoteOutput);
    assertThat(CaptureCoordinator.remoteCaptureTransfers(plan, localRawDirectory))
        .containsExactly(
            new CaptureCoordinator.RemoteCaptureTransfer(
                remoteOutput.toString(),
                localRawDirectory.resolve(InstrumentationPlanner.STARLARK_CPU_PROFILE_FILE)));
  }

  @Test
  @DisplayName("a failed SSH profile download is retained for recovery")
  void failedStarlarkDownloadIsMarkedForRecovery(@TempDir Path directory) throws Exception {
    Path remoteStaging = Files.createDirectories(directory.resolve("remote-staging"));
    Path remoteProfile =
        Files.writeString(
            remoteStaging.resolve(InstrumentationPlanner.STARLARK_CPU_PROFILE_FILE),
            "profile bytes");
    InstrumentationPlan plan =
        new InstrumentationPlanner()
            .plan(
                PlanRequest.initial(
                        command(),
                        capabilities(),
                        CapturePreset.PERFORMANCE_DIAGNOSTICS,
                        remoteStaging,
                        Optional.of(LOCAL_BES))
                    .withRemoteDestinations());
    Path invalidRawDirectory = Files.writeString(directory.resolve("raw-is-a-file"), "x");
    List<String> warnings = new ArrayList<>();

    Set<String> retained =
        CaptureCoordinator.transferRemoteOutputs(
            new LocalExecutionFileSystem("remote-fixture"),
            directory.toString(),
            plan,
            invalidRawDirectory,
            warnings);

    assertThat(retained).containsExactly(remoteProfile.toString());
    assertThat(warnings)
        .anyMatch(
            warning -> warning.contains("could not copy remote capture file " + remoteProfile));
    assertThat(Files.readString(remoteProfile)).isEqualTo("profile bytes");
  }

  @Test
  @DisplayName("an absent SSH profile needs no retained staging copy")
  void absentStarlarkProfileDoesNotRetainStaging(@TempDir Path directory) throws Exception {
    Path remoteStaging = Files.createDirectories(directory.resolve("remote-staging"));
    InstrumentationPlan plan =
        new InstrumentationPlanner()
            .plan(
                PlanRequest.initial(
                        command(),
                        capabilities(),
                        CapturePreset.PERFORMANCE_DIAGNOSTICS,
                        remoteStaging,
                        Optional.of(LOCAL_BES))
                    .withRemoteDestinations());
    Path localRawDirectory = Files.createDirectories(directory.resolve("local-raw"));
    List<String> warnings = new ArrayList<>();

    Set<String> retained =
        CaptureCoordinator.transferRemoteOutputs(
            new LocalExecutionFileSystem("remote-fixture"),
            directory.toString(),
            plan,
            localRawDirectory,
            warnings);

    assertThat(retained).isEmpty();
    assertThat(warnings).anyMatch(warning -> warning.contains("does not exist"));
  }

  @Test
  @DisplayName("an unsupported Starlark CPU profile is persisted as skipped")
  void unsupportedStarlarkCpuProfileIsPersisted(@TempDir Path directory) throws Exception {
    Map<String, FlagSpec> flags = new LinkedHashMap<>(capabilities().flags());
    flags.remove("starlark_cpu_profile");
    BazelCapabilities withoutStarlark =
        BazelCapabilities.fromFlags(
            "bazel 9.2.0",
            Optional.of("9.2.0"),
            BazelCapabilities.DetectionMethod.FLAGS_PROTO,
            flags,
            List.of());
    InstrumentationPlan plan =
        new InstrumentationPlanner()
            .plan(
                PlanRequest.initial(
                        command(),
                        withoutStarlark,
                        CapturePreset.PERFORMANCE_DIAGNOSTICS,
                        REMOTE_STAGING_DIRECTORY,
                        Optional.of(LOCAL_BES))
                    .withRemoteDestinations());

    try (SessionDatabase database = SessionDatabase.open(directory.resolve("session.db"))) {
      MigrationRunner.standard().migrate(database);
      List<String> warnings = new ArrayList<>();

      CaptureCoordinator.recordUnattemptedStarlarkCpuProfile(
          database.writerConnection(), plan, 123L, warnings);

      assertThat(warnings).isEmpty();
      try (var statement = database.writerConnection().createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT state, exit_status FROM enrichment_tasks"
                      + " WHERE kind='STARLARK_CPU_PROFILE'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("state")).isEqualTo("SKIPPED");
        assertThat(rows.getString("exit_status"))
            .startsWith("UNSUPPORTED: ")
            .contains("does not accept --starlark_cpu_profile");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  @DisplayName("a Starlark CPU profile disabled in review is persisted as skipped")
  void reviewSkippedStarlarkCpuProfileIsPersisted(@TempDir Path directory) throws Exception {
    InstrumentationPlan plan =
        new InstrumentationPlanner()
            .plan(
                PlanRequest.initial(
                        command(),
                        capabilities(),
                        CapturePreset.PERFORMANCE_DIAGNOSTICS,
                        REMOTE_STAGING_DIRECTORY,
                        Optional.of(LOCAL_BES))
                    .vetoing(Capability.STARLARK_CPU_PROFILE)
                    .withRemoteDestinations());

    try (SessionDatabase database = SessionDatabase.open(directory.resolve("session.db"))) {
      MigrationRunner.standard().migrate(database);
      List<String> warnings = new ArrayList<>();

      CaptureCoordinator.recordUnattemptedStarlarkCpuProfile(
          database.writerConnection(), plan, 123L, warnings);

      assertThat(warnings).isEmpty();
      try (var statement = database.writerConnection().createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT state, exit_status FROM enrichment_tasks"
                      + " WHERE kind='STARLARK_CPU_PROFILE'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("state")).isEqualTo("SKIPPED");
        assertThat(rows.getString("exit_status"))
            .isEqualTo("SKIPPED: Starlark CPU profiling was disabled" + " in the launch review");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  @DisplayName("local aquery writes its exact BEP scope beside the query output")
  void localAqueryScopeFileIsPrepared(@TempDir Path directory) throws Exception {
    ManagedSessionLayout layout = ManagedSessionLayout.at(directory.resolve("session"));
    Files.createDirectories(layout.rawDirectory());
    try (SessionDatabase database = SessionDatabase.open(layout.databaseFile())) {
      MigrationRunner.standard().migrate(database);
      insertReportedTarget(database, "//pkg:chosen");
      Path output = layout.rawDirectory().resolve(AuxiliaryQueryPlanner.AQUERY_OUTPUT_FILE);
      BazelCommand original = commandWithOutput(directory, "//...");
      AuxiliaryQueryPlanner.Plan query =
          new AuxiliaryQueryPlanner(capabilities()).aquery(original, output);

      var result =
          CaptureCoordinator.prepareTargetQueryFile(
              database.writerConnection(), query, original, layout, Optional.empty());

      assertThat(result.scope().name()).isEqualTo("EXACT_BEP_TARGETS");
      assertThat(
              Files.readString(
                  layout.rawDirectory().resolve(AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE)))
          .isEqualTo("deps(set(\"//pkg:chosen\"))\n");
    }
  }

  @Test
  @DisplayName("SSH cquery keeps a local scope copy and uploads it to execution staging")
  void remoteCqueryScopeFileIsUploaded(@TempDir Path directory) throws Exception {
    ManagedSessionLayout layout = ManagedSessionLayout.at(directory.resolve("session"));
    Files.createDirectories(layout.rawDirectory());
    Path executionRaw = directory.resolve("remote-staging");
    Files.createDirectories(executionRaw);
    try (SessionDatabase database = SessionDatabase.open(layout.databaseFile())) {
      MigrationRunner.standard().migrate(database);
      insertReportedTarget(database, "//pkg:chosen");
      BazelCommand original = commandWithOutput(directory, "//...");
      AuxiliaryQueryPlanner.Plan query =
          new AuxiliaryQueryPlanner(capabilities())
              .cquery(original, executionRaw.resolve(AuxiliaryQueryPlanner.CQUERY_OUTPUT_FILE));

      var result =
          CaptureCoordinator.prepareTargetQueryFile(
              database.writerConnection(),
              query,
              original,
              layout,
              Optional.of(new LocalExecutionFileSystem("remote-fixture")));

      assertThat(result.scope().name()).isEqualTo("EXACT_BEP_TARGETS");
      String expected = "deps(set(\"//pkg:chosen\"))\n";
      assertThat(
              Files.readString(
                  layout.rawDirectory().resolve(AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE)))
          .isEqualTo(expected);
      assertThat(
              Files.readString(executionRaw.resolve(AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE)))
          .isEqualTo(expected);
    }
  }

  private static void insertReportedTarget(SessionDatabase database, String label)
      throws Exception {
    try (var statement = database.writerConnection().createStatement()) {
      statement.execute(
          "INSERT INTO event_streams (id, stream_key, state)" + " VALUES (1, 's', 'CLOSED')");
      statement.execute(
          "INSERT INTO build_invocation"
              + " (singleton, stream_id, saw_last_message) VALUES (1, 1, 1)");
      statement.execute("INSERT INTO labels (id, value) VALUES (1, '" + label + "')");
      statement.execute(
          "INSERT INTO targets (id, label_id, aspect, outcome)"
              + " VALUES (1, 1, '', 'CONFIGURED')");
      statement.execute("INSERT INTO configurations (id, stream_id, bep_id) VALUES (1, 1, 'cfg')");
      statement.execute(
          "INSERT INTO configured_targets (target_id, configuration_id, outcome)"
              + " VALUES (1, 1, 'BUILT')");
    }
  }

  private static BazelCommand commandWithOutput(Path workingDirectory, String target) {
    return BazelCommand.builder(Path.of("/usr/bin/bazel"), workingDirectory)
        .command("build")
        .targets(List.of(target))
        .build();
  }

  private static InstrumentationPlan userOwnedBepPlan(String executionPath) {
    return userOwnedBepPlanWithArguments("--build_event_binary_file=" + executionPath);
  }

  private static InstrumentationPlan userOwnedBepPlanWithArguments(String... bepArguments) {
    ArrayList<String> commandArguments = new ArrayList<>();
    commandArguments.add("--bes_backend=grpc://company.example:443");
    commandArguments.addAll(List.of(bepArguments));
    return new InstrumentationPlanner()
        .plan(
            baseRequest(command(commandArguments.toArray(String[]::new)))
                .resolving(
                    PlanConflict.Kind.EXISTING_BES_BACKEND,
                    PlanConflict.RESOLUTION_KEEP_BES_USE_FILE)
                .resolving(
                    PlanConflict.Kind.EXISTING_BEP_OUTPUT,
                    InstrumentationPlanner.RESOLUTION_READ_USER_BEP_FILE));
  }

  private static PlanRequest baseRequest(BazelCommand command) {
    return PlanRequest.initial(
            command,
            capabilities(),
            CapturePreset.LIVE_ESSENTIALS,
            REMOTE_STAGING_DIRECTORY,
            Optional.of(LOCAL_BES))
        .withRemoteDestinations();
  }

  private static BazelCommand command(String... commandArgs) {
    return BazelCommand.builder(Path.of("/usr/bin/bazel"), REMOTE_WORKING_DIRECTORY)
        .command("build")
        .commandArgs(List.of(commandArgs))
        .targets(List.of("//..."))
        .build();
  }

  private static BazelCapabilities capabilities() {
    Map<String, FlagSpec> flags = new LinkedHashMap<>();
    for (String name :
        List.of(
            "bes_backend",
            "bes_timeout",
            "build_event_binary_file",
            "build_event_publish_all_actions",
            "starlark_cpu_profile")) {
      flags.put(
          name,
          new FlagSpec(
              name, Set.of("build"), true, false, Optional.of(true), Optional.empty(), List.of()));
    }
    return BazelCapabilities.fromFlags(
        "bazel 9.2.0",
        Optional.of("9.2.0"),
        BazelCapabilities.DetectionMethod.FLAGS_PROTO,
        flags,
        List.of());
  }
}
