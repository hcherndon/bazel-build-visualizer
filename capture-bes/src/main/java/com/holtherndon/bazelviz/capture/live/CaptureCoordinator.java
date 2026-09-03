package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.capture.bes.BesServer;
import com.holtherndon.bazelviz.capture.bes.BesServerConfig;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParseOutcome;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParseResult;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParser;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphTargetScope;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.enrich.execlog.EnvironmentRedactor;
import com.holtherndon.bazelviz.enrich.execlog.ExecutionLogImporter;
import com.holtherndon.bazelviz.enrich.graph.ActionGraphImporter;
import com.holtherndon.bazelviz.enrich.graph.AuxiliaryQueryRunner;
import com.holtherndon.bazelviz.enrich.graph.BepTargetQueryFile;
import com.holtherndon.bazelviz.enrich.graph.ConfiguredTargetImporter;
import com.holtherndon.bazelviz.enrich.profile.ProfileImporter;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileImporter;
import com.holtherndon.bazelviz.format.journal.ImportCheckpointStore;
import com.holtherndon.bazelviz.format.journal.JournalWriter;
import com.holtherndon.bazelviz.format.journal.JournalWriterConfig;
import com.holtherndon.bazelviz.format.session.ManagedSession;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilityDetector;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import com.holtherndon.bazelviz.runner.command.EffectiveOptions;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.exec.BazelExecutableResolver;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.files.UploadMode;
import com.holtherndon.bazelviz.runner.launch.BazelLauncher;
import com.holtherndon.bazelviz.runner.launch.LaunchRequest;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryCommandPlan;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability.Availability;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.ssh.SshReverseForward;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceDetector;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.events.DiagnosticCodes;
import com.holtherndon.bazelviz.storage.events.DiagnosticSeverity;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.ImportDiagnostic;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import com.holtherndon.bazelviz.storage.events.StringDictionary;
import com.holtherndon.bazelviz.storage.graph.ActionEdgeDeriver;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one instrumented Bazel build into a managed session (plan 7.1 {@code CaptureCoordinator}).
 *
 * <h2>Two phases, because the user decides in between</h2>
 *
 * <p>{@link #preflight()} resolves the executable, finds the workspace, probes capabilities, starts
 * the embedded BES server and builds the plan. It creates no session and runs no build, so the
 * dialog can show the plan, take a veto or a conflict resolution, re-plan, and be abandoned at no
 * cost beyond a socket.
 *
 * <p>{@link #run} then creates the session, attaches the pipeline, launches Bazel and finalizes.
 * The two are separate because ADR-007 requires the user to see the effective command before it
 * runs, and a plan built without the real port would not be the command that runs.
 *
 * <h2>Order of construction, and why</h2>
 *
 * <p>The session and its journal exist before the process starts, and the session moves to {@code
 * CAPTURING} before {@code ProcessBuilder.start()}. Bazel connects to the BES endpoint almost
 * immediately, so a session created after the launch would have to hold the first events somewhere
 * else — and "somewhere else" is where events get lost. Finalization runs in a {@code finally}: a
 * build that dies, is cancelled, or fails to start still leaves an inspectable session, which is a
 * Phase 2 exit criterion.
 */
public final class CaptureCoordinator implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(CaptureCoordinator.class);

  /** Largest single SSH capture artifact copied into a managed session. */
  public static final long MAX_REMOTE_CAPTURE_FILE_BYTES = 32L * 1024 * 1024 * 1024;

  private final CaptureRequest request;
  private final SessionManager sessions;
  private final BazelCapabilityDetector detector;
  private final Clock clock;

  private final SettableRawEventSink sink = new SettableRawEventSink();
  private final AtomicReference<BazelLauncher.BazelProcess> running = new AtomicReference<>();

  /**
   * A stop requested before there was anything to stop.
   *
   * <p>Cancellation used to read {@code running} and return silently when it was null — which is
   * the state for the whole of preflight and for the session setup that follows it. A user pressing
   * Ctrl-C during the capability probe was told "asking Bazel to stop", and then the build they had
   * just cancelled was launched. The request is now remembered, and the launch path asks before
   * starting anything.
   */
  private final AtomicReference<CancellationMode> pendingCancel = new AtomicReference<>();

  /**
   * Whether the one escalation ladder has been started.
   *
   * <p>{@link #cancel} used to start a fresh daemon thread on every call, each running a full
   * escalation ladder against the same process and each timing its own grace period. A user who
   * clicked Cancel, then Terminate, then Force Kill inside the thirty-second Cancel grace — which
   * is exactly what a user does when the first click appears to do nothing — had three ladders
   * racing, sending rungs in whatever order they woke up in.
   *
   * <p>Now the first request runs the ladder and every later one only delivers its harsher rung,
   * immediately, into the same process. The ladder picks the new rung up because {@link
   * BazelLauncher.BazelProcess#cancel} never re-sends a rung and always continues from the harshest
   * one already delivered.
   */
  private final AtomicBoolean escalating = new AtomicBoolean();

  private BesServer server;
  private Preflight preflight;
  private CommandExecutor commandExecutor = LocalCommandExecutor.INSTANCE;
  private ExecutionFileSystem remoteFileSystem;
  private RemoteExecution remoteExecution;
  private boolean remoteExecutionOwned;
  private boolean remoteExecutionDetached;
  private SshReverseForward reverseForward;
  private String remoteStagingDirectory;
  private boolean closed;

  public CaptureCoordinator(CaptureRequest request) {
    this(
        request,
        new SessionManager(request.sessionsRoot(), request.appVersion()),
        new BazelCapabilityDetector(),
        Clock.systemUTC());
  }

  public CaptureCoordinator(
      CaptureRequest request,
      SessionManager sessions,
      BazelCapabilityDetector detector,
      Clock clock) {
    this.request = Objects.requireNonNull(request, "request");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.detector = Objects.requireNonNull(detector, "detector");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // ------------------------------------------------------------- preflight

  /**
   * Resolves everything needed to show the user what will run.
   *
   * <p>The BES server is started here rather than at launch, because the plan has to contain the
   * real endpoint: showing a placeholder and substituting a port afterwards would mean the command
   * the user approved is not the command that runs, which is the one thing ADR-007 exists to
   * prevent.
   */
  /**
   * The request's environment overrides that set a value, as a plain map.
   *
   * <p>The unsets are dropped: they matter to the build and not to identifying the binary, and
   * {@code Subprocess} has no way to express "remove this variable" anyway.
   */
  private Map<String, String> setVariables() {
    Map<String, String> set = new LinkedHashMap<>();
    request
        .environmentOverrides()
        .forEach((name, value) -> value.ifPresent(present -> set.put(name, present)));
    return set;
  }

  public synchronized Preflight preflight() throws IOException {
    if (preflight != null) {
      log.debug(
          "reusing {} capture preflight for {}",
          request.isRemote() ? "SSH" : "local",
          preflight.executable().displayName());
      return preflight;
    }
    long startedNanos = System.nanoTime();
    log.info(
        "{} capture preflight started: preset={}, argumentCount={}, shellMode={}",
        request.isRemote() ? "SSH" : "local",
        request.preset(),
        request.args().size(),
        request.shellMode());
    try {
      Preflight resolved = request.isRemote() ? preflightRemote() : preflightLocal();
      log.info(
          "{} capture preflight finished: bazel={}, appliedFlags={},"
              + " auxiliaryCommands={}, conflicts={}, launchable={}, elapsed={} ms",
          request.isRemote() ? "SSH" : "local",
          resolved.executable().displayName(),
          resolved.plan().appliedFlags().size(),
          resolved.plan().auxiliaryCommands().size(),
          resolved.plan().conflicts().size(),
          resolved.canLaunch(),
          elapsedMillis(startedNanos));
      return resolved;
    } catch (IOException | RuntimeException failure) {
      log.error(
          "{} capture preflight failed after {} ms",
          request.isRemote() ? "SSH" : "local",
          elapsedMillis(startedNanos),
          failure);
      throw failure;
    }
  }

  private Preflight preflightLocal() throws IOException {
    Path localWorkingDirectory = request.localWorkingDirectory();
    WorkspaceInfo workspace = WorkspaceDetector.detect(localWorkingDirectory);
    // Resolved under the environment the build will run with, so that
    // bazelisk's USE_BAZEL_VERSION picks the same Bazel here as it will
    // there. Without this the detector probes one version and the build
    // runs another, and the planner injects flags the build rejects.
    BazelExecutable executable =
        BazelExecutableResolver.resolve(
            request.executable(), workspace.workspaceRoot(), setVariables());
    var capabilities = detector.detect(executable, startupArgsOf(executable, workspace));

    server =
        new BesServer(
            sink,
            BesServerConfig.defaults().withMaxMessageBytes(request.options().maxMessageBytes()));
    BesEndpoint endpoint = server.start();

    BazelCommand original =
        new CommandLineParser(Optional.of(capabilities))
            .parse(executable.resolved(), localWorkingDirectory, request.args()).toBuilder()
                .environmentOverrides(request.environmentOverrides())
                .inheritance(request.inheritance())
                .shellMode(request.shellMode())
                .build();

    // Planned against a session directory that does not exist yet. Only the
    // fallback BEP file is named from it, and only when a conflict pushes
    // the capture onto that path; the directory is created before anything
    // is written to it.
    Path provisionalRaw = request.sessionsRoot().resolve("pending-raw");
    // Asked of Bazel, in the workspace, with the rc files in force: an
    // option the user set in a .bazelrc is one they set, and injecting over
    // it without saying so is the same defect as doing it to a typed flag.
    PlanRequest planRequest =
        PlanRequest.initial(
                original,
                capabilities,
                request.preset(),
                provisionalRaw,
                Optional.of(endpoint.besBackendUri()))
            .withEffectiveOptions(
                EffectiveOptions.resolve(executable.resolved(), localWorkingDirectory, original));

    preflight =
        new Preflight(
            executable,
            workspace,
            capabilities,
            endpoint,
            new InstrumentationPlanner().plan(planRequest),
            planRequest);
    return preflight;
  }

  /** Resolves the same preflight entirely on the SSH execution host. */
  private Preflight preflightRemote() throws IOException {
    RemoteExecution execution = null;
    try {
      if (request.connectedRemote().isPresent()) {
        execution = request.connectedRemote().orElseThrow();
        remoteExecutionOwned = false;
        // The window already owns this connection. It must never be
        // detached back to the listener or closed with this capture.
        remoteExecutionDetached = true;
      } else {
        execution =
            RemoteExecution.connect(
                request.sshTarget().orElseThrow(),
                request.workingDirectory(),
                Duration.ofSeconds(30));
        remoteExecutionOwned = true;
        remoteExecutionDetached = false;
      }
      remoteExecution = execution;
      commandExecutor = execution.commandExecutor();
      remoteFileSystem = execution.fileSystem();

      RemoteWorkspace detected =
          detectRemoteWorkspace(remoteFileSystem, request.workingDirectory());
      String workingDirectory = detected.workingDirectory().value();
      Optional<String> workspaceRoot = detected.workspaceRoot().map(ExecutionPath::value);
      WorkspaceInfo workspace = remoteWorkspaceCompatibility(detected);

      BazelExecutable executable =
          BazelExecutableResolver.resolve(
              request.executable(), workingDirectory, setVariables(), commandExecutor);
      var capabilities =
          new BazelCapabilityDetector(commandExecutor)
              .detect(executable, startupArgsOf(executable, workspace));

      server =
          new BesServer(
              sink,
              BesServerConfig.defaults().withMaxMessageBytes(request.options().maxMessageBytes()));
      BesEndpoint endpoint = server.start();
      reverseForward = execution.openReverseForward(endpoint.port());
      remoteStagingDirectory = createRemoteStaging(commandExecutor);

      BazelCommand original =
          new CommandLineParser(Optional.of(capabilities))
                  // Compatibility carrier only. Executor-backed paths below
                  // are converted back to text and never touched with Files.
                  .parse(executable.resolved(), Path.of(workingDirectory), request.args())
                  .toBuilder()
                  .environmentOverrides(request.environmentOverrides())
                  .inheritance(request.inheritance())
                  .shellMode(request.shellMode())
                  .build();
      PlanRequest planRequest =
          PlanRequest.initial(
                  original,
                  capabilities,
                  request.preset(),
                  Path.of(remoteStagingDirectory),
                  Optional.of(reverseForward.besBackendUri().toString()))
              .withRemoteDestinations()
              .withEffectiveOptions(
                  EffectiveOptions.resolve(
                      executable.resolved().toString(),
                      workingDirectory,
                      original,
                      commandExecutor));

      Preflight.RemoteDetails remote =
          new Preflight.RemoteDetails(
              execution.displayName(),
              workingDirectory,
              workspaceRoot,
              endpoint.besBackendUri(),
              reverseForward.besBackendUri().toString(),
              remoteStagingDirectory);
      preflight =
          new Preflight(
              executable,
              workspace,
              capabilities,
              endpoint,
              new InstrumentationPlanner().plan(planRequest),
              planRequest,
              Optional.of(remote));
      return preflight;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      closeRemotePreflight(execution);
      throw new IOException("SSH preflight was interrupted", interrupted);
    } catch (IOException | RuntimeException failure) {
      closeRemotePreflight(execution);
      throw failure;
    }
  }

  private void closeRemotePreflight(RemoteExecution execution) {
    cleanupRemoteStagingQuietly(null, new ArrayList<>());
    closeReverseForward();
    if (server != null) {
      server.close();
      server = null;
    }
    if (execution != null && remoteExecutionOwned) {
      execution.close();
    }
    commandExecutor = LocalCommandExecutor.INSTANCE;
    remoteFileSystem = null;
    remoteExecution = null;
    remoteExecutionOwned = false;
    remoteExecutionDetached = false;
    remoteStagingDirectory = null;
  }

  /**
   * Re-plans with the user's answers, keeping the same endpoint and probe.
   *
   * <p>Adjusts the request that produced the current plan rather than building a fresh one, so
   * answers accumulate: a user who resolves a conflict and then vetoes a flag still has both.
   */
  public synchronized Preflight replan(UnaryOperator<PlanRequest> adjust) throws IOException {
    Preflight current = preflight();
    PlanRequest adjusted = adjust.apply(current.request());
    preflight =
        new Preflight(
            current.executable(),
            current.workspace(),
            current.capabilities(),
            current.endpoint(),
            new InstrumentationPlanner().plan(adjusted),
            adjusted,
            current.remote());
    return preflight;
  }

  // ------------------------------------------------------------------- run

  /**
   * Creates the session, launches the build, and finalizes whatever happened.
   *
   * @throws IllegalStateException when the plan cannot be launched; callers must resolve mandatory
   *     conflicts first, and the plan says which
   */
  public CaptureResult run() throws IOException {
    long startedNanos = System.nanoTime();
    Preflight ready = preflight();
    if (!ready.canLaunch()) {
      throw new IllegalStateException(
          "this plan cannot be launched: " + describeBlockers(ready.plan()));
    }

    SessionId sessionId = SessionId.random();
    List<String> warnings = new ArrayList<>();
    ManagedSession session = sessions.create(sessionId);
    Path sessionRoot = session.root();
    liveSessionRoot = sessionRoot;
    ManagedSessionLayout layout = session.layout();

    SessionDatabase database = null;
    EventWriter events = null;
    EntityWriter entities = null;
    StreamRegistry streams = null;
    JournalWriter journal = null;
    LiveCapturePipeline pipeline = null;
    ConsoleCapture console = null;
    ProcessOutcome outcome = null;
    CaptureSummary summary = null;
    boolean remoteOutputsTransferAttempted = false;
    Set<String> remoteOutputsToPreserve = new LinkedHashSet<>();
    // FAILED_TO_START only until the session starts capturing. After that
    // it is unreachable -- CAPTURING goes to BUILD_FINISHED, CANCELLED,
    // INCOMPLETE or CORRUPT_PARTIAL and nowhere else -- so a failure after
    // launch used to ask for a transition the state machine refuses, and
    // the session stayed in CAPTURING for ever with no record of why.
    SessionState terminal = SessionState.FAILED_TO_START;
    // The plan that actually ran, which differs from the preflight plan in
    // the paths it names. Reporting the preflight one meant `--json` could
    // print an injected flag pointing at a directory nothing ever created.
    InstrumentationPlan executedPlan = ready.plan();
    log.info(
        "capture {} started: execution={}, command={}, targets={}, preset={}",
        sessionId,
        request.isRemote() ? "SSH" : "local",
        BazelLauncher.operationForLogging(ready.plan().effective().command()),
        ready.plan().effective().targets().size(),
        ready.plan().preset());

    try {
      session.transitionTo(SessionState.PREFLIGHT);

      // The plan named a provisional raw directory during preflight, when
      // no session existed. Re-plan against the real one -- from the same
      // request, with only that field changed, so every answer the user
      // gave survives.
      Path executionRawDirectory =
          request.isRemote() ? Path.of(requireRemoteStaging()) : layout.rawDirectory();
      InstrumentationPlan plan =
          new InstrumentationPlanner().plan(ready.request().inSession(executionRawDirectory));
      executedPlan = plan;

      writeManifest(session, ready, plan);
      InstrumentationPlanCodec.write(layout.instrumentationPlanFile(), plan, ready);

      database = SessionDatabase.open(layout.databaseFile());
      new MigrationRunner(MigrationRunner.standard().migrations()).migrate(database);
      log.debug("capture {} storage is ready", sessionId);
      events =
          new EventWriter(
              database.writerConnection(),
              request.options().batchSize(),
              StringDictionary.DEFAULT_CACHE_ENTRIES);
      entities = new EntityWriter(database.writerConnection());
      streams = new StreamRegistry(database.writerConnection());

      journal =
          JournalWriter.create(
              layout.rawDirectory(),
              sessionId.value(),
              JournalWriterConfig.defaults()
                  .withMaxPayloadBytes(request.options().maxMessageBytes()));
      pipeline =
          new LiveCapturePipeline(
              journal,
              events,
              entities,
              streams,
              new EventNormalizer(request.options().maxMessageBytes()),
              new ImportCheckpointStore(layout.checkpointsDirectory()),
              request.options(),
              request.progress(),
              clock);
      pipeline.start();
      sink.attach(pipeline);
      log.debug("capture {} journal and BES pipeline are ready", sessionId);

      console = ConsoleCapture.open(layout.rawDirectory(), request.console());

      // CAPTURING before the process exists: Bazel connects almost at
      // once, and a session that was not yet capturing would have nowhere
      // to put the first events.
      session.transitionTo(SessionState.CAPTURING);
      terminal = SessionState.INCOMPLETE;

      CancellationMode requestedBeforeLaunch = pendingCancel.get();
      if (requestedBeforeLaunch != null) {
        // Asked to stop before there was anything to stop. Launching
        // now would run the build the user has already cancelled, which
        // is what this used to do.
        warnings.add("the launch was cancelled before Bazel was started");
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.WARNING,
                CaptureDiagnosticCodes.CAPTURE_CANCELLED,
                "cancelled during preparation, before Bazel was started",
                nowMicros()));
        outcome =
            ProcessOutcome.cancelled(OptionalInt.empty(), requestedBeforeLaunch, Duration.ZERO);
      } else {
        LaunchRequest launch = LaunchRequest.of(plan.effective(), console);
        BazelLauncher.BazelProcess process =
            request.isRemote()
                ? BazelLauncher.start(launch, commandExecutor, true)
                : BazelLauncher.start(launch);
        running.set(process);
        // A stop that arrived while the process was starting would have
        // found `running` still null a moment ago. Re-checked here so
        // that window cannot swallow it either -- and applied through
        // the same single stopper, because a click landing in that same
        // window sees a process now and starts its own. Two entries into
        // one ladder is precisely the race this routes around; running
        // the ladder inline here would also block this thread for up to
        // three-quarters of a minute before await() was ever reached.
        if (pendingCancel.get() != null) {
          applyPendingCancel();
        }
        outcome = process.await();
        log.info(
            "capture {} Bazel process ended: exit={}, cancelled={},"
                + " failure={}, duration={} ms",
            sessionId,
            outcome.exitCode().isPresent()
                ? Integer.toString(outcome.exitCode().getAsInt())
                : "unavailable",
            outcome.wasCancelled(),
            outcome.failure().isPresent(),
            outcome.duration().toMillis());
        awaitStreamsToSettle(outcome);
      }

      if (request.isRemote()) {
        remoteOutputsTransferAttempted = true;
        remoteOutputsToPreserve.addAll(transferRemoteOutputs(plan, layout, warnings));
      }

      // The keep-your-own-backend resolution (plan 8.5 option 2) told
      // Bazel to write a local copy of the stream. Reading it is the
      // whole point of offering that choice, and the dialog says so:
      // "This application reads a local copy instead."
      ingestFallbackFile(plan, layout, pipeline, warnings);

      summary = pipeline.finish();
      log.info(
          "capture {} BES pipeline finished: received={}, journaled={},"
              + " normalized={}, decodeFailures={}, streams={}, complete={}",
          sessionId,
          summary.received(),
          summary.journaled(),
          summary.normalized(),
          summary.decodeFailures(),
          summary.streams().size(),
          summary.isComplete());
      terminal = terminalStateFor(outcome, summary, warnings);
    } catch (SQLException failure) {
      warnings.add("the session database could not be prepared: " + failure);
      log.error(
          "capture {} could not prepare storage after {} ms",
          sessionId,
          elapsedMillis(startedNanos),
          failure);
      throw new IOException("cannot prepare the capture session at " + sessionRoot, failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      warnings.add("the capture was interrupted");
      terminal = SessionState.INCOMPLETE;
    } catch (IOException | RuntimeException failure) {
      log.error("capture {} failed after {} ms", sessionId, elapsedMillis(startedNanos), failure);
      throw failure;
    } finally {
      // Cleared for the duration of the finalization, and restored at the
      // end. An interrupted thread cannot drain a queue, cannot join, and
      // cannot write to a FileChannel -- NIO closes the channel out from
      // under it -- so finalizing while interrupted loses the journal
      // buffer that was already acknowledged to Bazel. The interrupt is a
      // request to stop capturing, not a request to abandon what was
      // captured.
      boolean interrupted = Thread.interrupted();
      // Cleared before either of these runs: reaping waits on the client
      // and an interrupted thread cannot wait. Taken out of `running` in
      // the same step, so a stop arriving during finalization signals
      // nothing rather than racing the reap.
      BazelLauncher.BazelProcess launched = running.getAndSet(null);
      reportEscalation(launched, warnings);
      reapIfStillRunning(launched, warnings);
      // An interrupt or launch failure can bypass the normal post-process copy. Recover
      // remote raw artifacts after the client has stopped, while SSH/SFTP is still alive.
      if (request.isRemote() && !remoteOutputsTransferAttempted) {
        remoteOutputsToPreserve.addAll(transferRemoteOutputs(executedPlan, layout, warnings));
      }
      closeReverseForward();
      sink.detach();
      // Closed in the order that preserves the most: the pipeline first
      // so its threads stop feeding the journal, then the journal, then
      // the database. Closing the database first would leave rows the
      // journal had already promised.
      summary = finishQuietly(pipeline, summary, warnings);
      if (console != null) {
        consoleWriteFailed = console.hasWriteFailure();
      }
      closeQuietly(console, "console log", warnings);
      if (console != null && console.hasWriteFailure()) {
        consoleWriteFailed = true;
        warnings.add(
            "some of the build's console output could not be written to the"
                + " session's log files");
      }
      closeJournalQuietly(journal, warnings);
      recordOutcome(events, outcome, summary, warnings);
      // Bulk-load-then-index, the same as the import path: nothing has
      // created a secondary index yet, because maintaining one per row
      // during a live capture is what the deferral exists to avoid. A
      // session that skipped this is correct and slow -- every view query
      // falls back to a scan -- which is why it runs here and not only
      // on the import path, where it used to be the only caller.
      reportNormalizationAnomalies(entities, events, pipeline, warnings);
      finalizeIndexesQuietly(entities, events, warnings);
      // After the indexes, because correlation joins actions by their
      // primary output. Before the database closes, because that is the
      // connection the imports write through.
      enrichQuietly(database, executedPlan, layout, warnings);
      queryGraphsQuietly(database, executedPlan, layout, warnings);
      cleanupRemoteStagingQuietly(executedPlan, warnings, remoteOutputsToPreserve);
      closeQuietly(entities, "entity writer", warnings);
      closeQuietly(events, "event writer", warnings);
      closeQuietly(streams, "stream registry", warnings);
      closeQuietly(database, "session database", warnings);
      terminal = finalizeSession(session, terminal, summary, outcome, warnings);
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    CaptureResult result =
        new CaptureResult(
            sessionRoot,
            sessionId,
            terminal,
            executedPlan,
            Optional.ofNullable(outcome),
            Optional.ofNullable(summary),
            warnings);
    log.info(
        "capture {} finished: state={}, buildOutcomeKnown={}, buildSucceeded={},"
            + " captureComplete={}, warnings={}, elapsed={} ms",
        sessionId,
        terminal,
        result.buildOutcomeKnown(),
        result.buildSucceeded(),
        result.captureComplete(),
        warnings.size(),
        elapsedMillis(startedNanos));
    return result;
  }

  /**
   * Stops the running build, if there is one.
   *
   * <p>Safe to call from any thread, including the EDT: it signals a process and does not wait for
   * the capture to finalize. {@link #run} returns a result describing a cancelled session once the
   * drain completes.
   */
  public void cancel(CancellationMode mode) {
    Objects.requireNonNull(mode, "mode");
    log.info(
        "capture cancellation requested: mode={}, processRunning={}", mode, running.get() != null);
    // Remembered first, and unconditionally. Whether or not a process
    // exists yet, the user has asked to stop, and that fact must outlive
    // this call.
    pendingCancel.accumulateAndGet(mode, CaptureCoordinator::harsherOf);
    applyPendingCancel();
  }

  /**
   * Delivers whatever stop has been asked for, on a thread of its own.
   *
   * <p>The first caller to get here runs the escalation ladder; every later one delivers its rung
   * and returns. That division is what makes the buttons responsive and the ladder single: a
   * Terminate clicked two seconds into the Cancel grace sends {@code SIGTERM} at once rather than
   * queueing behind twenty-eight seconds of waiting, and it does so without a second ladder timing
   * its own grace periods against the same client.
   *
   * <p>The ladder is asked to escalate — see {@link BazelLauncher.BazelProcess#cancel} — because
   * there is no guarantee of a second click. The CLI has one Ctrl-C and the window has none at all
   * once it is closing, and a client that outlives its cancellation holds the workspace's command
   * lock against every later Bazel command. Escalation is therefore the promise, and {@link
   * #reportEscalation} is the part that keeps it from being a silent one.
   *
   * <p>Called with no process only from {@link #cancel}, where the request is already remembered in
   * {@code pendingCancel} and the launch path asks again the moment there is something to signal.
   */
  private void applyPendingCancel() {
    BazelLauncher.BazelProcess process = running.get();
    if (process == null) {
      return;
    }
    boolean runsTheLadder = escalating.compareAndSet(false, true);
    Thread stopper =
        new Thread(
            () -> {
              CancellationMode mode = pendingCancel.get();
              if (mode == null) {
                return;
              }
              try {
                if (runsTheLadder) {
                  process.cancel(mode, true);
                } else {
                  process.requestStop(mode);
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            },
            "bbv-capture-cancel");
    stopper.setDaemon(true);
    stopper.start();
  }

  /** True while a build is running. */
  /**
   * Set once the session directory exists, read from the UI thread. Volatile rather than
   * synchronized because it is written once and read often.
   */
  private volatile Path liveSessionRoot;

  /**
   * The session directory, once there is one.
   *
   * <p>Published as soon as it is created rather than when the capture ends, so a view can open the
   * session read-only and watch it fill. The directory, its manifest and its database all exist
   * before Bazel is launched; what is inside them grows for the life of the build.
   */
  public Optional<Path> sessionRoot() {
    return Optional.ofNullable(liveSessionRoot);
  }

  public boolean isRunning() {
    return running.get() != null;
  }

  /** True once a stop has been asked for, whether or not it could be applied. */
  public boolean isCancelRequested() {
    return pendingCancel.get() != null;
  }

  /** The harsher of two stops, so a second request never softens the first. */
  private static CancellationMode harsherOf(CancellationMode current, CancellationMode next) {
    return current == null || next.ordinal() > current.ordinal() ? next : current;
  }

  /**
   * Says so when the ladder had to go further than the user asked.
   *
   * <p>Escalation is the promise that the client dies and the workspace lock is released, and it is
   * worth keeping. It is also an override of a choice the user made — Cancel keeps the event
   * stream, Force Kill costs it — and rule 12 does not permit an override to be silent. The session
   * records which rung it actually took, so "why is my stream incomplete when I pressed Cancel?"
   * has an answer written down next to the session.
   */
  private void reportEscalation(BazelLauncher.BazelProcess process, List<String> warnings) {
    if (process == null) {
      return;
    }
    CancellationMode asked = pendingCancel.get();
    CancellationMode applied = process.stoppedBy().orElse(null);
    if (asked == null || applied == null || applied.ordinal() <= asked.ordinal()) {
      return;
    }
    warnings.add(
        "the build did not stop when it was asked to, so the stop was escalated from "
            + asked
            + " to "
            + applied
            + "; a Bazel client that is still running holds this"
            + " workspace's command lock, and every later Bazel command in it — 'clean'"
            + " included — waits for that lock");
  }

  /**
   * Force-stops a client that is somehow still alive at finalization.
   *
   * <p>The backstop for the one path the escalation ladder does not cover: {@link
   * BazelLauncher.BazelProcess#await()} is an untimed {@code waitFor}, and the only way out of it
   * other than the process exiting is an interrupt — which unwinds straight to the {@code finally}
   * with the client untouched and the last reference to it about to be dropped. That leaves a Bazel
   * client nobody is watching, holding the workspace's command lock until the user finds the pid
   * themselves — and every Bazel command in that workspace, {@code clean} first among them, waiting
   * on it in the meantime.
   *
   * <p>Nothing is force-killed on a normal ending, because there is nothing left alive to kill:
   * {@code await()} returns when the process exits.
   */
  private void reapIfStillRunning(BazelLauncher.BazelProcess process, List<String> warnings) {
    if (process == null || !process.isAlive()) {
      return;
    }
    log.warn(
        "the capture is finalizing while the Bazel client {} is still running;"
            + " force-stopping it so it does not hold the workspace lock",
        process.pid());
    warnings.add(
        "the Bazel client was still running when the capture ended, so it was"
            + " force-stopped; leaving it alive would have held this workspace's command lock"
            + " and blocked every later Bazel command in it, including 'clean'");
    try {
      process.cancel(CancellationMode.FORCE_KILL, false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    cleanupRemoteStagingQuietly(
        preflight == null ? null : preflight.plan(), new ArrayList<>(), Set.of());
    closeReverseForward();
    if (server != null) {
      server.close();
      server = null;
    }
    if (remoteExecution != null && remoteExecutionOwned && !remoteExecutionDetached) {
      remoteExecution.close();
    }
    remoteExecution = null;
    remoteExecutionOwned = false;
    remoteFileSystem = null;
  }

  /** Transfers ownership of a successfully connected SSH workspace to the UI. */
  public synchronized Optional<RemoteExecution> detachRemoteExecution() {
    if (remoteExecution == null || !remoteExecutionOwned || remoteExecutionDetached) {
      return Optional.empty();
    }
    remoteExecutionDetached = true;
    return Optional.of(remoteExecution);
  }

  private static RemoteWorkspace detectRemoteWorkspace(
      ExecutionFileSystem files, String requestedDirectory) throws IOException {
    ExecutionPath working = files.canonicalize(files.path(requestedDirectory));
    FileMetadata workingMetadata = files.stat(working);
    if (!workingMetadata.isDirectory()) {
      throw new IOException(
          "the remote working directory is not a directory: "
              + working
              + workingMetadata.detail().map(detail -> " (" + detail + ")").orElse(""));
    }

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
      for (int index = 0; index < metadata.size(); index++) {
        if (metadata.get(index).isRegularFile()) {
          return new RemoteWorkspace(
              working, Optional.of(current), Optional.of(WorkspaceInfo.MARKERS.get(index)));
        }
      }
      ExecutionPath parent = files.canonicalize(files.resolve(current, ".."));
      if (parent.value().equals(current.value())) {
        return new RemoteWorkspace(working, Optional.empty(), Optional.empty());
      }
      current = parent;
    }
  }

  private static WorkspaceInfo remoteWorkspaceCompatibility(RemoteWorkspace workspace)
      throws IOException {
    try {
      return new WorkspaceInfo(
          Path.of(workspace.workingDirectory().value()),
          workspace.workspaceRoot().map(root -> Path.of(root.value())),
          workspace.marker(),
          workspace.workspaceRoot().isPresent()
              ? WorkspaceInfo.Detection.MARKER_SEARCH
              : WorkspaceInfo.Detection.NOT_FOUND);
    } catch (RuntimeException invalid) {
      throw new IOException(
          "the remote Linux workspace path cannot be displayed: " + workspace.workingDirectory(),
          invalid);
    }
  }

  private record RemoteWorkspace(
      ExecutionPath workingDirectory,
      Optional<ExecutionPath> workspaceRoot,
      Optional<String> marker) {}

  private static String createRemoteStaging(CommandExecutor executor)
      throws IOException, InterruptedException {
    CommandResult created =
        executor.run(
            CommandRequest.of(
                List.of("/usr/bin/mktemp", "-d", "/tmp/bbv-capture.XXXXXXXX"), (String) null),
            Duration.ofSeconds(15));
    String directory = created.stdout().strip();
    if (!created.isSuccess() || !isOwnedRemoteStaging(directory)) {
      throw new IOException(
          "could not create private SSH capture staging: " + created.failureDetail());
    }
    CommandResult permissions =
        executor.run(
            CommandRequest.of(List.of("/bin/chmod", "700", "--", directory), (String) null),
            Duration.ofSeconds(10));
    if (!permissions.isSuccess()) {
      executor.run(
          CommandRequest.of(List.of("/bin/rmdir", "--", directory), (String) null),
          Duration.ofSeconds(10));
      throw new IOException(
          "could not make SSH capture staging private: " + permissions.failureDetail());
    }
    return directory;
  }

  private String requireRemoteStaging() {
    if (remoteStagingDirectory == null) {
      throw new IllegalStateException("the SSH capture staging directory is unavailable");
    }
    return remoteStagingDirectory;
  }

  private void closeReverseForward() {
    SshReverseForward closing = reverseForward;
    reverseForward = null;
    if (closing != null) {
      closing.close();
    }
  }

  /** Deletes only files whose exact names this capture created, then its private directory. */
  private void cleanupRemoteStagingQuietly(InstrumentationPlan plan, List<String> warnings) {
    cleanupRemoteStagingQuietly(plan, warnings, Set.of());
  }

  private void cleanupRemoteStagingQuietly(
      InstrumentationPlan plan, List<String> warnings, Set<String> preservedOutputs) {
    String staging = remoteStagingDirectory;
    if (!request.isRemote() || staging == null || commandExecutor == null) {
      return;
    }
    if (!isOwnedRemoteStaging(staging)) {
      warnings.add("refused to clean an invalid SSH staging path: " + staging);
      return;
    }
    boolean retainForRecovery = !preservedOutputs.isEmpty();
    if (retainForRecovery) {
      warnings.add(
          "the private SSH capture staging directory was retained at "
              + staging
              + " because "
              + preservedOutputs.size()
              + " capture file(s) could not be copied; reconnect to recover: "
              + String.join(", ", preservedOutputs));
      log.warn(
          "retaining SSH staging {} for uncopied capture files: {}", staging, preservedOutputs);
      // Relinquish cleanup ownership now so a later close cannot erase the recovery copy,
      // even if removing the other app-created files fails below.
      remoteStagingDirectory = null;
    }
    List<String> createdFiles = new ArrayList<>();
    if (plan != null) {
      for (Path output : plan.expectedOutputs()) {
        String value = output.toString();
        if (value.startsWith(staging + "/") && !preservedOutputs.contains(value)) {
          createdFiles.add(value);
        }
      }
    }
    createdFiles.add(staging + "/" + AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE);
    createdFiles.add(staging + "/" + AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE);
    try {
      List<String> remove = new ArrayList<>();
      remove.add("/bin/rm");
      remove.add("-f");
      remove.add("--");
      remove.addAll(createdFiles);
      CommandResult removed =
          commandExecutor.run(CommandRequest.of(remove, (String) null), Duration.ofSeconds(20));
      if (retainForRecovery) {
        if (!removed.isSuccess()) {
          warnings.add(
              "other temporary files could not be removed from retained SSH"
                  + " staging "
                  + staging
                  + ": "
                  + removed.failureDetail());
        }
        return;
      }
      CommandResult directory =
          commandExecutor.run(
              CommandRequest.of(List.of("/bin/rmdir", "--", staging), (String) null),
              Duration.ofSeconds(20));
      if (!removed.isSuccess() || !directory.isSuccess()) {
        String detail = !removed.isSuccess() ? removed.failureDetail() : directory.failureDetail();
        warnings.add(
            "the private SSH capture staging directory could not be fully" + " removed: " + detail);
        log.warn("could not clean SSH staging {}: {}", staging, detail);
        return;
      }
      remoteStagingDirectory = null;
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      warnings.add("the private SSH capture staging directory could not be removed: " + failure);
      log.warn("could not clean SSH staging {}", staging, failure);
    }
  }

  private static boolean isOwnedRemoteStaging(String path) {
    return path != null && path.matches("/tmp/bbv-capture\\.[A-Za-z0-9]{8,}");
  }

  /**
   * Reads the local BEP file the plan asked Bazel to write, if there is one.
   *
   * <p>Runs after the process has exited and before the pipeline is drained, on this thread, which
   * is the only writer at that point. A file that was planned and never appeared is a warning
   * rather than a failure: Bazel may have died before creating it, and that is a fact about the
   * build, not a fault in the capture.
   */
  private void ingestFallbackFile(
      InstrumentationPlan plan,
      ManagedSessionLayout layout,
      LiveCapturePipeline pipeline,
      List<String> warnings) {
    // Read from the effective command rather than from the plan's added
    // flags, so both branches are covered by one rule: the file Bazel was
    // told to write is the file to read, whether this application named it
    // or the user did.
    Optional<Path> fallback =
        buildEventFileOf(plan)
            .map(
                file ->
                    request.isRemote()
                        ? layout.rawDirectory().resolve(InstrumentationPlanner.FALLBACK_BEP_FILE)
                        : resolveLocalExecutionPath(file));
    if (fallback.isEmpty()) {
      return;
    }
    Path file = fallback.get();
    if (!Files.isRegularFile(file)) {
      warnings.add(
          "the build was asked to write "
              + file
              + " and did not, so no events were captured from it");
      return;
    }
    try {
      long[] ordinal = {0};
      BinaryBepParseResult result =
          BinaryBepParser.withDefaults()
              .parseFile(
                  file,
                  0,
                  frame -> {
                    try {
                      // Copied rather than passed through: the frame's
                      // buffer is a view that is only valid for the
                      // duration of this callback, and the journal writes
                      // from a byte array.
                      byte[] payload = frame.copyPayload();
                      pipeline.ingestFileRecord(payload, 0, payload.length, ordinal[0]++);
                    } catch (SQLException storeFailure) {
                      throw new IOException(storeFailure);
                    }
                  });
      pipeline.flushFileRecords();
      fallbackSource = Optional.of(new FallbackSource(file, ordinal[0], result.outcome()));
      if (result.outcome() != BinaryBepParseOutcome.COMPLETE) {
        warnings.add(
            file.getFileName()
                + " was "
                + result.outcome()
                + "; "
                + ordinal[0]
                + " event(s) were read before the damage");
      }
    } catch (IOException | SQLException | RuntimeException failure) {
      warnings.add("could not read the local build event file " + file + ": " + failure);
      log.warn("could not read the local build event file {}", file, failure);
    }
  }

  /**
   * The local build event file the effective command writes, if any.
   *
   * <p>Only consulted on the keep-your-backend path. On the ordinary path events arrive live
   * through the embedded server and no file is written, so an empty answer here is the normal case,
   * not a failure.
   */
  private static Optional<String> buildEventFileOf(InstrumentationPlan plan) {
    if (plan.sourceAvailability().entry(DataSource.BES_ENVELOPE).availability()
        == Availability.PLANNED) {
      return Optional.empty();
    }
    String found = null;
    List<String> commandArgs = plan.effective().commandArgs();
    for (int index = 0; index < commandArgs.size(); index++) {
      String token = commandArgs.get(index);
      Optional<String> name = CommandLineParser.flagName(token);
      if (name.isPresent() && name.get().equals("build_event_binary_file")) {
        // Last one wins, exactly as Bazel resolves it.
        Optional<String> attached = CommandLineParser.attachedValue(token);
        if (attached.isPresent()) {
          found = attached.orElseThrow();
        } else if (index + 1 < commandArgs.size()) {
          // This command was parsed with the probed capability table, so a separate
          // value immediately follows a known value-taking flag in commandArgs.
          found = commandArgs.get(++index);
        }
      }
    }
    return Optional.ofNullable(found);
  }

  /** Resolves a user-supplied local output against the directory Bazel ran in. */
  private Path resolveLocalExecutionPath(String value) {
    Path requested = Path.of(value);
    return requested.isAbsolute()
        ? requested.normalize()
        : request.localWorkingDirectory().resolve(requested).normalize().toAbsolutePath();
  }

  /** Copies planned primary artifacts from the SSH host before local import. */
  private Set<String> transferRemoteOutputs(
      InstrumentationPlan plan, ManagedSessionLayout layout, List<String> warnings) {
    ExecutionFileSystem files = remoteFileSystem;
    Set<String> appOwnedOutputs = appOwnedRemoteOutputs(plan);
    if (files == null) {
      warnings.add(
          "remote capture files could not be copied because the SSH filesystem"
              + " is unavailable");
      return appOwnedOutputs;
    }
    String effectiveWorkingDirectory =
        preflight == null
            ? request.workingDirectory()
            : preflight
                .remote()
                .map(Preflight.RemoteDetails::workingDirectory)
                .orElse(request.workingDirectory());
    return transferRemoteOutputs(
        files, effectiveWorkingDirectory, plan, layout.rawDirectory(), warnings);
  }

  static Set<String> transferRemoteOutputs(
      ExecutionFileSystem files,
      String effectiveWorkingDirectory,
      InstrumentationPlan plan,
      Path localRawDirectory,
      List<String> warnings) {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(effectiveWorkingDirectory, "effectiveWorkingDirectory");
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(localRawDirectory, "localRawDirectory");
    Objects.requireNonNull(warnings, "warnings");
    Set<String> failures = new LinkedHashSet<>(appOwnedRemoteOutputs(plan));
    ExecutionPath workingDirectory;
    try {
      workingDirectory = files.path(effectiveWorkingDirectory);
    } catch (IOException | RuntimeException failure) {
      warnings.add(
          "remote capture files could not be copied because the working directory"
              + " is invalid: "
              + failure);
      return Set.copyOf(failures);
    }
    for (RemoteCaptureTransfer transfer : remoteCaptureTransfers(plan, localRawDirectory)) {
      try {
        ExecutionPath source = files.resolve(workingDirectory, transfer.executionPath());
        FileMetadata sourceMetadata = files.stat(source);
        if (sourceMetadata.state() == FileMetadata.State.MISSING) {
          warnings.add(
              "Bazel was asked to write "
                  + transfer.executionPath()
                  + " on the SSH host, but it does not exist");
          failures.remove(transfer.executionPath());
          continue;
        }
        if (sourceMetadata.state() != FileMetadata.State.PRESENT) {
          warnings.add(
              "Bazel was asked to write "
                  + transfer.executionPath()
                  + " on the SSH host, but "
                  + sourceMetadata.detail().orElse("its metadata is unavailable"));
          continue;
        }
        ExecutionPath readable = files.canonicalize(source);
        FileMetadata metadata = files.stat(readable);
        if (!metadata.isRegularFile()) {
          warnings.add(
              "Bazel was asked to write "
                  + transfer.executionPath()
                  + " on the SSH host, but "
                  + metadata.detail().orElse("it is not a regular file"));
          continue;
        }
        files.download(readable, transfer.localDestination(), MAX_REMOTE_CAPTURE_FILE_BYTES);
        failures.remove(transfer.executionPath());
      } catch (IOException | RuntimeException failure) {
        warnings.add(
            "could not copy remote capture file " + transfer.executionPath() + ": " + failure);
        log.warn("could not copy remote capture file {}", transfer.executionPath(), failure);
      }
    }
    return Set.copyOf(failures);
  }

  private static Set<String> appOwnedRemoteOutputs(InstrumentationPlan plan) {
    Set<String> outputs = new LinkedHashSet<>();
    for (Path output : plan.expectedOutputs()) {
      outputs.add(output.toString());
    }
    return outputs;
  }

  /**
   * Plans copies into managed local storage without treating Linux paths as desktop Paths.
   *
   * <p>The planner excludes a user-owned BEP output from {@code expectedOutputs}, because the app
   * did not cause that file to be written and must never delete it. It still has to be downloaded
   * when the user explicitly chose "read that file", so it is added here with a stable app-owned
   * destination. Cleanup continues to use {@code expectedOutputs} only.
   */
  static List<RemoteCaptureTransfer> remoteCaptureTransfers(
      InstrumentationPlan plan, Path localRawDirectory) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(localRawDirectory, "localRawDirectory");
    Map<String, RemoteCaptureTransfer> transfers = new LinkedHashMap<>();
    for (Path planned : plan.expectedOutputs()) {
      String executionPath = planned.toString();
      transfers.put(
          executionPath,
          new RemoteCaptureTransfer(
              executionPath, localRawDirectory.resolve(executionFileName(executionPath))));
    }
    buildEventFileOf(plan)
        .ifPresent(
            executionPath ->
                transfers.put(
                    executionPath,
                    new RemoteCaptureTransfer(
                        executionPath,
                        localRawDirectory.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE))));
    return List.copyOf(transfers.values());
  }

  record RemoteCaptureTransfer(String executionPath, Path localDestination) {
    RemoteCaptureTransfer {
      executionPath = Objects.requireNonNull(executionPath, "executionPath");
      localDestination = Objects.requireNonNull(localDestination, "localDestination");
      if (executionPath.isBlank() || executionPath.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("a remote capture path is blank or invalid");
      }
    }
  }

  /** Maps a planned execution-host file to its managed local raw copy. */
  private Path localCapturedFile(Path planned, ManagedSessionLayout layout) {
    return request.isRemote()
        ? layout.rawDirectory().resolve(executionFileName(planned.toString()))
        : planned;
  }

  private static String executionFileName(String path) {
    int slash = path.lastIndexOf('/');
    String name = slash < 0 ? path : path.substring(slash + 1);
    if (name.isBlank() || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("capture output has no safe filename: " + path);
    }
    return name;
  }

  /** A locally-captured BEP file that was read into this session. */
  private record FallbackSource(Path file, long events, BinaryBepParseOutcome outcome) {}

  private Optional<FallbackSource> fallbackSource = Optional.empty();

  // ------------------------------------------------------------- internals

  /**
   * Waits for the BES streams to close after the client has exited.
   *
   * <p>The client exiting is not the end of the event stream. The Bazel <em>server</em> is a
   * separate, longer-lived process that publishes the events, and after a force-kill it carries on
   * for about two and a half seconds — running actions, then cancelling the build itself, then
   * finishing the stream. Finishing the pipeline at the moment the client died would refuse those
   * last events, and the session would record a stream that aborted when in fact it completed.
   *
   * <p>Bounded, because a stream that never closes must not hang the application at exactly the
   * moment the user is trying to look at what was captured. Whatever arrived is already journaled
   * either way.
   */
  private void awaitStreamsToSettle(ProcessOutcome outcome) throws InterruptedException {
    if (server == null) {
      return;
    }
    // Longer after a force-kill, because that is the case where the server
    // is known to still be working. A clean exit means Bazel already
    // finished its upload, so the wait normally returns at once.
    Duration budget =
        outcome != null
                && outcome.terminatedBy().filter(CancellationMode.FORCE_KILL::equals).isPresent()
            ? Duration.ofSeconds(15)
            : Duration.ofSeconds(5);
    long deadline = System.nanoTime() + budget.toNanos();
    while (server.openStreamCount() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    if (server.openStreamCount() > 0) {
      log.info(
          "{} BES stream(s) were still open {} after the build exited; finalizing anyway",
          server.openStreamCount(),
          budget);
    }
  }

  /**
   * The terminal state that honestly describes what happened.
   *
   * <p>The build's success and the capture's completeness are different questions, and the state
   * answers the capture's. A failed build with a complete stream is {@code READY}: it is a good
   * session about a bad build, which is the most useful thing this tool produces.
   */
  private static SessionState terminalStateFor(
      ProcessOutcome outcome, CaptureSummary summary, List<String> warnings) {
    if (outcome != null && outcome.wasCancelled()) {
      return SessionState.CANCELLED;
    }
    if (summary == null) {
      return SessionState.INCOMPLETE;
    }
    if (summary.failure().isPresent()) {
      warnings.add("the capture failed: " + summary.failure().get());
      return SessionState.INCOMPLETE;
    }
    if (!summary.isComplete()) {
      // Includes the empty case. A session that received nothing is
      // INCOMPLETE, not READY: READY is a promise that the session
      // contains the build, and an empty one does not.
      warnings.addAll(summary.discrepancies());
      return SessionState.INCOMPLETE;
    }
    return summary.lagged() ? SessionState.READY_WITH_WARNINGS : SessionState.READY;
  }

  /**
   * Runs the post-build enrichment imports, if the plan asked for their files.
   *
   * <h2>Quietly, and that is the point</h2>
   *
   * <p>Plan 21.4: each enrichment task is independent and a failure must not invalidate the BEP. By
   * the time this runs the build events are written, normalized and indexed, and nothing here can
   * undo that — the importers write only to the tables schema v4 added, in their own transactions,
   * and record their own failures in {@code enrichment_tasks}.
   *
   * <p>So a missing or corrupt execution log costs the user the execution log and nothing else. It
   * does not fail the capture, does not change the session's terminal state, and adds a warning
   * only when a file the plan promised is not there — which is worth saying, because the user asked
   * for it.
   */
  private void enrichQuietly(
      SessionDatabase database,
      InstrumentationPlan plan,
      ManagedSessionLayout layout,
      List<String> warnings) {
    if (database == null) {
      return;
    }
    for (AddedFlag flag : plan.addedFlags()) {
      Optional<Path> written = flag.writesFile();
      if (written.isEmpty()) {
        continue;
      }
      Path file = localCapturedFile(written.get(), layout);
      long startedNanos = System.nanoTime();
      try {
        switch (flag.enables()) {
          case EXECUTION_LOG -> importExecutionLog(database, file, warnings);
          case PROFILE -> importProfile(database, file, warnings);
          case STARLARK_CPU_PROFILE -> importStarlarkCpuProfile(database, file, warnings);
          default -> {
            // BEP files are the capture path's own business.
          }
        }
        log.debug(
            "{} enrichment finished from {} in {} ms",
            flag.enables(),
            file.getFileName(),
            elapsedMillis(startedNanos));
      } catch (SQLException | RuntimeException failure) {
        // The task row already records this; the warning is for the
        // capture summary, which is read before anyone opens the
        // coverage panel.
        log.warn("enrichment from {} failed", file, failure);
        warnings.add("could not read " + file.getFileName() + ": " + failure);
      }
    }
    recordUnattemptedStarlarkCpuProfile(
        database.writerConnection(), plan, clock.millis() * 1_000L, warnings);
  }

  private void importExecutionLog(SessionDatabase database, Path file, List<String> warnings)
      throws SQLException {
    if (!Files.exists(file)) {
      warnings.add(
          "Bazel was asked to write an execution log to "
              + file
              + " and did not, so nothing is known about where actions ran.");
      return;
    }
    ExecutionLogImporter.Result result =
        new ExecutionLogImporter(database.writerConnection(), new EnvironmentRedactor())
            .importFrom(file);
    if (result.state() != EnrichmentTask.State.SUCCEEDED) {
      warnings.add(
          "the execution log could not be imported: " + result.error().orElse("unknown reason"));
    }
  }

  private void importProfile(SessionDatabase database, Path file, List<String> warnings)
      throws SQLException {
    if (!Files.exists(file)) {
      warnings.add(
          "Bazel was asked to write a trace profile to "
              + file
              + " and did not, so there are no build phases and no critical path.");
      return;
    }
    ProfileImporter.Result result =
        new ProfileImporter(database.writerConnection()).importFrom(file);
    if (result.state() != EnrichmentTask.State.SUCCEEDED) {
      warnings.add(
          "the trace profile could not be imported: " + result.error().orElse("unknown reason"));
    }
  }

  private void importStarlarkCpuProfile(SessionDatabase database, Path file, List<String> warnings)
      throws SQLException {
    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(database.writerConnection()).importFrom(file);
    if (result.state() != EnrichmentTask.State.SUCCEEDED) {
      warnings.add(
          "the Starlark CPU profile could not be imported: "
              + result.error().orElse("unknown reason"));
    }
  }

  /** Records why a requested Starlark profile was not attempted. */
  static void recordUnattemptedStarlarkCpuProfile(
      Connection connection, InstrumentationPlan plan, long atMicros, List<String> warnings) {
    if (!plan.preset().requestedCapabilities().contains(Capability.STARLARK_CPU_PROFILE)) {
      return;
    }
    var availability = plan.sourceAvailability().entry(DataSource.STARLARK_CPU_PROFILE);
    String result =
        switch (availability.availability()) {
          case PLANNED -> null;
          case DECLINED -> "SKIPPED: " + availability.reason();
          case UNAVAILABLE -> "UNSUPPORTED: " + availability.reason();
          case UNKNOWN -> "UNKNOWN: " + availability.reason();
        };
    if (result == null) {
      return;
    }
    try {
      EnrichmentTaskStore tasks = new EnrichmentTaskStore(connection);
      long taskId =
          tasks.begin(EnrichmentTask.Kind.STARLARK_CPU_PROFILE, Optional.empty(), atMicros);
      tasks.finish(
          taskId,
          EnrichmentTask.State.SKIPPED,
          Optional.of(result),
          Optional.empty(),
          false,
          StarlarkCpuProfileImporter.METRICS_LOST,
          OptionalLong.empty(),
          OptionalLong.empty(),
          atMicros);
    } catch (SQLException failure) {
      warnings.add("the unattempted Starlark CPU profile could not be recorded: " + failure);
      log.warn("could not record unattempted Starlark CPU profile", failure);
    }
  }

  /**
   * Runs the auxiliary queries and imports their graphs.
   *
   * <p>After the build, never during (plan 8.6): a query is an analysis pass in the same Bazel
   * server, so running it alongside would slow the build and change the timings this application
   * exists to report.
   *
   * <p>Quietly, for the same reason the Phase 4 enrichment is: plan 24 requires a failed auxiliary
   * query to leave the rest of the session usable, and these write only to tables schema v5 added.
   * A query that will not run costs the user the dependency graph and nothing else.
   */
  private void queryGraphsQuietly(
      SessionDatabase database,
      InstrumentationPlan plan,
      ManagedSessionLayout layout,
      List<String> warnings) {
    if (database == null || preflight == null) {
      return;
    }
    BazelCommand original = plan.original();
    AuxiliaryQueryPlanner planner = new AuxiliaryQueryPlanner(preflight.capabilities());
    AuxiliaryQueryRunner runner = new AuxiliaryQueryRunner();

    auxiliary(plan, "aquery")
        .ifPresent(
            declared ->
                runTargetScopedGraphQuery(
                    database,
                    warnings,
                    runner,
                    declared,
                    planner.aquery(original, declared.outputPath()),
                    original,
                    layout,
                    (connection, file, argv, scope, detail) ->
                        new ActionGraphImporter(connection)
                            .importFrom(file, argv, scope, detail)
                            .succeeded(),
                    (connection, file, argv, error, scope, detail) ->
                        new ActionGraphImporter(connection)
                            .recordFailure(file, argv, error, scope, detail)));
    auxiliary(plan, "cquery")
        .ifPresent(
            declared ->
                runTargetScopedGraphQuery(
                    database,
                    warnings,
                    runner,
                    declared,
                    planner.cquery(original, declared.outputPath()),
                    original,
                    layout,
                    (connection, file, argv, scope, detail) ->
                        new ConfiguredTargetImporter(connection)
                            .importFrom(file, argv, scope, detail)
                            .succeeded(),
                    (connection, file, argv, error, scope, detail) ->
                        new ConfiguredTargetImporter(connection)
                            .recordFailure(file, argv, error, scope, detail)));

    buildGraphIndexesQuietly(database, layout, warnings);
  }

  /** Runs aquery or cquery over the exact top-level labels the completed build reported. */
  private void runTargetScopedGraphQuery(
      SessionDatabase database,
      List<String> warnings,
      AuxiliaryQueryRunner runner,
      AuxiliaryCommandPlan declared,
      AuxiliaryQueryPlanner.Plan query,
      BazelCommand original,
      ManagedSessionLayout layout,
      GraphImport importer,
      GraphFailureRecorder failureRecorder) {
    Path localOutput = localCapturedFile(query.outputFile(), layout);
    BepTargetQueryFile.Result scope;
    try {
      scope =
          prepareTargetQueryFile(
              database.writerConnection(),
              query,
              original,
              layout,
              request.isRemote()
                  ? Optional.of(Objects.requireNonNull(remoteFileSystem))
                  : Optional.empty());
      if (scope.usedRequestedPatterns()) {
        warnings.add(
            "Bazel reported no top-level targets, so "
                + query.command().command()
                + " used the requested"
                + " target patterns. Its scope may be wider than this build.");
      } else if (!scope.scope().permitsExactClaim()) {
        warnings.add(
            query.command().command()
                + " used the top-level targets received before an incomplete BEP ended;"
                + " the resulting graph is retained but not trusted as complete.");
      }
    } catch (IOException | SQLException | RuntimeException failure) {
      String error =
          "The " + query.command().command() + " target scope could not be written: " + failure;
      warnings.add(error);
      recordGraphQueryFailure(
          database,
          warnings,
          query,
          localOutput,
          error,
          GraphTargetScope.UNKNOWN,
          GraphTargetScope.UNKNOWN.describe(),
          failureRecorder);
      return;
    }
    runGraphQuery(
        database,
        warnings,
        runner,
        declared,
        query,
        localOutput,
        scope.scope(),
        scope.detail(),
        importer,
        failureRecorder);
  }

  /** Writes locally and, for SSH capture, uploads the same bounded query file to staging. */
  static BepTargetQueryFile.Result prepareTargetQueryFile(
      Connection connection,
      AuxiliaryQueryPlanner.Plan query,
      BazelCommand original,
      ManagedSessionLayout layout,
      Optional<ExecutionFileSystem> remoteFiles)
      throws IOException, SQLException {
    Path executionQueryFile =
        AuxiliaryQueryPlanner.queryExpressionFile(query.command().command(), query.outputFile());
    Path localQueryFile =
        remoteFiles.isPresent()
            ? layout.rawDirectory().resolve(executionFileName(executionQueryFile.toString()))
            : executionQueryFile;
    List<String> requested = original.targets().isEmpty() ? List.of("//...") : original.targets();
    BepTargetQueryFile.Result result =
        new BepTargetQueryFile(connection)
            .write(localQueryFile, AuxiliaryQueryPlanner.dependencyClosure(requested));
    if (remoteFiles.isPresent()) {
      ExecutionFileSystem files = remoteFiles.orElseThrow();
      files.upload(
          localQueryFile,
          files.path(executionQueryFile.toString()),
          MAX_REMOTE_CAPTURE_FILE_BYTES,
          UploadMode.REPLACE);
    }
    return result;
  }

  private static Optional<AuxiliaryCommandPlan> auxiliary(InstrumentationPlan plan, String label) {
    return plan.auxiliaryCommands().stream()
        .filter(command -> command.label().equals(label))
        .findFirst();
  }

  /**
   * Derives the action edges and builds the CSR indexes the graph view reads.
   *
   * <h2>Here, because this is the only place the graphs are ever imported</h2>
   *
   * <p>{@code aquery} and {@code cquery} need a live workspace, so only a capture can import them —
   * a session imported from a BEP file alone has no graph to index. The edge derivation and the
   * index build therefore belong to the same finalization step as the imports whose rows they read.
   * Without this step the imports were a dead end: every real captured session had {@code
   * declared_actions} rows and no index, so {@code GraphQueries.forwardIndex} answered empty and
   * the canvas reported "no action graph" forever.
   *
   * <h2>On the capture worker, never anything interactive</h2>
   *
   * <p>This runs on the thread that ran the build, after the build, alongside the auxiliary queries
   * themselves — which cost a Bazel analysis pass each and dwarf an edge derivation. Nothing on the
   * EDT waits for it; the capture dialog polls the session state asynchronously.
   *
   * <h2>Quietly, like every other enrichment</h2>
   *
   * <p>A derivation or index build that fails costs the user the graph view and nothing else. The
   * failure is logged, a warning names it, and the graph view degrades to its honest "no index"
   * message rather than the capture failing — the raw query output is still on disk either way.
   */
  private void buildGraphIndexesQuietly(
      SessionDatabase database, ManagedSessionLayout layout, List<String> warnings) {
    // Two graphs, two independent failures: a broken aquery import must
    // not cost the user the label graph the cquery delivered, or the
    // other way round — the same independence the enrichment tasks have.
    try {
      Connection connection = database.writerConnection();
      ActionEdgeDeriver.Result derived = new ActionEdgeDeriver(connection).deriveAll();
      GraphIndexBuilder builder = new GraphIndexBuilder(connection, layout.indexesDirectory());
      var declared = builder.build(EdgeDerivation.DECLARED);
      var observed = builder.build(EdgeDerivation.OBSERVED);
      log.info(
          "derived {} declared and {} observed action edges; indexed {} / {}",
          derived.declaredEdges(),
          derived.observedEdges(),
          declared.map(Object::toString).orElse("no declared graph"),
          observed.map(Object::toString).orElse("no observed graph"));
    } catch (SQLException | IOException | RuntimeException failure) {
      log.warn("could not build the action graph index", failure);
      warnings.add(
          "The action dependency graph could not be indexed: "
              + failure
              + ". The graph view will report the action graph as unavailable;"
              + " everything else in this session is unaffected.");
    }
    try {
      GraphIndexBuilder builder =
          new GraphIndexBuilder(database.writerConnection(), layout.indexesDirectory());
      var labels = builder.buildConfiguredTargets();
      log.info("indexed {}", labels.map(Object::toString).orElse("no configured-target graph"));
    } catch (SQLException | IOException | RuntimeException failure) {
      log.warn("could not build the configured-target graph index", failure);
      warnings.add(
          "The configured-target graph could not be indexed: "
              + failure
              + ". The graph view will report the target graph as unavailable;"
              + " everything else in this session is unaffected.");
    }
  }

  private void runGraphQuery(
      SessionDatabase database,
      List<String> warnings,
      AuxiliaryQueryRunner runner,
      AuxiliaryCommandPlan declared,
      AuxiliaryQueryPlanner.Plan plan,
      Path localOutput,
      GraphTargetScope targetScope,
      String targetScopeDetail,
      GraphImport importer,
      GraphFailureRecorder failureRecorder) {
    if (!declared.argv().equals(plan.argv())) {
      String error =
          "The planned "
              + declared.label()
              + " command changed before finalization, so it was not run.";
      warnings.add(error);
      recordGraphQueryFailure(
          database,
          warnings,
          plan,
          localOutput,
          error,
          targetScope,
          targetScopeDetail,
          failureRecorder);
      return;
    }
    // Plan 8.6 step 10: say when the graph may not match because options
    // could not be reproduced. Said before the query runs, because that is
    // when it is a prediction rather than an excuse.
    plan.mismatchWarning().ifPresent(warnings::add);

    long startedNanos = System.nanoTime();
    log.info("{} graph query started", plan.command().command());
    AuxiliaryQueryRunner.Result result = runner.run(plan, commandExecutor, localOutput);
    if (!result.succeeded()) {
      log.warn(
          "{} graph query failed after {} ms",
          plan.command().command(),
          elapsedMillis(startedNanos));
      String error =
          "The "
              + plan.command().command()
              + " that would have described this"
              + " build's dependency graph did not run: "
              + result.error().orElse("unknown reason");
      warnings.add(error);
      recordGraphQueryFailure(
          database,
          warnings,
          plan,
          localOutput,
          error,
          targetScope,
          targetScopeDetail,
          failureRecorder);
      return;
    }
    try {
      if (!importer.run(
          database.writerConnection(),
          result.output(),
          plan.argv(),
          targetScope,
          targetScopeDetail)) {
        warnings.add("The " + plan.command().command() + " output could not be imported.");
      }
      log.info(
          "{} graph query imported in {} ms",
          plan.command().command(),
          elapsedMillis(startedNanos));
    } catch (SQLException | RuntimeException failure) {
      log.warn("importing {}", result.output(), failure);
      warnings.add("The " + plan.command().command() + " output could not be imported: " + failure);
    }
  }

  private static void recordGraphQueryFailure(
      SessionDatabase database,
      List<String> warnings,
      AuxiliaryQueryPlanner.Plan plan,
      Path localOutput,
      String error,
      GraphTargetScope targetScope,
      String targetScopeDetail,
      GraphFailureRecorder recorder) {
    try {
      recorder.run(
          database.writerConnection(),
          localOutput,
          plan.argv(),
          error,
          targetScope,
          targetScopeDetail);
    } catch (SQLException | RuntimeException recordingFailure) {
      warnings.add(
          "The "
              + plan.command().command()
              + " failure could not be recorded for the UI: "
              + recordingFailure);
    }
  }

  @FunctionalInterface
  private interface GraphImport {
    boolean run(
        Connection connection,
        Path file,
        List<String> argv,
        GraphTargetScope targetScope,
        String targetScopeDetail)
        throws SQLException;
  }

  @FunctionalInterface
  private interface GraphFailureRecorder {
    void run(
        Connection connection,
        Path file,
        List<String> argv,
        String error,
        GraphTargetScope targetScope,
        String targetScopeDetail)
        throws SQLException;
  }

  private SessionState finalizeSession(
      ManagedSession session,
      SessionState terminal,
      CaptureSummary summary,
      ProcessOutcome outcome,
      List<String> warnings) {
    try {
      SessionState current = session.state();
      if (!current.isTerminal()) {
        updateManifestCounts(session, summary, outcome);
        finalizeReachable(session, current, terminal, warnings);
      }
      return session.state();
    } catch (IOException | RuntimeException failure) {
      log.error("could not finalize the capture session at {}", session.root(), failure);
      // Appended and persisted in the same breath. Added to the list
      // alone it would arrive after the loop that writes warnings to the
      // manifest and never reach disk.
      warnings.add("the session could not be finalized cleanly: " + failure);
      try {
        session.addWarning("the session could not be finalized cleanly: " + failure);
      } catch (IOException | RuntimeException alsoFailed) {
        log.error("could not even record the finalization failure", alsoFailed);
      }
      return session.state();
    } finally {
      try {
        session.close();
      } catch (IOException releaseFailure) {
        log.warn("could not release the session lock", releaseFailure);
      }
    }
  }

  /**
   * Finalizes as {@code wanted}, falling back to a state the session can actually reach.
   *
   * <p>The state machine is deliberately strict and refuses an impossible transition rather than
   * guessing — which is right, and means the caller must not ask for one. A capture that failed
   * after it started cannot be {@code FAILED_TO_START}, because it did start.
   *
   * <p>Reachability is asked of the state machine by attempting the transition, not re-derived
   * here: {@code finalizeSession} walks a multi-step path, so a single-step check would reject
   * legal endings such as PREFLIGHT to READY and quietly downgrade a good session.
   */
  private static void finalizeReachable(
      ManagedSession session, SessionState current, SessionState wanted, List<String> warnings)
      throws IOException {
    for (String warning : warnings) {
      session.addWarning(warning);
    }
    try {
      session.finalizeSession(wanted);
      return;
    } catch (IllegalStateException unreachable) {
      log.warn(
          "cannot finalize a session in {} as {}; recording INCOMPLETE instead", current, wanted);
    }
    String note =
        "the capture ended as "
            + wanted
            + ", which a session already in "
            + current
            + " cannot record; it is marked INCOMPLETE instead";
    warnings.add(note);
    session.addWarning(note);
    session.finalizeSession(SessionState.INCOMPLETE);
  }

  /** Whether the console logs are a complete record of what the build printed. */
  private Completeness consoleCompleteness(ProcessOutcome outcome) {
    if (consoleWriteFailed) {
      // Bytes the build printed did not reach the log. Saying COMPLETE
      // would claim a console record the session does not have.
      return Completeness.CORRUPT_PARTIAL;
    }
    if (outcome == null) {
      return Completeness.UNKNOWN;
    }
    // A force-killed client stops producing output at an arbitrary point,
    // so what was captured is a prefix, not the whole of what the build
    // would have printed.
    return outcome.terminatedBy().filter(CancellationMode.FORCE_KILL::equals).isPresent()
        ? Completeness.TRUNCATED
        : Completeness.COMPLETE;
  }

  private boolean consoleWriteFailed;

  private void updateManifestCounts(
      ManagedSession session, CaptureSummary summary, ProcessOutcome outcome) throws IOException {
    session.updateManifest(
        builder -> {
          if (summary != null) {
            builder.eventCount(OptionalLong.of(summary.normalized()));
            // A source that never delivered anything is not a COMPLETE
            // source: it is one this session knows nothing about. UNKNOWN
            // is the honest record, and the note says which case it was.
            Completeness besCompleteness =
                !summary.capturedAnything()
                    ? Completeness.UNKNOWN
                    : summary.isComplete() ? Completeness.COMPLETE : Completeness.TRUNCATED;
            builder.addSource(
                SessionManifest.CaptureSourceEntry.of(
                    "BES_STREAM",
                    Optional.empty(),
                    Optional.empty(),
                    OptionalLong.of(summary.bytesJournaled()),
                    besCompleteness,
                    Optional.of(
                        summary.capturedAnything()
                            ? summary.streams().size()
                                + " stream(s), "
                                + summary.received()
                                + " event(s) received"
                            : "no stream was ever opened")));
          }
          fallbackSource.ifPresent(
              fallback ->
                  builder.addSource(
                      SessionManifest.CaptureSourceEntry.of(
                          "BEP_BINARY",
                          Optional.of(fallback.file().getFileName().toString()),
                          Optional.empty(),
                          sizeOf(fallback.file()),
                          switch (fallback.outcome()) {
                            case COMPLETE -> Completeness.COMPLETE;
                            case TRUNCATED -> Completeness.TRUNCATED;
                            default -> Completeness.CORRUPT_PARTIAL;
                          },
                          Optional.of(
                              fallback.events()
                                  + " event(s) read from the local"
                                  + " build event file"))));
          builder.addSource(
              SessionManifest.CaptureSourceEntry.of(
                  "STDOUT",
                  Optional.of(ManagedSessionLayout.STDOUT_LOG_FILE_NAME),
                  Optional.empty(),
                  sizeOf(layoutOf(session).stdoutLog()),
                  consoleCompleteness(outcome),
                  request.isRemote()
                      ? Optional.of(
                          "forced-TTY SSH output; stdout and stderr are merged in" + " this file")
                      : Optional.empty()));
          builder.addSource(
              SessionManifest.CaptureSourceEntry.of(
                  "STDERR",
                  Optional.of(ManagedSessionLayout.STDERR_LOG_FILE_NAME),
                  Optional.empty(),
                  sizeOf(layoutOf(session).stderrLog()),
                  request.isRemote() ? Completeness.UNKNOWN : consoleCompleteness(outcome),
                  request.isRemote()
                      ? Optional.of(
                          "not separately available: forced-TTY SSH merged stderr"
                              + " into stdout.log")
                      : Optional.empty()));
          return builder;
        });
  }

  private void writeManifest(ManagedSession session, Preflight ready, InstrumentationPlan plan)
      throws IOException {
    ManifestExecutionPaths paths = manifestExecutionPaths(request, ready);
    session.updateManifest(
        builder ->
            builder
                .workingDirectory(Optional.of(paths.workingDirectory()))
                .workspaceRoot(paths.workspaceRoot())
                .executionLocation(
                    Optional.of(
                        request
                            .sshTarget()
                            .<SessionManifest.ExecutionLocation>map(
                                target ->
                                    SessionManifest.ExecutionLocation.ssh(
                                        target.displayName(), target.destination(), target.port()))
                            .orElseGet(SessionManifest.ExecutionLocation::local)))
                .bazelExecutable(Optional.of(ready.executable().resolved().toString()))
                .bazelVersion(ready.executable().effectiveVersion())
                .originalCommand(Optional.of(plan.original().toArgv()))
                .effectiveCommand(Optional.of(plan.effective().toArgv()))
                .capturePreset(Optional.of(request.preset().name()))
                .injectedFlags(Optional.of(plan.injectedArgv()))
                .auxiliaryCommands(
                    Optional.of(
                        plan.auxiliaryCommands().stream()
                            .map(
                                command ->
                                    new SessionManifest.AuxiliaryCommand(
                                        command.label(), command.argv()))
                            .toList()))
                .environmentCapturePolicy(Optional.of(request.inheritance().name()))
                .schemaVersion(OptionalInt.of(MigrationRunner.LATEST_VERSION))
                // A command line names absolute paths by construction, and an
                // environment override carries whatever the user set. Both are
                // stated rather than assumed absent, so the sensitivity warning
                // is honest before anyone reads the session (plan 22.2).
                .containsAbsolutePaths(Optional.of(true))
                .containsEnvironmentValues(Optional.of(!request.environmentOverrides().isEmpty()))
                .warnings(sensitivityWarnings(plan)));
  }

  /** Chooses provenance paths without normalizing a remote Linux path on the desktop. */
  static ManifestExecutionPaths manifestExecutionPaths(CaptureRequest request, Preflight ready) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(ready, "ready");
    return ready
        .remote()
        .map(
            remote -> new ManifestExecutionPaths(remote.workingDirectory(), remote.workspaceRoot()))
        .orElseGet(
            () ->
                new ManifestExecutionPaths(
                    request.workingDirectory(),
                    ready.workspace().workspaceRoot().map(Path::toString)));
  }

  record ManifestExecutionPaths(String workingDirectory, Optional<String> workspaceRoot) {
    ManifestExecutionPaths {
      workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
      workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    }
  }

  private static ManagedSessionLayout layoutOf(ManagedSession session) {
    return session.layout();
  }

  /**
   * Warnings about what the recorded command line may contain.
   *
   * <p>The manifest stores the effective command verbatim, which is what makes a session
   * reproducible and is also how a credential passed on a command line ends up on disk. {@code
   * containsEnvironmentValues} does not cover it — that field is about the environment — so the
   * fact is said plainly here, before anyone shares the session (plan 22.2).
   *
   * <p>A name-shaped heuristic, deliberately: the full redaction machinery is a later phase, and a
   * warning that occasionally fires without cause is a far better failure than silence about a
   * leaked token.
   */
  private static List<String> sensitivityWarnings(InstrumentationPlan plan) {
    List<String> warnings = new ArrayList<>();
    for (String argument : plan.effective().toArgv()) {
      String lower = argument.toLowerCase(Locale.ROOT);
      if (SECRET_NAME_HINTS.stream().anyMatch(lower::contains)) {
        warnings.add(
            "this session records a command line containing an argument whose"
                + " name suggests a credential; review it before sharing the session");
        break;
      }
    }
    return warnings;
  }

  /** Default secret-name patterns (plan 22.2), to be user-editable in a later phase. */
  private static final List<String> SECRET_NAME_HINTS =
      List.of(
          "token",
          "password",
          "passwd",
          "secret",
          "credential",
          "api_key",
          "apikey",
          "auth",
          "_key=");

  /** The file's size, or unknown when it cannot be read. Never zero as a guess. */
  private static OptionalLong sizeOf(Path file) {
    try {
      return OptionalLong.of(Files.size(file));
    } catch (IOException unreadable) {
      return OptionalLong.empty();
    }
  }

  private void recordOutcome(
      EventWriter events, ProcessOutcome outcome, CaptureSummary summary, List<String> warnings) {
    if (events == null) {
      return;
    }
    try {
      if (outcome != null && outcome.wasCancelled()) {
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.WARNING,
                CaptureDiagnosticCodes.CAPTURE_CANCELLED,
                "the build was stopped with "
                    + outcome.terminatedBy().orElseThrow()
                    + " after "
                    + outcome.duration().toSeconds()
                    + "s",
                nowMicros()));
      } else if (outcome != null
          && outcome.exitCode().orElse(0) == CaptureResult.BES_TRANSPORT_FAILURE_EXIT) {
        // Bazel reports 38 when the event-stream upload failed, whatever
        // the build itself did. Recorded as a capture problem, not as a
        // failed build: blaming the user's build for our transport would
        // be exactly backwards.
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.ERROR,
                CaptureDiagnosticCodes.STREAM_FAILED,
                "bazel exited 38: the build event upload failed. The build's own outcome"
                    + " cannot be read from the exit code and must come from the event"
                    + " stream.",
                nowMicros()));
        warnings.add(
            "bazel exited 38 (build event upload failed); the build's own result"
                + " is not knowable from its exit code");
      } else if (outcome != null && !outcome.isSuccess()) {
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.INFO,
                CaptureDiagnosticCodes.BUILD_FAILED,
                "bazel exited with "
                    + outcome.exitCode().stream()
                        .mapToObj(Integer::toString)
                        .findFirst()
                        .orElse("no exit code")
                    + "; this is a build outcome, not a capture problem",
                nowMicros()));
      }
      if (summary != null && summary.lagged()) {
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.INFO,
                CaptureDiagnosticCodes.CAPTURE_LAG,
                "the capture applied backpressure at least once; the build waited for this"
                    + " application rather than events being dropped",
                nowMicros()));
      }
      events.flush();
    } catch (SQLException failure) {
      warnings.add("could not record the build outcome: " + failure);
    }
  }

  private CaptureSummary finishQuietly(
      LiveCapturePipeline pipeline, CaptureSummary already, List<String> warnings) {
    if (pipeline == null) {
      return already;
    }
    try {
      // Already drained on the happy path; closing is still owed, and
      // returning early used to skip it.
      return already != null ? already : pipeline.finish();
    } catch (IOException | SQLException failure) {
      warnings.add("the capture pipeline could not be drained: " + failure);
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      warnings.add("draining the capture pipeline was interrupted");
      return null;
    } catch (RuntimeException unexpected) {
      // Caught deliberately. Everything after this call in the caller's
      // finally -- closing the journal, recording the outcome, closing
      // the database, finalizing the session and releasing its lock --
      // is what makes a failed capture inspectable, and an unchecked
      // throw here used to skip all of it.
      log.error("draining the capture pipeline failed", unexpected);
      warnings.add("the capture pipeline could not be drained: " + unexpected);
      return null;
    } finally {
      pipeline.close();
    }
  }

  private static void closeJournalQuietly(JournalWriter journal, List<String> warnings) {
    if (journal == null) {
      return;
    }
    try {
      journal.close();
    } catch (IOException | RuntimeException failure) {
      warnings.add("the raw journal could not be closed cleanly: " + failure);
    }
  }

  /**
   * Records what normalization could not reconcile.
   *
   * <p>The same two facts the import path reports, for the same reason: a duplicate primary output
   * means one of two actions is missing from the table, and a file set referenced but never defined
   * means every byte total reached through it is a lower bound. Both look exactly like a smaller
   * build, so neither is visible any other way.
   */
  private static void reportNormalizationAnomalies(
      EntityWriter entities,
      EventWriter events,
      LiveCapturePipeline pipeline,
      List<String> warnings) {
    if (entities == null || events == null || pipeline == null) {
      return;
    }
    try {
      long conflicts = entities.conflictingActions();
      if (conflicts > 0) {
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.WARNING,
                DiagnosticCodes.DUPLICATE_ACTION_OUTPUT,
                conflicts
                    + " action event(s) repeated a primary output already recorded;"
                    + " the first row for each was kept. The path is measured unique"
                    + " across a stream on every supported Bazel version, so this"
                    + " means one of each pair is not in the actions table.",
                System.currentTimeMillis() * 1_000L));
        warnings.add(conflicts + " action(s) repeated a primary output already recorded");
      }
      for (long streamId : pipeline.normalizedStreamIds()) {
        long undefined = entities.undefinedDepsets(streamId);
        if (undefined == 0) {
          continue;
        }
        events.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.WARNING,
                DiagnosticCodes.UNDEFINED_FILE_SET,
                undefined
                    + " named set(s) of files were referenced and never defined, so"
                    + " any byte total reached through them is a lower bound rather"
                    + " than a total.",
                System.currentTimeMillis() * 1_000L));
        warnings.add(undefined + " file set(s) were referenced and never defined");
      }
    } catch (SQLException failure) {
      log.warn("could not check the session for normalization anomalies", failure);
    }
  }

  /**
   * Creates the post-load indexes and refreshes the planner's statistics.
   *
   * <p>Quiet by design: a session whose indexes were never built still holds every row and answers
   * every query, just more slowly. Failing the capture over it would discard a complete session to
   * protect its speed.
   */
  private static void finalizeIndexesQuietly(
      EntityWriter entities, EventWriter events, List<String> warnings) {
    if (events == null) {
      return;
    }
    try {
      if (entities != null) {
        entities.flush();
      }
      events.finalizeIngest();
    } catch (SQLException failure) {
      log.warn("could not build the session's indexes", failure);
      warnings.add(
          "the session's indexes could not be built; it is complete but its"
              + " views will be slower to query");
    }
  }

  private static void closeQuietly(AutoCloseable resource, String what, List<String> warnings) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception failure) {
      warnings.add("the " + what + " could not be closed cleanly: " + failure);
    }
  }

  /**
   * Startup options to use when probing.
   *
   * <p>Empty for now, and deliberately so: the probe runs outside the workspace in batch mode, and
   * adding the user's startup options there would pin a different server configuration than the one
   * the flag table describes. The parameter exists because plan 7.1 makes startup context part of
   * the cache key, and a later phase that lets the user set startup options will need to thread
   * them here.
   */
  private static List<String> startupArgsOf(BazelExecutable executable, WorkspaceInfo workspace) {
    return List.of();
  }

  private static String describeBlockers(InstrumentationPlan plan) {
    List<String> reasons = new ArrayList<>(plan.errors());
    plan.mandatoryConflicts().forEach(conflict -> reasons.add(conflict.summary()));
    return reasons.isEmpty() ? "no command to run" : String.join("; ", reasons);
  }

  private long nowMicros() {
    return clock.instant().getEpochSecond() * 1_000_000L + clock.instant().getNano() / 1_000L;
  }

  private static long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }
}
