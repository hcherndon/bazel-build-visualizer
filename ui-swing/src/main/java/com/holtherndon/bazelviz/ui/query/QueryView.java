package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.storage.query.AdHocQueries;
import com.holtherndon.bazelviz.storage.query.QueryFailedException;
import com.holtherndon.bazelviz.storage.query.QueryOutline;
import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.storage.query.SqlNotAllowedException;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Query card: a SQL editor over the open session, a schema tree beside it
 * and a paged result grid under it.
 *
 * <h2>What this is for</h2>
 *
 * <p>Perfetto's query page is the reason this exists: being able to ask a trace
 * a question nobody built a view for is the single most useful thing it does,
 * and it is unreachable on a build large enough to matter because Perfetto is a
 * web application. This is a desktop application over a local SQLite file, so
 * the size that stops Perfetto is not a constraint here — the same paging shell
 * the actions and events tables use serves a result of any size, a page at a
 * time.
 *
 * <h2>Nothing typed here can write to the session</h2>
 *
 * <p>That is a guarantee and not a hope: the connection is opened
 * {@code SQLITE_OPEN_READONLY}, the flag is fixed at open time, and no SQL
 * reaches it. Every write fails in SQLite's VFS whatever the statement says.
 *
 * <p>{@code PRAGMA query_only = ON} and {@code ReadOnlySql}'s single-read-only-
 * statement filter sit in front of that, and they are refusals rather than
 * guarantees — {@code query_only} is connection state and
 * {@code EXPLAIN PRAGMA query_only = OFF} clears it at prepare time, which is
 * why the filter treats {@code EXPLAIN} as transparent and why
 * {@code AdHocQueries} re-reads the flag before every execution. What they buy
 * is the things the open mode does not cover: a second statement out of a
 * semicolon-joined string, an {@code ATTACH} of somebody else's database, and
 * process-global settings such as {@code soft_heap_limit} that would reach the
 * writer connection ingesting a build.
 *
 * <h2>Threads</h2>
 *
 * <p>One single-threaded executor owns the one {@link QueryReader}, and serves
 * both the description (columns and count) and the grid's page fetches. One
 * thread rather than two on purpose: they share a connection, and a connection
 * is not thread-safe.
 *
 * <p>{@link QueryReader#cancel()} is the exception and is called straight from
 * the event thread. It is {@code sqlite3_interrupt}; it returns immediately and
 * cannot block. That is the whole cancel path: the button interrupts the
 * statement, the generation counter makes the in-flight answer stale, and the
 * executor thread unwinds on its own.
 */
public final class QueryView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(QueryView.class);

    /**
     * Lowest row cap the spinner offers.
     *
     * <p>One, not zero, for the reason the graph's node-limit spinner has the
     * same floor: a cap of zero shows an empty grid, and an empty grid reads as
     * "the query matched nothing" — a claim about the build rather than about
     * the setting.
     */
    public static final int MIN_ROW_LIMIT = 1;

    /**
     * Highest row cap the spinner offers.
     *
     * <p>Twenty million. Four times the largest planned session (Tier 3, five
     * million actions), so a join that multiplies rows still fits, and far
     * enough below {@code Integer.MAX_VALUE} that {@code JTable}'s int-based
     * row geometry — which is what actually stops working first — is nowhere
     * near its limit. An unbounded control would read as "no limit", which is a
     * claim plan 13.6 forbids.
     */
    public static final int MAX_ROW_LIMIT = 20_000_000;

    /** Pages held in the grid's LRU cache; a few thousand rows in memory. */
    private static final int CACHE_PAGES = 24;

    private static final String STARTER_SQL = """
            -- Read-only SQL over this session's database. Ctrl/Cmd-Enter runs.
            -- Double-click a table on the left to start one.
            SELECT mnemonic, COUNT(*) AS actions, SUM(duration_micros) / 1000 AS total_ms
            FROM actions
            GROUP BY mnemonic
            ORDER BY total_ms DESC""";

    private final SchemaBrowser schema = new SchemaBrowser();
    private final JTextArea editor = new JTextArea(STARTER_SQL, 8, 60);
    private final JButton runButton = new JButton("Run");
    private final JButton cancelButton = new JButton("Cancel");
    private final JSpinner rowCap = new JSpinner(new SpinnerNumberModel(
            AdHocQueries.DEFAULT_ROW_LIMIT, MIN_ROW_LIMIT, MAX_ROW_LIMIT, 100_000));
    private final JTable results = new JTable();
    private final JLabel status = new JLabel(" ");
    private final JLabel legend = new JLabel(" ");
    private final JLabel capNotice = new JLabel(" ");
    private final JButton raiseCapButton = new JButton("Raise the cap");
    private final JTextArea errorArea = new JTextArea(3, 60);
    private final JScrollPane errorScroll = new JScrollPane(errorArea);

    private ExecutorService executor;
    private QueryReader reader;
    private SessionSource source;
    private PagedTableModel<QueryRow> tableModel;
    private QueryRowSource rowSource;

    /** Bumped whenever a result stops being wanted: a new run, a cancel, a close. */
    private long generation;

    private boolean running;

    public QueryView() {
        super(new BorderLayout());

        PlainText.install(results);
        results.setDefaultRenderer(Object.class, new SqlValueRenderer());
        results.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setFillsViewportHeight(true);

        editor.setLineWrap(false);
        editor.setTabSize(2);
        editor.setFont(monospaced(editor));

        errorArea.setEditable(false);
        errorArea.setLineWrap(true);
        errorArea.setWrapStyleWord(true);
        errorScroll.setVisible(false);
        errorScroll.setBorder(BorderFactory.createTitledBorder("The query did not run"));

        PlainText.disableHtml(status);
        PlainText.disableHtml(legend);
        PlainText.disableHtml(capNotice);
        legend.setEnabled(false);
        legend.setText("An italic NULL is a SQL NULL — not 0, and not an empty string.");
        capNotice.setText(" ");
        raiseCapButton.setVisible(false);
        raiseCapButton.addActionListener(event -> raiseCapAndRerun());

        runButton.addActionListener(event -> run());
        cancelButton.addActionListener(event -> cancel());
        cancelButton.setEnabled(false);
        rowCap.setToolTipText(PlainText.tooltip(
                "Rows the grid will address. A query matching more says so and is not truncated"
                        + " on disk; raise this or add LIMIT/OFFSET to reach the rest."));

        schema.onTableChosen(this::insertTableQuery);
        installRunShortcut();

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, schema, buildRightSide());
        split.setResizeWeight(0.22);
        split.setBorder(null);
        schema.setMinimumSize(new Dimension(180, 120));
        add(split, BorderLayout.CENTER);

        setEnabledForSession(false);
        status.setText("Open a session to query it.");
    }

    private JComponent buildRightSide() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(runButton);
        toolbar.add(cancelButton);
        toolbar.add(PlainText.disableHtml(new JLabel("Row cap:")));
        toolbar.add(rowCap);

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar, BorderLayout.NORTH);
        JScrollPane editorScroll = new JScrollPane(editor);
        editorScroll.setMinimumSize(new Dimension(280, 90));
        top.add(editorScroll, BorderLayout.CENTER);
        top.add(errorScroll, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        JPanel capRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        capRow.add(capNotice);
        capRow.add(raiseCapButton);
        footer.add(status, BorderLayout.NORTH);
        footer.add(capRow, BorderLayout.CENTER);
        footer.add(legend, BorderLayout.SOUTH);
        footer.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));

        JPanel bottom = new JPanel(new BorderLayout());
        JScrollPane resultScroll = new JScrollPane(results);
        resultScroll.setMinimumSize(new Dimension(280, 120));
        bottom.add(resultScroll, BorderLayout.CENTER);
        bottom.add(footer, BorderLayout.SOUTH);

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        vertical.setResizeWeight(0.34);
        vertical.setBorder(null);
        return vertical;
    }

    private void installRunShortcut() {
        editor.getActionMap().put("bbv-run-query", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                run();
            }
        });
        // Both modifiers, spelled out rather than asked of the Toolkit:
        // getMenuShortcutKeyMaskEx() throws HeadlessException, and these views
        // are built headless in test. Cmd-Enter is the macOS habit and
        // Ctrl-Enter is everyone else's; binding both costs nothing.
        for (int modifier : new int[] {InputEvent.META_DOWN_MASK, InputEvent.CTRL_DOWN_MASK}) {
            editor.getInputMap(JComponent.WHEN_FOCUSED).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, modifier), "bbv-run-query");
        }
    }

    private static java.awt.Font monospaced(JComponent component) {
        return new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN,
                component.getFont() == null ? 12 : component.getFont().getSize());
    }

    // ------------------------------------------------------------ session hooks

    /** Opens a session and reads its schema. Returns immediately. */
    public void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        closeSession();
        source = newSource;
        executor = singleThreadExecutor("bbv-query");
        status.setText("Reading the schema…");
        ExecutorService opening = executor;
        opening.execute(() -> {
            try {
                QueryReader opened = newSource.openQueryReader();
                List<SchemaTable> tables = opened.schema();
                SwingUtilities.invokeLater(() -> {
                    if (source != newSource) {
                        closeQuietly(opened);
                        return;
                    }
                    reader = opened;
                    schema.show(tables);
                    setEnabledForSession(true);
                    status.setText(tables.size() + " tables and views. "
                            + "The connection is read-only; a runaway query is stopped after "
                            + opened.timeoutSeconds() + " s.");
                });
            } catch (RuntimeException failure) {
                log.error("could not open the query view", failure);
                SwingUtilities.invokeLater(() -> {
                    if (source == newSource) {
                        showError("This session could not be opened for queries.",
                                String.valueOf(failure.getMessage()));
                        status.setText("No queryable session.");
                    }
                });
            }
        });
    }

    /** Lets go of the session, off the EDT, without leaving a statement running. */
    public void closeSession() {
        generation++;
        running = false;
        tableModel = null;
        rowSource = null;
        results.setModel(new DefaultTableModel());
        schema.clear();
        clearError();
        capNotice.setText(" ");
        raiseCapButton.setVisible(false);
        setEnabledForSession(false);
        status.setText("Open a session to query it.");

        QueryReader closing = reader;
        ExecutorService stopping = executor;
        reader = null;
        executor = null;
        source = null;
        if (closing == null && stopping == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            // Interrupt before shutting down: a describe that is mid-scan would
            // otherwise hold the executor for as long as the scan takes, and
            // closing the connection under it blocks too. This is what keeps a
            // session close from wedging behind a query nobody wants.
            if (closing != null) {
                closing.cancel();
            }
            shutdown(stopping);
            if (closing != null) {
                closing.close();
            }
        }, "bbv-query-close");
        closer.setDaemon(true);
        closer.start();
    }

    // ------------------------------------------------------------------ running

    /** Runs whatever is in the editor. EDT only; returns immediately. */
    public void run() {
        if (reader == null || running) {
            return;
        }
        String sql = editor.getText();
        long cap = ((Number) rowCap.getValue()).longValue();
        long mine = ++generation;
        QueryReader active = reader;
        ExecutorService on = executor;
        if (on == null) {
            return;
        }
        setRunning(true);
        clearError();
        capNotice.setText(" ");
        raiseCapButton.setVisible(false);
        status.setText("Running…");
        on.execute(() -> {
            try {
                QueryOutline outline = active.describe(sql, cap);
                QueryRowSource built = new QueryRowSource(active, outline);
                SwingUtilities.invokeLater(() -> install(mine, built));
            } catch (SqlNotAllowedException refused) {
                SwingUtilities.invokeLater(() -> {
                    if (isCurrent(mine)) {
                        setRunning(false);
                        showError("Refused before anything ran.", refused.getMessage());
                        status.setText("Nothing was executed.");
                    }
                });
            } catch (QueryFailedException failed) {
                SwingUtilities.invokeLater(() -> {
                    if (isCurrent(mine)) {
                        setRunning(false);
                        reportFailure(failed);
                    }
                });
            } catch (RuntimeException unexpected) {
                log.error("the query view failed unexpectedly", unexpected);
                SwingUtilities.invokeLater(() -> {
                    if (isCurrent(mine)) {
                        setRunning(false);
                        showError("The query could not be run.",
                                String.valueOf(unexpected.getMessage()));
                        status.setText("The query did not run.");
                    }
                });
            }
        });
    }

    /**
     * Abandons the running query.
     *
     * <p>Called on the EDT and deliberately so: the interrupt underneath is
     * {@code sqlite3_interrupt}, which returns without waiting for the
     * statement. Nothing here blocks and nothing is left running — the
     * generation bump means the answer, when it unwinds, is discarded.
     */
    public void cancel() {
        if (!running) {
            return;
        }
        generation++;
        QueryReader active = reader;
        if (active != null) {
            active.cancel();
        }
        setRunning(false);
        status.setText("Stopped. Nothing was changed; the connection is still open.");
    }

    private boolean isCurrent(long mine) {
        return mine == generation;
    }

    private void install(long mine, QueryRowSource built) {
        if (!isCurrent(mine)) {
            return;
        }
        setRunning(false);
        QueryOutline outline = built.outline();
        if (outline.columns().isEmpty()) {
            // PRAGMA foreign_key_check on a clean database is the real case:
            // it returns no columns at all, and a zero-column table model
            // would throw rather than say so.
            results.setModel(new DefaultTableModel());
            tableModel = null;
            rowSource = null;
            status.setText("The statement returned no columns and no rows.");
            return;
        }
        rowSource = built;
        tableModel = new PagedTableModel<>(
                built, built.columns(), executor, QueryRowSource.PAGE_SIZE, CACHE_PAGES);
        results.setModel(tableModel);
        sizeColumns();
        status.setText(describe(outline));
        showCapNotice(outline);
    }

    private void sizeColumns() {
        for (int i = 0; i < results.getColumnModel().getColumnCount(); i++) {
            results.getColumnModel().getColumn(i).setPreferredWidth(160);
        }
    }

    private String describe(QueryOutline outline) {
        StringBuilder text = new StringBuilder();
        if (outline.isCapped()) {
            text.append(count(outline.visibleRows())).append(" of ");
            text.append(outline.matchedRows().isPresent()
                    ? count(outline.matchedRows().getAsLong()) + " rows"
                    : "an uncounted number of rows");
        } else {
            text.append(count(outline.visibleRows())).append(" rows");
        }
        text.append(" · ").append(outline.columns().size()).append(" columns · counted in ")
                .append(millis(outline.elapsedNanos()));
        return text.toString();
    }

    /**
     * Rule 12: a capped result says so, with both exact numbers and a way out.
     *
     * <p>Nothing is dropped from the query — the rows are still in the database
     * and the statement is unchanged. What is bounded is how much of the result
     * the grid claims to be a view of, and that has to be visible or the last
     * row on screen reads as the last row there is.
     */
    private void showCapNotice(QueryOutline outline) {
        if (!outline.isCapped()) {
            capNotice.setText(" ");
            raiseCapButton.setVisible(false);
            return;
        }
        long visible = outline.visibleRows();
        String total = outline.matchedRows().isPresent()
                ? count(outline.matchedRows().getAsLong()) + " that match"
                : "an uncounted number that match";
        capNotice.setText("Showing the first " + count(visible) + " rows of " + total
                + ". The row cap is " + count(outline.rowLimit())
                + "; nothing was dropped from the database. Raise the cap, or add"
                + " LIMIT/OFFSET to the query, to reach the rest.");
        long raised = raisedCap(outline);
        raiseCapButton.setText("Raise to " + count(raised) + " and run again");
        raiseCapButton.setVisible(raised > outline.rowLimit());
        raiseCapButton.putClientProperty("bbv.raisedCap", raised);
    }

    private static long raisedCap(QueryOutline outline) {
        long wanted = outline.matchedRows().isPresent()
                ? outline.matchedRows().getAsLong()
                : outline.rowLimit() * 2;
        return Math.min(MAX_ROW_LIMIT, Math.max(wanted, outline.rowLimit() + 1));
    }

    private void raiseCapAndRerun() {
        Object raised = raiseCapButton.getClientProperty("bbv.raisedCap");
        if (raised instanceof Long value) {
            rowCap.setValue((int) Math.min(MAX_ROW_LIMIT, value));
            run();
        }
    }

    private void reportFailure(QueryFailedException failed) {
        if (failed.wasStopped()) {
            // Not an error. A stopped query reported as one teaches people to
            // ignore the error area.
            clearError();
            status.setText("Stopped after " + (reader == null ? "the deadline"
                    : reader.timeoutSeconds() + " s") + ". Nothing was changed.");
            return;
        }
        showError("SQLite refused this statement.",
                failed.getMessage()
                        + "\n\nStage: " + failed.stage().description()
                        + "\nStatement as executed:\n" + failed.statement());
        status.setText("The query did not run.");
    }

    private void showError(String title, String detail) {
        errorScroll.setBorder(BorderFactory.createTitledBorder(title));
        errorArea.setText(detail);
        errorArea.setCaretPosition(0);
        errorScroll.setVisible(true);
        revalidate();
        repaint();
    }

    private void clearError() {
        errorArea.setText("");
        errorScroll.setVisible(false);
    }

    private void insertTableQuery(String table) {
        editor.setText("SELECT *\nFROM " + quoteIfNeeded(table) + "\nLIMIT 200");
        editor.setCaretPosition(editor.getDocument().getLength());
    }

    private static String quoteIfNeeded(String table) {
        return table.matches("[A-Za-z_][A-Za-z0-9_]*")
                ? table
                : "\"" + table.replace("\"", "\"\"") + "\"";
    }

    private void setRunning(boolean nowRunning) {
        running = nowRunning;
        runButton.setEnabled(!nowRunning && reader != null);
        cancelButton.setEnabled(nowRunning);
        rowCap.setEnabled(!nowRunning);
    }

    private void setEnabledForSession(boolean open) {
        runButton.setEnabled(open);
        cancelButton.setEnabled(false);
        rowCap.setEnabled(open);
        editor.setEnabled(open);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String millis(long nanos) {
        double ms = nanos / 1_000_000.0;
        return ms >= 10 ? String.format(Locale.ROOT, "%,.0f ms", ms)
                : String.format(Locale.ROOT, "%.1f ms", ms);
    }

    private static ExecutorService singleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void shutdown(ExecutorService service) {
        if (service == null) {
            return;
        }
        service.shutdownNow();
        try {
            service.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(QueryReader opened) {
        try {
            opened.close();
        } catch (RuntimeException failure) {
            log.debug("a superseded query reader would not close", failure);
        }
    }

    // -------------------------------------------------------- visible for tests

    /** Visible for testing: sets the editor text as a user typing would. */
    public void setSqlForTest(String sql) {
        editor.setText(sql);
    }

    /** Visible for testing: sets the row cap as the spinner would. */
    public void setRowCapForTest(int cap) {
        rowCap.setValue(cap);
    }

    /** Visible for testing: the installed model, or null when there is none. */
    public PagedTableModel<QueryRow> tableModelForTest() {
        return tableModel;
    }

    /** Visible for testing: the source behind the current grid. */
    public QueryRowSource rowSourceForTest() {
        return rowSource;
    }

    /** Visible for testing: the status line. */
    public String statusForTest() {
        return status.getText();
    }

    /** Visible for testing: the cap notice, or a single space when there is none. */
    public String capNoticeForTest() {
        return capNotice.getText();
    }

    /** Visible for testing: the error text, empty when there is none. */
    public String errorForTest() {
        return errorArea.getText();
    }

    /** Visible for testing: whether the error area is on screen. */
    public boolean errorShownForTest() {
        return errorScroll.isVisible();
    }

    /** Visible for testing: whether a query is in flight. */
    public boolean isRunningForTest() {
        return running;
    }

    /** Visible for testing: the schema tree's table labels. */
    public List<String> schemaTablesForTest() {
        return schema.listedTablesForTest();
    }

    /** Visible for testing: the schema tree's columns for one table. */
    public List<String> schemaColumnsForTest(String table) {
        return schema.listedColumnsForTest(table);
    }

    /** Visible for testing: drives the schema filter box. */
    public void filterSchemaForTest(String text) {
        schema.filterForTest(text);
    }

    /** Visible for testing: the schema tree's summary line. */
    public String schemaSummaryForTest() {
        return schema.summaryForTest();
    }

    /** Visible for testing: whether the raise-the-cap offer is on screen. */
    public boolean raiseOfferShownForTest() {
        return raiseCapButton.isVisible();
    }

    /** Visible for testing: the text of the raise-the-cap offer. */
    public String raiseOfferForTest() {
        return raiseCapButton.getText();
    }
}
