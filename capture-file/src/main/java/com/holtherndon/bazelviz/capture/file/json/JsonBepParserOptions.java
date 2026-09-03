package com.holtherndon.bazelviz.capture.file.json;

import com.holtherndon.bazelviz.core.journal.JournalFormat;

/**
 * Tunables for {@link JsonBepParser}.
 *
 * <p>{@code maxRecordBytes} is the configurable per-record limit plan 9.5 and 21.3 require. It
 * defaults to {@link JournalFormat#DEFAULT_MAX_PAYLOAD_BYTES} on purpose: an accepted record is
 * journaled as a single frame, so a record the parser would accept but the journal would reject is
 * a limit mismatch waiting to happen. Exceeding the limit is reported with the record's offset and
 * true length, never skipped silently and never honoured by allocating whatever the file happens to
 * contain.
 *
 * <p>{@code readBufferBytes} bounds the parser's streaming window. Together with the record buffer
 * it is the parser's entire memory footprint; the file itself is never read into memory (Phase 1
 * exit criterion).
 *
 * @param maxRecordBytes largest record whose bytes will be retained and delivered
 * @param readBufferBytes size of the fixed streaming read buffer
 * @param decodeEvents whether to decode each record to a {@code BuildEvent}
 */
public record JsonBepParserOptions(int maxRecordBytes, int readBufferBytes, boolean decodeEvents) {

  /** Matches the journal's frame payload ceiling so the two limits cannot disagree. */
  public static final int DEFAULT_MAX_RECORD_BYTES = JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES;

  /** Streaming window size. Large enough to amortize syscalls, small enough to stay bounded. */
  public static final int DEFAULT_READ_BUFFER_BYTES = 64 * 1024;

  /** Smallest useful read buffer; below this the parser degenerates into byte-at-a-time I/O. */
  public static final int MIN_READ_BUFFER_BYTES = 512;

  private static final JsonBepParserOptions DEFAULTS =
      new JsonBepParserOptions(DEFAULT_MAX_RECORD_BYTES, DEFAULT_READ_BUFFER_BYTES, true);

  public JsonBepParserOptions {
    if (maxRecordBytes <= 0) {
      throw new IllegalArgumentException("maxRecordBytes must be positive: " + maxRecordBytes);
    }
    if (readBufferBytes < MIN_READ_BUFFER_BYTES) {
      throw new IllegalArgumentException(
          "readBufferBytes must be at least " + MIN_READ_BUFFER_BYTES + ": " + readBufferBytes);
    }
  }

  public static JsonBepParserOptions defaults() {
    return DEFAULTS;
  }

  public JsonBepParserOptions withMaxRecordBytes(int bytes) {
    return new JsonBepParserOptions(bytes, readBufferBytes, decodeEvents);
  }

  public JsonBepParserOptions withReadBufferBytes(int bytes) {
    return new JsonBepParserOptions(maxRecordBytes, bytes, decodeEvents);
  }

  public JsonBepParserOptions withDecodeEvents(boolean decode) {
    return new JsonBepParserOptions(maxRecordBytes, readBufferBytes, decode);
  }
}
