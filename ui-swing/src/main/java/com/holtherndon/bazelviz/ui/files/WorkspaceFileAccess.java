package com.holtherndon.bazelviz.ui.files;

import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** One execution filesystem plus the invocation paths used to resolve file links. */
public record WorkspaceFileAccess(
    ExecutionFileSystem files,
    Optional<ExecutionPath> workspaceRoot,
    Optional<ExecutionPath> workingDirectory) {

  public WorkspaceFileAccess {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    workspaceRoot.ifPresent(path -> requireOwned(files, path));
    workingDirectory.ifPresent(path -> requireOwned(files, path));
  }

  /**
   * Builds local access for a recorded local session. An imported SSH session deliberately returns
   * empty: recorded paths cannot reconnect it or be interpreted on the desktop machine.
   */
  public static Optional<WorkspaceFileAccess> fromSession(SessionInfo session) {
    Objects.requireNonNull(session, "session");
    boolean remote =
        session
            .executionLocation()
            .map(location -> location.kind() == ExecutionLocation.Kind.SSH)
            .orElse(false);
    if (remote) {
      return Optional.empty();
    }
    LocalExecutionFileSystem files =
        new LocalExecutionFileSystem("local-session:" + session.sessionId());
    return Optional.of(
        new WorkspaceFileAccess(
            files,
            session.workspaceRoot().map(files::path),
            session.workingDirectory().map(files::path)));
  }

  /** Compatibility bridge for local path-based call sites and tests. */
  public static WorkspaceFileAccess local(
      Optional<Path> workspaceRoot, Optional<Path> workingDirectory) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    LocalExecutionFileSystem files = new LocalExecutionFileSystem();
    return new WorkspaceFileAccess(
        files, workspaceRoot.map(files::path), workingDirectory.map(files::path));
  }

  private static void requireOwned(ExecutionFileSystem files, ExecutionPath path) {
    if (!files.executionId().equals(path.executionId())) {
      throw new IllegalArgumentException(
          "workspace path belongs to execution "
              + path.executionId()
              + ", not "
              + files.executionId());
    }
  }
}
