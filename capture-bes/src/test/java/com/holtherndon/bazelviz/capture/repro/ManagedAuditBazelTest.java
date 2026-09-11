package com.holtherndon.bazelviz.capture.repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison.Finding;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison;
import com.holtherndon.bazelviz.format.session.SessionAuditReference;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One pinned private server at a time; never cleans the outer test repository's output base. */
final class ManagedAuditBazelTest {
  @Test
  void capturesTwoRunsWithBoundedVerificationAndCleansOnlyItsOwnedBase(@TempDir Path temporary)
      throws Exception {
    CaptureRequest request = request(temporary);
    AtomicBoolean released = new AtomicBoolean();
    List<ReproducibilityCoordinator.Step> steps = new ArrayList<>();
    ReproducibilityCoordinator.Result result;
    try (var audit =
        new ReproducibilityCoordinator(
            request, temporary.resolve("audits"), () -> released.set(true), steps::add)) {
      var review = audit.preflight();
      assertThat(review.canLaunch()).as(review.blockers().toString()).isTrue();
      assertThat(review.a().plan().auxiliaryCommands()).isEmpty();
      assertThat(review.b().plan().auxiliaryCommands()).isEmpty();
      result = audit.run(review);
      assertThat(result.state())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.State.CAPTURED);
      assertThat(result.cleanup())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.Cleanup.REMOVED);
      assertThat(Files.exists(Path.of(review.protocol().outputBase()))).isFalse();
    }
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
    assertThat(saved.sessionA()).contains(result.a().orElseThrow().sessionRoot());
    assertThat(saved.state()).isEqualTo("CAPTURED");
    assertThat(saved.executionLogSha256A()).isPresent();
    assertThat(saved.executionLogSha256B()).isPresent();
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
  void cancellationBetweenRunsRetainsAAndNeverCleansForB(@TempDir Path temporary) throws Exception {
    CaptureRequest request = request(temporary);
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
      var result = audit.run(review);
      assertThat(result.state())
          .as(result.notices().toString())
          .isEqualTo(ReproducibilityCoordinator.State.CANCELLED);
      assertThat(result.a()).isPresent();
      assertThat(result.b()).isEmpty();
      assertThat(result.cleanup()).isEqualTo(ReproducibilityCoordinator.Cleanup.REMOVED);
    }
    assertThat(released).isTrue();
    assertThat(steps)
        .doesNotContain(
            ReproducibilityCoordinator.Step.CLEAN_B, ReproducibilityCoordinator.Step.BUILD_B);
  }

  private static CaptureRequest request(Path temporary) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
    Path workspace = BazelWorkspaceFixture.simple(temporary.resolve("workspace"), 1).root();
    Files.writeString(workspace.resolve(".bazelversion"), "9.2.0\n");
    return CaptureRequest.of(
            temporary.resolve("ordinary-sessions"),
            "test",
            bazel.orElseThrow().toString(),
            workspace,
            List.of("build", "//..."))
        .withEnvironment(BazelBinary.VERSION_ENV, Optional.of("9.2.0"));
  }
}
