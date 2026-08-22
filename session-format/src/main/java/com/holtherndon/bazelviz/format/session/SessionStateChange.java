package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One committed session-state transition.
 *
 * <p>Published only after the manifest on disk has been rewritten, so a listener
 * never sees a state that a crash would erase.
 *
 * @param sessionId the session that moved
 * @param sessionRoot its managed directory
 * @param from the state left behind
 * @param to the state now recorded on disk
 * @param atMicros when the transition was committed, epoch microseconds
 * @param reason why it happened
 */
public record SessionStateChange(
        SessionId sessionId,
        Path sessionRoot,
        SessionState from,
        SessionState to,
        long atMicros,
        Reason reason) {

    /** Why a transition happened, so a listener can tell routine progress from repair. */
    public enum Reason {
        /** A session was created; {@code from} equals {@code to} equals {@code NEW}. */
        CREATED,
        /** Normal progress through the lifecycle. */
        PROGRESS,
        /** Startup recovery moved an interrupted session (plan 21.1). */
        RECOVERY
    }

    public SessionStateChange {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sessionRoot, "sessionRoot");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
    }
}
