package com.holtherndon.bazelviz.runner.caps;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BazelCapabilitiesTest {
  @Test
  void successfulTestHelpCannotTurnFailedBuildHelpIntoUnsupported() {
    BazelCapabilities capabilities =
        partial(
            Map.of(
                "execution_log_compact_file",
                FlagSpec.of("execution_log_compact_file", Set.of("test"))),
            Set.of("test"));

    assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
        .isEqualTo(CapabilityStatus.UNKNOWN);
    assertThat(capabilities.status(Capability.BES_BACKEND)).isEqualTo(CapabilityStatus.UNKNOWN);
    assertThat(capabilities.status(Capability.AQUERY_PROTO_OUTPUT))
        .isEqualTo(CapabilityStatus.UNKNOWN);
    assertThat(capabilities.status(Capability.CQUERY_PROTO_OUTPUT))
        .isEqualTo(CapabilityStatus.UNKNOWN);
    assertThat(capabilities.probeWarnings()).containsExactly("build help failed");
  }

  @Test
  void successfulBuildHelpCanEstablishThatCompactFlagIsAbsent() {
    BazelCapabilities capabilities =
        partial(
            Map.of(
                "bes_backend", FlagSpec.of("bes_backend", Set.of("build")),
                "execution_log_compact_file",
                    FlagSpec.of("execution_log_compact_file", Set.of("test"))),
            Set.of("build", "test"));

    assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
        .isEqualTo(CapabilityStatus.UNSUPPORTED);
    assertThat(capabilities.status(Capability.BES_BACKEND)).isEqualTo(CapabilityStatus.SUPPORTED);
    assertThat(capabilities.status(Capability.CQUERY_PROTO_OUTPUT))
        .isEqualTo(CapabilityStatus.UNKNOWN);
  }

  @Test
  void observedFlagsApplyOnlyToSuccessfullyInspectedCommands() {
    BazelCapabilities capabilities =
        partial(
            Map.of(
                "execution_log_compact_file",
                    FlagSpec.of("execution_log_compact_file", Set.of("build")),
                "output", FlagSpec.of("output", Set.of("aquery"))),
            Set.of("build", "aquery"));

    assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
        .isEqualTo(CapabilityStatus.SUPPORTED);
    assertThat(capabilities.status(Capability.AQUERY_PROTO_OUTPUT))
        .isEqualTo(CapabilityStatus.SUPPORTED);
    assertThat(capabilities.status(Capability.CQUERY_PROTO_OUTPUT))
        .isEqualTo(CapabilityStatus.UNKNOWN);
    assertThat(capabilities.preferredFlag(Capability.EXECUTION_LOG_COMPACT))
        .contains("execution_log_compact_file");
  }

  @Test
  void noSuccessfulCommandMeansUnknownEvenIfPartialRowsExist() {
    BazelCapabilities capabilities =
        partial(
            Map.of(
                "execution_log_compact_file",
                FlagSpec.of("execution_log_compact_file", Set.of("build"))),
            Set.of());

    assertThat(capabilities.statuses().values()).containsOnly(CapabilityStatus.UNKNOWN);
  }

  @Test
  void completeTableOverloadKeepsExistingAbsentFlagBehavior() {
    for (BazelCapabilities.DetectionMethod method :
        List.of(
            BazelCapabilities.DetectionMethod.FLAGS_PROTO,
            BazelCapabilities.DetectionMethod.HELP_TEXT)) {
      BazelCapabilities capabilities =
          BazelCapabilities.fromFlags(
              "bazel 7.4.1",
              Optional.of("7.4.1"),
              method,
              Map.of("bes_backend", FlagSpec.of("bes_backend", Set.of("build"))),
              List.of());

      assertThat(capabilities.status(Capability.BES_BACKEND)).isEqualTo(CapabilityStatus.SUPPORTED);
      assertThat(capabilities.status(Capability.EXECUTION_LOG_COMPACT))
          .isEqualTo(CapabilityStatus.UNSUPPORTED);
      assertThat(capabilities.status(Capability.CQUERY_PROTO_OUTPUT))
          .isEqualTo(CapabilityStatus.UNSUPPORTED);
    }
  }

  private static BazelCapabilities partial(Map<String, FlagSpec> flags, Set<String> commands) {
    return BazelCapabilities.fromFlags(
        "bazel 7.4.1",
        Optional.of("7.4.1"),
        BazelCapabilities.DetectionMethod.HELP_TEXT,
        flags,
        commands,
        List.of("build help failed"));
  }
}
