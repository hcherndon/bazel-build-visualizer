package com.holtherndon.bazelviz.ui.errors;

import com.holtherndon.bazelviz.storage.entities.ErrorRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileResolver;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.util.List;
import java.util.Objects;

/**
 * Describes one Errors-card row for the shared inspector.
 *
 * <p>The message is Bazel's own text, shown verbatim. It is never parsed — its
 * wording changes between versions — and for a compiler error it is often the
 * only structured thing there is, because a syntax error produces thirteen
 * events and zero structured diagnostics.
 *
 * <h2>Console rows keep their text somewhere else</h2>
 *
 * <p>An {@link ErrorRow.Kind#OUTPUT} row has no message column to show: its
 * text is {@code progress.stderr}, which stays in the journal (ADR-004) and is
 * read back when the row is selected. So this class takes the text as a second
 * argument rather than reading it — the read is I/O and belongs on
 * {@code ErrorsView}'s executor — and renders whichever of the four states that
 * read is in. The one thing it never does is render an absent read as an empty
 * one: "still reading", "the journal does not have it" and "the event carried
 * no console text" are three different facts and each says which it is.
 */
public final class ErrorInspection {

    /**
     * Lines of console text rendered before the inspector stops.
     *
     * <p>A cap, and one that is stated when it fires. Nothing bounds how much
     * a build writes to its console, and a pane that quietly showed the first
     * screenful would be indistinguishable from a build whose diagnostic was
     * that short.
     */
    static final int MAX_LINES = 400;

    private ErrorInspection() {}

    /**
     * What is known about one row's console text.
     *
     * <p>Four states rather than a nullable string, because the difference
     * between them is the whole point: a user looking at a diagnostic that is
     * not there needs to know whether it is coming, whether it was never
     * captured, or whether Bazel wrote nothing.
     */
    public sealed interface Console {

        /** This row has no console text to read — every kind but OUTPUT. */
        static Console none() {
            return new None();
        }

        /** The read has been started on a background thread and has not landed. */
        static Console reading() {
            return new Reading();
        }

        /** The bytes could not be reached, and this is what went wrong. */
        static Console unavailable(String why) {
            return new Unavailable(why);
        }

        /** The event decoded; this is what it says the console received. */
        static Console text(String stderr, String stdout) {
            return new Text(stderr, stdout);
        }

        /** @see #none() */
        record None() implements Console {}

        /** @see #reading() */
        record Reading() implements Console {}

        /** @see #unavailable(String) */
        record Unavailable(String why) implements Console {
            public Unavailable {
                Objects.requireNonNull(why, "why");
            }
        }

        /** @see #text(String, String) */
        record Text(String stderr, String stdout) implements Console {
            public Text {
                Objects.requireNonNull(stderr, "stderr");
                Objects.requireNonNull(stdout, "stdout");
            }
        }
    }

    /** A row on its own, with nothing read from the journal for it. */
    public static Inspection of(ErrorRow row) {
        return of(row, Console.none());
    }

    /** A row together with whatever state its console read has reached. */
    public static Inspection of(ErrorRow row, Console console) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(console, "console");
        Inspection.Builder builder = new Inspection.Builder(row.subject())
                .subtitle(row.kind().title())
                .sourceEvent(row.bepEventId());
        if (row.kind() == ErrorRow.Kind.ACTION) {
            builder.ref(new EntityRef.ActionId(row.id()));
        }
        WorkspaceFileResolver.mainRepositoryLabel(row.subject())
                .ifPresent(label -> builder.ref(new EntityRef.TargetLabel(label)));
        row.bepEventId().ifPresent(eventId ->
                builder.ref(new EntityRef.EventId(eventId)));

        builder.section("Failure")
                .field("Kind", row.kind().title())
                .field("Subject", row.subject())
                .field(EntityFormat.field(
                        row.kind() == ErrorRow.Kind.NOT_BUILT ? "Reason" : "Category",
                        row.detail()))
                .field(messageField(row));

        addConsole(builder, console);

        if (row.kind() == ErrorRow.Kind.NOT_BUILT) {
            // Worth saying out loud: under --nokeep_going this row is a
            // statement about a sibling's failure, not about this target.
            builder.section("What this means").field(
                    "Not built",
                    "Bazel did not attempt this target. Under --nokeep_going one broken target"
                            + " aborts its siblings, so this may say more about another target"
                            + " than about this one.");
        }
        return builder.build();
    }

    /**
     * The Message field, which for a console row is a pointer rather than a
     * blank.
     *
     * <p>"unknown" would be a lie here: the text is known, it is simply not in
     * this row. Saying where it is turns a dead end into a direction.
     */
    private static Inspection.Field messageField(ErrorRow row) {
        if (row.message().filter(text -> !text.isEmpty()).isPresent()) {
            return Inspection.Field.of("Message", row.message().orElseThrow());
        }
        if (row.rawLocation().isPresent()) {
            return Inspection.Field.unknown("Message",
                    "this row carries no message column; the text Bazel wrote is in the journal"
                            + " and is shown below");
        }
        return Inspection.Field.unknown("Message");
    }

    private static void addConsole(Inspection.Builder builder, Console console) {
        switch (console) {
            case Console.None ignored -> { }
            case Console.Reading ignored -> builder.section("Console output")
                    .unknown("Text", "reading it back from the journal…");
            case Console.Unavailable unavailable -> builder.section("Console output")
                    .unknown("Text", unavailable.why());
            case Console.Text text -> addText(builder, text);
        }
    }

    private static void addText(Inspection.Builder builder, Console.Text text) {
        if (text.stderr().isEmpty() && text.stdout().isEmpty()) {
            // The bytes were read and decoded and there was nothing in them.
            // That is an answer about the event, not a failure of the read, and
            // the two must not look alike.
            builder.section("Console output")
                    .unknown("Text", "this event decoded and carried no console text");
            return;
        }
        if (!text.stderr().isEmpty()) {
            addStream(builder, "Console output (stderr)", text.stderr());
        }
        if (!text.stdout().isEmpty()) {
            addStream(builder, "Console output (stdout)", text.stdout());
        }
    }

    /**
     * One stream, a line per field.
     *
     * <p>Line per field rather than one field holding the whole string: the
     * inspector renders a field's value in a single label, so a multi-line
     * diagnostic in one field would come out as one unreadable run. The line
     * number is the field name, which also keeps two identical lines visibly
     * distinct.
     */
    private static void addStream(Inspection.Builder builder, String heading, String stream) {
        List<String> lines = linesOf(stream);
        builder.section(heading);
        for (int i = 0; i < Math.min(lines.size(), MAX_LINES); i++) {
            builder.field(Integer.toString(i + 1), lines.get(i));
        }
        if (lines.size() > MAX_LINES) {
            builder.unknown("…", (lines.size() - MAX_LINES) + " of " + lines.size()
                    + " lines are not shown: this pane is limited to " + MAX_LINES
                    + ". The event is stored complete in the journal.");
        }
    }

    /**
     * The stream split for display, carriage returns dropped and one trailing
     * newline absorbed.
     *
     * <p>Bazel ends a console chunk with a newline; rendering the empty string
     * after it as a line would add a blank row to every diagnostic. Any further
     * blank lines are the event's own and are kept.
     */
    private static List<String> linesOf(String stream) {
        String normalized = stream.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return List.of(normalized.split("\n", -1));
    }
}
