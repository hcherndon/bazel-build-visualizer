package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.storage.query.QueryOutline;
import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.storage.query.TempViewDefinition;
import java.util.List;

/**
 * The read service the ad hoc query card is written against — the same arrangement {@link
 * SessionReader} makes for the Events view and {@link EntityReader} for the Phase 3 tables, and for
 * the same reason (plan rule 19: UI components depend on service interfaces, not on SQLite
 * implementation classes).
 *
 * <p>No {@code Connection}, no {@code Statement} and no {@code SQLException} crosses this line.
 * What does cross is the same kind of value record the other readers already hand back.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method except {@link #cancel()} blocks on SQL, so <b>none of them may be called on the
 * Swing EDT</b>. One reader is bound to one thread.
 *
 * <p>{@link #cancel()} is the exception on purpose: it is {@code sqlite3_interrupt}, it returns
 * immediately, and it is what the Cancel button calls from the event thread. Without a cancel that
 * is safe to call from the EDT there is no way to abandon a query except by waiting for it.
 */
public interface QueryReader extends AutoCloseable {

  /**
   * Every table and view in the session database, with its columns, read from the file rather than
   * from a generated list.
   *
   * <p>Blocking, but bounded by the schema rather than the build.
   */
  List<SchemaTable> schema();

  /**
   * Validates {@code sql}, reads its result columns and counts its rows.
   *
   * <p>Blocking, and usually the slow half: the count is a full pass.
   *
   * @param rowLimit rows the grid may address
   * @throws com.holtherndon.bazelviz.storage.query.SqlNotAllowedException if the text is not one
   *     read-only statement — nothing was executed
   * @throws com.holtherndon.bazelviz.storage.query.QueryFailedException if SQLite refused it or it
   *     was stopped
   */
  QueryOutline describe(String sql, long rowLimit);

  /** One page of {@code outline}'s rows. Blocking. */
  List<QueryRow> page(QueryOutline outline, long offset, int limit);

  /**
   * Replaces this reader's replayed temporary views with {@code views}.
   *
   * <p>Blocking. A temp view is per-connection state, which is why the saved definitions have to be
   * replayed onto every reader rather than written once: each query tab owns a reader, and each
   * reader's connection gets its own copy. Definitions that cannot be applied are reported in the
   * returned list and skipped — saved views are user-edited files, and one broken file must not
   * take the tab down.
   *
   * @return one human-readable problem per definition not applied
   */
  List<String> applyTempViews(List<TempViewDefinition> views);

  /**
   * Interrupts whatever this reader is running, from any thread including the EDT. Safe to call
   * when nothing is running.
   */
  void cancel();

  /** The runaway-query deadline this reader enforces, in seconds. */
  int timeoutSeconds();

  @Override
  void close();
}
