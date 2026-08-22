package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportFormatException;
import com.holtherndon.bazelviz.capture.file.importer.ImportOutcome;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.capture.file.importer.PreservedSource;
import com.holtherndon.bazelviz.capture.file.importer.UnsupportedSourceException;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * {@code bbv import} — the headless face of the Phase 1 import pipeline.
 *
 * <p>This command exists so that every Phase 1 exit criterion can be checked
 * without a display: it imports complete and truncated files, resumes an
 * interrupted one, reports the preserved source and its digest, prints
 * reproducible counts, and runs the same bounded-memory pipeline the UI will.
 *
 * <p>Division of streams: progress on stderr, summary on stdout, errors on
 * stderr. That is what makes {@code bbv import --json build.bep > summary.json}
 * work while the operator still watches it run, and it is why an error never
 * goes to stdout even in {@code --json} mode — a consumer parsing stdout must
 * never receive half a summary followed by an error object.
 */
final class ImportCommand {

    static final String NAME = "import";

    private static final Set<String> FLAGS = Set.of("json", "quiet", "resume", "help");
    private static final Set<String> OPTIONS = Set.of("sessions-root", "name");

    /**
     * Namespace prefix for {@code --name}. Fixed forever: it is the difference
     * between a name meaning the same session tomorrow and meaning a different
     * one.
     */
    private static final String NAME_NAMESPACE = "bbv-session:";

    private ImportCommand() {}

    static ExitCode run(List<String> tokens, CliContext ctx) throws CliUsageException {
        Args args = Args.parse(NAME, tokens, FLAGS, OPTIONS);
        if (args.has("help")) {
            printHelp(ctx.out());
            return ExitCode.OK;
        }

        Path source = sourceArgument(args);
        Path sessionsRoot = sessionsRoot(args, ctx);
        boolean json = args.has("json");
        boolean resume = args.has("resume");
        Optional<SessionId> named = args.value("name").map(ImportCommand::sessionIdFor);

        SessionManager sessions = new SessionManager(sessionsRoot, ctx.appVersion());
        BepImporter importer = new BepImporter(sessions);

        Path resumeRoot = null;
        if (resume) {
            resumeRoot = locateResumableSession(sessions, sessionsRoot, source, named);
        } else if (named.isPresent()) {
            Path root = ManagedSessionLayout.forSession(sessionsRoot, named.get()).root();
            if (ManagedSessionLayout.at(root).isManagedSession()) {
                throw new CliUsageException(NAME, "a session named '" + args.value("name").orElseThrow()
                        + "' already exists at " + root
                        + "; add --resume to continue it, or choose another --name");
            }
        }

        ProgressReporter reporter = new ProgressReporter(ctx.err(), args.has("quiet"), ctx.interactive());
        long startNanos = System.nanoTime();

        try (CancellationGuard guard = CancellationGuard.install(ctx.hooks(), ctx.err())) {
            try {
                ImportResult result = resume
                        ? importer.resume(resumeRoot, reporter, guard)
                        : importer.importFile(source, named.orElseGet(SessionId::random), reporter, guard);
                long elapsedNanos = System.nanoTime() - startNanos;
                reporter.finish();
                return report(result, elapsedNanos, sessions, json, ctx);
            } catch (UnsupportedSourceException e) {
                reporter.finish();
                // The detector's own reason is in the message; it is far more
                // useful than "unsupported file" and nothing was created on disk.
                return fail(ctx.err(), e.getMessage());
            } catch (ImportFormatException e) {
                reporter.finish();
                return fail(ctx.err(), e.getMessage());
            } catch (IllegalStateException e) {
                reporter.finish();
                return fail(ctx.err(), e.getMessage());
            } catch (IOException e) {
                reporter.finish();
                return fail(ctx.err(), describe(e));
            } catch (UncheckedIOException e) {
                reporter.finish();
                return fail(ctx.err(), describe(e.getCause()));
            } finally {
                // After the summary has been written, not merely after the
                // import returned: a shutdown hook waiting on this is what keeps
                // the JVM alive long enough for the operator to see it.
                guard.settled();
            }
        }
    }

    // --------------------------------------------------------------- arguments

    private static Path sourceArgument(Args args) throws CliUsageException {
        List<String> positionals = args.positionals();
        if (positionals.isEmpty()) {
            throw new CliUsageException(NAME, "no BEP file given");
        }
        if (positionals.size() > 1) {
            throw new CliUsageException(NAME,
                    "expected one BEP file, got " + positionals.size() + ": " + String.join(", ", positionals));
        }
        Path source = path(positionals.get(0));
        if (Files.isDirectory(source)) {
            throw new CliUsageException(NAME, source
                    + " is a directory; 'bbv import' takes a BEP file, and an already-imported"
                    + " session directory is read with 'bbv inspect'");
        }
        if (!Files.exists(source)) {
            throw new CliUsageException(NAME, "no such file: " + source);
        }
        if (!Files.isReadable(source)) {
            throw new CliUsageException(NAME, "cannot read " + source + " (permission denied)");
        }
        return source;
    }

    private static Path sessionsRoot(Args args, CliContext ctx) throws CliUsageException {
        Optional<String> given = args.value("sessions-root");
        if (given.isEmpty()) {
            return ctx.defaultSessionsRoot();
        }
        Path root = path(given.get());
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new CliUsageException(NAME, "--sessions-root " + root + " is not a directory");
        }
        return root;
    }

    private static Path path(String text) throws CliUsageException {
        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new CliUsageException(NAME, "'" + text + "' is not a usable path: " + e.getReason());
        }
    }

    /**
     * The session id a {@code --name} stands for.
     *
     * <p>A session has a UUID identity and no display name — the manifest is a
     * frozen contract and inventing a field in it here would be a schema change
     * smuggled in through a CLI flag. So {@code --name} instead makes the
     * identity <em>derivable</em>: the same name always names the same session
     * directory, which is what a script re-running an import actually needs. A
     * name that already is a UUID is taken at face value so an exact session can
     * be addressed.
     */
    private static SessionId sessionIdFor(String name) {
        try {
            return SessionId.parse(name);
        } catch (IllegalArgumentException notAUuid) {
            return new SessionId(
                    UUID.nameUUIDFromBytes((NAME_NAMESPACE + name).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Finds the session a {@code --resume} means: the one named by
     * {@code --name}, or failing that the single interrupted session under the
     * sessions root that records this file as its source.
     *
     * <p>Ambiguity is refused rather than guessed. Resuming the wrong session
     * would append one file's events to another file's journal, and no later
     * check could untangle that.
     */
    private static Path locateResumableSession(
            SessionManager sessions, Path sessionsRoot, Path source, Optional<SessionId> named)
            throws CliUsageException {
        if (named.isPresent()) {
            Path root = ManagedSessionLayout.forSession(sessionsRoot, named.get()).root();
            if (!ManagedSessionLayout.at(root).isManagedSession()) {
                throw new CliUsageException(NAME,
                        "no session to resume at " + root + "; run the import without --resume first");
            }
            return root;
        }
        List<SessionManager.RecoveryCandidate> candidates;
        try {
            candidates = sessions.findInterrupted();
        } catch (IOException e) {
            throw new CliUsageException(NAME,
                    "cannot scan " + sessionsRoot + " for interrupted sessions: " + describe(e));
        }
        List<Path> matches = new ArrayList<>();
        for (SessionManager.RecoveryCandidate candidate : candidates) {
            if (candidate.state().isTerminal()) {
                continue;
            }
            try {
                SessionManifest manifest = sessions.readManifest(candidate.sessionRoot());
                boolean sameSource = manifest.sources().stream()
                        .flatMap(entry -> entry.path().stream())
                        .anyMatch(recorded -> recorded.equals(source.toString()));
                if (sameSource) {
                    matches.add(candidate.sessionRoot());
                }
            } catch (IOException unreadable) {
                // A damaged manifest must not hide the other candidates.
            }
        }
        if (matches.isEmpty()) {
            throw new CliUsageException(NAME, "no interrupted import of " + source + " found under "
                    + sessionsRoot + "; run the import without --resume to start one");
        }
        if (matches.size() > 1) {
            StringBuilder message = new StringBuilder("more than one interrupted import of ")
                    .append(source).append(" is present; name the one to continue with --name:");
            for (Path match : matches) {
                message.append(System.lineSeparator()).append("    ").append(match);
            }
            throw new CliUsageException(NAME, message.toString());
        }
        return matches.get(0);
    }

    // ----------------------------------------------------------------- results

    private static ExitCode report(
            ImportResult result, long elapsedNanos, SessionManager sessions, boolean json, CliContext ctx) {
        SessionManifest manifest;
        try {
            manifest = sessions.readManifest(result.sessionRoot());
        } catch (IOException e) {
            return fail(ctx.err(), "imported into " + result.sessionRoot()
                    + " but its manifest cannot be read back: " + describe(e));
        }
        SessionSnapshot snapshot;
        try {
            snapshot = SessionSnapshot.read(result.sessionRoot());
        } catch (IOException | SQLException e) {
            return fail(ctx.err(), "imported into " + result.sessionRoot()
                    + " but its database cannot be read back: " + describe(e));
        }
        ExitCode code = exitCodeFor(result.outcome());
        if (json) {
            ctx.out().print(JsonWriter.writePretty(toJson(result, manifest, snapshot, elapsedNanos, code)));
        } else {
            printText(ctx.out(), result, manifest, snapshot, elapsedNanos);
        }
        ctx.out().flush();
        return code;
    }

    /**
     * Damage is not failure. A truncated source still produced a session full of
     * real events, so it gets its own code rather than being lumped in with an
     * import that produced nothing.
     */
    private static ExitCode exitCodeFor(ImportOutcome outcome) {
        return switch (outcome) {
            case COMPLETE -> ExitCode.OK;
            case TRUNCATED, CORRUPT_PARTIAL -> ExitCode.PARTIAL;
            case CANCELLED -> ExitCode.CANCELLED;
        };
    }

    private static void printText(
            PrintStream out,
            ImportResult result,
            SessionManifest manifest,
            SessionSnapshot snapshot,
            long elapsedNanos) {
        out.println(headline(result));
        out.printf(Locale.ROOT, "  session id         %s%n", result.sessionId());
        out.printf(Locale.ROOT, "  session directory  %s%n", result.sessionRoot());
        out.printf(Locale.ROOT, "  session state      %s%n", result.sessionState());
        out.printf(Locale.ROOT, "  detected format    %s%n", result.format());
        PreservedSource source = result.source();
        out.printf(Locale.ROOT, "  preservation       %s%n", source.preservation());
        source.storedPath().ifPresent(stored ->
                out.printf(Locale.ROOT, "  preserved copy     %s%n", stored));
        out.println("  sources:");
        int index = 0;
        for (SessionManifest.CaptureSourceEntry entry : manifest.sources()) {
            index++;
            out.printf(Locale.ROOT, "    %d. %s  completeness %s%n", index, entry.kind(), entry.completeness());
            out.printf(Locale.ROOT, "       path      %s%n", entry.path().orElse(Formatting.UNKNOWN));
            out.printf(Locale.ROOT, "       bytes     %s%n", Formatting.byteSize(entry.byteSize()));
            out.printf(Locale.ROOT, "       sha256    %s%n", entry.sha256().orElse(Formatting.UNKNOWN));
            entry.note().ifPresent(note -> out.printf(Locale.ROOT, "       note      %s%n", note));
        }
        out.printf(Locale.ROOT, "  events stored      %s%n", Formatting.count(snapshot.eventCount()));
        out.printf(Locale.ROOT, "  records journaled  %s (this run)%n",
                Formatting.count(result.recordsJournaled()));
        out.printf(Locale.ROOT, "  decode status      %s%n", snapshot.decodeStatusSummary());
        out.printf(Locale.ROOT, "  diagnostics        %s%n", Formatting.count(snapshot.diagnosticCount()));
        snapshot.diagnosticCodesOrdered().forEach((code, count) ->
                out.printf(Locale.ROOT, "       %-22s %s%n", code, Formatting.count(count)));
        if (result.damageOffset().isPresent()) {
            out.printf(Locale.ROOT, "  damage at byte     %s%n",
                    Formatting.count(result.damageOffset().getAsLong()));
        }
        out.printf(Locale.ROOT, "  peak buffers       %s%n", Formatting.bytes(result.peakBufferBytes()));
        out.printf(Locale.ROOT, "  elapsed            %s (%s)%n",
                Formatting.duration(elapsedNanos),
                Formatting.rate(result.recordsJournaled(), elapsedNanos));
        for (String warning : manifest.warnings()) {
            out.printf(Locale.ROOT, "  warning            %s%n", warning);
        }
        if (result.outcome() == ImportOutcome.CANCELLED) {
            out.println();
            out.println("  the session is unfinished and resumable; continue it with:");
            out.printf(Locale.ROOT, "    bbv import %s --sessions-root %s --resume%n",
                    source.originalPath(), result.sessionRoot().getParent());
        }
    }

    private static String headline(ImportResult result) {
        String resumed = result.resumedFromCheckpoint() ? " (resumed)" : "";
        return switch (result.outcome()) {
            case COMPLETE -> "import complete" + resumed;
            case TRUNCATED -> "import incomplete" + resumed
                    + ": the source ends mid-record at byte "
                    + Formatting.count(result.damageOffset()) + "; everything before it was imported";
            case CORRUPT_PARTIAL -> "import stopped" + resumed
                    + ": the source is corrupt at byte "
                    + Formatting.count(result.damageOffset()) + "; everything before it was imported";
            case CANCELLED -> "import cancelled" + resumed + "; the session was left resumable";
        };
    }

    private static JsonObject toJson(
            ImportResult result,
            SessionManifest manifest,
            SessionSnapshot snapshot,
            long elapsedNanos,
            ExitCode code) {
        List<JsonObject> sources = new ArrayList<>();
        for (SessionManifest.CaptureSourceEntry entry : manifest.sources()) {
            sources.add(Json.object()
                    .put("kind", entry.kind())
                    .putString("path", entry.path())
                    .putString("sha256", entry.sha256())
                    .put("byteSize", entry.byteSize())
                    .put("completeness", entry.completeness().name())
                    .putString("note", entry.note())
                    .build());
        }
        Json.Obj decode = Json.object();
        snapshot.decodeStatusOrdered().forEach(decode::put);
        Json.Obj diagnosticCodes = Json.object();
        snapshot.diagnosticCodesOrdered().forEach(diagnosticCodes::put);

        return Json.object()
                .put("command", NAME)
                .put("outcome", result.outcome().name())
                .put("exitCode", code.code())
                .put("sessionId", result.sessionId().toString())
                .put("sessionDirectory", result.sessionRoot().toString())
                .put("sessionState", result.sessionState().name())
                .put("format", result.format().name())
                .put("preservation", result.source().preservation().name())
                .putString("preservedCopy", result.source().storedPath().map(Path::toString))
                .put("resumed", result.resumedFromCheckpoint())
                .put("sourceCompleteness", result.sourceCompleteness().name())
                .putObjects("sources", sources)
                .put("eventCount", snapshot.eventCount())
                .put("recordsJournaledThisRun", result.recordsJournaled())
                .put("eventsNormalizedThisRun", result.eventsNormalized())
                .put("eventsInsertedThisRun", result.ingest().isPresent()
                        ? OptionalLong.of(result.ingest().get().eventsInserted())
                        : OptionalLong.empty())
                .put("duplicatesIgnoredThisRun", result.ingest().isPresent()
                        ? OptionalLong.of(result.ingest().get().duplicatesIgnored())
                        : OptionalLong.empty())
                .put("decodeStatusCounts", decode.build())
                .put("diagnosticCount", snapshot.diagnosticCount())
                .put("diagnosticCountsByCode", diagnosticCodes.build())
                .put("damageOffset", result.damageOffset())
                .put("peakBufferBytes", result.peakBufferBytes())
                .put("elapsedMillis", elapsedNanos / 1_000_000L)
                .put("resumable", result.outcome().isResumable())
                .putStrings("warnings", manifest.warnings())
                .build();
    }

    private static ExitCode fail(PrintStream err, String message) {
        err.println("bbv " + NAME + ": " + message);
        err.flush();
        return ExitCode.FAILED;
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }

    // -------------------------------------------------------------------- help

    static void printHelp(PrintStream out) {
        out.println("usage: bbv import <bep-file> [options]");
        out.println();
        out.println("Imports a binary or JSON build event protocol file into a managed session.");
        out.println("The format is detected from the file's content, never its name; the source");
        out.println("and its SHA-256 are recorded before anything is decoded; every record is");
        out.println("written to the session's raw journal verbatim before it is normalized; and");
        out.println("the result is indexed into the session's SQLite database.");
        out.println();
        out.println("Progress is drawn on stderr, the summary is written to stdout, and errors go");
        out.println("to stderr in plain text even under --json.");
        out.println();
        out.println("options:");
        out.println("  --sessions-root DIR  where managed sessions are kept");
        out.println("                       (default: the application's managed-sessions directory)");
        out.println("  --name NAME          derive the session's identity from NAME instead of");
        out.println("                       generating a random one, so a scripted re-import lands");
        out.println("                       in a predictable directory. A NAME that already is a");
        out.println("                       UUID is used as the session id verbatim.");
        out.println("  --resume             continue an interrupted import instead of starting a");
        out.println("                       new session. Reading continues at the recorded byte");
        out.println("                       offset; the file is never re-read from the start.");
        out.println("                       Without --name the session is found by matching");
        out.println("                       <bep-file> against each interrupted session's sources.");
        out.println("  --json               print the summary as a JSON object instead of text");
        out.println("  --quiet              do not draw progress (the summary still prints)");
        out.println("  --help               show this message");
        out.println();
        out.println("exit codes:");
        out.println("  0  imported completely");
        out.println("  1  the source is truncated or corrupt: everything before the damage was");
        out.println("     imported, the byte offset is in the session's diagnostics, and the");
        out.println("     session is marked INCOMPLETE or CORRUPT_PARTIAL rather than READY");
        out.println("  2  the command line was wrong");
        out.println("  3  the import failed outright and produced no usable session");
        out.println("  4  the import was cancelled and the session left resumable (--resume)");
        out.println();
        out.println("Ctrl-C leaves the session resumable in exactly the same way, but the");
        out.println("shell reports 130 rather than 4: a JVM killed by SIGINT exits with");
        out.println("128 plus the signal number, and a shutdown hook cannot change that.");
        out.println("Scripts checking for an interrupted import should accept either.");
        out.flush();
    }
}
