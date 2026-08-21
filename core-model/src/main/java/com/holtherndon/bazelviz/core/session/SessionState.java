package com.holtherndon.bazelviz.core.session;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a capture/analysis session.
 *
 * <p>The happy path is {@code NEW -> PREFLIGHT -> CAPTURING -> BUILD_FINISHED
 * -> ENRICHING -> INDEXING -> READY}. The remaining states are alternative
 * terminals. A session in {@code CANCELLED}, {@code INCOMPLETE} or
 * {@code CORRUPT_PARTIAL} still has inspectable raw data; those states describe
 * the capture outcome, not whether the session can be opened.
 */
public enum SessionState {
    NEW,
    PREFLIGHT,
    CAPTURING,
    BUILD_FINISHED,
    ENRICHING,
    INDEXING,
    READY,
    FAILED_TO_START,
    CANCELLED,
    INCOMPLETE,
    CORRUPT_PARTIAL,
    READY_WITH_WARNINGS;

    private static final Set<SessionState> TERMINAL = EnumSet.of(
            READY, READY_WITH_WARNINGS, FAILED_TO_START, CANCELLED, INCOMPLETE, CORRUPT_PARTIAL);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public Set<SessionState> allowedNext() {
        return switch (this) {
            case NEW -> EnumSet.of(PREFLIGHT);
            case PREFLIGHT -> EnumSet.of(CAPTURING, FAILED_TO_START);
            case CAPTURING -> EnumSet.of(BUILD_FINISHED, CANCELLED, INCOMPLETE, CORRUPT_PARTIAL);
            case BUILD_FINISHED -> EnumSet.of(ENRICHING, INDEXING);
            case ENRICHING -> EnumSet.of(INDEXING, INCOMPLETE, CORRUPT_PARTIAL);
            case INDEXING -> EnumSet.of(READY, READY_WITH_WARNINGS, INCOMPLETE, CORRUPT_PARTIAL);
            case READY, READY_WITH_WARNINGS, FAILED_TO_START, CANCELLED, INCOMPLETE, CORRUPT_PARTIAL ->
                    EnumSet.noneOf(SessionState.class);
        };
    }

    public boolean canTransitionTo(SessionState next) {
        return allowedNext().contains(next);
    }
}
