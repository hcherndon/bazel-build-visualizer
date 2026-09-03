package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import java.util.Objects;
import java.util.Optional;

/**
 * What was learned before anything ran: which Bazel, which workspace, what it supports, and what
 * would be launched.
 *
 * <p>This is the value the instrumentation dialog renders (plan 4.3). Producing it starts the
 * embedded BES server — the plan has to name a real port — but no session directory, no database
 * and no build. A user who reads this and changes their mind leaves nothing behind but a closed
 * socket.
 *
 * <p>{@link #request} is the input that produced {@link #plan}, carried rather than discarded.
 * Launch has to plan a second time, against the session directory that only exists once the user
 * has said yes, and rebuilding the input from the output loses everything the user decided in
 * between: a vetoed flag came back, and a conflict resolution had to be guessed at from whether its
 * effect happened to be visible in the plan.
 */
public record Preflight(
    BazelExecutable executable,
    WorkspaceInfo workspace,
    BazelCapabilities capabilities,
    BesEndpoint endpoint,
    InstrumentationPlan plan,
    PlanRequest request,
    Optional<RemoteDetails> remote) {

  public Preflight {
    Objects.requireNonNull(executable, "executable");
    Objects.requireNonNull(workspace, "workspace");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(endpoint, "endpoint");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(request, "request");
    remote = Objects.requireNonNull(remote, "remote");
  }

  /** Compatibility constructor for a local preflight. */
  public Preflight(
      BazelExecutable executable,
      WorkspaceInfo workspace,
      BazelCapabilities capabilities,
      BesEndpoint endpoint,
      InstrumentationPlan plan,
      PlanRequest request) {
    this(executable, workspace, capabilities, endpoint, plan, request, Optional.empty());
  }

  /** Whether the build may be started as planned. */
  public boolean canLaunch() {
    return plan.canLaunch();
  }

  public boolean isRemote() {
    return remote.isPresent();
  }

  /** SSH-specific values shown before launch. No credentials are included. */
  public record RemoteDetails(
      String host,
      String workingDirectory,
      Optional<String> workspaceRoot,
      String localBesListener,
      String remoteBesBackend,
      String stagingDirectory) {

    public RemoteDetails {
      host = requireText(host, "host");
      workingDirectory = requirePath(workingDirectory, "workingDirectory");
      workspaceRoot =
          Objects.requireNonNull(workspaceRoot, "workspaceRoot")
              .map(value -> requirePath(value, "workspaceRoot"));
      localBesListener = requireText(localBesListener, "localBesListener");
      remoteBesBackend = requireText(remoteBesBackend, "remoteBesBackend");
      stagingDirectory = requirePath(stagingDirectory, "stagingDirectory");
    }

    private static String requireText(String value, String name) {
      String checked = Objects.requireNonNull(value, name).strip();
      if (checked.isEmpty() || checked.indexOf('\0') >= 0) {
        throw new IllegalArgumentException(name + " is blank or invalid");
      }
      return checked;
    }

    private static String requirePath(String value, String name) {
      String checked = Objects.requireNonNull(value, name);
      if (checked.isBlank() || checked.indexOf('\0') >= 0) {
        throw new IllegalArgumentException(name + " is blank or invalid");
      }
      return checked;
    }
  }
}
