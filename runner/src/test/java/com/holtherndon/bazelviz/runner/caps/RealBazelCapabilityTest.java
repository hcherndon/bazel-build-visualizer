package com.holtherndon.bazelviz.runner.caps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.exec.BazelExecutableResolver;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The compatibility matrix, checked against the binaries it describes.
 *
 * <p>{@code docs/bazel-compatibility.md} makes observation the rule, and this is what keeps that
 * honest: the differences asserted here — the compact execution log arriving in 7, the profile-path
 * announcement leaving after 7 — were measured, and if a future Bazel moves them this test says so
 * instead of the planner silently injecting a flag that no longer exists.
 */
@Tag("real-bazel")
class RealBazelCapabilityTest {

  @Test
  void renamedBazeliskKeepsSelectedVersionOutsideWorkspace(@TempDir Path directory)
      throws Exception {
    assumeTrue(
        System.getenv(BazelBinary.REPRODUCIBILITY_VERSION_ENV) != null,
        "Opt in with BBV_REPRO_BAZEL_VERSION to check one selected release, not another matrix.");
    String version = BazelBinary.reproducibilityFixtureVersion();
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 1);
    Files.writeString(workspace.root().resolve(".bazelversion"), version + "\n");
    Map<String, String> selectedVersion = Map.of(BazelBinary.VERSION_ENV, version);
    BazelExecutable original =
        BazelExecutableResolver.resolve(
            bazel.orElseThrow().toString(), Optional.of(workspace.root()), selectedVersion);
    assumeTrue(original.isBazelisk(), "This regression needs a real Bazelisk launcher.");
    assumeTrue(
        Files.size(original.resolved()) < 16 * 1024 * 1024,
        "Copy only the small launcher, not a full Bazel distribution.");
    assertThat(original.bazelVersion()).contains(version);

    Path renamed =
        Files.copy(
            original.resolved(),
            Files.createDirectory(directory.resolve("bin")).resolve("bazel"),
            StandardCopyOption.COPY_ATTRIBUTES);
    Map<Path, String> before = workspaceContents(workspace.root());
    BazelExecutable executable =
        BazelExecutableResolver.resolve(
            renamed.toString(), Optional.of(workspace.root()), selectedVersion);
    assertThat(executable.bazelVersion()).contains(version);
    assertThat(executable.isBazelisk()).isFalse();

    RealRecordingExecutor executor = new RealRecordingExecutor();
    BazelCapabilities capabilities =
        new BazelCapabilityDetector(executor, Duration.ofSeconds(30))
            .detect(executable, List.of("--host_jvm_args=-Xmx512m"));

    assertThat(capabilities.detection())
        .describedAs("probe warnings: %s", capabilities.probeWarnings())
        .isEqualTo(BazelCapabilities.DetectionMethod.FLAGS_PROTO);
    assertThat(capabilities.bazelVersion()).contains(version);
    assertThat(capabilities.supports(Capability.EXECUTION_LOG_COMPACT)).isTrue();
    assertThat(capabilities.flags()).hasSizeGreaterThan(500);
    List<ProbeInvocation> probes =
        executor.invocations.stream()
            .filter(call -> call.request().argv().getFirst().equals(renamed.toString()))
            .toList();
    assertThat(probes).hasSize(2);
    assertThat(probes.getFirst().request().argv()).contains("--version");
    assertThat(probes.getFirst().result().isSuccess()).isTrue();
    assertThat(probes.getFirst().result().stdout()).contains("bazel " + version);
    assertThat(probes.getLast().request().argv()).contains("help", "flags-as-proto");
    for (ProbeInvocation probe : probes) {
      assertThat(probe.request().environmentOverrides())
          .containsEntry(BazelBinary.VERSION_ENV, Optional.of(version));
      Path scratch = Path.of(probe.request().workingDirectory().orElseThrow());
      assertThat(scratch.startsWith(workspace.root())).isFalse();
      assertThat(Files.exists(scratch)).as("probe scratch is removed").isFalse();
    }
    assertThat(workspaceContents(workspace.root())).isEqualTo(before);
  }

  private static Map<Path, String> workspaceContents(Path root) throws IOException {
    Map<Path, String> contents = new LinkedHashMap<>();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted().toList()) {
        contents.put(
            root.relativize(path),
            Files.isSymbolicLink(path)
                ? "symlink: " + Files.readSymbolicLink(path)
                : Files.isDirectory(path) ? "directory" : Files.readString(path));
      }
    }
    return contents;
  }

  private record ProbeInvocation(CommandRequest request, CommandResult result) {}

  /** Records the actual process requests and payloads; no capability output is simulated. */
  private static final class RealRecordingExecutor implements CommandExecutor {
    final List<ProbeInvocation> invocations = new ArrayList<>();

    @Override
    public CommandResult run(CommandRequest request, Duration timeout)
        throws IOException, InterruptedException {
      CommandResult result = LocalCommandExecutor.INSTANCE.run(request, timeout);
      invocations.add(new ProbeInvocation(request, result));
      return result;
    }

    @Override
    public CommandResult runRedirectingStdout(
        CommandRequest request, Duration timeout, Path localOutputFile) {
      throw new UnsupportedOperationException("Capability probes must not redirect output.");
    }

    @Override
    public RunningCommand start(CommandRequest request) {
      throw new UnsupportedOperationException(
          "Capability probes must not leave a running process.");
    }

    @Override
    public InteractiveChannel openTerminal(String workingDirectory) {
      throw new UnsupportedOperationException("Capability probes must not open terminals.");
    }
  }

  @ParameterizedTest(name = "Bazel {0}")
  @ValueSource(strings = {"6.5.0", "7.6.1", "8.4.1", "9.2.0"})
  @DisplayName("every targeted Bazel is probed structurally and reports the BES capabilities")
  void capabilitiesAreDetectedStructurally(String version, @TempDir Path directory)
      throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelExecutable executable = resolvePinned(bazel.orElseThrow(), version, directory);
    assumeTrue(
        executable.bazelVersion().map(version::equals).orElse(false),
        "this machine resolved "
            + executable.bazelVersion().orElse("no version")
            + " rather than "
            + version);

    BazelCapabilities capabilities = new BazelCapabilityDetector().detect(executable, List.of());

    assertThat(capabilities.detection())
        .describedAs("probe warnings: %s", capabilities.probeWarnings())
        .isEqualTo(BazelCapabilities.DetectionMethod.FLAGS_PROTO);

    // The capabilities Phase 2 actually injects, on every supported version.
    assertThat(capabilities.supports(Capability.BES_BACKEND)).isTrue();
    assertThat(capabilities.supports(Capability.BES_LIFECYCLE_EVENTS)).isTrue();
    assertThat(capabilities.supports(Capability.PUBLISH_ALL_ACTIONS)).isTrue();
    assertThat(capabilities.supports(Capability.BEP_BINARY_FILE)).isTrue();
    assertThat(capabilities.supports(Capability.BEP_JSON_FILE)).isTrue();
    assertThat(capabilities.preferredFlag(Capability.BES_BACKEND)).contains("bes_backend");

    // A flag table with a thousand entries, not a handful: a probe that
    // returned three flags would satisfy every assertion above and still be
    // broken.
    assertThat(capabilities.flags()).hasSizeGreaterThan(500);
  }

  @ParameterizedTest(name = "Bazel {0}")
  @ValueSource(strings = {"6.5.0", "7.6.1", "8.4.1", "9.2.0"})
  @DisplayName("version-gated capabilities are reported as the binary actually has them")
  void versionGatedCapabilities(String version, @TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelExecutable executable = resolvePinned(bazel.orElseThrow(), version, directory);
    assumeTrue(
        executable.bazelVersion().map(version::equals).orElse(false),
        "this machine resolved a different version");

    BazelCapabilities capabilities = new BazelCapabilityDetector().detect(executable, List.of());
    boolean isBazel6 = version.startsWith("6.");

    // The compact execution log arrives in Bazel 7. On 6 the binary log is
    // all there is, which is why the capability catalog lists both.
    assertThat(capabilities.supports(Capability.EXECUTION_LOG_COMPACT)).isEqualTo(!isBazel6);
    assertThat(capabilities.supports(Capability.EXECUTION_LOG_BINARY)).isTrue();

    // The sampled Starlark CPU profile predates the supported matrix.
    assertThat(capabilities.supports(Capability.STARLARK_CPU_PROFILE)).isTrue();

    // --build_event_binary_file_upload_mode likewise.
    assertThat(capabilities.supports(Capability.BEP_FILE_UPLOAD_MODE)).isEqualTo(!isBazel6);

    // aquery and cquery accept --output on every version.
    assertThat(capabilities.supports(Capability.AQUERY_PROTO_OUTPUT)).isTrue();
    assertThat(capabilities.supports(Capability.CQUERY_PROTO_OUTPUT)).isTrue();
  }

  @ParameterizedTest(name = "Bazel {0}")
  @ValueSource(strings = {"6.5.0", "8.4.1"})
  @DisplayName("fields a version does not populate are reported absent, not as empty values")
  void absentFieldsAreNotEmptyValues(String version, @TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelExecutable executable = resolvePinned(bazel.orElseThrow(), version, directory);
    assumeTrue(
        executable.bazelVersion().map(version::equals).orElse(false),
        "this machine resolved a different version");

    BazelCapabilities capabilities = new BazelCapabilityDetector().detect(executable, List.of());
    FlagSpec besBackend = capabilities.flag("bes_backend").orElseThrow();

    if (version.startsWith("6.")) {
      // Bazel 6.5 populates FlagInfo fields 1-9 only. A getter would
      // report requires_value = false and default_value = "", both of
      // which would be inventions.
      assertThat(besBackend.requiresValue()).isEmpty();
      assertThat(besBackend.defaultValue()).isEmpty();
    } else {
      assertThat(besBackend.requiresValue()).contains(true);
      assertThat(besBackend.defaultValue()).isPresent();
    }
    assertThat(besBackend.appliesTo("build")).isTrue();
  }

  /**
   * Resolves the launcher with {@code USE_BAZEL_VERSION} in effect.
   *
   * <p>Set through a fixture workspace containing a {@code .bazelversion} rather than through the
   * environment, because the environment of a test JVM cannot be changed and because this is how a
   * real repository pins its Bazel — which makes it the case worth exercising.
   */
  private static BazelExecutable resolvePinned(Path bazel, String version, Path directory)
      throws Exception {
    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 1);
    Files.writeString(workspace.root().resolve(".bazelversion"), version + "\n");
    return BazelExecutableResolver.resolve(bazel.toString(), Optional.of(workspace.root()));
  }
}
