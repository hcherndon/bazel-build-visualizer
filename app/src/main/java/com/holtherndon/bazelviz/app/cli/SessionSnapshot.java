package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.EventQueries;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The counted-up state of a finished or partly finished session: how many
 * events it holds, how they decoded, and what the importer complained about.
 *
 * <p>The event total comes from {@link EventQueries#eventCount()} rather than a
 * private query, so the number the CLI prints is the number the UI's own query
 * layer would print. The two breakdowns are grouped aggregates that
 * {@code EventQueries} does not offer and that no paging API should be bent
 * into providing, so they are explicit SQL here (ADR-006: explicit SQL, no ORM)
 * against a read connection, which cannot disturb an import.
 *
 * <p>The counts are read once, on demand, and never cached: a session on disk
 * is the authority, and a stale summary of an import that was just resumed
 * would be worse than no summary.
 */
record SessionSnapshot(
        long eventCount,
        Map<String, Long> decodeStatusCounts,
        long diagnosticCount,
        Map<String, Long> diagnosticCountsByCode) {

    private static final String COUNT_BY_DECODE_STATUS =
            "SELECT decode_status, COUNT(*) FROM bep_events GROUP BY decode_status";

    private static final String COUNT_BY_DIAGNOSTIC_CODE =
            "SELECT code, COUNT(*) FROM import_diagnostics GROUP BY code";

    SessionSnapshot {
        decodeStatusCounts = Map.copyOf(decodeStatusCounts);
        diagnosticCountsByCode = Map.copyOf(diagnosticCountsByCode);
    }

    /**
     * Reads the session database at {@code sessionRoot}.
     *
     * @throws IOException when the session has no database at all, which means
     *     an import failed before it created one
     */
    static SessionSnapshot read(Path sessionRoot) throws IOException, SQLException {
        Path databaseFile = ManagedSessionLayout.at(sessionRoot).databaseFile();
        if (!Files.isRegularFile(databaseFile)) {
            throw new IOException("session " + sessionRoot + " has no database ("
                    + ManagedSessionLayout.DATABASE_FILE_NAME + "); nothing was ever indexed into it");
        }
        try (SessionDatabase database = SessionDatabase.open(databaseFile)) {
            Connection connection = database.newReadConnection();
            long events;
            try (EventQueries queries = new EventQueries(connection)) {
                events = queries.eventCount();
            }
            Map<String, Long> byStatus = orderedByDecodeStatus(groupCount(connection, COUNT_BY_DECODE_STATUS));
            Map<String, Long> byCode = new TreeMap<>(groupCount(connection, COUNT_BY_DIAGNOSTIC_CODE));
            long diagnostics = byCode.values().stream().mapToLong(Long::longValue).sum();
            return new SessionSnapshot(events, byStatus, diagnostics, byCode);
        }
    }

    /**
     * The decode breakdown in {@link DecodeStatus} declaration order.
     * {@code Map.copyOf} in the constructor deliberately does not preserve
     * insertion order, so the order is reapplied here — it is a property of the
     * rendering, not of the stored value.
     */
    Map<String, Long> decodeStatusOrdered() {
        return orderedByDecodeStatus(decodeStatusCounts);
    }

    /** Diagnostic codes in alphabetical order. */
    Map<String, Long> diagnosticCodesOrdered() {
        return new TreeMap<>(diagnosticCountsByCode);
    }

    /** The decode breakdown rendered as {@code OK 198, UNKNOWN_FIELDS 2}. */
    String decodeStatusSummary() {
        if (decodeStatusCounts.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        decodeStatusOrdered().forEach((status, count) -> {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(status).append(' ').append(Formatting.count(count));
        });
        return text.toString();
    }

    private static Map<String, Long> groupCount(Connection connection, String sql) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                counts.put(rows.getString(1), rows.getLong(2));
            }
        }
        return counts;
    }

    /**
     * Orders the breakdown by the declaration order of {@link DecodeStatus}, so
     * two runs print the same columns in the same order. A status this build
     * does not know — a session written by a newer one — is kept and printed
     * last rather than dropped.
     */
    private static Map<String, Long> orderedByDecodeStatus(Map<String, Long> counts) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (DecodeStatus status : DecodeStatus.values()) {
            Long count = counts.get(status.name());
            if (count != null) {
                ordered.put(status.name(), count);
            }
        }
        counts.forEach(ordered::putIfAbsent);
        return ordered;
    }
}
