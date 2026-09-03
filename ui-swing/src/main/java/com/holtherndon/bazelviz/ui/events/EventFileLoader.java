package com.holtherndon.bazelviz.ui.events;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.ui.events.EventFileInspection.FileEntry;
import com.holtherndon.bazelviz.ui.files.FileLink;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileAccess;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileResolver;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Decodes and stats only the selected event when its Files tab is first visited. */
public final class EventFileLoader {

  /** File rows retained from one event. The exact total is still reported. */
  public static final int MAX_FILES = 10_000;

  private static final DateTimeFormatter MODIFIED_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

  private EventFileLoader() {}

  /** Compatibility entry point for local sessions. */
  public static EventFileInspection load(
      long eventId,
      RawPayload payload,
      Optional<Path> workspaceRoot,
      Optional<Path> workingDirectory) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    return load(
        eventId, payload, Optional.of(WorkspaceFileAccess.local(workspaceRoot, workingDirectory)));
  }

  /** Loads file rows through the selected execution host, or without metadata when detached. */
  public static EventFileInspection load(
      long eventId, RawPayload payload, Optional<WorkspaceFileAccess> access) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(access, "access");
    RawPayloadRenderer.DecodedEvent decoded = RawPayloadRenderer.decodeEvent(payload);
    if (decoded.event().isEmpty()) {
      return EventFileInspection.loaded(eventId, List.of(), 0, decoded.absence());
    }
    BuildEvent event = decoded.event().orElseThrow();
    Collector collected = new Collector(access);
    collectDirectFiles(event, collected);
    long total = collected.total;
    List<FileEntry> files = collected.describe();
    Optional<String> note =
        total > MAX_FILES
            ? Optional.of(
                "This event carries "
                    + total
                    + " files. Showing the first "
                    + MAX_FILES
                    + "; no file was removed from the stored event.")
            : Optional.empty();
    if (access.isEmpty() && total > 0) {
      String detached =
          "Execution-file metadata is unavailable until its execution "
              + "filesystem is connected; recorded paths are not interpreted locally.";
      note = Optional.of(note.map(value -> value + " " + detached).orElse(detached));
    }
    if (event.hasNamedSetOfFiles() && event.getNamedSetOfFiles().getFileSetsCount() > 0) {
      String references =
          "This named set also references "
              + event.getNamedSetOfFiles().getFileSetsCount()
              + " other named set event(s); this tab lists files carried directly by the"
              + " selected event.";
      note = Optional.of(note.map(value -> value + " " + references).orElse(references));
    }
    return EventFileInspection.loaded(eventId, files, total, note);
  }

  private static void collectDirectFiles(BuildEvent event, Collector files) {
    if (event.hasNamedSetOfFiles()) {
      add(files, "Named set", event.getNamedSetOfFiles().getFilesList());
    }
    if (event.hasAction()) {
      if (event.getAction().hasPrimaryOutput()) {
        files.add("Primary output", event.getAction().getPrimaryOutput());
      }
      if (event.getAction().hasStdout()) {
        files.add("Action stdout", event.getAction().getStdout());
      }
      if (event.getAction().hasStderr()) {
        files.add("Action stderr", event.getAction().getStderr());
      }
    }
    if (event.hasCompleted()) {
      add(files, "Important output", event.getCompleted().getImportantOutputList());
      add(files, "Directory output", event.getCompleted().getDirectoryOutputList());
    }
    if (event.hasTestResult()) {
      add(files, "Test output", event.getTestResult().getTestActionOutputList());
    }
    if (event.hasTestSummary()) {
      add(files, "Passed test output", event.getTestSummary().getPassedList());
      add(files, "Failed test output", event.getTestSummary().getFailedList());
    }
    if (event.hasBuildToolLogs()) {
      add(files, "Build tool log", event.getBuildToolLogs().getLogList());
    }
  }

  private static void add(Collector into, String role, List<File> files) {
    for (File file : files) {
      into.add(role, file);
    }
  }

  private static Pending pending(String role, File file, Optional<WorkspaceFileAccess> access) {
    String path = joinPath(file.getPathPrefixList(), file.getName());
    String source = file.hasUri() ? file.getUri() : "";
    String kind =
        switch (file.getFileCase()) {
          case URI -> "URI";
          case CONTENTS -> "Inline (" + file.getContents().size() + " bytes)";
          case SYMLINK_TARGET_PATH -> "Symlink to " + file.getSymlinkTargetPath();
          case FILE_NOT_SET -> "Path only";
        };
    OptionalLong declared =
        file.getLength() > 0 ? OptionalLong.of(file.getLength()) : OptionalLong.empty();
    Optional<String> digest =
        file.getDigest().isBlank() ? Optional.empty() : Optional.of(file.getDigest());
    Optional<ExecutionPath> executionPath =
        access.flatMap(value -> pathCandidate(file, path, value));
    return new Pending(role, path, source, kind, declared, digest, executionPath);
  }

  private static Optional<ExecutionPath> pathCandidate(
      File file, String path, WorkspaceFileAccess access) {
    FileLink link;
    if (file.hasUri() && file.getUri().regionMatches(true, 0, "file:", 0, 5)) {
      link = new FileLink("Event file", file.getUri(), FileLink.Base.FILE_URI);
    } else if (!path.isBlank() && !file.hasContents()) {
      link = new FileLink("Event file", path, FileLink.Base.WORKSPACE);
    } else {
      return Optional.empty();
    }
    try {
      return Optional.of(WorkspaceFileResolver.pathCandidate(link, access));
    } catch (IOException | RuntimeException invalid) {
      return Optional.empty();
    }
  }

  private static String joinPath(List<String> prefixes, String name) {
    if (prefixes.isEmpty()) {
      return name;
    }
    String joined = String.join("/", prefixes);
    return name.isEmpty() ? joined : joined + "/" + name;
  }

  private static FileEntry describe(
      Pending pending, FileMetadata metadata, Optional<WorkspaceFileAccess> access) {
    Optional<Path> local =
        pending
            .executionPath()
            .flatMap(path -> access.flatMap(value -> value.files().localPath(path)));
    boolean exists = metadata.state() == FileMetadata.State.PRESENT;
    boolean directory = metadata.isDirectory();
    Optional<String> modified =
        metadata.modifiedMillis().isPresent()
            ? Optional.of(
                MODIFIED_FORMAT.format(
                    Instant.ofEpochMilli(metadata.modifiedMillis().getAsLong())
                        .atZone(ZoneId.systemDefault())))
            : Optional.empty();
    return new FileEntry(
        pending.role(),
        pending.path(),
        pending.source(),
        pending.kind(),
        pending.declaredBytes(),
        pending.digest(),
        local,
        exists,
        directory,
        metadata.bytes(),
        modified,
        pending.executionPath(),
        metadata.state(),
        metadata.kind(),
        metadata.detail());
  }

  private static FileMetadata noPath(Pending pending) {
    // The entry still exists in raw BEP evidence, but it has no external
    // path that this execution filesystem can inspect.
    ExecutionPath placeholder =
        new ExecutionPath(
            "unresolved-event-file",
            pending.source().isBlank()
                ? pending.path().isBlank() ? "<inline>" : pending.path()
                : pending.source());
    return FileMetadata.unavailable(placeholder, "no execution path is available");
  }

  /** Counts every file while retaining only the documented prefix. */
  private static final class Collector {
    private final Optional<WorkspaceFileAccess> access;
    private final List<Pending> pending = new ArrayList<>();
    private long total;

    Collector(Optional<WorkspaceFileAccess> access) {
      this.access = access;
    }

    void add(String role, File file) {
      total++;
      if (pending.size() < MAX_FILES) {
        pending.add(pending(role, file, access));
      }
    }

    List<FileEntry> describe() {
      if (pending.isEmpty()) {
        return List.of();
      }
      List<ExecutionPath> paths =
          pending.stream().flatMap(value -> value.executionPath().stream()).toList();
      List<FileMetadata> metadata = List.of();
      Optional<String> batchFailure = Optional.empty();
      if (!paths.isEmpty() && access.isPresent()) {
        try {
          metadata = access.orElseThrow().files().statAll(paths);
          if (metadata.size() != paths.size()) {
            throw new IOException(
                "filesystem returned "
                    + metadata.size()
                    + " metadata rows for "
                    + paths.size()
                    + " paths");
          }
          for (int index = 0; index < paths.size(); index++) {
            if (!paths.get(index).equals(metadata.get(index).path())) {
              throw new IOException("filesystem returned metadata out of request order");
            }
          }
        } catch (IOException | RuntimeException failure) {
          batchFailure = Optional.of(describeFailure(failure));
          metadata = List.of();
        }
      }
      List<FileEntry> result = new ArrayList<>(pending.size());
      int metadataIndex = 0;
      for (Pending value : pending) {
        FileMetadata one;
        if (value.executionPath().isEmpty()) {
          one = noPath(value);
        } else if (batchFailure.isPresent()) {
          one =
              FileMetadata.unavailable(
                  value.executionPath().orElseThrow(), batchFailure.orElseThrow());
        } else {
          one = metadata.get(metadataIndex++);
        }
        result.add(EventFileLoader.describe(value, one, access));
      }
      return List.copyOf(result);
    }
  }

  private record Pending(
      String role,
      String path,
      String source,
      String kind,
      OptionalLong declaredBytes,
      Optional<String> digest,
      Optional<ExecutionPath> executionPath) {

    private Pending {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(declaredBytes, "declaredBytes");
      Objects.requireNonNull(digest, "digest");
      Objects.requireNonNull(executionPath, "executionPath");
    }
  }

  private static String describeFailure(Throwable failure) {
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }
}
