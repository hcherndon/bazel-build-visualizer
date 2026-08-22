package com.holtherndon.bazelviz.storage.events;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The read path the Phase 1 Events view needs: a page of events, the total
 * count, one event's full row including its raw location, and parent/child
 * lookups by canonical event-id hash.
 *
 * <h2>Keyset, never OFFSET</h2>
 *
 * <p>Every paging method takes an anchor id and asks for rows on one side of
 * it. {@code OFFSET} would make SQLite walk and discard every skipped row, so
 * its cost grows with how far down the table the user has scrolled — 11 ms
 * mean and 23 ms worst case at 2M rows in the Phase 0 spike, and worse from
 * there. The keyset form seeks straight into the primary-key B-tree and stayed
 * at 0.23 ms mean regardless of depth (docs/performance.md). See
 * {@link EventPage}.
 *
 * <h2>Threading and transactions</h2>
 *
 * <p>Read-only by construction: nothing here writes. The connection stays in
 * auto-commit, which in SQLite means each statement runs inside its own
 * implicit read transaction that ends when the statement does — the "short
 * read transactions" plan 10.9 requires, and what keeps a scrolling UI from
 * pinning a snapshot and stalling WAL checkpoints during live capture.
 *
 * <p>One instance wraps one connection and is <em>not</em> thread-safe; give
 * each reader thread its own via {@link SessionDatabase#newReadConnection()}.
 * The single exception is {@link #cancel()}, which is meant to be called from
 * another thread — typically the EDT abandoning a query whose results are no
 * longer wanted (plan 10.9, "cancellable long-running queries"). None of this
 * may run on the EDT itself.
 */
public final class EventQueries implements AutoCloseable {

    private static final String EVENT_COLUMNS =
            "id, stream_id, sequence, event_type, event_id_hash, last_message, child_count,"
                    + " decode_status, has_unknown_fields, event_micros, receive_micros";

    private static final String COUNT_EVENTS = "SELECT COUNT(*) FROM bep_events";

    private static final String PAGE_FORWARD =
            "SELECT " + EVENT_COLUMNS + " FROM bep_events WHERE id > ? ORDER BY id ASC LIMIT ?";

    private static final String PAGE_BACKWARD =
            "SELECT " + EVENT_COLUMNS + " FROM bep_events WHERE id < ? ORDER BY id DESC LIMIT ?";

    private static final String SELECT_EVENT =
            "SELECT " + EVENT_COLUMNS + ", raw_segment, raw_offset, raw_length"
                    + " FROM bep_events WHERE id = ?";

    private static final String SELECT_EVENT_BY_HASH =
            "SELECT " + EVENT_COLUMNS + " FROM bep_events WHERE event_id_hash = ? ORDER BY id ASC";

    private static final String SELECT_IDENTITY =
            "SELECT event_id_hash, id_kind, id_bytes, display FROM bep_event_ids"
                    + " WHERE event_id_hash = ?";

    private static final String SELECT_CHILDREN_BY_PARENT_ID =
            "SELECT edge.parent_event_id, edge.ordinal, edge.child_event_id_hash, child.id"
                    + " FROM bep_event_edges edge"
                    + " LEFT JOIN bep_events child ON child.event_id_hash = edge.child_event_id_hash"
                    + " WHERE edge.parent_event_id = ? ORDER BY edge.ordinal ASC";

    private static final String SELECT_CHILDREN_BY_PARENT_HASH =
            "SELECT edge.parent_event_id, edge.ordinal, edge.child_event_id_hash, child.id"
                    + " FROM bep_event_edges edge"
                    + " JOIN bep_events parent ON parent.id = edge.parent_event_id"
                    + " LEFT JOIN bep_events child ON child.event_id_hash = edge.child_event_id_hash"
                    + " WHERE parent.event_id_hash = ?"
                    + " ORDER BY edge.parent_event_id ASC, edge.ordinal ASC";

    private static final String SELECT_PARENTS_BY_CHILD_HASH =
            "SELECT parent.id, parent.stream_id, parent.sequence, parent.event_type,"
                    + " parent.event_id_hash, parent.last_message, parent.child_count,"
                    + " parent.decode_status, parent.has_unknown_fields, parent.event_micros,"
                    + " parent.receive_micros"
                    + " FROM bep_event_edges edge"
                    + " JOIN bep_events parent ON parent.id = edge.parent_event_id"
                    + " WHERE edge.child_event_id_hash = ?"
                    + " ORDER BY parent.id ASC";

    // Two forms rather than one with a sentinel anchor: an event-id hash is an
    // arbitrary signed 64-bit value, so Long.MIN_VALUE is a legitimate hash and
    // could not be used as "before the first row" without hiding that row.
    private static final String FIRST_ANNOUNCED_MISSING =
            "SELECT child_event_id_hash, announced_by_event_id FROM bep_announced_missing"
                    + " ORDER BY child_event_id_hash ASC LIMIT ?";

    private static final String PAGE_ANNOUNCED_MISSING =
            "SELECT child_event_id_hash, announced_by_event_id FROM bep_announced_missing"
                    + " WHERE child_event_id_hash > ? ORDER BY child_event_id_hash ASC LIMIT ?";

    private static final String COUNT_ANNOUNCED_MISSING =
            "SELECT COUNT(*) FROM bep_announced_missing";

    private static final String PAGE_DIAGNOSTICS =
            "SELECT id, severity, code, message, segment_index, byte_offset, at_micros"
                    + " FROM import_diagnostics WHERE id > ? ORDER BY id ASC LIMIT ?";

    private final Connection connection;
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    private volatile Statement active;
    private boolean closed;

    /** Wraps a caller-owned read connection; closing this does not close it. */
    public EventQueries(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Cancels whatever query is running, from another thread. A cancelled
     * query's caller sees a {@link SQLException}; the connection stays usable.
     */
    public void cancel() {
        Statement running = active;
        if (running != null) {
            try {
                running.cancel();
            } catch (SQLException ignored) {
                // The statement finished between the read and the cancel;
                // nothing to cancel and nothing to report.
            }
        }
    }

    /** Total events stored. */
    public long eventCount() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            active = statement;
            try (ResultSet rows = statement.executeQuery(COUNT_EVENTS)) {
                return rows.next() ? rows.getLong(1) : 0L;
            } finally {
                active = null;
            }
        }
    }

    /**
     * The page of events after {@code afterId}, ascending.
     *
     * @param afterId anchor from the previous page's {@link EventPage#nextAnchor()},
     *     empty to start at the beginning
     * @param limit maximum rows; must be at least 1
     */
    public EventPage pageForward(OptionalLong afterId, int limit) throws SQLException {
        requireLimit(limit);
        PreparedStatement statement = statement(PAGE_FORWARD);
        statement.setLong(1, afterId.orElse(Long.MIN_VALUE));
        statement.setInt(2, limit);
        List<EventSummary> page = runEventQuery(statement);
        return toPage(page, limit, page.isEmpty() ? 0 : page.getLast().id());
    }

    /**
     * The page of events before {@code beforeId}, returned ascending so the
     * caller never has to reverse it.
     *
     * @param beforeId anchor, empty to start at the end of the table
     */
    public EventPage pageBackward(OptionalLong beforeId, int limit) throws SQLException {
        requireLimit(limit);
        PreparedStatement statement = statement(PAGE_BACKWARD);
        statement.setLong(1, beforeId.orElse(Long.MAX_VALUE));
        statement.setInt(2, limit);
        List<EventSummary> descending = runEventQuery(statement);
        long anchor = descending.isEmpty() ? 0 : descending.getLast().id();
        List<EventSummary> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return toPage(ascending, limit, anchor);
    }

    /** One event's full row, including its raw journal location and its own id. */
    public Optional<EventDetail> event(long id) throws SQLException {
        PreparedStatement statement = statement(SELECT_EVENT);
        statement.setLong(1, id);
        EventSummary summary;
        RawLocation raw;
        active = statement;
        try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            summary = readSummary(rows);
            raw = new RawLocation(rows.getInt(12), rows.getLong(13), rows.getInt(14));
        } finally {
            active = null;
        }
        Optional<EventIdentity> identity = summary.eventIdHash().isPresent()
                ? identity(summary.eventIdHash().getAsLong())
                : Optional.empty();
        return Optional.of(new EventDetail(summary, raw, identity));
    }

    /** The canonical identity behind an event-id hash. */
    public Optional<EventIdentity> identity(long eventIdHash) throws SQLException {
        PreparedStatement statement = statement(SELECT_IDENTITY);
        statement.setLong(1, eventIdHash);
        active = statement;
        try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.of(new EventIdentity(
                    rows.getLong(1), rows.getInt(2), rows.getBytes(3), rows.getString(4)));
        } finally {
            active = null;
        }
    }

    /**
     * Events that arrived carrying {@code eventIdHash}. Normally zero or one;
     * the list form exists because the hash is a hash — a collision must be
     * visible to the caller rather than silently resolved to the first row.
     */
    public List<EventSummary> eventsWithIdHash(long eventIdHash) throws SQLException {
        PreparedStatement statement = statement(SELECT_EVENT_BY_HASH);
        statement.setLong(1, eventIdHash);
        return runEventQuery(statement);
    }

    /** The children a given event row announced, with whether each arrived. */
    public List<AnnouncedChild> childrenOfEvent(long parentEventId) throws SQLException {
        PreparedStatement statement = statement(SELECT_CHILDREN_BY_PARENT_ID);
        statement.setLong(1, parentEventId);
        return runChildQuery(statement);
    }

    /** The children announced by the event whose own id hash is {@code parentIdHash}. */
    public List<AnnouncedChild> childrenOfIdHash(long parentIdHash) throws SQLException {
        PreparedStatement statement = statement(SELECT_CHILDREN_BY_PARENT_HASH);
        statement.setLong(1, parentIdHash);
        return runChildQuery(statement);
    }

    /**
     * Events that announced {@code childIdHash}. A list because BEP permits
     * more than one parent to announce the same child, and collapsing that to
     * "the" parent would invent a tree where the stream describes a DAG.
     */
    public List<EventSummary> parentsOfIdHash(long childIdHash) throws SQLException {
        PreparedStatement statement = statement(SELECT_PARENTS_BY_CHILD_HASH);
        statement.setLong(1, childIdHash);
        return runEventQuery(statement);
    }

    /** How many announced children were never delivered. */
    public long announcedMissingCount() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            active = statement;
            try (ResultSet rows = statement.executeQuery(COUNT_ANNOUNCED_MISSING)) {
                return rows.next() ? rows.getLong(1) : 0L;
            } finally {
                active = null;
            }
        }
    }

    /** A keyset page of the missing-announced-child report, anchored on the child hash. */
    public List<AnnouncedMissingEntry> announcedMissingPage(OptionalLong afterHash, int limit)
            throws SQLException {
        requireLimit(limit);
        PreparedStatement statement;
        if (afterHash.isPresent()) {
            statement = statement(PAGE_ANNOUNCED_MISSING);
            statement.setLong(1, afterHash.getAsLong());
            statement.setInt(2, limit);
        } else {
            statement = statement(FIRST_ANNOUNCED_MISSING);
            statement.setInt(1, limit);
        }
        List<AnnouncedMissingEntry> entries = new ArrayList<>();
        active = statement;
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                entries.add(new AnnouncedMissingEntry(rows.getLong(1), rows.getLong(2)));
            }
        } finally {
            active = null;
        }
        return entries;
    }

    /** A keyset page of import diagnostics, anchored on the diagnostic id. */
    public List<DiagnosticEntry> diagnosticsPage(OptionalLong afterId, int limit)
            throws SQLException {
        requireLimit(limit);
        PreparedStatement statement = statement(PAGE_DIAGNOSTICS);
        statement.setLong(1, afterId.orElse(Long.MIN_VALUE));
        statement.setInt(2, limit);
        List<DiagnosticEntry> entries = new ArrayList<>();
        active = statement;
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long id = rows.getLong(1);
                int segment = rows.getInt(5);
                OptionalInt segmentIndex =
                        rows.wasNull() ? OptionalInt.empty() : OptionalInt.of(segment);
                long offset = rows.getLong(6);
                OptionalLong byteOffset =
                        rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(offset);
                entries.add(new DiagnosticEntry(
                        id,
                        new ImportDiagnostic(
                                DiagnosticSeverity.parse(rows.getString(2)),
                                rows.getString(3),
                                rows.getString(4),
                                segmentIndex,
                                byteOffset,
                                rows.getLong(7))));
            }
        } finally {
            active = null;
        }
        return entries;
    }

    /** Closes the cached statements. The connection belongs to the caller and stays open. */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        SQLException failure = null;
        for (PreparedStatement statement : statements.values()) {
            try {
                statement.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        statements.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private List<EventSummary> runEventQuery(PreparedStatement statement) throws SQLException {
        List<EventSummary> events = new ArrayList<>();
        active = statement;
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.add(readSummary(rows));
            }
        } finally {
            active = null;
        }
        return events;
    }

    private List<AnnouncedChild> runChildQuery(PreparedStatement statement) throws SQLException {
        List<AnnouncedChild> children = new ArrayList<>();
        active = statement;
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                long arrived = rows.getLong(4);
                OptionalLong arrivedEventId =
                        rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(arrived);
                children.add(new AnnouncedChild(
                        rows.getLong(1), rows.getInt(2), rows.getLong(3), arrivedEventId));
            }
        } finally {
            active = null;
        }
        return children;
    }

    private static EventSummary readSummary(ResultSet rows) throws SQLException {
        long hash = rows.getLong(5);
        OptionalLong eventIdHash = rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(hash);
        long micros = rows.getLong(10);
        OptionalLong eventMicros = rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(micros);
        return new EventSummary(
                rows.getLong(1),
                rows.getLong(2),
                rows.getLong(3),
                rows.getInt(4),
                eventIdHash,
                rows.getInt(6) != 0,
                rows.getInt(7),
                DecodeStatus.parse(rows.getString(8)),
                rows.getInt(9) != 0,
                eventMicros,
                rows.getLong(11));
    }

    private static EventPage toPage(List<EventSummary> events, int limit, long anchor) {
        // A short page means the end of the table was reached, so there is no
        // next anchor to hand out. A full page might be the last one; the
        // caller finds out by asking and getting nothing back, which costs one
        // indexed seek and never a wrong answer.
        OptionalLong next = events.size() < limit ? OptionalLong.empty() : OptionalLong.of(anchor);
        return new EventPage(events, next);
    }

    private PreparedStatement statement(String sql) throws SQLException {
        PreparedStatement cached = statements.get(sql);
        if (cached == null) {
            // Bounded by construction: the keys are the handful of compile-time
            // constants above, so this cache cannot grow with the data.
            cached = connection.prepareStatement(sql);
            statements.put(sql, cached);
        }
        return cached;
    }

    private static void requireLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got " + limit);
        }
    }
}
