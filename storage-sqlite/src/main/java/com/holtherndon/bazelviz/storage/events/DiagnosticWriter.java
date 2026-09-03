package com.holtherndon.bazelviz.storage.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Records {@code import_diagnostics} rows.
 *
 * <p>Diagnostics are written and committed <em>immediately</em>, not batched. That is deliberate:
 * the diagnostics that matter most describe things going wrong — a corrupt frame, a full disk (plan
 * 21.2), a truncated tail — and a diagnostic that is still sitting in a batch when the process dies
 * explains nothing to the user afterwards.
 *
 * <p>Committing on a connection whose auto-commit is suspended by an active {@link
 * com.holtherndon.bazelviz.storage.BatchedInsert} is safe: that class executes and commits its
 * batch in one step, so between batches there is never executed-but-uncommitted work for this
 * commit to sweep up early.
 *
 * <p><b>Caveat for other writers on the same connection.</b> A commit is a connection-wide act. If
 * a caller has opened its own multi-statement transaction on the writer connection by hand and
 * executed part of it, recording a diagnostic mid-way would commit that partial work early. Record
 * diagnostics either side of such a transaction, not inside one.
 *
 * <p>Not thread-safe. Use from the writer thread.
 */
public final class DiagnosticWriter implements AutoCloseable {

  private static final String INSERT =
      "INSERT INTO import_diagnostics"
          + " (severity, code, message, segment_index, byte_offset, at_micros)"
          + " VALUES (?, ?, ?, ?, ?, ?)"
          + " ON CONFLICT (code, segment_index, byte_offset)"
          + " WHERE segment_index IS NOT NULL AND byte_offset IS NOT NULL"
          + " DO NOTHING";

  private final Connection connection;
  private final PreparedStatement insert;
  private long recorded;
  private boolean closed;

  public DiagnosticWriter(Connection connection) throws SQLException {
    this.connection = connection;
    this.insert = connection.prepareStatement(INSERT);
  }

  /** Writes one diagnostic and commits it. */
  public void record(ImportDiagnostic diagnostic) throws SQLException {
    insert.setString(1, diagnostic.severity().name());
    insert.setString(2, diagnostic.code());
    insert.setString(3, diagnostic.message());
    if (diagnostic.segmentIndex().isPresent()) {
      insert.setInt(4, diagnostic.segmentIndex().getAsInt());
    } else {
      insert.setNull(4, Types.INTEGER);
    }
    if (diagnostic.byteOffset().isPresent()) {
      insert.setLong(5, diagnostic.byteOffset().getAsLong());
    } else {
      insert.setNull(5, Types.INTEGER);
    }
    insert.setLong(6, diagnostic.atMicros());
    int inserted = insert.executeUpdate();
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
    // A repeat of an anchored diagnostic inserts nothing; counting it
    // would overstate how much was recorded.
    recorded += inserted;
  }

  /** Diagnostics written through this writer. */
  public long recordedCount() {
    return recorded;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    insert.close();
  }
}
