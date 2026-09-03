package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout.SessionDirectory;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, opens, finalizes, recovers and deletes managed sessions (plan
 * section 7.1), and publishes state changes to listeners.
 *
 * <p>Phase 1 scope. This service owns the session <em>directory</em>: the
 * layout, the manifest, the lock, and the state machine. It deliberately does
 * not open {@code session.sqlite} — the database is {@code storage-sqlite}'s
 * responsibility, and keeping that boundary means the session format has no
 * JDBC dependency and can be read by a tool that has no database at all.
 *
 * <p>No Swing types appear here or anywhere in this module, and every method
 * touches the disk, so none of them may be called on the EDT (plan 19.1).
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** Source of epoch-microsecond timestamps; injectable so tests are deterministic. */
    @FunctionalInterface
    public interface MicrosClock {
        long nowMicros();

        static MicrosClock of(Clock clock) {
            return () -> SessionLock.toMicros(clock.instant());
        }
    }

    /** Internal hook letting {@link ManagedSession} publish through this manager. */
    @FunctionalInterface
    interface TransitionPublisher {
        void publish(SessionStateChange change);
    }

    /** What to do with a session found interrupted at startup. */
    public enum RecoveryDecision {
        /**
         * Keep the recorded state and hand back an open session so the pipeline
         * can carry on where it stopped — the Phase 1 exit criterion "restart
         * resumes interrupted indexing" lives here. The raw journal is the source
         * of truth for what was actually captured (ADR-004), so resuming is a
         * matter of replaying from the checkpoint, not of trusting the manifest.
         */
        RESUME,
        /**
         * Declare the session over. Walks the shortest legal path to
         * {@link SessionState#INCOMPLETE} (or {@link SessionState#FAILED_TO_START}
         * for a session that never got as far as capturing), so the data already
         * on disk stays inspectable and is honestly labelled as partial.
         */
        MARK_INCOMPLETE
    }

    /** A session found in a non-terminal state, with the reason it looks interrupted. */
    public record RecoveryCandidate(
            Path sessionRoot,
            SessionId sessionId,
            SessionState state,
            SessionLock.LockState lockState) {

        /** True when nothing live is holding it, so recovery can proceed unattended. */
        public boolean isRecoverable() {
            return lockState.isBreakable();
        }
    }

    /** What recovery did. */
    public record RecoveryReport(
            Path sessionRoot,
            SessionId sessionId,
            SessionState stateFound,
            SessionState stateNow,
            SessionLock.Status lockFound,
            RecoveryDecision decision,
            String warning) {}

    private final Path sessionsRoot;
    private final String appVersion;
    private final SessionManifestCodec codec;
    private final MicrosClock clock;
    private final String host;
    private final List<SessionStateListener> listeners = new CopyOnWriteArrayList<>();
    private final TransitionPublisher publisher = this::publish;

    public SessionManager(Path sessionsRoot, String appVersion) {
        this(sessionsRoot, appVersion, SessionManifestCodec.standard(),
                MicrosClock.of(Clock.systemUTC()), SessionLock.currentHost());
    }

    public SessionManager(
            Path sessionsRoot,
            String appVersion,
            SessionManifestCodec codec,
            MicrosClock clock,
            String host) {
        this.sessionsRoot = Objects.requireNonNull(sessionsRoot, "sessionsRoot").normalize();
        this.appVersion = Objects.requireNonNull(appVersion, "appVersion");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.host = Objects.requireNonNull(host, "host");
    }

    public Path sessionsRoot() {
        return sessionsRoot;
    }

    public String appVersion() {
        return appVersion;
    }

    public void addListener(SessionStateListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(SessionStateListener listener) {
        listeners.remove(listener);
    }

    private void publish(SessionStateChange change) {
        log.info("session {} state {} -> {} ({}) at {}",
                change.sessionId(), change.from(), change.to(), change.reason(), change.sessionRoot());
        for (SessionStateListener listener : listeners) {
            try {
                listener.sessionStateChanged(change);
            } catch (RuntimeException e) {
                // A misbehaving listener must not roll back a transition that is
                // already durable on disk, nor starve the listeners after it.
                log.warn("session state listener failed for {} -> {}", change.from(), change.to(), e);
            }
        }
    }

    // ---------------------------------------------------------------- create

    /**
     * Creates a session directory under the sessions root, writes its manifest in
     * state {@link SessionState#NEW}, and takes the lock.
     *
     * <p>Only {@link ManagedSessionLayout#CAPTURE_DIRECTORIES} are created:
     * {@code indexes/} and {@code exports/} appear when there is something to put
     * in them, because an empty directory is a claim that work happened there
     * (plan 10.2, "only create files that are relevant").
     */
    public ManagedSession create(SessionId sessionId) throws IOException {
        return create(sessionId, ManagedSessionLayout.CAPTURE_DIRECTORIES, builder -> builder);
    }

    /**
     * @param directories exactly the subdirectories to create
     * @param seed applied to the initial manifest before it is written, for the
     *     command, workspace and source metadata known at creation time
     */
    public ManagedSession create(
            SessionId sessionId,
            Set<SessionDirectory> directories,
            java.util.function.UnaryOperator<SessionManifest.Builder> seed)
            throws IOException {
        Objects.requireNonNull(sessionId, "sessionId");
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(sessionsRoot, sessionId);
        log.debug("creating session {} at {} with {} capture directorie(s)",
                sessionId, layout.root(), directories.size());
        if (layout.isManagedSession()) {
            throw new SessionFormatException("a session already exists at " + layout.root());
        }
        layout.createDirectories(directories);

        long createdMicros = clock.nowMicros();
        SessionManifest manifest = seed
                .apply(SessionManifest.newSession(sessionId, appVersion, createdMicros))
                .state(SessionState.NEW)
                .sessionId(sessionId)
                .createdMicros(createdMicros)
                .formatVersion(codec.migrations().targetVersion())
                .build();

        SessionLock lock = SessionLock.acquire(layout, ownerDescription(), false, clock.nowMicros(), host);
        boolean ok = false;
        try {
            codec.write(layout.manifestFile(), manifest);
            ok = true;
        } finally {
            if (!ok) {
                lock.close();
            }
        }
        publish(new SessionStateChange(
                sessionId, layout.root(), SessionState.NEW, SessionState.NEW, createdMicros,
                SessionStateChange.Reason.CREATED));
        return new ManagedSession(layout, codec, lock, manifest, publisher, clock);
    }

    // ------------------------------------------------------------------ open

    /**
     * Opens an existing managed session directory for writing, taking its lock.
     *
     * @throws SessionLockedException when another process holds it
     * @throws UnsupportedManifestVersionException when it was written by a newer build
     */
    public ManagedSession open(Path sessionRoot) throws IOException {
        return open(sessionRoot, false);
    }

    private ManagedSession open(Path sessionRoot, boolean breakStaleLock) throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.at(sessionRoot);
        SessionManifest manifest = readManifest(layout);
        log.debug("opening session {} at {} (break stale lock: {})",
                manifest.sessionId(), layout.root(), breakStaleLock);
        SessionLock lock = SessionLock.acquire(layout, ownerDescription(), breakStaleLock, clock.nowMicros(), host);
        return new ManagedSession(layout, codec, lock, manifest, publisher, clock);
    }

    /** Reads a session's manifest without locking it. Safe for listing. */
    public SessionManifest readManifest(Path sessionRoot) throws IOException {
        return readManifest(ManagedSessionLayout.at(sessionRoot));
    }

    private SessionManifest readManifest(ManagedSessionLayout layout) throws IOException {
        if (!layout.isManagedSession()) {
            throw new SessionFormatException(
                    "no managed session at " + layout.root() + " (expected " + ManagedSessionLayout.MANIFEST_FILE_NAME
                            + ")");
        }
        SessionManifest manifest = codec.read(layout.manifestFile());
        ManagedSessionLayout.sessionIdFromDirectoryName(layout.root()).ifPresent(fromName -> {
            if (!fromName.equals(manifest.sessionId())) {
                // The directory is the artifact and the manifest wins, but a
                // mismatch means someone renamed or copied a session and it is
                // worth saying out loud rather than silently preferring one.
                log.warn("session directory {} names id {} but its manifest says {}",
                        layout.root(), fromName, manifest.sessionId());
            }
        });
        return manifest;
    }

    /** Every managed session directory directly beneath the sessions root. */
    public List<Path> listSessionDirectories() throws IOException {
        if (!Files.isDirectory(sessionsRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(sessionsRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(path -> ManagedSessionLayout.at(path).isManagedSession())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    // -------------------------------------------------------------- recovery

    /**
     * Sessions that were left mid-flight: a non-terminal state, or a lock that
     * outlived its owner (plan 21.1 step 1).
     *
     * <p>Non-mutating. A session whose manifest cannot be read is logged and
     * skipped rather than aborting the scan — one damaged directory must not
     * hide every other session from the user.
     */
    public List<RecoveryCandidate> findInterrupted() throws IOException {
        List<RecoveryCandidate> candidates = new ArrayList<>();
        for (Path sessionRoot : listSessionDirectories()) {
            ManagedSessionLayout layout = ManagedSessionLayout.at(sessionRoot);
            SessionManifest manifest;
            try {
                manifest = codec.read(layout.manifestFile());
            } catch (SessionFormatException e) {
                log.warn("skipping unreadable session at {}: {}", sessionRoot, e.getMessage());
                continue;
            }
            SessionLock.LockState lockState = SessionLock.inspect(layout.lockFile(), host);
            boolean interrupted = !manifest.state().isTerminal()
                    || lockState.status() == SessionLock.Status.STALE;
            if (interrupted) {
                candidates.add(new RecoveryCandidate(
                        sessionRoot, manifest.sessionId(), manifest.state(), lockState));
            }
        }
        log.debug("interrupted-session scan under {} found {} candidate(s)",
                sessionsRoot, candidates.size());
        return List.copyOf(candidates);
    }

    /**
     * Recovers one interrupted session.
     *
     * <p>What happens: a stale lock is broken and retaken, a recovery warning is
     * appended to the manifest so the session is permanently marked as having
     * been interrupted (plan 21.1 step 7), and then either the state is left
     * alone for the pipeline to resume, or the session is walked to a terminal
     * state. Journal truncation, checkpoint comparison and index rebuilding —
     * plan 21.1 steps 2 through 6 — belong to the journal and normalization
     * layers and run against the session this returns.
     *
     * @return the open session and a report of what was found, or an empty
     *     session when the decision was {@link RecoveryDecision#MARK_INCOMPLETE}
     *     and the session was therefore closed
     * @throws SessionLockedException when a live process still holds it
     */
    public Recovered recover(Path sessionRoot, RecoveryDecision decision) throws IOException {
        Objects.requireNonNull(decision, "decision");
        ManagedSessionLayout layout = ManagedSessionLayout.at(sessionRoot);
        SessionLock.LockState lockState = SessionLock.inspect(layout.lockFile(), host);
        if (!lockState.isBreakable()) {
            throw new SessionLockedException(layout.lockFile(), lockState);
        }

        ManagedSession session = open(sessionRoot, true);
        SessionState found = session.state();
        String warning = "recovered at %d: session was left in state %s (%s)"
                .formatted(clock.nowMicros(), found, lockState.detail());
        boolean ok = false;
        try {
            session.addWarning(warning);
            if (decision == RecoveryDecision.MARK_INCOMPLETE && !found.isTerminal()) {
                session.finalizeSession(terminalFor(found), SessionStateChange.Reason.RECOVERY);
            }
            ok = true;
        } finally {
            if (!ok) {
                session.close();
            }
        }

        RecoveryReport report = new RecoveryReport(
                layout.root(), session.id(), found, session.state(), lockState.status(), decision, warning);
        log.info("recovered session {} from {} to {} using {} (lock {})",
                session.id(), found, session.state(), decision, lockState.status());
        if (decision == RecoveryDecision.MARK_INCOMPLETE) {
            session.close();
            return new Recovered(report, Optional.empty());
        }
        return new Recovered(report, Optional.of(session));
    }

    /**
     * The terminal state that honestly describes a session abandoned in
     * {@code state}. A session that never reached {@code CAPTURING} produced no
     * data at all, which is {@link SessionState#FAILED_TO_START}, not
     * {@link SessionState#INCOMPLETE} — the latter promises partial data that
     * would not be there.
     */
    private static SessionState terminalFor(SessionState state) {
        return switch (state) {
            case NEW, PREFLIGHT -> SessionState.FAILED_TO_START;
            default -> SessionState.INCOMPLETE;
        };
    }

    /** A recovery outcome: always a report, plus the open session when it was resumed. */
    public record Recovered(RecoveryReport report, Optional<ManagedSession> session) {}

    // -------------------------------------------------------------- deletion

    /**
     * Deletes a managed session directory and everything in it (ADR-005:
     * deleting a session is deleting its directory).
     *
     * @throws SessionLockedException when a live process holds it
     */
    public void delete(Path sessionRoot) throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.at(sessionRoot);
        SessionLock.LockState lockState = SessionLock.inspect(layout.lockFile(), host);
        if (!lockState.isBreakable()) {
            throw new SessionLockedException(layout.lockFile(), lockState);
        }
        if (!Files.exists(layout.root())) {
            return;
        }
        if (!layout.isManagedSession()) {
            throw new SessionFormatException(
                    "refusing to delete " + layout.root() + ": it has no manifest and may not be a session");
        }
        deleteRecursively(layout.root());
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String ownerDescription() {
        return "Bazel Build Visualizer " + appVersion;
    }
}
