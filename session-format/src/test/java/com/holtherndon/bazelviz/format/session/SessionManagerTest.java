package com.holtherndon.bazelviz.format.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout.SessionDirectory;
import com.holtherndon.bazelviz.format.session.SessionManifest.CaptureSourceEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerTest {

    private static final String HOST = "test-host";

    @TempDir
    Path sessionsRoot;

    private final List<SessionStateChange> published = new ArrayList<>();

    @Test
    void createsTheDirectoryManifestAndLock() throws Exception {
        SessionManager manager = newManager();
        SessionId id = SessionId.random();

        try (ManagedSession session = manager.create(id)) {
            ManagedSessionLayout layout = session.layout();

            assertThat(layout.root()).isEqualTo(sessionsRoot.resolve("session-" + id.value()));
            assertThat(layout.isManagedSession()).isTrue();
            assertThat(session.state()).isEqualTo(SessionState.NEW);
            assertThat(session.manifest().appVersion()).isEqualTo("test-app");
            assertThat(session.manifest().formatVersion()).isEqualTo(ManifestMigrations.CURRENT_FORMAT_VERSION);
            assertThat(SessionLock.inspect(layout.lockFile(), HOST).status())
                    .isEqualTo(SessionLock.Status.HELD_BY_THIS_PROCESS);

            // Only the directories a fresh capture actually needs.
            assertThat(layout.existingDirectories()).containsExactlyInAnyOrder(
                    SessionDirectory.RAW, SessionDirectory.CHECKPOINTS, SessionDirectory.LOCKS);
        }
    }

    @Test
    void aSeedPopulatesTheManifestAtCreationTime() throws Exception {
        SessionManager manager = newManager();

        try (ManagedSession session = manager.create(
                SessionId.random(),
                Set.of(SessionDirectory.RAW, SessionDirectory.LOCKS),
                builder -> builder
                        .workspaceRoot(Optional.of("/work/repo"))
                        .addSource(CaptureSourceEntry.of(
                                "BEP_BINARY",
                                Optional.of("/tmp/build.bep"),
                                Optional.of("sha"),
                                OptionalLong.of(10),
                                Completeness.COMPLETE,
                                Optional.empty())))) {

            assertThat(session.manifest().workspaceRoot()).contains("/work/repo");
            assertThat(session.manifest().sources()).singleElement()
                    .satisfies(source -> assertThat(source.kind()).isEqualTo("BEP_BINARY"));
            assertThat(session.layout().existingDirectories())
                    .containsExactlyInAnyOrder(SessionDirectory.RAW, SessionDirectory.LOCKS);
        }
    }

    @Test
    void refusesToCreateOverAnExistingSession() throws Exception {
        SessionManager manager = newManager();
        SessionId id = SessionId.random();
        manager.create(id).close();

        assertThatThrownBy(() -> manager.create(id))
                .isInstanceOf(SessionFormatException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void reopensASessionAndSeesWhatWasWritten() throws Exception {
        SessionManager manager = newManager();
        SessionId id = SessionId.random();
        Path root;
        try (ManagedSession session = manager.create(id)) {
            root = session.root();
            session.transitionTo(SessionState.PREFLIGHT);
            session.addWarning("something to remember");
        }

        try (ManagedSession reopened = manager.open(root)) {
            assertThat(reopened.id()).isEqualTo(id);
            assertThat(reopened.state()).isEqualTo(SessionState.PREFLIGHT);
            assertThat(reopened.manifest().warnings()).containsExactly("something to remember");
        }
    }

    @Test
    void refusesToOpenSomethingThatIsNotASession() throws Exception {
        SessionManager manager = newManager();
        Path notASession = Files.createDirectories(sessionsRoot.resolve("random-directory"));

        assertThatThrownBy(() -> manager.open(notASession))
                .isInstanceOf(SessionFormatException.class)
                .hasMessageContaining("no managed session at");
    }

    @Test
    void refusesToOpenASessionAnotherProcessHolds() throws Exception {
        SessionManager manager = newManager();
        try (ManagedSession held = manager.create(SessionId.random())) {
            assertThatThrownBy(() -> manager.open(held.root()))
                    .isInstanceOf(SessionLockedException.class);
        }
    }

    @Test
    void publishesEveryTransitionAfterItIsDurable() throws Exception {
        SessionManager manager = newManager();
        manager.addListener(published::add);

        Path root;
        try (ManagedSession session = manager.create(SessionId.random())) {
            root = session.root();
            session.transitionTo(SessionState.PREFLIGHT);
            session.transitionTo(SessionState.CAPTURING);
        }

        assertThat(published).hasSize(3);
        assertThat(published.get(0).reason()).isEqualTo(SessionStateChange.Reason.CREATED);
        assertThat(published.get(1).from()).isEqualTo(SessionState.NEW);
        assertThat(published.get(1).to()).isEqualTo(SessionState.PREFLIGHT);
        assertThat(published.get(2).to()).isEqualTo(SessionState.CAPTURING);
        assertThat(published).allSatisfy(change -> assertThat(change.sessionRoot()).isEqualTo(root));
    }

    @Test
    void aFailingListenerDoesNotUndoTheTransition() throws Exception {
        SessionManager manager = newManager();
        manager.addListener(change -> {
            throw new IllegalStateException("listener is broken");
        });
        manager.addListener(published::add);

        try (ManagedSession session = manager.create(SessionId.random())) {
            session.transitionTo(SessionState.PREFLIGHT);

            assertThat(session.state()).isEqualTo(SessionState.PREFLIGHT);
            assertThat(manager.readManifest(session.root()).state()).isEqualTo(SessionState.PREFLIGHT);
            assertThat(published).hasSize(2);
        }
    }

    @Test
    void removedListenersStopHearingAboutChanges() throws Exception {
        SessionManager manager = newManager();
        SessionStateListener listener = published::add;
        manager.addListener(listener);
        manager.removeListener(listener);

        manager.create(SessionId.random()).close();

        assertThat(published).isEmpty();
    }

    @Test
    void findsASessionLeftCapturingByACrashedProcess() throws Exception {
        Path root = crashDuringCapture();
        SessionManager manager = newManager();

        List<SessionManager.RecoveryCandidate> candidates = manager.findInterrupted();

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.sessionRoot()).isEqualTo(root);
            assertThat(candidate.state()).isEqualTo(SessionState.CAPTURING);
            assertThat(candidate.lockState().status()).isEqualTo(SessionLock.Status.STALE);
            assertThat(candidate.isRecoverable()).isTrue();
        });
    }

    @Test
    void recoveringACapturingSessionAsIncompleteKeepsItInspectable() throws Exception {
        Path root = crashDuringCapture();
        SessionManager manager = newManager();
        manager.addListener(published::add);

        SessionManager.Recovered recovered = manager.recover(root, SessionManager.RecoveryDecision.MARK_INCOMPLETE);

        assertThat(recovered.session()).isEmpty();
        assertThat(recovered.report().stateFound()).isEqualTo(SessionState.CAPTURING);
        assertThat(recovered.report().stateNow()).isEqualTo(SessionState.INCOMPLETE);
        assertThat(recovered.report().lockFound()).isEqualTo(SessionLock.Status.STALE);

        SessionManifest manifest = manager.readManifest(root);
        assertThat(manifest.state()).isEqualTo(SessionState.INCOMPLETE);
        assertThat(manifest.finalizedMicros()).isPresent();
        // Plan 21.1 step 7: the interruption is recorded, permanently.
        assertThat(manifest.warnings()).singleElement().asString()
                .contains("session was left in state CAPTURING");
        assertThat(published).anySatisfy(change ->
                assertThat(change.reason()).isEqualTo(SessionStateChange.Reason.RECOVERY));

        // The lock the crashed process left behind is gone, and the session is
        // no longer reported as interrupted.
        assertThat(manager.findInterrupted()).isEmpty();
    }

    @Test
    void recoveringToResumeKeepsTheStateAndHandsBackAnOpenSession() throws Exception {
        Path root = crashDuringCapture();
        SessionManager manager = newManager();

        SessionManager.Recovered recovered = manager.recover(root, SessionManager.RecoveryDecision.RESUME);

        try (ManagedSession session = recovered.session().orElseThrow()) {
            assertThat(session.state()).isEqualTo(SessionState.CAPTURING);
            assertThat(session.manifest().warnings()).hasSize(1);
            assertThat(recovered.report().stateNow()).isEqualTo(SessionState.CAPTURING);
            // It is a real, writable session: capture can carry on.
            session.transitionTo(SessionState.BUILD_FINISHED);
        }
    }

    @Test
    void aSessionInterruptedBeforeCapturingIsMarkedFailedToStartNotIncomplete() throws Exception {
        SessionManager manager = newManager();
        Path root;
        try (ManagedSession session = manager.create(SessionId.random())) {
            root = session.root();
            session.transitionTo(SessionState.PREFLIGHT);
        }
        orphanTheLock(root);

        SessionManager.Recovered recovered = manager.recover(root, SessionManager.RecoveryDecision.MARK_INCOMPLETE);

        // INCOMPLETE would promise partial data that was never captured.
        assertThat(recovered.report().stateNow()).isEqualTo(SessionState.FAILED_TO_START);
    }

    @Test
    void recoveryRefusesASessionALiveProcessStillHolds() throws Exception {
        SessionManager manager = newManager();

        try (ManagedSession held = manager.create(SessionId.random())) {
            assertThatThrownBy(() ->
                    manager.recover(held.root(), SessionManager.RecoveryDecision.MARK_INCOMPLETE))
                    .isInstanceOf(SessionLockedException.class);
        }
    }

    @Test
    void aTerminalSessionWithNoLockIsNotConsideredInterrupted() throws Exception {
        SessionManager manager = newManager();
        try (ManagedSession session = manager.create(SessionId.random())) {
            session.transitionTo(SessionState.PREFLIGHT);
            session.transitionTo(SessionState.CAPTURING);
            session.finalizeSession(SessionState.CANCELLED);
        }

        assertThat(manager.findInterrupted()).isEmpty();
    }

    @Test
    void anUnreadableSessionDirectoryDoesNotHideTheOthers() throws Exception {
        SessionManager manager = newManager();
        Path broken;
        try (ManagedSession session = manager.create(SessionId.random())) {
            broken = session.root();
        }
        Files.writeString(broken.resolve("manifest.json"), "{ this is not a manifest");
        Path good = crashDuringCapture();

        List<SessionManager.RecoveryCandidate> candidates = manager.findInterrupted();

        assertThat(candidates).singleElement()
                .satisfies(candidate -> assertThat(candidate.sessionRoot()).isEqualTo(good));
    }

    @Test
    void listsOnlyDirectoriesThatAreActuallySessions() throws Exception {
        SessionManager manager = newManager();
        Path session;
        try (ManagedSession created = manager.create(SessionId.random())) {
            session = created.root();
        }
        Files.createDirectories(sessionsRoot.resolve("not-a-session"));
        Files.writeString(sessionsRoot.resolve("stray.txt"), "x");

        assertThat(manager.listSessionDirectories()).containsExactly(session);
    }

    @Test
    void deletingASessionRemovesItsWholeDirectory() throws Exception {
        SessionManager manager = newManager();
        Path root;
        try (ManagedSession session = manager.create(SessionId.random())) {
            root = session.root();
            Files.writeString(session.layout().journalSegment(0), "raw bytes");
        }

        manager.delete(root);

        assertThat(Files.exists(root)).isFalse();
        assertThat(manager.listSessionDirectories()).isEmpty();
    }

    @Test
    void deletionRefusesALiveSessionAndAnythingWithoutAManifest() throws Exception {
        SessionManager manager = newManager();
        Path notASession = Files.createDirectories(sessionsRoot.resolve("random-directory"));

        assertThatThrownBy(() -> manager.delete(notASession))
                .isInstanceOf(SessionFormatException.class)
                .hasMessageContaining("may not be a session");

        try (ManagedSession held = manager.create(SessionId.random())) {
            assertThatThrownBy(() -> manager.delete(held.root()))
                    .isInstanceOf(SessionLockedException.class);
        }
    }

    private SessionManager newManager() {
        return new SessionManager(
                sessionsRoot, "test-app", SessionManifestCodec.standard(), new CountingClock(), HOST);
    }

    /**
     * A session left exactly as an abruptly killed capture process would leave
     * it: state {@code CAPTURING} on disk and a lock file naming a pid that is no
     * longer running.
     */
    private Path crashDuringCapture() throws IOException {
        SessionManager manager = newManager();
        Path root;
        try (ManagedSession session = manager.create(SessionId.random())) {
            root = session.root();
            session.transitionTo(SessionState.PREFLIGHT);
            session.transitionTo(SessionState.CAPTURING);
        }
        orphanTheLock(root);
        return root;
    }

    /** Replaces the lock with one owned by a process that no longer exists. */
    private static void orphanTheLock(Path sessionRoot) throws IOException {
        long deadPid = 40_000L;
        while (ProcessHandle.of(deadPid).isPresent()) {
            deadPid++;
        }
        Files.writeString(
                ManagedSessionLayout.at(sessionRoot).lockFile(),
                """
                {"formatVersion":1,"host":"%s","pid":%d,"processStartMicros":111,\
                "acquiredMicros":1,"owner":"crashed capture"}"""
                        .formatted(HOST, deadPid));
    }

    private static final class CountingClock implements SessionManager.MicrosClock {
        private long now = 1_000_000L;

        @Override
        public long nowMicros() {
            return now += 1_000L;
        }
    }
}
