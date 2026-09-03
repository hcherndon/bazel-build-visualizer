package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.storage.query.AdHocQueries;
import com.holtherndon.bazelviz.storage.query.QueryFailedException;
import com.holtherndon.bazelviz.storage.query.QueryOutline;
import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.storage.query.ReadOnlySql;
import com.holtherndon.bazelviz.storage.query.SchemaColumn;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.storage.query.SqlNotAllowedException;
import com.holtherndon.bazelviz.storage.query.TempViewDefinition;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.table.TableHeaderInteractions;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.SyntaxTextTheme;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One query tab: an editor, its results, and — the reason tabs exist at all —
 * its own {@link QueryReader} over its own connection, on its own thread.
 *
 * <p>The per-tab connection is not an implementation detail. A JDBC connection
 * runs one statement at a time, so two queries on one connection would queue
 * behind each other; a tab that owns its connection runs concurrently with
 * every other tab, the same per-view arrangement the events and actions tables
 * use. It is also what gives temp views their scope: a {@code CREATE TEMP
 * VIEW} typed into this tab exists on this tab's connection and no other,
 * which the schema tree shows by listing this connection's temp views.
 *
 * <h2>Threads</h2>
 *
 * <p>One single-threaded executor owns the one reader, serving the describe
 * and the grid's page fetches — one thread rather than two because they share
 * a connection and a connection is not thread-safe. {@link #cancel()} is the
 * exception, called straight from the EDT: it is {@code sqlite3_interrupt},
 * returns immediately, and cannot block.
 *
 * <p>Everything the old single-editor Query card guaranteed still holds per
 * tab: results are paged, a capped result says so with both numbers and an
 * offer, a NULL is rendered as NULL, a stopped query is not an error, and
 * closing under a running statement hands the waiting to a daemon thread.
 */
final class QueryTab extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(QueryTab.class);

    /** Pages held in the grid's LRU cache; a few thousand rows in memory. */
    private static final int CACHE_PAGES = 24;

    private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();

    /** What a tab needs from the shell around it. */
    interface Host {

        /**
         * The saved view definitions to replay onto this tab's connection.
         * Called on the tab's executor thread — it may read files.
         */
        List<TempViewDefinition> savedViewDefinitions();

        /** Called on the EDT whenever this tab's schema was (re)read. */
        void schemaUpdated(QueryTab tab, List<SchemaTable> tables);
    }

    private final Host host;
    private final RSyntaxTextArea editor;
    private final RTextScrollPane editorScroll;
    private final DefaultCompletionProvider completions = new DefaultCompletionProvider();
    private final JButton runButton = new JButton("Run");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton formatButton = new JButton("Format");
    private final JSpinner rowCap = new JSpinner(new SpinnerNumberModel(
            AdHocQueries.DEFAULT_ROW_LIMIT, QueryView.MIN_ROW_LIMIT,
            QueryView.MAX_ROW_LIMIT, 100_000));
    private final JTable results = new JTable();

    /**
     * Why the result grid's headers do not sort: the order is the query's
     * business, and pretending otherwise would either re-run SQL the user
     * did not write or sort only the fetched pages of a paged result.
     */
    static final String ORDER_BELONGS_TO_THE_SQL =
            "Result order belongs to the query — add ORDER BY to the SQL to"
                    + " change it. The grid pages rows straight from the"
                    + " database in the order SQLite returned them, and"
                    + " re-sorting only the pages already fetched would"
                    + " misrepresent the rest.";

    /**
     * The shared header behaviour: no sorting (see
     * {@link #ORDER_BELONGS_TO_THE_SQL}), but the column menu and the
     * persisted column state, matched to result columns by name — every
     * query tab shares the one {@code query} state file.
     */
    private final TableHeaderInteractions headerInteractions;

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
    private List<SchemaTable> schemaTables = List.of();

    /** Bumped whenever a result stops being wanted: a new run, a cancel, a close. */
    private long generation;

    private boolean running;

    QueryTab(Host host, String initialSql) {
        super(new BorderLayout());
        this.host = Objects.requireNonNull(host, "host");

        editor = new RSyntaxTextArea(initialSql, 8, 60);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        editor.setTabSize(2);
        editor.setMarkOccurrences(true);
        installCompletion();
        editorScroll = new RTextScrollPane(editor);
        editorScroll.setLineNumbersEnabled(true);
        editorScroll.setMinimumSize(new Dimension(280, 90));
        SyntaxTextTheme.apply(editor, editorScroll);

        PlainText.install(results);
        results.setDefaultRenderer(Object.class, new SqlValueRenderer());
        results.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setFillsViewportHeight(true);
        headerInteractions = TableHeaderInteractions.install(
                results, TableHeaderInteractions.Adapter.unsortable(ORDER_BELONGS_TO_THE_SQL));

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
        formatButton.addActionListener(event -> format());
        formatButton.setToolTipText(PlainText.tooltip(
                "Reformat the SQL in the editor. Text only; nothing is executed."));
        rowCap.setToolTipText(PlainText.tooltip(
                "Rows the grid will address. A query matching more says so and is not truncated"
                        + " on disk; raise this or add LIMIT/OFFSET to reach the rest."));

        installRunShortcut();
        add(buildLayout(), BorderLayout.CENTER);

        setEnabledForSession(false);
        status.setText("Open a session to query it.");
    }

    private JComponent buildLayout() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(runButton);
        toolbar.add(cancelButton);
        toolbar.add(formatButton);
        toolbar.add(PlainText.disableHtml(new JLabel("Row cap:")));
        toolbar.add(rowCap);

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar, BorderLayout.NORTH);
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

        JSplitPane vertical = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new SectionPane("Query", top),
                new SectionPane("Results", bottom));
        vertical.setResizeWeight(0.34);
        vertical.setBorder(null);
        return vertical;
    }

    /**
     * Completion over the words this tab's connection can actually resolve:
     * table and view names (temp views included, because they are in the
     * schema listing) and column names. Refilled whenever the schema is.
     */
    private void installCompletion() {
        completions.setAutoActivationRules(false, null);
        AutoCompletion autoCompletion = new AutoCompletion(completions);
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.install(editor);
    }

    private void refillCompletions(List<SchemaTable> tables) {
        completions.clear();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SchemaTable table : tables) {
            String kind = table.isTemp() ? "temp view"
                    : table.isView() ? "view" : "table";
            if (seen.add(table.name())) {
                completions.addCompletion(
                        new BasicCompletion(completions, table.name(), kind));
            }
            for (SchemaColumn column : table.columns()) {
                if (seen.add(column.name())) {
                    completions.addCompletion(new BasicCompletion(
                            completions, column.name(), "column of " + table.name()));
                }
            }
        }
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

    // ------------------------------------------------------------ session hooks

    /** Opens this tab's own reader over the session. Returns immediately. */
    void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        closeSession();
        source = newSource;
        executor = singleThreadExecutor(
                "bbv-query-tab-" + THREAD_NUMBER.incrementAndGet());
        status.setText("Reading the schema…");
        ExecutorService opening = executor;
        opening.execute(() -> {
            try {
                QueryReader opened = newSource.openQueryReader();
                List<String> viewProblems = opened.applyTempViews(host.savedViewDefinitions());
                List<SchemaTable> tables = opened.schema();
                SwingUtilities.invokeLater(() -> {
                    if (source != newSource) {
                        closeQuietly(opened);
                        return;
                    }
                    reader = opened;
                    schemaTables = tables;
                    refillCompletions(tables);
                    host.schemaUpdated(this, tables);
                    setEnabledForSession(true);
                    status.setText(tables.size() + " tables and views. "
                            + "The connection is read-only; a runaway query is stopped after "
                            + opened.timeoutSeconds() + " s.");
                    if (viewProblems.isEmpty()) {
                        clearError();
                    } else {
                        showError("Some saved views were not applied.",
                                String.join("\n", viewProblems));
                    }
                });
            } catch (RuntimeException failure) {
                log.error("could not open a query tab", failure);
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
    void closeSession() {
        closeSessionAsync();
    }

    /** Detaches immediately and completes after this tab's query reader has stopped. */
    CompletionStage<Void> closeSessionAsync() {
        generation++;
        running = false;
        tableModel = null;
        rowSource = null;
        schemaTables = List.of();
        results.setModel(new DefaultTableModel());
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
            return CompletableFuture.completedFuture(null);
        }
        return ViewClose.runAsync("bbv-query-close", () -> {
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
        });
    }

    /** Permanently closes the tab, including its debounced column-state writer. */
    CompletionStage<Void> closeAsync() {
        return CompletableFuture.allOf(
                closeSessionAsync().toCompletableFuture(),
                headerInteractions.closeAsync().toCompletableFuture());
    }

    /**
     * Replays the saved view set again — after a save, rename or delete in the
     * library — and re-reads the schema so the tree and the completions agree
     * with what the connection now holds.
     */
    void reapplySavedViews() {
        QueryReader active = reader;
        ExecutorService on = executor;
        if (active == null || on == null) {
            return;
        }
        on.execute(() -> {
            try {
                List<String> problems = active.applyTempViews(host.savedViewDefinitions());
                List<SchemaTable> tables = active.schema();
                SwingUtilities.invokeLater(() -> {
                    if (reader != active) {
                        return;
                    }
                    schemaTables = tables;
                    refillCompletions(tables);
                    host.schemaUpdated(this, tables);
                    if (!problems.isEmpty()) {
                        showError("Some saved views were not applied.",
                                String.join("\n", problems));
                    }
                });
            } catch (RuntimeException failure) {
                log.warn("could not reapply saved views", failure);
            }
        });
    }

    // ------------------------------------------------------------------ running

    /** Runs whatever is in the editor. EDT only; returns immediately. */
    void run() {
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
                if (outline.shape() == ReadOnlySql.Shape.DEFINE) {
                    // The view now exists on this connection; the schema is
                    // the result that changed, so it is what gets refreshed.
                    List<SchemaTable> tables = active.schema();
                    SwingUtilities.invokeLater(() -> installDefined(mine, tables));
                    return;
                }
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
                log.error("the query tab failed unexpectedly", unexpected);
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
    void cancel() {
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

    /** Reformats the editor text. Text-only; nothing is executed. */
    private void format() {
        String sql = editor.getText();
        if (sql.isBlank()) {
            return;
        }
        try {
            editor.setText(com.github.vertical_blank.sqlformatter.SqlFormatter.format(sql));
            editor.setCaretPosition(0);
        } catch (RuntimeException couldNotFormat) {
            // The formatter is cosmetic; its failure must not read like a
            // query failure. The SQL is untouched.
            status.setText("Could not format this text; it is unchanged.");
        }
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
        // Reapply remembered widths and visibility to whatever result
        // columns match by name; columns this state has never seen keep
        // sizeColumns' default.
        headerInteractions.modelInstalled();
        status.setText(describe(outline));
        showCapNotice(outline);
    }

    private void installDefined(long mine, List<SchemaTable> tables) {
        if (!isCurrent(mine)) {
            return;
        }
        setRunning(false);
        schemaTables = tables;
        refillCompletions(tables);
        host.schemaUpdated(this, tables);
        status.setText("Temporary view defined — on this tab's connection only, and gone"
                + " when it closes. Save it in the library to keep it.");
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
        return Math.min(QueryView.MAX_ROW_LIMIT, Math.max(wanted, outline.rowLimit() + 1));
    }

    private void raiseCapAndRerun() {
        Object raised = raiseCapButton.getClientProperty("bbv.raisedCap");
        if (raised instanceof Long value) {
            rowCap.setValue((int) Math.min(QueryView.MAX_ROW_LIMIT, value));
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

    void insertTableQuery(String table) {
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

    // ------------------------------------------------------------------- editor

    String editorText() {
        return editor.getText();
    }

    void setEditorText(String sql) {
        editor.setText(sql);
        editor.setCaretPosition(0);
    }

    /** The schema this tab's connection last reported. EDT only. */
    List<SchemaTable> schemaTables() {
        return schemaTables;
    }

    boolean isRunning() {
        return running;
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

    /**
     * Persists the result grid's column state (widths, visibility) under the
     * application settings directory, matched by column name across queries.
     * Called by {@code QueryView} for each tab; the load and every save run
     * on the store's I/O thread, never the EDT.
     */
    void attachColumnState(java.nio.file.Path settingsDirectory) {
        headerInteractions.attachPersistence(settingsDirectory, "query");
    }

    // -------------------------------------------------------- visible for tests

    TableHeaderInteractions headerInteractionsForTest() {
        return headerInteractions;
    }

    RSyntaxTextArea editorForTest() {
        return editor;
    }

    RTextScrollPane editorScrollForTest() {
        return editorScroll;
    }

    void setRowCapForTest(int cap) {
        rowCap.setValue(cap);
    }

    PagedTableModel<QueryRow> tableModelForTest() {
        return tableModel;
    }

    QueryRowSource rowSourceForTest() {
        return rowSource;
    }

    String statusForTest() {
        return status.getText();
    }

    String capNoticeForTest() {
        return capNotice.getText();
    }

    String errorForTest() {
        return errorArea.getText();
    }

    boolean errorShownForTest() {
        return errorScroll.isVisible();
    }

    boolean raiseOfferShownForTest() {
        return raiseCapButton.isVisible();
    }

    String raiseOfferForTest() {
        return raiseCapButton.getText();
    }
}
