package com.holtherndon.bazelviz.enrich.profile;

import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import com.holtherndon.bazelviz.storage.enrich.ProfileWriter;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Imports a JSON trace profile into a session.
 *
 * <p>Independent of the execution-log import in both directions: neither reads the other's rows,
 * and either can fail without the other noticing. Plan 21.4 says a failed profile import must not
 * invalidate BEP, and this one cannot, because it writes only to the profile tables schema v4
 * added.
 */
public final class ProfileImporter {

  /** What the user loses when the profile does not import, in the UI's words. */
  static final List<String> METRICS_LOST =
      List.of(
          "how long each build phase took",
          "Bazel's own critical path",
          "CPU, memory and load over the course of the build",
          "per-action spans on the timeline");

  private final Connection connection;
  private final LongSupplier clock;

  public ProfileImporter(Connection connection) {
    this(connection, () -> System.currentTimeMillis() * 1_000L);
  }

  ProfileImporter(Connection connection, LongSupplier clock) {
    this.connection = connection;
    this.clock = clock;
  }

  /**
   * Imports {@code file}.
   *
   * <p>Never throws for a bad profile: the failure becomes a task row and a result. A profile from
   * a different build is imported anyway and flagged, rather than refused as an execution log is —
   * the profile's numbers are about the machine and the phases, and a user comparing two builds
   * deliberately is doing something reasonable. The flag is what stops it being an accident.
   */
  public Result importFrom(Path file) throws SQLException {
    EnrichmentTaskStore tasks = new EnrichmentTaskStore(connection);
    long taskId =
        tasks.begin(EnrichmentTask.Kind.PROFILE, Optional.of(file.toString()), clock.getAsLong());

    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      clearPreviousProfile();
      Result result = read(file, taskId);
      connection.commit();
      tasks.finish(
          taskId,
          EnrichmentTask.State.SUCCEEDED,
          Optional.of(result.summary()),
          Optional.empty(),
          false,
          List.of(),
          OptionalLong.of(result.eventsRead()),
          OptionalLong.empty(),
          clock.getAsLong());
      connection.commit();
      return result;
    } catch (IOException | RuntimeException failure) {
      rollbackQuietly();
      String message =
          failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
      tasks.finish(
          taskId,
          EnrichmentTask.State.FAILED,
          Optional.of("failed"),
          Optional.of(message),
          true,
          METRICS_LOST,
          OptionalLong.empty(),
          OptionalLong.empty(),
          clock.getAsLong());
      connection.commit();
      return Result.failed(message);
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }

  /**
   * Replaces profile-derived data inside the import transaction.
   *
   * <p>A failed retry rolls this deletion back and leaves the last complete profile intact for
   * recovery, while the FAILED task state prevents readers from presenting those retained rows as
   * current.
   */
  private void clearPreviousProfile() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM profile_spans");
      statement.executeUpdate("DELETE FROM profile_counters");
      statement.executeUpdate("DELETE FROM profile_threads");
      statement.executeUpdate("DELETE FROM build_phases");
      statement.executeUpdate("DELETE FROM bazel_critical_path");
      statement.executeUpdate("DELETE FROM profile_metadata");
    }
  }

  private Result read(Path file, long taskId) throws IOException, SQLException {
    Optional<String> sessionBuildId = sessionInvocationId();
    try (Reader reader =
            new InputStreamReader(
                new BufferedInputStream(Files.newInputStream(file), 1 << 16),
                StandardCharsets.UTF_8);
        ProfileWriter writer = new ProfileWriter(connection, taskId, sessionBuildId)) {

      ProfileParser parser = new ProfileParser(command -> write(writer, command));
      parser.parse(reader);
      writer.finish();

      return new Result(
          EnrichmentTask.State.SUCCEEDED,
          parser.keptEvents() + parser.skippedEvents(),
          parser.keptEvents(),
          parser.skippedEvents(),
          writer.spansWritten(),
          writer.attributedSpans(),
          writer.buildIdMatches(),
          Optional.empty());
    }
  }

  private static void write(ProfileWriter writer, EnrichmentCommand command) {
    try {
      writer.apply(command);
    } catch (SQLException failure) {
      throw new UncheckedWriteException(failure);
    }
  }

  private Optional<String> sessionInvocationId() throws SQLException {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT invocation_id FROM build_invocation WHERE singleton = 1");
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
    }
  }

  private void rollbackQuietly() {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The failure being reported is the one worth reporting.
    }
  }

  /**
   * What an import did.
   *
   * @param skippedEvents events read and not kept. Reported because the plan asks for "selected
   *     spans" and the user is entitled to know how selective that was.
   * @param attributedSpans spans carrying a primary output. When this is zero and {@code
   *     spansWritten} is not, the build was captured without {@code
   *     --experimental_profile_include_primary_output} and no span can be tied to an action (P4).
   * @param buildIdMatches empty when one of the two ids was missing, so the question could not be
   *     asked
   */
  public record Result(
      EnrichmentTask.State state,
      long eventsRead,
      long keptEvents,
      long skippedEvents,
      long spansWritten,
      long attributedSpans,
      Optional<Boolean> buildIdMatches,
      Optional<String> error) {

    static Result failed(String message) {
      return new Result(
          EnrichmentTask.State.FAILED, 0, 0, 0, 0, 0, Optional.empty(), Optional.of(message));
    }

    String summary() {
      return spansWritten + " spans of " + eventsRead + " events";
    }

    /** True when spans exist but none can be tied to an action. */
    public boolean spansCannotBeAttributed() {
      return spansWritten > 0 && attributedSpans == 0;
    }
  }

  /** Carries a {@link SQLException} out of a consumer. */
  static final class UncheckedWriteException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UncheckedWriteException(SQLException cause) {
      super(cause.getMessage(), cause);
    }
  }
}
