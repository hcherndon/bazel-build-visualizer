package com.holtherndon.bazelviz.core.domain;

/**
 * A test's result, mirroring Bazel's {@code TestStatus} without inventing
 * anything it does not say.
 *
 * <p>The values are Bazel's own, kept rather than mapped onto a smaller set,
 * because the differences matter to a user: {@link #FLAKY} is not
 * {@link #FAILED}, {@link #NO_STATUS} is not {@link #FAILED} either, and a
 * {@link #TIMEOUT} points somewhere quite different from a
 * {@link #REMOTE_FAILURE}. Collapsing them would produce a tests view that
 * says "failed" about four unrelated situations.
 *
 * <p>{@link #UNKNOWN} covers a value this build does not recognise — a newer
 * Bazel adding a status must not be reported as a failure (plan 21.5).
 */
public enum TestOutcome {

    /** Every run, shard and attempt passed. */
    PASSED,

    /** Passed only after a retry. Bazel reports this distinctly and so do we. */
    FLAKY,

    /** The test ran and failed. */
    FAILED,

    /** The test exceeded its timeout. */
    TIMEOUT,

    /** The test could not be run at all — a build failure in the test itself. */
    FAILED_TO_BUILD,

    /** The test was not run: filtered out, or skipped as incompatible. */
    SKIPPED,

    /** The test was cancelled before it produced a result. */
    INCOMPLETE,

    /** The remote execution system failed, which says nothing about the test. */
    REMOTE_FAILURE,

    /**
     * Bazel reported no status. Distinct from a failure: it usually means the
     * build stopped before the test ran.
     */
    NO_STATUS,

    /** A status this build does not recognise, preserved rather than guessed. */
    UNKNOWN;

    /**
     * True when a user should look at this.
     *
     * <p>{@link #FLAKY} counts: a test that needed a retry is a real signal
     * even though it ultimately passed, and hiding it is how flakiness becomes
     * invisible until it is a crisis.
     */
    public boolean needsAttention() {
        return switch (this) {
            case FAILED, TIMEOUT, FAILED_TO_BUILD, FLAKY, REMOTE_FAILURE -> true;
            case PASSED, SKIPPED, INCOMPLETE, NO_STATUS, UNKNOWN -> false;
        };
    }

    /** True when the test actually ran to a verdict. */
    public boolean ran() {
        return switch (this) {
            case PASSED, FLAKY, FAILED, TIMEOUT -> true;
            case FAILED_TO_BUILD, SKIPPED, INCOMPLETE, REMOTE_FAILURE, NO_STATUS, UNKNOWN -> false;
        };
    }

    /**
     * Maps a {@code build_event_stream.TestStatus} name.
     *
     * <p>By name rather than by ordinal: the enum is defined in a vendored
     * proto that a future Bazel may extend, and matching on numbers would
     * silently reassign a meaning the day one is inserted.
     */
    public static TestOutcome ofBazelStatus(String name) {
        if (name == null) {
            return UNKNOWN;
        }
        return switch (name) {
            case "PASSED" -> PASSED;
            case "FLAKY" -> FLAKY;
            case "FAILED" -> FAILED;
            case "TIMEOUT" -> TIMEOUT;
            case "FAILED_TO_BUILD" -> FAILED_TO_BUILD;
            case "TOOL_HALTED_BEFORE_TESTING" -> SKIPPED;
            case "INCOMPLETE" -> INCOMPLETE;
            case "REMOTE_FAILURE" -> REMOTE_FAILURE;
            case "NO_STATUS" -> NO_STATUS;
            default -> UNKNOWN;
        };
    }
}
