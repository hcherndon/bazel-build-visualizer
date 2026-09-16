package com.holtherndon.bazelviz.runner.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PlanRequestTest {

  @Test
  void localDestinationsRemainTheCompatibilityDefault() {
    PlanRequest request =
        PlanRequest.initial(
            command(),
            BazelCapabilities.unprobed("not needed by this test"),
            CapturePreset.LIVE_ESSENTIALS,
            Path.of("/tmp/local-raw"),
            Optional.of("grpc://127.0.0.1:12345"));

    assertThat(request.destinationsAreLocal()).isTrue();
    assertThat(request.inSession(Path.of("/tmp/session/raw")).destinationsAreLocal()).isTrue();
  }

  @Test
  void everyPlanAdjustmentPreservesRemoteDestinationSemantics() {
    Path remoteRaw = Path.of("/tmp/bbv-capture.A1b2C3d4");
    PlanRequest request =
        PlanRequest.initial(
                command(),
                BazelCapabilities.unprobed("not needed by this test"),
                CapturePreset.PERFORMANCE_DIAGNOSTICS,
                remoteRaw,
                Optional.of("grpc://127.0.0.1:39117"))
            .withRemoteDestinations()
            .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND, PlanConflict.RESOLUTION_REPLACE_BES)
            .vetoing(Capability.PUBLISH_ALL_ACTIONS)
            .withEffectiveOptions(Optional.of(List.of("--keep_going")))
            .inSession(Path.of("/tmp/bbv-capture.Z9y8X7w6"));

    assertThat(request.destinationsAreLocal()).isFalse();
    assertThat(request.allowOverwrite()).isTrue();
    assertThat(request.sessionRawDirectory().toString()).isEqualTo("/tmp/bbv-capture.Z9y8X7w6");
    assertThat(request.effectiveOptions()).contains(List.of("--keep_going"));
    assertThat(request.vetoed()).containsExactly(Capability.PUBLISH_ALL_ACTIONS);
    assertThat(request.resolutionFor(PlanConflict.Kind.EXISTING_BES_BACKEND))
        .contains(PlanConflict.RESOLUTION_REPLACE_BES);
  }

  @Test
  void enablingRemovesOnlyTheRequestedVetoWithoutChangingTheOriginalRequest() {
    PlanRequest request =
        PlanRequest.initial(
                command(),
                BazelCapabilities.unprobed("not needed by this test"),
                CapturePreset.PERFORMANCE_DIAGNOSTICS,
                Path.of("/tmp/raw"),
                Optional.of("grpc://127.0.0.1:39117"))
            .withRemoteDestinations()
            .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND, PlanConflict.RESOLUTION_REPLACE_BES)
            .withEffectiveOptions(Optional.of(List.of("--keep_going")))
            .inSession(Path.of("/tmp/session/raw"))
            .vetoing(Capability.EXECUTION_LOG_COMPACT)
            .vetoing(Capability.EXECUTION_LOG_BINARY)
            .vetoing(Capability.PUBLISH_ALL_ACTIONS);

    PlanRequest enabled = request.enabling(Capability.EXECUTION_LOG_COMPACT);

    assertThat(enabled.vetoed())
        .containsExactlyInAnyOrder(Capability.EXECUTION_LOG_BINARY, Capability.PUBLISH_ALL_ACTIONS);
    assertThat(enabled).usingRecursiveComparison().ignoringFields("vetoed").isEqualTo(request);
    assertThat(request.vetoed())
        .containsExactlyInAnyOrder(
            Capability.EXECUTION_LOG_COMPACT,
            Capability.EXECUTION_LOG_BINARY,
            Capability.PUBLISH_ALL_ACTIONS);
    assertThatThrownBy(() -> enabled.vetoed().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(enabled.vetoing(Capability.EXECUTION_LOG_COMPACT)).isEqualTo(request);
    assertThat(enabled.enabling(Capability.EXECUTION_LOG_BINARY).vetoed())
        .containsExactly(Capability.PUBLISH_ALL_ACTIONS);
  }

  @Test
  void enablingAnUnvetoedCapabilityIsAnIdempotentValueOperation() {
    PlanRequest request =
        PlanRequest.initial(
            command(),
            BazelCapabilities.unprobed("not needed by this test"),
            CapturePreset.LIVE_ESSENTIALS,
            Path.of("/tmp/raw"),
            Optional.empty());

    assertThat(request.enabling(Capability.EXECUTION_LOG_COMPACT)).isEqualTo(request);
    PlanRequest vetoed = request.vetoing(Capability.EXECUTION_LOG_BINARY);
    assertThat(vetoed.enabling(Capability.EXECUTION_LOG_COMPACT)).isEqualTo(vetoed);
    assertThat(
            vetoed
                .enabling(Capability.EXECUTION_LOG_BINARY)
                .enabling(Capability.EXECUTION_LOG_BINARY))
        .isEqualTo(request);
  }

  @Test
  void enablingRequiresACapability() {
    PlanRequest request =
        PlanRequest.initial(
            command(),
            BazelCapabilities.unprobed("not needed by this test"),
            CapturePreset.LIVE_ESSENTIALS,
            Path.of("/tmp/raw"),
            Optional.empty());
    assertThatThrownBy(() -> request.enabling(null)).isInstanceOf(NullPointerException.class);
  }

  private static BazelCommand command() {
    return BazelCommand.builder(Path.of("/usr/bin/bazel"), Path.of("/repo"))
        .command("build")
        .targets(List.of("//..."))
        .build();
  }
}
