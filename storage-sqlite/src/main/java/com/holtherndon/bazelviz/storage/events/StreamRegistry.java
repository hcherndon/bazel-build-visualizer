package com.holtherndon.bazelviz.storage.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Resolves {@code event_streams} rows, which every {@code bep_events} row has
 * a foreign key to.
 *
 * <p>A stream is identified externally by its {@code stream_key} — a stable
 * string the capture layer derives from the BES stream identity or from the
 * imported file — and internally by its row id. Nothing outside this database
 * ever sees the row id, per plan 11.1: row ids are not cross-session
 * identities.
 *
 * <p>{@link #open(String, Optional, Optional)} is idempotent on the stream
 * key, so replaying a journal after a crash re-resolves the same id instead of
 * creating a second stream.
 *
 * <p>Sequence bookkeeping ({@code first_sequence}, {@code last_sequence},
 * {@code contiguous_through}, {@code duplicate_count}, {@code gap_count}) is
 * left to the ingestion layer that actually observes the sequence numbers;
 * this class only persists what it is told, through {@link #updateProgress}.
 * Those columns stay NULL until something has been observed — an unstarted
 * stream must not report "first sequence 0" (plan 11.4).
 */
public final class StreamRegistry implements AutoCloseable {

    /** Default state for a stream that has been opened but not finished. */
    public static final String STATE_OPEN = "OPEN";

    private static final String INSERT =
            "INSERT INTO event_streams (stream_key, invocation_id, build_id, state)"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT (stream_key) DO NOTHING";
    private static final String SELECT = "SELECT id FROM event_streams WHERE stream_key = ?";
    private static final String UPDATE_PROGRESS =
            "UPDATE event_streams SET first_sequence = ?, last_sequence = ?,"
                    + " contiguous_through = ?, duplicate_count = ?, gap_count = ?, state = ?"
                    + " WHERE id = ?";

    private final PreparedStatement insert;
    private final PreparedStatement select;
    private final PreparedStatement updateProgress;
    private boolean closed;

    public StreamRegistry(Connection connection) throws SQLException {
        this.insert = connection.prepareStatement(INSERT);
        this.select = connection.prepareStatement(SELECT);
        this.updateProgress = connection.prepareStatement(UPDATE_PROGRESS);
    }

    /** Returns the id of {@code streamKey}, creating the row on first sight. */
    public long open(String streamKey, Optional<String> invocationId, Optional<String> buildId)
            throws SQLException {
        Objects.requireNonNull(streamKey, "streamKey");
        OptionalLong existing = find(streamKey);
        if (existing.isPresent()) {
            return existing.getAsLong();
        }
        insert.setString(1, streamKey);
        setNullableString(insert, 2, invocationId);
        setNullableString(insert, 3, buildId);
        insert.setString(4, STATE_OPEN);
        insert.executeUpdate();
        return find(streamKey).orElseThrow(() -> new IllegalStateException(
                "event stream vanished immediately after insert: " + streamKey));
    }

    /** Convenience for a stream with no known invocation or build id. */
    public long open(String streamKey) throws SQLException {
        return open(streamKey, Optional.empty(), Optional.empty());
    }

    /** The id of an already-registered stream, or empty. */
    public OptionalLong find(String streamKey) throws SQLException {
        select.setString(1, streamKey);
        try (ResultSet rows = select.executeQuery()) {
            return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
        }
    }

    /**
     * Persists observed sequence bookkeeping for a stream. Absent values are
     * written as NULL, never as 0.
     */
    public void updateProgress(
            long streamId,
            OptionalLong firstSequence,
            OptionalLong lastSequence,
            OptionalLong contiguousThrough,
            long duplicateCount,
            long gapCount,
            String state)
            throws SQLException {
        setNullableLong(updateProgress, 1, firstSequence);
        setNullableLong(updateProgress, 2, lastSequence);
        setNullableLong(updateProgress, 3, contiguousThrough);
        updateProgress.setLong(4, duplicateCount);
        updateProgress.setLong(5, gapCount);
        updateProgress.setString(6, state);
        updateProgress.setLong(7, streamId);
        updateProgress.executeUpdate();
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        try (PreparedStatement a = insert;
                PreparedStatement b = select;
                PreparedStatement c = updateProgress) {
            // try-with-resources closes all three.
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, OptionalLong value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.getAsLong());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }
}
