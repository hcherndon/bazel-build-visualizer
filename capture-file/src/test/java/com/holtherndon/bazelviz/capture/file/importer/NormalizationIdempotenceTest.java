package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.ImportTestSupport.EventRow;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.journal.JournalFrame;
import com.holtherndon.bazelviz.format.journal.JournalReader;
import com.holtherndon.bazelviz.format.journal.JournalReaderConfig;
import com.holtherndon.bazelviz.format.journal.JournalSegments;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resuming an import replays journal frames that may already have been normalized, and the whole
 * design rests on that being harmless. This test checks it rather than assuming it.
 *
 * <p>The claim has three parts, and all three are asserted: replaying every frame of a finished
 * session adds no {@code bep_events} row, adds no {@code bep_event_edges} row, and leaves every
 * existing row's raw location untouched. The last part is the one that would be easy to get wrong —
 * an {@code INSERT OR REPLACE} would satisfy the row count while quietly rewriting the offsets that
 * exit criterion 4 says must be reproducible.
 */
class NormalizationIdempotenceTest {

  @Test
  @DisplayName("re-normalizing every journal frame changes nothing at all")
  void replayingAFinishedSessionIsANoOp(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(150));
    ImportResult imported =
        new BepImporter(
                ImportTestSupport.sessionManager(temporary.resolve("sessions")),
                ImportTestSupport.deterministicOptions())
            .importFile(source);

    List<EventRow> before = ImportTestSupport.readEvents(imported.sessionRoot());
    long edgesBefore = count(imported.sessionRoot(), "bep_event_edges");
    long identitiesBefore = count(imported.sessionRoot(), "bep_event_ids");
    assertThat(before).hasSize(150);
    assertThat(edgesBefore).isPositive();

    EventWriter.IngestSummary summary =
        replayEveryFrame(imported.sessionRoot(), ImportTestSupport.deterministicOptions());

    // Every frame was offered again, and every one of them was a duplicate.
    assertThat(summary.eventsOffered()).isEqualTo(150);
    assertThat(summary.eventsInserted()).isZero();
    assertThat(summary.duplicatesIgnored()).isEqualTo(150);
    assertThat(summary.reconciles()).isTrue();

    List<EventRow> after = ImportTestSupport.readEvents(imported.sessionRoot());
    assertThat(after)
        .extracting(EventRow::reproducibleForm)
        .containsExactlyElementsOf(before.stream().map(EventRow::reproducibleForm).toList());
    assertThat(after)
        .extracting(EventRow::id)
        .as("row ids are stable, so nothing was deleted and re-inserted")
        .containsExactlyElementsOf(before.stream().map(EventRow::id).toList());
    assertThat(count(imported.sessionRoot(), "bep_event_edges")).isEqualTo(edgesBefore);
    assertThat(count(imported.sessionRoot(), "bep_event_ids")).isEqualTo(identitiesBefore);
  }

  @Test
  @DisplayName("a resume that replays frames reports them as duplicates rather than hiding them")
  void resumeReportsTheDuplicatesItAbsorbed(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(200));

    BepImporter importer =
        new BepImporter(
            ImportTestSupport.sessionManager(temporary.resolve("sessions")),
            ImportTestSupport.deterministicOptions());
    AtomicLong polls = new AtomicLong();
    ImportResult cancelled =
        importer.importFile(
            source,
            SessionId.random(),
            ImportProgressListener.NONE,
            () -> polls.incrementAndGet() > 60);
    assertThat(cancelled.outcome()).isEqualTo(ImportOutcome.CANCELLED);

    ImportResult resumed = importer.resume(cancelled.sessionRoot());

    assertThat(resumed.eventsInDatabase()).isEqualTo(200);
    assertThat(resumed.ingest()).isPresent();
    // Whatever replay re-offered is accounted for rather than swallowed:
    // offered = inserted + duplicates, with nothing unexplained.
    assertThat(resumed.ingest().get().reconciles()).isTrue();
    assertThat(resumed.eventsNormalized())
        .as("normalization did at least as much work as there are new rows")
        .isGreaterThanOrEqualTo(resumed.ingest().get().eventsInserted());
  }

  // ------------------------------------------------------------------ helpers

  /** Feeds every frame in a session's journal back through normalization. */
  private static EventWriter.IngestSummary replayEveryFrame(Path sessionRoot, ImportOptions options)
      throws Exception {
    Path raw = sessionRoot.resolve("raw");
    EventNormalizer normalizer = new EventNormalizer(options.maxRecordBytes());
    try (SessionDatabase database = SessionDatabase.open(sessionRoot.resolve("session.sqlite"));
        EventWriter writer = new EventWriter(database.writerConnection())) {
      long streamId = onlyStreamId(database.writerConnection());
      JournalReaderConfig config = JournalReaderConfig.defaults().withReadPayloads(true);
      for (int index : JournalSegments.listSegmentIndexes(raw)) {
        try (JournalReader reader =
            JournalReader.open(JournalSegments.segmentFile(raw, index), config)) {
          JournalFrame frame;
          while ((frame = reader.next()) != null) {
            byte[] payload = frame.requirePayload();
            writer.write(
                normalizer
                    .normalize(
                        frame.header().sourceKind(),
                        payload,
                        0,
                        payload.length,
                        streamId,
                        frame.header().sequence(),
                        frame.location(),
                        frame.header().receiveMicros())
                    .normalized());
          }
        }
      }
      return writer.finalizeIngest();
    }
  }

  private static long onlyStreamId(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT id FROM event_streams ORDER BY id")) {
      if (!rows.next()) {
        throw new IllegalStateException("the session has no event stream");
      }
      return rows.getLong(1);
    }
  }

  private static long count(Path sessionRoot, String table) throws SQLException {
    try (SessionDatabase database = SessionDatabase.open(sessionRoot.resolve("session.sqlite"));
        Connection connection = database.newReadConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }
}
