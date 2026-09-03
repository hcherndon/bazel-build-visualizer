package com.holtherndon.bazelviz.enrich.graph;

import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/** Writes an auxiliary query's scope from completed top-level labels the BEP reported. */
public final class BepTargetQueryFile {

  private static final String LABELS =
      "SELECT l.value FROM targets t JOIN labels l ON l.id = t.label_id"
          + " WHERE EXISTS (SELECT 1 FROM configured_targets ct"
          + " WHERE ct.target_id = t.id AND ct.outcome IN ('BUILT', 'FAILED'))"
          + " GROUP BY l.value ORDER BY l.value";

  private final Connection connection;

  public BepTargetQueryFile(Connection connection) {
    this.connection = Objects.requireNonNull(connection, "connection");
  }

  /**
   * Atomically writes a {@code deps(...)} expression without retaining the label set.
   *
   * <p>Wildcard expansion differs between build/test and both graph-query commands. Reading
   * normalized completed-target rows preserves what the primary invocation built, including its
   * exclusion of manual and incompatible targets. A configured-only target must not be replayed as
   * an explicit query target: Bazel can skip it under a wildcard but rejects it when explicitly
   * requested. A completed subset is exact when the complete invocation succeeded, because omitted
   * labels were skipped rather than failed, or when no reported label was omitted. A failed or
   * unknown invocation with omitted labels remains unverified. If the build reported no completed
   * target, {@code fallbackExpression} preserves the requested-pattern behavior and the returned
   * scope makes that wider fallback explicit.
   */
  public Result write(Path destination, String fallbackExpression)
      throws IOException, SQLException {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(fallbackExpression, "fallbackExpression");
    Files.createDirectories(destination.getParent());
    Path temporary =
        Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
    long labels = 0;
    boolean moved = false;
    try {
      try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
          Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery(LABELS)) {
        while (rows.next()) {
          if (labels == 0) {
            writer.write("deps(set(");
          } else {
            writer.write(' ');
          }
          writeQuoted(writer, rows.getString(1));
          labels++;
        }
        if (labels == 0) {
          writer.write(fallbackExpression);
        } else {
          writer.write("))");
        }
        writer.newLine();
      }
      replace(temporary, destination);
      moved = true;
      InvocationEvidence evidence = invocationEvidence();
      long omittedLabels = Math.max(0, evidence.reportedLabels() - labels);
      GraphTargetScope scope;
      String detail;
      if (labels == 0) {
        scope = GraphTargetScope.REQUESTED_PATTERNS;
        detail = scope.describe();
      } else if (!evidence.completeBep()) {
        scope = GraphTargetScope.UNKNOWN;
        detail =
            "The query used "
                + labels
                + " completed top-level target label"
                + (labels == 1 ? "" : "s")
                + " recorded before the BEP ended, but the stream had no final marker;"
                + " the recorded label set may be incomplete.";
      } else if (!evidence.successful() && omittedLabels > 0) {
        scope = GraphTargetScope.UNKNOWN;
        String outcome =
            evidence.overallSuccess().isPresent()
                ? "the invocation failed"
                : "the invocation did not report whether it succeeded";
        detail =
            "The query used "
                + labels
                + " completed top-level target label"
                + (labels == 1 ? "" : "s")
                + " but omitted "
                + omittedLabels
                + " configured or aborted label"
                + (omittedLabels == 1 ? "" : "s")
                + "; because "
                + outcome
                + ", that completed subset cannot be confirmed as the invocation's exact"
                + " target scope.";
      } else {
        scope = GraphTargetScope.EXACT_BEP_TARGETS;
        detail =
            "The query used "
                + labels
                + " distinct completed top-level target label"
                + (labels == 1 ? "" : "s")
                + " recorded in this build's complete BEP."
                + (omittedLabels == 0
                    ? ""
                    : " The successful invocation also reported "
                        + omittedLabels
                        + " configured or aborted label"
                        + (omittedLabels == 1 ? "" : "s")
                        + " without a BUILT or FAILED completion; those labels were not"
                        + " replayed as explicit query targets.");
      }
      return new Result(labels, scope, detail);
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private InvocationEvidence invocationEvidence() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT"
                    + " COALESCE((SELECT saw_last_message FROM build_invocation"
                    + " WHERE singleton = 1), 0),"
                    + " (SELECT overall_success FROM build_invocation WHERE singleton = 1),"
                    + " (SELECT COUNT(DISTINCT l.value) FROM targets t"
                    + " JOIN labels l ON l.id = t.label_id)")) {
      rows.next();
      boolean completeBep = rows.getInt(1) != 0;
      int successValue = rows.getInt(2);
      Optional<Boolean> overallSuccess =
          rows.wasNull() ? Optional.empty() : Optional.of(successValue != 0);
      return new InvocationEvidence(completeBep, overallSuccess, rows.getLong(3));
    }
  }

  private record InvocationEvidence(
      boolean completeBep, Optional<Boolean> overallSuccess, long reportedLabels) {

    boolean successful() {
      return overallSuccess.orElse(false);
    }
  }

  private static void writeQuoted(BufferedWriter writer, String label) throws IOException {
    writer.write('"');
    for (int index = 0; index < label.length(); index++) {
      char value = label.charAt(index);
      if (value == '"' || value == '\\') {
        writer.write('\\');
      }
      writer.write(value);
    }
    writer.write('"');
  }

  private static void replace(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** The scope written and the evidence persisted with the graph source. */
  public record Result(long topLevelLabels, GraphTargetScope scope, String detail) {

    public boolean usedRequestedPatterns() {
      return scope == GraphTargetScope.REQUESTED_PATTERNS;
    }
  }
}
