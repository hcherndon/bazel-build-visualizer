package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a finished capture produced.
 *
 * <p>The build's outcome and the capture's outcome are separate fields because
 * they are separate facts and both matter. A build that failed with a complete
 * event stream is a good session about a bad build — the most useful kind this
 * tool produces. A build that succeeded while the capture lost events is a bad
 * session about a good build, and must not be presented as if it were fine.
 *
 * @param sessionRoot the managed session directory
 * @param sessionId its identity
 * @param state the terminal session state
 * @param plan what was launched and why
 * @param process how Bazel ended, absent when it never started
 * @param capture what the pipeline received, absent when capture never began
 * @param warnings anything the user should know, in the order it was noticed
 */
public record CaptureResult(
        Path sessionRoot,
        SessionId sessionId,
        SessionState state,
        InstrumentationPlan plan,
        Optional<ProcessOutcome> process,
        Optional<CaptureSummary> capture,
        List<String> warnings) {

    public CaptureResult {
        Objects.requireNonNull(sessionRoot, "sessionRoot");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(plan, "plan");
        process = Objects.requireNonNull(process, "process");
        capture = Objects.requireNonNull(capture, "capture");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /** True when Bazel reported success. Says nothing about the capture. */
    public boolean buildSucceeded() {
        return process.map(ProcessOutcome::isSuccess).orElse(false);
    }

    /** True when everything Bazel sent was kept. Says nothing about the build. */
    public boolean captureComplete() {
        return capture.map(CaptureSummary::isComplete).orElse(false);
    }

    /** True when the user stopped the build. */
    public boolean wasCancelled() {
        return state == SessionState.CANCELLED;
    }
}
