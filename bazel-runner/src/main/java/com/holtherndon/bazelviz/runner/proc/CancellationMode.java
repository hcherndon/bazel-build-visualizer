package com.holtherndon.bazelviz.runner.proc;

import java.time.Duration;

/**
 * The three stops offered by plan 8.7, in increasing order of violence.
 *
 * <h2>Why three and not one</h2>
 *
 * <p>They produce different sessions. {@link #CANCEL} lets Bazel finish its BES
 * upload and emit a {@code BuildFinished} event saying the build was
 * interrupted, so the session records a real ending. {@link #TERMINATE} does not,
 * so the stream simply stops and the session is honestly marked incomplete.
 * {@link #FORCE_KILL} additionally reaps the process tree, which is the only way
 * to stop a Bazel server that has stopped responding, and which can leave the
 * workspace's output base locked. Collapsing them into one button would take
 * that choice away from the user and would usually take the graceful ending
 * with it.
 *
 * <p>Whichever is used, plan 8.7's "always" list applies: keep draining output
 * briefly, finalize the raw data already captured, and mark the invocation
 * cancelled rather than failed.
 */
public enum CancellationMode {

    /**
     * Ask Bazel to stop as it would on Ctrl-C: {@code SIGINT} to the client,
     * which asks the server to interrupt the build and still flush its event
     * stream.
     */
    CANCEL(Duration.ofSeconds(30)),

    /** {@code SIGTERM} to the client process. No further events are expected. */
    TERMINATE(Duration.ofSeconds(10)),

    /**
     * {@code SIGKILL} to the process and its descendants. The Bazel server is a
     * descendant only when it was started by this launch; a pre-existing server
     * is a sibling and is deliberately left alone, because killing it would
     * discard analysis state belonging to the user's other terminals.
     */
    FORCE_KILL(Duration.ofSeconds(5));

    private final Duration defaultGrace;

    CancellationMode(Duration defaultGrace) {
        this.defaultGrace = defaultGrace;
    }

    /** How long to wait for this stop before escalating to the next one. */
    public Duration defaultGracePeriod() {
        return defaultGrace;
    }

    /** The next, harsher mode, or empty when this is the last resort. */
    public java.util.Optional<CancellationMode> escalation() {
        return switch (this) {
            case CANCEL -> java.util.Optional.of(TERMINATE);
            case TERMINATE -> java.util.Optional.of(FORCE_KILL);
            case FORCE_KILL -> java.util.Optional.empty();
        };
    }
}
