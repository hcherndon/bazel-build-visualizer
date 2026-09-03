package com.holtherndon.bazelviz.ui.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Query card over a real imported session — the whole path, from the text a person types to the
 * rows on screen.
 *
 * <p>The storage layer's own tests prove the connection cannot write. What this covers is the part
 * that could be right underneath and wrong on screen: that the refusal reaches the user as
 * something they can act on, that a capped result says it is capped, that a NULL is not an empty
 * cell, and that Cancel actually abandons a query instead of leaving the card wedged.
 */
class QueryViewWiringTest {

  private static final int EVENT_COUNT = 300;

  /** Runs far longer than this test will wait, so Cancel has something to stop. */
  private static final String SLOW_QUERY =
      "WITH RECURSIVE counter(x) AS ("
          + " SELECT 1 UNION ALL SELECT x + 1 FROM counter WHERE x < 2000000000)"
          + " SELECT COUNT(*) FROM counter";

  @TempDir Path temporary;

  private SqliteSessionSource session;
  private QueryView view;

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  @BeforeEach
  void openSession() throws Exception {
    SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
    Path source = temporary.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);
    session = SqliteSessionSource.open(sessions, imported.sessionRoot());

    AtomicReference<QueryView> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          QueryView opened = new QueryView();
          opened.openSession(session);
          held.set(opened);
        });
    view = held.get();
    await(() -> onEdt(() -> !view.schemaTablesForTest().isEmpty()));
  }

  @AfterEach
  void closeSession() throws Exception {
    if (view != null) {
      SwingUtilities.invokeAndWait(view::closeSession);
    }
    if (session != null) {
      session.close();
    }
  }

  // ------------------------------------------------------------ schema browser

  @Test
  @Timeout(180)
  @DisplayName("the schema browser reads the real tables and columns off the file")
  void schemaComesFromTheOpenSession() throws Exception {
    List<String> tables = onEdt(view::schemaTablesForTest);
    assertThat(tables)
        .as("a current session carries dozens of tables and there is no schema page")
        .hasSizeGreaterThan(20)
        .contains("bep_events", "actions", "targets");

    assertThat(onEdt(() -> view.schemaColumnsForTest("bep_events")))
        .isNotEmpty()
        .anySatisfy(column -> assertThat(column).startsWith("event_type"))
        .anySatisfy(column -> assertThat(column).contains("PK"));

    // Rule 12: a filtered tree says it is filtered, so an empty one is not
    // read as a session with no such table.
    SwingUtilities.invokeAndWait(() -> view.filterSchemaForTest("zzz-no-such-thing"));
    assertThat(onEdt(view::schemaTablesForTest)).isEmpty();
    assertThat(onEdt(view::schemaSummaryForTest)).contains("0 of").contains("match");
    SwingUtilities.invokeAndWait(() -> view.filterSchemaForTest(""));
  }

  // ------------------------------------------------------------------- results

  @Test
  @Timeout(180)
  @DisplayName("a query fills the existing paged grid, a page at a time")
  void aQueryPagesThroughTheExistingGrid() throws Exception {
    runSql("SELECT id, event_type FROM bep_events ORDER BY id");

    PagedTableModel<?> model = onEdt(view::tableModelForTest);
    assertThat(model).isNotNull();
    long rows = onEdt(() -> view.rowSourceForTest().rowCount());
    assertThat(rows).isEqualTo(EVENT_COUNT);
    assertThat(onEdt(model::getRowCount)).isEqualTo((int) rows);
    assertThat(onEdt(() -> model.getColumnName(1))).isEqualTo("event_type");
    assertThat(onEdt(view::statusForTest)).contains("300 rows").contains("2 columns");

    await(() -> onEdt(() -> isLoaded(model.getValueAt(0, 0))));
    // A row past the first page, so paging is actually exercised.
    await(() -> onEdt(() -> isLoaded(model.getValueAt(EVENT_COUNT - 1, 0))));
    assertThat(onEdt(model::failedFetchCount)).isZero();
  }

  @Test
  @Timeout(180)
  @DisplayName("a SQL NULL reaches the grid as null, not as an empty string")
  void nullReachesTheGridAsNull() throws Exception {
    // "nothing" would be a syntax error: NOTHING is a SQLite keyword, from
    // ON CONFLICT DO NOTHING.
    runSql("SELECT NULL AS no_value, '' AS empty_text, 0 AS zero_value");

    PagedTableModel<?> model = onEdt(view::tableModelForTest);
    await(() -> onEdt(() -> !PagedTableModel.PLACEHOLDER.equals(model.getValueAt(0, 1))));
    assertThat(onEdt(() -> model.getValueAt(0, 0)))
        .as("a SQL NULL, which the renderer draws as an italic NULL")
        .isNull();
    assertThat(onEdt(() -> model.getValueAt(0, 1))).isEqualTo("");
    assertThat(onEdt(() -> model.getValueAt(0, 2))).isEqualTo(0);
  }

  // ---------------------------------------------------------------- rule 12 cap

  @Test
  @Timeout(180)
  @DisplayName("a capped result says so on screen, with both numbers and a way out")
  void aCappedResultSaysSo() throws Exception {
    SwingUtilities.invokeAndWait(() -> view.setRowCapForTest(10));
    runSql("SELECT id FROM bep_events ORDER BY id");

    assertThat(onEdt(() -> view.rowSourceForTest().rowCount())).isEqualTo(10L);
    String notice = onEdt(view::capNoticeForTest);
    assertThat(notice)
        .contains("Showing the first 10")
        .contains(String.valueOf(EVENT_COUNT))
        .contains("row cap is 10")
        .contains("nothing was dropped");
    assertThat(onEdt(view::raiseOfferShownForTest))
        .as("rule 12 asks for the offer, not only the statement")
        .isTrue();
    assertThat(onEdt(view::raiseOfferForTest)).contains("Raise to 300");
    assertThat(onEdt(view::statusForTest)).contains("10 of 300 rows");
  }

  @Test
  @Timeout(180)
  @DisplayName("an uncapped result makes no claim about being capped")
  void anUncappedResultIsQuiet() throws Exception {
    runSql("SELECT id FROM bep_events ORDER BY id LIMIT 5");

    assertThat(onEdt(view::capNoticeForTest)).isBlank();
    assertThat(onEdt(view::raiseOfferShownForTest)).isFalse();
    assertThat(onEdt(view::statusForTest)).contains("5 rows");
  }

  // ------------------------------------------------------------------ refusals

  @Test
  @Timeout(180)
  @DisplayName("a write is refused on screen, and the table is still there afterwards")
  void aWriteIsRefusedAndNothingChanges() throws Exception {
    runSql("DROP TABLE bep_events");

    assertThat(onEdt(view::errorShownForTest)).isTrue();
    assertThat(onEdt(view::errorForTest)).contains("DROP");
    assertThat(onEdt(view::statusForTest)).contains("Nothing was executed");
    assertThat(onEdt(view::tableModelForTest))
        .as("no grid was installed for a statement that never ran")
        .isNull();

    // The table is still there, which is the claim that matters.
    runSql("SELECT COUNT(*) AS still_here FROM bep_events");
    PagedTableModel<?> model = onEdt(view::tableModelForTest);
    await(() -> onEdt(() -> isLoaded(model.getValueAt(0, 0))));
    assertThat(onEdt(() -> model.getValueAt(0, 0))).isEqualTo(EVENT_COUNT);
  }

  @Test
  @Timeout(180)
  @DisplayName("a semicolon-joined write is refused by the app layer before anything runs")
  void twoStatementsAreRefused() throws Exception {
    runSql("SELECT 1; DROP TABLE bep_events");

    assertThat(onEdt(view::errorShownForTest)).isTrue();
    assertThat(onEdt(view::errorForTest)).contains("Run one statement at a time");
    assertThat(onEdt(view::statusForTest)).contains("Nothing was executed");

    runSql("SELECT COUNT(*) AS still_here FROM bep_events");
    PagedTableModel<?> model = onEdt(view::tableModelForTest);
    await(() -> onEdt(() -> isLoaded(model.getValueAt(0, 0))));
    assertThat(onEdt(() -> model.getValueAt(0, 0))).isEqualTo(EVENT_COUNT);
  }

  @Test
  @Timeout(180)
  @DisplayName("a SQL error shows SQLite's message and the statement it came from")
  void aSqlErrorIsActionable() throws Exception {
    runSql("SELECT * FROM no_such_table");

    assertThat(onEdt(view::errorShownForTest)).isTrue();
    String error = onEdt(view::errorForTest);
    assertThat(error).contains("no such table");
    assertThat(error).contains("Statement as executed:");
    assertThat(error)
        .as("the wrapped form, because that is what SQLite was actually asked")
        .contains("SELECT * FROM (SELECT * FROM no_such_table)");
  }

  // -------------------------------------------------------------------- cancel

  @Test
  @Timeout(180)
  @DisplayName("Cancel abandons a running query without wedging the card")
  void cancelAbandonsARunningQuery() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          view.setSqlForTest(SLOW_QUERY);
          view.run();
        });
    assertThat(onEdt(view::isRunningForTest)).isTrue();

    // Long enough that the statement is genuinely inside SQLite.
    TimeUnit.MILLISECONDS.sleep(400);
    long cancelStarted = System.nanoTime();
    SwingUtilities.invokeAndWait(view::cancel);
    long cancelNanos = System.nanoTime() - cancelStarted;

    assertThat(TimeUnit.NANOSECONDS.toMillis(cancelNanos))
        .as("cancel runs on the EDT and must not block on the query")
        .isLessThan(2_000L);
    assertThat(onEdt(view::isRunningForTest)).isFalse();
    assertThat(onEdt(view::statusForTest)).contains("Stopped");
    assertThat(onEdt(view::errorShownForTest))
        .as("a cancelled query is not a broken one")
        .isFalse();

    // And the card still works: the connection was interrupted, not lost.
    runSql("SELECT COUNT(*) AS after_cancel FROM bep_events");
    PagedTableModel<?> model = onEdt(view::tableModelForTest);
    await(() -> onEdt(() -> isLoaded(model.getValueAt(0, 0))));
    assertThat(onEdt(() -> model.getValueAt(0, 0))).isEqualTo(EVENT_COUNT);
  }

  @Test
  @Timeout(180)
  @DisplayName("closing a session under a running query does not wedge or leak")
  void closingUnderARunningQueryReturns() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          view.setSqlForTest(SLOW_QUERY);
          view.run();
        });
    TimeUnit.MILLISECONDS.sleep(300);

    long started = System.nanoTime();
    SwingUtilities.invokeAndWait(view::closeSession);
    assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
        .as("closeSession runs on the EDT and hands the waiting to a daemon thread")
        .isLessThan(5L);
    assertThat(onEdt(view::isRunningForTest)).isFalse();
    assertThat(onEdt(view::tableModelForTest)).isNull();

    // The session itself still closes, which is what "does not leak" means
    // in practice: no connection is left holding the file.
    session.close();
    session = null;
    view = null;
  }

  // ------------------------------------------------------- tabs and temp views

  @Test
  @Timeout(180)
  @DisplayName("the starter SQL runs as shipped, over the session's real columns")
  void theStarterQueryRunsAsShipped() throws Exception {
    assertThat(onEdt(view::sqlForTest)).isEqualTo(QueryView.STARTER_SQL);
    SwingUtilities.invokeAndWait(view::run);
    await(() -> onEdt(() -> !view.isRunningForTest()));

    assertThat(onEdt(view::errorShownForTest))
        .as("a starter that errors teaches the schema is unknowable: %s", onEdt(view::errorForTest))
        .isFalse();
    assertThat(onEdt(view::statusForTest)).contains("rows").contains("4 columns");
  }

  @Test
  @Timeout(180)
  @DisplayName("each tab owns its connection, so a temp view is one tab's alone")
  void aTempViewBelongsToItsTab() throws Exception {
    runSql("CREATE TEMP VIEW mine AS SELECT id FROM bep_events");
    assertThat(onEdt(view::statusForTest)).contains("Temporary view defined");
    await(
        () ->
            onEdt(
                () ->
                    view.schemaTablesForTest().stream()
                        .anyMatch(
                            label -> label.startsWith("mine") && label.contains("temp view"))));

    // Queryable where it was defined.
    runSql("SELECT COUNT(*) AS c FROM mine");
    assertThat(onEdt(view::errorShownForTest)).isFalse();

    // A second tab is a second connection: the view is not there, and the
    // schema tree follows the selected tab.
    SwingUtilities.invokeAndWait(view::addTab);
    assertThat(onEdt(view::tabCountForTest)).isEqualTo(2);
    await(() -> onEdt(() -> !view.schemaTablesForTest().isEmpty()));
    assertThat(onEdt(view::schemaTablesForTest))
        .noneSatisfy(label -> assertThat(label).startsWith("mine"));
    runSql("SELECT COUNT(*) FROM mine");
    assertThat(onEdt(view::errorShownForTest)).isTrue();
    assertThat(onEdt(view::errorForTest)).contains("no such table");

    // Back on the first tab, the view is still on its connection.
    SwingUtilities.invokeAndWait(() -> view.selectTabForTest(0));
    assertThat(onEdt(view::schemaTablesForTest))
        .anySatisfy(label -> assertThat(label).startsWith("mine"));

    SwingUtilities.invokeAndWait(view::closeSelectedTab);
    assertThat(onEdt(view::tabCountForTest)).isEqualTo(1);
  }

  @Test
  @Timeout(180)
  @DisplayName("tabs rename, and the last one refuses to close with a reason")
  void tabsRenameAndTheLastOneStays() throws Exception {
    SwingUtilities.invokeAndWait(() -> view.renameSelectedTab("Slow joins"));
    assertThat(onEdt(view::selectedTabTitleForTest)).isEqualTo("Slow joins");

    SwingUtilities.invokeAndWait(view::closeSelectedTab);
    assertThat(onEdt(view::tabCountForTest)).isEqualTo(1);
    assertThat(onEdt(view::tabNoticeForTest)).contains("last tab");
  }

  @Test
  @Timeout(180)
  @DisplayName("two tabs run genuinely concurrently, on their own connections")
  void twoTabsRunConcurrently() throws Exception {
    SwingUtilities.invokeAndWait(view::addTab);
    await(() -> onEdt(() -> !view.schemaTablesForTest().isEmpty()));

    // A slow query on the second tab…
    SwingUtilities.invokeAndWait(
        () -> {
          view.setSqlForTest(SLOW_QUERY);
          view.run();
        });
    assertThat(onEdt(view::isRunningForTest)).isTrue();

    // …does not block a query on the first: two connections, two threads.
    SwingUtilities.invokeAndWait(() -> view.selectTabForTest(0));
    runSql("SELECT COUNT(*) AS c FROM bep_events");
    assertThat(onEdt(view::errorShownForTest)).isFalse();
    assertThat(onEdt(view::statusForTest)).contains("1 rows");

    // The slow one is still running on its own tab; stop it.
    SwingUtilities.invokeAndWait(() -> view.selectTabForTest(1));
    assertThat(onEdt(view::isRunningForTest)).isTrue();
    SwingUtilities.invokeAndWait(view::cancel);
    assertThat(onEdt(view::isRunningForTest)).isFalse();
  }

  // ----------------------------------------------------------------- library

  @Test
  @Timeout(180)
  @DisplayName("saved views seed on first use and replay onto every tab's connection")
  void savedViewsReplayOntoTabs() throws Exception {
    SwingUtilities.invokeAndWait(() -> view.attachLibrary(temporary.resolve("settings")));
    await(
        () ->
            onEdt(() -> view.libraryPanelForTest().viewNamesForTest().contains("mnemonic_totals")));

    // Tabs opened before the library attached replay on demand; a fresh
    // open replays for every tab. Reopen, which is the ordinary path.
    SwingUtilities.invokeAndWait(() -> view.openSession(session));
    await(
        () ->
            onEdt(
                () ->
                    view.schemaTablesForTest().stream()
                        .anyMatch(
                            label ->
                                label.startsWith("actions_with_labels")
                                    && label.contains("temp view"))));

    // The shipped example is real SQL over real columns.
    runSql("SELECT mnemonic, total_ms FROM mnemonic_totals ORDER BY total_ms DESC");
    assertThat(onEdt(view::errorShownForTest))
        .as("the shipped example must run: %s", onEdt(view::errorForTest))
        .isFalse();
    assertThat(onEdt(view::statusForTest)).contains("columns");
  }

  @Test
  @Timeout(180)
  @DisplayName("saving the editor's CREATE TEMP VIEW persists it and applies it everywhere")
  void aViewSavedFromTheEditorReachesEveryTab() throws Exception {
    SwingUtilities.invokeAndWait(() -> view.attachLibrary(temporary.resolve("settings")));
    await(
        () ->
            onEdt(() -> view.libraryPanelForTest().viewNamesForTest().contains("mnemonic_totals")));

    List<String> problems = new CopyOnWriteArrayList<>();
    SwingUtilities.invokeAndWait(
        () -> {
          view.setSqlForTest("CREATE TEMP VIEW event_ids AS SELECT id FROM bep_events");
          view.panelHostForTest().saveViewFromEditor(problems::add);
        });
    await(() -> onEdt(() -> view.libraryPanelForTest().viewNamesForTest().contains("event_ids")));
    assertThat(problems).isEmpty();

    // The saved view lands on the open tab's connection without a reopen.
    await(
        () ->
            onEdt(
                () ->
                    view.schemaTablesForTest().stream()
                        .anyMatch(label -> label.startsWith("event_ids"))));
    runSql("SELECT COUNT(*) AS c FROM event_ids");
    assertThat(onEdt(view::errorShownForTest)).isFalse();

    // And an editor whose text is not a definition is told what to do.
    List<String> refused = new CopyOnWriteArrayList<>();
    SwingUtilities.invokeAndWait(
        () -> {
          view.setSqlForTest("SELECT 1");
          view.panelHostForTest().saveViewFromEditor(refused::add);
        });
    assertThat(refused)
        .singleElement()
        .satisfies(text -> assertThat(text).contains("CREATE TEMP VIEW"));
  }

  // ------------------------------------------------------------------- helpers

  private void runSql(String sql) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          view.setSqlForTest(sql);
          view.run();
        });
    await(() -> onEdt(() -> !view.isRunningForTest()));
  }

  private static boolean isLoaded(Object value) {
    return !PagedTableModel.PLACEHOLDER.equals(value)
        && !PagedTableModel.ERROR_PLACEHOLDER.equals(value);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private static <T> T onEdt(Callable<T> read) {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              value.set(read.call());
            } catch (Exception e) {
              failure.set(e);
            }
          });
    } catch (Exception e) {
      throw new AssertionError("EDT read failed", e);
    }
    if (failure.get() != null) {
      throw new AssertionError("EDT read failed", failure.get());
    }
    return value.get();
  }

  private static boolean onEdt(BooleanSupplier read) {
    return onEdt((Callable<Boolean>) read::getAsBoolean);
  }
}
