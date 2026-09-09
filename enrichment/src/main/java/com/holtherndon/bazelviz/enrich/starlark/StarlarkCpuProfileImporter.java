package com.holtherndon.bazelviz.enrich.starlark;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/** Transactional importer for Bazel's gzip-compressed Starlark CPU pprof. */
public final class StarlarkCpuProfileImporter {

  public static final List<String> METRICS_LOST =
      List.of(
          "Starlark hot functions and source files",
          "Starlark caller and callee costs",
          "the Starlark CPU call hierarchy");

  private final Connection connection;
  private final StarlarkCpuProfileParser.Limits limits;
  private final LongSupplier clock;

  public StarlarkCpuProfileImporter(Connection connection) {
    this(
        connection,
        StarlarkCpuProfileParser.DEFAULT_LIMITS,
        () -> System.currentTimeMillis() * 1_000L);
  }

  StarlarkCpuProfileImporter(
      Connection connection, StarlarkCpuProfileParser.Limits limits, LongSupplier clock) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Replaces the current normalized Starlark profile atomically.
   *
   * <p>A malformed, over-limit, or unsupported profile is reported as a failed enrichment task and
   * a failed result. A failed retry rolls back to the last complete row set.
   */
  public Result importFrom(Path file) throws SQLException {
    return importFrom(file, false);
  }

  /** Imports a standalone pprof using its declared default sample type and original units. */
  public Result importPprof(Path file) throws SQLException {
    return importFrom(file, true);
  }

  private Result importFrom(Path file, boolean generic) throws SQLException {
    Objects.requireNonNull(file, "file");
    EnrichmentTaskStore tasks = new EnrichmentTaskStore(connection);
    long taskId =
        tasks.begin(
            EnrichmentTask.Kind.STARLARK_CPU_PROFILE,
            Optional.of(file.toString()),
            clock.getAsLong());

    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      clearPreviousProfile();
      StarlarkCpuProfileParser parser = new StarlarkCpuProfileParser(limits);
      StarlarkCpuProfileParser.ParseResult parsed;
      StarlarkProfileWriter.ImportSummary imported;
      try (StarlarkProfileWriter writer = new StarlarkProfileWriter(connection)) {
        parsed = parser.parse(file, writer);
        imported = writer.finish(taskId, parsed, generic);
      }
      connection.commit();

      String summary =
          imported.sampleCount()
              + " samples, "
              + imported.totalValue()
              + (generic ? " selected sample units" : " CPU microseconds");
      tasks.finish(
          taskId,
          EnrichmentTask.State.SUCCEEDED,
          Optional.of(summary),
          Optional.empty(),
          false,
          List.of(),
          OptionalLong.of(Math.addExact(parsed.topLevelRecords(), parsed.childRecords())),
          OptionalLong.empty(),
          clock.getAsLong());
      connection.commit();
      return new Result(
          EnrichmentTask.State.SUCCEEDED,
          imported.sampleCount(),
          imported.totalValue(),
          Optional.empty());
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
    } catch (SQLException failure) {
      rollbackQuietly();
      String message = failure.getMessage() == null ? "SQL failure" : failure.getMessage();
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

  private void clearPreviousProfile() throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM starlark_call_edges");
      statement.executeUpdate("DELETE FROM starlark_file_metrics");
      statement.executeUpdate("DELETE FROM starlark_function_metrics");
      statement.executeUpdate("DELETE FROM starlark_call_nodes");
      statement.executeUpdate("DELETE FROM starlark_profile_sample_labels");
      statement.executeUpdate("DELETE FROM starlark_profile_sample_frames");
      statement.executeUpdate("DELETE FROM starlark_profile_sample_values");
      statement.executeUpdate("DELETE FROM starlark_profile_samples");
      statement.executeUpdate("DELETE FROM starlark_profile_location_lines");
      statement.executeUpdate("DELETE FROM starlark_profile_locations");
      statement.executeUpdate("DELETE FROM starlark_profile_functions");
      statement.executeUpdate("DELETE FROM starlark_profile_mappings");
      statement.executeUpdate("DELETE FROM starlark_profile_sample_types");
      statement.executeUpdate("DELETE FROM starlark_profile_strings");
      statement.executeUpdate("DELETE FROM starlark_profile_metadata");
    }
  }

  private void rollbackQuietly() {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // The original parse or write failure is the useful one.
    }
  }

  /** What one import attempt produced. Values use the validated microsecond sample type. */
  public record Result(
      EnrichmentTask.State state, long samplesRead, long totalCpuMicros, Optional<String> error) {

    public Result {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(error, "error");
    }

    static Result failed(String message) {
      return new Result(EnrichmentTask.State.FAILED, 0, 0, Optional.of(message));
    }
  }
}
