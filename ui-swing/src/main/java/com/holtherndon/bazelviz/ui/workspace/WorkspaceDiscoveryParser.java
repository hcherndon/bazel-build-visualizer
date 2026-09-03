package com.holtherndon.bazelviz.ui.workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/** Pure parser for the line-oriented workspace-discovery protocol. */
final class WorkspaceDiscoveryParser {

  private static final String DEFAULT_BAZEL_EXECUTABLE = "bazel";
  private static final String ID_NAMESPACE = "bazelviz-workspace-discovery-v1";

  private WorkspaceDiscoveryParser() {}

  static ParseResult parse(String stdout) {
    Objects.requireNonNull(stdout, "stdout");
    List<WorkspaceProfile> workspaces = new ArrayList<>();
    Map<String, Integer> firstLineById = new HashMap<>();
    DiagnosticCollector diagnostics = new DiagnosticCollector();
    String[] lines = stdout.split("\\R", -1);
    for (int index = 0; index < lines.length; index++) {
      int lineNumber = index + 1;
      String line = lines[index].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] fields = line.split("\\|", -1);
      String kind = fields[0].strip();
      WorkspaceProfile profile;
      try {
        profile =
            switch (kind) {
              case "local" -> local(fields, lineNumber, diagnostics);
              case "ssh" -> ssh(fields, lineNumber, diagnostics);
              default -> {
                diagnostics.add(
                    "Line " + lineNumber + " is invalid: expected row type 'local' or 'ssh'.");
                yield null;
              }
            };
      } catch (IllegalArgumentException invalid) {
        diagnostics.add(
            "Line "
                + lineNumber
                + " is invalid: "
                + ("ssh".equals(kind)
                    ? "the SSH name, destination, or working directory is not permitted."
                    : "the local name or working directory is not permitted."));
        continue;
      }
      if (profile == null) {
        continue;
      }
      Integer firstLine = firstLineById.putIfAbsent(profile.id(), lineNumber);
      if (firstLine != null) {
        diagnostics.add(
            "Line "
                + lineNumber
                + " duplicates the workspace first declared on line "
                + firstLine
                + ".");
        continue;
      }
      if (workspaces.size() == WorkspaceDiscovery.MAX_ACCEPTED_ROWS) {
        diagnostics.add(
            "Line "
                + lineNumber
                + " exceeds the "
                + WorkspaceDiscovery.MAX_ACCEPTED_ROWS
                + " discovered workspace limit and was not accepted.");
        continue;
      }
      workspaces.add(profile);
    }
    return new ParseResult(workspaces, diagnostics.finish());
  }

  private static WorkspaceProfile local(
      String[] fields, int lineNumber, DiagnosticCollector diagnostics) {
    if (fields.length != 3) {
      diagnostics.add(
          "Line " + lineNumber + " is invalid: local rows need exactly 3 pipe-delimited fields.");
      return null;
    }
    String label = fields[1].strip();
    String workingDirectory = fields[2].strip();
    return WorkspaceProfile.local(
        discoveredId(WorkspaceProfile.Kind.LOCAL, "", workingDirectory),
        label,
        workingDirectory,
        DEFAULT_BAZEL_EXECUTABLE,
        OptionalLong.empty());
  }

  private static WorkspaceProfile ssh(
      String[] fields, int lineNumber, DiagnosticCollector diagnostics) {
    if (fields.length != 4) {
      diagnostics.add(
          "Line " + lineNumber + " is invalid: ssh rows need exactly 4 pipe-delimited fields.");
      return null;
    }
    String label = fields[1].strip();
    String destination = fields[2].strip();
    String workingDirectory = fields[3].strip();
    return WorkspaceProfile.ssh(
        discoveredId(WorkspaceProfile.Kind.SSH, destination, workingDirectory),
        label,
        destination,
        OptionalInt.empty(),
        workingDirectory,
        DEFAULT_BAZEL_EXECUTABLE,
        OptionalLong.empty());
  }

  private static String discoveredId(
      WorkspaceProfile.Kind kind, String destination, String workingDirectory) {
    String seed =
        ID_NAMESPACE
            + '\u001f'
            + kind.name().toLowerCase(Locale.ROOT)
            + '\u001f'
            + destination.strip()
            + '\u001f'
            + workingDirectory.strip();
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static <T> List<T> freshImmutable(List<T> values) {
    return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values")));
  }

  record ParseResult(List<WorkspaceProfile> workspaces, List<String> diagnostics) {

    ParseResult {
      workspaces = freshImmutable(workspaces);
      diagnostics = freshImmutable(diagnostics);
    }
  }

  private static final class DiagnosticCollector {

    private final List<String> retained = new ArrayList<>();
    private int omitted;

    void add(String diagnostic) {
      if (retained.size() < WorkspaceDiscovery.MAX_ROW_DIAGNOSTICS - 1) {
        retained.add(diagnostic);
      } else {
        omitted++;
      }
    }

    List<String> finish() {
      if (omitted > 0) {
        retained.add(
            omitted
                + " additional row diagnostic"
                + (omitted == 1 ? " was" : "s were")
                + " omitted after the "
                + WorkspaceDiscovery.MAX_ROW_DIAGNOSTICS
                + " diagnostic limit was reached.");
      }
      return retained;
    }
  }
}
