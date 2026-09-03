package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PreflightTest {

  @Test
  void compatibilityConstructorStillRepresentsALocalPreflight() {
    Fixture fixture = fixture();

    Preflight preflight =
        new Preflight(
            fixture.executable,
            fixture.workspace,
            fixture.capabilities,
            fixture.endpoint,
            fixture.plan,
            fixture.request);

    assertThat(preflight.isRemote()).isFalse();
    assertThat(preflight.remote()).isEmpty();
    assertThat(preflight.canLaunch()).isTrue();
  }

  @Test
  void remoteDetailsAreExplicitAndDoNotReplaceTheLocalListener() {
    Fixture fixture = fixture();
    Preflight.RemoteDetails details =
        new Preflight.RemoteDetails(
            " builder@example.internal:2222 ",
            "/srv/repo",
            Optional.of("/srv"),
            " grpc://127.0.0.1:48123 ",
            " grpc://127.0.0.1:39117 ",
            "/tmp/bbv-capture.A1b2C3d4");

    Preflight preflight =
        new Preflight(
            fixture.executable,
            fixture.workspace,
            fixture.capabilities,
            fixture.endpoint,
            fixture.plan,
            fixture.request,
            Optional.of(details));

    assertThat(preflight.isRemote()).isTrue();
    assertThat(preflight.endpoint()).isSameAs(fixture.endpoint);
    assertThat(preflight.remote()).contains(details);
    assertThat(details.host()).isEqualTo("builder@example.internal:2222");
    assertThat(details.workingDirectory()).isEqualTo("/srv/repo");
    assertThat(details.workspaceRoot()).contains("/srv");
    assertThat(details.localBesListener()).isEqualTo("grpc://127.0.0.1:48123");
    assertThat(details.remoteBesBackend()).isEqualTo("grpc://127.0.0.1:39117");
    assertThat(details.stagingDirectory()).isEqualTo("/tmp/bbv-capture.A1b2C3d4");
  }

  @Test
  void localPlanEvidenceKeepsTheDesktopBesAddress() {
    Fixture fixture = fixture();
    Preflight preflight =
        new Preflight(
            fixture.executable,
            fixture.workspace,
            fixture.capabilities,
            fixture.endpoint,
            fixture.plan,
            fixture.request);

    JsonValue.JsonObject json =
        (JsonValue.JsonObject) InstrumentationPlanCodec.toJson(fixture.plan, preflight);

    assertThat(json.members())
        .containsEntry("formatVersion", JsonValue.JsonNumber.of(3))
        .containsEntry("besEndpoint", JsonValue.of(fixture.endpoint.besBackendUri()))
        .containsEntry("desktopBesListener", JsonValue.of(fixture.endpoint.besBackendUri()))
        .containsEntry("executionHost", JsonValue.of("LOCAL"))
        .doesNotContainKeys("sshHost", "remoteStagingDirectory");
  }

  @Test
  void remotePlanEvidenceSeparatesTheAdvertisedAndDesktopBesAddresses() {
    Fixture fixture = fixture();
    Preflight.RemoteDetails details =
        new Preflight.RemoteDetails(
            "builder@example.internal:2222",
            "/srv/repo",
            Optional.of("/srv"),
            fixture.endpoint.besBackendUri(),
            "grpc://127.0.0.1:39117",
            "/tmp/bbv-capture.A1b2C3d4");
    Preflight preflight =
        new Preflight(
            fixture.executable,
            fixture.workspace,
            fixture.capabilities,
            fixture.endpoint,
            fixture.plan,
            fixture.request,
            Optional.of(details));

    JsonValue.JsonObject json =
        (JsonValue.JsonObject) InstrumentationPlanCodec.toJson(fixture.plan, preflight);

    assertThat(json.members())
        .containsEntry("besEndpoint", JsonValue.of("grpc://127.0.0.1:39117"))
        .containsEntry("desktopBesListener", JsonValue.of(fixture.endpoint.besBackendUri()))
        .containsEntry("executionHost", JsonValue.of("SSH"))
        .containsEntry("sshHost", JsonValue.of("builder@example.internal:2222"))
        .containsEntry("remoteStagingDirectory", JsonValue.of("/tmp/bbv-capture.A1b2C3d4"));
  }

  @Test
  void manifestUsesTheCanonicalRemotePathsFromPreflight() {
    Fixture fixture = fixture();
    Preflight.RemoteDetails details =
        new Preflight.RemoteDetails(
            "builder",
            "/canonical/repository",
            Optional.of("/canonical"),
            fixture.endpoint.besBackendUri(),
            "grpc://127.0.0.1:39117",
            "/tmp/bbv-capture.A1b2C3d4");
    Preflight preflight =
        new Preflight(
            fixture.executable,
            fixture.workspace,
            fixture.capabilities,
            fixture.endpoint,
            fixture.plan,
            fixture.request,
            Optional.of(details));
    CaptureRequest entered =
        CaptureRequest.of(
            Path.of("/sessions"),
            "test",
            "bazel",
            Path.of("/entered/symlinked-repository"),
            List.of("build", "//..."));

    assertThat(CaptureCoordinator.manifestExecutionPaths(entered, preflight))
        .isEqualTo(
            new CaptureCoordinator.ManifestExecutionPaths(
                "/canonical/repository", Optional.of("/canonical")));
  }

  @Test
  void remoteDetailsRejectMissingDisplayValues() {
    assertThatThrownBy(
            () ->
                new Preflight.RemoteDetails(
                    " ",
                    "/srv/repo",
                    Optional.empty(),
                    "grpc://127.0.0.1:48123",
                    "grpc://127.0.0.1:39117",
                    "/tmp/bbv-capture.A1b2C3d4"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host");

    assertThatThrownBy(
            () ->
                new Preflight.RemoteDetails(
                    "builder",
                    "/srv/repo",
                    Optional.of("  "),
                    "grpc://127.0.0.1:48123",
                    "grpc://127.0.0.1:39117",
                    "/tmp/bbv-capture.A1b2C3d4"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workspaceRoot");
  }

  private static Fixture fixture() {
    Path bazel = Path.of("/usr/bin/bazel");
    Path workingDirectory = Path.of("/repo");
    BazelCommand command =
        BazelCommand.builder(bazel, workingDirectory)
            .command("build")
            .targets(List.of("//..."))
            .build();
    BazelCapabilities capabilities = BazelCapabilities.unprobed("not needed by this test");
    BesEndpoint endpoint = new BesEndpoint(BesEndpoint.LOOPBACK, 48123, "test-token");
    PlanRequest request =
        PlanRequest.initial(
            command,
            capabilities,
            CapturePreset.LIVE_ESSENTIALS,
            Path.of("/tmp/raw"),
            Optional.of(endpoint.besBackendUri()));
    InstrumentationPlan plan =
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
            CapturePreset.LIVE_ESSENTIALS);
    return new Fixture(
        new BazelExecutable(
            "bazel",
            bazel,
            "bazel 9.2.0",
            Optional.empty(),
            Optional.of("9.2.0"),
            Optional.empty(),
            false),
        new WorkspaceInfo(
            workingDirectory,
            Optional.of(workingDirectory),
            Optional.of("MODULE.bazel"),
            WorkspaceInfo.Detection.MARKER_SEARCH),
        capabilities,
        endpoint,
        plan,
        request);
  }

  private record Fixture(
      BazelExecutable executable,
      WorkspaceInfo workspace,
      BazelCapabilities capabilities,
      BesEndpoint endpoint,
      InstrumentationPlan plan,
      PlanRequest request) {}
}
