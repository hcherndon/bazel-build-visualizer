package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Shared scaffolding for the importer tests: a session manager over a temporary root, deterministic
 * options, and read-only views of what an import actually stored.
 *
 * <p>The row readers below go straight to SQL rather than through {@code EventQueries} on purpose.
 * These tests are asserting what the importer <em>wrote</em>, and reading it back through another
 * component's paging logic would make a bug in that component look like a bug here.
 */
final class ImportTestSupport {

  /** Fixed instant so two runs of the same import agree on every timestamp too. */
  static final long FIXED_MICROS = 1_767_225_600_000_000L;

  static final String APP_VERSION = "0.1.0-test";

  private ImportTestSupport() {}

  static SessionManager sessionManager(Path sessionsRoot) {
    return new SessionManager(sessionsRoot, APP_VERSION);
  }

  /**
   * Options that make an import byte-for-byte reproducible: a constant clock, so {@code
   * receive_micros} and diagnostic timestamps do not vary between runs, plus small buffers and a
   * short checkpoint interval so the streaming and resume paths are actually exercised by small
   * fixtures.
   */
  static ImportOptions deterministicOptions() {
    return ImportOptions.defaults()
        .withClock(() -> FIXED_MICROS)
        .withCheckpointEveryRecords(25)
        // Deliberately smaller than the checkpoint interval, so a run
        // interrupted between checkpoints leaves the database ahead of
        // the resume point and journal replay really does re-offer rows
        // that are already stored. With a larger batch that path would
        // never be exercised and the idempotence claim would go untested.
        .withBatchSize(10)
        .withReadBufferBytes(4096)
        .withJournalBufferBytes(64 * 1024)
        .withSegmentBytes(1 << 20);
  }

  /** One {@code bep_events} row, with everything that must be reproducible. */
  record EventRow(
      long id,
      long streamId,
      long sequence,
      int eventType,
      OptionalLong eventIdHash,
      boolean lastMessage,
      int childCount,
      int rawSegment,
      long rawOffset,
      int rawLength,
      DecodeStatus decodeStatus,
      boolean hasUnknownFields,
      OptionalLong eventMicros,
      long receiveMicros) {

    RawLocation rawLocation() {
      return new RawLocation(rawSegment, rawOffset, rawLength);
    }

    /**
     * Everything except {@code id} and {@code receive_micros}. Row ids are assigned by insertion
     * order and receive times are wall-clock observations, so those two legitimately differ between
     * an interrupted import and an uninterrupted one; nothing else may.
     */
    String reproducibleForm() {
      return streamId
          + "/"
          + sequence
          + "/"
          + eventType
          + "/"
          + eventIdHash
          + "/"
          + lastMessage
          + "/"
          + childCount
          + "/"
          + rawSegment
          + "@"
          + rawOffset
          + "+"
          + rawLength
          + "/"
          + decodeStatus
          + "/"
          + hasUnknownFields
          + "/"
          + eventMicros;
    }
  }

  record DiagnosticRow(String severity, String code, String message, OptionalLong byteOffset) {}

  record CaptureSourceRow(
      String kind, String path, String sha256, OptionalLong byteSize, String completeness) {}

  static List<EventRow> readEvents(Path sessionRoot) throws SQLException {
    List<EventRow> rows = new ArrayList<>();
    forEachRow(
        sessionRoot,
        "SELECT id, stream_id, sequence, event_type, event_id_hash, last_message,"
            + " child_count, raw_segment, raw_offset, raw_length, decode_status,"
            + " has_unknown_fields, event_micros, receive_micros"
            + " FROM bep_events ORDER BY id",
        result ->
            rows.add(
                new EventRow(
                    result.getLong(1),
                    result.getLong(2),
                    result.getLong(3),
                    result.getInt(4),
                    optionalLong(result, 5),
                    result.getInt(6) != 0,
                    result.getInt(7),
                    result.getInt(8),
                    result.getLong(9),
                    result.getInt(10),
                    DecodeStatus.parse(result.getString(11)),
                    result.getInt(12) != 0,
                    optionalLong(result, 13),
                    result.getLong(14))));
    return rows;
  }

  static List<DiagnosticRow> readDiagnostics(Path sessionRoot) throws SQLException {
    List<DiagnosticRow> rows = new ArrayList<>();
    forEachRow(
        sessionRoot,
        "SELECT severity, code, message, byte_offset FROM import_diagnostics ORDER BY id",
        result ->
            rows.add(
                new DiagnosticRow(
                    result.getString(1),
                    result.getString(2),
                    result.getString(3),
                    optionalLong(result, 4))));
    return rows;
  }

  static List<CaptureSourceRow> readCaptureSources(Path sessionRoot) throws SQLException {
    List<CaptureSourceRow> rows = new ArrayList<>();
    forEachRow(
        sessionRoot,
        "SELECT kind, path, sha256, byte_size, completeness FROM capture_sources ORDER BY id",
        result ->
            rows.add(
                new CaptureSourceRow(
                    result.getString(1),
                    result.getString(2),
                    result.getString(3),
                    optionalLong(result, 4),
                    result.getString(5))));
    return rows;
  }

  static String sessionInfoState(Path sessionRoot) throws SQLException {
    List<String> states = new ArrayList<>();
    forEachRow(
        sessionRoot, "SELECT state FROM session_info", result -> states.add(result.getString(1)));
    return states.isEmpty() ? null : states.get(0);
  }

  static List<String> indexNames(Path sessionRoot) throws SQLException {
    List<String> names = new ArrayList<>();
    forEachRow(
        sessionRoot,
        "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'"
            + " ORDER BY name",
        result -> names.add(result.getString(1)));
    return names;
  }

  static String streamInvocationId(Path sessionRoot) throws SQLException {
    List<String> values = new ArrayList<>();
    forEachRow(
        sessionRoot,
        "SELECT invocation_id FROM event_streams ORDER BY id",
        result -> values.add(result.getString(1)));
    return values.isEmpty() ? null : values.get(0);
  }

  private static OptionalLong optionalLong(ResultSet result, int column) throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static void forEachRow(Path sessionRoot, String sql, RowConsumer consumer)
      throws SQLException {
    Path databaseFile = sessionRoot.resolve("session.sqlite");
    try (SessionDatabase database = SessionDatabase.open(databaseFile);
        Connection connection = database.newReadConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        consumer.accept(result);
      }
    }
  }

  @FunctionalInterface
  private interface RowConsumer {
    void accept(ResultSet result) throws SQLException;
  }

  /** Reads every stored raw payload back out of the journal, in row order. */
  static List<byte[]> readRawPayloads(Path sessionRoot, List<EventRow> rows) throws IOException {
    JournalPayloadReader reader = JournalPayloadReader.forSession(sessionRoot);
    List<byte[]> payloads = new ArrayList<>(rows.size());
    for (EventRow row : rows) {
      payloads.add(reader.read(row.rawLocation()));
    }
    return payloads;
  }
}
