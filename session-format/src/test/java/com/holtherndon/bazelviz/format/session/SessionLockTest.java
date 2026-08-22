package com.holtherndon.bazelviz.format.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout.SessionDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionLockTest {

    private static final String THIS_HOST = "test-host";

    @TempDir
    Path root;

    private ManagedSessionLayout layout;

    @BeforeEach
    void createSessionDirectory() throws IOException {
        layout = ManagedSessionLayout.forSession(root, SessionId.random());
        layout.createDirectories(Set.of(SessionDirectory.LOCKS));
    }

    @Test
    void anAbsentLockFileIsFree() throws IOException {
        SessionLock.LockState state = SessionLock.inspect(layout.lockFile(), THIS_HOST);

        assertThat(state.status()).isEqualTo(SessionLock.Status.FREE);
        assertThat(state.record()).isEmpty();
        assertThat(state.isBreakable()).isTrue();
    }

    @Test
    void acquiringWritesARecordNamingThisProcess() throws IOException {
        try (SessionLock lock = SessionLock.acquire(layout, "importer", false, 1_000L, THIS_HOST)) {
            assertThat(Files.exists(layout.lockFile())).isTrue();
            assertThat(lock.record().pid()).isEqualTo(ProcessHandle.current().pid());
            assertThat(lock.record().host()).isEqualTo(THIS_HOST);
            assertThat(lock.record().acquiredMicros()).isEqualTo(1_000L);
            assertThat(lock.record().owner()).contains("importer");

            SessionLock.LockState state = SessionLock.inspect(layout.lockFile(), THIS_HOST);
            assertThat(state.status()).isEqualTo(SessionLock.Status.HELD_BY_THIS_PROCESS);
            assertThat(state.isBreakable()).isFalse();
        }

        assertThat(Files.exists(layout.lockFile())).isFalse();
    }

    @Test
    void aSecondAcquisitionIsRefusedWhileTheFirstIsHeld() throws IOException {
        try (SessionLock ignored = SessionLock.acquire(layout, "first", false, 1_000L, THIS_HOST)) {
            assertThatThrownBy(() -> SessionLock.acquire(layout, "second", true, 2_000L, THIS_HOST))
                    .isInstanceOf(SessionLockedException.class)
                    .hasMessageContaining("held by this process");
        }
    }

    @Test
    void aLockFromADeadProcessIsStaleAndCanBeTakenOver() throws IOException {
        long deadPid = pidWithNoLiveProcess();
        writeLockRecord(THIS_HOST, deadPid, 111_000_000L);

        SessionLock.LockState state = SessionLock.inspect(layout.lockFile(), THIS_HOST);
        assertThat(state.status()).isEqualTo(SessionLock.Status.STALE);
        assertThat(state.detail()).contains("no longer running");
        assertThat(state.isBreakable()).isTrue();

        // A session must not be bricked by the crash that left this behind.
        try (SessionLock taken = SessionLock.acquire(layout, "recovery", true, 3_000L, THIS_HOST)) {
            assertThat(taken.record().pid()).isEqualTo(ProcessHandle.current().pid());
        }
    }

    @Test
    void aStaleLockIsStillRefusedWhenBreakingWasNotRequested() throws IOException {
        writeLockRecord(THIS_HOST, pidWithNoLiveProcess(), 111_000_000L);

        assertThatThrownBy(() -> SessionLock.acquire(layout, "polite", false, 3_000L, THIS_HOST))
                .isInstanceOf(SessionLockedException.class)
                .satisfies(thrown -> assertThat(((SessionLockedException) thrown).state().status())
                        .isEqualTo(SessionLock.Status.STALE));
    }

    @Test
    void aRecycledPidIsDetectedByItsProcessStartTime() throws IOException {
        // Same pid as this JVM, but started at a time this JVM was not. That is a
        // different process wearing a recycled pid, so the lock is abandoned.
        writeLockRecord(THIS_HOST, ProcessHandle.current().pid(), 1_000_000L);

        SessionLock.LockState state = SessionLock.inspect(layout.lockFile(), THIS_HOST);

        assertThat(state.status()).isEqualTo(SessionLock.Status.STALE);
        assertThat(state.detail()).contains("recycled");
    }

    @Test
    void aLiveProcessOnThisHostHoldsTheLock() throws IOException {
        // The pid of a live process that is not this JVM: our own parent, if the
        // platform reports one, otherwise this test has nothing to assert against.
        ProcessHandle other = ProcessHandle.current().parent().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(other != null && other.isAlive(),
                "platform does not report a live parent process");

        long startMicros = other.info().startInstant()
                .map(SessionLock::toMicros)
                .orElse(0L);
        if (other.info().startInstant().isPresent()) {
            writeLockRecord(THIS_HOST, other.pid(), startMicros);
        } else {
            writeLockRecordWithoutStart(THIS_HOST, other.pid());
        }

        SessionLock.LockState state = SessionLock.inspect(layout.lockFile(), THIS_HOST);

        assertThat(state.status()).isEqualTo(SessionLock.Status.HELD_LIVE);
        assertThat(state.isBreakable()).isFalse();
    }

    @Test
    void aLockFromAnotherHostIsNotAssumedDead() throws IOException {
        writeLockRecord("some-other-machine", pidWithNoLiveProcess(), 111_000_000L);

        SessionLock.LockState state = SessionLock.inspect(layout.lockFile(), THIS_HOST);

        // This machine cannot ask about a process on that one. Guessing "dead"
        // would let two hosts write the same session over a shared filesystem.
        assertThat(state.status()).isEqualTo(SessionLock.Status.HELD_ELSEWHERE);
        assertThat(state.isBreakable()).isFalse();
        assertThat(state.detail()).contains("some-other-machine");
    }

    @Test
    void anUnreadableLockIsTreatedAsAbandonedRatherThanPermanent() throws IOException {
        Files.writeString(layout.lockFile(), "this is not JSON");

        SessionLock.LockState corrupt = SessionLock.inspect(layout.lockFile(), THIS_HOST);
        assertThat(corrupt.status()).isEqualTo(SessionLock.Status.STALE);
        assertThat(corrupt.detail()).contains("not readable JSON");

        Files.writeString(layout.lockFile(), "{\"formatVersion\":1}");
        SessionLock.LockState incomplete = SessionLock.inspect(layout.lockFile(), THIS_HOST);
        assertThat(incomplete.status()).isEqualTo(SessionLock.Status.STALE);
        assertThat(incomplete.detail()).contains("missing required fields");
    }

    @Test
    void closingDoesNotDeleteSomeoneElsesLock() throws IOException {
        SessionLock lock = SessionLock.acquire(layout, "first", false, 1_000L, THIS_HOST);

        // Simulate the lock having been broken and retaken by another process.
        writeLockRecord(THIS_HOST, pidWithNoLiveProcess(), 222_000_000L);
        lock.close();

        assertThat(Files.exists(layout.lockFile())).isTrue();
    }

    @Test
    void closingTwiceIsHarmless() throws IOException {
        SessionLock lock = SessionLock.acquire(layout, "first", false, 1_000L, THIS_HOST);

        lock.close();
        lock.close();

        assertThat(lock.isReleased()).isTrue();
        assertThat(Files.exists(layout.lockFile())).isFalse();
    }

    @Test
    void breakLockRemovesTheFileUnconditionally() throws IOException {
        writeLockRecord(THIS_HOST, ProcessHandle.current().pid(), 999L);

        assertThat(SessionLock.breakLock(layout)).isTrue();
        assertThat(SessionLock.breakLock(layout)).isFalse();
    }

    private void writeLockRecord(String host, long pid, long processStartMicros) throws IOException {
        Files.writeString(layout.lockFile(),
                """
                {"formatVersion":1,"host":"%s","pid":%d,"processStartMicros":%d,\
                "acquiredMicros":1,"owner":"ghost"}"""
                        .formatted(host, pid, processStartMicros));
    }

    private void writeLockRecordWithoutStart(String host, long pid) throws IOException {
        Files.writeString(layout.lockFile(),
                "{\"formatVersion\":1,\"host\":\"%s\",\"pid\":%d,\"acquiredMicros\":1}".formatted(host, pid));
    }

    /** A pid that no live process holds, so a lock naming it is definitively stale. */
    private static long pidWithNoLiveProcess() {
        for (long candidate = 40_000L; candidate < 500_000L; candidate++) {
            if (ProcessHandle.of(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("every candidate pid was in use");
    }
}
