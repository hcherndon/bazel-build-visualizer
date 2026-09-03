package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.ssh.SshControlSession;
import com.holtherndon.bazelviz.runner.ssh.SshReverseForward;
import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** A live SSH workspace connection shared by execution-scoped UI tools and capture. */
public final class RemoteExecution implements AutoCloseable {

  private final SshControlSession session;
  private final String workingDirectory;
  private final Optional<String> workspaceRoot;
  private final AtomicBoolean closed = new AtomicBoolean();

  RemoteExecution(
      SshControlSession session, String workingDirectory, Optional<String> workspaceRoot) {
    this.session = Objects.requireNonNull(session, "session");
    this.workingDirectory = requirePath(workingDirectory, "workingDirectory");
    this.workspaceRoot =
        Objects.requireNonNull(workspaceRoot, "workspaceRoot")
            .map(value -> requirePath(value, "workspaceRoot"));
  }

  /**
   * Opens and validates one explicitly selected SSH workspace.
   *
   * <p>This performs network and filesystem I/O and must never run on the Swing EDT. It opens no
   * BES listener, tunnel, staging directory, or Bazel process; those are capture-scoped resources
   * added only if the user later starts a build.
   */
  public static RemoteExecution connect(
      SshTarget target, String requestedWorkingDirectory, Duration timeout)
      throws IOException, InterruptedException {
    Objects.requireNonNull(target, "target");
    String requested = requirePath(requestedWorkingDirectory, "requestedWorkingDirectory");
    SshControlSession connected =
        SshControlSession.connect(target, Objects.requireNonNull(timeout, "timeout"));
    boolean handedOff = false;
    try {
      ExecutionFileSystem files = connected.fileSystem();
      ExecutionPath working = files.canonicalize(files.path(requested));
      FileMetadata workingMetadata = files.stat(working);
      if (!workingMetadata.isDirectory()) {
        throw new IOException(
            "the remote working directory is not a directory: "
                + working
                + workingMetadata.detail().map(detail -> " (" + detail + ")").orElse(""));
      }
      Optional<String> root = findWorkspaceRoot(files, working).map(ExecutionPath::value);
      RemoteExecution result = new RemoteExecution(connected, working.value(), root);
      handedOff = true;
      return result;
    } finally {
      if (!handedOff) {
        connected.close();
      }
    }
  }

  private static Optional<ExecutionPath> findWorkspaceRoot(
      ExecutionFileSystem files, ExecutionPath working) throws IOException {
    ExecutionPath current = working;
    while (true) {
      List<ExecutionPath> markerPaths = new ArrayList<>(WorkspaceInfo.MARKERS.size());
      for (String marker : WorkspaceInfo.MARKERS) {
        markerPaths.add(files.resolve(current, marker));
      }
      List<FileMetadata> metadata = files.statAll(markerPaths);
      if (metadata.size() != markerPaths.size()) {
        throw new IOException("the remote filesystem returned incomplete marker metadata");
      }
      for (FileMetadata marker : metadata) {
        if (marker.isRegularFile()) {
          return Optional.of(current);
        }
      }
      ExecutionPath parent = files.canonicalize(files.resolve(current, ".."));
      if (parent.value().equals(current.value())) {
        return Optional.empty();
      }
      current = parent;
    }
  }

  public String displayName() {
    return session.displayName();
  }

  public SshTarget target() {
    ensureOpen();
    return session.target();
  }

  public String workingDirectory() {
    return workingDirectory;
  }

  public Optional<String> workspaceRoot() {
    return workspaceRoot;
  }

  public String repositoryRootText() {
    return workspaceRoot.orElse(workingDirectory);
  }

  public ExecutionFileSystem fileSystem() {
    ensureOpen();
    return session.fileSystem();
  }

  public ExecutionPath repositoryRoot() throws IOException {
    return fileSystem().path(repositoryRootText());
  }

  public CommandExecutor commandExecutor() {
    ensureOpen();
    return session.commandExecutor();
  }

  /** Adds a capture-scoped reverse forward to this workspace connection. */
  SshReverseForward openReverseForward(int localPort) throws IOException, InterruptedException {
    ensureOpen();
    return session.openReverseForward(localPort);
  }

  public ExecutionLocation provenance() {
    return ExecutionLocation.ssh(
        displayName(), session.target().destination(), session.target().port());
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      session.close();
    }
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("the remote execution is closed");
    }
  }

  private static String requirePath(String value, String name) {
    String checked = Objects.requireNonNull(value, name);
    if (checked.isBlank() || checked.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(name + " is blank or invalid");
    }
    return checked;
  }
}
