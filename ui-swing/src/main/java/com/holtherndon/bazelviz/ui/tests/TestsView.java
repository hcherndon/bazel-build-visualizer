package com.holtherndon.bazelviz.ui.tests;

import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.enrich.AttemptRow;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import com.holtherndon.bazelviz.storage.entities.TestAttemptRow;
import com.holtherndon.bazelviz.storage.entities.TestQueries;
import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.files.FileLink;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectionPagingPanel;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.table.TableHeaderInteractions;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Tests card: every test target's verdict, worst first, with its attempts in the inspector.
 *
 * <p>The verdict shown is {@code testSummary.overallStatus} and nothing else. A target's own {@code
 * success} flag was measured {@code true} for a test that failed, so a view built on that would
 * show every failure green — which is the single most consequential thing this view could get
 * wrong.
 */
public final class TestsView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;

  private static final Logger log = LoggerFactory.getLogger(TestsView.class);

  private static final String CARD_EMPTY = "empty";
  private static final String CARD_TABLE = "table";
  private static final int CACHE_PAGES = 16;

  /** BEP attempts retained for one selected test at a time. */
  public static final int TEST_ATTEMPT_PAGE_SIZE = 100;

  /** Test-log links retained for one selected test at a time. */
  public static final int TEST_LOG_PAGE_SIZE = 100;

  /** Execution-log subprocesses retained for one selected test at a time. */
  public static final int TEST_SPAWN_PAGE_SIZE = 100;

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel(" ");
  private final JTable table = new JTable();
  private final InspectorPanel inspector = new InspectorPanel();
  private final InspectionPagingPanel inspectionPaging = new InspectionPagingPanel();
  private final JLabel statusLabel = new JLabel(" ");

  /**
   * Why the test table's headers do not sort: the worst-first ordering is the product ({@link
   * TestRowSource}'s fixed failures-first order), not an arbitrary default a header click should
   * overwrite.
   */
  static final String ORDER_IS_FIXED =
      "This table is deliberately ordered worst-first — failures, then"
          + " timeouts and build failures, then flakes, then passes"
          + " — so what went wrong is at the top without asking."
          + " That ordering is the product, and it is fixed.";

  /**
   * The shared header behaviour: no sorting (see {@link #ORDER_IS_FIXED}), but the column menu and
   * the persisted column state.
   */
  private final TableHeaderInteractions headerInteractions;

  private ExecutorService pageExecutor;
  private ExecutorService detailExecutor;
  private ExecutorService inspectionExecutor;
  private EntityReader pageReader;
  private EntityReader detailReader;
  private SessionSource source;
  private PagedTableModel<TestRow> tableModel;
  private LongConsumer showEventHandler = eventId -> {};
  private long selectionGeneration;

  /** Bumped whenever a session is opened or closed, including reuse of the same source object. */
  private long sessionGeneration;

  private Optional<CountedPage<TestAttemptRow, TestQueries.TestAttemptAnchor>> attemptPage =
      Optional.empty();
  private Optional<CountedPage<TestQueries.TestLog, Long>> logPage = Optional.empty();
  private Optional<CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor>> spawnPage =
      Optional.empty();
  private boolean attemptPageLoading;
  private boolean logPageLoading;
  private boolean spawnPageLoading;
  private Future<?> attemptTask;
  private Future<?> logTask;
  private Future<?> spawnTask;
  private Future<?> inspectionTask;
  private PageToolbar pageToolbar;
  private String pageMetadata = "";
  private String pageMetadataDetail = "";

  public TestsView() {
    super(new BorderLayout());

    PlainText.install(table);
    PlainText.disableHtml(statusLabel);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    table
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                selectionChanged();
              }
            });
    inspector.onShowSourceEvent(eventId -> showEventHandler.accept(eventId));
    headerInteractions =
        TableHeaderInteractions.install(
            table, TableHeaderInteractions.Adapter.unsortable(ORDER_IS_FIXED));

    JScrollPane scroll = new JScrollPane(table);
    scroll.setMinimumSize(new Dimension(320, 160));
    inspector.setMinimumSize(new Dimension(300, 160));
    JPanel inspectionPanel = new JPanel(new BorderLayout());
    inspectionPanel.add(inspector, BorderLayout.CENTER);
    inspectionPanel.add(inspectionPaging, BorderLayout.SOUTH);
    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new SectionPane("Tests", scroll),
            new SectionPane("Test details", inspectionPanel));
    split.setResizeWeight(0.62);

    JPanel status = new JPanel(new BorderLayout());
    status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    status.add(statusLabel, BorderLayout.WEST);

    JPanel session = new JPanel(new BorderLayout());
    session.add(split, BorderLayout.CENTER);
    session.add(status, BorderLayout.SOUTH);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(session, CARD_TABLE);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
  }

  /** Installs cached test state in the common toolbar; this page has no root-level actions. */
  @Override
  public void installPageToolbar(PageToolbar installed) {
    Objects.requireNonNull(installed, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = installed;
    syncPageMetadata();
  }

  public void onShowSourceEvent(LongConsumer handler) {
    this.showEventHandler = Objects.requireNonNull(handler, "handler");
  }

  /** Adopts the shared row/inspector actions, including Open Build File. */
  public void installEntityActions(EntityActions actions) {
    Objects.requireNonNull(actions, "actions");
    inspector.installEntityActions(actions, Set.of());
    actions.installRowMenu(table, this::refsAtRow, Set.of());
  }

  public void onOpenFile(Consumer<FileLink> handler) {
    inspector.onOpenFile(handler);
  }

  List<EntityRef> refsAtRow(int modelRow) {
    PagedTableModel<TestRow> model = tableModel;
    TestRow row = model == null ? null : model.rowAt(modelRow);
    if (row == null) {
      return List.of();
    }
    List<EntityRef> refs = new ArrayList<>();
    refs.add(new EntityRef.TargetLabel(row.label()));
    row.bepEventId().ifPresent(eventId -> refs.add(new EntityRef.EventId(eventId)));
    return refs;
  }

  public void showEmpty(String message) {
    emptyState.setText(Objects.requireNonNull(message, "message"));
    cards.show(deck, CARD_EMPTY);
    setPageMetadata(message.startsWith("No session") ? "" : message, message);
  }

  /** Opens a session and builds the table over it. Returns immediately. */
  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    long generation = ++sessionGeneration;
    source = newSource;
    pageExecutor = singleThreadExecutor("bbv-tests-pages");
    detailExecutor = singleThreadExecutor("bbv-tests-detail");
    inspectionExecutor = singleThreadExecutor("bbv-tests-inspection");
    showEmpty("Reading tests…");
    ExecutorService opening = pageExecutor;
    opening.execute(
        () -> {
          try {
            EntityReader pages = newSource.openEntityReader();
            try {
              EntityReader detail = newSource.openEntityReader();
              try {
                TestRowSource rows = TestRowSource.open(pages, TestRowSource.DEFAULT_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      if (source != newSource || generation != sessionGeneration) {
                        ViewClose.runAsync(
                            "bbv-tests-stale-open-close",
                            () -> {
                              pages.close();
                              detail.close();
                            });
                        return;
                      }
                      pageReader = pages;
                      detailReader = detail;
                      install(rows);
                    });
              } catch (RuntimeException failure) {
                detail.close();
                throw failure;
              }
            } catch (RuntimeException failure) {
              pages.close();
              throw failure;
            }
          } catch (RuntimeException failure) {
            log.error("could not read tests", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (source == newSource && generation == sessionGeneration) {
                    showEmpty(failure.getMessage());
                  }
                });
          }
        });
  }

  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately and completes after this session's test reads have stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    sessionGeneration++;
    selectionGeneration++;
    cancelDetailTasks();
    resetDetailPages();
    tableModel = null;
    table.setModel(new DefaultTableModel());
    inspector.show(Inspection.NONE);
    inspectionPaging.clear();
    ExecutorService pages = pageExecutor;
    ExecutorService details = detailExecutor;
    ExecutorService inspections = inspectionExecutor;
    EntityReader pageSide = pageReader;
    EntityReader detailSide = detailReader;
    if (pageSide != null) {
      pageSide.cancelRunningQuery();
    }
    if (detailSide != null) {
      detailSide.cancelRunningQuery();
    }
    source = null;
    pageExecutor = null;
    detailExecutor = null;
    inspectionExecutor = null;
    pageReader = null;
    detailReader = null;
    if (pages == null
        && details == null
        && inspections == null
        && pageSide == null
        && detailSide == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-tests-close",
        () -> {
          shutdown(pages);
          shutdown(details);
          shutdown(inspections);
          if (pageSide != null) {
            pageSide.close();
          }
          if (detailSide != null) {
            detailSide.close();
          }
        });
  }

  /**
   * Persists this table's column state (widths, visibility, order) under the application settings
   * directory. Called once at wiring time; the load and every save run on the store's I/O thread,
   * never the EDT.
   */
  public void attachColumnState(Path settingsDirectory) {
    headerInteractions.attachPersistence(settingsDirectory, "tests");
  }

  /** Permanently closes this view, including its debounced column-state writer. */
  public CompletionStage<Void> closeAsync() {
    return CompletableFuture.allOf(
        closeSessionAsync().toCompletableFuture(),
        headerInteractions.closeAsync().toCompletableFuture());
  }

  /** Visible for testing: the shared header behaviour on this table. */
  public TableHeaderInteractions headerInteractionsForTest() {
    return headerInteractions;
  }

  /** Visible for testing. */
  PagedTableModel<TestRow> tableModelForTest() {
    return tableModel;
  }

  /** Visible for testing. */
  String statusForTest() {
    return statusLabel.getText();
  }

  private void install(TestRowSource rows) {
    if (rows.rowCount() == 0) {
      // A build with no tests is not a broken view. Saying so beats an
      // empty table the user has to interpret -- but the claim is about
      // this session, not about the build: a truncated stream records no
      // tests whether or not any ran.
      showEmpty("This session recorded no tests.");
      return;
    }
    tableModel =
        new PagedTableModel<>(
            rows, TestTableColumns.columns(), pageExecutor, rows.pageSize(), CACHE_PAGES);
    tableModel.addTableModelListener(
        event -> {
          if (table.getSelectedRow() >= 0 && inspector.displayed().isEmpty()) {
            selectionChanged();
          }
        });
    table.setModel(tableModel);
    int[] widths = TestTableColumns.widths();
    for (int i = 0; i < widths.length && i < table.getColumnModel().getColumnCount(); i++) {
      TableColumn column = table.getColumnModel().getColumn(i);
      column.setPreferredWidth(widths[i]);
    }
    // setModel rebuilt the column model with default widths and every
    // column visible; reapply what the user arranged.
    headerInteractions.modelInstalled();
    setStatus(EntityFormat.count(rows.rowCount()) + (rows.rowCount() == 1 ? " test" : " tests"));
    cards.show(deck, CARD_TABLE);
  }

  private void selectionChanged() {
    int viewRow = table.getSelectedRow();
    if (viewRow < 0 || tableModel == null) {
      selectionGeneration++;
      cancelDetailTasks();
      resetDetailPages();
      EntityReader details = detailReader;
      if (details != null) {
        details.cancelRunningQuery();
      }
      inspector.show(Inspection.NONE);
      inspectionPaging.clear();
      return;
    }
    TestRow row = tableModel.rowAt(viewRow);
    if (row == null) {
      return;
    }
    long generation = ++selectionGeneration;
    cancelDetailTasks();
    resetDetailPages();
    EntityReader details = detailReader;
    if (details != null) {
      details.cancelRunningQuery();
    }
    inspectionPaging.clear();
    inspector.show(Inspection.NONE);
    showTestSummaryAsync(row, generation);
    loadAttemptPage(row, Optional.empty(), generation);
    loadLogPage(row, Optional.empty(), generation);
    loadSpawnPage(row, Optional.empty(), generation);
  }

  private void showTestSummaryAsync(TestRow row, long generation) {
    ExecutorService running = inspectionExecutor;
    SessionSource opened = source;
    if (running == null || opened == null) {
      return;
    }
    inspectionTask =
        running.submit(
            () -> {
              Inspection inspection = TestInspection.summary(row);
              SwingUtilities.invokeLater(
                  () -> {
                    if (sameSelection(row, opened, generation)) {
                      inspector.show(inspection);
                    }
                  });
            });
  }

  private void loadAttemptPage(
      TestRow row, Optional<TestQueries.TestAttemptAnchor> after, long generation) {
    ExecutorService details = detailExecutor;
    EntityReader reader = detailReader;
    SessionSource opened = source;
    if (details == null || reader == null || opened == null || attemptPageLoading) {
      return;
    }
    attemptPageLoading = true;
    attemptTask =
        details.submit(
            () -> {
              try {
                CountedPage<TestAttemptRow, TestQueries.TestAttemptAnchor> page =
                    reader.testAttemptPage(row.id(), after, TEST_ATTEMPT_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      if (!sameSelection(row, opened, generation)) {
                        return;
                      }
                      attemptPageLoading = false;
                      attemptPage = Optional.of(page);
                      renderDetailPagesAsync(row, generation);
                      inspectionPaging.setPage(
                          "attempts",
                          "Attempts",
                          page,
                          () ->
                              page.nextAnchor()
                                  .ifPresent(
                                      anchor ->
                                          loadAttemptPage(row, Optional.of(anchor), generation)));
                    });
              } catch (RuntimeException failure) {
                detailFailed(row, opened, generation, "attempts", failure);
              }
            });
  }

  private void loadLogPage(TestRow row, Optional<Long> after, long generation) {
    ExecutorService details = detailExecutor;
    EntityReader reader = detailReader;
    SessionSource opened = source;
    if (details == null || reader == null || opened == null || logPageLoading) {
      return;
    }
    logPageLoading = true;
    logTask =
        details.submit(
            () -> {
              try {
                CountedPage<TestQueries.TestLog, Long> page =
                    reader.testLogPage(row.id(), after, TEST_LOG_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      if (!sameSelection(row, opened, generation)) {
                        return;
                      }
                      logPageLoading = false;
                      logPage = Optional.of(page);
                      renderDetailPagesAsync(row, generation);
                      inspectionPaging.setPage(
                          "logs",
                          "Logs",
                          page,
                          () ->
                              page.nextAnchor()
                                  .ifPresent(
                                      anchor -> loadLogPage(row, Optional.of(anchor), generation)));
                    });
              } catch (RuntimeException failure) {
                detailFailed(row, opened, generation, "logs", failure);
              }
            });
  }

  private void loadSpawnPage(
      TestRow row, Optional<EnrichmentQueries.AttemptAnchor> after, long generation) {
    ExecutorService details = detailExecutor;
    EntityReader reader = detailReader;
    SessionSource opened = source;
    if (details == null || reader == null || opened == null || spawnPageLoading) {
      return;
    }
    spawnPageLoading = true;
    spawnTask =
        details.submit(
            () -> {
              try {
                CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> page =
                    reader.attemptsForLabelPage(row.label(), after, TEST_SPAWN_PAGE_SIZE);
                SwingUtilities.invokeLater(
                    () -> {
                      if (!sameSelection(row, opened, generation)) {
                        return;
                      }
                      spawnPageLoading = false;
                      spawnPage = Optional.of(page);
                      renderDetailPagesAsync(row, generation);
                      inspectionPaging.setPage(
                          "spawns",
                          "Subprocesses",
                          page,
                          () ->
                              page.nextAnchor()
                                  .ifPresent(
                                      anchor ->
                                          loadSpawnPage(row, Optional.of(anchor), generation)));
                    });
              } catch (RuntimeException failure) {
                detailFailed(row, opened, generation, "subprocesses", failure);
              }
            });
  }

  private void detailFailed(
      TestRow row, SessionSource opened, long generation, String detail, RuntimeException failure) {
    log.warn("could not read {} of test {}", detail, row.label(), failure);
    SwingUtilities.invokeLater(
        () -> {
          if (sameSelection(row, opened, generation)) {
            switch (detail) {
              case "attempts" -> attemptPageLoading = false;
              case "logs" -> logPageLoading = false;
              default -> spawnPageLoading = false;
            }
          }
        });
  }

  private boolean sameSelection(TestRow row, SessionSource opened, long generation) {
    if (source != opened || generation != selectionGeneration) {
      return false;
    }
    int selected = table.getSelectedRow();
    TestRow current = selected < 0 || tableModel == null ? null : tableModel.rowAt(selected);
    return current != null && current.id() == row.id();
  }

  /** Captures immutable page references on the EDT and builds the inspection on a worker. */
  private void renderDetailPagesAsync(TestRow row, long generation) {
    ExecutorService running = inspectionExecutor;
    SessionSource opened = source;
    if (running == null || opened == null) {
      return;
    }
    Optional<List<TestAttemptRow>> attempts = attemptPage.map(CountedPage::rows);
    Optional<List<TestQueries.TestLog>> logs = logPage.map(CountedPage::rows);
    Optional<List<AttemptRow>> spawns = spawnPage.map(CountedPage::rows);
    inspectionTask =
        running.submit(
            () -> {
              Inspection inspection = TestInspection.ofLoaded(row, attempts, logs, spawns);
              SwingUtilities.invokeLater(
                  () -> {
                    if (sameSelection(row, opened, generation)) {
                      inspector.show(inspection);
                    }
                  });
            });
  }

  private void resetDetailPages() {
    attemptPage = Optional.empty();
    logPage = Optional.empty();
    spawnPage = Optional.empty();
    attemptPageLoading = false;
    logPageLoading = false;
    spawnPageLoading = false;
  }

  private void cancelDetailTasks() {
    cancel(attemptTask);
    cancel(logTask);
    cancel(spawnTask);
    cancel(inspectionTask);
    attemptTask = null;
    logTask = null;
    spawnTask = null;
    inspectionTask = null;
  }

  private static void cancel(Future<?> task) {
    if (task != null) {
      task.cancel(false);
    }
  }

  private void setStatus(String value) {
    statusLabel.setText(value);
    setPageMetadata(value, value);
  }

  private void setPageMetadata(String concise, String detail) {
    pageMetadata = concise;
    pageMetadataDetail = detail;
    syncPageMetadata();
  }

  private void syncPageMetadata() {
    if (pageToolbar != null) {
      pageToolbar.setMetadata(pageMetadata, pageMetadataDetail);
    }
  }

  private static ExecutorService singleThreadExecutor(String name) {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void shutdown(ExecutorService executor) {
    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
