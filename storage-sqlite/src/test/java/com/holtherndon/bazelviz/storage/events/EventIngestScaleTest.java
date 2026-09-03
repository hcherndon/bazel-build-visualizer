package com.holtherndon.bazelviz.storage.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hundred thousand events through the real write path, then reconciled row by row. Small next to
 * a Tier 3 session, but large enough that a per-row commit, an unbounded cache or an accidental
 * full-table read would show up as a failure or a timeout rather than passing quietly.
 */
final class EventIngestScaleTest {

  private static final int EVENTS = 100_000;
  private static final int ANNOUNCE_EVERY = 10;
  private static final int DUPLICATE_EVERY = 1_000;

  @TempDir Path tempDir;

  @Test
  void oneHundredThousandEventsIngestAndReconcileExactly() throws Exception {
    try (SessionDatabase db = TestSession.migrated(tempDir.resolve("scale.db"))) {
      long stream = EventWriterTest.openStream(db, "stream-a");

      EventWriter.IngestSummary summary;
      long expectedDuplicates = 0;
      long expectedEdges = 0;
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        for (int sequence = 1; sequence <= EVENTS; sequence++) {
          long hash = 1_000_000L + sequence;
          List<Long> children = List.of();
          if (sequence % ANNOUNCE_EVERY == 0) {
            // Announce the previous event, which has already
            // arrived, and one that never will. Announcing a child
            // that arrived earlier is the out-of-order case the
            // resolver has to get right.
            children = List.of(1_000_000L + sequence - 1, -(long) sequence);
            expectedEdges += 2;
          }
          NormalizedEvent event =
              new NormalizedEvent(
                  TestSession.event(stream, sequence, hash),
                  Optional.of(TestSession.identity(hash)),
                  children);
          writer.write(event);
          if (sequence % DUPLICATE_EVERY == 0) {
            writer.write(event); // BES retransmission
            expectedDuplicates++;
          }
        }
        summary = writer.finalizeIngest();
      }

      long announcedMissing = EVENTS / ANNOUNCE_EVERY;
      assertThat(summary.eventsOffered()).isEqualTo(EVENTS + expectedDuplicates);
      assertThat(summary.eventsInserted()).isEqualTo(EVENTS);
      assertThat(summary.duplicatesIgnored()).isEqualTo(expectedDuplicates);
      assertThat(summary.reconciles()).isTrue();
      assertThat(summary.announcedMissing()).isEqualTo(announcedMissing);

      assertThat(EventWriterTest.scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_events"))
          .isEqualTo(EVENTS);
      assertThat(
              EventWriterTest.scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_event_ids"))
          .isEqualTo(EVENTS);
      assertThat(
              EventWriterTest.scalar(db.writerConnection(), "SELECT COUNT(*) FROM bep_event_edges"))
          .isEqualTo(expectedEdges);
      assertThat(
              EventWriterTest.scalar(
                  db.writerConnection(), "SELECT COUNT(*) FROM bep_announced_missing"))
          .isEqualTo(announcedMissing);
      // No row lost its identity to a rowid gap: ids run 1..EVENTS.
      assertThat(EventWriterTest.scalar(db.writerConnection(), "SELECT MIN(id) FROM bep_events"))
          .isEqualTo(1);
      assertThat(EventWriterTest.scalar(db.writerConnection(), "SELECT MAX(id) FROM bep_events"))
          .isEqualTo(EVENTS);

      try (Connection read = db.newReadConnection();
          EventQueries queries = new EventQueries(read)) {
        assertThat(queries.eventCount()).isEqualTo(EVENTS);

        // Page the whole table with a keyset anchor: every row once,
        // in order, with no page ever holding more than 1,000 rows.
        long previous = 0;
        long seen = 0;
        OptionalLong anchor = OptionalLong.empty();
        while (true) {
          EventPage page = queries.pageForward(anchor, 1_000);
          for (EventSummary event : page.events()) {
            assertThat(event.id()).isGreaterThan(previous);
            previous = event.id();
            seen++;
          }
          if (page.nextAnchor().isEmpty()) {
            break;
          }
          anchor = page.nextAnchor();
        }
        assertThat(seen).isEqualTo(EVENTS);

        // Spot-check that the missing children are the ones announced
        // and never delivered, and no others.
        List<AnnouncedMissingEntry> firstMissing =
            queries.announcedMissingPage(OptionalLong.empty(), 5);
        assertThat(firstMissing).hasSize(5);
        assertThat(firstMissing)
            .allSatisfy(entry -> assertThat(entry.childEventIdHash()).isNegative());

        List<Long> allMissing = new ArrayList<>();
        OptionalLong hashAnchor = OptionalLong.empty();
        while (true) {
          List<AnnouncedMissingEntry> batch = queries.announcedMissingPage(hashAnchor, 256);
          if (batch.isEmpty()) {
            break;
          }
          batch.forEach(entry -> allMissing.add(entry.childEventIdHash()));
          hashAnchor = OptionalLong.of(batch.getLast().childEventIdHash());
        }
        assertThat(allMissing).hasSize((int) announcedMissing).doesNotHaveDuplicates();
      }
    }
  }
}
