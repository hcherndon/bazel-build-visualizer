package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.bepcodec.BepPayloadType;
import com.holtherndon.bazelviz.capture.file.importer.JournalPayloadReader;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.format.session.SessionManifestCodec;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNull;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.AnnouncedChild;
import com.holtherndon.bazelviz.storage.events.DiagnosticEntry;
import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventIdentity;
import com.holtherndon.bazelviz.storage.events.EventPage;
import com.holtherndon.bazelviz.storage.events.EventQueries;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.storage.events.ImportDiagnostic;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * {@code bbv inspect} — reads a session back without the GUI.
 *
 * <p>This is the command that makes an import checkable. It answers three
 * questions and nothing else: what events are in there, what does one event
 * actually say including where its raw bytes live, and what did the importer
 * complain about.
 *
 * <p>Paging is keyset paging through {@link EventQueries}, never {@code OFFSET}.
 * That is not a style preference: {@code OFFSET n} makes SQLite walk and discard
 * n rows, so printing the tail of a ten-million-event session would get
 * quadratically slower the further in it went, and a session being written
 * concurrently would silently skip or repeat rows as earlier ones shifted. The
 * anchor is the last row id seen, so each page costs one index seek regardless
 * of how deep it is.
 *
 * <p>Rows are streamed page by page and printed as they arrive rather than
 * collected into a list first, so {@code --events 5000000} costs one page of
 * memory, not five million rows of it.
 */
final class InspectCommand {

    static final String NAME = "inspect";

    private static final Set<String> FLAGS = Set.of("diagnostics", "json", "help");
    private static final Set<String> OPTIONS = Set.of("events", "event", "after");

    /** Rows fetched per keyset query, independent of how many were asked for. */
    private static final int PAGE_SIZE = 500;

    /** Default number of events listed when {@code --events} is not given. */
    private static final long DEFAULT_EVENT_LIMIT = 20;

    /** Bytes of a raw payload shown as hex in an event's detail. */
    private static final int PAYLOAD_PREVIEW_BYTES = 48;

    /**
     * The event-list columns. Every field is {@code %s} so the same format
     * string prints the header and the rows, which is what keeps the two from
     * drifting apart. The widths fit the longest real value: a payload-case name
     * with its number, and a microsecond ISO-8601 timestamp.
     */
    private static final String ROW_FORMAT = "%10s %10s  %-28s %-14s %-28s %s%n";

    private InspectCommand() {}

    static ExitCode run(List<String> tokens, CliContext ctx) throws CliUsageException {
        Args args = Args.parse(NAME, tokens, FLAGS, OPTIONS);
        if (args.has("help")) {
            printHelp(ctx.out());
            return ExitCode.OK;
        }
        Path sessionRoot = sessionArgument(args);
        boolean json = args.has("json");
        OptionalLong singleEvent = args.nonNegativeLong("event");
        boolean diagnostics = args.has("diagnostics");
        if (singleEvent.isPresent() && diagnostics) {
            throw new CliUsageException(NAME, "--event and --diagnostics ask for different things;"
                    + " run the command twice");
        }
        if (singleEvent.isPresent() && args.value("events").isPresent()) {
            throw new CliUsageException(NAME,
                    "--event names one event and --events lists many; use one or the other");
        }
        long limit = args.positiveLong("events").orElse(DEFAULT_EVENT_LIMIT);
        OptionalLong after = args.nonNegativeLong("after");

        SessionManifest manifest;
        try {
            // Read directly through the codec rather than through SessionManager:
            // inspect is read-only and must never take the session's lock, so a
            // session being imported in another window can still be looked at.
            manifest = SessionManifestCodec.standard()
                    .read(ManagedSessionLayout.at(sessionRoot).manifestFile());
        } catch (IOException e) {
            return fail(ctx.err(), describe(e));
        }

        Path databaseFile = ManagedSessionLayout.at(sessionRoot).databaseFile();
        if (!Files.isRegularFile(databaseFile)) {
            return fail(ctx.err(), "session " + sessionRoot + " has no "
                    + ManagedSessionLayout.DATABASE_FILE_NAME + "; nothing was ever indexed into it");
        }

        try (SessionDatabase database = SessionDatabase.open(databaseFile)) {
            Connection connection = database.newReadConnection();
            try (EventQueries queries = new EventQueries(connection)) {
                if (singleEvent.isPresent()) {
                    return showEvent(queries, sessionRoot, manifest, singleEvent.getAsLong(), json, ctx);
                }
                if (diagnostics) {
                    return showDiagnostics(queries, sessionRoot, manifest, limit, after, json, ctx);
                }
                return showEvents(queries, sessionRoot, manifest, limit, after, json, ctx);
            }
        } catch (SQLException e) {
            return fail(ctx.err(), "cannot read " + databaseFile + ": " + describe(e));
        }
    }

    // --------------------------------------------------------------- arguments

    private static Path sessionArgument(Args args) throws CliUsageException {
        List<String> positionals = args.positionals();
        if (positionals.isEmpty()) {
            throw new CliUsageException(NAME, "no session directory given");
        }
        if (positionals.size() > 1) {
            throw new CliUsageException(NAME, "expected one session directory, got "
                    + positionals.size() + ": " + String.join(", ", positionals));
        }
        Path root;
        try {
            root = Path.of(positionals.get(0)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new CliUsageException(NAME,
                    "'" + positionals.get(0) + "' is not a usable path: " + e.getReason());
        }
        if (!Files.exists(root)) {
            throw new CliUsageException(NAME, "no such directory: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new CliUsageException(NAME, root + " is a file; 'bbv inspect' takes the session"
                    + " directory an import produced, and a BEP file is read with 'bbv import'");
        }
        if (!ManagedSessionLayout.at(root).isManagedSession()) {
            throw new CliUsageException(NAME, root + " is not a managed session (no "
                    + ManagedSessionLayout.MANIFEST_FILE_NAME + ")");
        }
        return root;
    }

    // ------------------------------------------------------------- event pages

    private static ExitCode showEvents(
            EventQueries queries,
            Path sessionRoot,
            SessionManifest manifest,
            long limit,
            OptionalLong after,
            boolean json,
            CliContext ctx)
            throws SQLException {
        PrintStream out = ctx.out();
        long total = queries.eventCount();
        List<JsonObject> jsonRows = json ? new ArrayList<>() : null;
        if (!json) {
            printHeader(out, sessionRoot, manifest, total);
            out.printf(Locale.ROOT, ROW_FORMAT, "id", "sequence", "type", "decode", "event time", "event id");
        }

        OptionalLong anchor = after;
        long printed = 0;
        OptionalLong nextAnchor = OptionalLong.empty();
        while (printed < limit) {
            int pageLimit = (int) Math.min(PAGE_SIZE, limit - printed);
            EventPage page = queries.pageForward(anchor, pageLimit);
            if (page.isEmpty()) {
                nextAnchor = OptionalLong.empty();
                break;
            }
            for (EventSummary event : page.events()) {
                if (json) {
                    jsonRows.add(eventJson(event, identityOf(queries, event)));
                } else {
                    printEventRow(out, event, identityOf(queries, event));
                }
                printed++;
            }
            anchor = page.nextAnchor();
            nextAnchor = page.nextAnchor();
            if (anchor.isEmpty()) {
                break;
            }
        }

        if (json) {
            out.print(JsonWriter.writePretty(sessionJson(sessionRoot, manifest, "events", total)
                    .putObjects("events", jsonRows)
                    .put("nextAfter", printed >= limit ? nextAnchor : OptionalLong.empty())
                    .build()));
        } else {
            out.println();
            if (printed == 0) {
                out.println("no events" + (after.isPresent() ? " after id " + after.getAsLong() : ""));
            } else {
                out.printf(Locale.ROOT, "%s of %s events shown%n",
                        Formatting.count(printed), Formatting.count(total));
                if (printed >= limit && nextAnchor.isPresent()) {
                    out.printf(Locale.ROOT, "next page: bbv inspect %s --after %d%n",
                            sessionRoot, nextAnchor.getAsLong());
                }
            }
        }
        out.flush();
        return ExitCode.OK;
    }

    private static void printEventRow(PrintStream out, EventSummary event, Optional<EventIdentity> identity) {
        out.printf(Locale.ROOT, ROW_FORMAT,
                event.id(),
                event.sequence(),
                typeLabel(event.eventType()),
                decodeLabel(event),
                event.eventMicros().isPresent()
                        ? Formatting.micros(event.eventMicros().getAsLong())
                        : Formatting.UNKNOWN,
                identity.map(EventIdentity::display).orElse(Formatting.hash(event.eventIdHash())));
    }

    private static String typeLabel(int eventType) {
        return BepPayloadType.name(eventType) + " (" + eventType + ")";
    }

    /** The decode status, with a marker when the payload carried unknown fields. */
    private static String decodeLabel(EventSummary event) {
        return event.decodeStatus().name() + (event.hasUnknownFields() ? "*" : "");
    }

    private static Optional<EventIdentity> identityOf(EventQueries queries, EventSummary event)
            throws SQLException {
        return event.eventIdHash().isPresent()
                ? queries.identity(event.eventIdHash().getAsLong())
                : Optional.empty();
    }

    // ------------------------------------------------------------ one event

    private static ExitCode showEvent(
            EventQueries queries,
            Path sessionRoot,
            SessionManifest manifest,
            long eventId,
            boolean json,
            CliContext ctx)
            throws SQLException {
        Optional<EventDetail> found = queries.event(eventId);
        if (found.isEmpty()) {
            long total = queries.eventCount();
            return fail(ctx.err(), "no event with id " + eventId + " in " + sessionRoot
                    + " (it holds " + Formatting.count(total) + " events)");
        }
        EventDetail detail = found.get();
        List<AnnouncedChild> children = queries.childrenOfEvent(eventId);
        RawLocation raw = detail.rawLocation();

        byte[] payload = null;
        String payloadError = null;
        try {
            payload = JournalPayloadReader.forSession(sessionRoot).read(raw);
        } catch (IOException e) {
            payloadError = describe(e);
        }

        PrintStream out = ctx.out();
        if (json) {
            out.print(JsonWriter.writePretty(sessionJson(sessionRoot, manifest, "event", queries.eventCount())
                    .put("event", eventDetailJson(detail, children, payload, payloadError))
                    .build()));
        } else {
            printHeader(out, sessionRoot, manifest, queries.eventCount());
            EventSummary summary = detail.summary();
            out.printf(Locale.ROOT, "event %d%n", summary.id());
            out.printf(Locale.ROOT, "  stream             %d%n", summary.streamId());
            out.printf(Locale.ROOT, "  sequence           %d%n", summary.sequence());
            out.printf(Locale.ROOT, "  type               %s%n", typeLabel(summary.eventType()));
            out.printf(Locale.ROOT, "  decode status      %s%n", summary.decodeStatus());
            out.printf(Locale.ROOT, "  unknown fields     %s%n", summary.hasUnknownFields() ? "yes" : "no");
            out.printf(Locale.ROOT, "  last message       %s%n", summary.lastMessage() ? "yes" : "no");
            out.printf(Locale.ROOT, "  announced children %d%n", summary.childCount());
            out.printf(Locale.ROOT, "  event time         %s%n", Formatting.micros(summary.eventMicros()));
            out.printf(Locale.ROOT, "  received           %s%n", Formatting.micros(summary.receiveMicros()));
            out.printf(Locale.ROOT, "  raw location       segment %d, offset %s, length %s%n",
                    raw.segment(), Formatting.count(raw.offset()), Formatting.count(raw.length()));
            out.printf(Locale.ROOT, "  raw file           %s%n",
                    ManagedSessionLayout.at(sessionRoot).journalSegment(raw.segment()));
            if (payload != null) {
                out.printf(Locale.ROOT, "  raw sha256         %s%n", sha256(payload));
                out.printf(Locale.ROOT, "  raw bytes          %s%n",
                        Formatting.hexPreview(payload, PAYLOAD_PREVIEW_BYTES));
            } else {
                out.printf(Locale.ROOT, "  raw bytes          unreadable: %s%n", payloadError);
            }
            detail.identity().ifPresentOrElse(identity -> {
                out.printf(Locale.ROOT, "  event id hash      %s%n", Formatting.hash(identity.hash()));
                out.printf(Locale.ROOT, "  event id kind      %d%n", identity.idKind());
                out.printf(Locale.ROOT, "  event id           %s%n", identity.display());
            }, () -> out.printf(Locale.ROOT, "  event id           %s (this event carries none)%n",
                    Formatting.UNKNOWN));
            if (!children.isEmpty()) {
                out.println("  children:");
                for (AnnouncedChild child : children) {
                    out.printf(Locale.ROOT, "    [%d] %s  %s%n",
                            child.ordinal(),
                            Formatting.hash(child.childEventIdHash()),
                            child.isMissing()
                                    ? "announced but never arrived"
                                    : "arrived as event " + child.arrivedEventId().getAsLong());
                }
            }
        }
        out.flush();
        // A raw payload that cannot be read back means the journal and the row
        // index disagree, which is exactly the thing this command exists to
        // catch. It is reported as a failure, not as a footnote.
        return payloadError == null ? ExitCode.OK : ExitCode.FAILED;
    }

    // ------------------------------------------------------------ diagnostics

    private static ExitCode showDiagnostics(
            EventQueries queries,
            Path sessionRoot,
            SessionManifest manifest,
            long limit,
            OptionalLong after,
            boolean json,
            CliContext ctx)
            throws SQLException {
        PrintStream out = ctx.out();
        List<JsonObject> jsonRows = json ? new ArrayList<>() : null;
        if (!json) {
            printHeader(out, sessionRoot, manifest, queries.eventCount());
            out.printf(Locale.ROOT, "%8s %-9s %-24s %14s  %s%n",
                    "id", "severity", "code", "byte offset", "message");
        }
        OptionalLong anchor = after;
        long printed = 0;
        while (printed < limit) {
            int pageLimit = (int) Math.min(PAGE_SIZE, limit - printed);
            List<DiagnosticEntry> page = queries.diagnosticsPage(anchor, pageLimit);
            if (page.isEmpty()) {
                break;
            }
            for (DiagnosticEntry entry : page) {
                ImportDiagnostic diagnostic = entry.diagnostic();
                if (json) {
                    jsonRows.add(Json.object()
                            .put("id", entry.id())
                            .put("severity", diagnostic.severity().name())
                            .put("code", diagnostic.code())
                            .put("message", diagnostic.message())
                            .put("segmentIndex", diagnostic.segmentIndex())
                            .put("byteOffset", diagnostic.byteOffset())
                            .put("atMicros", diagnostic.atMicros())
                            .build());
                } else {
                    out.printf(Locale.ROOT, "%8d %-9s %-24s %14s  %s%n",
                            entry.id(),
                            diagnostic.severity(),
                            diagnostic.code(),
                            Formatting.count(diagnostic.byteOffset()),
                            diagnostic.message());
                }
                printed++;
            }
            anchor = OptionalLong.of(page.get(page.size() - 1).id());
            if (page.size() < pageLimit) {
                break;
            }
        }
        if (json) {
            out.print(JsonWriter.writePretty(
                    sessionJson(sessionRoot, manifest, "diagnostics", queries.eventCount())
                            .putObjects("diagnostics", jsonRows)
                            .build()));
        } else {
            out.println();
            out.printf(Locale.ROOT, "%s diagnostics shown%n", Formatting.count(printed));
        }
        out.flush();
        return ExitCode.OK;
    }

    // ----------------------------------------------------------------- shared

    private static void printHeader(
            PrintStream out, Path sessionRoot, SessionManifest manifest, long eventCount) {
        out.printf(Locale.ROOT, "session %s%n", manifest.sessionId());
        out.printf(Locale.ROOT, "  directory   %s%n", sessionRoot);
        out.printf(Locale.ROOT, "  state       %s%n", manifest.state());
        out.printf(Locale.ROOT, "  app version %s%n", manifest.appVersion());
        out.printf(Locale.ROOT, "  events      %s%n", Formatting.count(eventCount));
        for (SessionManifest.CaptureSourceEntry entry : manifest.sources()) {
            out.printf(Locale.ROOT, "  source      %s  %s  %s  %s%n",
                    entry.kind(),
                    entry.completeness(),
                    Formatting.bytes(entry.byteSize()),
                    entry.path().orElse(Formatting.UNKNOWN));
        }
        for (String warning : manifest.warnings()) {
            out.printf(Locale.ROOT, "  warning     %s%n", warning);
        }
        out.println();
    }

    private static Json.Obj sessionJson(
            Path sessionRoot, SessionManifest manifest, String view, long eventCount) {
        List<JsonObject> sources = new ArrayList<>();
        for (SessionManifest.CaptureSourceEntry entry : manifest.sources()) {
            sources.add(Json.object()
                    .put("kind", entry.kind())
                    .putString("path", entry.path())
                    .putString("sha256", entry.sha256())
                    .put("byteSize", entry.byteSize())
                    .put("completeness", entry.completeness().name())
                    .build());
        }
        return Json.object()
                .put("command", NAME)
                .put("view", view)
                .put("sessionId", manifest.sessionId().toString())
                .put("sessionDirectory", sessionRoot.toString())
                .put("sessionState", manifest.state().name())
                .put("eventCount", eventCount)
                .putObjects("sources", sources)
                .putStrings("warnings", manifest.warnings());
    }

    private static JsonObject eventJson(EventSummary event, Optional<EventIdentity> identity) {
        return Json.object()
                .put("id", event.id())
                .put("streamId", event.streamId())
                .put("sequence", event.sequence())
                .put("eventType", event.eventType())
                .put("eventTypeName", BepPayloadType.name(event.eventType()))
                .put("decodeStatus", event.decodeStatus().name())
                .put("hasUnknownFields", event.hasUnknownFields())
                .put("lastMessage", event.lastMessage())
                .put("childCount", event.childCount())
                .put("eventMicros", event.eventMicros())
                .put("receiveMicros", event.receiveMicros())
                .put("eventIdHash", event.eventIdHash())
                .putString("eventIdDisplay", identity.map(EventIdentity::display))
                .build();
    }

    private static JsonObject eventDetailJson(
            EventDetail detail, List<AnnouncedChild> children, byte[] payload, String payloadError) {
        RawLocation raw = detail.rawLocation();
        List<JsonObject> childJson = new ArrayList<>();
        for (AnnouncedChild child : children) {
            childJson.add(Json.object()
                    .put("ordinal", child.ordinal())
                    .put("childEventIdHash", child.childEventIdHash())
                    .put("arrivedEventId", child.arrivedEventId())
                    .build());
        }
        Json.Obj rawJson = Json.object()
                .put("segment", raw.segment())
                .put("offset", raw.offset())
                .put("length", raw.length());
        if (payload != null) {
            rawJson.put("sha256", sha256(payload))
                    .put("prefixHex", Formatting.hex(
                            java.util.Arrays.copyOf(payload, Math.min(PAYLOAD_PREVIEW_BYTES, payload.length))));
        } else {
            rawJson.put("sha256", (String) null).put("error", payloadError);
        }
        Json.Obj event = Json.object();
        eventJson(detail.summary(), detail.identity()).members().forEach(event::put);
        JsonValue identityJson = detail.identity()
                .<JsonValue>map(identity -> Json.object()
                        .put("hash", identity.hash())
                        .put("idKind", identity.idKind())
                        .put("idByteLength", identity.idBytes().length)
                        .put("display", identity.display())
                        .build())
                .orElse(JsonNull.INSTANCE);
        return event
                .put("raw", rawJson.build())
                .put("identity", identityJson)
                .putObjects("children", childJson)
                .build();
    }

    private static String sha256(byte[] data) {
        try {
            return Formatting.hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
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
                : message;
    }

    // -------------------------------------------------------------------- help

    static void printHelp(PrintStream out) {
        out.println("usage: bbv inspect <session-dir> [options]");
        out.println();
        out.println("Reads back a session that 'bbv import' produced. With no view option it");
        out.println("lists events in arrival order; --event shows one event in full, including");
        out.println("the journal segment, offset and length of its raw bytes; --diagnostics");
        out.println("lists what the importer recorded about the source.");
        out.println();
        out.println("Paging is keyset paging anchored on the last row id, never SQL OFFSET, so");
        out.println("a page deep in a large session costs the same as the first one.");
        out.println();
        out.println("options:");
        out.println("  --events N     list at most N events (default 20)");
        out.println("  --after ID     start after this event id, from a previous page's");
        out.println("                 'next page' line; with --diagnostics, after this");
        out.println("                 diagnostic id");
        out.println("  --event ID     show one event in full instead of a list");
        out.println("  --diagnostics  list import diagnostics instead of events");
        out.println("  --json         print a JSON object instead of text");
        out.println("  --help         show this message");
        out.println();
        out.println("exit codes:");
        out.println("  0  the session was read");
        out.println("  2  the command line was wrong, or the directory is not a managed session");
        out.println("  3  the session could not be read, or an event's raw bytes could not be");
        out.println("     found in the journal at the location its row records");
        out.flush();
    }
}
