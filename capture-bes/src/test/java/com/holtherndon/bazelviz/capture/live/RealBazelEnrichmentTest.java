package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilityDetector;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import com.holtherndon.bazelviz.testsupport.bazel.BazelWorkspaceFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phase 4 end to end: a real build, instrumented by the planner, captured, and enriched.
 *
 * <p>This is the test that proves the phase is not a library nobody calls. The planner adds the
 * execution-log and profile flags, Bazel writes the files, the coordinator imports them, and the
 * attempts land correlated to the actions that produced them — with no fixture and no hand-written
 * path anywhere.
 *
 * <p>Skipped, not failed, when no Bazel is on the machine.
 */
@Tag("real-bazel")
class RealBazelEnrichmentTest {

  @Test
  @DisplayName("the planner's flags produce files, and those files become attempts")
  void enrichmentHappensWithoutBeingAskedTwice(@TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 4);
    CaptureResult result = capture(directory, bazel.orElseThrow(), workspace, Optional.empty());

    assertThat(result.buildSucceeded()).describedAs("warnings: %s", result.warnings()).isTrue();

    // Bazel actually wrote what the plan asked for.
    Path raw = ManagedSessionLayout.at(result.sessionRoot()).rawDirectory();
    assertThat(Files.list(raw).map(path -> path.getFileName().toString()).toList())
        .describedAs("raw directory contents")
        .anyMatch(name -> name.startsWith("execution-log"))
        .anyMatch(name -> name.equals("profile.json"))
        .anyMatch(name -> name.equals(InstrumentationPlanner.STARLARK_CPU_PROFILE_FILE));

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();

      // Attempts exist and are attached. This is the whole of Phase 4 in
      // one assertion.
      assertThat(scalar(c, "SELECT COUNT(*) FROM action_attempts")).isPositive();
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM action_attempts"
                      + " WHERE correlation = 'MATCHED_BY_OUTPUT' AND action_id IS NOT NULL"))
          .isPositive();

      // Every attempt says where it ran.
      assertThat(scalar(c, "SELECT COUNT(*) FROM action_attempts WHERE runner IS NULL")).isZero();

      // The execution log covers fewer actions than the BEP publishes,
      // which is the measured shape and not a defect (K1).
      long actions = scalar(c, "SELECT COUNT(*) FROM actions");
      long covered =
          scalar(
              c,
              "SELECT COUNT(DISTINCT action_id) FROM action_attempts"
                  + " WHERE action_id IS NOT NULL");
      assertThat(covered).isPositive().isLessThan(actions);

      // The profile landed too, with phases and Bazel's critical path.
      assertThat(scalar(c, "SELECT COUNT(*) FROM build_phases")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM profile_spans")).isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM profile_counters")).isPositive();

      // --noslim_profile and --experimental_profile_include_primary_output
      // did their jobs: without either, this is zero (X3, P4).
      assertThat(scalar(c, "SELECT COUNT(*) FROM profile_spans WHERE primary_output IS NOT NULL"))
          .isPositive();
      assertThat(scalar(c, "SELECT COUNT(*) FROM profile_spans WHERE action_id IS NOT NULL"))
          .isPositive();

      // The anchor's meaning was recorded rather than assumed.
      assertThat(text(c, "SELECT anchor_meaning FROM profile_metadata"))
          .isIn("EXACT_START", "START_FLOORED_TO_SECOND");
      // And the profile was checked against this session's build.
      assertThat(scalar(c, "SELECT build_id_matches FROM profile_metadata")).isEqualTo(1);

      // The execution log, trace profile and Starlark CPU profile all
      // recorded themselves.
      assertThat(scalar(c, "SELECT COUNT(*) FROM enrichment_tasks WHERE state = 'SUCCEEDED'"))
          .isEqualTo(3);

      // The real gzip pprof made it through capture, parsing,
      // validation, and derivation. A very short invocation may have no
      // samples, so format and units are the stable contract.
      assertThat(text(c, "SELECT format FROM starlark_profile_metadata")).isEqualTo("pprof-gzip");
      assertThat(text(c, "SELECT validation_state FROM starlark_profile_metadata"))
          .isEqualTo("VALID");
      assertThat(
              scalar(
                  c,
                  "SELECT COUNT(*) FROM starlark_profile_sample_types st"
                      + " JOIN starlark_profile_strings type"
                      + " ON type.string_index = st.type_string_index"
                      + " JOIN starlark_profile_strings unit"
                      + " ON unit.string_index = st.unit_string_index"
                      + " WHERE type.value = 'CPU' AND unit.value = 'microseconds'"))
          .isEqualTo(1);

      // Nothing was dropped to keep the schema tidy.
      try (Statement s = c.createStatement();
          ResultSet rows = s.executeQuery("PRAGMA foreign_key_check")) {
        assertThat(rows.next()).describedAs("a foreign key violation exists").isFalse();
      }
    }
  }

  @ParameterizedTest(name = "Bazel {0}")
  @ValueSource(strings = {"6.5.0", "7.6.1", "8.4.1", "9.2.0"})
  @DisplayName("every supported version enriches, and 6.5.0 admits it has no spawn starts")
  void everyVersionEnriches(String version, @TempDir Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);

    BazelWorkspaceFixture workspace = BazelWorkspaceFixture.simple(directory.resolve("ws"), 3);
    CaptureResult result = capture(directory, bazel.orElseThrow(), workspace, Optional.of(version));
    assumeTrue(result.buildSucceeded(), () -> "build failed: " + result.warnings());

    try (SessionDatabase database = open(result)) {
      Connection c = database.newReadConnection();
      assertThat(scalar(c, "SELECT COUNT(*) FROM action_attempts")).isPositive();
      assertThat(text(c, "SELECT format FROM starlark_profile_metadata")).isEqualTo("pprof-gzip");
      assertThat(text(c, "SELECT validation_state FROM starlark_profile_metadata"))
          .isEqualTo("VALID");

      long withStart =
          scalar(c, "SELECT COUNT(*) FROM action_attempts WHERE start_micros IS NOT NULL");
      long withReason =
          scalar(c, "SELECT COUNT(*) FROM action_attempts WHERE start_unknown_reason IS NOT NULL");
      long total = scalar(c, "SELECT COUNT(*) FROM action_attempts");

      if (version.equals("6.5.0")) {
        // Measured: 6.5.0 never emits start_time under any flag (S2).
        // An attempt has a length and no position, and the row says so
        // rather than leaving a blank that reads as "instantaneous".
        assertThat(withStart).isZero();
        assertThat(withReason).isEqualTo(total);
        assertThat(scalar(c, "SELECT COUNT(*) FROM action_attempts WHERE total_micros IS NOT NULL"))
            .isPositive();
      } else {
        assertThat(withStart).isPositive();
      }

      // Every version's attempt has a duration one way or another.
      assertThat(scalar(c, "SELECT COUNT(*) FROM action_attempts WHERE total_micros IS NULL"))
          .isZero();
    }
  }

  // ---------------------------------------------------------------- helpers

  private static CaptureResult capture(
      Path directory, Path bazel, BazelWorkspaceFixture workspace, Optional<String> version)
      throws Exception {
    Path sessionsRoot = directory.resolve("sessions");
    List<String> command = new ArrayList<>(List.of("build", "//..."));
    CaptureRequest request =
        CaptureRequest.of(sessionsRoot, "test", bazel.toString(), workspace.root(), command)
            // The preset that asks for the execution log and both profiles.
            .withPreset(CapturePreset.PERFORMANCE_DIAGNOSTICS)
            .withEnvironment(BazelBinary.VERSION_ENV, version);
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

  private static long scalar(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1L;
    }
  }

  private static String text(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement();
        ResultSet rows = s.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }
}
