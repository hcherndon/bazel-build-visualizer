package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.format.session.SessionManifest.AuxiliaryCommand;
import com.holtherndon.bazelviz.format.session.SessionManifest.CaptureSourceEntry;
import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Applies one export-scoped redactor to every sensitive manifest field. */
public final class SessionManifestRedaction {

  private SessionManifestRedaction() {}

  /** Returns a redacted manifest without changing the source object or session. */
  public static SessionManifest redact(SessionManifest manifest, Redactor redactor) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(redactor, "redactor");
    return manifest.toBuilder()
        .workingDirectory(path(redactor, manifest.workingDirectory(), "manifest.workingDirectory"))
        .workspaceRoot(path(redactor, manifest.workspaceRoot(), "manifest.workspaceRoot"))
        .executionLocation(redactLocation(manifest.executionLocation(), redactor))
        .bazelExecutable(path(redactor, manifest.bazelExecutable(), "manifest.bazelExecutable"))
        .originalCommand(argv(redactor, manifest.originalCommand(), "manifest.originalCommand"))
        .effectiveCommand(argv(redactor, manifest.effectiveCommand(), "manifest.effectiveCommand"))
        .environmentCapturePolicy(
            text(
                redactor, manifest.environmentCapturePolicy(), "manifest.environmentCapturePolicy"))
        .capturePreset(text(redactor, manifest.capturePreset(), "manifest.capturePreset"))
        .injectedFlags(argv(redactor, manifest.injectedFlags(), "manifest.injectedFlags"))
        .auxiliaryCommands(redactAuxiliary(manifest.auxiliaryCommands(), redactor))
        .sources(redactSources(manifest.sources(), redactor))
        .redactionState(Optional.of("REDACTED"))
        // Redacted archives omit all derived index sidecars, so do not advertise their versions.
        .indexVersions(Optional.empty())
        .warnings(
            manifest.warnings().stream()
                .map(value -> redactor.text(value, "manifest.warnings"))
                .toList())
        // Unknown keys and non-string values can carry secrets too; their semantics are unknowable.
        .unknownFields(Map.of())
        .build();
  }

  private static Optional<ExecutionLocation> redactLocation(
      Optional<ExecutionLocation> location, Redactor redactor) {
    return location.map(
        value -> {
          if (value.kind() != ExecutionLocation.Kind.SSH) {
            return value;
          }
          String displayName =
              redactor.pseudonymize(
                  value.displayName(),
                  "manifest.executionLocation.displayName",
                  "an SSH display name");
          String destination =
              redactor.pseudonymize(
                  value.sshDestination().orElseThrow(),
                  "manifest.executionLocation.sshDestination",
                  "an SSH destination");
          return ExecutionLocation.ssh(displayName, destination, value.sshPort());
        });
  }

  private static Optional<List<AuxiliaryCommand>> redactAuxiliary(
      Optional<List<AuxiliaryCommand>> commands, Redactor redactor) {
    return commands.map(
        values -> {
          List<AuxiliaryCommand> redacted = new ArrayList<>(values.size());
          for (AuxiliaryCommand command : values) {
            redacted.add(
                new AuxiliaryCommand(
                    redactor.text(command.label(), "manifest.auxiliaryCommands.label"),
                    redactor.argv(command.argv(), "manifest.auxiliaryCommands.argv")));
          }
          return List.copyOf(redacted);
        });
  }

  private static List<CaptureSourceEntry> redactSources(
      List<CaptureSourceEntry> sources, Redactor redactor) {
    List<CaptureSourceEntry> redacted = new ArrayList<>(sources.size());
    for (CaptureSourceEntry source : sources) {
      redacted.add(
          new CaptureSourceEntry(
              redactor.text(source.kind(), "manifest.sources.kind"),
              path(redactor, source.path(), "manifest.sources.path"),
              // A raw-source digest is stable cross-export provenance; omit it from shared output.
              Optional.empty(),
              source.byteSize(),
              source.completeness(),
              text(redactor, source.note(), "manifest.sources.note"),
              Map.of()));
    }
    return List.copyOf(redacted);
  }

  private static Optional<String> path(Redactor redactor, Optional<String> value, String field) {
    return value.map(text -> redactor.path(text, field));
  }

  private static Optional<String> text(Redactor redactor, Optional<String> value, String field) {
    return value.map(content -> redactor.text(content, field));
  }

  private static Optional<List<String>> argv(
      Redactor redactor, Optional<List<String>> value, String field) {
    return value.map(arguments -> redactor.argv(arguments, field));
  }
}
