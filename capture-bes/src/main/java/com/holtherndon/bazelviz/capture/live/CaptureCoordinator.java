package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.capture.bes.BesServer;
import com.holtherndon.bazelviz.capture.bes.BesServerConfig;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParseOutcome;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParseResult;
import com.holtherndon.bazelviz.capture.file.binary.BinaryBepParser;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
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
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.proc.BazelLauncher;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.LaunchRequest;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceDetector;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.DiagnosticSeverity;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.ImportDiagnostic;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.storage.schema.SchemaV1;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.enrich.execlog.EnvironmentRedactor;
import com.holtherndon.bazelviz.enrich.execlog.ExecutionLogImporter;
import com.holtherndon.bazelviz.enrich.profile.ProfileImporter;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.enrich.graph.ActionGraphImporter;
import com.holtherndon.bazelviz.enrich.graph.AuxiliaryQueryRunner;
import com.holtherndon.bazelviz.enrich.graph.ConfiguredTargetImporter;
import com.holtherndon.bazelviz.storage.graph.ActionEdgeDeriver;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import java.io.IOException;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one instrumented Bazel build into a managed session (plan 7.1
 * {@code CaptureCoordinator}).
 *
 * <h2>Two phases, because the user decides in between</h2>
 *
 * <p>{@link #preflight()} resolves the executable, finds the workspace, probes
 * capabilities, starts the embedded BES server and builds the plan. It creates
 * no session and runs no build, so the dialog can show the plan, take a veto or
 * a conflict resolution, re-plan, and be abandoned at no cost beyond a socket.
 *
 * <p>{@link #run} then creates the session, attaches the pipeline, launches
 * Bazel and finalizes. The two are separate because ADR-007 requires the user
 * to see the effective command before it runs, and a plan built without the
 * real port would not be the command that runs.
 *
 * <h2>Order of construction, and why</h2>
 *
 * <p>The session and its journal exist before the process starts, and the
 * session moves to {@code CAPTURING} before {@code ProcessBuilder.start()}.
 * Bazel connects to the BES endpoint almost immediately, so a session created
 * after the launch would have to hold the first events somewhere else — and
 * "somewhere else" is where events get lost. Finalization runs in a
 * {@code finally}: a build that dies, is cancelled, or fails to start still
 * leaves an inspectable session, which is a Phase 2 exit criterion.
 */
public final class CaptureCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CaptureCoordinator.class);

    private final CaptureRequest request;
    private final SessionManager sessions;
    private final BazelCapabilityDetector detector;
    private final Clock clock;

    private final SettableRawEventSink sink = new SettableRawEventSink();
    private final AtomicReference<BazelLauncher.BazelProcess> running = new AtomicReference<>();

    /**
     * A stop requested before there was anything to stop.
     *
     * <p>Cancellation used to read {@code running} and return silently when it
     * was null — which is the state for the whole of preflight and for the
     * session setup that follows it. A user pressing Ctrl-C during the
     * capability probe was told "asking Bazel to stop", and then the build they
     * had just cancelled was launched. The request is now remembered, and the
     * launch path asks before starting anything.
     */
    private final AtomicReference<CancellationMode> pendingCancel = new AtomicReference<>();

    /**
     * Whether the one escalation ladder has been started.
     *
     * <p>{@link #cancel} used to start a fresh daemon thread on every call, each
     * running a full escalation ladder against the same process and each timing
     * its own grace period. A user who clicked Cancel, then Terminate, then
     * Force Kill inside the thirty-second Cancel grace — which is exactly what a
     * user does when the first click appears to do nothing — had three ladders
     * racing, sending rungs in whatever order they woke up in.
     *
     * <p>Now the first request runs the ladder and every later one only delivers
     * its harsher rung, immediately, into the same process. The ladder picks the
     * new rung up because {@link BazelLauncher.BazelProcess#cancel} never re-sends
     * a rung and always continues from the harshest one already delivered.
     */
    private final AtomicBoolean escalating = new AtomicBoolean();

    private BesServer server;
    /** Where a captured action graph is written. */
    private static final String AQUERY_FILE = "aquery.proto";

    /** Where a captured configured-target graph is written. */
    private static final String CQUERY_FILE = "cquery.proto";

    private Preflight preflight;
    private boolean closed;

    public CaptureCoordinator(CaptureRequest request) {
        this(request,
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
     * <p>The BES server is started here rather than at launch, because the plan
     * has to contain the real endpoint: showing a placeholder and substituting
     * a port afterwards would mean the command the user approved is not the
     * command that runs, which is the one thing ADR-007 exists to prevent.
     */
    /**
     * The request's environment overrides that set a value, as a plain map.
     *
     * <p>The unsets are dropped: they matter to the build and not to
     * identifying the binary, and {@code Subprocess} has no way to express
     * "remove this variable" anyway.
     */
    private Map<String, String> setVariables() {
        Map<String, String> set = new java.util.LinkedHashMap<>();
        request.environmentOverrides().forEach((name, value) ->
                value.ifPresent(present -> set.put(name, present)));
        return set;
    }

    public synchronized Preflight preflight() throws IOException {
        if (preflight != null) {
            return preflight;
        }
        WorkspaceInfo workspace = WorkspaceDetector.detect(request.workingDirectory());
        // Resolved under the environment the build will run with, so that
        // bazelisk's USE_BAZEL_VERSION picks the same Bazel here as it will
        // there. Without this the detector probes one version and the build
        // runs another, and the planner injects flags the build rejects.
        BazelExecutable executable = BazelExecutableResolver.resolve(
                request.executable(), workspace.workspaceRoot(), setVariables());
        var capabilities = detector.detect(executable, startupArgsOf(executable, workspace));

        server = new BesServer(sink, BesServerConfig.defaults()
                .withMaxMessageBytes(request.options().maxMessageBytes()));
        BesEndpoint endpoint = server.start();

        BazelCommand original = new CommandLineParser(Optional.of(capabilities))
                .parse(executable.resolved(), request.workingDirectory(), request.args())
                .toBuilder()
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
        PlanRequest planRequest = PlanRequest.initial(
                        original, capabilities, request.preset(), provisionalRaw,
                        Optional.of(endpoint.besBackendUri()))
                .withEffectiveOptions(EffectiveOptions.resolve(
                        executable.resolved(), request.workingDirectory(), original));

        preflight = new Preflight(executable, workspace, capabilities, endpoint,
                new InstrumentationPlanner().plan(planRequest), planRequest);
        return preflight;
    }

    /**
     * Re-plans with the user's answers, keeping the same endpoint and probe.
     *
     * <p>Adjusts the request that produced the current plan rather than
     * building a fresh one, so answers accumulate: a user who resolves a
     * conflict and then vetoes a flag still has both.
     */
    public synchronized Preflight replan(java.util.function.UnaryOperator<PlanRequest> adjust)
            throws IOException {
        Preflight current = preflight();
        PlanRequest adjusted = adjust.apply(current.request());
        preflight = new Preflight(
                current.executable(), current.workspace(), current.capabilities(),
                current.endpoint(), new InstrumentationPlanner().plan(adjusted), adjusted);
        return preflight;
    }

    // ------------------------------------------------------------------- run

    /**
     * Creates the session, launches the build, and finalizes whatever happened.
     *
     * @throws IllegalStateException when the plan cannot be launched; callers
     *     must resolve mandatory conflicts first, and the plan says which
     */
    public CaptureResult run() throws IOException {
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

        try {
            session.transitionTo(SessionState.PREFLIGHT);

            // The plan named a provisional raw directory during preflight, when
            // no session existed. Re-plan against the real one -- from the same
            // request, with only that field changed, so every answer the user
            // gave survives.
            InstrumentationPlan plan = new InstrumentationPlanner()
                    .plan(ready.request().inSession(layout.rawDirectory()));
            executedPlan = plan;

            writeManifest(session, ready, plan);
            InstrumentationPlanCodec.write(layout.instrumentationPlanFile(), plan, ready);

            database = SessionDatabase.open(layout.databaseFile());
            new MigrationRunner(MigrationRunner.standard().migrations()).migrate(database);
            events = new EventWriter(database.writerConnection(), request.options().batchSize(),
                    com.holtherndon.bazelviz.storage.events.StringDictionary.DEFAULT_CACHE_ENTRIES);
            entities = new EntityWriter(database.writerConnection());
            streams = new StreamRegistry(database.writerConnection());

            journal = JournalWriter.create(
                    layout.rawDirectory(), sessionId.value(),
                    JournalWriterConfig.defaults()
                            .withMaxPayloadBytes(request.options().maxMessageBytes()));
            pipeline = new LiveCapturePipeline(
                    journal, events, entities, streams,
                    new EventNormalizer(request.options().maxMessageBytes()),
                    new ImportCheckpointStore(layout.checkpointsDirectory()),
                    request.options(), request.progress(), clock);
            pipeline.start();
            sink.attach(pipeline);

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
                events.recordDiagnostic(ImportDiagnostic.general(
                        DiagnosticSeverity.WARNING,
                        CaptureDiagnosticCodes.CAPTURE_CANCELLED,
                        "cancelled during preparation, before Bazel was started",
                        nowMicros()));
                outcome = ProcessOutcome.cancelled(
                        OptionalInt.empty(), requestedBeforeLaunch, Duration.ZERO);
            } else {
                BazelLauncher.BazelProcess process = BazelLauncher.start(
                        LaunchRequest.of(plan.effective(), console));
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
                awaitStreamsToSettle(outcome);
            }

            // The keep-your-own-backend resolution (plan 8.5 option 2) told
            // Bazel to write a local copy of the stream. Reading it is the
            // whole point of offering that choice, and the dialog says so:
            // "This application reads a local copy instead."
            ingestFallbackFile(plan, pipeline, warnings);

            summary = pipeline.finish();
            terminal = terminalStateFor(outcome, summary, warnings);
        } catch (SQLException failure) {
            warnings.add("the session database could not be prepared: " + failure);
            throw new IOException("cannot prepare the capture session at " + sessionRoot, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warnings.add("the capture was interrupted");
            terminal = SessionState.INCOMPLETE;
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
                warnings.add("some of the build's console output could not be written to the"
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
            queryGraphsQuietly(database, layout, warnings);
            closeQuietly(entities, "entity writer", warnings);
            closeQuietly(events, "event writer", warnings);
            closeQuietly(streams, "stream registry", warnings);
            closeQuietly(database, "session database", warnings);
            terminal = finalizeSession(session, terminal, summary, outcome, warnings);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        return new CaptureResult(
                sessionRoot,
                sessionId,
                terminal,
                executedPlan,
                Optional.ofNullable(outcome),
                Optional.ofNullable(summary),
                warnings);
    }

    /**
     * Stops the running build, if there is one.
     *
     * <p>Safe to call from any thread, including the EDT: it signals a process
     * and does not wait for the capture to finalize. {@link #run} returns a
     * result describing a cancelled session once the drain completes.
     */
    public void cancel(CancellationMode mode) {
        Objects.requireNonNull(mode, "mode");
        // Remembered first, and unconditionally. Whether or not a process
        // exists yet, the user has asked to stop, and that fact must outlive
        // this call.
        pendingCancel.accumulateAndGet(mode, CaptureCoordinator::harsherOf);
        applyPendingCancel();
    }

    /**
     * Delivers whatever stop has been asked for, on a thread of its own.
     *
     * <p>The first caller to get here runs the escalation ladder; every later
     * one delivers its rung and returns. That division is what makes the
     * buttons responsive and the ladder single: a Terminate clicked two seconds
     * into the Cancel grace sends {@code SIGTERM} at once rather than queueing
     * behind twenty-eight seconds of waiting, and it does so without a second
     * ladder timing its own grace periods against the same client.
     *
     * <p>The ladder is asked to escalate — see
     * {@link BazelLauncher.BazelProcess#cancel} — because there is no guarantee
     * of a second click. The CLI has one Ctrl-C and the window has none at all
     * once it is closing, and a client that outlives its cancellation holds the
     * workspace's command lock against every later Bazel command. Escalation is
     * therefore the promise, and {@link #reportEscalation} is the part that
     * keeps it from being a silent one.
     *
     * <p>Called with no process only from {@link #cancel}, where the request is
     * already remembered in {@code pendingCancel} and the launch path asks
     * again the moment there is something to signal.
     */
    private void applyPendingCancel() {
        BazelLauncher.BazelProcess process = running.get();
        if (process == null) {
            return;
        }
        boolean runsTheLadder = escalating.compareAndSet(false, true);
        Thread stopper = new Thread(() -> {
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
        }, "bbv-capture-cancel");
        stopper.setDaemon(true);
        stopper.start();
    }

    /** True while a build is running. */
    /**
     * Set once the session directory exists, read from the UI thread. Volatile
     * rather than synchronized because it is written once and read often.
     */
    private volatile Path liveSessionRoot;

    /**
     * The session directory, once there is one.
     *
     * <p>Published as soon as it is created rather than when the capture ends,
     * so a view can open the session read-only and watch it fill. The
     * directory, its manifest and its database all exist before Bazel is
     * launched; what is inside them grows for the life of the build.
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
     * <p>Escalation is the promise that the client dies and the workspace lock
     * is released, and it is worth keeping. It is also an override of a choice
     * the user made — Cancel keeps the event stream, Force Kill costs it — and
     * rule 12 does not permit an override to be silent. The session records
     * which rung it actually took, so "why is my stream incomplete when I
     * pressed Cancel?" has an answer written down next to the session.
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
        warnings.add("the build did not stop when it was asked to, so the stop was escalated from "
                + asked + " to " + applied + "; a Bazel client that is still running holds this"
                + " workspace's command lock, and every later Bazel command in it — 'clean'"
                + " included — waits for that lock");
    }

    /**
     * Force-stops a client that is somehow still alive at finalization.
     *
     * <p>The backstop for the one path the escalation ladder does not cover:
     * {@link BazelLauncher.BazelProcess#await()} is an untimed
     * {@code waitFor}, and the only way out of it other than the process
     * exiting is an interrupt — which unwinds straight to the {@code finally}
     * with the client untouched and the last reference to it about to be
     * dropped. That leaves a Bazel client nobody is watching, holding the
     * workspace's command lock until the user finds the pid themselves — and
     * every Bazel command in that workspace, {@code clean} first among them,
     * waiting on it in the meantime.
     *
     * <p>Nothing is force-killed on a normal ending, because there is nothing
     * left alive to kill: {@code await()} returns when the process exits.
     */
    private void reapIfStillRunning(BazelLauncher.BazelProcess process, List<String> warnings) {
        if (process == null || !process.isAlive()) {
            return;
        }
        log.warn("the capture is finalizing while the Bazel client {} is still running;"
                + " force-stopping it so it does not hold the workspace lock", process.pid());
        warnings.add("the Bazel client was still running when the capture ended, so it was"
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
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /**
     * Reads the local BEP file the plan asked Bazel to write, if there is one.
     *
     * <p>Runs after the process has exited and before the pipeline is drained,
     * on this thread, which is the only writer at that point. A file that was
     * planned and never appeared is a warning rather than a failure: Bazel may
     * have died before creating it, and that is a fact about the build, not a
     * fault in the capture.
     */
    private void ingestFallbackFile(
            InstrumentationPlan plan, LiveCapturePipeline pipeline, List<String> warnings) {
        // Read from the effective command rather than from the plan's added
        // flags, so both branches are covered by one rule: the file Bazel was
        // told to write is the file to read, whether this application named it
        // or the user did.
        Optional<Path> fallback = localBepFileOf(plan);
        if (fallback.isEmpty()) {
            return;
        }
        Path file = fallback.get();
        if (!Files.isRegularFile(file)) {
            warnings.add("the build was asked to write " + file
                    + " and did not, so no events were captured from it");
            return;
        }
        try {
            long[] ordinal = {0};
            BinaryBepParseResult result = BinaryBepParser.withDefaults().parseFile(
                    file, 0, frame -> {
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
                warnings.add(file.getFileName() + " was " + result.outcome()
                        + "; " + ordinal[0] + " event(s) were read before the damage");
            }
        } catch (IOException | SQLException | RuntimeException failure) {
            warnings.add("could not read the local build event file " + file + ": " + failure);
            log.warn("could not read the local build event file {}", file, failure);
        }
    }

    /**
     * The local build event file the effective command writes, if any.
     *
     * <p>Only consulted on the keep-your-backend path. On the ordinary path
     * events arrive live through the embedded server and no file is written, so
     * an empty answer here is the normal case, not a failure.
     */
    private static Optional<Path> localBepFileOf(InstrumentationPlan plan) {
        if (plan.sourceAvailability().entry(com.holtherndon.bazelviz.core.source.DataSource.BES_ENVELOPE)
                .availability() == com.holtherndon.bazelviz.runner.plan.SourceAvailability.Availability.PLANNED) {
            return Optional.empty();
        }
        Path found = null;
        for (String token : plan.effective().commandArgs()) {
            Optional<String> name = CommandLineParser.flagName(token);
            if (name.isPresent() && name.get().equals("build_event_binary_file")) {
                // Last one wins, exactly as Bazel resolves it.
                found = CommandLineParser.attachedValue(token).map(Path::of).orElse(found);
            }
        }
        return Optional.ofNullable(found).map(Path::toAbsolutePath);
    }

    /** A locally-captured BEP file that was read into this session. */
    private record FallbackSource(Path file, long events, BinaryBepParseOutcome outcome) {}

    private Optional<FallbackSource> fallbackSource = Optional.empty();

    // ------------------------------------------------------------- internals

    /**
     * Waits for the BES streams to close after the client has exited.
     *
     * <p>The client exiting is not the end of the event stream. The Bazel
     * <em>server</em> is a separate, longer-lived process that publishes the
     * events, and after a force-kill it carries on for about two and a half
     * seconds — running actions, then cancelling the build itself, then
     * finishing the stream. Finishing the pipeline at the moment the client
     * died would refuse those last events, and the session would record a
     * stream that aborted when in fact it completed.
     *
     * <p>Bounded, because a stream that never closes must not hang the
     * application at exactly the moment the user is trying to look at what was
     * captured. Whatever arrived is already journaled either way.
     */
    private void awaitStreamsToSettle(ProcessOutcome outcome) throws InterruptedException {
        if (server == null) {
            return;
        }
        // Longer after a force-kill, because that is the case where the server
        // is known to still be working. A clean exit means Bazel already
        // finished its upload, so the wait normally returns at once.
        Duration budget = outcome != null
                        && outcome.terminatedBy().filter(CancellationMode.FORCE_KILL::equals).isPresent()
                ? Duration.ofSeconds(15)
                : Duration.ofSeconds(5);
        long deadline = System.nanoTime() + budget.toNanos();
        while (server.openStreamCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        if (server.openStreamCount() > 0) {
            log.info("{} BES stream(s) were still open {} after the build exited; finalizing anyway",
                    server.openStreamCount(), budget);
        }
    }

    /**
     * The terminal state that honestly describes what happened.
     *
     * <p>The build's success and the capture's completeness are different
     * questions, and the state answers the capture's. A failed build with a
     * complete stream is {@code READY}: it is a good session about a bad build,
     * which is the most useful thing this tool produces.
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
     * Runs the post-build enrichment imports, if the plan asked for their
     * files.
     *
     * <h2>Quietly, and that is the point</h2>
     *
     * <p>Plan 21.4: each enrichment task is independent and a failure must not
     * invalidate the BEP. By the time this runs the build events are written,
     * normalized and indexed, and nothing here can undo that — the importers
     * write only to the tables schema v4 added, in their own transactions, and
     * record their own failures in {@code enrichment_tasks}.
     *
     * <p>So a missing or corrupt execution log costs the user the execution
     * log and nothing else. It does not fail the capture, does not change the
     * session's terminal state, and adds a warning only when a file the plan
     * promised is not there — which is worth saying, because the user asked
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
            Path file = written.get();
            try {
                switch (flag.enables()) {
                    case EXECUTION_LOG -> importExecutionLog(database, file, warnings);
                    case PROFILE -> importProfile(database, file, warnings);
                    default -> {
                        // BEP files are the capture path's own business.
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                // The task row already records this; the warning is for the
                // capture summary, which is read before anyone opens the
                // coverage panel.
                log.warn("enrichment from {} failed", file, failure);
                warnings.add("could not read " + file.getFileName() + ": " + failure);
            }
        }
    }

    private void importExecutionLog(SessionDatabase database, Path file, List<String> warnings)
            throws SQLException {
        if (!Files.exists(file)) {
            warnings.add("Bazel was asked to write an execution log to " + file
                    + " and did not, so nothing is known about where actions ran.");
            return;
        }
        ExecutionLogImporter.Result result =
                new ExecutionLogImporter(database.writerConnection(), new EnvironmentRedactor())
                        .importFrom(file);
        if (result.state() != EnrichmentTask.State.SUCCEEDED) {
            warnings.add("the execution log could not be imported: "
                    + result.error().orElse("unknown reason"));
        }
    }

    private void importProfile(SessionDatabase database, Path file, List<String> warnings)
            throws SQLException {
        if (!Files.exists(file)) {
            warnings.add("Bazel was asked to write a trace profile to " + file
                    + " and did not, so there are no build phases and no critical path.");
            return;
        }
        ProfileImporter.Result result =
                new ProfileImporter(database.writerConnection()).importFrom(file);
        if (result.state() != EnrichmentTask.State.SUCCEEDED) {
            warnings.add("the trace profile could not be imported: "
                    + result.error().orElse("unknown reason"));
        }
    }


    /**
     * Runs the auxiliary queries and imports their graphs.
     *
     * <p>After the build, never during (plan 8.6): a query is an analysis pass
     * in the same Bazel server, so running it alongside would slow the build
     * and change the timings this application exists to report.
     *
     * <p>Quietly, for the same reason the Phase 4 enrichment is: plan 24
     * requires a failed auxiliary query to leave the rest of the session
     * usable, and these write only to tables schema v5 added. A query that will
     * not run costs the user the dependency graph and nothing else.
     */
    private void queryGraphsQuietly(
            SessionDatabase database, ManagedSessionLayout layout, List<String> warnings) {
        if (database == null || preflight == null) {
            return;
        }
        BazelCommand original = preflight.plan().original();
        AuxiliaryQueryPlanner planner =
                new AuxiliaryQueryPlanner(preflight.capabilities());
        AuxiliaryQueryRunner runner = new AuxiliaryQueryRunner();

        runGraphQuery(database, warnings, runner,
                planner.aquery(original, layout.rawDirectory().resolve(AQUERY_FILE)),
                (connection, file, argv) ->
                        new ActionGraphImporter(connection).importFrom(file, argv).succeeded());
        runGraphQuery(database, warnings, runner,
                planner.cquery(original, layout.rawDirectory().resolve(CQUERY_FILE)),
                (connection, file, argv) ->
                        new ConfiguredTargetImporter(connection).importFrom(file, argv)
                                .succeeded());

        buildGraphIndexesQuietly(database, layout, warnings);
    }

    /**
     * Derives the action edges and builds the CSR indexes the graph view reads.
     *
     * <h2>Here, because this is the only place the graphs are ever imported</h2>
     *
     * <p>{@code aquery} and {@code cquery} need a live workspace, so only a
     * capture can import them — a session imported from a BEP file alone has no
     * graph to index. The edge derivation and the index build therefore belong
     * to the same finalization step as the imports whose rows they read.
     * Without this step the imports were a dead end: every real captured
     * session had {@code declared_actions} rows and no index, so
     * {@code GraphQueries.forwardIndex} answered empty and the canvas reported
     * "no action graph" forever.
     *
     * <h2>On the capture worker, never anything interactive</h2>
     *
     * <p>This runs on the thread that ran the build, after the build, alongside
     * the auxiliary queries themselves — which cost a Bazel analysis pass each
     * and dwarf an edge derivation. Nothing on the EDT waits for it; the
     * capture dialog polls the session state asynchronously.
     *
     * <h2>Quietly, like every other enrichment</h2>
     *
     * <p>A derivation or index build that fails costs the user the graph view
     * and nothing else. The failure is logged, a warning names it, and the
     * graph view degrades to its honest "no index" message rather than the
     * capture failing — the raw query output is still on disk either way.
     */
    private void buildGraphIndexesQuietly(
            SessionDatabase database, ManagedSessionLayout layout, List<String> warnings) {
        try {
            java.sql.Connection connection = database.writerConnection();
            ActionEdgeDeriver.Result derived = new ActionEdgeDeriver(connection).deriveAll();
            GraphIndexBuilder builder =
                    new GraphIndexBuilder(connection, layout.indexesDirectory());
            var declared = builder.build(EdgeDerivation.DECLARED);
            var observed = builder.build(EdgeDerivation.OBSERVED);
            var labels = builder.buildConfiguredTargets();
            log.info("derived {} declared and {} observed action edges; indexed {} / {} / {}",
                    derived.declaredEdges(), derived.observedEdges(),
                    declared.map(Object::toString).orElse("no declared graph"),
                    observed.map(Object::toString).orElse("no observed graph"),
                    labels.map(Object::toString).orElse("no configured-target graph"));
        } catch (SQLException | IOException | RuntimeException failure) {
            log.warn("could not build the action graph index", failure);
            warnings.add("The action dependency graph could not be indexed: " + failure
                    + ". The graph view will report the graph as unavailable;"
                    + " everything else in this session is unaffected.");
        }
    }

    private void runGraphQuery(
            SessionDatabase database,
            List<String> warnings,
            AuxiliaryQueryRunner runner,
            AuxiliaryQueryPlanner.Plan plan,
            GraphImport importer) {
        // Plan 8.6 step 10: say when the graph may not match because options
        // could not be reproduced. Said before the query runs, because that is
        // when it is a prediction rather than an excuse.
        plan.mismatchWarning().ifPresent(warnings::add);

        AuxiliaryQueryRunner.Result result = runner.run(plan);
        if (!result.succeeded()) {
            warnings.add("The " + plan.command().command() + " that would have described this"
                    + " build's dependency graph did not run: "
                    + result.error().orElse("unknown reason"));
            return;
        }
        try {
            if (!importer.run(database.writerConnection(), result.output(), plan.argv())) {
                warnings.add("The " + plan.command().command()
                        + " output could not be imported.");
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("importing {}", result.output(), failure);
            warnings.add("The " + plan.command().command() + " output could not be imported: "
                    + failure);
        }
    }

    @FunctionalInterface
    private interface GraphImport {
        boolean run(java.sql.Connection connection, Path file, List<String> argv)
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
     * Finalizes as {@code wanted}, falling back to a state the session can
     * actually reach.
     *
     * <p>The state machine is deliberately strict and refuses an impossible
     * transition rather than guessing — which is right, and means the caller
     * must not ask for one. A capture that failed after it started cannot be
     * {@code FAILED_TO_START}, because it did start.
     *
     * <p>Reachability is asked of the state machine by attempting the
     * transition, not re-derived here: {@code finalizeSession} walks a
     * multi-step path, so a single-step check would reject legal endings such
     * as PREFLIGHT to READY and quietly downgrade a good session.
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
            log.warn("cannot finalize a session in {} as {}; recording INCOMPLETE instead",
                    current, wanted);
        }
        String note = "the capture ended as " + wanted + ", which a session already in " + current
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
        return outcome.terminatedBy()
                        .filter(com.holtherndon.bazelviz.runner.proc.CancellationMode.FORCE_KILL::equals)
                        .isPresent()
                ? Completeness.TRUNCATED
                : Completeness.COMPLETE;
    }

    private boolean consoleWriteFailed;

    private void updateManifestCounts(
            ManagedSession session, CaptureSummary summary, ProcessOutcome outcome) throws IOException {
        session.updateManifest(builder -> {
            if (summary != null) {
                builder.eventCount(OptionalLong.of(summary.normalized()));
                // A source that never delivered anything is not a COMPLETE
                // source: it is one this session knows nothing about. UNKNOWN
                // is the honest record, and the note says which case it was.
                Completeness besCompleteness = !summary.capturedAnything()
                        ? Completeness.UNKNOWN
                        : summary.isComplete() ? Completeness.COMPLETE : Completeness.TRUNCATED;
                builder.addSource(SessionManifest.CaptureSourceEntry.of(
                        "BES_STREAM",
                        Optional.empty(),
                        Optional.empty(),
                        OptionalLong.of(summary.bytesJournaled()),
                        besCompleteness,
                        Optional.of(summary.capturedAnything()
                                ? summary.streams().size() + " stream(s), "
                                        + summary.received() + " event(s) received"
                                : "no stream was ever opened")));
            }
            fallbackSource.ifPresent(fallback -> builder.addSource(
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
                            Optional.of(fallback.events() + " event(s) read from the local"
                                    + " build event file"))));
            builder.addSource(SessionManifest.CaptureSourceEntry.of(
                    "STDOUT",
                    Optional.of(ManagedSessionLayout.STDOUT_LOG_FILE_NAME),
                    Optional.empty(),
                    sizeOf(layoutOf(session).stdoutLog()),
                    consoleCompleteness(outcome),
                    Optional.empty()));
            builder.addSource(SessionManifest.CaptureSourceEntry.of(
                    "STDERR",
                    Optional.of(ManagedSessionLayout.STDERR_LOG_FILE_NAME),
                    Optional.empty(),
                    sizeOf(layoutOf(session).stderrLog()),
                    consoleCompleteness(outcome),
                    Optional.empty()));
            return builder;
        });
    }

    private void writeManifest(ManagedSession session, Preflight ready, InstrumentationPlan plan)
            throws IOException {
        session.updateManifest(builder -> builder
                .workingDirectory(Optional.of(request.workingDirectory().toString()))
                .workspaceRoot(ready.workspace().workspaceRoot().map(Path::toString))
                .bazelExecutable(Optional.of(ready.executable().resolved().toString()))
                .bazelVersion(ready.executable().effectiveVersion())
                .originalCommand(Optional.of(plan.original().toArgv()))
                .effectiveCommand(Optional.of(plan.effective().toArgv()))
                .capturePreset(Optional.of(request.preset().name()))
                .injectedFlags(Optional.of(plan.injectedArgv()))
                .environmentCapturePolicy(Optional.of(request.inheritance().name()))
                .schemaVersion(OptionalInt.of(SchemaV1.VERSION))
                // A command line names absolute paths by construction, and an
                // environment override carries whatever the user set. Both are
                // stated rather than assumed absent, so the sensitivity warning
                // is honest before anyone reads the session (plan 22.2).
                .containsAbsolutePaths(Optional.of(true))
                .containsEnvironmentValues(
                        Optional.of(!request.environmentOverrides().isEmpty()))
                .warnings(sensitivityWarnings(plan)));
    }

    private static ManagedSessionLayout layoutOf(ManagedSession session) {
        return session.layout();
    }

    /**
     * Warnings about what the recorded command line may contain.
     *
     * <p>The manifest stores the effective command verbatim, which is what
     * makes a session reproducible and is also how a credential passed on a
     * command line ends up on disk. {@code containsEnvironmentValues} does not
     * cover it — that field is about the environment — so the fact is said
     * plainly here, before anyone shares the session (plan 22.2).
     *
     * <p>A name-shaped heuristic, deliberately: the full redaction machinery is
     * a later phase, and a warning that occasionally fires without cause is a
     * far better failure than silence about a leaked token.
     */
    private static List<String> sensitivityWarnings(InstrumentationPlan plan) {
        List<String> warnings = new ArrayList<>();
        for (String argument : plan.effective().toArgv()) {
            String lower = argument.toLowerCase(java.util.Locale.ROOT);
            if (SECRET_NAME_HINTS.stream().anyMatch(lower::contains)) {
                warnings.add("this session records a command line containing an argument whose"
                        + " name suggests a credential; review it before sharing the session");
                break;
            }
        }
        return warnings;
    }

    /** Default secret-name patterns (plan 22.2), to be user-editable in a later phase. */
    private static final List<String> SECRET_NAME_HINTS =
            List.of("token", "password", "passwd", "secret", "credential", "api_key", "apikey",
                    "auth", "_key=");

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
                events.recordDiagnostic(ImportDiagnostic.general(
                        DiagnosticSeverity.WARNING,
                        CaptureDiagnosticCodes.CAPTURE_CANCELLED,
                        "the build was stopped with " + outcome.terminatedBy().orElseThrow()
                                + " after " + outcome.duration().toSeconds() + "s",
                        nowMicros()));
            } else if (outcome != null
                    && outcome.exitCode().orElse(0) == CaptureResult.BES_TRANSPORT_FAILURE_EXIT) {
                // Bazel reports 38 when the event-stream upload failed, whatever
                // the build itself did. Recorded as a capture problem, not as a
                // failed build: blaming the user's build for our transport would
                // be exactly backwards.
                events.recordDiagnostic(ImportDiagnostic.general(
                        DiagnosticSeverity.ERROR,
                        CaptureDiagnosticCodes.STREAM_FAILED,
                        "bazel exited 38: the build event upload failed. The build's own outcome"
                                + " cannot be read from the exit code and must come from the event"
                                + " stream.",
                        nowMicros()));
                warnings.add("bazel exited 38 (build event upload failed); the build's own result"
                        + " is not knowable from its exit code");
            } else if (outcome != null && !outcome.isSuccess()) {
                events.recordDiagnostic(ImportDiagnostic.general(
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
                events.recordDiagnostic(ImportDiagnostic.general(
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
     * <p>The same two facts the import path reports, for the same reason: a
     * duplicate primary output means one of two actions is missing from the
     * table, and a file set referenced but never defined means every byte total
     * reached through it is a lower bound. Both look exactly like a smaller
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
                events.recordDiagnostic(ImportDiagnostic.general(
                        DiagnosticSeverity.WARNING,
                        com.holtherndon.bazelviz.storage.events.DiagnosticCodes
                                .DUPLICATE_ACTION_OUTPUT,
                        conflicts + " action event(s) repeated a primary output already recorded;"
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
                events.recordDiagnostic(ImportDiagnostic.general(
                        DiagnosticSeverity.WARNING,
                        com.holtherndon.bazelviz.storage.events.DiagnosticCodes.UNDEFINED_FILE_SET,
                        undefined + " named set(s) of files were referenced and never defined, so"
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
     * <p>Quiet by design: a session whose indexes were never built still holds
     * every row and answers every query, just more slowly. Failing the capture
     * over it would discard a complete session to protect its speed.
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
            warnings.add("the session's indexes could not be built; it is complete but its"
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
     * <p>Empty for now, and deliberately so: the probe runs outside the
     * workspace in batch mode, and adding the user's startup options there
     * would pin a different server configuration than the one the flag table
     * describes. The parameter exists because plan 7.1 makes startup context
     * part of the cache key, and a later phase that lets the user set startup
     * options will need to thread them here.
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
}
