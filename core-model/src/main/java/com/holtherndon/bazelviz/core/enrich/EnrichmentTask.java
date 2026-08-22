package com.holtherndon.bazelviz.core.enrich;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One independent enrichment job and everything the user needs to understand it
 * having failed.
 *
 * <p>Plan 21.4 is specific about what a failed enrichment must show — task,
 * source, exit status, error excerpt, retriability, and the metrics that are
 * unavailable as a result — and specific that a failed profile import must not
 * invalidate the BEP. Both are properties of this record: tasks carry their own
 * outcome, and nothing about a failed one reaches back into the entity tables.
 *
 * @param kind which enrichment this is
 * @param sourcePath the file it reads, absent for tasks that have none
 * @param state where it got to
 * @param exitStatus a short machine-ish summary, absent while pending
 * @param errorExcerpt the first part of what went wrong, absent on success
 * @param retriable whether running it again could succeed
 * @param unavailableMetrics what the user does not have because this failed,
 *     named in the words the UI uses for them
 * @param recordsRead how far it got, for a partial import
 * @param resumeOffset the byte offset a resumed run would start from
 */
public record EnrichmentTask(
        Kind kind,
        Optional<String> sourcePath,
        State state,
        Optional<String> exitStatus,
        Optional<String> errorExcerpt,
        boolean retriable,
        List<String> unavailableMetrics,
        OptionalLong recordsRead,
        OptionalLong resumeOffset) {

    /** The enrichment jobs this phase can run. */
    public enum Kind {
        EXECUTION_LOG("Execution log"),
        PROFILE("Trace profile"),
        FILESYSTEM_STAT("Output sizes from disk");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** How far a task got. */
    public enum State {
        /** Queued, not started. */
        PENDING,
        /** Running now. */
        RUNNING,
        /** Finished, everything imported. */
        SUCCEEDED,
        /**
         * Finished, some records imported and then a failure.
         *
         * <p>Distinct from {@link #FAILED} because the rows that did land are
         * real and are shown; the coverage panel says how far it got.
         */
        PARTIAL,
        /** Failed with nothing usable imported. */
        FAILED,
        /**
         * Not run, and not a failure: the source does not exist on this Bazel
         * version, or was not requested.
         */
        SKIPPED;

        public boolean isTerminal() {
            return this != PENDING && this != RUNNING;
        }

        /** True when some data from this task is in the database. */
        public boolean producedData() {
            return this == SUCCEEDED || this == PARTIAL;
        }
    }

    public EnrichmentTask {
        unavailableMetrics = List.copyOf(unavailableMetrics);
        if (state == State.SUCCEEDED && errorExcerpt.isPresent()) {
            throw new IllegalArgumentException(
                    "a succeeded task cannot carry an error excerpt");
        }
        if (state == State.SUCCEEDED && !unavailableMetrics.isEmpty()) {
            throw new IllegalArgumentException(
                    "a succeeded task cannot have made metrics unavailable");
        }
        if ((state == State.FAILED || state == State.PARTIAL) && errorExcerpt.isEmpty()) {
            throw new IllegalArgumentException(
                    "a " + state + " task must say what went wrong");
        }
    }

    /** A task that has not started. */
    public static EnrichmentTask pending(Kind kind, String sourcePath) {
        return new EnrichmentTask(
                kind, Optional.ofNullable(sourcePath), State.PENDING, Optional.empty(),
                Optional.empty(), false, List.of(), OptionalLong.empty(), OptionalLong.empty());
    }

    /**
     * A task not run because the source cannot exist here.
     *
     * @param reason why, in the words the panel shows — for example that Bazel
     *     6.5.0 has no compact execution log
     */
    public static EnrichmentTask skipped(Kind kind, String reason, List<String> unavailable) {
        return new EnrichmentTask(
                kind, Optional.empty(), State.SKIPPED, Optional.of(reason), Optional.empty(),
                false, unavailable, OptionalLong.empty(), OptionalLong.empty());
    }
}
