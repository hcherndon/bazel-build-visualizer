package com.holtherndon.bazelviz.storage.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StringDictionaryTest {

    @TempDir
    Path tempDir;

    @Test
    void internsEachValueOnceAndReturnsAStableId() throws Exception {
        try (SessionDatabase db = TestSession.migrated(tempDir.resolve("intern.db"));
                StringDictionary dictionary = new StringDictionary(db.writerConnection())) {

            long first = dictionary.id("//src/main:lib");
            long again = dictionary.id("//src/main:lib");
            long other = dictionary.id("//src/test:lib");

            assertThat(again).isEqualTo(first);
            assertThat(other).isNotEqualTo(first);
            assertThat(EventWriterTest.scalar(db.writerConnection(), "SELECT COUNT(*) FROM strings"))
                    .isEqualTo(2);
            assertThat(dictionary.cacheHits()).isEqualTo(1);
            assertThat(dictionary.cacheMisses()).isEqualTo(2);
        }
    }

    @Test
    void theCacheIsBoundedAndEvictionCostsOnlyALookupNeverAnIdentity() throws Exception {
        int capacity = 16;
        int distinctValues = 500;
        try (SessionDatabase db = TestSession.migrated(tempDir.resolve("bounded.db"));
                StringDictionary dictionary = new StringDictionary(db.writerConnection(), capacity)) {

            Map<String, Long> assigned = new HashMap<>();
            for (int i = 0; i < distinctValues; i++) {
                String value = "//pkg/" + i + ":target";
                assigned.put(value, dictionary.id(value));
                assertThat(dictionary.cachedEntries()).isLessThanOrEqualTo(capacity);
            }

            assertThat(dictionary.capacity()).isEqualTo(capacity);
            assertThat(dictionary.evictions()).isEqualTo(distinctValues - capacity);
            assertThat(EventWriterTest.scalar(db.writerConnection(), "SELECT COUNT(*) FROM strings"))
                    .isEqualTo(distinctValues);

            // Every id survives eviction: the UNIQUE constraint is the authority,
            // the cache only decides whether answering costs a round trip.
            for (Map.Entry<String, Long> entry : assigned.entrySet()) {
                assertThat(dictionary.id(entry.getKey())).isEqualTo(entry.getValue());
            }
            assertThat(EventWriterTest.scalar(db.writerConnection(), "SELECT COUNT(*) FROM strings"))
                    .isEqualTo(distinctValues);
        }
    }

    @Test
    void leastRecentlyUsedIsTheEntryEvicted() throws Exception {
        try (SessionDatabase db = TestSession.migrated(tempDir.resolve("lru.db"));
                StringDictionary dictionary = new StringDictionary(db.writerConnection(), 2)) {

            dictionary.id("a");
            dictionary.id("b");
            dictionary.id("a"); // "a" is now the most recently used, "b" the eldest.
            long missesBefore = dictionary.cacheMisses();
            dictionary.id("c"); // evicts "b"

            dictionary.id("a");
            assertThat(dictionary.cacheMisses()).isEqualTo(missesBefore + 1); // "c" only
            dictionary.id("b");
            assertThat(dictionary.cacheMisses()).isEqualTo(missesBefore + 2); // "b" had gone
        }
    }

    @Test
    void invalidateDropsTheCacheWithoutChangingAnyId() throws Exception {
        try (SessionDatabase db = TestSession.migrated(tempDir.resolve("invalidate.db"));
                StringDictionary dictionary = new StringDictionary(db.writerConnection())) {
            long id = dictionary.id("mnemonic:Javac");
            dictionary.invalidate();
            assertThat(dictionary.cachedEntries()).isZero();
            assertThat(dictionary.id("mnemonic:Javac")).isEqualTo(id);
        }
    }

    @Test
    void rejectsAnUnboundedCache() throws Exception {
        try (SessionDatabase db = TestSession.migrated(tempDir.resolve("reject.db"))) {
            assertThatThrownBy(() -> new StringDictionary(db.writerConnection(), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
