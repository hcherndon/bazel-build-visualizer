package com.holtherndon.bazelviz.capture.repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison.Finding;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison;
import com.holtherndon.bazelviz.format.session.SessionAuditReference;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/** One pinned private server at a time; never cleans the outer test repository's output base. */
@Execution(ExecutionMode.SAME_THREAD)
final class ManagedAuditBazelTest {
  @Test
  void readsRcAndNestedConfigsForBothRunsAndCleansOnlyItsOwnedBase(
      @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary) throws Exception {
    String version = BazelBinary.reproducibilityFixtureVersion();
    CaptureRequest request =
        request(temporary, version, List.of("build", "--config=audit", "//..."));
    Path workspace = request.localWorkingDirectory();
    Files.writeString(
        workspace.resolve(".bazelrc"),
        """
        startup --host_jvm_args=-Xmx1g
        startup --max_idle_secs=15
        build --action_env=BBV_RC_REQUIRED=from_rc
        build:audit --config=nested
        build:nested --action_env=BBV_CONFIG_REQUIRED=from_nested
        clean --expunge_async
        clean --symlink_prefix=bazel-
        """);
    Files.writeString(
        workspace.resolve("BUILD.bazel"),
        """
        genrule(
            name = "t0",
            outs = ["t0.txt"],
            cmd = 'test "$$BBV_RC_REQUIRED" = from_rc && '
                + 'test "$$BBV_CONFIG_REQUIRED" = from_nested && '
                + 'echo "$$BBV_RC_REQUIRED/$$BBV_CONFIG_REQUIRED" > $@',
        )
        """);
    Map<Path, Path> existingLinks = createWorkspaceLinks(temporary, workspace);
    AtomicBoolean released = new AtomicBoolean();
    AtomicReference<Path> privateMarker = new AtomicReference<>();
    List<ReproducibilityCoordinator.Step> steps = new ArrayList<>();
    ReproducibilityCoordinator.Result result;
    try (var audit =
        new ReproducibilityCoordinator(
            request,
            temporary.resolve("audits"),
            () -> released.set(true),
            step -> {
              steps.add(step);
              if (step == ReproducibilityCoordinator.Step.BUILD_A
                  || step == ReproducibilityCoordinator.Step.BUILD_B) {
                assertThat(Files.isRegularFile(privateMarker.get()))
                    .as("ordinary clean must not expunge the private base")
                    .isTrue();
                assertThat(existingLinks.keySet()).allMatch(Files::isSymbolicLink);
              }
            })) {
      var review = audit.preflight();
      var originalReview = review;
      assertThat(review.canLaunch()).as(review.blockers().toString()).isTrue();
      assertThat(review.protocol().ignoreRcFiles()).isFalse();
      assertThat(review.a().request().effectiveOptions())
          .hasValueSatisfying(
              options ->
                  assertThat(options)
                      .contains(
                          "--action_env=BBV_RC_REQUIRED=from_rc",
                          "--action_env=BBV_CONFIG_REQUIRED=from_nested"));
      review =
          audit.replan(
              plan ->
                  plan.vetoing(Capability.EXECUTION_LOG_COMPACT)
                      .vetoing(Capability.EXECUTION_LOG_BINARY));
      assertThat(review.canLaunch()).isFalse();
      review = audit.setIgnoreRcFiles(true);
      assertThat(review.protocol().ignoreRcFiles()).isTrue();
      assertThat(review.canLaunch()).isFalse();
      assertThat(review.blockers()).anyMatch(blocker -> blocker.contains("--config"));
      assertThatThrownBy(() -> audit.run(originalReview))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("latest launchable audit review");
      review = audit.setIgnoreRcFiles(false);
      assertThat(review.protocol().ignoreRcFiles()).isFalse();
      assertThat(review.canLaunch()).isFalse();
      assertThat(review.a().request().vetoed())
          .contains(Capability.EXECUTION_LOG_COMPACT, Capability.EXECUTION_LOG_BINARY);
      assertThat(review.b().request().vetoed())
          .contains(Capability.EXECUTION_LOG_COMPACT, Capability.EXECUTION_LOG_BINARY);
      review =
          audit.replan(
              plan ->
                  plan.enabling(Capability.EXECUTION_LOG_COMPACT)
                      .enabling(Capability.EXECUTION_LOG_BINARY));
      assertThat(review.canLaunch()).as(review.blockers().toString()).isTrue();
      assertThat(review.a().capabilities().bazelVersion()).contains(version);
      assertThat(review.b().capabilities().bazelVersion()).contains(version);
      assertBindingDisclosure(review.notices(), version);
      assertThat(review.a().plan().auxiliaryCommands()).isEmpty();
      assertThat(review.b().plan().auxiliaryCommands()).isEmpty();
      privateMarker.set(Path.of(review.protocol().outputBase()).resolve("ordinary-clean-marker"));
      Files.writeString(privateMarker.get(), "preserve the root across each ordinary clean\n");
      result = audit.run(review);
      assertThat(result.state())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.State.CAPTURED);
      assertThat(result.cleanup())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.Cleanup.REMOVED);
      assertThat(Files.exists(Path.of(review.protocol().outputBase()))).isFalse();
    }
    assertWorkspaceLinksUnchanged(existingLinks);
    assertThat(released).isTrue();
    assertThat(result.a()).isPresent();
    assertThat(result.b()).isPresent();
    assertThat(SessionAuditReference.isProtected(result.a().orElseThrow().sessionRoot())).isTrue();
    assertThat(SessionAuditReference.isProtected(result.b().orElseThrow().sessionRoot())).isTrue();
    assertThat(result.a().orElseThrow().sessionRoot())
        .startsWith(result.directory().resolve("sessions"));
    assertThat(result.a().orElseThrow().warnings())
        .anyMatch(value -> value.contains("without normal enrichment"));
    assertThat(Files.exists(request.sessionsRoot())).isFalse();
    assertThat(steps)
        .containsSubsequence(
            ReproducibilityCoordinator.Step.PRESERVE_A,
            ReproducibilityCoordinator.Step.CLEAN_B,
            ReproducibilityCoordinator.Step.BUILD_B,
            ReproducibilityCoordinator.Step.PRESERVE_B,
            ReproducibilityCoordinator.Step.SHUTDOWN,
            ReproducibilityCoordinator.Step.CLEANUP);
    var saved = ReproducibilityCoordinator.readSavedOperation(result.directory());
    assertThat(saved.notices())
        .anySatisfy(note -> assertThat(note).contains("Normal rc files", "enabled"));
    assertBindingDisclosure(result.notices(), version);
    assertBindingDisclosure(saved.notices(), version);
    var record = AuditJournal.read(result.directory());
    assertThat(record.getProperty("rcPolicy")).isEqualTo("READ");
    assertThat(record.getProperty("execution.PRESERVE_A.evidenceBinding")).isNotBlank();
    assertThat(record.getProperty("execution.PRESERVE_B.evidenceBinding")).isNotBlank();
    assertThat(saved.sessionA()).contains(result.a().orElseThrow().sessionRoot());
    assertThat(saved.state()).isEqualTo("CAPTURED");
    assertThat(saved.executionLogSha256A()).isPresent();
    assertThat(saved.executionLogSha256B()).isPresent();
    assertThat(saved.sessionB()).contains(result.b().orElseThrow().sessionRoot());
    assertThat(saved.executionLogA()).isNotEqualTo(saved.executionLogB());
    assertInvocationEvidence(
        result.a().orElseThrow(), saved.executionLogA().orElseThrow(), version);
    assertInvocationEvidence(
        result.b().orElseThrow(), saved.executionLogB().orElseThrow(), version);
    try (var comparison =
        ExecutionLogComparison.open(
            saved.executionLogA().orElseThrow(),
            saved.executionLogB().orElseThrow(),
            temporary.resolve("comparison"),
            () -> false,
            saved.executionLogSha256A(),
            saved.executionLogSha256B())) {
      var summary = comparison.summary();
      assertThat(summary.matched()).isPositive();
      assertThat(summary.drift()).isZero();
      assertThat(summary.outputDivergences()).isZero();
      assertThat(summary.downstream()).isZero();
      var page = comparison.page(FilterExpression.ALL, 0, 100);
      assertThat(page.total()).isEqualTo(page.rows().size());
      assertThat(page.rows()).anySatisfy(row -> assertThat(row.target()).isEqualTo("//:t0"));
      assertThat(page.rows())
          .allSatisfy(
              row -> {
                assertThat(row.recipeChanged()).isFalse();
                assertThat(row.inputsChanged()).isFalse();
                assertThat(row.outputsChanged()).isFalse();
                // Missing platform evidence in actual Bazel logs must stay inconclusive, not pass.
                assertThat(row.finding()).isIn(Finding.UNCHANGED, Finding.INCONCLUSIVE);
              });
      if (summary.inconclusive() > 0) {
        assertThat(summary.coverageNotes()).isNotEmpty();
      }
    }
  }

  @Test
  void changedRcOptionsAfterReviewStopBeforeEitherCleanOrBuild(
      @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary) throws Exception {
    String version = BazelBinary.reproducibilityFixtureVersion();
    CaptureRequest request = request(temporary, version);
    Path rc = request.localWorkingDirectory().resolve(".bazelrc");
    String reviewedRc = Files.readString(rc) + "build --action_env=BBV_REVIEWED=before\n";
    Files.writeString(rc, reviewedRc);
    List<ReproducibilityCoordinator.Step> steps = new ArrayList<>();
    try (var audit =
        new ReproducibilityCoordinator(
            request, temporary.resolve("audits"), () -> {}, steps::add)) {
      var review = audit.preflight();
      assertThat(review.canLaunch()).as(review.blockers().toString()).isTrue();
      assertThat(review.protocol().ignoreRcFiles()).isFalse();
      Files.writeString(rc, reviewedRc.replace("BBV_REVIEWED=before", "BBV_REVIEWED=after"));
      var result = audit.run(review);
      assertThat(result.state()).isEqualTo(ReproducibilityCoordinator.State.FAILED);
      assertThat(result.notices())
          .anyMatch(note -> note.contains("rc/build options changed after review"));
      assertThat(result.cleanup())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.Cleanup.REMOVED);
      assertThat(result.a()).isEmpty();
      assertThat(result.b()).isEmpty();
      assertThat(Files.exists(Path.of(review.protocol().outputBase()))).isFalse();
      assertThat(Files.exists(result.directory().resolve("sessions"))).isFalse();
      var saved = ReproducibilityCoordinator.readSavedOperation(result.directory());
      assertThat(saved.state()).isEqualTo("FAILED");
      assertThat(saved.sessionA()).isEmpty();
      assertThat(saved.sessionB()).isEmpty();
      assertThat(AuditJournal.read(result.directory()).getProperty("rcPolicy")).isEqualTo("READ");
    }
    assertThat(steps)
        .doesNotContain(
            ReproducibilityCoordinator.Step.CLEAN_A,
            ReproducibilityCoordinator.Step.BUILD_A,
            ReproducibilityCoordinator.Step.CLEAN_B,
            ReproducibilityCoordinator.Step.BUILD_B);
  }

  @Test
  void explicitlyIgnoringRcRunsWithoutInvalidWorkspaceOptions(
      @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary) throws Exception {
    String version = BazelBinary.reproducibilityFixtureVersion();
    CaptureRequest request = request(temporary, version);
    Files.writeString(
        request.localWorkingDirectory().resolve(".bazelrc"),
        "build --bbv_invalid_fixture_option=true\n");
    try (var audit =
        new ReproducibilityCoordinator(
            request, temporary.resolve("audits"), () -> {}, step -> {})) {
      var review = audit.preflight();
      assertThat(review.protocol().ignoreRcFiles()).isFalse();
      assertThat(review.canLaunch()).isFalse();
      review = audit.setIgnoreRcFiles(true);
      assertThat(review.protocol().ignoreRcFiles()).isTrue();
      assertThat(review.canLaunch()).as(review.blockers().toString()).isTrue();
      var result = audit.run(review);
      assertThat(result.state())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.State.CAPTURED);
      assertThat(result.cleanup()).isEqualTo(ReproducibilityCoordinator.Cleanup.REMOVED);
      assertThat(result.a()).isPresent();
      assertThat(result.b()).isPresent();
      assertThat(Files.exists(Path.of(review.protocol().outputBase()))).isFalse();
      assertThat(AuditJournal.read(result.directory()).getProperty("rcPolicy")).isEqualTo("IGNORE");
      assertThat(ReproducibilityCoordinator.readSavedOperation(result.directory()).notices())
          .anyMatch(note -> note.contains("Rc files are ignored"));
    }
  }

  @Test
  void cancellationBetweenRunsRetainsAAndNeverCleansForB(
      @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary) throws Exception {
    String version = BazelBinary.reproducibilityFixtureVersion();
    CaptureRequest request = request(temporary, version);
    AtomicReference<ReproducibilityCoordinator> active = new AtomicReference<>();
    List<ReproducibilityCoordinator.Step> steps = new ArrayList<>();
    AtomicBoolean released = new AtomicBoolean();
    try (var audit =
        new ReproducibilityCoordinator(
            request,
            temporary.resolve("audits"),
            () -> released.set(true),
            step -> {
              steps.add(step);
              if (step == ReproducibilityCoordinator.Step.SNAPSHOT_BETWEEN) {
                active.get().cancel();
              }
            })) {
      active.set(audit);
      var review = audit.preflight();
      assertThat(review.canLaunch()).as(review.blockers().toString()).isTrue();
      assertBindingDisclosure(review.notices(), version);
      var result = audit.run(review);
      assertThat(result.state())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.State.CANCELLED);
      assertThat(result.a()).isPresent();
      assertThat(result.b()).isEmpty();
      assertThat(result.cleanup()).isEqualTo(ReproducibilityCoordinator.Cleanup.REMOVED);
      assertThat(Files.exists(Path.of(review.protocol().outputBase()))).isFalse();
      var saved = ReproducibilityCoordinator.readSavedOperation(result.directory());
      assertThat(saved.state()).isEqualTo("CANCELLED");
      assertThat(saved.sessionA()).contains(result.a().orElseThrow().sessionRoot());
      assertThat(saved.sessionB()).isEmpty();
      assertThat(saved.executionLogA()).isPresent();
      assertThat(saved.executionLogSha256A()).isPresent();
      assertThat(saved.executionLogB()).isEmpty();
      assertBindingDisclosure(result.notices(), version);
      assertBindingDisclosure(saved.notices(), version);
      var record = AuditJournal.read(result.directory());
      assertThat(record.getProperty("execution.PRESERVE_A.evidenceBinding")).isNotBlank();
      assertThat(record.getProperty("execution.PRESERVE_B.evidenceBinding")).isNull();
      assertInvocationEvidence(
          result.a().orElseThrow(), saved.executionLogA().orElseThrow(), version);
    }
    assertThat(released).isTrue();
    assertThat(steps)
        .doesNotContain(
            ReproducibilityCoordinator.Step.CLEAN_B, ReproducibilityCoordinator.Step.BUILD_B);
  }

  private static void assertBindingDisclosure(List<String> notices, String version) {
    if (version.startsWith("7.4.")) {
      assertThat(notices)
          .anySatisfy(
              note -> assertThat(note).contains("capture-bound", "no embedded invocation ID"));
    }
  }

  private static void assertInvocationEvidence(CaptureResult capture, Path log, String version)
      throws IOException {
    assertThat(Files.size(log)).isPositive().isLessThan(1024 * 1024);
    try (var input = new ZstdInputStream(Files.newInputStream(log))) {
      ExecLogEntry first = ExecLogEntry.parseDelimitedFrom(input);
      assertThat(first).isNotNull();
      assertThat(first.hasInvocation()).isTrue();
      String embedded = first.getInvocation().getId();
      if (version.startsWith("7.4.")) {
        // These releases require capture-bound provenance, not an invented embedded identity.
        assertThat(embedded).isEmpty();
      } else {
        List<String> captured =
            capture.capture().orElseThrow().streams().stream()
                .map(stream -> stream.key().invocationId())
                .distinct()
                .toList();
        assertThat(captured).containsExactly(embedded);
        assertThat(embedded).isNotBlank();
      }
    }
  }

  private static CaptureRequest request(Path temporary, String version) throws Exception {
    return request(temporary, version, List.of("build", "//..."));
  }

  private static CaptureRequest request(Path temporary, String version, List<String> args)
      throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
    Path workspace = BazelWorkspaceFixture.simple(temporary.resolve("workspace"), 1).root();
    Files.writeString(workspace.resolve(".bazelversion"), version + "\n");
    return CaptureRequest.of(
            temporary.resolve("ordinary-sessions"),
            "test",
            bazel.orElseThrow().toString(),
            workspace,
            args)
        .withEnvironment(BazelBinary.VERSION_ENV, Optional.of(version));
  }

  private static Map<Path, Path> createWorkspaceLinks(Path temporary, Path workspace)
      throws IOException {
    Map<Path, Path> links = new LinkedHashMap<>();
    Path normalOutputs = Files.createDirectory(temporary.resolve("normal-outputs"));
    for (String name :
        List.of("bazel-bin", "bazel-out", "bazel-testlogs", "bazel-" + workspace.getFileName())) {
      Path target = Files.createDirectory(normalOutputs.resolve(name));
      Files.writeString(target.resolve("keep.txt"), "ordinary output, not owned by the audit\n");
      Path link = Files.createSymbolicLink(workspace.resolve(name), target);
      links.put(link, target);
    }
    return links;
  }

  private static void assertWorkspaceLinksUnchanged(Map<Path, Path> links) throws IOException {
    for (Map.Entry<Path, Path> entry : links.entrySet()) {
      assertThat(Files.isSymbolicLink(entry.getKey())).isTrue();
      assertThat(Files.readSymbolicLink(entry.getKey())).isEqualTo(entry.getValue());
      assertThat(Files.readString(entry.getValue().resolve("keep.txt")))
          .isEqualTo("ordinary output, not owned by the audit\n");
    }
  }
}
