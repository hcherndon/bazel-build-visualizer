package com.holtherndon.bazelviz.capture.file.json;

/**
 * Incremental structural scanner that finds the byte boundaries of the
 * top-level JSON objects in a BEP JSON file.
 *
 * <p>Bazel's {@code --build_event_json_file} usually writes one object per
 * line, but that is a coincidence of its default printer, not a guarantee:
 * some versions pretty-print, producing multi-line objects concatenated with
 * nothing but whitespace between them. Splitting on newlines is therefore
 * wrong. This scanner instead tracks JSON structural depth, which is correct
 * for both layouts and for any other whitespace arrangement.
 *
 * <p>The subtlety is that braces also occur <em>inside</em> string literals —
 * a Bazel command line or a failure message routinely contains
 * {@code {} } and escaped quotes — so a naive brace counter mis-detects the
 * end of a record. The scanner therefore carries three pieces of state:
 * brace depth, whether it is inside a string literal, and whether the previous
 * byte was a backslash that escapes the next one. That last flag is what makes
 * {@code "\\"} (an escaped backslash, which ends the string) behave
 * differently from {@code "\""} (an escaped quote, which does not).
 *
 * <p>Square brackets are deliberately <em>not</em> counted. In well-formed
 * JSON, braces inside an array still balance, so brace depth alone returns to
 * zero exactly at the end of the top-level object; tracking brackets as well
 * would add state without changing any boundary.
 *
 * <p>Scanning works on raw UTF-8 bytes rather than decoded characters, which
 * is safe because every byte of a multi-byte UTF-8 sequence has its high bit
 * set and so can never be mistaken for {@code "}, {@code \}, {@code &#123;} or
 * {@code &#125;}. It also means the scanner never has to buffer a whole record
 * to decode it.
 *
 * <p>Instances are stateful and single-threaded: one scanner follows one
 * stream from start to finish.
 */
public final class JsonRecordScanner {

    /** Returned by the scan methods when the supplied range was fully consumed. */
    public static final int RANGE_CONSUMED = -1;

    private int depth;
    private boolean inString;
    private boolean escaped;
    private boolean inRecord;
    private boolean sawSeparatorComma;

    /**
     * Scans forward from {@code from} while between records, skipping the
     * whitespace (and tolerated comma separators) that may sit between two
     * top-level objects.
     *
     * @return the index of the first non-separator byte, which the caller must
     *     inspect: {@code '{'} opens the next record, anything else is
     *     structurally invalid at top level. Returns {@link #RANGE_CONSUMED}
     *     when {@code [from, to)} held nothing but separators.
     */
    public int findRecordStart(byte[] buffer, int from, int to) {
        for (int i = from; i < to; i++) {
            byte b = buffer[i];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
                continue;
            }
            if (b == ',') {
                // Not standard for concatenated objects, but some producers
                // emit an unwrapped comma-separated sequence. Tolerated and
                // reported once by the parser rather than silently accepted.
                sawSeparatorComma = true;
                continue;
            }
            return i;
        }
        return RANGE_CONSUMED;
    }

    /**
     * Opens a record. The caller has just observed the opening {@code '{'} and
     * must pass the index <em>after</em> it to the next {@link #scanBody} call.
     */
    public void beginRecord() {
        if (inRecord) {
            throw new IllegalStateException("a record is already open");
        }
        inRecord = true;
        depth = 1;
        inString = false;
        escaped = false;
    }

    /**
     * Consumes {@code [from, to)} as record body bytes.
     *
     * @return the index one past the record's closing {@code '}'} when the
     *     record ends inside this range, or {@link #RANGE_CONSUMED} when the
     *     record continues past {@code to}
     */
    public int scanBody(byte[] buffer, int from, int to) {
        if (!inRecord) {
            throw new IllegalStateException("no record is open");
        }
        int d = depth;
        boolean string = inString;
        boolean esc = escaped;
        for (int i = from; i < to; i++) {
            byte b = buffer[i];
            if (string) {
                if (esc) {
                    // Whatever this byte is, it is escaped and structurally
                    // inert -- including a quote or a second backslash.
                    esc = false;
                } else if (b == '\\') {
                    esc = true;
                } else if (b == '"') {
                    string = false;
                }
                continue;
            }
            if (b == '"') {
                string = true;
            } else if (b == '{') {
                d++;
            } else if (b == '}') {
                if (--d == 0) {
                    depth = 0;
                    inString = false;
                    escaped = false;
                    inRecord = false;
                    return i + 1;
                }
            }
        }
        depth = d;
        inString = string;
        escaped = esc;
        return RANGE_CONSUMED;
    }

    /** True while a record has been opened but not yet closed. */
    public boolean isInRecord() {
        return inRecord;
    }

    /** Current brace nesting depth inside the open record; 0 when between records. */
    public int depth() {
        return depth;
    }

    /** True when the stream ended while inside a string literal. */
    public boolean isInString() {
        return inString;
    }

    /** True if a comma was tolerated as a top-level separator at least once. */
    public boolean sawSeparatorComma() {
        return sawSeparatorComma;
    }
}
