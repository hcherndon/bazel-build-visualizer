package com.holtherndon.bazelviz.storage.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EventQueriesTest {

    /** Deliberately not a multiple of any page size used below. */
    private static final int EVENT_COUNT = 257;

    @TempDir
    Path tempDir;

    @Test
    void forwardKeysetPagingVisitsEveryRowExactlyOnce() throws Exception {
        try (SessionDatabase db = seeded("forward.db");
                Connection read = db.newReadConnection();
                EventQueries queries = new EventQueries(read)) {

            assertThat(queries.eventCount()).isEqualTo(EVENT_COUNT);

            for (int pageSize : new int[] {1, 7, 50, EVENT_COUNT, EVENT_COUNT + 13}) {
                List<Long> visited = new ArrayList<>();
                OptionalLong anchor = OptionalLong.empty();
                int guard = 0;
                while (true) {
                    EventPage page = queries.pageForward(anchor, pageSize);
                    visited.addAll(page.events().stream().map(EventSummary::id).toList());
                    if (page.nextAnchor().isEmpty()) {
                        break;
                    }
                    anchor = page.nextAnchor();
                    if (++guard > EVENT_COUNT + 10) {
                        throw new AssertionError("paging did not terminate at pageSize " + pageSize);
                    }
                }
                assertThat(visited)
                        .as("page size %d", pageSize)
                        .hasSize(EVENT_COUNT)
                        .doesNotHaveDuplicates()
                        .isSorted()
                        .containsExactlyElementsOf(expectedIds());
            }
        }
    }

    @Test
    void backwardKeysetPagingVisitsEveryRowExactlyOnce() throws Exception {
        try (SessionDatabase db = seeded("backward.db");
                Connection read = db.newReadConnection();
                EventQueries queries = new EventQueries(read)) {

            List<Long> visited = new ArrayList<>();
            OptionalLong anchor = OptionalLong.empty();
            while (true) {
                EventPage page = queries.pageBackward(anchor, 40);
                // Backward pages come back in natural order, so prepend them.
                visited.addAll(0, page.events().stream().map(EventSummary::id).toList());
                if (page.nextAnchor().isEmpty()) {
                    break;
                }
                anchor = page.nextAnchor();
            }
            assertThat(visited)
                    .hasSize(EVENT_COUNT)
                    .doesNotHaveDuplicates()
                    .isSorted()
                    .containsExactlyElementsOf(expectedIds());
        }
    }

    @Test
    void pagingIsStableAcrossAGapInIds() throws Exception {
        try (SessionDatabase db = seeded("gaps.db")) {
            // Duplicate deliveries consume no id, but a partly-failed batch can
            // still leave holes; paging must not care.
            try (var statement = db.writerConnection().createStatement()) {
                statement.executeUpdate("DELETE FROM bep_events WHERE id % 3 = 0");
            }
            long remaining;
            try (Connection read = db.newReadConnection();
                    EventQueries queries = new EventQueries(read)) {
                remaining = queries.eventCount();
                List<Long> visited = new ArrayList<>();
                OptionalLong anchor = OptionalLong.empty();
                while (true) {
                    EventPage page = queries.pageForward(anchor, 16);
                    visited.addAll(page.events().stream().map(EventSummary::id).toList());
                    if (page.nextAnchor().isEmpty()) {
                        break;
                    }
                    anchor = page.nextAnchor();
                }
                assertThat(visited).hasSize((int) remaining).doesNotHaveDuplicates().isSorted();
            }
        }
    }

    @Test
    void rejectsANonPositivePageSize() throws Exception {
        try (SessionDatabase db = seeded("limits.db");
                Connection read = db.newReadConnection();
                EventQueries queries = new EventQueries(read)) {
            assertThatThrownBy(() -> queries.pageForward(OptionalLong.empty(), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void missingEventReadsAsAbsentRatherThanAnEmptyRow() throws Exception {
        try (SessionDatabase db = seeded("missing.db");
                Connection read = db.newReadConnection();
                EventQueries queries = new EventQueries(read)) {
            assertThat(queries.event(EVENT_COUNT + 1)).isEmpty();
            assertThat(queries.identity(0xDEADL)).isEmpty();
            assertThat(queries.eventsWithIdHash(0xDEADL)).isEmpty();
        }
    }

    @Test
    void lookupsByEventIdHashResolveIdentityChildrenAndParents() throws Exception {
        try (SessionDatabase db = TestSession.migrated(tempDir.resolve("lookups.db"))) {
            long stream = EventWriterTest.openStream(db, "stream-a");
            try (EventWriter writer = new EventWriter(db.writerConnection())) {
                writer.write(new NormalizedEvent(
                        TestSession.event(stream, 1, 100L),
                        Optional.of(TestSession.identity(100L)),
                        List.of(200L, 300L)));
                writer.write(NormalizedEvent.of(
                        TestSession.event(stream, 2, 200L), TestSession.identity(200L)));
                writer.write(NormalizedEvent.of(
                        TestSession.event(stream, 3, 300L), TestSession.identity(300L)));
                writer.finalizeIngest();
            }

            try (Connection read = db.newReadConnection();
                    EventQueries queries = new EventQueries(read)) {
                assertThat(queries.identity(200L)).contains(TestSession.identity(200L));
                assertThat(queries.eventsWithIdHash(300L))
                        .singleElement()
                        .satisfies(event -> assertThat(event.sequence()).isEqualTo(3));

                assertThat(queries.childrenOfIdHash(100L))
                        .extracting(AnnouncedChild::childEventIdHash)
                        .containsExactly(200L, 300L);
                assertThat(queries.childrenOfEvent(1L))
                        .extracting(AnnouncedChild::ordinal)
                        .containsExactly(0, 1);
                assertThat(queries.parentsOfIdHash(300L))
                        .extracting(EventSummary::id)
                        .containsExactly(1L);
                assertThat(queries.announcedMissingCount()).isZero();
            }
        }
    }

    @Test
    void cancelIsSafeWhenNothingIsRunning() throws Exception {
        try (SessionDatabase db = seeded("cancel.db");
                Connection read = db.newReadConnection();
                EventQueries queries = new EventQueries(read)) {
            queries.cancel();
            assertThat(queries.eventCount()).isEqualTo(EVENT_COUNT);
            queries.cancel();
            assertThat(queries.pageForward(OptionalLong.empty(), 5).size()).isEqualTo(5);
        }
    }

    private static List<Long> expectedIds() {
        List<Long> ids = new ArrayList<>(EVENT_COUNT);
        for (long id = 1; id <= EVENT_COUNT; id++) {
            ids.add(id);
        }
        return ids;
    }

    private SessionDatabase seeded(String name) throws Exception {
        SessionDatabase db = TestSession.migrated(tempDir.resolve(name));
        boolean ok = false;
        try {
            long stream = EventWriterTest.openStream(db, "stream-a");
            try (EventWriter writer = new EventWriter(db.writerConnection(), 64,
                    StringDictionary.DEFAULT_CACHE_ENTRIES)) {
                for (int sequence = 1; sequence <= EVENT_COUNT; sequence++) {
                    writer.write(NormalizedEvent.of(TestSession.event(stream, sequence, sequence)));
                }
                writer.finalizeIngest();
            }
            ok = true;
        } finally {
            if (!ok) {
                db.close();
            }
        }
        return db;
    }
}
