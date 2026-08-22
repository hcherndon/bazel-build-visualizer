package com.holtherndon.bazelviz.runner.proc;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything needed to start one Bazel process.
 *
 * <p>Separate from {@link BazelCommand} because the command describes
 * <em>what</em> to run and this describes <em>how to run it here</em>: where the
 * output goes, how long to wait for a graceful stop, whether the launcher is
 * allowed to use a shell. Keeping them apart means the command can be planned,
 * displayed, edited and stored in the manifest without dragging a console sink
 * and a timeout along with it.
 *
 * @param command the command to run
 * @param console where console bytes are delivered
 * @param gracePeriod how long each cancellation step waits before escalating;
 *     empty uses {@link CancellationMode#defaultGracePeriod()}
 * @param drainAfterExit how long to keep reading the pipes after the process
 *     exits, so the last lines of a failing build are not lost (plan 8.7,
 *     "continue draining output briefly")
 * @param shellPath the shell to use when the command asks for shell mode; empty
 *     means the platform default. Present only to make shell mode explicit and
 *     testable — direct argv remains the default (plan 22.3)
 */
public record LaunchRequest(
        BazelCommand command,
        ConsoleSink console,
        Optional<Duration> gracePeriod,
        Duration drainAfterExit,
        Optional<String> shellPath) {

    /**
     * Two seconds. Long enough for Bazel's final error block to arrive after the
     * client exits, short enough that a user clicking cancel sees the UI respond.
     */
    public static final Duration DEFAULT_DRAIN = Duration.ofSeconds(2);

    public LaunchRequest {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(console, "console");
        gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod");
        Objects.requireNonNull(drainAfterExit, "drainAfterExit");
        shellPath = Objects.requireNonNull(shellPath, "shellPath");
        if (drainAfterExit.isNegative()) {
            throw new IllegalArgumentException("drainAfterExit must not be negative: " + drainAfterExit);
        }
    }

    public static LaunchRequest of(BazelCommand command, ConsoleSink console) {
        return new LaunchRequest(command, console, Optional.empty(), DEFAULT_DRAIN, Optional.empty());
    }

    /** The grace period for {@code mode}, honouring any override. */
    public Duration gracePeriodFor(CancellationMode mode) {
        return gracePeriod.orElseGet(mode::defaultGracePeriod);
    }
}
