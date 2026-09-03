package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.storage.query.AdHocQueries;
import com.holtherndon.bazelviz.storage.query.QueryOutline;
import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.storage.query.TempViewDefinition;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link QueryReader} over one read-only connection.
 *
 * <p>Owns the connection it was handed and closes it, which is what keeps a cancelled or failed
 * query from leaking one: the view lets go of the reader, the reader closes the connection, and
 * {@code SqliteSessionSource} closes any that outlive their view anyway.
 */
final class SqliteQueryReader implements QueryReader {

  private static final Logger log = LoggerFactory.getLogger(SqliteQueryReader.class);

  private final String describedSession;
  private final Connection connection;
  private final AdHocQueries queries;
  private volatile boolean closed;

  SqliteQueryReader(String describedSession, Connection connection, AdHocQueries queries) {
    this.describedSession = Objects.requireNonNull(describedSession, "describedSession");
    this.connection = Objects.requireNonNull(connection, "connection");
    this.queries = Objects.requireNonNull(queries, "queries");
  }

  @Override
  public List<SchemaTable> schema() {
    return queries.schema();
  }

  @Override
  public QueryOutline describe(String sql, long rowLimit) {
    return queries.describe(sql, rowLimit);
  }

  @Override
  public List<QueryRow> page(QueryOutline outline, long offset, int limit) {
    return queries.page(outline, offset, limit);
  }

  @Override
  public List<String> applyTempViews(List<TempViewDefinition> views) {
    return queries.applyTempViews(views);
  }

  @Override
  public void cancel() {
    if (!closed) {
      queries.cancel();
    }
  }

  @Override
  public int timeoutSeconds() {
    return queries.timeoutSeconds();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    // Interrupt first. Closing a connection under a running statement
    // blocks, and this is exactly the path a view takes when it is torn
    // down mid-query.
    queries.cancel();
    queries.close();
    try {
      connection.close();
    } catch (SQLException e) {
      log.warn("failed to close the query connection for {}", describedSession, e);
    }
  }
}
