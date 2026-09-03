package com.holtherndon.bazelviz.storage.events;

import com.holtherndon.bazelviz.storage.BatchedInsert;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.SchemaIndexes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Objects;

/**
 * The batched ingestion path into {@code bep_events}, {@code bep_event_ids}, {@code
 * bep_event_edges} and {@code bep_announced_missing}.
 *
 * <h2>Idempotence</h2>
 *
 * <p>Every insert here is {@code ON CONFLICT DO NOTHING}, because re-delivery is normal rather than
 * exceptional: BES retransmits sequences, and crash recovery replays the raw journal from the last
 * checkpoint (plan 21.1), so the same event legitimately arrives at this class twice. {@code UNIQUE
 * (stream_id, sequence)} turns the repeat into a no-op that leaves exactly one row.
 *
 * <h2>Why edges resolve their parent in SQL</h2>
 *
 * <p>{@code bep_event_edges.parent_event_id} is a foreign key, and row ids are assigned by SQLite
 * as rows insert — so a batching writer does not know the id of the event it just queued. Worse, on
 * a duplicate delivery no id is assigned at all, and an edge pointing at the id we <em>assumed</em>
 * would be assigned would violate the foreign key. So the edge insert never names an id. It selects
 * one:
 *
 * <pre>{@code
 * INSERT INTO bep_event_edges (parent_event_id, child_event_id_hash, ordinal)
 * SELECT e.id, ?, ? FROM bep_events e WHERE e.stream_id = ? AND e.sequence = ?
 * ON CONFLICT DO NOTHING
 * }</pre>
 *
 * <p>That makes three problems disappear at once. The parent id is always the canonical one,
 * whether this delivery inserted it or an earlier one did. A duplicate delivery resolves to the
 * original parent and then hits the {@code (parent_event_id, ordinal)} primary key, so it is a
 * no-op. And if the parent genuinely is not there, the SELECT returns no rows and zero edges are
 * inserted — no foreign-key violation, no invented parent. The lookup rides the index that {@code
 * UNIQUE (stream_id, sequence)} already creates, so it costs nothing extra and works before the
 * finalize indexes exist.
 *
 * <p>This does require that the event batch is executed before the edge batch. The batches are
 * therefore driven from here, in dependency order, rather than each {@link BatchedInsert}
 * auto-flushing on its own row count.
 *
 * <h2>Announced children</h2>
 *
 * <p>See {@link #resolveAnnouncedMissing()}. Nothing about announced children is held in Java
 * memory.
 *
 * <p>Not thread-safe: it owns the single writer connection's statements. All work here is blocking
 * I/O and must never run on the Swing EDT (plan 19.1).
 */
public final class EventWriter implements AutoCloseable {

  /** Events per transaction. Plan 9.3's tunable default for normalization batching. */
  public static final int DEFAULT_BATCH_SIZE = 5_000;

  /**
   * Auto-flush inside {@link BatchedInsert} is switched off by giving it a batch size it can never
   * reach; this class flushes the three statements itself, in dependency order.
   */
  private static final int AUTO_FLUSH_DISABLED = Integer.MAX_VALUE;

  private static final String INSERT_EVENT =
      "INSERT INTO bep_events (stream_id, sequence, event_type, event_id_hash,"
          + " last_message, child_count, raw_segment, raw_offset, raw_length,"
          + " decode_status, has_unknown_fields, event_micros, receive_micros)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT (stream_id, sequence) DO NOTHING";

  private static final String INSERT_IDENTITY =
      "INSERT INTO bep_event_ids (event_id_hash, id_kind, id_bytes, display)"
          + " VALUES (?, ?, ?, ?) ON CONFLICT (event_id_hash) DO NOTHING";

  private static final String INSERT_EDGE =
      "INSERT INTO bep_event_edges (parent_event_id, child_event_id_hash, ordinal)"
          + " SELECT e.id, ?, ? FROM bep_events e"
          + " WHERE e.stream_id = ? AND e.sequence = ?"
          + " ON CONFLICT DO NOTHING";

  // Re-runnable in two halves so it can be called mid-stream as well as at
  // finalization: clear entries whose child has since arrived, then record
  // everything announced that is still absent.
  private static final String CLEAR_ARRIVED_ANNOUNCED =
      "DELETE FROM bep_announced_missing WHERE EXISTS ("
          + " SELECT 1 FROM bep_events e"
          + " WHERE e.event_id_hash = bep_announced_missing.child_event_id_hash)";

  private static final String RECORD_STILL_MISSING =
      "INSERT INTO bep_announced_missing (child_event_id_hash, announced_by_event_id)"
          + " SELECT announced.child_event_id_hash, announced.first_parent FROM ("
          + "   SELECT child_event_id_hash, MIN(parent_event_id) AS first_parent"
          + "   FROM bep_event_edges GROUP BY child_event_id_hash) AS announced"
          + " WHERE NOT EXISTS ("
          + "   SELECT 1 FROM bep_events e"
          + "   WHERE e.event_id_hash = announced.child_event_id_hash)"
          + " ON CONFLICT DO NOTHING";

  private static final String COUNT_EVENTS = "SELECT COUNT(*) FROM bep_events";

  private final Connection connection;
  private final int batchSize;
  private final BatchedInsert events;
  private final BatchedInsert identities;
  private final BatchedInsert edges;
  private final DiagnosticWriter diagnostics;
  private final StringDictionary strings;

  private final long baselineEventRows;
  private long eventsOffered;
  private long identitiesOffered;
  private long edgesOffered;
  private int sinceFlush;
  private boolean closed;

  public EventWriter(SessionDatabase database) throws SQLException {
    this(database.writerConnection(), DEFAULT_BATCH_SIZE, StringDictionary.DEFAULT_CACHE_ENTRIES);
  }

  public EventWriter(Connection connection) throws SQLException {
    this(connection, DEFAULT_BATCH_SIZE, StringDictionary.DEFAULT_CACHE_ENTRIES);
  }

  /**
   * @param connection the session database's single writer connection
   * @param batchSize events per committed transaction
   * @param stringCacheEntries bound on the interning cache — see {@link StringDictionary}
   */
  public EventWriter(Connection connection, int batchSize, int stringCacheEntries)
      throws SQLException {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
    }
    this.connection = connection;
    this.batchSize = batchSize;
    this.baselineEventRows = countEvents(connection);
    // Construction order matters: BatchedInsert suspends auto-commit and
    // restores what it found, so these must be closed in reverse order.
    this.events = new BatchedInsert(connection, INSERT_EVENT, AUTO_FLUSH_DISABLED);
    this.identities = new BatchedInsert(connection, INSERT_IDENTITY, AUTO_FLUSH_DISABLED);
    this.edges = new BatchedInsert(connection, INSERT_EDGE, AUTO_FLUSH_DISABLED);
    this.diagnostics = new DiagnosticWriter(connection);
    this.strings = new StringDictionary(connection, stringCacheEntries);
  }

  /** The bounded interning dictionary backing the {@code strings} table. */
  public StringDictionary strings() {
    return strings;
  }

  /** Records an {@code import_diagnostics} row immediately (see {@link DiagnosticWriter}). */
  public void recordDiagnostic(ImportDiagnostic diagnostic) throws SQLException {
    diagnostics.record(diagnostic);
  }

  /**
   * Queues one event, its identity and its announced children.
   *
   * <p>Rows become visible to readers at the next batch boundary or at {@link #flush()}.
   */
  public void write(NormalizedEvent normalized) throws SQLException {
    Objects.requireNonNull(normalized, "normalized");
    addEvent(normalized.event());
    if (normalized.identity().isPresent()) {
      addIdentity(normalized.identity().get());
    }
    List<Long> children = normalized.announcedChildHashes();
    for (int ordinal = 0; ordinal < children.size(); ordinal++) {
      addAnnouncedChild(
          normalized.event().streamId(),
          normalized.event().sequence(),
          ordinal,
          children.get(ordinal));
    }
    sinceFlush++;
    if (sinceFlush >= batchSize) {
      flush();
    }
  }

  /** Queues one {@code bep_events} row without identity or children. */
  public void addEvent(EventRecord event) throws SQLException {
    PreparedStatement statement = events.statement();
    statement.setLong(1, event.streamId());
    statement.setLong(2, event.sequence());
    statement.setInt(3, event.eventType());
    if (event.eventIdHash().isPresent()) {
      statement.setLong(4, event.eventIdHash().getAsLong());
    } else {
      statement.setNull(4, Types.INTEGER);
    }
    statement.setInt(5, event.lastMessage() ? 1 : 0);
    statement.setInt(6, event.childCount());
    statement.setInt(7, event.rawSegment());
    statement.setLong(8, event.rawOffset());
    statement.setInt(9, event.rawLength());
    statement.setString(10, event.decodeStatus().name());
    statement.setInt(11, event.hasUnknownFields() ? 1 : 0);
    if (event.eventMicros().isPresent()) {
      statement.setLong(12, event.eventMicros().getAsLong());
    } else {
      statement.setNull(12, Types.INTEGER);
    }
    statement.setLong(13, event.receiveMicros());
    events.add();
    eventsOffered++;
  }

  /** Queues one {@code bep_event_ids} row. Repeats of a known hash are no-ops. */
  public void addIdentity(EventIdentity identity) throws SQLException {
    PreparedStatement statement = identities.statement();
    statement.setLong(1, identity.hash());
    statement.setInt(2, identity.idKind());
    statement.setBytes(3, identity.idBytes());
    statement.setString(4, identity.display());
    identities.add();
    identitiesOffered++;
  }

  /**
   * Queues one announced-child edge, naming the parent by the {@code (stream_id, sequence)} that
   * identifies it rather than by a row id this writer would have to guess. See the class comment.
   */
  public void addAnnouncedChild(
      long parentStreamId, long parentSequence, int ordinal, long childHash) throws SQLException {
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative: " + ordinal);
    }
    PreparedStatement statement = edges.statement();
    statement.setLong(1, childHash);
    statement.setInt(2, ordinal);
    statement.setLong(3, parentStreamId);
    statement.setLong(4, parentSequence);
    edges.add();
    edgesOffered++;
  }

  /**
   * Executes and commits everything queued, in dependency order: events first, so the edge
   * statement's parent lookup can find them.
   *
   * <p>The three statements commit as three transactions rather than one. That is safe because
   * every insert is idempotent and because the journal checkpoint that would let recovery skip this
   * work is only written after this method returns — a crash between the commits replays the whole
   * batch and lands on the same rows.
   */
  public void flush() throws SQLException {
    try {
      events.flush();
      identities.flush();
      edges.flush();
    } catch (SQLException failure) {
      // BatchedInsert has already rolled back. The interning cache may be
      // vouching for rows that rollback removed, so it can no longer be
      // trusted.
      strings.invalidate();
      throw failure;
    }
    sinceFlush = 0;
  }

  /**
   * Recomputes {@code bep_announced_missing} from what is on disk.
   *
   * <h2>Why this is SQL and not a Java map</h2>
   *
   * <p>The natural implementation keeps a pending set of announced child hashes and removes entries
   * as children arrive. At Tier 3 scale that set is tens of millions of longs — hundreds of
   * megabytes of heap that exist only to answer a question the database can already answer, and
   * exactly the kind of "retain the whole build in Java objects" that ADR-007 forbids.
   *
   * <p>So the pending set is not materialized at all. The announced children already live in {@code
   * bep_event_edges} and the arrived events already live in {@code bep_events}; what remains is the
   * set difference between them, computed by SQLite with its own on-disk temporary storage, bounded
   * by its page cache rather than by the JVM heap.
   *
   * <p>The two statements make this re-runnable rather than once-only: the first clears entries
   * whose child has since arrived (a child arriving after its parent is the common case, not an
   * error), the second records everything still absent. Calling it mid-import therefore produces a
   * correct snapshot for the Events view's missing-child report, and calling it again at
   * finalization produces the final answer.
   *
   * <p>Known limits, since this is a derived answer: when several parents announce the same child
   * only the lowest-numbered parent is recorded, and the report is only meaningful once the stream
   * is complete — during capture, "missing" and "not yet delivered" are indistinguishable by
   * construction.
   *
   * @return how many children are recorded as announced but never delivered
   */
  public long resolveAnnouncedMissing() throws SQLException {
    flush();
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(CLEAR_ARRIVED_ANNOUNCED);
      statement.executeUpdate(RECORD_STILL_MISSING);
    }
    commitIfNeeded();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM bep_announced_missing")) {
      return rows.next() ? rows.getLong(1) : 0L;
    }
  }

  /**
   * Ends bulk load: flush, build the indexes, resolve announced children, refresh planner
   * statistics, and reconcile the row count.
   *
   * <p>Explicit rather than implicit, and in this order on purpose. The indexes are built after the
   * rows are loaded because the Phase 0 spike measured that as the cheaper direction, and before
   * the announced-child resolution because that query's {@code NOT EXISTS} lookup rides {@code
   * idx_bep_events_id_hash}. {@code ANALYZE} runs last so it measures the finished tables (plan
   * 10.9).
   *
   * @return the reconciliation between events offered and rows stored
   */
  public IngestSummary finalizeIngest() throws SQLException {
    flush();
    SchemaIndexes.createAll(connection);
    long missing = resolveAnnouncedMissing();
    SchemaIndexes.analyze(connection);

    long stored = countEvents(connection);
    long inserted = stored - baselineEventRows;
    long duplicates = eventsOffered - inserted;
    if (duplicates > 0) {
      // Not a silent drop: a repeat of a sequence already stored carries
      // the same bytes, but the user is still told it happened.
      recordDiagnostic(
          ImportDiagnostic.general(
              DiagnosticSeverity.INFO,
              DiagnosticCodes.DUPLICATE_SEQUENCE,
              duplicates
                  + " event(s) repeated a (stream, sequence) already stored and were"
                  + " ignored as duplicates",
              System.currentTimeMillis() * 1_000L));
    }
    if (missing > 0) {
      recordDiagnostic(
          ImportDiagnostic.general(
              DiagnosticSeverity.WARNING,
              DiagnosticCodes.ANNOUNCED_CHILD_MISSING,
              missing + " announced child event(s) never arrived",
              System.currentTimeMillis() * 1_000L));
    }
    return new IngestSummary(
        eventsOffered, inserted, Math.max(duplicates, 0), identitiesOffered, edgesOffered, missing);
  }

  /** Events handed to this writer, including repeats that stored no row. */
  public long eventsOffered() {
    return eventsOffered;
  }

  /** Identity rows handed to this writer, including repeats. */
  public long identitiesOffered() {
    return identitiesOffered;
  }

  /** Announced-child edges handed to this writer, including repeats. */
  public long edgesOffered() {
    return edgesOffered;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    SQLException failure = null;
    // Reverse construction order: each BatchedInsert restores the
    // auto-commit mode it found, so the innermost must be closed first.
    failure = closeQuietly(failure, strings::close);
    failure = closeQuietly(failure, diagnostics::close);
    failure = closeQuietly(failure, edges::close);
    failure = closeQuietly(failure, identities::close);
    failure = closeQuietly(failure, events::close);
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * What one ingestion run did.
   *
   * @param eventsOffered events handed to the writer
   * @param eventsInserted rows the database actually gained
   * @param duplicatesIgnored repeats of an already-stored (stream, sequence)
   * @param identitiesOffered identity rows offered, including repeats
   * @param edgesOffered announced-child edges offered, including repeats
   * @param announcedMissing children announced but never delivered
   */
  public record IngestSummary(
      long eventsOffered,
      long eventsInserted,
      long duplicatesIgnored,
      long identitiesOffered,
      long edgesOffered,
      long announcedMissing) {

    /** True when every offered event produced a stored row. */
    public boolean reconciles() {
      return eventsOffered == eventsInserted + duplicatesIgnored;
    }
  }

  private void commitIfNeeded() throws SQLException {
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
  }

  private static long countEvents(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(COUNT_EVENTS)) {
      return rows.next() ? rows.getLong(1) : 0L;
    }
  }

  @FunctionalInterface
  private interface SqlCloser {
    void close() throws SQLException;
  }

  private static SQLException closeQuietly(SQLException existing, SqlCloser closer) {
    try {
      closer.close();
      return existing;
    } catch (SQLException e) {
      if (existing == null) {
        return e;
      }
      existing.addSuppressed(e);
      return existing;
    }
  }
}
