package com.holtherndon.bazelviz.format.session;

import java.nio.file.Path;

/**
 * The session is already in use and this process must not write to it.
 *
 * <p>Carries the {@link SessionLock.LockState} that caused the refusal, because
 * the right response depends entirely on which one it is: a
 * {@link SessionLock.Status#HELD_LIVE} lock means "close the other window", a
 * {@link SessionLock.Status#HELD_ELSEWHERE} one means "another machine may have
 * it open, and you decide". Collapsing them into one message would leave the
 * user with no way to act.
 */
public final class SessionLockedException extends SessionFormatException {

    private static final long serialVersionUID = 1L;

    private final transient SessionLock.LockState state;

    public SessionLockedException(Path lockFile, SessionLock.LockState state) {
        super("session is locked (" + lockFile + "): " + state.detail());
        this.state = state;
    }

    /** Why the lock was refused, including the owning record when it was readable. */
    public SessionLock.LockState state() {
        return state;
    }
}
