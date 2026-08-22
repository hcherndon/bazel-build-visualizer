package com.holtherndon.bazelviz.storage.events;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.OptionalLong;

/** Shared fixtures for the ingestion tests: a migrated database and event builders. */
final class TestSession {

    private TestSession() {}

    /** Opens a database at {@code file} with schema v1 applied. */
    static SessionDatabase migrated(Path file) throws SQLException {
        SessionDatabase database = SessionDatabase.open(file);
        boolean ok = false;
        try {
            MigrationRunner.standard().migrate(database);
            ok = true;
        } finally {
            if (!ok) {
                database.close();
            }
        }
        return database;
    }

    /** A fully populated event: every optional value present. */
    static EventRecord event(long streamId, long sequence, long idHash) {
        return new EventRecord(
                streamId,
                sequence,
                /* eventType= */ 3,
                OptionalLong.of(idHash),
                /* lastMessage= */ false,
                /* childCount= */ 0,
                /* rawSegment= */ 0,
                /* rawOffset= */ sequence * 128,
                /* rawLength= */ 64,
                DecodeStatus.OK,
                /* hasUnknownFields= */ false,
                OptionalLong.of(1_700_000_000_000_000L + sequence),
                /* receiveMicros= */ 1_700_000_000_500_000L + sequence);
    }

    /** An event whose id hash and event timestamp are genuinely unavailable. */
    static EventRecord eventWithUnknowns(long streamId, long sequence) {
        return new EventRecord(
                streamId,
                sequence,
                /* eventType= */ 0,
                OptionalLong.empty(),
                /* lastMessage= */ false,
                /* childCount= */ 0,
                /* rawSegment= */ 0,
                /* rawOffset= */ sequence * 128,
                /* rawLength= */ 16,
                DecodeStatus.FAILED,
                /* hasUnknownFields= */ true,
                OptionalLong.empty(),
                /* receiveMicros= */ 1_700_000_000_500_000L + sequence);
    }

    static EventIdentity identity(long hash) {
        return new EventIdentity(
                hash, /* idKind= */ 7, new byte[] {1, 2, 3, (byte) hash}, "target://x/" + hash);
    }
}
