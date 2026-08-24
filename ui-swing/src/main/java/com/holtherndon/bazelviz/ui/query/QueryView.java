package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.storage.query.QueryRow;
import com.holtherndon.bazelviz.storage.query.ReadOnlySql;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.storage.query.SqlNotAllowedException;
import com.holtherndon.bazelviz.storage.query.TempViewDefinition;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Query card: SQL over the open session, in tabs.
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
 * <h2>Tabs, and why each owns a connection</h2>
 *
 * <p>Each tab ({@link QueryTab}) holds its own editor, its own results, and its
 * own {@code QueryReader} over its own read-only connection, on its own
 * executor. That is what makes two long queries genuinely concurrent — a JDBC
 * connection runs one statement at a time — and it is what scopes temp views:
 * a {@code CREATE TEMP VIEW} lives on the connection that ran it, so the
 * schema tree always shows the <em>selected</em> tab's connection.
 *
 * <h2>The library</h2>
 *
 * <p>Saved queries and saved views live as {@code .sql} files with a JSON
 * index under the application settings directory ({@link QueryLibrary}), so
 * they are the user's to edit and version. Saved views are replayed onto every
 * tab's connection when it opens and again whenever the saved set changes —
 * see {@code QueryReader#applyTempViews}. All library I/O runs on this view's
 * own single-threaded executor, never the EDT.
 *
 * <h2>Nothing typed here can write to the session</h2>
 *
 * <p>That is a guarantee and not a hope: every tab's connection is opened
 * {@code SQLITE_OPEN_READONLY}, the flag is fixed at open time, and no SQL
 * reaches it. In front of it sit {@code PRAGMA query_only} and
 * {@code ReadOnlySql}'s single-statement filter — refusals rather than
 * guarantees, lifted only for the one statement shape ({@code CREATE TEMP
 * VIEW}) that writes the connection's own temp schema and nothing else.
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

    /**
     * Most query tabs the card will open.
     *
     * <p>Sixteen. Every tab holds a SQLite connection and a thread, both real
     * resources, and past a handful of tabs the tab strip itself stops being
     * navigable. When the limit is hit the card says so, with the number and
     * the way out (close a tab); nothing existing is closed for you.
     */
    public static final int MAX_TABS = 16;

    /**
     * The editor's starting text: a real question over real columns, so the
     * first Run teaches the schema instead of erroring on it.
     *
     * <p>Durations are {@code end_micros - start_micros} and either end can be
     * NULL (a discovered-but-never-executed action, or a version that did not
     * report times). {@code SUM} skips NULL differences, so {@code total_ms}
     * covers exactly the rows {@code timed_actions} counts — the untimed ones
     * are counted in {@code actions} and not silently folded into a total they
     * are absent from.
     */
    static final String STARTER_SQL = """
            -- Read-only SQL over this session's database. Ctrl/Cmd-Enter runs.
            -- Double-click a table on the left to start one.
            -- Total execution time by mnemonic; timed_actions says how many
            -- rows the total actually covers, because either end of a duration
            -- can be NULL.
            SELECT mnemonics.value AS mnemonic,
                   COUNT(*) AS actions,
                   COUNT(actions.end_micros - actions.start_micros) AS timed_actions,
                   SUM(actions.end_micros - actions.start_micros) / 1000 AS total_ms
            FROM actions
            JOIN mnemonics ON mnemonics.id = actions.mnemonic_id
            GROUP BY mnemonics.value
            ORDER BY total_ms DESC""";

    private final SchemaBrowser schema = new SchemaBrowser();
    private final QueryLibraryPanel libraryPanel;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JButton newTabButton = new JButton("New tab");
    private final JButton renameTabButton = new JButton("Rename tab…");
    private final JButton closeTabButton = new JButton("Close tab");
    private final JLabel tabNotice = new JLabel(" ");

    /**
     * Library I/O and nothing else. A field with a thread for the same reason
     * every view here has one: this component reaches things that block (the
     * session, the library files), and the EDT is not where that happens.
     */
    private final ExecutorService io;

    private SessionSource source;
    private QueryLibrary library;

    /**
     * Where the tabs' shared column state lives once {@link #attachColumnState}
     * has run, kept so a tab opened later is attached the same way.
     */
    private java.nio.file.Path columnStateDirectory;
    private int nextTabNumber = 1;

    private final QueryTab.Host tabHost = new QueryTab.Host() {
        @Override
        public List<TempViewDefinition> savedViewDefinitions() {
            QueryLibrary lib = library;
            if (lib == null) {
                return List.of();
            }
            List<TempViewDefinition> definitions = new ArrayList<>();
            for (QueryLibrary.SavedView view : lib.views()) {
                definitions.add(new TempViewDefinition(view.name(), view.select()));
            }
            return List.copyOf(definitions);
        }

        @Override
        public void schemaUpdated(QueryTab tab, List<SchemaTable> tables) {
            if (tab == selectedTab()) {
                schema.show(tables);
            }
        }
    };

    public QueryView() {
        super(new BorderLayout());
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-query-library");
            thread.setDaemon(true);
            return thread;
        });
        this.libraryPanel = new QueryLibraryPanel(new PanelHost());

        PlainText.disableHtml(tabNotice);
        tabNotice.setEnabled(false);

        newTabButton.addActionListener(event -> addTab());
        renameTabButton.addActionListener(event -> renameSelectedTabInteractively());
        closeTabButton.addActionListener(event -> closeSelectedTab());
        tabs.addChangeListener(event -> {
            QueryTab selected = selectedTab();
            if (selected != null) {
                schema.show(selected.schemaTables());
            }
        });
        schema.onTableChosen(table -> {
            QueryTab selected = selectedTab();
            if (selected != null) {
                selected.insertTableQuery(table);
            }
        });

        JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        tabBar.add(newTabButton);
        tabBar.add(renameTabButton);
        tabBar.add(closeTabButton);
        tabBar.add(tabNotice);

        JPanel right = new JPanel(new BorderLayout());
        right.add(tabBar, BorderLayout.NORTH);
        right.add(tabs, BorderLayout.CENTER);

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, schema, libraryPanel);
        left.setResizeWeight(0.6);
        left.setBorder(null);
        schema.setMinimumSize(new Dimension(180, 120));
        libraryPanel.setMinimumSize(new Dimension(180, 100));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.22);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        addTab();
    }

    /**
     * Points the library at the application settings directory and loads it.
     *
     * <p>Called once by the application shell; path arithmetic only on the
     * EDT, with the listing (and the first-run example views) on the I/O
     * thread. Without a library the tabs still work — saving is what needs a
     * place on disk, not querying.
     */
    public void attachLibrary(Path settingsDirectory) {
        Objects.requireNonNull(settingsDirectory, "settingsDirectory");
        this.library = new QueryLibrary(settingsDirectory);
        QueryLibrary lib = this.library;
        io.execute(() -> {
            try {
                lib.seedExampleViewsIfNeverUsed();
            } catch (RuntimeException failure) {
                log.warn("could not seed the example views", failure);
            }
            refreshLibraryLists(lib);
        });
    }

    /**
     * Persists the result grids' column state under the application settings
     * directory — one shared state for every tab, present and future, since
     * the tabs are one surface over changing queries. Called once by the
     * application shell, beside {@link #attachLibrary}.
     */
    public void attachColumnState(Path settingsDirectory) {
        Objects.requireNonNull(settingsDirectory, "settingsDirectory");
        this.columnStateDirectory = settingsDirectory;
        for (QueryTab tab : allTabs()) {
            tab.attachColumnState(settingsDirectory);
        }
    }

    // ------------------------------------------------------------ session hooks

    /** Opens a session in every tab. Returns immediately. */
    public void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        source = newSource;
        for (QueryTab tab : allTabs()) {
            tab.openSession(newSource);
        }
    }

    /** Lets go of the session in every tab, off the EDT. */
    public void closeSession() {
        source = null;
        for (QueryTab tab : allTabs()) {
            tab.closeSession();
        }
        schema.clear();
    }

    // -------------------------------------------------------------------- tabs

    /** Opens a new query tab, up to {@link #MAX_TABS}. */
    public void addTab() {
        if (tabs.getTabCount() >= MAX_TABS) {
            tabNotice.setText("Tab limit reached: " + MAX_TABS
                    + " tabs are open. Close one to open another.");
            return;
        }
        tabNotice.setText(" ");
        QueryTab tab = new QueryTab(tabHost, STARTER_SQL);
        if (columnStateDirectory != null) {
            tab.attachColumnState(columnStateDirectory);
        }
        tabs.addTab("Query " + nextTabNumber++, tab);
        tabs.setSelectedComponent(tab);
        if (source != null) {
            tab.openSession(source);
        }
        newTabButton.setEnabled(tabs.getTabCount() < MAX_TABS);
    }

    /** Closes the selected tab and its connection. The last tab stays. */
    public void closeSelectedTab() {
        QueryTab selected = selectedTab();
        if (selected == null) {
            return;
        }
        if (tabs.getTabCount() <= 1) {
            tabNotice.setText("The last tab stays open; its session can still be closed"
                    + " from the session menu.");
            return;
        }
        tabNotice.setText(" ");
        selected.closeSession();
        tabs.remove(selected);
        newTabButton.setEnabled(tabs.getTabCount() < MAX_TABS);
        QueryTab now = selectedTab();
        if (now != null) {
            schema.show(now.schemaTables());
        }
    }

    /** Renames the selected tab. */
    public void renameSelectedTab(String title) {
        int index = tabs.getSelectedIndex();
        if (index >= 0 && title != null && !title.isBlank()) {
            tabs.setTitleAt(index, title.strip());
        }
    }

    private void renameSelectedTabInteractively() {
        int index = tabs.getSelectedIndex();
        if (index < 0) {
            return;
        }
        Object answer = JOptionPane.showInputDialog(this, "Tab name:", "Rename tab",
                JOptionPane.PLAIN_MESSAGE, null, null, tabs.getTitleAt(index));
        if (answer instanceof String title) {
            renameSelectedTab(title);
        }
    }

    private QueryTab selectedTab() {
        return tabs.getSelectedComponent() instanceof QueryTab tab ? tab : null;
    }

    private List<QueryTab> allTabs() {
        List<QueryTab> all = new ArrayList<>(tabs.getTabCount());
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof QueryTab tab) {
                all.add(tab);
            }
        }
        return all;
    }

    // ------------------------------------------------------------------ running

    /** Runs the selected tab's editor text. EDT only; returns immediately. */
    public void run() {
        QueryTab selected = selectedTab();
        if (selected != null) {
            selected.run();
        }
    }

    /** Abandons the selected tab's running query. Safe on the EDT. */
    public void cancel() {
        QueryTab selected = selectedTab();
        if (selected != null) {
            selected.cancel();
        }
    }

    // ----------------------------------------------------------------- library

    private void refreshLibraryLists(QueryLibrary lib) {
        try {
            List<QueryLibrary.SavedQuery> queries = lib.queries();
            List<QueryLibrary.SavedView> views = lib.views();
            SwingUtilities.invokeLater(() -> {
                if (library == lib) {
                    libraryPanel.showQueries(queries);
                    libraryPanel.showViews(views);
                }
            });
        } catch (RuntimeException failure) {
            log.warn("could not read the query library", failure);
            SwingUtilities.invokeLater(() -> libraryPanel.showProblem(
                    "The library could not be read: " + failure.getMessage()));
        }
    }

    private void afterViewsChanged(QueryLibrary lib) {
        refreshLibraryLists(lib);
        SwingUtilities.invokeLater(() -> {
            for (QueryTab tab : allTabs()) {
                tab.reapplySavedViews();
            }
        });
    }

    /** The library panel's way of asking this view to do things. */
    final class PanelHost {

        /** The selected tab's editor text, for saving. EDT only. */
        String currentEditorSql() {
            QueryTab selected = selectedTab();
            return selected == null ? "" : selected.editorText();
        }

        /** Loads {@code sql} into the selected tab's editor. EDT only. */
        void loadIntoEditor(String sql) {
            QueryTab selected = selectedTab();
            if (selected != null) {
                selected.setEditorText(sql);
            }
        }

        boolean hasLibrary() {
            return library != null;
        }

        void saveQuery(String name, String sql, Consumer<String> problem) {
            onLibrary(lib -> lib.saveQuery(name, sql), problem, false);
        }

        void renameQuery(String oldName, String newName, Consumer<String> problem) {
            onLibrary(lib -> lib.renameQuery(oldName, newName), problem, false);
        }

        void deleteQuery(String name, Consumer<String> problem) {
            onLibrary(lib -> lib.deleteQuery(name), problem, false);
        }

        /**
         * Saves the definition in the selected editor as a view. The editor
         * must hold a {@code CREATE TEMP VIEW} statement — that is where the
         * name and the body come from — and the refusal message says so.
         */
        void saveViewFromEditor(Consumer<String> problem) {
            String sql = currentEditorSql();
            ReadOnlySql.Statement checked;
            try {
                checked = ReadOnlySql.check(sql);
            } catch (SqlNotAllowedException refused) {
                problem.accept(refused.getMessage());
                return;
            }
            if (checked.shape() != ReadOnlySql.Shape.DEFINE) {
                problem.accept("To save a view, the editor must hold its definition:"
                        + " CREATE TEMP VIEW <name> AS SELECT …. The name and the"
                        + " SELECT are what get saved.");
                return;
            }
            String name = checked.tempViewName();
            String body = checked.tempViewSelect();
            onLibrary(lib -> lib.saveView(name, body), problem, true);
        }

        void renameView(String oldName, String newName, Consumer<String> problem) {
            onLibrary(lib -> lib.renameView(oldName, newName), problem, true);
        }

        void deleteView(String name, Consumer<String> problem) {
            onLibrary(lib -> lib.deleteView(name), problem, true);
        }

        private void onLibrary(
                Consumer<QueryLibrary> action, Consumer<String> problem, boolean viewsChanged) {
            QueryLibrary lib = library;
            if (lib == null) {
                problem.accept("No settings directory is attached, so nothing can be"
                        + " saved. This is expected only in tests.");
                return;
            }
            io.execute(() -> {
                try {
                    action.accept(lib);
                } catch (RuntimeException failure) {
                    SwingUtilities.invokeLater(() -> problem.accept(
                            String.valueOf(failure.getMessage())));
                    return;
                }
                if (viewsChanged) {
                    afterViewsChanged(lib);
                } else {
                    refreshLibraryLists(lib);
                }
            });
        }
    }

    // -------------------------------------------------------- visible for tests

    /** Visible for testing: sets the selected tab's editor text. */
    public void setSqlForTest(String sql) {
        QueryTab selected = selectedTab();
        if (selected != null) {
            selected.setEditorText(sql);
        }
    }

    /** Visible for testing: the selected tab's editor text. */
    public String sqlForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? "" : selected.editorText();
    }

    /** Visible for testing: sets the selected tab's row cap. */
    public void setRowCapForTest(int cap) {
        QueryTab selected = selectedTab();
        if (selected != null) {
            selected.setRowCapForTest(cap);
        }
    }

    /** Visible for testing: the selected tab's installed model, or null. */
    public PagedTableModel<QueryRow> tableModelForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? null : selected.tableModelForTest();
    }

    /** Visible for testing: the selected tab's row source. */
    public QueryRowSource rowSourceForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? null : selected.rowSourceForTest();
    }

    /** Visible for testing: the selected tab's status line. */
    public String statusForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? "" : selected.statusForTest();
    }

    /** Visible for testing: the selected tab's cap notice. */
    public String capNoticeForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? "" : selected.capNoticeForTest();
    }

    /** Visible for testing: the selected tab's error text. */
    public String errorForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? "" : selected.errorForTest();
    }

    /** Visible for testing: whether the selected tab's error area is shown. */
    public boolean errorShownForTest() {
        QueryTab selected = selectedTab();
        return selected != null && selected.errorShownForTest();
    }

    /** Visible for testing: whether the selected tab has a query in flight. */
    public boolean isRunningForTest() {
        QueryTab selected = selectedTab();
        return selected != null && selected.isRunning();
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
        return selectedTab() != null && selectedTab().raiseOfferShownForTest();
    }

    /** Visible for testing: the text of the raise-the-cap offer. */
    public String raiseOfferForTest() {
        QueryTab selected = selectedTab();
        return selected == null ? "" : selected.raiseOfferForTest();
    }

    /** Visible for testing: how many tabs are open. */
    public int tabCountForTest() {
        return tabs.getTabCount();
    }

    /** Visible for testing: selects a tab by index. */
    public void selectTabForTest(int index) {
        tabs.setSelectedIndex(index);
    }

    /** Visible for testing: the selected tab's title. */
    public String selectedTabTitleForTest() {
        int index = tabs.getSelectedIndex();
        return index < 0 ? "" : tabs.getTitleAt(index);
    }

    /** Visible for testing: the tab-strip notice. */
    public String tabNoticeForTest() {
        return tabNotice.getText();
    }

    /** Visible for testing: the library panel. */
    public QueryLibraryPanel libraryPanelForTest() {
        return libraryPanel;
    }

    /** Visible for testing: the panel host, for driving library actions. */
    public PanelHost panelHostForTest() {
        return new PanelHost();
    }
}
