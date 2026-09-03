package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilityDetector;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Phase 3 exit criteria against a real Bazel: that a build's events become coherent target,
 * action, test and artifact records.
 *
 * <p>Everything else about normalization is tested against commands built by hand, which proves the
 * writer does what it is told. This proves the translator was told the right things — that the
 * shapes measured in {@code docs/bep-content.md} are the shapes this machine's Bazel actually
 * emits, and that the whole path from a gRPC frame to a queryable row holds together.
 *
 * <p>Skipped, not failed, when no Bazel is on the machine.
 */
@Tag("real-bazel")
class RealBazelNormalizationTest {

  @Test
  @DisplayName("a successful build normalizes into targets, actions, configurations and file sets")
  void successfulBuildProducesCoherentRecords(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 4);
    CaptureResult result =
        capture(directory, bazel.orElseThrow(), workspace, hermetic("build", "//..."));

    assertThat(result.buildSucceeded()).describedAs("warnings: %s", result.warnings()).isTrue();

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      // The invocation row is what makes every NULL elsewhere readable.
      assertThat(text(c, "SELECT build_tool_version FROM build_invocation")).isNotBlank();
      assertThat(text(c, "SELECT command FROM build_invocation")).isEqualTo("build");
      assertThat(scalar(c, "SELECT overall_success FROM build_invocation")).isEqualTo(1);
      assertThat(scalar(c, "SELECT finished_micros > started_micros FROM build_invocation"))
          .isEqualTo(1);

      // Four genrules, each configured and each completed.
      assertThat(scalar(c, "SELECT COUNT(*) FROM targets")).isGreaterThanOrEqualTo(4);
      assertThat(scalar(c, "SELECT COUNT(*) FROM configured_targets WHERE outcome = 'BUILT'"))
          .isGreaterThanOrEqualTo(4);
      assertThat(labels(c, "SELECT l.value FROM targets t JOIN labels l ON l.id = t.label_id"))
          .contains("//:t0", "//:t3");

      // Actions arrive because the preset asks for all of them. Without
      // that flag only failures appear, and an empty table would look
      // like a build that did no work.
      assertThat(scalar(c, "SELECT publishes_all_actions FROM build_invocation")).isEqualTo(1);
      assertThat(scalar(c, "SELECT COUNT(*) FROM actions")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM actions WHERE outcome = 'SUCCEEDED'"))
          .isPositive();

      // Every action's identity is its primary output, and the table's
      // UNIQUE constraint held across a real stream.
      assertThat(scalar(c, "SELECT COUNT(DISTINCT primary_output) FROM actions"))
          .isEqualTo(scalar(c, "SELECT COUNT(*) FROM actions"));

      // The measured absence, confirmed on this machine's Bazel rather
      // than assumed: a configuration id is referenced that Bazel never
      // publishes a Configuration event for. The placeholder row is what
      // keeps the referencing actions in the table.
      //
      // This assertion used to read isGreaterThanOrEqualTo(0), which is
      // true of every count there has ever been.
      assertThat(scalar(c, "SELECT COUNT(*) FROM configurations WHERE declared = 0")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM configurations WHERE declared = 1")).isPositive();
      assertThat(labels(c, "SELECT bep_id FROM configurations WHERE declared = 0"))
          .contains("system");

      // Outputs are reachable only through the file sets on Bazel 8+, so
      // the depset graph has to be real, not incidental.
      assertThat(scalar(c, "SELECT COUNT(*) FROM depsets")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM depset_files")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM artifacts")).isPositive();
      assertThat(
              scalar(
                  c, "SELECT COUNT(*) FROM target_output_groups WHERE root_depset_id IS NOT NULL"))
          .isPositive();

      // A named set referenced but never defined would be evidence of
      // truncation. There were none in 1,829 measured references, and
      // there are none here.
      assertThat(scalar(c, "SELECT COUNT(*) FROM depsets WHERE bep_event_id IS NULL")).isZero();

      // Every foreign key resolved: nothing was dropped to protect
      // referential tidiness and nothing points at a row that is not there.
      assertThat(foreignKeyViolations(c)).isEmpty();

      // Provenance: every normalized row can name the event it came from.
      assertThat(scalar(c, "SELECT COUNT(*) FROM targets WHERE bep_event_id IS NULL")).isZero();
      assertThat(scalar(c, "SELECT COUNT(*) FROM actions WHERE bep_event_id IS NULL")).isZero();

      // Bazel's own counters arrived and are not zeroes standing in for
      // silence.
      assertThat(scalar(c, "SELECT COUNT(*) FROM build_metrics")).isEqualTo(1);
      assertThat(scalar(c, "SELECT actions_created FROM build_metrics")).isPositive();

      // The stream reached its end marker, so the overview must not tell
      // the user their capture was truncated. Aborted events arrive after
      // buildFinished, so this flag is what says the failed-target list
      // is complete.
      assertThat(scalar(c, "SELECT saw_last_message FROM build_invocation")).isEqualTo(1);

      // A live capture builds its indexes like an import does. Without
      // this every view query on a captured session is a scan, and
      // nothing about the session says so.
      assertThat(indexNames(c))
          .contains(
              "idx_bep_events_sequence",
              "idx_actions_start",
              "idx_actions_label",
              "idx_configured_targets_target",
              "idx_test_attempts_test");
    }
  }

  @Test
  @DisplayName("a failing build records which action failed and what it really exited with")
  void failingBuildRecordsTheFailure(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.withFailure(directory.resolve("ws"), 2);
    CaptureResult result =
        capture(
            directory, bazel.orElseThrow(), workspace, hermetic("build", "--keep_going", "//..."));

    assertThat(result.buildSucceeded()).isFalse();
    assertThat(result.captureComplete()).describedAs("warnings: %s", result.warnings()).isTrue();

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      assertThat(scalar(c, "SELECT overall_success FROM build_invocation")).isZero();
      assertThat(text(c, "SELECT exit_code_name FROM build_invocation")).isNotBlank();

      // The failing action is there, with the failure detail attached.
      assertThat(scalar(c, "SELECT COUNT(*) FROM actions WHERE outcome = 'FAILED'")).isPositive();
      assertThat(text(c, "SELECT failure_category FROM actions WHERE outcome = 'FAILED' LIMIT 1"))
          .startsWith("spawn");
      // The genrule exits 1, and so does Bazel's own exit_code field --
      // but they are stored separately, because for any other exit code
      // they disagree and only one of them is the process's.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM actions WHERE outcome = 'FAILED'"
                      + " AND failure_message IS NOT NULL"))
          .isPositive();

      assertThat(scalar(c, "SELECT COUNT(*) FROM configured_targets WHERE outcome = 'FAILED'"))
          .isPositive();
      // Under --keep_going the siblings still build, so the session shows
      // a partial failure rather than a blanket one.
      assertThat(scalar(c, "SELECT COUNT(*) FROM configured_targets WHERE outcome = 'BUILT'"))
          .isPositive();

      // The compiler's own text lives only in progress events, so the
      // failures view needs to be able to find them.
      assertThat(scalar(c, "SELECT COUNT(*) FROM progress_output WHERE stderr_bytes > 0"))
          .isPositive();

      assertThat(foreignKeyViolations(c)).isEmpty();
    }
  }

  @Test
  @DisplayName("tests normalize into verdicts and attempts, and the verdict is not the target's")
  void testsProduceAttemptsAndVerdicts(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace =
        BazelWorkspaceFixture.withTests(directory.resolve("ws"), 2, 1);
    CaptureResult result =
        capture(
            directory,
            bazel.orElseThrow(),
            workspace,
            hermetic("test", "--keep_going", "--flaky_test_attempts=2", "//..."));

    // The build "fails" because a test failed. That is the point.
    assertThat(result.captureComplete()).describedAs("warnings: %s", result.warnings()).isTrue();

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      assertThat(scalar(c, "SELECT COUNT(*) FROM tests")).isEqualTo(3);
      assertThat(scalar(c, "SELECT COUNT(*) FROM tests WHERE overall_status = 'PASSED'"))
          .isEqualTo(2);
      assertThat(scalar(c, "SELECT COUNT(*) FROM tests WHERE overall_status = 'FAILED'"))
          .isEqualTo(1);

      // The whole reason tests have their own tables: the target reports
      // success for a test that failed, so the verdict cannot come from
      // there.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM tests te"
                      + " JOIN configured_targets ct ON ct.id = te.configured_target_id"
                      + " WHERE te.overall_status = 'FAILED' AND ct.outcome = 'BUILT'"))
          .isEqualTo(1);

      // --flaky_test_attempts=2 makes the failing test run twice, and
      // both attempts are kept. Keeping only the last would leave no
      // evidence of what happened.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM test_attempts ta"
                      + " JOIN tests te ON te.id = ta.test_id"
                      + " WHERE te.overall_status = 'FAILED'"))
          .isEqualTo(2);
      assertThat(scalar(c, "SELECT COUNT(*) FROM test_attempts WHERE status = 'FAILED'"))
          .isEqualTo(2);

      // Every attempt is 1-based on every measured version, so no row
      // should carry a zero it never had.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM test_attempts"
                      + " WHERE run < 1 OR shard < 1 OR attempt < 1"))
          .isZero();

      // The effective timeout is the only thing that makes a TIMEOUT
      // status interpretable, and it arrives only under `bazel test`.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM configured_targets"
                      + " WHERE test_timeout_seconds IS NOT NULL"))
          .isEqualTo(3);

      // The logs are recorded once each, not once per source.
      assertThat(scalar(c, "SELECT COUNT(*) FROM test_logs")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(DISTINCT uri) FROM test_logs"))
          .isEqualTo(scalar(c, "SELECT COUNT(*) FROM test_logs"));

      // The tag the fixture wrote, and whatever Bazel appended, stay
      // distinguishable.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM target_tags"
                      + " WHERE tag = 'fixture-tag' AND from_event = 'CONFIGURED'"))
          .isEqualTo(3);

      assertThat(foreignKeyViolations(c)).isEmpty();
    }
  }

  @Test
  @DisplayName("a build interrupted during analysis still names its targets")
  void abortedTargetsAreRecorded(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 3);
    // A pattern that cannot resolve: analysis fails, and the `aborted`
    // events that say so arrive after buildFinished.
    CaptureResult result =
        capture(
            directory,
            bazel.orElseThrow(),
            workspace,
            hermetic("build", "--keep_going", "//...", "//nosuchpackage:nosuchtarget"));

    assertThat(result.buildSucceeded()).isFalse();

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      // An ingest that stopped at buildFinished would find none of these.
      assertThat(scalar(c, "SELECT COUNT(*) FROM aborted_events")).isPositive();
      assertThat(text(c, "SELECT reason FROM aborted_events LIMIT 1")).isNotBlank();
      assertThat(foreignKeyViolations(c)).isEmpty();
    }
  }

  @ParameterizedTest(name = "Bazel {0}")
  @MethodSource("targetedVersions")
  @DisplayName("one build normalizes coherently on every supported Bazel")
  void everySupportedVersionNormalizes(String version, @TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace =
        BazelWorkspaceFixture.withTests(directory.resolve("ws"), 1, 1);
    CaptureResult result =
        capture(
            directory,
            bazel.orElseThrow(),
            workspace,
            hermetic("test", "--keep_going", "//..."),
            Optional.of(version));

    assertThat(result.captureComplete())
        .describedAs("Bazel %s warnings: %s", version, result.warnings())
        .isTrue();

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      assertThat(text(c, "SELECT build_tool_version FROM build_invocation")).isEqualTo(version);

      // The parts that exist on every version. If any of these is empty,
      // the view for it would be empty on that Bazel and nothing would
      // say why.
      assertThat(scalar(c, "SELECT COUNT(*) FROM targets")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM configured_targets")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM actions")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM configurations WHERE declared = 1")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM tests")).isEqualTo(2);
      assertThat(scalar(c, "SELECT COUNT(*) FROM test_attempts")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM build_metrics")).isEqualTo(1);

      // Outputs are reachable through the file sets on every version --
      // which matters because importantOutput, the other route, is gone
      // by default from Bazel 8.
      assertThat(scalar(c, "SELECT COUNT(*) FROM depset_files")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM depsets WHERE bep_event_id IS NULL")).isZero();

      // Action timing is the sharpest version difference there is, and
      // the rule is the same on all four: a duration is either derivable
      // or explicitly unknown, never silently zero.
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM actions"
                      + " WHERE start_micros IS NOT NULL AND end_micros IS NOT NULL"
                      + " AND end_micros <= start_micros"
                      + " AND duration_unknown_reason IS NULL"))
          .isZero();
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM actions"
                      + " WHERE start_micros IS NULL AND duration_unknown_reason IS NULL"))
          .isZero();
      if (version.startsWith("6.") || version.startsWith("7.")) {
        // These versions publish no action timestamps at all.
        assertThat(scalar(c, "SELECT COUNT(*) FROM actions WHERE start_micros IS NOT NULL"))
            .isZero();
      }

      // The verdict never comes from the target's success flag.
      assertThat(scalar(c, "SELECT COUNT(*) FROM tests WHERE overall_status = 'FAILED'"))
          .isEqualTo(1);

      assertThat(foreignKeyViolations(c)).isEmpty();
    }
  }

  static List<String> targetedVersions() {
    return BazelBinary.targetedVersions();
  }

  @Test
  @Timeout(300)
  @DisplayName("a session can be read while the build that is writing it still runs")
  void theSessionIsReadableDuringTheCapture(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    // Chained sleeps, so the build is still running while this reads.
    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.slow(directory.resolve("ws"), 6, 3);
    Path sessionsRoot = directory.resolve("sessions");
    CaptureRequest request =
        CaptureRequest.of(
                sessionsRoot,
                "test",
                bazel.orElseThrow().toString(),
                workspace.root(),
                hermetic("build", "//..."))
            .withPreset(CapturePreset.LIVE_ESSENTIALS);

    long duringCapture;
    try (CaptureCoordinator coordinator =
        new CaptureCoordinator(
            request,
            new SessionManager(sessionsRoot, request.appVersion()),
            new BazelCapabilityDetector(),
            Clock.systemUTC())) {
      coordinator.preflight();
      Thread building =
          new Thread(
              () -> {
                try {
                  coordinator.run();
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              },
              "capture");
      building.setDaemon(true);
      building.start();

      // The whole of the "live overview" deliverable rests on this: the
      // session directory exists, and its database answers, before the
      // build ends. Nothing exercised it, which is how the overview came
      // to be attached only after the capture finished.
      Path root = awaitSessionRoot(coordinator);
      duringCapture = awaitReadableOverview(root);
      building.join(TimeUnit.MINUTES.toMillis(4));
    }

    assertThat(duringCapture)
        .as("the overview answered while the build was still running")
        .isGreaterThanOrEqualTo(0);
  }

  private static Path awaitSessionRoot(CaptureCoordinator coordinator) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    while (System.nanoTime() < deadline) {
      Optional<Path> root = coordinator.sessionRoot();
      if (root.isPresent()) {
        return root.get();
      }
      TimeUnit.MILLISECONDS.sleep(20);
    }
    throw new AssertionError("the capture never published a session root");
  }

  /**
   * Reads the growing session until the overview answers.
   *
   * <p>Retried rather than asserted once: the manifest and the database are written early but not
   * instantly, and a reader that arrived a millisecond too soon would fail for a reason that says
   * nothing about the design.
   */
  private static long awaitReadableOverview(Path root) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
    Exception last = null;
    while (System.nanoTime() < deadline) {
      try (SessionDatabase database =
          SessionDatabase.open(ManagedSessionLayout.at(root).databaseFile())) {
        Connection c = database.newReadConnection();
        long events = scalar(c, "SELECT COUNT(*) FROM bep_events");
        if (events > 0) {
          // The entity tables answer too, which is the part that
          // would have been missing had the schema not been applied.
          return scalar(c, "SELECT COUNT(*) FROM targets");
        }
      } catch (Exception notYet) {
        last = notYet;
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new AssertionError("the session never became readable during the capture", last);
  }

  // --- helpers ---------------------------------------------------------

  private static CaptureResult capture(
      Path directory, Path bazel, BazelWorkspaceFixture workspace, List<String> command)
      throws Exception {
    return capture(directory, bazel, workspace, command, Optional.empty());
  }

  private static CaptureResult capture(
      Path directory,
      Path bazel,
      BazelWorkspaceFixture workspace,
      List<String> command,
      Optional<String> bazelVersion)
      throws Exception {
    Path sessionsRoot = directory.resolve("sessions");
    CaptureRequest request =
        CaptureRequest.of(sessionsRoot, "test", bazel.toString(), workspace.root(), command)
            .withPreset(CapturePreset.LIVE_ESSENTIALS)
            .withEnvironment(BazelBinary.VERSION_ENV, bazelVersion);
    try (CaptureCoordinator coordinator =
        new CaptureCoordinator(
            request,
            new SessionManager(sessionsRoot, request.appVersion()),
            new BazelCapabilityDetector(),
            Clock.systemUTC())) {
      coordinator.preflight();
      return coordinator.run();
    }
  }

  private static SessionDatabase open(CaptureResult result) throws SQLException {
    return SessionDatabase.open(ManagedSessionLayout.at(result.sessionRoot()).databaseFile());
  }

  private static List<String> hermetic(String... command) {
    List<String> argv = new ArrayList<>(BazelWorkspaceFixture.hermeticStartupOptions());
    argv.addAll(List.of(command));
    return List.copyOf(argv);
  }

  private static List<String> indexNames(Connection c) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Statement statement = c.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index'")) {
      while (rows.next()) {
        names.add(rows.getString(1));
      }
    }
    return names;
  }

  /** SQLite's own answer to "does every foreign key point at a row". */
  private static List<String> foreignKeyViolations(Connection c) throws SQLException {
    List<String> violations = new ArrayList<>();
    try (Statement statement = c.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
      while (rows.next()) {
        violations.add(rows.getString(1) + " row " + rows.getLong(2) + " -> " + rows.getString(3));
      }
    }
    return violations;
  }

  private static long scalar(Connection c, String sql) throws SQLException {
    try (Statement statement = c.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1L;
    }
  }

  private static String text(Connection c, String sql) throws SQLException {
    try (Statement statement = c.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  private static List<String> labels(Connection c, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Statement statement = c.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }
}
