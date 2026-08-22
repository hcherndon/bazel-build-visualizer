package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.capture.file.importer.JournalPayloadReader;
import com.holtherndon.bazelviz.format.journal.JournalFrame;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventPage;
import com.holtherndon.bazelviz.storage.events.EventQueries;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class in {@code ui-swing} that names {@link SessionDatabase},
 * {@link EventQueries} or {@link JournalPayloadReader}. Everything above it
 * talks to {@link SessionSource} and {@link SessionReader} (plan rule 19).
 *
 * <h2>Reopening an indexed session</h2>
 *
 * <p>Opening is read-only and takes no session lock. A finished session is a
 * directory of files; re-indexing it to look at it would defeat the point of
 * having indexed it once. The manifest is read through {@link SessionManager}
 * so that a session written by a newer build is refused with a version error
 * rather than silently misread, and the database file is required to exist —
 * {@link SessionDatabase#open} would otherwise create an empty one and every
 * query would then fail with "no such table", which reads like a corrupt
 * session instead of an interrupted one.
 */
public final class SqliteSessionSource implements SessionSource {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionSource.class);

    private final Path root;
    private final SessionInfo info;
    private final SessionDatabase database;
    private final Path journalDirectory;
    private final List<SqliteSessionReader> readers = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private SqliteSessionSource(
            Path root, SessionInfo info, SessionDatabase database, Path journalDirectory) {
        this.root = root;
        this.info = info;
        this.database = database;
        this.journalDirectory = journalDirectory;
    }

    /**
     * Opens the managed session at {@code sessionRoot} for reading.
     *
     * <p>Blocking I/O: call it from a worker thread, never the EDT.
     *
     * @throws SessionDataException if the directory is not a readable managed
     *     session, or holds no database yet
     */
    public static SqliteSessionSource open(SessionManager sessions, Path sessionRoot) {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(sessionRoot, "sessionRoot");
        Path root = sessionRoot.toAbsolutePath().normalize();
        ManagedSessionLayout layout = ManagedSessionLayout.at(root);
        SessionManifest manifest;
        try {
            manifest = sessions.readManifest(root);
        } catch (IOException e) {
            throw new SessionDataException("cannot read the session manifest in " + root, e);
        }
        if (!Files.isRegularFile(layout.databaseFile())) {
            throw new SessionDataException("session " + root + " has no "
                    + ManagedSessionLayout.DATABASE_FILE_NAME
                    + "; its import was interrupted before the database was created, so there is"
                    + " nothing indexed to open. Resume or re-import the source instead.");
        }
        SessionDatabase database;
        try {
            database = SessionDatabase.open(layout.databaseFile());
        } catch (SQLException e) {
            throw new SessionDataException("cannot open " + layout.databaseFile(), e);
        }
        return new SqliteSessionSource(
                root, SessionInfo.of(root, manifest), database, layout.rawDirectory());
    }

    @Override
    public SessionInfo info() {
        return info;
    }

    @Override
    public SessionReader openReader() {
        if (closed) {
            throw new SessionDataException("session " + root + " is closed");
        }
        Connection connection;
        try {
            connection = database.newReadConnection();
        } catch (SQLException e) {
            throw new SessionDataException("cannot open a read connection to " + root, e);
        }
        SqliteSessionReader reader = new SqliteSessionReader(
                connection, new EventQueries(connection), new JournalPayloadReader(journalDirectory));
        readers.add(reader);
        return reader;
    }

    @Override
    public void close() {
        closed = true;
        for (SqliteSessionReader reader : readers) {
            reader.close();
        }
        readers.clear();
        try {
            database.close();
        } catch (SQLException e) {
            // Nothing above can act on this, and throwing would mask whatever
            // the caller was actually doing when it closed the view.
            log.warn("failed to close the session database for {}", root, e);
        }
    }

    /** One connection's worth of reads. Bound to one thread by its caller. */
    private final class SqliteSessionReader implements SessionReader {

        private final Connection connection;
        private final EventQueries queries;
        private final JournalPayloadReader journal;
        private boolean readerClosed;

        SqliteSessionReader(
                Connection connection, EventQueries queries, JournalPayloadReader journal) {
            this.connection = connection;
            this.queries = queries;
            this.journal = journal;
        }

        @Override
        public long eventCount() {
            try {
                return queries.eventCount();
            } catch (SQLException e) {
                throw new SessionDataException("counting events in " + root + " failed", e);
            }
        }

        @Override
        public List<EventSummary> pageAfter(OptionalLong afterId, int limit) {
            try {
                EventPage page = queries.pageForward(afterId, limit);
                return page.events();
            } catch (SQLException e) {
                throw new SessionDataException(
                        "reading " + limit + " events after " + describe(afterId) + " failed", e);
            }
        }

        @Override
        public List<EventSummary> pageBefore(OptionalLong beforeId, int limit) {
            try {
                EventPage page = queries.pageBackward(beforeId, limit);
                return page.events();
            } catch (SQLException e) {
                throw new SessionDataException(
                        "reading " + limit + " events before " + describe(beforeId) + " failed", e);
            }
        }

        @Override
        public Optional<EventDetail> event(long id) {
            try {
                return queries.event(id);
            } catch (SQLException e) {
                throw new SessionDataException("reading event " + id + " failed", e);
            }
        }

        @Override
        public RawPayload rawPayload(RawLocation location) {
            Objects.requireNonNull(location, "location");
            try {
                JournalFrame frame = journal.readFrame(location);
                if (frame.frameOffset() != location.offset()) {
                    throw new SessionDataException("the journal frame in segment "
                            + location.segment() + " starts at " + frame.frameOffset()
                            + ", but the event row records " + location.offset());
                }
                byte[] payload = frame.requirePayload();
                if (payload.length != location.length()) {
                    // The row and the journal disagree about the same record.
                    // Reporting it is the whole point of storing both.
                    throw new SessionDataException("the journal frame at segment "
                            + location.segment() + " offset " + location.offset() + " holds "
                            + payload.length + " bytes, but the event row records "
                            + location.length());
                }
                return new RawPayload(payload, frame.header().sourceKind());
            } catch (IOException e) {
                throw new SessionDataException("reading the raw payload at segment "
                        + location.segment() + " offset " + location.offset() + " failed", e);
            }
        }

        @Override
        public void cancelRunningQuery() {
            queries.cancel();
        }

        @Override
        public void close() {
            if (readerClosed) {
                return;
            }
            readerClosed = true;
            try {
                queries.close();
            } catch (SQLException e) {
                log.warn("failed to close prepared statements for {}", root, e);
            }
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("failed to close a read connection for {}", root, e);
            }
        }

        private String describe(OptionalLong anchor) {
            return anchor.isPresent() ? "id " + anchor.getAsLong() : "the start of the table";
        }
    }
}
