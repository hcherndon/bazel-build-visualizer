package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.capture.bes.BesServer;
import com.holtherndon.bazelviz.capture.bes.BesServerConfig;
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
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
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
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.ImportDiagnostic;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.storage.schema.SchemaV1;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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

    private BesServer server;
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
    public synchronized Preflight preflight() throws IOException {
        if (preflight != null) {
            return preflight;
        }
        WorkspaceInfo workspace = WorkspaceDetector.detect(request.workingDirectory());
        BazelExecutable executable =
                BazelExecutableResolver.resolve(request.executable(), workspace.workspaceRoot());
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
        InstrumentationPlan plan = new InstrumentationPlanner().plan(PlanRequest.initial(
                original, capabilities, request.preset(), provisionalRaw,
                Optional.of(endpoint.besBackendUri())));

        preflight = new Preflight(executable, workspace, capabilities, endpoint, plan);
        return preflight;
    }

    /** Re-plans with the user's answers, keeping the same endpoint and probe. */
    public synchronized Preflight replan(java.util.function.UnaryOperator<PlanRequest> adjust)
            throws IOException {
        Preflight current = preflight();
        PlanRequest base = PlanRequest.initial(
                current.plan().original(),
                current.capabilities(),
                request.preset(),
                request.sessionsRoot().resolve("pending-raw"),
                Optional.of(current.endpoint().besBackendUri()));
        InstrumentationPlan replanned = new InstrumentationPlanner().plan(adjust.apply(base));
        preflight = new Preflight(
                current.executable(), current.workspace(), current.capabilities(),
                current.endpoint(), replanned);
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
        ManagedSessionLayout layout = session.layout();

        SessionDatabase database = null;
        EventWriter events = null;
        StreamRegistry streams = null;
        JournalWriter journal = null;
        LiveCapturePipeline pipeline = null;
        ConsoleCapture console = null;
        ProcessOutcome outcome = null;
        CaptureSummary summary = null;
        SessionState terminal = SessionState.FAILED_TO_START;

        try {
            session.transitionTo(SessionState.PREFLIGHT);

            // The plan named a provisional raw directory during preflight, when
            // no session existed. Re-plan against the real one so the recorded
            // command and any file it writes agree with where they actually go.
            InstrumentationPlan plan = new InstrumentationPlanner().plan(new PlanRequest(
                    ready.plan().original(),
                    ready.capabilities(),
                    request.preset(),
                    layout.rawDirectory(),
                    Optional.of(ready.endpoint().besBackendUri()),
                    java.util.Set.of(),
                    resolutionsOf(ready.plan()),
                    true));

            writeManifest(session, ready, plan);
            InstrumentationPlanCodec.write(layout.instrumentationPlanFile(), plan, ready);

            database = SessionDatabase.open(layout.databaseFile());
            new MigrationRunner(MigrationRunner.standard().migrations()).migrate(database);
            events = new EventWriter(database.writerConnection(), request.options().batchSize(),
                    com.holtherndon.bazelviz.storage.events.StringDictionary.DEFAULT_CACHE_ENTRIES);
            streams = new StreamRegistry(database.writerConnection());

            journal = JournalWriter.create(
                    layout.rawDirectory(), sessionId.value(),
                    JournalWriterConfig.defaults()
                            .withMaxPayloadBytes(request.options().maxMessageBytes()));
            pipeline = new LiveCapturePipeline(
                    journal, events, streams,
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

            BazelLauncher.BazelProcess process = BazelLauncher.start(
                    LaunchRequest.of(plan.effective(), console));
            running.set(process);
            outcome = process.await();

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
            running.set(null);
            sink.detach();
            // Closed in the order that preserves the most: the pipeline first
            // so its threads stop feeding the journal, then the journal, then
            // the database. Closing the database first would leave rows the
            // journal had already promised.
            summary = finishQuietly(pipeline, summary, warnings);
            closeQuietly(console, "console log", warnings);
            closeJournalQuietly(journal, warnings);
            recordOutcome(events, outcome, summary, warnings);
            closeQuietly(events, "event writer", warnings);
            closeQuietly(streams, "stream registry", warnings);
            closeQuietly(database, "session database", warnings);
            terminal = finalizeSession(session, terminal, summary, outcome, warnings);
        }

        return new CaptureResult(
                sessionRoot,
                sessionId,
                terminal,
                preflight.plan(),
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
        BazelLauncher.BazelProcess process = running.get();
        if (process == null) {
            return;
        }
        Thread stopper = new Thread(() -> {
            try {
                process.cancel(mode, true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "bbv-capture-cancel");
        stopper.setDaemon(true);
        stopper.start();
    }

    /** True while a build is running. */
    public boolean isRunning() {
        return running.get() != null;
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

    // ------------------------------------------------------------- internals

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
            warnings.addAll(summary.discrepancies());
            return SessionState.INCOMPLETE;
        }
        return summary.lagged() ? SessionState.READY_WITH_WARNINGS : SessionState.READY;
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
                for (String warning : warnings) {
                    session.addWarning(warning);
                }
                session.finalizeSession(terminal);
            }
            return session.state();
        } catch (IOException | RuntimeException failure) {
            log.error("could not finalize the capture session at {}", session.root(), failure);
            warnings.add("the session could not be finalized cleanly: " + failure);
            return session.state();
        } finally {
            try {
                session.close();
            } catch (IOException releaseFailure) {
                log.warn("could not release the session lock", releaseFailure);
            }
        }
    }

    private void updateManifestCounts(
            ManagedSession session, CaptureSummary summary, ProcessOutcome outcome) throws IOException {
        session.updateManifest(builder -> {
            if (summary != null) {
                builder.eventCount(OptionalLong.of(summary.normalized()));
                builder.addSource(SessionManifest.CaptureSourceEntry.of(
                        "BES_STREAM",
                        Optional.empty(),
                        Optional.empty(),
                        OptionalLong.of(summary.bytesJournaled()),
                        summary.isComplete() ? Completeness.COMPLETE : Completeness.TRUNCATED,
                        Optional.of(summary.streams().size() + " stream(s), "
                                + summary.received() + " event(s) received")));
            }
            builder.addSource(SessionManifest.CaptureSourceEntry.of(
                    "STDOUT",
                    Optional.of(ManagedSessionLayout.STDOUT_LOG_FILE_NAME),
                    Optional.empty(),
                    OptionalLong.empty(),
                    outcome == null ? Completeness.UNKNOWN : Completeness.COMPLETE,
                    Optional.empty()));
            builder.addSource(SessionManifest.CaptureSourceEntry.of(
                    "STDERR",
                    Optional.of(ManagedSessionLayout.STDERR_LOG_FILE_NAME),
                    Optional.empty(),
                    OptionalLong.empty(),
                    outcome == null ? Completeness.UNKNOWN : Completeness.COMPLETE,
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
                        Optional.of(!request.environmentOverrides().isEmpty())));
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
        if (pipeline == null || already != null) {
            return already;
        }
        try {
            return pipeline.finish();
        } catch (IOException | SQLException failure) {
            warnings.add("the capture pipeline could not be drained: " + failure);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warnings.add("draining the capture pipeline was interrupted");
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

    private static java.util.Map<com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind, String>
            resolutionsOf(InstrumentationPlan plan) {
        java.util.Map<com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind, String> resolutions =
                new java.util.EnumMap<>(com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind.class);
        for (var replaced : plan.replacedFlags()) {
            com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind kind =
                    PlanConflictKinds.forResolution(replaced.approvedBy());
            if (kind != null) {
                resolutions.put(kind, replaced.approvedBy());
            }
        }
        // A conflict the user resolved by keeping their backend produced no
        // ReplacedFlag, because nothing of theirs was replaced. It is recovered
        // from the fallback file the plan decided to write.
        boolean usesFallback = plan.appliedFlags().stream()
                .anyMatch(flag -> flag.capability()
                        == com.holtherndon.bazelviz.runner.caps.Capability.BEP_BINARY_FILE);
        if (usesFallback) {
            resolutions.put(
                    com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind.EXISTING_BES_BACKEND,
                    com.holtherndon.bazelviz.runner.plan.PlanConflict.RESOLUTION_KEEP_BES_USE_FILE);
        }
        return resolutions;
    }

    /** Maps a resolution id back to the conflict it answers. */
    private static final class PlanConflictKinds {
        static com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind forResolution(String id) {
            return switch (id) {
                case com.holtherndon.bazelviz.runner.plan.PlanConflict.RESOLUTION_REPLACE_BES,
                        com.holtherndon.bazelviz.runner.plan.PlanConflict.RESOLUTION_KEEP_BES_USE_FILE ->
                        com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind.EXISTING_BES_BACKEND;
                case "overwrite" ->
                        com.holtherndon.bazelviz.runner.plan.PlanConflict.Kind.DESTINATION_EXISTS;
                default -> null;
            };
        }

        private PlanConflictKinds() {}
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
