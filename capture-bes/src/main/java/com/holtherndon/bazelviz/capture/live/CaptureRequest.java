package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.runner.command.EnvironmentInheritance;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the user asked for, before anything has been resolved or started.
 *
 * <p>Everything here is the user's input. Nothing in it has been probed, planned or launched —
 * which is what makes it safe to build one from a dialog, hand it to {@link
 * CaptureCoordinator#preflight}, show the result and then throw it away when the user changes their
 * mind.
 *
 * @param sessionsRoot where managed sessions live
 * @param appVersion recorded in the manifest
 * @param executable what the user typed for the Bazel launcher
 * @param workingDirectory where the build runs, on the selected execution host. It stays text
 *     because an SSH Linux path must not become a desktop Path. Not normalized to the workspace
 *     root: Bazel resolves relative target patterns against this, so changing it would change what
 *     gets built
 * @param args everything after the executable, as typed
 * @param preset how much instrumentation to ask for
 * @param environmentOverrides variables to set or unset for the build
 * @param inheritance what the build inherits from this process
 * @param shellMode whether to run through a shell; carries a security warning
 * @param console receives console output as it arrives, for a live view
 * @param progress receives capture counters, throttled
 * @param options pipeline tunables
 * @param sshTarget explicit SSH destination, absent for local execution
 * @param connectedRemote an already selected SSH workspace to borrow, absent for headless commands
 *     and local execution
 */
public record CaptureRequest(
    Path sessionsRoot,
    String appVersion,
    String executable,
    String workingDirectory,
    List<String> args,
    CapturePreset preset,
    Map<String, Optional<String>> environmentOverrides,
    EnvironmentInheritance inheritance,
    boolean shellMode,
    ConsoleSink console,
    CaptureProgressListener progress,
    CaptureOptions options,
    Optional<SshTarget> sshTarget,
    Optional<RemoteExecution> connectedRemote) {

  public CaptureRequest {
    Objects.requireNonNull(sessionsRoot, "sessionsRoot");
    Objects.requireNonNull(appVersion, "appVersion");
    Objects.requireNonNull(executable, "executable");
    workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").strip();
    if (workingDirectory.isEmpty() || workingDirectory.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("working directory must not be blank or contain NUL");
    }
    args = List.copyOf(args);
    Objects.requireNonNull(preset, "preset");
    environmentOverrides =
        Map.copyOf(Objects.requireNonNull(environmentOverrides, "environmentOverrides"));
    Objects.requireNonNull(inheritance, "inheritance");
    Objects.requireNonNull(console, "console");
    Objects.requireNonNull(progress, "progress");
    Objects.requireNonNull(options, "options");
    sshTarget = Objects.requireNonNull(sshTarget, "sshTarget");
    connectedRemote = Objects.requireNonNull(connectedRemote, "connectedRemote");
    if (connectedRemote.isPresent()) {
      RemoteExecution remote = connectedRemote.orElseThrow();
      if (sshTarget.isEmpty() || !sshTarget.orElseThrow().equals(remote.target())) {
        throw new IllegalArgumentException(
            "a connected SSH workspace must match the request target");
      }
      if (!workingDirectory.equals(remote.workingDirectory())) {
        throw new IllegalArgumentException(
            "a connected SSH workspace must match the request working directory");
      }
    }
  }

  /** A headless request with the recommended preset and no listeners. */
  public static CaptureRequest of(
      Path sessionsRoot,
      String appVersion,
      String executable,
      Path workingDirectory,
      List<String> args) {
    return new CaptureRequest(
        sessionsRoot,
        appVersion,
        executable,
        workingDirectory.toAbsolutePath().normalize().toString(),
        args,
        CapturePreset.defaultPreset(),
        Map.of(),
        EnvironmentInheritance.INHERIT_ALL,
        false,
        ConsoleSink.discarding(),
        CaptureProgressListener.ignoring(),
        CaptureOptions.defaults(),
        Optional.empty(),
        Optional.empty());
  }

  /** A headless SSH request without interpreting its Linux directory locally. */
  public static CaptureRequest remote(
      Path sessionsRoot,
      String appVersion,
      String executable,
      String workingDirectory,
      List<String> args,
      SshTarget target) {
    return new CaptureRequest(
        sessionsRoot,
        appVersion,
        executable,
        workingDirectory,
        args,
        CapturePreset.defaultPreset(),
        Map.of(),
        EnvironmentInheritance.INHERIT_ALL,
        false,
        ConsoleSink.discarding(),
        CaptureProgressListener.ignoring(),
        CaptureOptions.defaults(),
        Optional.of(Objects.requireNonNull(target, "target")),
        Optional.empty());
  }

  public CaptureRequest withPreset(CapturePreset value) {
    return copy(
        value,
        environmentOverrides,
        inheritance,
        shellMode,
        console,
        progress,
        options,
        sshTarget,
        connectedRemote,
        workingDirectory);
  }

  public CaptureRequest withConsole(ConsoleSink value) {
    return copy(
        preset,
        environmentOverrides,
        inheritance,
        shellMode,
        value,
        progress,
        options,
        sshTarget,
        connectedRemote,
        workingDirectory);
  }

  public CaptureRequest withProgress(CaptureProgressListener value) {
    return copy(
        preset,
        environmentOverrides,
        inheritance,
        shellMode,
        console,
        value,
        options,
        sshTarget,
        connectedRemote,
        workingDirectory);
  }

  /**
   * Sets or unsets one environment variable for the build.
   *
   * <p>An empty value means "unset this for the child", which is a different instruction from "set
   * it to the empty string" and has to stay distinguishable — {@code USE_BAZEL_VERSION=""} makes
   * bazelisk fall back to its own resolution rather than pinning nothing.
   */
  public CaptureRequest withEnvironment(String name, Optional<String> value) {
    Map<String, Optional<String>> merged = new LinkedHashMap<>(environmentOverrides);
    merged.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    return copy(
        preset,
        merged,
        inheritance,
        shellMode,
        console,
        progress,
        options,
        sshTarget,
        connectedRemote,
        workingDirectory);
  }

  public CaptureRequest withOptions(CaptureOptions value) {
    return copy(
        preset,
        environmentOverrides,
        inheritance,
        shellMode,
        console,
        progress,
        value,
        sshTarget,
        connectedRemote,
        workingDirectory);
  }

  /** Runs the same request on an explicit SSH host and remote Linux directory. */
  public CaptureRequest withSshTarget(SshTarget target, String remoteWorkingDirectory) {
    return copy(
        preset,
        environmentOverrides,
        inheritance,
        shellMode,
        console,
        progress,
        options,
        Optional.of(Objects.requireNonNull(target, "target")),
        Optional.empty(),
        remoteWorkingDirectory);
  }

  /** Borrows the already connected workspace for this capture. */
  public CaptureRequest withConnectedRemote(RemoteExecution remote) {
    RemoteExecution checked = Objects.requireNonNull(remote, "remote");
    return copy(
        preset,
        environmentOverrides,
        inheritance,
        shellMode,
        console,
        progress,
        options,
        Optional.of(checked.target()),
        Optional.of(checked),
        checked.workingDirectory());
  }

  public boolean isRemote() {
    return sshTarget.isPresent();
  }

  /** Local-only compatibility accessor. Remote paths are never converted to Path. */
  public Path localWorkingDirectory() {
    if (isRemote()) {
      throw new IllegalStateException("an SSH working directory is not a local Path");
    }
    return Path.of(workingDirectory);
  }

  private CaptureRequest copy(
      CapturePreset copiedPreset,
      Map<String, Optional<String>> copiedEnvironment,
      EnvironmentInheritance copiedInheritance,
      boolean copiedShellMode,
      ConsoleSink copiedConsole,
      CaptureProgressListener copiedProgress,
      CaptureOptions copiedOptions,
      Optional<SshTarget> copiedTarget,
      Optional<RemoteExecution> copiedRemote,
      String copiedWorkingDirectory) {
    return new CaptureRequest(
        sessionsRoot,
        appVersion,
        executable,
        copiedWorkingDirectory,
        args,
        copiedPreset,
        copiedEnvironment,
        copiedInheritance,
        copiedShellMode,
        copiedConsole,
        copiedProgress,
        copiedOptions,
        copiedTarget,
        copiedRemote);
  }
}
