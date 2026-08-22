package com.holtherndon.bazelviz.testsupport.bep;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Both JSON layouts must decode to exactly the same events, because the JSON
 * parser has to accept both and a fixture that only exercises one would let a
 * line-oriented parser pass.
 */
final class BepJsonWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void oneObjectPerLineDecodesBackToTheSameEvents() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(97);
        Path file = tempDir.resolve("build.jsonl.json");

        long written = BepJsonWriter.write(file, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE, stream);
        assertThat(written).isEqualTo(stream.eventCount());

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(stream.eventCount());

        List<BuildEvent> decoded = new ArrayList<>();
        JsonFormat.Parser parser = BepJsonWriter.lenientParser();
        for (String line : lines) {
            assertThat(line).startsWith("{").endsWith("}");
            BuildEvent.Builder builder = BuildEvent.newBuilder();
            parser.merge(line, builder);
            decoded.add(builder.build());
        }

        assertThat(decoded).isEqualTo(stream.events().toList());
    }

    @Test
    void prettyLayoutIsGenuinelyMultiLineAndDecodesBackToTheSameEvents() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(97);
        Path file = tempDir.resolve("build.pretty.json");

        BepJsonWriter.write(file, BepJsonWriter.Layout.PRETTY_MULTILINE, stream);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        long lineCount = text.lines().count();
        // The point of this layout: many more lines than events, so a
        // line-per-record parser cannot possibly read it.
        assertThat(lineCount).isGreaterThan(stream.eventCount() * 5L);

        List<BuildEvent> decoded = decodeConcatenatedObjects(text);
        assertThat(decoded).isEqualTo(stream.events().toList());
    }

    @Test
    void bothLayoutsCarryIdenticalContent() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(56);
        Path lines = tempDir.resolve("lines.json");
        Path pretty = tempDir.resolve("pretty.json");

        BepJsonWriter.write(lines, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE, stream);
        BepJsonWriter.write(pretty, BepJsonWriter.Layout.PRETTY_MULTILINE, stream);

        List<BuildEvent> fromLines = decodeConcatenatedObjects(Files.readString(lines, StandardCharsets.UTF_8));
        List<BuildEvent> fromPretty = decodeConcatenatedObjects(Files.readString(pretty, StandardCharsets.UTF_8));

        assertThat(fromLines).isEqualTo(fromPretty);
        assertThat(Files.size(pretty)).isGreaterThan(Files.size(lines));
    }

    @Test
    void jsonUsesProtobufJsonNamesAndNotProtoFieldNames() throws IOException {
        String json = BepJsonWriter.toJson(
                SyntheticBepStream.of(21).eventAt(20), BepJsonWriter.Layout.ONE_OBJECT_PER_LINE);

        // Bazel's JSON transport emits lowerCamelCase json_names; a parser keyed
        // on proto snake_case names would silently drop every field.
        assertThat(json).contains("\"buildToolLogs\"");
        assertThat(json).contains("\"lastMessage\":true");
        assertThat(json).doesNotContain("\"build_tool_logs\"");
    }

    @Test
    void writingTwiceProducesIdenticalText() throws IOException {
        Path a = tempDir.resolve("a.json");
        Path b = tempDir.resolve("b.json");
        BepJsonWriter.write(a, BepJsonWriter.Layout.PRETTY_MULTILINE, SyntheticBepStream.of(40));
        BepJsonWriter.write(b, BepJsonWriter.Layout.PRETTY_MULTILINE, SyntheticBepStream.of(40));

        assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
    }

    /**
     * Splits a concatenation of JSON objects on brace depth, ignoring braces
     * inside string literals. Deliberately independent of the writer: it makes
     * no assumption about where newlines fall, which is the property under test.
     */
    static List<BuildEvent> decodeConcatenatedObjects(String text) throws IOException {
        List<BuildEvent> events = new ArrayList<>();
        JsonFormat.Parser parser = BepJsonWriter.lenientParser();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                }
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        BuildEvent.Builder builder = BuildEvent.newBuilder();
                        parser.merge(text.substring(start, i + 1), builder);
                        events.add(builder.build());
                    }
                }
                default -> { }
            }
        }
        return events;
    }
}
