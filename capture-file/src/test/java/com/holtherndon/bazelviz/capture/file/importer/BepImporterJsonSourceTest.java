package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.testsupport.bep.BepJsonWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JSON half of exit criterion 1. Bazel's {@code --build_event_json_file}
 * fails differently from the binary format — there is no length prefix to
 * contradict itself, only structure — so truncation and corruption have to be
 * distinguished by their own evidence rather than by the binary parser's.
 */
class BepImporterJsonSourceTest {

    private static final int EVENT_COUNT = 100;

    @Test
    @DisplayName("a JSON file cut mid-object imports its prefix and is marked TRUNCATED")
    void truncatedJsonKeepsThePrefix(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.json");
        BepJsonWriter.write(source, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE,
                SyntheticBepStream.of(EVENT_COUNT));
        long full = Files.size(source);
        // Cut inside the final object, not at a record boundary.
        long lastNewline = lastNewlineOffset(source);
        long keep = lastNewline + 20;
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.WRITE)) {
            channel.truncate(keep);
        }
        assertThat(Files.size(source)).isLessThan(full);

        ImportResult result = importInto(temporary, source);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.TRUNCATED);
        assertThat(result.sourceCompleteness()).isEqualTo(Completeness.TRUNCATED);
        assertThat(result.sessionState()).isEqualTo(SessionState.INCOMPLETE);
        assertThat(result.eventsInDatabase()).isEqualTo(EVENT_COUNT - 1);
        assertThat(result.damageOffset()).hasValue(lastNewline + 1);

        assertThat(ImportTestSupport.readDiagnostics(result.sessionRoot()))
                .filteredOn(row -> row.code().equals(ImportDiagnosticCodes.SOURCE_TRUNCATED))
                .singleElement()
                .satisfies(row -> assertThat(row.byteOffset()).hasValue(lastNewline + 1));
    }

    @Test
    @DisplayName("junk between JSON records is CORRUPT_PARTIAL, and stops reading there")
    void malformedTopLevelIsCorrupt(@TempDir Path temporary) throws Exception {
        Path intact = temporary.resolve("intact.json");
        BepJsonWriter.write(intact, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE,
                SyntheticBepStream.of(EVENT_COUNT));
        String text = Files.readString(intact, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        StringBuilder damaged = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 30) {
                damaged.append("!! not a json object !!\n");
            }
            damaged.append(lines[i]).append('\n');
        }
        Path source = temporary.resolve("damaged.json");
        Files.writeString(source, damaged.toString(), StandardCharsets.UTF_8);

        ImportResult result = importInto(temporary, source);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CORRUPT_PARTIAL);
        assertThat(result.sourceCompleteness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
        assertThat(result.sourceCompleteness()).isNotEqualTo(Completeness.TRUNCATED);
        assertThat(result.sessionState()).isEqualTo(SessionState.CORRUPT_PARTIAL);
        assertThat(result.eventsInDatabase()).isEqualTo(30);
        assertThat(result.damageOffset()).isPresent();
        assertThat(ImportTestSupport.readDiagnostics(result.sessionRoot()))
                .anySatisfy(row -> {
                    assertThat(row.code()).isEqualTo(ImportDiagnosticCodes.MALFORMED_TOP_LEVEL);
                    assertThat(row.severity()).isEqualTo("ERROR");
                });
    }

    @Test
    @DisplayName("a record over the per-record limit is reported, never silently skipped")
    void oversizedRecordIsReported(@TempDir Path temporary) throws Exception {
        Path source = temporary.resolve("build.json");
        BepJsonWriter.write(source, BepJsonWriter.Layout.ONE_OBJECT_PER_LINE,
                SyntheticBepStream.of(EVENT_COUNT));
        int limit = shortestRecordLength(source) + 1;

        ImportResult result = new BepImporter(
                ImportTestSupport.sessionManager(temporary.resolve("sessions")),
                ImportTestSupport.deterministicOptions().withMaxRecordBytes(limit))
                .importFile(source);

        long dropped = EVENT_COUNT - result.eventsInDatabase();
        assertThat(dropped).as("the limit really did exclude something").isPositive();
        assertThat(ImportTestSupport.readDiagnostics(result.sessionRoot()))
                .filteredOn(row -> row.code().equals(ImportDiagnosticCodes.RECORD_TOO_LARGE))
                .as("every dropped record is named with its offset and true size")
                .hasSize((int) dropped);
        // A session that lost records must not present itself as plainly ready.
        assertThat(result.sessionState()).isEqualTo(SessionState.READY_WITH_WARNINGS);
    }

    private static long lastNewlineOffset(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        for (int i = bytes.length - 2; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        throw new IllegalStateException("no newline in " + file);
    }

    private static int shortestRecordLength(Path file) throws Exception {
        int shortest = Integer.MAX_VALUE;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                shortest = Math.min(shortest, line.getBytes(StandardCharsets.UTF_8).length);
            }
        }
        return shortest;
    }

    private static ImportResult importInto(Path temporary, Path source) throws Exception {
        return new BepImporter(
                ImportTestSupport.sessionManager(temporary.resolve("sessions-" + source.getFileName())),
                ImportTestSupport.deterministicOptions())
                .importFile(source);
    }
}
