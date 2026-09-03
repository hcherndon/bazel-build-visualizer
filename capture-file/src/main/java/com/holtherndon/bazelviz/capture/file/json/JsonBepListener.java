package com.holtherndon.bazelviz.capture.file.json;

import java.io.IOException;

/**
 * Sink for what {@link JsonBepParser} produces as it streams.
 *
 * <p>Records and diagnostics are pushed rather than collected into a list the parser returns, so
 * that neither the event count nor the diagnostic count can grow the parser's memory with the size
 * of the file. A pathological source that produces a diagnostic per record must not be able to
 * exhaust the heap inside the parser; what to keep is the consumer's decision.
 *
 * <p>Callbacks run on the parsing thread, which is never the Swing EDT (project rule).
 * Implementations that update the UI must hand off.
 */
public interface JsonBepListener {

  /**
   * Called once per delivered record, in file order.
   *
   * <p>{@link JsonBepRecord#rawBytes()} is valid for the duration of the call and must be copied to
   * be retained.
   */
  void onRecord(JsonBepRecord record) throws IOException;

  /**
   * Called for every limit hit, tolerated oddity, and decode problem.
   *
   * <p>Default is to ignore, which is appropriate only for callers that inspect {@link
   * JsonBepParseResult} counts instead; anything user-facing should implement it, because this is
   * where "we did not read all of your file" is said.
   */
  default void onDiagnostic(JsonParseDiagnostic diagnostic) throws IOException {
    // Intentionally empty; see javadoc.
  }
}
