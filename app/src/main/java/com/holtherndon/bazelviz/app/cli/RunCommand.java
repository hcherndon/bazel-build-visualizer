package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import com.holtherndon.bazelviz.capture.live.CaptureCoordinator;
import com.holtherndon.bazelviz.capture.live.CaptureProgress;
import com.holtherndon.bazelviz.capture.live.CaptureRequest;
import com.holtherndon.bazelviz.capture.live.CaptureResult;
import com.holtherndon.bazelviz.capture.live.CaptureSummary;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.exec.ExecutableNotUsableException;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code bbv run} — launch an instrumented Bazel build and capture it.
 *
 * <p>The headless face of Phase 2, and the reason every Phase 2 exit criterion
 * can be checked without a display: it plans, shows the plan, launches, streams
 * the build's own output through, cancels on Ctrl-C, and prints what the
 * session ended up containing.
 *
 * <h2>Where the Bazel command goes</h2>
 *
 * <p>After a {@code --}, always. The alternative — guessing which of the
 * options belong to {@code bbv} and which to Bazel — breaks the first time
 * someone passes {@code --json} to a build, and the two tools genuinely share
 * option names. So {@code bbv run --json -- build --keep_going //...} is
 * unambiguous, and a nested {@code --} inside the Bazel command survives
 * untouched.
 *
 * <h2>Streams</h2>
 *
 * <p>Bazel's own console output goes to this process's stderr, interleaved live,
 * because that is where Bazel would have written it. The capture summary goes
 * to stdout. That division is what makes {@code bbv run --json -- build //...}
 * usable in a pipeline while the operator still watches the build scroll past.
 */
final class RunCommand {

    static final String NAME = "run";

    private static final Set<String> FLAGS =
            Set.of("json", "quiet", "help", "dry-run", "replace-bes", "keep-bes");
    private static final Set<String> OPTIONS =
            Set.of("sessions-root", "bazel", "cwd", "preset");

    private RunCommand() {}

    static ExitCode run(List<String> tokens, CliContext ctx) throws CliUsageException {
        Args args = Args.parse(NAME, tokens, FLAGS, OPTIONS);
        if (args.has("help")) {
            printHelp(ctx.out());
            return ExitCode.OK;
        }

        List<String> bazelArgs = args.positionals();
        if (bazelArgs.isEmpty()) {
            throw new CliUsageException(NAME,
                    "no Bazel command was given; put it after '--', as in"
                            + " 'bbv run -- build //...'");
        }

        Path sessionsRoot = sessionsRoot(args, ctx);
        Path workingDirectory = workingDirectory(args);
        String executable = args.value("bazel").orElse("bazel");
        CapturePreset preset = preset(args);
        boolean json = args.has("json");
        boolean quiet = args.has("quiet");

        if (args.has("replace-bes") && args.has("keep-bes")) {
            throw new CliUsageException(NAME,
                    "--replace-bes and --keep-bes ask for opposite things; choose one");
        }

        CaptureRequest request = CaptureRequest.of(
                        sessionsRoot, ctx.appVersion(), executable, workingDirectory, bazelArgs)
                .withPreset(preset)
                // Bazel's output goes where Bazel would have put it. Suppressed
                // by --quiet, which is for a pipeline that wants only the
                // summary, never for hiding a failing build's error block.
                .withConsole(quiet ? ConsoleSink.discarding() : forwardingTo(ctx.err()))
                .withProgress(quiet || json ? progress -> {} : progressTo(ctx.err()));

        try (CaptureCoordinator coordinator = new CaptureCoordinator(request);
                CancellationGuard guard = CancellationGuard.install(
                        ctx.hooks(), ctx.err(), CancellationGuard.DEFAULT_WAIT_MILLIS,
                        CancellationGuard.RUN_STOPPING, CancellationGuard.RUN_TIMED_OUT)) {
            guard.onCancelRequested(() -> coordinator.cancel(CancellationMode.CANCEL));
            try {
                Preflight preflight = resolveConflicts(coordinator, args);
                if (!quiet && !json) {
                    printPlan(ctx.err(), preflight);
                }
                if (!preflight.canLaunch()) {
                    reportBlockers(ctx.err(), preflight.plan());
                    return ExitCode.USAGE;
                }
                if (args.has("dry-run")) {
                    if (json) {
                        ctx.out().println(JsonWriter.writePretty(planJson(preflight)));
                        ctx.out().flush();
                    } else {
                        ctx.err().println("--dry-run: nothing was launched.");
                        ctx.err().flush();
                    }
                    return ExitCode.OK;
                }

                CaptureResult result = coordinator.run();
                report(ctx, result, json);
                return exitCodeFor(result);
            } finally {
                guard.settled();
            }
        } catch (ExecutableNotUsableException notUsable) {
            throw new CliUsageException(NAME, notUsable.getMessage());
        } catch (IOException failure) {
            ctx.err().println(CliMain.PROGRAM + ": the capture could not run: " + failure.getMessage());
            ctx.err().flush();
            return ExitCode.FAILED;
        }
    }

    // ---------------------------------------------------------------- planning

    /**
     * Applies the user's conflict answers, if they gave any.
     *
     * <p>Headless, so the three-way choice plan 8.5 requires becomes two flags
     * and a refusal. A run with neither flag stops and prints the choices rather
     * than picking one: redirecting a team's build results away from their own
     * backend is not a default anything should have.
     */
    private static Preflight resolveConflicts(CaptureCoordinator coordinator, Args args)
            throws IOException {
        Preflight preflight = coordinator.preflight();
        if (!args.has("replace-bes") && !args.has("keep-bes")) {
            return preflight;
        }
        String resolution = args.has("replace-bes")
                ? PlanConflict.RESOLUTION_REPLACE_BES
                : PlanConflict.RESOLUTION_KEEP_BES_USE_FILE;
        return coordinator.replan(request ->
                request.resolving(PlanConflict.Kind.EXISTING_BES_BACKEND, resolution));
    }

    private static void printPlan(PrintStream err, Preflight preflight) {
        InstrumentationPlan plan = preflight.plan();
        err.println("bazel:     " + preflight.executable().displayName()
                + "  (" + preflight.executable().resolved() + ")");
        preflight.workspace().workspaceRoot()
                .ifPresent(root -> err.println("workspace: " + root));
        err.println("original:  " + String.join(" ", plan.original().userVisibleArgs()));
        err.println("effective: " + String.join(" ", plan.effective().userVisibleArgs()));
        for (AddedFlag flag : plan.addedFlags()) {
            err.println("  " + (flag.isApplied() ? "+" : "·") + " " + flag.argv()
                    + "  [" + flag.overhead().displayName() + " overhead]"
                    + (flag.isApplied() ? "" : "  not applied: " + flag.capabilityStatus()));
            err.println("      " + flag.reason());
        }
        for (String warning : plan.warnings()) {
            err.println("  ! " + warning);
        }
        err.println();
        err.flush();
    }

    private static void reportBlockers(PrintStream err, InstrumentationPlan plan) {
        for (String error : plan.errors()) {
            err.println(CliMain.PROGRAM + ": " + error);
        }
        for (PlanConflict conflict : plan.mandatoryConflicts()) {
            err.println(CliMain.PROGRAM + ": " + conflict.summary());
            err.println("  " + conflict.detail());
            for (PlanConflict.Resolution resolution : conflict.resolutions()) {
                err.println("  - " + flagFor(resolution.id()) + ": " + resolution.label());
                err.println("      " + resolution.consequence());
            }
        }
        err.flush();
    }

    /** The command-line flag that selects a resolution, for the message above. */
    private static String flagFor(String resolutionId) {
        return switch (resolutionId) {
            case PlanConflict.RESOLUTION_REPLACE_BES -> "--replace-bes";
            case PlanConflict.RESOLUTION_KEEP_BES_USE_FILE -> "--keep-bes";
            case PlanConflict.RESOLUTION_CANCEL -> "(do nothing)";
            default -> resolutionId;
        };
    }

    // --------------------------------------------------------------- reporting

    private static void report(CliContext ctx, CaptureResult result, boolean json) {
        if (json) {
            ctx.out().println(JsonWriter.writePretty(resultJson(result)));
            ctx.out().flush();
            return;
        }
        PrintStream out = ctx.out();
        out.println("session:  " + result.sessionRoot());
        out.println("state:    " + result.state());
        result.process().ifPresent(process -> out.println("build:    "
                + describeBuild(result, process)
                + " in " + process.duration().toMillis() + " ms"));
        result.capture().ifPresent(capture -> {
            out.println("events:   " + capture.received() + " received, " + capture.journaled()
                    + " journaled, " + capture.normalized() + " indexed"
                    + (capture.nonEventEnvelopes() > 0
                            ? " (+" + capture.nonEventEnvelopes() + " stream-control)" : ""));
            out.println("capture:  " + (capture.isComplete() ? "complete" : "INCOMPLETE"));
            for (String problem : capture.discrepancies()) {
                out.println("  ! " + problem);
            }
            for (BesStreamState stream : capture.streams()) {
                out.println("  stream " + stream.key() + ": " + stream.eventsAccepted()
                        + " events, " + stream.duplicateCount() + " duplicate(s), "
                        + stream.completion());
            }
        });
        for (String warning : result.warnings()) {
            out.println("  ! " + warning);
        }
        out.flush();
    }

    /**
     * How the build ended, in words that do not overstate the exit code.
     *
     * <p>Exit 38 means the event upload failed, and Bazel reports it whatever
     * the build itself did — so calling it "failed" blames the user's build for
     * this application's transport. The build's real result is in the event
     * stream, which later phases read.
     */
    private static String describeBuild(
            CaptureResult result, com.holtherndon.bazelviz.runner.proc.ProcessOutcome process) {
        if (process.wasCancelled()) {
            return "cancelled (" + process.terminatedBy().orElseThrow() + ")";
        }
        if (!result.buildOutcomeKnown()) {
            return "outcome unknown: bazel exited "
                    + CaptureResult.BES_TRANSPORT_FAILURE_EXIT
                    + ", which reports a build event upload failure and hides the build's own result";
        }
        return process.isSuccess()
                ? "succeeded"
                : "failed, exit " + process.exitCode().stream().mapToObj(Integer::toString)
                        .findFirst().orElse("unknown");
    }

    /**
     * The exit code.
     *
     * <p>Reports the <em>capture</em>, not the build. A failed build with a
     * complete stream is a successful capture and exits 0, because the session
     * it produced is exactly what the user asked for; the build's own result is
     * in the summary and in the session. Conflating the two would make
     * {@code bbv run} unusable in a script that captures failing builds on
     * purpose, which is most of them.
     */
    private static ExitCode exitCodeFor(CaptureResult result) {
        if (result.wasCancelled()) {
            return ExitCode.CANCELLED;
        }
        if (result.capture().isEmpty()) {
            return ExitCode.FAILED;
        }
        return result.captureComplete() ? ExitCode.OK : ExitCode.PARTIAL;
    }

    private static JsonValue resultJson(CaptureResult result) {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("session", JsonValue.of(result.sessionRoot().toString()));
        root.put("sessionId", JsonValue.of(result.sessionId().toString()));
        root.put("state", JsonValue.of(result.state().name()));
        result.process().ifPresent(process -> {
            Map<String, JsonValue> build = new LinkedHashMap<>();
            process.exitCode().ifPresent(code -> build.put("exitCode", JsonValue.of(code)));
            process.terminatedBy().ifPresent(
                    mode -> build.put("terminatedBy", JsonValue.of(mode.name())));
            build.put("durationMillis", JsonValue.of(process.duration().toMillis()));
            // Three states, not two. "succeeded": false for exit 38 would tell
            // a scripted consumer the build failed when nothing knows whether
            // it did; outcomeKnown is how it finds out that it must read the
            // event stream instead.
            build.put("outcomeKnown", JsonValue.of(result.buildOutcomeKnown()));
            if (result.buildOutcomeKnown()) {
                build.put("succeeded", JsonValue.of(process.isSuccess()));
            }
            root.put("build", new JsonValue.JsonObject(build));
        });
        result.capture().ifPresent(capture -> {
            Map<String, JsonValue> events = new LinkedHashMap<>();
            events.put("received", JsonValue.of(capture.received()));
            events.put("journaled", JsonValue.of(capture.journaled()));
            events.put("normalized", JsonValue.of(capture.normalized()));
            events.put("streamControl", JsonValue.of(capture.nonEventEnvelopes()));
            events.put("decodeFailures", JsonValue.of(capture.decodeFailures()));
            events.put("bytesJournaled", JsonValue.of(capture.bytesJournaled()));
            events.put("complete", JsonValue.of(capture.isComplete()));
            events.put("lagged", JsonValue.of(capture.lagged()));
            events.put("discrepancies", JsonValue.JsonArray.ofStrings(capture.discrepancies()));
            root.put("capture", new JsonValue.JsonObject(events));
        });
        root.put("injectedFlags", JsonValue.JsonArray.ofStrings(result.plan().injectedArgv()));
        root.put("warnings", JsonValue.JsonArray.ofStrings(result.warnings()));
        return new JsonValue.JsonObject(root);
    }

    private static JsonValue planJson(Preflight preflight) {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("besEndpoint", JsonValue.of(preflight.endpoint().besBackendUri()));
        root.put("originalCommand",
                JsonValue.JsonArray.ofStrings(preflight.plan().original().toArgv()));
        root.put("effectiveCommand",
                JsonValue.JsonArray.ofStrings(preflight.plan().effective().toArgv()));
        root.put("injectedFlags",
                JsonValue.JsonArray.ofStrings(preflight.plan().injectedArgv()));
        root.put("canLaunch", JsonValue.of(preflight.canLaunch()));
        root.put("capabilityDetection",
                JsonValue.of(preflight.capabilities().detection().name()));
        return new JsonValue.JsonObject(root);
    }

    // ------------------------------------------------------------------ inputs

    private static ConsoleSink forwardingTo(PrintStream err) {
        return (stream, data, offset, length) -> {
            // Written as bytes, not decoded and re-encoded: Bazel's progress
            // display is carriage returns and escape sequences, and a round trip
            // through a String would mangle it.
            err.write(data, offset, length);
            err.flush();
        };
    }

    private static com.holtherndon.bazelviz.capture.live.CaptureProgressListener progressTo(
            PrintStream err) {
        return progress -> {
            // Deliberately terse and on one carriage-returned line: this shares
            // stderr with Bazel's own progress display, and two competing
            // multi-line reports would be unreadable.
            if (progress.received() > 0 && progress.received() % 5_000 == 0) {
                err.printf("[bbv] %d events captured, %d indexed%n",
                        progress.received(), progress.normalized());
                err.flush();
            }
        };
    }

    private static Path sessionsRoot(Args args, CliContext ctx) throws CliUsageException {
        Optional<String> given = args.value("sessions-root");
        if (given.isEmpty()) {
            return ctx.defaultSessionsRoot();
        }
        try {
            return Path.of(given.get()).toAbsolutePath().normalize();
        } catch (InvalidPathException bad) {
            throw new CliUsageException(NAME, "not a usable path for --sessions-root: " + given.get());
        }
    }

    private static Path workingDirectory(Args args) throws CliUsageException {
        String given = args.value("cwd").orElse(System.getProperty("user.dir"));
        try {
            Path path = Path.of(given).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isDirectory(path)) {
                throw new CliUsageException(NAME, "not a directory: " + path);
            }
            return path;
        } catch (InvalidPathException bad) {
            throw new CliUsageException(NAME, "not a usable path for --cwd: " + given);
        }
    }

    private static CapturePreset preset(Args args) throws CliUsageException {
        Optional<String> given = args.value("preset");
        if (given.isEmpty()) {
            return CapturePreset.defaultPreset();
        }
        String normalized = given.get().toUpperCase(Locale.ROOT).replace('-', '_');
        for (CapturePreset preset : CapturePreset.values()) {
            if (preset.name().equals(normalized)) {
                return preset;
            }
        }
        List<String> names = new ArrayList<>();
        for (CapturePreset preset : CapturePreset.values()) {
            names.add(preset.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        throw new CliUsageException(NAME,
                "unknown preset '" + given.get() + "'; the presets are " + String.join(", ", names));
    }

    static void printHelp(PrintStream out) {
        out.println("usage: bbv run [options] -- <bazel command>");
        out.println();
        out.println("Launches Bazel with instrumentation added, captures the event stream through");
        out.println("an embedded Build Event Service bound to loopback, and writes a session.");
        out.println();
        out.println("The Bazel command goes after '--', so its options are never confused with");
        out.println("this tool's:  bbv run -- build --keep_going //...");
        out.println();
        out.println("options:");
        out.println("  --bazel=<path>          bazel or bazelisk to use (default: bazel on PATH)");
        out.println("  --cwd=<dir>             where to run the build (default: this directory)");
        out.println("  --sessions-root=<dir>   where to write the session");
        out.println("  --preset=<name>         live-essentials | performance-diagnostics |");
        out.println("                          full-graph-diagnostics | custom");
        out.println("  --dry-run               plan and print, launch nothing");
        out.println("  --replace-bes           if the command names its own BES backend, use ours");
        out.println("  --keep-bes              keep theirs and capture through a local file");
        out.println("  --json                  print the summary as JSON on stdout");
        out.println("  --quiet                 do not forward Bazel's console output");
        out.println();
        out.println("Bazel's own output goes to stderr, where Bazel would have written it.");
        out.println("The summary goes to stdout. Ctrl-C asks Bazel to stop and finalizes the");
        out.println("session as cancelled, which leaves it inspectable.");
        out.println();
        out.println("The exit code describes the capture, not the build: a failing build whose");
        out.println("events were all captured exits 0, and the build's own result is in the");
        out.println("summary. Exit 1 means the capture is incomplete, 4 that it was cancelled.");
        out.flush();
    }
}
