package com.holtherndon.bazelviz.enrich.graph;

import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs an {@code aquery} or {@code cquery} and captures its output to a file.
 *
 * <h2>After the build, never during</h2>
 *
 * <p>Plan 8.6: "run enrichment after the primary build by default so it does
 * not delay or contend with the measured execution". A query is an analysis
 * pass in the same Bazel server, so running it alongside the build would both
 * slow the build and change the timings this application exists to report.
 *
 * <h2>Failure is a result, not an exception</h2>
 *
 * <p>Plan 8.6 step 9: treat failure as non-fatal to the main session. A query
 * that cannot run leaves a {@link Result} saying so, and the caller records it
 * in {@code graph_sources} where the UI can explain it. Nothing about it
 * touches the build data.
 *
 * <h2>stdout is the output, stderr is the explanation</h2>
 *
 * <p>Step 8: capture them independently. The proto goes to a file because it is
 * binary and large; stderr is kept in memory because it is the thing shown when
 * the query fails, and truncated because Bazel's stderr on a broken query is
 * mostly progress lines.
 */
public final class AuxiliaryQueryRunner {

    /** How long a query may take before it is abandoned. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /** How much stderr is kept for the failure message. */
    static final int STDERR_LIMIT = 4_000;

    private final Duration timeout;

    public AuxiliaryQueryRunner() {
        this(DEFAULT_TIMEOUT);
    }

    public AuxiliaryQueryRunner(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Runs {@code plan}, writing its stdout to the plan's output file.
     *
     * @return what happened; never throws for a query that merely failed
     */
    public Result run(AuxiliaryQueryPlanner.Plan plan) {
        return run(plan, LocalCommandExecutor.INSTANCE, plan.outputFile());
    }

    /**
     * Runs {@code plan} on {@code executor} while preserving its protobuf in
     * the explicitly local {@code output} file.
     *
     * <p>The command's {@link Path} working directory is converted to text and
     * thereafter belongs to the executor. A remote executor interprets that
     * text remotely; this class never resolves it on the desktop filesystem.
     */
    public Result run(
            AuxiliaryQueryPlanner.Plan plan,
            CommandExecutor executor,
            Path output) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return Result.failed(plan, output, -1,
                    "the output file has no local parent directory: " + output);
        }
        try {
            Files.createDirectories(parent);
            // The old local redirect created an empty file even when the
            // command failed. Preserve that inspectable failure evidence for
            // executors that install redirected output only after success.
            Files.write(output, new byte[0]);
        } catch (IOException cannotCreate) {
            return Result.failed(plan, output, -1, "cannot create " + parent
                    + ": " + cannotCreate.getMessage());
        }

        CommandResult result;
        try {
            CommandRequest request = new CommandRequest(
                    plan.argv(),
                    Optional.of(plan.command().workingDirectory().toString()),
                    plan.command().environmentOverrides(),
                    inheritance(plan),
                    false);
            // Redirected as bytes, never captured as a String: decoding a
            // query protobuf would replace invalid UTF-8 and corrupt it.
            result = executor.runRedirectingStdout(request, timeout, output);
        } catch (IOException failure) {
            return Result.failed(plan, output, -1, String.valueOf(failure.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed(plan, output, -1, "the query was interrupted");
        }

        // The file exists either way. A failed query writes zero bytes, which
        // is itself the signal (Q8), and the user is entitled to inspect the
        // raw output whatever happened (plan 12.4).
        if (result.timedOut()) {
            return Result.failed(plan, output, -1,
                    "the query did not finish within " + timeout.toMinutes() + " minutes");
        }
        if (result.exitCode() != 0) {
            return Result.failed(plan, output, result.exitCode(), excerpt(result.stderr()));
        }
        return new Result(plan, output, 0, Optional.empty(), true);
    }

    /**
     * The build's environment, plus nothing.
     *
     * <p>Plan 8.6 step 3 reuses the workspace and environment, and the reason
     * it matters here is the same as in Phase 4: {@code bazelisk} chooses its
     * Bazel from {@code USE_BAZEL_VERSION}, so a query run without it queries a
     * different Bazel and returns a different graph.
     */
    private static RuntimeEnvironment inheritance(AuxiliaryQueryPlanner.Plan plan) {
        return switch (plan.command().inheritance()) {
            case INHERIT_ALL -> RuntimeEnvironment.INHERIT_ALL;
            case INHERIT_ALLOWLISTED -> RuntimeEnvironment.INHERIT_ESSENTIAL;
            case NONE -> RuntimeEnvironment.NONE;
        };
    }

    private static String excerpt(String stderr) {
        String trimmed = stderr.strip();
        if (trimmed.length() <= STDERR_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, STDERR_LIMIT) + "\n… (truncated)";
    }

    /**
     * What a query run produced.
     *
     * @param output where the proto was written; present whether or not the
     *     query succeeded, because plan 12.4 lets the user inspect raw output
     * @param error the stderr excerpt, absent on success
     */
    public record Result(
            AuxiliaryQueryPlanner.Plan plan,
            Path output,
            int exitCode,
            Optional<String> error,
            boolean succeeded) {

        static Result failed(
                AuxiliaryQueryPlanner.Plan plan, Path output, int exitCode, String error) {
            return new Result(plan, output, exitCode, Optional.of(error), false);
        }

        /** The command as the user would type it, for the plan display. */
        public List<String> argv() {
            return plan.argv();
        }
    }
}
