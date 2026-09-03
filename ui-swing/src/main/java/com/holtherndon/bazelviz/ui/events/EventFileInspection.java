package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Lazy file metadata for one selected event. */
public record EventFileInspection(
    State state,
    OptionalLong eventId,
    List<FileEntry> files,
    long totalFiles,
    Optional<String> note) {

  public enum State {
    NONE,
    LOADING,
    LOADED,
    FAILED
  }

  public EventFileInspection {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(eventId, "eventId");
    files = List.copyOf(files);
    Objects.requireNonNull(note, "note");
  }

  public static EventFileInspection none() {
    return new EventFileInspection(
        State.NONE, OptionalLong.empty(), List.of(), 0, Optional.empty());
  }

  public static EventFileInspection loading(long eventId) {
    return new EventFileInspection(
        State.LOADING, OptionalLong.of(eventId), List.of(), 0, Optional.empty());
  }

  public static EventFileInspection loaded(
      long eventId, List<FileEntry> files, long totalFiles, Optional<String> note) {
    return new EventFileInspection(State.LOADED, OptionalLong.of(eventId), files, totalFiles, note);
  }

  public static EventFileInspection failed(long eventId, String reason) {
    return new EventFileInspection(
        State.FAILED,
        OptionalLong.of(eventId),
        List.of(),
        0,
        Optional.of(Objects.requireNonNull(reason, "reason")));
  }

  /** One BEP File message plus execution-host metadata, when it maps to a path. */
  public record FileEntry(
      String role,
      String path,
      String source,
      String kind,
      OptionalLong declaredBytes,
      Optional<String> digest,
      Optional<Path> localPath,
      boolean exists,
      boolean directory,
      OptionalLong actualBytes,
      Optional<String> modified,
      Optional<ExecutionPath> executionPath,
      FileMetadata.State metadataState,
      FileMetadata.Kind metadataKind,
      Optional<String> metadataDetail) {

    public FileEntry {
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(declaredBytes, "declaredBytes");
      Objects.requireNonNull(digest, "digest");
      Objects.requireNonNull(localPath, "localPath");
      Objects.requireNonNull(actualBytes, "actualBytes");
      Objects.requireNonNull(modified, "modified");
      Objects.requireNonNull(executionPath, "executionPath");
      Objects.requireNonNull(metadataState, "metadataState");
      Objects.requireNonNull(metadataKind, "metadataKind");
      Objects.requireNonNull(metadataDetail, "metadataDetail");
    }

    /** Compatibility shape for existing local event-file callers. */
    public FileEntry(
        String role,
        String path,
        String source,
        String kind,
        OptionalLong declaredBytes,
        Optional<String> digest,
        Optional<Path> localPath,
        boolean exists,
        boolean directory,
        OptionalLong actualBytes,
        Optional<String> modified) {
      this(
          role,
          path,
          source,
          kind,
          declaredBytes,
          digest,
          localPath,
          exists,
          directory,
          actualBytes,
          modified,
          Optional.empty(),
          localPath.isEmpty()
              ? FileMetadata.State.UNAVAILABLE
              : exists ? FileMetadata.State.PRESENT : FileMetadata.State.MISSING,
          !exists
              ? FileMetadata.Kind.UNKNOWN
              : directory ? FileMetadata.Kind.DIRECTORY : FileMetadata.Kind.REGULAR_FILE,
          Optional.empty());
    }

    /** Best path-like text for copying. */
    public String copyText() {
      return executionPath
          .map(ExecutionPath::value)
          .or(() -> localPath.map(Path::toString))
          .orElseGet(() -> source.isBlank() ? path : source);
    }

    public boolean openable() {
      return (executionPath.isPresent() || localPath.isPresent())
          && metadataState == FileMetadata.State.PRESENT
          && metadataKind != FileMetadata.Kind.DIRECTORY;
    }
  }
}
