package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import java.util.Objects;

/**
 * What was learned before anything ran: which Bazel, which workspace, what it
 * supports, and what would be launched.
 *
 * <p>This is the value the instrumentation dialog renders (plan 4.3). Producing
 * it starts the embedded BES server — the plan has to name a real port — but no
 * session directory, no database and no build. A user who reads this and
 * changes their mind leaves nothing behind but a closed socket.
 */
public record Preflight(
        BazelExecutable executable,
        WorkspaceInfo workspace,
        BazelCapabilities capabilities,
        BesEndpoint endpoint,
        InstrumentationPlan plan) {

    public Preflight {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(plan, "plan");
    }

    /** Whether the build may be started as planned. */
    public boolean canLaunch() {
        return plan.canLaunch();
    }
}
