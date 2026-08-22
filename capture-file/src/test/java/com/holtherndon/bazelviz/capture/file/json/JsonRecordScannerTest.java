package com.holtherndon.bazelviz.capture.file.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Boundary-finding tests for the structural scanner.
 *
 * <p>Every case is run at several chunk sizes, down to one byte at a time, so
 * that a boundary landing exactly on a buffer edge — mid-string, mid-escape,
 * between a brace and its neighbour — is exercised rather than hoped about.
 */
final class JsonRecordScannerTest {

    /**
     * A record whose string values contain both braces and escaped quotes, the
     * shape a real Bazel failure message or command line has. Written as a text
     * block, so after Java escape processing the actual JSON bytes are:
     *
     * <pre>
     * {"msg":"closes } opens { quote \" backslash \\ both \\\" done","n":{"k":"}"}}
     * </pre>
     *
     * A brace counter that does not track string state ends this record early,
     * at the {@code &#125;} inside the first string value.
     */
    private static final String TRICKY = """
            {"msg":"closes } opens { quote \\" backslash \\\\ both \\\\\\" done","n":{"k":"}"}}""";

    private static final String SIMPLE = """
            {"id":{"progress":{"opaqueCount":1}},"progress":{"stderr":"hello"}}""";

    @Test
    void trickyFixtureReallyContainsTheHazards() {
        // Guard the fixture itself: if escape processing ever changed these
        // out from under us the tests below would pass vacuously.
        assertThat(TRICKY).contains("closes } opens {");
        assertThat(TRICKY).contains("quote \\\" backslash \\\\ both \\\\\\\" done");
        // The first '}' is inside a string, far from the record's real end.
        assertThat(TRICKY.indexOf('}')).isLessThan(TRICKY.length() - 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 16, 64, 4096})
    void bracesAndEscapedQuotesInsideStringsDoNotSplitTheRecord(int chunk) {
        byte[] data = bytes(TRICKY);

        List<long[]> records = split(data, chunk);

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsExactly(0L, data.length);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 13, 64, 4096})
    void findsEveryBoundaryInAConcatenatedSequence(int chunk) {
        String joined = SIMPLE + "\n" + TRICKY + "\n" + SIMPLE + "\n";
        byte[] data = bytes(joined);

        List<long[]> records = split(data, chunk);

        int simple = bytes(SIMPLE).length;
        int tricky = bytes(TRICKY).length;
        assertThat(records).hasSize(3);
        assertThat(records.get(0)).containsExactly(0L, simple);
        assertThat(records.get(1)).containsExactly(simple + 1L, tricky);
        assertThat(records.get(2)).containsExactly(simple + 1L + tricky + 1L, simple);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 17, 4096})
    void prettyPrintedObjectsWithNoSeparatorSplitAtTheSameBoundaries(int chunk) {
        String pretty = """
                {
                  "id": {
                    "progress": {}
                  },
                  "progress": {
                    "stderr": "a } inside {"
                  }
                }""";
        byte[] data = bytes(pretty + "\n" + pretty);

        List<long[]> records = split(data, chunk);

        int length = bytes(pretty).length;
        assertThat(records).hasSize(2);
        assertThat(records.get(0)).containsExactly(0L, length);
        assertThat(records.get(1)).containsExactly(length + 1L, length);
    }

    @Test
    void anEscapedBackslashDoesNotEscapeTheFollowingQuote() {
        // "a\\" closes the string; the '}' after it is structural. If the
        // escape flag were sticky the record would appear to run on forever.
        byte[] data = bytes("{\"a\":\"x\\\\\"}{\"b\":1}");

        List<long[]> records = split(data, 1);

        assertThat(records).hasSize(2);
        assertThat(records.get(0)).containsExactly(0L, 11L);
        assertThat(records.get(1)).containsExactly(11L, 7L);
    }

    @Test
    void reportsAnUnclosedRecordAsStillOpen() {
        byte[] data = bytes("{\"a\":{\"b\":1}");
        JsonRecordScanner scanner = new JsonRecordScanner();

        int start = scanner.findRecordStart(data, 0, data.length);
        scanner.beginRecord();
        int end = scanner.scanBody(data, start + 1, data.length);

        assertThat(end).isEqualTo(JsonRecordScanner.RANGE_CONSUMED);
        assertThat(scanner.isInRecord()).isTrue();
        assertThat(scanner.depth()).isEqualTo(1);
        assertThat(scanner.isInString()).isFalse();
    }

    @Test
    void reportsAnUnclosedStringAsStillOpen() {
        byte[] data = bytes("{\"a\":\"unterminated");
        JsonRecordScanner scanner = new JsonRecordScanner();
        scanner.beginRecord();

        int end = scanner.scanBody(data, 1, data.length);

        assertThat(end).isEqualTo(JsonRecordScanner.RANGE_CONSUMED);
        assertThat(scanner.isInString()).isTrue();
    }

    @Test
    void reportsTopLevelBytesThatCannotOpenARecord() {
        byte[] data = bytes("  \n garbage {\"a\":1}");
        JsonRecordScanner scanner = new JsonRecordScanner();

        int index = scanner.findRecordStart(data, 0, data.length);

        assertThat(index).isEqualTo(4);
        assertThat(data[index]).isEqualTo((byte) 'g');
    }

    @Test
    void tolerantOfCommaSeparatorsAndSaysSo() {
        byte[] data = bytes("{\"a\":1} , {\"b\":2}");

        List<long[]> records = split(data, 4096);

        assertThat(records).hasSize(2);
        assertThat(records.get(1)).containsExactly(10L, 7L);
    }

    @Test
    void utf8ContinuationBytesAreNeverMistakenForStructure() {
        // Every byte of a multi-byte UTF-8 sequence has its high bit set, so
        // none can collide with '{', '}', '"' or '\'.
        String json = "{\"m\":\"é中😀 } { \"}";
        byte[] data = bytes(json);

        List<long[]> records = split(data, 1);

        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsExactly(0L, data.length);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Drives the scanner over {@code data} handing it at most {@code chunk}
     * bytes at a time, mirroring how {@link JsonBepParser} feeds it from a
     * fixed read buffer. Offsets are absolute because the window moves over one
     * array.
     *
     * @return one {@code {offset, length}} pair per completed record
     */
    private static List<long[]> split(byte[] data, int chunk) {
        JsonRecordScanner scanner = new JsonRecordScanner();
        List<long[]> found = new ArrayList<>();
        long start = -1;
        for (int windowStart = 0; windowStart < data.length; windowStart += chunk) {
            int windowEnd = Math.min(data.length, windowStart + chunk);
            int p = windowStart;
            while (p < windowEnd) {
                if (scanner.isInRecord()) {
                    int end = scanner.scanBody(data, p, windowEnd);
                    if (end == JsonRecordScanner.RANGE_CONSUMED) {
                        p = windowEnd;
                    } else {
                        found.add(new long[] {start, end - start});
                        p = end;
                    }
                } else {
                    int index = scanner.findRecordStart(data, p, windowEnd);
                    if (index == JsonRecordScanner.RANGE_CONSUMED) {
                        p = windowEnd;
                    } else {
                        assertThat(data[index]).isEqualTo((byte) '{');
                        start = index;
                        scanner.beginRecord();
                        p = index + 1;
                    }
                }
            }
        }
        return found;
    }
}
