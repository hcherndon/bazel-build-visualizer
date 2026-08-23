package com.holtherndon.bazelviz.enrich.graph;

import com.holtherndon.bazelviz.runner.plan.AuxiliaryQueryPlanner;
import com.holtherndon.bazelviz.runner.proc.Subprocess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Path output = plan.outputFile();
        try {
            Files.createDirectories(output.getParent());
        } catch (IOException cannotCreate) {
            return Result.failed(plan, -1, "cannot create " + output.getParent()
                    + ": " + cannotCreate.getMessage());
        }

        Subprocess.Result result;
        try {
            // Redirected, not captured as a String. Subprocess.run decodes
            // stdout as UTF-8, which is right for `bazel help` and destroys a
            // protobuf: every byte sequence that is not valid UTF-8 becomes a
            // replacement character and the graph is unrecoverable. The file
            // is written by the operating system without the JVM looking at
            // the bytes.
            result = Subprocess.runRedirectingStdout(
                    plan.argv(), plan.command().workingDirectory(),
                    environment(plan), timeout, output);
        } catch (IOException failure) {
            return Result.failed(plan, -1, String.valueOf(failure.getMessage()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failed(plan, -1, "the query was interrupted");
        }

        // The file exists either way. A failed query writes zero bytes, which
        // is itself the signal (Q8), and the user is entitled to inspect the
        // raw output whatever happened (plan 12.4).
        if (result.timedOut()) {
            return Result.failed(plan, -1,
                    "the query did not finish within " + timeout.toMinutes() + " minutes");
        }
        if (result.exitCode() != 0) {
            return Result.failed(plan, result.exitCode(), excerpt(result.stderr()));
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
    private static Map<String, String> environment(AuxiliaryQueryPlanner.Plan plan) {
        Map<String, String> set = new LinkedHashMap<>();
        plan.command().environmentOverrides().forEach((name, value) ->
                value.ifPresent(present -> set.put(name, present)));
        return set;
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

        static Result failed(AuxiliaryQueryPlanner.Plan plan, int exitCode, String error) {
            return new Result(plan, plan.outputFile(), exitCode, Optional.of(error), false);
        }

        /** The command as the user would type it, for the plan display. */
        public List<String> argv() {
            return plan.argv();
        }
    }
}
