package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The three schema-v1 tables that describe a session rather than its events:
 * {@code session_info}, {@code capture_sources} and the identity columns of
 * {@code event_streams}.
 *
 * <p>Explicit SQL, no ORM (ADR-006). These are small, singular writes that
 * happen a handful of times per import, so they are written directly rather
 * than through the batching machinery that {@code bep_events} needs.
 *
 * <p>Unknown-is-not-zero is enforced at every {@code setNull} below: a source
 * of unknown size stores NULL, a session that has not finished stores NULL
 * {@code finalized_micros}. Nothing here ever writes 0 to mean "we do not
 * know" (plan 11.4).
 */
final class SessionTables {

    private SessionTables() {}

    private static final String UPSERT_SESSION_INFO =
            "INSERT INTO session_info"
                    + " (singleton, session_uuid, state, created_micros, finalized_micros, app_version)"
                    + " VALUES (1, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (singleton) DO UPDATE SET"
                    + " session_uuid = excluded.session_uuid,"
                    + " state = excluded.state,"
                    + " created_micros = excluded.created_micros,"
                    + " finalized_micros = excluded.finalized_micros,"
                    + " app_version = excluded.app_version";

    private static final String INSERT_CAPTURE_SOURCE =
            "INSERT INTO capture_sources (kind, path, sha256, byte_size, completeness, note)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_CAPTURE_SOURCE =
            "UPDATE capture_sources SET kind = ?, path = ?, sha256 = ?, byte_size = ?,"
                    + " completeness = ?, note = ? WHERE id = ?";

    private static final String FIND_CAPTURE_SOURCE =
            "SELECT id FROM capture_sources ORDER BY id LIMIT 1";

    private static final String UPDATE_STREAM_IDENTITY =
            "UPDATE event_streams SET invocation_id = ?, build_id = ? WHERE id = ?";

    /** Writes the singleton {@code session_info} row, creating or replacing it. */
    static void writeSessionInfo(
            Connection connection,
            SessionId sessionId,
            SessionState state,
            long createdMicros,
            OptionalLong finalizedMicros,
            String appVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_SESSION_INFO)) {
            statement.setString(1, sessionId.toString());
            statement.setString(2, state.name());
            statement.setLong(3, createdMicros);
            setNullableLong(statement, 4, finalizedMicros);
            statement.setString(5, appVersion);
            statement.executeUpdate();
        }
        commitIfNeeded(connection);
    }

    /** Inserts the import's capture source and returns its row id. */
    static long insertCaptureSource(
            Connection connection,
            String kind,
            String path,
            String sha256,
            OptionalLong byteSize,
            Completeness completeness,
            Optional<String> note)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(INSERT_CAPTURE_SOURCE, Statement.RETURN_GENERATED_KEYS)) {
            bindCaptureSource(statement, kind, path, sha256, byteSize, completeness, note);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    commitIfNeeded(connection);
                    return keys.getLong(1);
                }
            }
        }
        commitIfNeeded(connection);
        // Generated keys are supported by sqlite-jdbc; the fallback exists so a
        // driver quirk degrades to a lookup rather than to a wrong row id.
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(FIND_CAPTURE_SOURCE)) {
            if (rows.next()) {
                return rows.getLong(1);
            }
        }
        throw new SQLException("capture_sources row vanished immediately after insert");
    }

    static void updateCaptureSource(
            Connection connection,
            long id,
            String kind,
            String path,
            String sha256,
            OptionalLong byteSize,
            Completeness completeness,
            Optional<String> note)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_CAPTURE_SOURCE)) {
            bindCaptureSource(statement, kind, path, sha256, byteSize, completeness, note);
            statement.setLong(7, id);
            statement.executeUpdate();
        }
        commitIfNeeded(connection);
    }

    /** The row id of the single capture source of a file import, when one exists. */
    static OptionalLong findCaptureSource(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(FIND_CAPTURE_SOURCE)) {
            return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
        }
    }

    /**
     * Records the stream's invocation id once an event has revealed it.
     *
     * <p>{@code StreamRegistry} sets these only at creation, and a file import
     * has not read the {@code BuildStarted} event yet at that point. Leaving
     * them NULL rather than guessing, then filling them in when the evidence
     * arrives, is the honest order.
     */
    static void updateStreamIdentity(
            Connection connection, long streamId, Optional<String> invocationId, Optional<String> buildId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_STREAM_IDENTITY)) {
            setNullableString(statement, 1, invocationId);
            setNullableString(statement, 2, buildId);
            statement.setLong(3, streamId);
            statement.executeUpdate();
        }
        commitIfNeeded(connection);
    }

    static long countEvents(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM bep_events")) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    static long countDiagnostics(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM import_diagnostics")) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /** Highest {@code sequence} stored for a stream, empty when it has no events. */
    /**
     * Deletes rows at or beyond {@code fromSequence}, and everything hanging
     * off them, returning how many events went.
     *
     * <p>Used on resume when the database is further ahead than the journal.
     * The journal is forced before the checkpoint is written, so a crash can
     * leave committed rows whose frames were still in the writer's staging
     * buffer. Those rows name a raw location that no longer exists, so they
     * cannot be read back and cannot be trusted; the journal is the authority
     * on frame identity (ADR-004) and the database is made to agree with it.
     */
    static long deleteEventsFromSequence(Connection connection, long streamId, long fromSequence)
            throws SQLException {
        long removed;
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM bep_events WHERE stream_id = ? AND sequence >= ?")) {
            count.setLong(1, streamId);
            count.setLong(2, fromSequence);
            try (ResultSet rows = count.executeQuery()) {
                removed = rows.next() ? rows.getLong(1) : 0L;
            }
        }
        if (removed == 0) {
            return 0;
        }
        String scope = " WHERE parent_event_id IN"
                + " (SELECT id FROM bep_events WHERE stream_id = ? AND sequence >= ?)";
        for (String sql : new String[] {
                "DELETE FROM bep_event_edges" + scope,
                "DELETE FROM bep_announced_missing WHERE announced_by_event_id IN"
                        + " (SELECT id FROM bep_events WHERE stream_id = ? AND sequence >= ?)",
                "DELETE FROM bep_events WHERE stream_id = ? AND sequence >= ?"}) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, streamId);
                statement.setLong(2, fromSequence);
                statement.executeUpdate();
            }
        }
        commitIfNeeded(connection);
        return removed;
    }

    static OptionalLong maxSequence(Connection connection, long streamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT MAX(sequence) FROM bep_events WHERE stream_id = ?")) {
            statement.setLong(1, streamId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    long value = rows.getLong(1);
                    return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
                }
            }
        }
        return OptionalLong.empty();
    }

    private static void bindCaptureSource(
            PreparedStatement statement,
            String kind,
            String path,
            String sha256,
            OptionalLong byteSize,
            Completeness completeness,
            Optional<String> note)
            throws SQLException {
        statement.setString(1, kind);
        statement.setString(2, path);
        if (sha256 == null || sha256.isBlank()) {
            statement.setNull(3, Types.VARCHAR);
        } else {
            statement.setString(3, sha256);
        }
        setNullableLong(statement, 4, byteSize);
        statement.setString(5, completeness.name());
        setNullableString(statement, 6, note);
    }

    private static void setNullableLong(PreparedStatement statement, int index, OptionalLong value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.getAsLong());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static void setNullableString(
            PreparedStatement statement, int index, Optional<String> value) throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.get());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static void commitIfNeeded(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }
}
