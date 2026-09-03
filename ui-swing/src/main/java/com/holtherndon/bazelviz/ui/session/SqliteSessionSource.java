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
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.query.AdHocQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class in {@code ui-swing} that names {@link SessionDatabase}, {@link EventQueries} or
 * {@link JournalPayloadReader}. Everything above it talks to {@link SessionSource} and {@link
 * SessionReader} (plan rule 19).
 *
 * <h2>Reopening an indexed session</h2>
 *
 * <p>Opening is read-only and takes no session lock. A finished session is a directory of files;
 * re-indexing it to look at it would defeat the point of having indexed it once. The manifest is
 * read through {@link SessionManager} so that a session written by a newer build is refused with a
 * version error rather than silently misread, and the database file is required to exist — {@link
 * SessionDatabase#open} would otherwise create an empty one and every query would then fail with
 * "no such table", which reads like a corrupt session instead of an interrupted one.
 */
public final class SqliteSessionSource implements SessionSource {

  private static final Logger log = LoggerFactory.getLogger(SqliteSessionSource.class);

  private final Path root;
  private final SessionInfo info;
  private final SessionDatabase database;
  private final Path journalDirectory;
  private final List<SqliteSessionReader> readers = new CopyOnWriteArrayList<>();
  private final List<EntityReader> entityReaders = new CopyOnWriteArrayList<>();
  private final List<QueryReader> queryReaders = new CopyOnWriteArrayList<>();
  private final List<StarlarkProfileReader> starlarkReaders = new CopyOnWriteArrayList<>();
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
   * @throws SessionDataException if the directory is not a readable managed session, or holds no
   *     database yet
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
      throw new SessionDataException(
          "session "
              + root
              + " has no "
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
    try {
      requireCurrentSchema(root, database);
    } catch (SessionDataException refused) {
      try {
        database.close();
      } catch (SQLException ignored) {
        // The refusal is what matters; a database that will not close
        // adds nothing the caller can act on.
      }
      throw refused;
    }
    SessionInfo info = invocationPaths(SessionInfo.of(root, manifest), database);
    return new SqliteSessionSource(root, info, database, layout.rawDirectory());
  }

  /**
   * A pure BEP import has no invocation paths in its manifest, but its BuildStarted row often has
   * both. Read that one row while opening is already on a worker, so label/file actions work for
   * imports too without adding SQL to a Swing component.
   */
  private static SessionInfo invocationPaths(SessionInfo info, SessionDatabase database) {
    String sql =
        "SELECT working_directory, workspace_directory FROM build_invocation"
            + " WHERE singleton = 1";
    try (Connection connection = database.newReadConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return info;
      }
      return info.withInvocationPaths(
          Optional.ofNullable(rows.getString(1)), Optional.ofNullable(rows.getString(2)));
    } catch (SQLException failure) {
      // Paths are an optional local convenience; a session whose
      // entities are readable must not fail to open because this one
      // supplementary row could not be read.
      log.debug("could not read invocation paths for {}", info.root(), failure);
      return info;
    }
  }

  /**
   * Refuses a session this build cannot read, with a reason.
   *
   * <p>Opening is read-only, so it does not migrate: a view is not a licence to rewrite the file
   * the user opened. It does have to <em>check</em>, though. Without this, a session written before
   * a schema version opened happily and then answered every entity query with "no such table" —
   * five views each showing a different SQL error, and an overview whose headline was a stack-trace
   * string. The raw journal is the source of truth (ADR-004), so re-importing rebuilds the session
   * from its own bytes.
   */
  private static void requireCurrentSchema(Path root, SessionDatabase database) {
    int version;
    try {
      version = MigrationRunner.currentVersion(database.writerConnection());
    } catch (SQLException e) {
      throw new SessionDataException("cannot read the schema version of " + root, e);
    }
    MigrationRunner runner = MigrationRunner.standard();
    if (version > runner.latestVersion()) {
      throw new SessionDataException(
          "session "
              + root
              + " was written by a newer build"
              + " (schema version "
              + version
              + "; this build understands "
              + runner.latestVersion()
              + "). Upgrade, or open it with the build that"
              + " wrote it.");
    }
    if (version < runner.latestVersion()) {
      throw new SessionDataException(
          "session "
              + root
              + " was indexed by an older build"
              + " (schema version "
              + version
              + "; this build reads "
              + runner.latestVersion()
              + "), so its targets, actions and tests were never"
              + " normalized. Import its source again — the raw events are preserved, so"
              + " nothing is lost by rebuilding.");
    }
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
    SqliteSessionReader reader =
        new SqliteSessionReader(
            connection, new EventQueries(connection), new JournalPayloadReader(journalDirectory));
    readers.add(reader);
    return reader;
  }

  @Override
  public Connection openTimelineConnection() {
    if (closed) {
      throw new SessionDataException("session " + root + " is closed");
    }
    try {
      return database.newReadConnection();
    } catch (SQLException e) {
      throw new SessionDataException("cannot open a read connection to " + root, e);
    }
  }

  @Override
  public GraphQueries openGraphQueries() {
    if (closed) {
      throw new SessionDataException("session " + root + " is closed");
    }
    Connection connection;
    try {
      connection = database.newReadConnection();
    } catch (SQLException e) {
      throw new SessionDataException("cannot open a read connection to " + root, e);
    }
    // The CSR index files live beside the database, in the directory the
    // session layout already reserves for them.
    return new GraphQueries(connection, ManagedSessionLayout.at(root).indexesDirectory());
  }

  @Override
  public MetricQueries openMetricQueries() {
    if (closed) {
      throw new SessionDataException("session " + root + " is closed");
    }
    Connection connection;
    try {
      connection = database.newReadConnection();
    } catch (SQLException e) {
      throw new SessionDataException("cannot open a read connection to " + root, e);
    }
    // The graph reader is created here and owned by the metric reader,
    // which closes it: sharing one with the graph views would mean two
    // threads on one JDBC connection.
    return new MetricQueries(connection, openGraphQueries());
  }

  @Override
  public StarlarkProfileReader openStarlarkProfileReader() {
    if (closed) {
      throw new SessionDataException("session " + root + " is closed");
    }
    Connection connection;
    try {
      connection = database.newReadConnection();
    } catch (SQLException e) {
      throw new SessionDataException("cannot open a read connection to " + root, e);
    }
    StarlarkProfileReader reader = new SqliteStarlarkProfileReader(root.toString(), connection);
    starlarkReaders.add(reader);
    return reader;
  }

  @Override
  public QueryReader openQueryReader() {
    if (closed) {
      throw new SessionDataException("session " + root + " is closed");
    }
    Connection connection;
    try {
      // newQueryConnection, not newReadConnection: this is the one
      // connection in the application that runs SQL nobody here wrote.
      connection = database.newQueryConnection();
    } catch (SQLException e) {
      throw new SessionDataException("cannot open a query connection to " + root, e);
    }
    QueryReader reader;
    try {
      reader = new SqliteQueryReader(root.toString(), connection, new AdHocQueries(connection));
    } catch (RuntimeException refused) {
      try {
        connection.close();
      } catch (SQLException ignored) {
        // The refusal is what matters.
      }
      throw refused;
    }
    queryReaders.add(reader);
    return reader;
  }

  @Override
  public EntityReader openEntityReader() {
    if (closed) {
      throw new SessionDataException("session " + root + " is closed");
    }
    Connection connection;
    try {
      connection = database.newReadConnection();
    } catch (SQLException e) {
      throw new SessionDataException("cannot open a read connection to " + root, e);
    }
    EntityReader reader = new SqliteEntityReader(root.toString(), connection);
    entityReaders.add(reader);
    return reader;
  }

  @Override
  public void close() {
    closed = true;
    for (SqliteSessionReader reader : readers) {
      reader.close();
    }
    readers.clear();
    for (EntityReader reader : entityReaders) {
      try {
        reader.close();
      } catch (RuntimeException failure) {
        // A view's executor may still be finishing a query on this
        // connection: the views are asked to let go first, but their
        // shutdown is asynchronous. Closing under an in-flight
        // statement throws, and there is nothing a caller could do
        // about it -- the session is being torn down either way.
        log.debug("a reader for {} would not close", root, failure);
      }
    }
    entityReaders.clear();
    for (QueryReader reader : queryReaders) {
      try {
        reader.close();
      } catch (RuntimeException failure) {
        // Same reason as the entity readers above: the query card's
        // executor may still be inside a statement, and the session is
        // being torn down either way.
        log.debug("a query reader for {} would not close", root, failure);
      }
    }
    queryReaders.clear();
    for (StarlarkProfileReader reader : starlarkReaders) {
      try {
        reader.close();
      } catch (RuntimeException failure) {
        log.debug("a Starlark profile reader for {} would not close", root, failure);
      }
    }
    starlarkReaders.clear();
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

    SqliteSessionReader(Connection connection, EventQueries queries, JournalPayloadReader journal) {
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
          throw new SessionDataException(
              "the journal frame in segment "
                  + location.segment()
                  + " starts at "
                  + frame.frameOffset()
                  + ", but the event row records "
                  + location.offset());
        }
        byte[] payload = frame.requirePayload();
        if (payload.length != location.length()) {
          // The row and the journal disagree about the same record.
          // Reporting it is the whole point of storing both.
          throw new SessionDataException(
              "the journal frame at segment "
                  + location.segment()
                  + " offset "
                  + location.offset()
                  + " holds "
                  + payload.length
                  + " bytes, but the event row records "
                  + location.length());
        }
        return new RawPayload(payload, frame.header().sourceKind());
      } catch (IOException e) {
        throw new SessionDataException(
            "reading the raw payload at segment "
                + location.segment()
                + " offset "
                + location.offset()
                + " failed",
            e);
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
