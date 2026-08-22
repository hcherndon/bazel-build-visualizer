package com.holtherndon.bazelviz.capture.file.json;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Test listener that keeps everything the parser produced.
 *
 * <p>It copies {@code rawBytes} on receipt, exactly as the parser's contract
 * requires of any caller that retains a record beyond the callback. Doing so
 * here rather than trusting the parser also means a test that compares bytes is
 * comparing what the listener was actually handed.
 */
final class CollectingListener implements JsonBepListener {

    private final List<JsonBepRecord> records = new ArrayList<>();
    private final List<byte[]> rawCopies = new ArrayList<>();
    private final List<JsonParseDiagnostic> diagnostics = new ArrayList<>();

    @Override
    public void onRecord(JsonBepRecord record) {
        records.add(record);
        rawCopies.add(record.rawBytes().clone());
    }

    @Override
    public void onDiagnostic(JsonParseDiagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    List<JsonBepRecord> records() {
        return records;
    }

    byte[] rawAt(int index) {
        return rawCopies.get(index);
    }

    List<JsonParseDiagnostic> diagnostics() {
        return diagnostics;
    }

    List<JsonParseDiagnostic> diagnostics(JsonParseDiagnostic.Code code) {
        return diagnostics.stream().filter(d -> d.code() == code).toList();
    }

    Optional<JsonParseDiagnostic> only(JsonParseDiagnostic.Code code) {
        List<JsonParseDiagnostic> matches = diagnostics(code);
        if (matches.size() > 1) {
            throw new AssertionError("expected at most one " + code + " but found " + matches);
        }
        return matches.stream().findFirst();
    }

    List<BuildEvent> events() {
        return records.stream().map(JsonBepRecord::event).toList();
    }
}
