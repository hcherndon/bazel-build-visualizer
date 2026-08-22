package com.holtherndon.bazelviz.capture.file.json;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.capture.file.json.JsonParseDiagnostic.Code;
import com.holtherndon.bazelviz.capture.file.json.JsonParseDiagnostic.Severity;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class JsonBepParserTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- layouts

    @ParameterizedTest(name = "readBuffer={0}")
    @ValueSource(ints = {512, 1024, 8192, 65536})
    void oneObjectPerLineAndPrettyPrintedYieldTheSameEvents(int readBuffer) throws IOException {
        List<BuildEvent> expected = JsonFixtures.sampleEvents();

        CollectingListener perLine = new CollectingListener();
        JsonBepParseResult perLineResult = parse(
                JsonFixtures.oneObjectPerLine(expected), readBuffer, perLine);

        CollectingListener pretty = new CollectingListener();
        JsonBepParseResult prettyResult = parse(
                JsonFixtures.prettyPrinted(expected), readBuffer, pretty);

        CollectingListener backToBack = new CollectingListener();
        JsonBepParseResult backToBackResult = parse(
                JsonFixtures.prettyPrintedBackToBack(expected), readBuffer, backToBack);

        assertThat(perLine.events()).containsExactlyElementsOf(expected);
        assertThat(pretty.events()).containsExactlyElementsOf(expected);
        assertThat(backToBack.events()).containsExactlyElementsOf(expected);

        for (JsonBepParseResult result :
                List.of(perLineResult, prettyResult, backToBackResult)) {
            assertThat(result.deliveredRecordCount()).isEqualTo(expected.size());
            assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
            assertThat(result.oversizedRecordCount()).isZero();
            assertThat(result.failedDecodeCount()).isZero();
        }
        assertThat(perLine.diagnostics()).isEmpty();
        assertThat(pretty.diagnostics()).isEmpty();
        assertThat(backToBack.diagnostics()).isEmpty();
    }

    @Test
    void bracesAndEscapedQuotesInsideStringValuesDoNotBreakSplitting() throws IOException {
        List<BuildEvent> expected = JsonFixtures.sampleEvents();
        String content = JsonFixtures.oneObjectPerLine(expected);

        // The fixture must really contain both hazards, or this proves nothing:
        // structural braces inside a string value, and escaped quotes.
        assertThat(content).contains("} {");
        assertThat(content).contains("\\\"");

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = parse(content, 512, listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(4);
        assertThat(listener.records().get(1).event().getUnstructuredCommandLine().getArgsList())
                .containsExactly("build", "//...", JsonFixtures.HAZARDOUS_COMMAND_ARG);
    }

    // ---------------------------------------------------------------- offsets

    @Test
    void reportedOffsetsAndLengthsSliceTheFileExactly() throws IOException {
        List<BuildEvent> expected = JsonFixtures.sampleEvents();
        Path file = write("offsets.json", JsonFixtures.prettyPrinted(expected));
        byte[] whole = Files.readAllBytes(file);

        CollectingListener listener = new CollectingListener();
        JsonBepParser parser = new JsonBepParser(
                JsonBepParserOptions.defaults().withReadBufferBytes(512));
        parser.parse(file, listener);

        assertThat(listener.records()).hasSize(expected.size());
        for (int i = 0; i < listener.records().size(); i++) {
            JsonBepRecord record = listener.records().get(i);
            byte[] slice = Arrays.copyOfRange(
                    whole, (int) record.byteOffset(), (int) record.endOffset());

            assertThat(slice)
                    .as("record %d bytes at [%d,%d)", i, record.byteOffset(), record.endOffset())
                    .isEqualTo(listener.rawAt(i));
            assertThat(record.byteLength()).isEqualTo(slice.length);
            assertThat(slice[0]).isEqualTo((byte) '{');
            assertThat(slice[slice.length - 1]).isEqualTo((byte) '}');

            // Re-parsing the slice on its own must produce the same event, which
            // is only possible if the boundary was exactly right.
            CollectingListener isolated = new CollectingListener();
            JsonBepParseResult reparsed = new JsonBepParser()
                    .parse(new ByteArrayInputStream(slice), isolated);
            assertThat(reparsed.deliveredRecordCount()).isEqualTo(1);
            assertThat(reparsed.completeness()).isEqualTo(Completeness.COMPLETE);
            assertThat(isolated.records().get(0).event()).isEqualTo(expected.get(i));
        }

        // Records tile the file: only whitespace separates them.
        long cursor = 0;
        for (JsonBepRecord record : listener.records()) {
            String gap = new String(
                    whole, (int) cursor, (int) (record.byteOffset() - cursor),
                    StandardCharsets.UTF_8);
            assertThat(gap.isBlank()).isTrue();
            cursor = record.endOffset();
        }
    }

    @Test
    void ordinalsFollowFileOrderAndCountRecordsThatCouldNotBeDelivered() throws IOException {
        String small = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}";
        String huge = "{\"id\":{\"progress\":{\"opaqueCount\":2}},\"progress\":{\"stderr\":\""
                + "x".repeat(600) + "\"}}";
        String content = small + "\n" + huge + "\n" + small + "\n";

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(JsonBepParserOptions.defaults()
                        .withMaxRecordBytes(256)
                        .withReadBufferBytes(512))
                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(2);
        assertThat(result.oversizedRecordCount()).isEqualTo(1);
        assertThat(result.delimitedRecordCount()).isEqualTo(3);
        assertThat(listener.records()).extracting(JsonBepRecord::ordinal).containsExactly(0L, 2L);
    }

    // -------------------------------------------------------- unknown fields

    @Test
    void aRecordWithAnUnknownFieldStillParsesAndIsFlagged() throws IOException {
        String content = "{\"id\":{\"progress\":{\"opaqueCount\":7}},"
                + "\"progress\":{\"stderr\":\"hi\"},"
                + "\"quantumEntanglementMetrics\":{\"qubits\":12}}\n";

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = parse(content, 512, listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(1);
        assertThat(result.unknownFieldRecordCount()).isEqualTo(1);
        assertThat(result.failedDecodeCount()).isZero();
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);

        JsonBepRecord record = listener.records().get(0);
        assertThat(record.decodeStatus()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
        assertThat(record.event().getProgress().getStderr()).isEqualTo("hi");
        assertThat(record.event().getId().getProgress().getOpaqueCount()).isEqualTo(7);
        // The unknown content is preserved verbatim, not dropped (ADR-004).
        assertThat(new String(record.rawBytes(), StandardCharsets.UTF_8))
                .contains("quantumEntanglementMetrics");

        JsonParseDiagnostic diagnostic = listener.only(Code.UNKNOWN_FIELDS).orElseThrow();
        assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
        assertThat(diagnostic.byteOffset()).isZero();
        assertThat(diagnostic.message()).contains("quantumEntanglementMetrics");
    }

    @Test
    void anUndecodableRecordIsStillDeliveredWithItsRawBytes() throws IOException {
        String bad = "{\"id\":{\"progress\":{\"opaqueCount\":\"seven\"}}}";
        String good = "{\"id\":{\"progress\":{\"opaqueCount\":8}}}";

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = parse(bad + "\n" + good + "\n", 512, listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(2);
        assertThat(result.failedDecodeCount()).isEqualTo(1);
        // A record this build cannot interpret does not end the import.
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);

        JsonBepRecord failed = listener.records().get(0);
        assertThat(failed.decodeStatus()).isEqualTo(DecodeStatus.FAILED);
        assertThat(failed.decodedEvent()).isEmpty();
        assertThat(failed.decodeDetail()).isPresent();
        assertThat(new String(failed.rawBytes(), StandardCharsets.UTF_8)).isEqualTo(bad);

        assertThat(listener.records().get(1).decodeStatus()).isEqualTo(DecodeStatus.OK);
        assertThat(listener.only(Code.DECODE_FAILED).orElseThrow().severity())
                .isEqualTo(Severity.ERROR);
    }

    @Test
    void decodingCanBeSkippedAndSaysSoRatherThanClaimingSuccess() throws IOException {
        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(
                        JsonBepParserOptions.defaults().withDecodeEvents(false))
                .parse(new ByteArrayInputStream(
                        JsonFixtures.oneObjectPerLine(JsonFixtures.sampleEvents())
                                .getBytes(StandardCharsets.UTF_8)),
                        listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(4);
        assertThat(listener.records())
                .allSatisfy(r -> {
                    assertThat(r.decodeStatus()).isEqualTo(DecodeStatus.NOT_ATTEMPTED);
                    assertThat(r.decodedEvent()).isEmpty();
                    assertThat(r.rawBytes()).isNotEmpty();
                });
    }

    // ------------------------------------------------------------- size limit

    @Test
    void anOversizedRecordIsReportedWithItsTrueSizeAndParsingContinues() throws IOException {
        String small = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}";
        String huge = "{\"id\":{\"progress\":{\"opaqueCount\":2}},\"progress\":{\"stderr\":\""
                + "x".repeat(4000) + "\"}}";
        String content = small + "\n" + huge + "\n" + small + "\n";
        int maxRecordBytes = 1024;

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(JsonBepParserOptions.defaults()
                        .withMaxRecordBytes(maxRecordBytes)
                        .withReadBufferBytes(512))
                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), listener);

        JsonParseDiagnostic diagnostic = listener.only(Code.RECORD_TOO_LARGE).orElseThrow();
        assertThat(diagnostic.severity()).isEqualTo(Severity.ERROR);
        assertThat(diagnostic.byteOffset()).isEqualTo(small.length() + 1L);
        assertThat(diagnostic.recordLength()).hasValue(huge.length());
        assertThat(diagnostic.message()).contains(String.valueOf(maxRecordBytes));

        // Reported, not skipped: the records on both sides still arrive, at
        // their real offsets, so the limit costs exactly one record and the
        // user is told which one.
        assertThat(result.deliveredRecordCount()).isEqualTo(2);
        assertThat(result.oversizedRecordCount()).isEqualTo(1);
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(listener.records()).extracting(JsonBepRecord::byteOffset)
                .containsExactly(0L, small.length() + 1L + huge.length() + 1L);

        // And no unbounded allocation happened on the way. The bound is twice
        // the record limit, not once: a completed record is copied out of the
        // accumulator, so the accumulator and the delivered copy are briefly
        // resident together. The reported peak counts both, because it is what
        // the bounded-memory claim is measured against.
        assertThat(result.peakRecordBufferBytes()).isLessThanOrEqualTo(2 * maxRecordBytes);
    }

    @Test
    void aRecordExactlyAtTheLimitIsAccepted() throws IOException {
        String record = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}";

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(JsonBepParserOptions.defaults()
                        .withMaxRecordBytes(record.length())
                        .withReadBufferBytes(512))
                .parse(new ByteArrayInputStream(record.getBytes(StandardCharsets.UTF_8)), listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(1);
        assertThat(result.oversizedRecordCount()).isZero();
        assertThat(listener.diagnostics()).isEmpty();
    }

    // ------------------------------------------------------------ truncation

    @Test
    void aTruncatedFinalObjectIsReportedAndEveryPriorRecordIsDelivered() throws IOException {
        List<BuildEvent> expected = JsonFixtures.sampleEvents();
        String full = JsonFixtures.oneObjectPerLine(expected);
        byte[] fullBytes = full.getBytes(StandardCharsets.UTF_8);

        // Offset of the final record: everything before it plus its newlines.
        String[] lines = full.split("\n", -1);
        long lastStart = 0;
        for (int i = 0; i < lines.length - 2; i++) {
            lastStart += lines[i].getBytes(StandardCharsets.UTF_8).length + 1;
        }
        int lastLineBytes = lines[lines.length - 2].getBytes(StandardCharsets.UTF_8).length;
        int cut = (int) lastStart + lastLineBytes / 2;
        byte[] truncated = Arrays.copyOf(fullBytes, cut);

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(
                        JsonBepParserOptions.defaults().withReadBufferBytes(512))
                .parse(new ByteArrayInputStream(truncated), listener);

        assertThat(result.completeness()).isEqualTo(Completeness.TRUNCATED);
        assertThat(result.deliveredRecordCount()).isEqualTo(expected.size() - 1);
        assertThat(listener.events())
                .containsExactlyElementsOf(expected.subList(0, expected.size() - 1));
        assertThat(result.truncatedTailOffset()).hasValue(lastStart);
        assertThat(result.truncatedTailBytes()).hasValue(cut - lastStart);
        assertThat(result.bytesRead()).isEqualTo(truncated.length);

        JsonParseDiagnostic diagnostic = listener.only(Code.TRUNCATED_TAIL).orElseThrow();
        assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
        assertThat(diagnostic.byteOffset()).isEqualTo(lastStart);
    }

    @Test
    void truncationInsideAStringLiteralIsStillTruncationNotCorruption() throws IOException {
        String content = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}\n"
                + "{\"progress\":{\"stderr\":\"half a mess";

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = parse(content, 512, listener);

        assertThat(result.completeness()).isEqualTo(Completeness.TRUNCATED);
        assertThat(result.deliveredRecordCount()).isEqualTo(1);
        assertThat(result.truncatedTailOffset()).hasValue(38L);
        assertThat(listener.only(Code.TRUNCATED_TAIL).orElseThrow().message())
                .contains("inside a string literal");
    }

    @Test
    void aTruncatedTailThatIsAlsoOversizedIsNotDelivered() throws IOException {
        String content = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}\n"
                + "{\"progress\":{\"stderr\":\"" + "y".repeat(3000);

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(JsonBepParserOptions.defaults()
                        .withMaxRecordBytes(512)
                        .withReadBufferBytes(512))
                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), listener);

        assertThat(result.completeness()).isEqualTo(Completeness.TRUNCATED);
        assertThat(result.deliveredRecordCount()).isEqualTo(1);
        assertThat(result.truncatedTailBytes()).hasValue(content.length() - 38L);
        // Twice the limit: accumulator plus the copy delivered from it.
        assertThat(result.peakRecordBufferBytes()).isLessThanOrEqualTo(2 * 512);
    }

    // -------------------------------------------------------- odd but legal

    @Test
    void anEmptySourceIsCompleteAndSaysItIsEmpty() throws IOException {
        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = parse("", 512, listener);

        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.deliveredRecordCount()).isZero();
        assertThat(result.bytesRead()).isZero();
        assertThat(listener.only(Code.EMPTY_INPUT)).isPresent();
    }

    @Test
    void aByteOrderMarkIsSkippedAndReportedAndDoesNotShiftOffsets() throws IOException {
        String record = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}";
        byte[] content = new byte[3 + record.length()];
        content[0] = (byte) 0xEF;
        content[1] = (byte) 0xBB;
        content[2] = (byte) 0xBF;
        System.arraycopy(record.getBytes(StandardCharsets.UTF_8), 0, content, 3, record.length());

        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = new JsonBepParser(
                        JsonBepParserOptions.defaults().withReadBufferBytes(512))
                .parse(new ByteArrayInputStream(content), listener);

        assertThat(listener.only(Code.BYTE_ORDER_MARK_SKIPPED)).isPresent();
        assertThat(result.deliveredRecordCount()).isEqualTo(1);
        // Offsets stay absolute from byte zero of the file, BOM included.
        assertThat(listener.records().get(0).byteOffset()).isEqualTo(3);
        assertThat(result.bytesRead()).isEqualTo(content.length);
    }

    @Test
    void commaSeparatedObjectsAreReadAndTheOddityIsReportedOnce() throws IOException {
        String record = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}";
        CollectingListener listener = new CollectingListener();

        JsonBepParseResult result =
                parse(record + " ,\n" + record + " ,\n" + record, 512, listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(3);
        assertThat(listener.only(Code.COMMA_SEPARATED_RECORDS)).isPresent();
    }

    @Test
    void junkBetweenRecordsStopsTheParseAndIsReportedAsCorruption() throws IOException {
        String record = "{\"id\":{\"progress\":{\"opaqueCount\":1}}}";
        CollectingListener listener = new CollectingListener();

        JsonBepParseResult result = parse(record + "\nnot json at all\n" + record, 512, listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(1);
        assertThat(result.completeness()).isEqualTo(Completeness.CORRUPT_PARTIAL);
        JsonParseDiagnostic diagnostic = listener.only(Code.MALFORMED_TOP_LEVEL).orElseThrow();
        assertThat(diagnostic.severity()).isEqualTo(Severity.ERROR);
        assertThat(diagnostic.byteOffset()).isEqualTo(record.length() + 1L);
    }

    @Test
    void whitespaceOnlySourceIsCompleteWithNoRecords() throws IOException {
        CollectingListener listener = new CollectingListener();
        JsonBepParseResult result = parse("\n\n   \t\r\n  ", 512, listener);

        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.deliveredRecordCount()).isZero();
        assertThat(listener.diagnostics()).isEmpty();
    }

    // ------------------------------------------------------- bounded memory

    /**
     * The Phase 1 exit criterion "no full file is loaded into memory", asserted
     * by instrumentation as contract §7 permits: the parser reports the largest
     * buffers it ever allocated, and they must stay tiny next to the file.
     */
    @Test
    void memoryStaysBoundedRegardlessOfFileSize() throws IOException {
        List<BuildEvent> events = JsonFixtures.sampleEvents();
        String block = JsonFixtures.oneObjectPerLine(events);
        Path file = tempDir.resolve("large.json");
        long expectedRecords;
        int repeats = 12_000;
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < repeats; i++) {
                out.write(block);
            }
        }
        expectedRecords = (long) repeats * events.size();
        long fileBytes = Files.size(file);
        assertThat(fileBytes).isGreaterThan(4L * 1024 * 1024);

        int readBuffer = 4096;
        CountingListener listener = new CountingListener();
        JsonBepParseResult result = new JsonBepParser(JsonBepParserOptions.defaults()
                        .withReadBufferBytes(readBuffer)
                        .withDecodeEvents(false))
                .parse(file, listener);

        assertThat(result.deliveredRecordCount()).isEqualTo(expectedRecords);
        assertThat(listener.count).isEqualTo(expectedRecords);
        assertThat(result.bytesRead()).isEqualTo(fileBytes);
        assertThat(result.completeness()).isEqualTo(Completeness.COMPLETE);
        assertThat(result.readBufferBytes()).isEqualTo(readBuffer);
        // Peak record buffer is driven by the largest record, never by the file.
        assertThat(result.peakRecordBufferBytes()).isLessThanOrEqualTo(64 * 1024);
        assertThat((long) result.peakRecordBufferBytes() + readBuffer)
                .isLessThan(fileBytes / 32);
    }

    @Test
    void rejectsOptionsThatWouldDefeatBoundedStreaming() {
        assertThatThrownBy(() -> JsonBepParserOptions.defaults().withMaxRecordBytes(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonBepParserOptions.defaults().withReadBufferBytes(16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------- utilities

    private JsonBepParseResult parse(String content, int readBuffer, JsonBepListener listener)
            throws IOException {
        return new JsonBepParser(JsonBepParserOptions.defaults().withReadBufferBytes(readBuffer))
                .parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        listener);
    }

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** Counts without retaining, so the test's own memory is bounded too. */
    private static final class CountingListener implements JsonBepListener {
        private long count;

        @Override
        public void onRecord(JsonBepRecord record) {
            count++;
        }
    }
}
