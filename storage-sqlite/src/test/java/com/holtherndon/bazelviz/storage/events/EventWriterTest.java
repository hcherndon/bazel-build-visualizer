package com.holtherndon.bazelviz.storage.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EventWriterTest {

  @TempDir Path tempDir;

  @Test
  void storesAnEventWithEveryValuePresent() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("basic.db"))) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(
            NormalizedEvent.of(
                TestSession.event(stream, 1, 0xABCDL), TestSession.identity(0xABCDL)));
        writer.flush();
      }

      try (Connection read = db.newReadConnection();
          EventQueries queries = new EventQueries(read)) {
        EventDetail detail = queries.event(1).orElseThrow();
        assertThat(detail.summary().sequence()).isEqualTo(1);
        assertThat(detail.summary().eventIdHash()).hasValue(0xABCDL);
        assertThat(detail.summary().eventMicros()).isPresent();
        assertThat(detail.summary().decodeStatus()).isEqualTo(DecodeStatus.OK);
        assertThat(detail.rawLocation()).isEqualTo(new RawLocation(0, 128, 64));
        assertThat(detail.identity()).contains(TestSession.identity(0xABCDL));
      }
    }
  }

  @Test
  void unknownValuesPersistAsNullAndReadBackAsAbsentNeverZero() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("nulls.db"))) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(NormalizedEvent.of(TestSession.eventWithUnknowns(stream, 1)));
        writer.flush();
      }

      // The columns really are NULL on disk, not 0.
      assertThat(
              scalar(
                  db.writerConnection(),
                  "SELECT COUNT(*) FROM bep_events WHERE event_id_hash IS NULL"))
          .isEqualTo(1);
      assertThat(
              scalar(
                  db.writerConnection(),
                  "SELECT COUNT(*) FROM bep_events WHERE event_micros IS NULL"))
          .isEqualTo(1);
      assertThat(
              scalar(
                  db.writerConnection(),
                  "SELECT COUNT(*) FROM bep_events WHERE event_id_hash = 0 OR event_micros = 0"))
          .isZero();

      try (Connection read = db.newReadConnection();
          EventQueries queries = new EventQueries(read)) {
        EventDetail detail = queries.event(1).orElseThrow();
        assertThat(detail.summary().eventIdHash()).isEmpty();
        assertThat(detail.summary().eventMicros()).isEmpty();
        assertThat(detail.summary().eventIdHash()).isNotEqualTo(OptionalLong.of(0));
        assertThat(detail.identity()).isEmpty();
        assertThat(detail.summary().decodeStatus()).isEqualTo(DecodeStatus.FAILED);
        assertThat(detail.summary().hasUnknownFields()).isTrue();
      }
    }
  }

  @Test
  void duplicateSequenceIsANoOpAndLeavesOneRow() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("dupes.db"))) {
      long stream = openStream(db, "stream-a");
      EventWriter.IngestSummary summary;
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(
            new NormalizedEvent(
                TestSession.event(stream, 7, 0x77L),
                Optional.of(TestSession.identity(0x77L)),
                List.of(0x780L, 0x781L)));
        writer.flush();
        // BES retransmits sequence 7, identity and children included.
        writer.write(
            new NormalizedEvent(
                TestSession.event(stream, 7, 0x77L),
                Optional.of(TestSession.identity(0x77L)),
                List.of(0x780L, 0x781L)));
        summary = writer.finalizeIngest();
      }

      assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_events")).isEqualTo(1);
      assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_event_ids")).isEqualTo(1);
      assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_event_edges"))
          .isEqualTo(2);
      assertThat(summary.eventsOffered()).isEqualTo(2);
      assertThat(summary.eventsInserted()).isEqualTo(1);
      assertThat(summary.duplicatesIgnored()).isEqualTo(1);
      assertThat(summary.reconciles()).isTrue();

      // The duplicate was ignored, not silently ignored: it is on the record.
      try (Connection read = db.newReadConnection();
          EventQueries queries = new EventQueries(read)) {
        assertThat(queries.diagnosticsPage(OptionalLong.empty(), 100))
            .anySatisfy(
                entry ->
                    assertThat(entry.diagnostic().code())
                        .isEqualTo(DiagnosticCodes.DUPLICATE_SEQUENCE));
      }
    }
  }

  @Test
  void aDuplicateThatArrivesInASeparateWriterStillStoresOneRow() throws Exception {
    Path file = tempDir.resolve("replay.db");
    try (SessionDatabase db = TestSession.migrated(file)) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(
            new NormalizedEvent(
                TestSession.event(stream, 3, 0x33L),
                Optional.of(TestSession.identity(0x33L)),
                List.of(0x340L)));
        writer.finalizeIngest();
      }
      // Recovery replays the journal from the last checkpoint and hands
      // the same event over again.
      try (EventWriter replay = new EventWriter(db.writerConnection())) {
        replay.write(
            new NormalizedEvent(
                TestSession.event(stream, 3, 0x33L),
                Optional.of(TestSession.identity(0x33L)),
                List.of(0x340L)));
        EventWriter.IngestSummary summary = replay.finalizeIngest();
        assertThat(summary.eventsInserted()).isZero();
        assertThat(summary.duplicatesIgnored()).isEqualTo(1);
      }

      assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_events")).isEqualTo(1);
      assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_event_edges"))
          .isEqualTo(1);
    }
  }

  @Test
  void edgesForAnEventThatWasNeverStoredAreDroppedRatherThanViolatingTheForeignKey()
      throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("orphan.db"))) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        // Announce a child on behalf of a parent that is not in the table.
        writer.addAnnouncedChild(stream, 999, 0, 0xC0FFEEL);
        writer.flush();
      }
      assertThat(scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_event_edges")).isZero();
    }
  }

  @Test
  void announcedChildrenClearWhenTheyArriveAndTheRestAreRecordedAsMissing() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("announced.db"))) {
      long stream = openStream(db, "stream-a");
      long arrivingChild = 0xAAAAL;
      long neverArrives = 0xBBBBL;

      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        // Parent announces two children.
        writer.write(
            new NormalizedEvent(
                TestSession.event(stream, 1, 0x1L),
                Optional.of(TestSession.identity(0x1L)),
                List.of(arrivingChild, neverArrives)));

        // Before either child arrives, both are pending.
        assertThat(writer.resolveAnnouncedMissing()).isEqualTo(2);

        // One of them turns up.
        writer.write(
            NormalizedEvent.of(
                TestSession.event(stream, 2, arrivingChild), TestSession.identity(arrivingChild)));

        EventWriter.IngestSummary summary = writer.finalizeIngest();
        assertThat(summary.announcedMissing()).isEqualTo(1);
      }

      try (Connection read = db.newReadConnection();
          EventQueries queries = new EventQueries(read)) {
        assertThat(queries.announcedMissingPage(OptionalLong.empty(), 10))
            .containsExactly(new AnnouncedMissingEntry(neverArrives, 1));

        List<AnnouncedChild> children = queries.childrenOfIdHash(0x1L);
        assertThat(children).hasSize(2);
        assertThat(children.get(0).arrivedEventId()).hasValue(2L);
        assertThat(children.get(1).isMissing()).isTrue();
        assertThat(children.get(1).arrivedEventId()).isEmpty();

        assertThat(queries.parentsOfIdHash(arrivingChild))
            .singleElement()
            .satisfies(parent -> assertThat(parent.id()).isEqualTo(1L));
      }
    }
  }

  @Test
  void aChildThatArrivesBeforeItIsAnnouncedIsNotReportedMissing() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("outoforder.db"))) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(
            NormalizedEvent.of(TestSession.event(stream, 1, 0x5L), TestSession.identity(0x5L)));
        writer.write(
            new NormalizedEvent(
                TestSession.event(stream, 2, 0x6L),
                Optional.of(TestSession.identity(0x6L)),
                List.of(0x5L)));
        assertThat(writer.finalizeIngest().announcedMissing()).isZero();
      }
    }
  }

  @Test
  void diagnosticsRecordPositionsAndAbsentPositionsSeparately() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("diagnostics.db"))) {
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.recordDiagnostic(
            ImportDiagnostic.at(
                DiagnosticSeverity.WARNING,
                DiagnosticCodes.TRUNCATED_TAIL,
                "segment ends mid-frame",
                2,
                4096,
                1_700_000_000_000_000L));
        writer.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.ERROR,
                DiagnosticCodes.CRC_MISMATCH,
                "checksum failed on a fully present frame",
                1_700_000_000_000_001L));
      }

      try (Connection read = db.newReadConnection();
          EventQueries queries = new EventQueries(read)) {
        List<DiagnosticEntry> entries = queries.diagnosticsPage(OptionalLong.empty(), 10);
        assertThat(entries).hasSize(2);

        ImportDiagnostic located = entries.get(0).diagnostic();
        assertThat(located.severity()).isEqualTo(DiagnosticSeverity.WARNING);
        assertThat(located.code()).isEqualTo(DiagnosticCodes.TRUNCATED_TAIL);
        assertThat(located.segmentIndex()).hasValue(2);
        assertThat(located.byteOffset()).hasValue(4096L);

        ImportDiagnostic unlocated = entries.get(1).diagnostic();
        assertThat(unlocated.segmentIndex()).isEmpty();
        assertThat(unlocated.byteOffset()).isEmpty();
        // Absent position must not read back as segment 0 / offset 0.
        assertThat(unlocated.segmentIndex()).isNotEqualTo(OptionalInt.of(0));
        assertThat(unlocated.byteOffset()).isNotEqualTo(OptionalLong.of(0));
      }
    }
  }

  @Test
  void diagnosticsSurviveEvenWhileABatchIsStillOpen() throws Exception {
    Path file = tempDir.resolve("durable-diagnostics.db");
    try (SessionDatabase db = TestSession.migrated(file)) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(NormalizedEvent.of(TestSession.event(stream, 1, 0x1L)));
        writer.recordDiagnostic(
            ImportDiagnostic.general(
                DiagnosticSeverity.ERROR,
                DiagnosticCodes.DISK_FULL,
                "no space left on device",
                1_700_000_000_000_000L));

        // Committed immediately: another connection can already see it,
        // while the queued event is still invisible.
        try (Connection read = db.newReadConnection()) {
          assertThat(scalar(read, "SELECT COUNT(*) FROM import_diagnostics")).isEqualTo(1);
        }
      }
    }
  }

  @Test
  void finalizeBuildsTheContractIndexes() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("finalize.db"))) {
      long stream = openStream(db, "stream-a");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        writer.write(NormalizedEvent.of(TestSession.event(stream, 1, 0x1L)));
        assertThat(indexExists(db.writerConnection(), "idx_bep_events_sequence")).isFalse();
        writer.finalizeIngest();
      }
      assertThat(indexExists(db.writerConnection(), "idx_bep_events_sequence")).isTrue();
      assertThat(indexExists(db.writerConnection(), "idx_bep_events_id_hash")).isTrue();
      assertThat(indexExists(db.writerConnection(), "idx_bep_event_edges_child")).isTrue();
    }
  }

  static long openStream(SessionDatabase db, String key) throws SQLException {
    try (StreamRegistry registry = new StreamRegistry(db.writerConnection())) {
      long id = registry.open(key);
      assertThat(registry.open(key)).isEqualTo(id);
      return id;
    }
  }

  static long scalar(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getLong(1) : -1L;
    }
  }

  private static boolean indexExists(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = '" + name + "'")) {
      return rows.next();
    }
  }
}
