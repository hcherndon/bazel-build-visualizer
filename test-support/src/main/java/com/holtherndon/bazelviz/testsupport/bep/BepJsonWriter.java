package com.holtherndon.bazelviz.testsupport.bep;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Writes protobuf-JSON BEP, the format behind {@code --build_event_json_file}.
 *
 * <h2>Two layouts, deliberately</h2>
 *
 * A JSON BEP file is a <em>concatenation</em> of JSON objects with no enclosing
 * array and no separator token, and the whitespace between them is not fixed:
 *
 * <ul>
 *   <li>{@link Layout#ONE_OBJECT_PER_LINE} — each event on a single line, the
 *       shape most third-party tooling emits and the one that tempts a parser
 *       into being a line reader;
 *   <li>{@link Layout#PRETTY_MULTILINE} — protobuf's default pretty printer,
 *       which spreads one event over many lines. Bazel itself writes this, so a
 *       line-oriented parser silently fails on real Bazel output.
 * </ul>
 *
 * A parser that handles only one of these handles neither in practice, which is
 * why both are produced from the same event source here: the two files must
 * decode to the same sequence of events.
 *
 * <p>Events are streamed from an iterator and written one at a time.
 *
 * <p>Note on Any: the generator never populates {@code Any}-typed fields
 * (notably {@code ActionExecuted.strategy_details}), so no {@link
 * JsonFormat.TypeRegistry} is required to print or parse these files.
 */
public final class BepJsonWriter {

    /** Whitespace layout of the emitted stream. */
    public enum Layout {
        /** One compact JSON object per line, newline-separated. */
        ONE_OBJECT_PER_LINE,
        /** Indented multi-line objects, concatenated — protobuf's default printer, as Bazel writes. */
        PRETTY_MULTILINE
    }

    private BepJsonWriter() {}

    /**
     * Writes {@code events} to {@code dest} in {@code layout}, UTF-8, creating
     * or truncating the file.
     *
     * @return the number of events written
     */
    public static long write(Path dest, Layout layout, Iterator<BuildEvent> events) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JsonFormat.Printer printer = printerFor(layout);
        long written = 0;
        try (Writer out = new BufferedWriter(
                new java.io.OutputStreamWriter(
                        Files.newOutputStream(
                                dest,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE),
                        StandardCharsets.UTF_8),
                1 << 16)) {
            while (events.hasNext()) {
                printer.appendTo(events.next(), out);
                out.write('\n');
                written++;
            }
        }
        return written;
    }

    /** Convenience: writes the whole of {@code stream} to {@code dest}. */
    public static long write(Path dest, Layout layout, SyntheticBepStream stream) throws IOException {
        return write(dest, layout, stream.iterator());
    }

    /** Renders a single event exactly as {@link #write} would render it, without the trailing newline. */
    public static String toJson(BuildEvent event, Layout layout) throws InvalidProtocolBufferException {
        return printerFor(layout).print(event);
    }

    /** The printer used for a layout. Exposed so tests can print expectations identically. */
    public static JsonFormat.Printer printerFor(Layout layout) {
        // sortingMapKeys is belt-and-braces: the generator sets no map fields,
        // but an unsorted map would silently make output non-reproducible.
        JsonFormat.Printer printer = JsonFormat.printer().sortingMapKeys();
        return layout == Layout.ONE_OBJECT_PER_LINE ? printer.omittingInsignificantWhitespace() : printer;
    }

    /** The parser counterpart, tolerating fields this build's protos do not know. */
    public static JsonFormat.Parser lenientParser() {
        return JsonFormat.parser().ignoringUnknownFields();
    }
}
