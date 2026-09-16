package com.holtherndon.bazelviz.capture.repro;

import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.enrich.repro.ExecutionLogComparison;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionAuditReference;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import com.holtherndon.bazelviz.runner.command.EffectiveOptions;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.repro.ReproducibilityPlan;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One reviewed, sequential, controlled repeat-build operation. All methods except {@link #cancel}
 * perform blocking I/O and belong on a worker. A supplied workspace lease is held until cleanup.
 * Captured files never construct this object: it accepts only an explicitly selected workspace.
 */
public final class ReproducibilityCoordinator implements AutoCloseable {
  public enum State {
    PREFLIGHT,
    REVIEW,
    RUNNING,
    CAPTURED,
    FAILED,
    CANCELLED
  }

  public enum Cleanup {
    NOT_ALLOCATED,
    PENDING,
    REMOVED,
    NEEDS_REVIEW
  }

  public enum Step {
    PREFLIGHT,
    SNAPSHOT_BEFORE,
    CLEAN_A,
    BUILD_A,
    PRESERVE_A,
    SNAPSHOT_BETWEEN,
    CLEAN_B,
    BUILD_B,
    PRESERVE_B,
    SNAPSHOT_AFTER,
    SHUTDOWN,
    CLEANUP
  }

  /** Replanning invalidates the old review; only the latest returned instance can launch. */
  public record Review(
      Path directory,
      ReproducibilityPlan protocol,
      Preflight a,
      Preflight b,
      List<String> blockers,
      List<String> notices) {
    public Review {
      blockers = List.copyOf(blockers);
      notices = List.copyOf(notices);
    }

    public boolean canLaunch() {
      return blockers.isEmpty();
    }
  }

  /** CAPTURED means two preserved runs, not that a comparison passed or hermeticity was proved. */
  public record Result(
      Path directory,
      State state,
      Cleanup cleanup,
      Optional<CaptureResult> a,
      Optional<CaptureResult> b,
      List<String> notices) {
    public Result {
      notices = List.copyOf(notices);
    }
  }

  /** A passive restart record. Paths are descriptive, never permission to clean or resume. */
  public record SavedOperation(
      Path directory,
      String state,
      String cleanup,
      Optional<Path> sessionA,
      Optional<Path> sessionB,
      String privateBase,
      Optional<Path> executionLogA,
      Optional<Path> executionLogB,
      Optional<String> executionLogSha256A,
      Optional<String> executionLogSha256B,
      String step,
      List<String> notices) {
    public SavedOperation {
      notices = List.copyOf(notices);
    }
  }

  private final CaptureRequest original;
  private final Path auditsRoot;
  private final AutoCloseable workspaceLease;
  private final Consumer<Step> progress;
  private final CommandExecutor executor;
  private final ExecutionFileSystem files;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean leaseReleased = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean();
  private final List<String> notices = new ArrayList<>();
  private AuditJournal journal;
  private OwnedAuditBase owned;
  private CaptureCoordinator a;
  private CaptureCoordinator b;
  private volatile CaptureCoordinator active;
  private ReproducibilityPlan protocol;
  private String reviewedVersion = "";
  private Path reviewedExecutable;
  private Review review;
  private State state = State.PREFLIGHT;
  private Cleanup cleanup = Cleanup.NOT_ALLOCATED;
  private boolean clientOutcomeUnknown;
  private boolean bazelContacted;
  private CaptureResult capturedA;
  private CaptureResult capturedB;
  private int helperCount;
  private boolean closed;

  /**
   * Ownership of a non-null, already acquired canonical workspace lease transfers here. The UI must
   * have explicitly selected the controlled diagnostic before calling preflight.
   */
  public ReproducibilityCoordinator(
      CaptureRequest request,
      Path auditsRoot,
      AutoCloseable workspaceLease,
      Consumer<Step> progress) {
    original = Objects.requireNonNull(request, "request");
    this.auditsRoot = Objects.requireNonNull(auditsRoot, "auditsRoot");
    this.workspaceLease = Objects.requireNonNull(workspaceLease, "workspaceLease");
    this.progress = Objects.requireNonNull(progress, "progress");
    if (request.isRemote() && request.connectedRemote().isEmpty()) {
      throw new IllegalArgumentException(
          "an audit must borrow an explicitly selected live SSH workspace");
    }
    executor =
        request
            .connectedRemote()
            .map(remote -> remote.commandExecutor())
            .orElse(LocalCommandExecutor.INSTANCE);
    files =
        request
            .connectedRemote()
            .map(remote -> remote.fileSystem())
            .orElseGet(LocalExecutionFileSystem::new);
  }

  public synchronized Review preflight() throws IOException {
    requireOpen();
    if (review != null) {
      return review;
    }
    checkCancelled();
    journal = new AuditJournal(auditsRoot);
    journal.put("rcPolicy", "READ");
    step(Step.PREFLIGHT);
    try {
      cleanup = Cleanup.PENDING;
      String allocationId = UUID.randomUUID().toString();
      journal.put("allocationId", allocationId);
      journal.put("allocationPattern", "/tmp/bbv-repro-" + allocationId + "-*");
      journal.put("cleanup", cleanup.name());
      owned =
          OwnedAuditBase.allocate(
              files, executor, allocationId, directory -> journal.put("privateRoot", directory));
      journal.put("privateRoot", owned.directory());
      journal.put("privateBase", owned.outputBase());
      journal.put("cleanup", cleanup.name());
      journal.put(
          "machine",
          original.connectedRemote().map(remote -> remote.displayName()).orElse("local"));
      journal.put("workingDirectory", original.workingDirectory());
      BazelCommand parsed =
          new CommandLineParser()
                  .parse(
                      Path.of(original.executable()),
                      Path.of(original.workingDirectory()),
                      original.args())
                  .toBuilder()
                  .environmentOverrides(original.environmentOverrides())
                  .inheritance(original.inheritance())
                  .shellMode(original.shellMode())
                  .build();
      protocol = ReproducibilityPlan.controlled(parsed, owned.outputBase());
      if (!protocol.canLaunch()) {
        throw new IOException(String.join("\n", protocol.blockers()));
      }
      CaptureRequest capture = copyRequest(protocol.build());
      a = new CaptureCoordinator(capture);
      b = new CaptureCoordinator(capture);
      bazelContacted = true;
      Preflight first =
          a.replan(
              request -> refreshConfiguration(withoutAuxiliaryQueries(request), protocol.build()));
      if (clientOutcomeUnknown) {
        throw new IOException(
            "Configuration inspection ended with an unknown command outcome. No further probes"
                + " or builds will run; the private base is retained for review.");
      }
      reviewedVersion = first.capabilities().bazelVersion().orElse("");
      reviewedExecutable = first.executable().resolved();
      protocol =
          ReproducibilityPlan.controlled(
              parsed.toBuilder().executable(first.executable().resolved()).build(),
              owned.outputBase());
      Preflight second =
          b.replan(
              request -> refreshConfiguration(withoutAuxiliaryQueries(request), protocol.build()));
      checkCancelled();
      return installReview(first, second);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      clientOutcomeUnknown |= original.isRemote();
      recordFailure("preflight interrupted");
      throw new IOException("audit preflight interrupted", interrupted);
    } catch (IOException | RuntimeException failure) {
      // A failed remote probe may have left a server client running. Never reconnect and replay it.
      clientOutcomeUnknown |= original.isRemote();
      recordFailure(failure.toString());
      throw failure;
    }
  }

  /** Apply instrumentation vetoes/conflict resolutions to both captures, before any build. */
  public synchronized Review replan(UnaryOperator<PlanRequest> changes) throws IOException {
    preflight();
    if (state != State.REVIEW) {
      throw new IllegalStateException("the audit is no longer in review");
    }
    Preflight first = a.replan(request -> withoutAuxiliaryQueries(changes.apply(request)));
    Preflight second = b.replan(request -> withoutAuxiliaryQueries(changes.apply(request)));
    return installReview(first, second);
  }

  /** Changes the configuration of both unstarted captures, preserving instrumentation choices. */
  public synchronized Review setIgnoreRcFiles(boolean ignore) throws IOException {
    preflight();
    if (state != State.REVIEW) throw new IllegalStateException("the audit is no longer in review");
    if (clientOutcomeUnknown)
      throw new IOException(
          "A previous command outcome is unknown. Cancel this diagnostic and review the connection"
              + " before retrying.");
    checkCancelled();
    protocol = ReproducibilityPlan.controlled(protocol.original(), owned.outputBase(), ignore);
    journal.put("rcPolicy", ignore ? "IGNORE" : "READ");
    Preflight first = a.replan(request -> refreshConfiguration(request, protocol.build()));
    Preflight second = b.replan(request -> refreshConfiguration(request, protocol.build()));
    checkCancelled();
    return installReview(first, second);
  }

  private PlanRequest refreshConfiguration(PlanRequest current, BazelCommand command) {
    BazelCommand resolved = command.toBuilder().executable(current.original().executable()).build();
    return new PlanRequest(
        resolved,
        current.capabilities(),
        current.preset(),
        current.sessionRawDirectory(),
        current.besEndpoint(),
        current.vetoed(),
        current.resolutions(),
        current.allowOverwrite(),
        inspectConfiguration(resolved),
        current.destinationsAreLocal());
  }

  private Optional<List<String>> inspectConfiguration(BazelCommand command) {
    if (clientOutcomeUnknown) return Optional.empty();
    return EffectiveOptions.resolve(
        command.executable().toString(),
        original.workingDirectory(),
        command,
        new CommandExecutor() {
          @Override
          public CommandResult run(CommandRequest request, Duration timeout)
              throws IOException, InterruptedException {
            try {
              CommandResult result = executor.run(request, timeout);
              if (result.timedOut() || result.exitCode() == 255) clientOutcomeUnknown = true;
              return result;
            } catch (IOException | InterruptedException failure) {
              clientOutcomeUnknown |=
                  original.isRemote() || failure instanceof InterruptedException;
              throw failure;
            }
          }

          @Override
          public CommandResult runRedirectingStdout(
              CommandRequest request, Duration timeout, Path output) {
            throw new UnsupportedOperationException(
                "Configuration inspection uses bounded output capture.");
          }

          @Override
          public RunningCommand start(CommandRequest request) {
            throw new UnsupportedOperationException(
                "Configuration inspection does not launch builds.");
          }

          @Override
          public InteractiveChannel openTerminal(String directory) {
            throw new UnsupportedOperationException(
                "Configuration inspection does not open terminals.");
          }
        });
  }

  private Review installReview(Preflight first, Preflight second) throws IOException {
    List<String> blockers = new ArrayList<>(protocol.capabilityBlockers(first.capabilities()));
    if (clientOutcomeUnknown)
      blockers.add(
          "A command outcome is unknown. Cancel and review the connection; no build or private-base"
              + " cleanup will run.");
    blockers.addAll(protocol.capabilityBlockers(second.capabilities()));
    if (!first.executable().resolved().equals(second.executable().resolved())
        || !first.capabilities().bazelVersion().equals(second.capabilities().bazelVersion())) {
      blockers.add("The executable/version changed between A and B preflight.");
    }
    if (first.executable().sha256().isPresent()
        && second.executable().sha256().isPresent()
        && !first.executable().sha256().equals(second.executable().sha256())) {
      blockers.add("The resolved launcher's file bytes changed between A and B preflight.");
    }
    for (Preflight preflight : List.of(first, second)) {
      blockers.addAll(protocol.effectiveBlockers(preflight.plan().effective()));
      blockers.addAll(protocol.configurationBlockers(preflight.request().effectiveOptions()));
      if (!preflight.canLaunch()) {
        blockers.add("Resolve capture instrumentation conflicts for both runs before launch.");
      }
      ExecutionLogBinding.reviewBlocker(preflight.request(), preflight.plan())
          .ifPresent(blockers::add);
    }
    List<String> reviewNotices =
        new ArrayList<>(
            List.of(
                rcPolicyNotice(protocol.ignoreRcFiles()),
                "Audit-owned output-base, cache, convenience-link and resource flags override rc"
                    + " defaults as shown in Protocol changes. Explicit command-line conflicts"
                    + " must be resolved; remote/dynamic strategies are not silently replaced.",
                "Clean is explicitly synchronous and non-expunging (--noasync --noexpunge),"
                    + " including when rc files configure a different clean mode.",
                "The reviewed commands request execution on the selected machine, with no"
                    + " disk/remote action cache. Recorded cached/remote spawns stop the audit;"
                    + " unknown runners are coverage gaps. Repository download caches remain"
                    + " enabled.",
                "Source checks include dirty and untracked files; .git and the standard root"
                    + " bazel-bin/out/testlogs/workspace links are excluded. Other symlinks,"
                    + " special files and exceeded bounds stop the audit.",
                "The lease prevents other app captures, not edits or builds from external"
                    + " terminals. Permission changes, transient edits and inputs outside the"
                    + " repository are not fully checked.",
                "Version checks do not prove that a trusted wrapper or tool installation behaves"
                    + " identically. Launcher hashes are compared when available during preflight;"
                    + " later external tool changes are not fully checked.",
                "Auxiliary aquery/cquery commands are deferred; they do not run between A and B.",
                "Ordinary clean does not reset every worker, host or network state. Equal recorded"
                    + " results never prove hermeticity.",
                "Raw sessions and the private operation record are retained. A's output file"
                    + " contents are not retained before the second clean."));
    if (ReproducibilityPlan.allowsCaptureBoundIdentity(reviewedVersion)) {
      reviewNotices.add(
          "Bazel "
              + reviewedVersion
              + " compact logs have no embedded invocation ID. This audit"
              + " uses capture-bound evidence: distinct fresh app-owned paths, successful complete"
              + " BES captures, successful preservation and recorded checksums. Embedded invocation"
              + " identity is not independently verified.");
    }
    review =
        new Review(
            journal.directory(),
            protocol,
            first,
            second,
            blockers.stream().distinct().toList(),
            reviewNotices);
    state = State.REVIEW;
    journal.put("bazelVersion", reviewedVersion);
    journal.put("rcPolicy", protocol.ignoreRcFiles() ? "IGNORE" : "READ");
    journal.put("bazelExecutable", reviewedExecutable.toString());
    journal.put("state", state.name());
    return review;
  }

  /** Execute only the exact latest review instance approved by the user. */
  public Result run(Review approved) throws IOException {
    synchronized (this) {
      requireOpen();
      if (approved == null
          || approved != review
          || !approved.canLaunch()
          || state != State.REVIEW) {
        throw new IllegalArgumentException(
            "the latest launchable audit review must be explicitly approved");
      }
      if (!running.compareAndSet(false, true)) {
        throw new IllegalStateException("audit already running");
      }
      state = State.RUNNING;
    }
    CaptureResult first = null;
    CaptureResult second = null;
    try {
      journal.put("state", state.name());
      recordCommand("buildA", approved.a().plan().effective());
      recordCommand("buildB", approved.b().plan().effective());
      recordCommand("clean", protocol.clean());
      recordCommand("shutdown", protocol.shutdown());
      ExecutionPath root =
          files.canonicalize(
              files.path(
                  original
                      .connectedRemote()
                      .map(remote -> remote.repositoryRootText())
                      .orElseGet(
                          () ->
                              approved
                                  .a()
                                  .workspace()
                                  .workspaceRoot()
                                  .orElse(original.localWorkingDirectory())
                                  .toString())));
      RepositorySnapshot.Result before = snapshot(Step.SNAPSHOT_BEFORE, root, "before");
      verifyConfiguration(approved.a());
      clean(Step.CLEAN_A);
      first = capture(Step.BUILD_A, a, "A");
      preserve(Step.PRESERVE_A, first, a, Optional.empty());
      RepositorySnapshot.Result between = snapshot(Step.SNAPSHOT_BETWEEN, root, "between");
      requireUnchanged(before, between);
      verifyConfiguration(approved.b());
      clean(Step.CLEAN_B);
      second = capture(Step.BUILD_B, b, "B");
      preserve(Step.PRESERVE_B, second, b, a.executionLogReceipt());
      RepositorySnapshot.Result after = snapshot(Step.SNAPSHOT_AFTER, root, "after");
      requireUnchanged(before, after);
      state = State.CAPTURED;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      clientOutcomeUnknown |= original.isRemote();
      state = cancelled.get() ? State.CANCELLED : State.FAILED;
      notices.add("The audit was interrupted; subsequent builds were not started.");
      journal.put("failure", interrupted.toString());
    } catch (IOException | RuntimeException failure) {
      state = cancelled.get() ? State.CANCELLED : State.FAILED;
      notices.add(
          failure.getMessage() == null
              ? "The audit failed; its operation record was preserved."
              : failure.getMessage());
      journal.put("failure", failure.toString());
    } finally {
      active = null;
      try {
        journal.put("state", state.name());
        if (a != null && a.sessionRoot().isPresent()) {
          SessionAuditReference.protect(a.sessionRoot().orElseThrow(), journal.directory());
          journal.put("sessionA", a.sessionRoot().orElseThrow().toString());
        }
        if (b != null && b.sessionRoot().isPresent()) {
          SessionAuditReference.protect(b.sessionRoot().orElseThrow(), journal.directory());
          journal.put("sessionB", b.sessionRoot().orElseThrow().toString());
        }
      } finally {
        try {
          finishCleanup();
        } finally {
          running.set(false);
          releaseLease();
        }
      }
    }
    return new Result(
        journal.directory(),
        state,
        cleanup,
        Optional.ofNullable(capturedA),
        Optional.ofNullable(capturedB),
        notices);
  }

  private RepositorySnapshot.Result snapshot(Step step, ExecutionPath root, String name)
      throws IOException {
    checkCancelled();
    step(step);
    RepositorySnapshot.Result result =
        RepositorySnapshot.capture(
            files,
            root,
            journal.directory().resolve("source-" + name + ".manifest"),
            cancelled::get);
    journal.put("source." + name + ".fingerprint", result.fingerprint());
    journal.put("source." + name + ".entries", Long.toString(result.files()));
    journal.put("source." + name + ".bytes", Long.toString(result.bytes()));
    return result;
  }

  private static void requireUnchanged(
      RepositorySnapshot.Result before, RepositorySnapshot.Result later) throws IOException {
    if (!before.fingerprint().equals(later.fingerprint())) {
      throw new IOException(
          "Repository source bytes or entries changed during the audit. No changes were reverted;"
              + " the run pair is not an unchanged-source experiment.");
    }
  }

  private void verifyConfiguration(Preflight approved) throws IOException {
    if (protocol.ignoreRcFiles()) return;
    checkCancelled();
    BazelCommand command = approved.request().original();
    Optional<List<String>> observed = inspectConfiguration(command);
    List<String> blockers = protocol.configurationBlockers(observed);
    if (!blockers.isEmpty()) throw new IOException(String.join("\n", blockers));
    if (!observed.equals(approved.request().effectiveOptions())) {
      throw new IOException(
          "The observed rc/build options changed after review. No later clean or build will start;"
              + " review a new diagnostic.");
    }
  }

  private static String rcPolicyNotice(boolean ignore) {
    return ignore
        ? "Rc files are ignored for both builds and helpers. This is not the workspace's normal"
            + " configured build."
        : "Normal rc files and named configs are enabled for both builds and helpers. Observed"
            + " build options are rechecked before each clean. Repository rc files are covered by"
            + " source checks, but files outside the repository and transient changes are not"
            + " fully verified. Command-specific helper rc options are not fully inspected.";
  }

  private void clean(Step stage) throws IOException, InterruptedException {
    checkCancelled();
    step(stage);
    verifyOutputBase();
    checkCancelled();
    runHelper(protocol.clean());
  }

  private void verifyOutputBase() throws IOException, InterruptedException {
    owned.validate();
    if (!ReproducibilityPlan.supportsVersion(reviewedVersion)
        || reviewedExecutable == null
        || !protocol.outputBaseProbe().executable().equals(reviewedExecutable)) {
      throw new IOException(
          "No supported reviewed executable/version is available for private-base cleanup.");
    }
    String version =
        runHelper(
                protocol.outputBaseProbe().toBuilder()
                    .command("version")
                    .commandArgs(List.of("--gnu_format"))
                    .targets(List.of())
                    .build())
            .strip();
    requireReviewedVersion(reviewedVersion, version);
    String observed = runHelper(protocol.outputBaseProbe()).strip();
    if (!observed.equals(owned.outputBase())
        || !files.canonicalize(files.path(observed)).value().equals(owned.outputBase())) {
      throw new IOException(
          "Bazel resolved a different output base. Refusing to clean or shut down it.");
    }
  }

  static void requireReviewedVersion(String expected, String output) throws IOException {
    if (!ReproducibilityPlan.supportsVersion(expected)
        || !output
            .lines()
            .filter(line -> line.startsWith("bazel "))
            .toList()
            .equals(List.of("bazel " + expected))) {
      throw new IOException(
          "The selected Bazel version changed or cannot be verified. Refusing private-base"
              + " cleanup.");
    }
  }

  private CaptureResult capture(Step stage, CaptureCoordinator capture, String name)
      throws IOException {
    checkCancelled();
    step(stage);
    active = capture;
    try {
      checkCancelled();
      CaptureResult result;
      try {
        result = capture.run();
      } catch (IOException | RuntimeException failure) {
        clientOutcomeUnknown |= original.isRemote();
        throw failure;
      }
      recordCaptureResult(result, name);
      if (!result.buildSucceeded() || !result.captureComplete() || result.wasCancelled()) {
        throw new IOException(
            "Build "
                + name
                + " did not finish with successful, complete capture; no later build will start.");
      }
      return result;
    } finally {
      active = null;
    }
  }

  /** Latch cleanup safety before any fallible local evidence/reference writes. */
  void recordCaptureResult(CaptureResult result, String name) throws IOException {
    if (result.process().isEmpty()
        || result.process().orElseThrow().failure().isPresent()
        || result.process().orElseThrow().exitCode().isEmpty()
        || result.process().orElseThrow().exitCode().orElse(-1) == 255
        || (original.isRemote() && result.wasCancelled())) {
      clientOutcomeUnknown = true;
    }
    if (name.equals("A")) {
      capturedA = result;
    } else {
      capturedB = result;
    }
    SessionAuditReference.protect(result.sessionRoot(), journal.directory());
    journal.put("session" + name, result.sessionRoot().toString());
  }

  private void preserve(
      Step stage,
      CaptureResult capture,
      CaptureCoordinator coordinator,
      Optional<CaptureCoordinator.ExecutionLogReceipt> previous)
      throws IOException {
    checkCancelled();
    step(stage);
    Path local =
        executionLogPath(capture)
            .orElseThrow(
                () -> new IOException("An unambiguous managed execution log was not planned."));
    if (!Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS) || Files.size(local) == 0) {
      throw new IOException(
          "The execution log was not preserved locally; the next clean is refused.");
    }
    String invocationId = ExecutionLogBinding.singleInvocationId(capture);
    var verified =
        ExecutionLogComparison.verify(local, journal.directory(), invocationId, cancelled::get);
    ExecutionLogBinding.Identity binding =
        ExecutionLogBinding.verify(
            reviewedVersion, capture, coordinator.executionLogReceipt(), verified, previous);
    String bindingNotice = binding.notice(reviewedVersion, stage == Step.PRESERVE_A ? "A" : "B");
    journal.put("execution." + stage.name() + ".evidenceBinding", binding.name());
    journal.put("execution." + stage.name() + ".evidenceBindingNotice", bindingNotice);
    notices.add(bindingNotice);
    journal.put("execution." + stage.name() + ".sha256", verified.sha256());
    journal.put("execution." + stage.name() + ".records", Long.toString(verified.records()));
    journal.put("execution." + stage.name() + ".spawns", Long.toString(verified.spawns()));
    journal.put(
        "execution." + stage.name() + ".cachedSpawns", Long.toString(verified.cachedSpawns()));
    journal.put(
        "execution." + stage.name() + ".remoteSpawns", Long.toString(verified.remoteSpawns()));
    journal.put(
        "execution." + stage.name() + ".unknownRunnerSpawns",
        Long.toString(verified.unknownRunnerSpawns()));
    for (int index = 0; index < verified.coverageNotes().size(); index++) {
      journal.put(
          "execution." + stage.name() + ".coverage." + index, verified.coverageNotes().get(index));
    }
    journal.put("preserved." + stage.name(), local.toString());
    notices.addAll(
        executionScopeNotes(
            verified.cachedSpawns(), verified.remoteSpawns(), verified.unknownRunnerSpawns()));
  }

  static List<String> executionScopeNotes(long cached, long remote, long unknown)
      throws IOException {
    if (cached > 0 || remote > 0) {
      throw new IOException(
          "The execution log recorded "
              + cached
              + " cached and "
              + remote
              + " remote-execution spawns. This violates the reviewed uncached, on-machine"
              + " protocol; the evidence was kept but no later build will start.");
    }
    return unknown == 0
        ? List.of()
        : List.of(
            unknown
                + " spawns have unknown runners. Execution on the selected machine is not verified"
                + " for those observations.");
  }

  private String runHelper(BazelCommand command) throws IOException, InterruptedException {
    int helper = ++helperCount;
    recordCommand("helper." + helper, command);
    journal.put("helper." + helper + ".state", "DISPATCHED");
    RuntimeEnvironment inheritance =
        switch (command.inheritance()) {
          case INHERIT_ALL -> RuntimeEnvironment.INHERIT_ALL;
          case INHERIT_ALLOWLISTED -> RuntimeEnvironment.INHERIT_ESSENTIAL;
          case NONE -> RuntimeEnvironment.NONE;
        };
    CommandResult result;
    try {
      result =
          executor.run(
              new CommandRequest(
                  command.toArgv(),
                  Optional.of(original.workingDirectory()),
                  original.environmentOverrides(),
                  inheritance,
                  false),
              Duration.ofSeconds(60));
    } catch (IOException | InterruptedException failure) {
      clientOutcomeUnknown |= original.isRemote();
      throw failure;
    }
    if (result.timedOut() || result.exitCode() == 255) {
      clientOutcomeUnknown = true;
    }
    Files.writeString(
        journal.directory().resolve("helper-" + helper + ".stdout.log"),
        result.stdout(),
        StandardCharsets.UTF_8);
    Files.writeString(
        journal.directory().resolve("helper-" + helper + ".stderr.log"),
        result.stderr(),
        StandardCharsets.UTF_8);
    journal.put(
        "helper." + helper + ".state",
        result.timedOut() ? "UNKNOWN" : "EXITED_" + result.exitCode());
    if (!result.isSuccess()) {
      throw new IOException("Audit helper failed: " + result.failureDetail());
    }
    return result.stdout();
  }

  private static PlanRequest withoutAuxiliaryQueries(PlanRequest request) {
    return request
        .vetoing(Capability.AQUERY_PROTO_OUTPUT)
        .vetoing(Capability.CQUERY_PROTO_OUTPUT)
        .vetoing(Capability.PROFILE_PATH)
        .vetoing(Capability.JSON_TRACE_PROFILE)
        .vetoing(Capability.STARLARK_CPU_PROFILE);
  }

  private CaptureRequest copyRequest(BazelCommand command) {
    return new CaptureRequest(
        journal.directory().resolve("sessions"),
        original.appVersion(),
        original.executable(),
        original.workingDirectory(),
        command.userVisibleArgs(),
        CapturePreset.defaultPreset(),
        original.environmentOverrides(),
        original.inheritance(),
        false,
        original.console(),
        original.progress(),
        original.options().withDeferredAuxiliaryProcessing(),
        original.sshTarget(),
        original.connectedRemote());
  }

  private void recordCommand(String name, BazelCommand command) throws IOException {
    List<String> argv = command.toArgv();
    for (int index = 0; index < argv.size(); index++) {
      journal.put("command." + name + "." + index, argv.get(index));
    }
  }

  private void step(Step value) throws IOException {
    journal.put("step", value.name());
    progress.accept(value);
  }

  private void recordFailure(String failure) throws IOException {
    state = cancelled.get() ? State.CANCELLED : State.FAILED;
    if (journal != null) {
      journal.put("failure", failure);
      journal.put("state", state.name());
    }
  }

  public void cancel() {
    cancelled.set(true);
    CaptureCoordinator capture = active;
    if (capture != null) {
      capture.cancel(CancellationMode.CANCEL);
    }
  }

  private void checkCancelled() throws IOException {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
      throw new IOException("Audit cancelled; no subsequent build will start.");
    }
  }

  private void finishCleanup() throws IOException {
    boolean interrupted = Thread.interrupted();
    Exception failure = null;
    Exception privateCleanupFailure = null;
    try {
      if (a != null) {
        try {
          a.close();
        } catch (RuntimeException closing) {
          failure = closing;
        }
      }
      if (b != null) {
        try {
          b.close();
        } catch (RuntimeException closing) {
          failure = appendFailure(failure, closing);
        }
      }
      if (failure != null) {
        cleanup = Cleanup.NEEDS_REVIEW;
        notices.add("Capture resource cleanup failed; the private base was retained for review.");
      } else if (owned == null) {
        if (cleanup == Cleanup.PENDING) {
          cleanup = Cleanup.NEEDS_REVIEW;
        }
      } else if (clientOutcomeUnknown) {
        cleanup = Cleanup.NEEDS_REVIEW;
        notices.add(
            "A command outcome is unknown. The private base was kept; review and reconcile it"
                + " before cleanup. No command was replayed after SSH recovery.");
      } else {
        try {
          if (bazelContacted) {
            step(Step.SHUTDOWN);
            verifyOutputBase();
            runHelper(protocol.shutdown());
          }
          step(Step.CLEANUP);
          owned.removeAfterShutdown();
          cleanup = Cleanup.REMOVED;
        } catch (IOException | InterruptedException | RuntimeException cleaning) {
          interrupted |= cleaning instanceof InterruptedException;
          privateCleanupFailure = cleaning;
          cleanup = Cleanup.NEEDS_REVIEW;
          notices.add(
              "Private Bazel cleanup needs review; the operation record retains its exact owned"
                  + " path.");
          try {
            journal.put("cleanupFailure", cleaning.toString());
          } catch (IOException | RuntimeException writing) {
            failure = appendFailure(cleaning, writing);
          }
        }
      }
    } finally {
      try {
        if (journal != null) {
          journal.put("cleanup", cleanup.name());
        }
      } catch (IOException | RuntimeException writing) {
        failure = appendFailure(failure == null ? privateCleanupFailure : failure, writing);
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    rethrowCleanupFailure(failure);
  }

  private static Exception appendFailure(Exception primary, Exception later) {
    if (primary == null) {
      return later;
    }
    if (primary != later) {
      primary.addSuppressed(later);
    }
    return primary;
  }

  private static void rethrowCleanupFailure(Exception failure) throws IOException {
    if (failure instanceof IOException io) {
      throw io;
    }
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    if (failure != null) {
      throw new IOException("Audit cleanup failed", failure);
    }
  }

  private void releaseLease() throws IOException {
    if (leaseReleased.compareAndSet(false, true)) {
      try {
        workspaceLease.close();
      } catch (Exception failure) {
        throw new IOException("could not release the audit workspace lease", failure);
      }
    }
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("audit coordinator is closed");
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    cancelled.set(true);
    if (running.get()) {
      cancel();
      return;
    }
    closed = true;
    Exception failure = null;
    try {
      if (state == State.REVIEW || state == State.PREFLIGHT) {
        state = State.CANCELLED;
      }
      if (journal != null) {
        journal.put("state", state.name());
      }
    } catch (IOException | RuntimeException writing) {
      failure = writing;
    } finally {
      try {
        if (cleanup == Cleanup.PENDING || cleanup == Cleanup.NOT_ALLOCATED) {
          finishCleanup();
        }
      } catch (IOException | RuntimeException cleaning) {
        failure = appendFailure(failure, cleaning);
      } finally {
        try {
          releaseLease();
        } catch (IOException | RuntimeException releasing) {
          failure = appendFailure(failure, releasing);
        }
      }
    }
    rethrowCleanupFailure(failure);
  }

  public static SavedOperation readSavedOperation(Path directory) throws IOException {
    Properties record = AuditJournal.read(directory);
    List<String> notices = new ArrayList<>();
    String rcPolicy = record.getProperty("rcPolicy", "IGNORE");
    notices.add(
        switch (rcPolicy) {
          case "READ" -> rcPolicyNotice(false);
          case "IGNORE" -> rcPolicyNotice(true);
          default -> "The saved rc-file policy is unknown: " + rcPolicy;
        });
    for (String key : List.of("failure", "cleanupFailure")) {
      String detail = record.getProperty(key);
      if (detail != null) {
        notices.add((key.equals("failure") ? "Audit failure: " : "Cleanup failure: ") + detail);
      }
    }
    for (String name : List.of("before", "between", "after")) {
      String fingerprint = record.getProperty("source." + name + ".fingerprint");
      if (fingerprint != null) {
        notices.add(
            "Source check "
                + name
                + ": "
                + record.getProperty("source." + name + ".entries", "unknown")
                + " entries, "
                + record.getProperty("source." + name + ".bytes", "unknown")
                + " bytes; fingerprint recorded.");
      }
    }
    for (String phase : List.of("PRESERVE_A", "PRESERVE_B")) {
      String binding = record.getProperty("execution." + phase + ".evidenceBindingNotice");
      if (binding != null) {
        notices.add(binding);
      }
      String count = record.getProperty("execution." + phase + ".unknownRunnerSpawns");
      if (count != null && !count.equals("0")) {
        notices.add(
            phase
                + ": "
                + count
                + " spawns have unknown runners; execution on the selected machine is not verified"
                + " for those observations.");
      }
    }
    return new SavedOperation(
        directory,
        record.getProperty("state", "UNKNOWN"),
        record.getProperty("cleanup", "NEEDS_REVIEW"),
        Optional.ofNullable(record.getProperty("sessionA")).map(Path::of),
        Optional.ofNullable(record.getProperty("sessionB")).map(Path::of),
        record.getProperty("privateBase", ""),
        Optional.ofNullable(record.getProperty("preserved.PRESERVE_A")).map(Path::of),
        Optional.ofNullable(record.getProperty("preserved.PRESERVE_B")).map(Path::of),
        Optional.ofNullable(record.getProperty("execution.PRESERVE_A.sha256")),
        Optional.ofNullable(record.getProperty("execution.PRESERVE_B.sha256")),
        record.getProperty("step", "UNKNOWN"),
        notices);
  }

  /** Pure local-path lookup; existence/verified preservation are separate from a planned name. */
  public static Optional<Path> executionLogPath(CaptureResult capture) {
    Set<String> knownNames =
        Set.of(
            InstrumentationPlanner.EXECUTION_LOG_FILE,
            InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    List<Path> logs =
        capture.plan().expectedOutputs().stream()
            .filter(path -> knownNames.contains(path.getFileName().toString()))
            .toList();
    return logs.size() == 1
        ? Optional.of(
            ManagedSessionLayout.at(capture.sessionRoot())
                .rawDirectory()
                .resolve(logs.getFirst().getFileName().toString()))
        : Optional.empty();
  }
}
