package com.holtherndon.bazelviz.runner.caps;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.runtime.commands.proto.BazelFlagsProto.FlagCollection;
import com.google.devtools.build.lib.runtime.commands.proto.BazelFlagsProto.FlagInfo;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class BazelCapabilityDetectorExecutorTest {

  @Test
  void probeFailurePreservesTimeoutAndTruncationTogether() {
    BazelCapabilityDetector.ProbeResult result =
        new BazelCapabilityDetector.ProbeResult(1, "", "", true, true);

    assertThat(result.failureDetail())
        .contains("did not finish in time")
        .contains("exceeded its bounded capture limit");
  }

  @Test
  void createsScratchAndRunsEveryProbeOnTheExecutorHost() {
    RecordingExecutor executor = new RecordingExecutor();
    BazelExecutable executable =
        new BazelExecutable(
            "bazel",
            Path.of("/remote/bin/bazel"),
            "bazel 9.2.0",
            Optional.empty(),
            Optional.of("9.2.0"),
            Optional.empty(),
            false);

    BazelCapabilities capabilities =
        new BazelCapabilityDetector(executor, Duration.ofSeconds(5))
            .detect(executable, List.of("--host_jvm_args=-Xmx1g"));

    assertThat(capabilities.detection()).isEqualTo(BazelCapabilities.DetectionMethod.HELP_TEXT);
    assertThat(capabilities.flag("bes_backend")).isPresent();
    assertThat(executor.requests.getFirst().argv().getFirst()).isEqualTo("/usr/bin/mktemp");
    assertThat(
            executor.requests.stream()
                .filter(request -> request.argv().contains("help"))
                .allMatch(
                    request ->
                        request
                            .workingDirectory()
                            .equals(Optional.of("/tmp/bbv-bazel-probe.ABCDEF"))))
        .isTrue();
    assertThat(executor.requests.getLast().argv())
        .containsExactly("/bin/rmdir", "--", "/tmp/bbv-bazel-probe.ABCDEF");
  }

  @Test
  void aLauncherNamedBazelIsPinnedEvenWithoutABazeliskBanner() {
    RecordingExecutor executor = new RecordingExecutor();
    executor.bazelReply =
        request -> {
          boolean pinned =
              Optional.of("7.4.1").equals(request.environmentOverrides().get("USE_BAZEL_VERSION"));
          if (request.argv().contains("--version"))
            return success("bazel " + (pinned ? "7.4.1" : "6.5.0") + "\n");
          return success(
              flagTable(pinned ? "execution_log_compact_file" : "execution_log_binary_file"));
        };
    BazelExecutable executable = executable("7.4.1");
    BazelCapabilityDetector detector = new BazelCapabilityDetector(executor);
    BazelCapabilities capabilities = detector.detect(executable, List.of());

    assertThat(executable.isBazelisk()).isFalse();
    assertThat(capabilities.detection()).isEqualTo(BazelCapabilities.DetectionMethod.FLAGS_PROTO);
    assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
        .isEqualTo(CapabilityStatus.SUPPORTED);
    assertThat(
            executor.requests.stream()
                .filter(request -> request.argv().getFirst().equals("/remote/bin/bazel")))
        .hasSize(2)
        .allSatisfy(
            request -> {
              assertThat(request.environmentOverrides())
                  .containsEntry("USE_BAZEL_VERSION", Optional.of("7.4.1"));
              assertThat(request.workingDirectory()).contains("/tmp/bbv-bazel-probe.ABCDEF");
            });
    assertThat(detector.detect(executable, List.of())).isSameAs(capabilities);
    assertThat(executor.requests).hasSize(4);
  }

  @Test
  void wrongScratchVersionIsUnknownRatherThanAnUnsupportedFlag() {
    RecordingExecutor executor = new RecordingExecutor();
    executor.bazelReply = request -> success("bazel 6.5.0\n");
    BazelCapabilities capabilities =
        new BazelCapabilityDetector(executor).detect(executable("7.4.1"), List.of());

    assertThat(capabilities.isUnprobed()).isTrue();
    assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
        .isEqualTo(CapabilityStatus.UNKNOWN);
    assertThat(capabilities.probeWarnings().getFirst())
        .contains("workspace selected Bazel 7.4.1", "launcher reported 6.5.0");
    assertThat(executor.requests).noneMatch(request -> request.argv().contains("help"));
    assertThat(executor.requests.getLast().argv())
        .containsExactly("/bin/rmdir", "--", "/tmp/bbv-bazel-probe.ABCDEF");
  }

  @Test
  void failedOrUnidentifiableScratchVersionCannotSupplyCapabilities() {
    for (CommandResult response :
        List.of(
            new CommandResult(1, "", "connection failed", false),
            new CommandResult(-1, "bazel 7.4.1\n", "", true),
            success("Bazelisk version: v1.0\n"))) {
      RecordingExecutor executor = new RecordingExecutor();
      executor.bazelReply = request -> response;
      BazelCapabilities capabilities =
          new BazelCapabilityDetector(executor).detect(executable("7.4.1"), List.of());
      assertThat(capabilities.isUnprobed()).isTrue();
      assertThat(executor.requests).noneMatch(request -> request.argv().contains("help"));
      assertThat(executor.requests.getLast().argv().getFirst()).isEqualTo("/bin/rmdir");
    }
  }

  @Test
  void failedOrEmptyBuildHelpDoesNotTurnOtherCommandsIntoNegativeBuildEvidence() {
    for (CommandResult buildHelp :
        List.of(
            new CommandResult(1, "", "SSH help request failed", false),
            success("wrapper status only\n"))) {
      RecordingExecutor executor = new RecordingExecutor();
      executor.bazelReply =
          request -> {
            if (request.argv().contains("--version")) return success("bazel 7.4.1\n");
            if (request.argv().contains("flags-as-proto"))
              return new CommandResult(1, "", "proto unavailable", false);
            if (request.argv().contains("build")) return buildHelp;
            return success("  --execution_log_compact_file (a path)\n  --output (a string)\n");
          };
      BazelCapabilities capabilities =
          new BazelCapabilityDetector(executor).detect(executable("7.4.1"), List.of());
      assertThat(capabilities.detection()).isEqualTo(BazelCapabilities.DetectionMethod.HELP_TEXT);
      assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
          .isEqualTo(CapabilityStatus.UNKNOWN);
      assertThat(capabilities.status(Capability.AQUERY_PROTO_OUTPUT))
          .isEqualTo(CapabilityStatus.SUPPORTED);
      assertThat(capabilities.probeWarnings())
          .anyMatch(warning -> warning.contains("'help build'"));
    }
  }

  @Test
  void successfulHelpCanConfirmCompactSupportOrItsAbsence() {
    for (boolean compact : List.of(true, false)) {
      RecordingExecutor executor = new RecordingExecutor();
      executor.bazelReply =
          request -> {
            if (request.argv().contains("--version")) return success("bazel 7.4.1\n");
            if (request.argv().contains("flags-as-proto")) return success("not base64");
            return success(
                "  --bes_backend (a string)\n"
                    + (compact ? "  --execution_log_compact_file (a path)\n" : ""));
          };
      BazelCapabilities capabilities =
          new BazelCapabilityDetector(executor).detect(executable("7.4.1"), List.of());
      assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
          .isEqualTo(compact ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNSUPPORTED);
    }
  }

  private static BazelExecutable executable(String version) {
    return new BazelExecutable(
        "bazel",
        Path.of("/remote/bin/bazel"),
        "bazel " + version,
        Optional.empty(),
        Optional.of(version),
        Optional.empty(),
        false);
  }

  private static CommandResult success(String output) {
    return new CommandResult(0, output, "", false);
  }

  private static String flagTable(String name) {
    return Base64.getEncoder()
        .encodeToString(
            FlagCollection.newBuilder()
                .addFlagInfos(FlagInfo.newBuilder().setName(name).addCommands("build"))
                .build()
                .toByteArray());
  }

  private static final class RecordingExecutor implements CommandExecutor {
    private final List<CommandRequest> requests = new ArrayList<>();
    private Function<CommandRequest, CommandResult> bazelReply;

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) {
      requests.add(request);
      if (request.argv().getFirst().equals("/usr/bin/mktemp")) {
        return new CommandResult(0, "/tmp/bbv-bazel-probe.ABCDEF\n", "", false);
      }
      if (request.argv().getFirst().equals("/bin/rmdir")) {
        return new CommandResult(0, "", "", false);
      }
      if (bazelReply != null) return bazelReply.apply(request);
      if (request.argv().contains("--version")) return success("bazel 9.2.0\n");
      if (request.argv().contains("flags-as-proto")) {
        return new CommandResult(2, "", "structured output unavailable", false);
      }
      return new CommandResult(
          0, "  --bes_backend (a string)\n  --build_event_binary_file (a path)\n", "", false);
    }

    @Override
    public CommandResult runRedirectingStdout(
        CommandRequest request, Duration timeout, Path localOutputFile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RunningCommand start(CommandRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public InteractiveChannel openTerminal(String workingDirectory) {
      throw new UnsupportedOperationException();
    }
  }
}
