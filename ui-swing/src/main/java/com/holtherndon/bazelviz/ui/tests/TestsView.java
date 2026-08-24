package com.holtherndon.bazelviz.ui.tests;

import com.holtherndon.bazelviz.storage.entities.TestRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.table.TableHeaderInteractions;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Tests card: every test target's verdict, worst first, with its attempts
 * in the inspector.
 *
 * <p>The verdict shown is {@code testSummary.overallStatus} and nothing else.
 * A target's own {@code success} flag was measured {@code true} for a test that
 * failed, so a view built on that would show every failure green — which is the
 * single most consequential thing this view could get wrong.
 */
public final class TestsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(TestsView.class);

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABLE = "table";
    private static final int CACHE_PAGES = 16;

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JTable table = new JTable();
    private final InspectorPanel inspector = new InspectorPanel();
    private final JLabel statusLabel = new JLabel(" ");

    /**
     * Why the test table's headers do not sort: the worst-first ordering is
     * the product ({@link TestRowSource}'s fixed failures-first order), not
     * an arbitrary default a header click should overwrite.
     */
    static final String ORDER_IS_FIXED =
            "This table is deliberately ordered worst-first — failures, then"
                    + " timeouts and build failures, then flakes, then passes"
                    + " — so what went wrong is at the top without asking."
                    + " That ordering is the product, and it is fixed.";

    /**
     * The shared header behaviour: no sorting (see {@link #ORDER_IS_FIXED}),
     * but the column menu and the persisted column state.
     */
    private final TableHeaderInteractions headerInteractions;

    private ExecutorService pageExecutor;
    private ExecutorService detailExecutor;
    private EntityReader pageReader;
    private EntityReader detailReader;
    private SessionSource source;
    private PagedTableModel<TestRow> tableModel;
    private LongConsumer showEventHandler = eventId -> { };
    private long selectionGeneration;

    public TestsView() {
        super(new BorderLayout());

        emptyLabel.setEnabled(false);
        JPanel empty = new JPanel(new BorderLayout());
        empty.add(emptyLabel, BorderLayout.CENTER);

        PlainText.install(table);
        PlainText.disableHtml(statusLabel);
        PlainText.disableHtml(emptyLabel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectionChanged();
            }
        });
        inspector.onShowSourceEvent(eventId -> showEventHandler.accept(eventId));
        headerInteractions = TableHeaderInteractions.install(
                table, TableHeaderInteractions.Adapter.unsortable(ORDER_IS_FIXED));

        JScrollPane scroll = new JScrollPane(table);
        scroll.setMinimumSize(new Dimension(320, 160));
        inspector.setMinimumSize(new Dimension(300, 160));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, inspector);
        split.setResizeWeight(0.62);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(statusLabel, BorderLayout.WEST);

        JPanel session = new JPanel(new BorderLayout());
        session.add(split, BorderLayout.CENTER);
        session.add(status, BorderLayout.SOUTH);

        deck.add(empty, CARD_EMPTY);
        deck.add(session, CARD_TABLE);
        add(deck, BorderLayout.CENTER);
        showEmpty("No session is open.");
    }

    public void onShowSourceEvent(LongConsumer handler) {
        this.showEventHandler = Objects.requireNonNull(handler, "handler");
    }

    public void showEmpty(String message) {
        emptyLabel.setText(Objects.requireNonNull(message, "message"));
        cards.show(deck, CARD_EMPTY);
    }

    /** Opens a session and builds the table over it. Returns immediately. */
    public void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        closeSession();
        source = newSource;
        pageExecutor = singleThreadExecutor("bbv-tests-pages");
        detailExecutor = singleThreadExecutor("bbv-tests-detail");
        showEmpty("Reading tests…");
        ExecutorService opening = pageExecutor;
        opening.execute(() -> {
            try {
                EntityReader pages = newSource.openEntityReader();
                EntityReader detail = newSource.openEntityReader();
                TestRowSource rows =
                        TestRowSource.open(pages, TestRowSource.DEFAULT_PAGE_SIZE);
                SwingUtilities.invokeLater(() -> {
                    if (source != newSource) {
                        pages.close();
                        detail.close();
                        return;
                    }
                    pageReader = pages;
                    detailReader = detail;
                    install(rows);
                });
            } catch (RuntimeException failure) {
                log.error("could not read tests", failure);
                SwingUtilities.invokeLater(() -> showEmpty(failure.getMessage()));
            }
        });
    }

    public void closeSession() {
        tableModel = null;
        table.setModel(new DefaultTableModel());
        inspector.show(Inspection.NONE);
        ExecutorService pages = pageExecutor;
        ExecutorService details = detailExecutor;
        EntityReader pageSide = pageReader;
        EntityReader detailSide = detailReader;
        source = null;
        pageExecutor = null;
        detailExecutor = null;
        pageReader = null;
        detailReader = null;
        if (pages == null && details == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            shutdown(pages);
            shutdown(details);
            if (pageSide != null) {
                pageSide.close();
            }
            if (detailSide != null) {
                detailSide.close();
            }
        }, "bbv-tests-close");
        closer.setDaemon(true);
        closer.start();
    }

    /**
     * Persists this table's column state (widths, visibility, order) under
     * the application settings directory. Called once at wiring time; the
     * load and every save run on the store's I/O thread, never the EDT.
     */
    public void attachColumnState(java.nio.file.Path settingsDirectory) {
        headerInteractions.attachPersistence(settingsDirectory, "tests");
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
        tableModel = new PagedTableModel<>(
                rows, TestTableColumns.columns(), pageExecutor, rows.pageSize(), CACHE_PAGES);
        tableModel.addTableModelListener(event -> {
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
        statusLabel.setText(EntityFormat.count(rows.rowCount())
                + (rows.rowCount() == 1 ? " test" : " tests"));
        cards.show(deck, CARD_TABLE);
    }

    private void selectionChanged() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || tableModel == null) {
            inspector.show(Inspection.NONE);
            return;
        }
        TestRow row = tableModel.rowAt(viewRow);
        if (row == null) {
            return;
        }
        // The attempts and logs are separate reads, so they happen off the EDT
        // and the inspector fills in when they land.
        long generation = ++selectionGeneration;
        ExecutorService details = detailExecutor;
        EntityReader reader = detailReader;
        if (details == null || reader == null) {
            return;
        }
        details.execute(() -> {
            try {
                Inspection inspection = TestInspection.of(
                        row, reader.testAttempts(row.id()), reader.testLogs(row.id()),
                        // The execution log's own record of this test. Reached
                        // by label because a test spawn matches no action by
                        // output on any Bazel version (K2).
                        reader.attemptsForLabel(row.label()));
                SwingUtilities.invokeLater(() -> {
                    if (generation == selectionGeneration) {
                        inspector.show(inspection);
                    }
                });
            } catch (RuntimeException failure) {
                log.warn("could not read the attempts of test {}", row.label(), failure);
            }
        });
    }

    private static ExecutorService singleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
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
