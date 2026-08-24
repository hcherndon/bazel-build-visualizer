package com.holtherndon.bazelviz.ui.table;

import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

/**
 * The shared table-header behaviour: 3-state sort on left-click where a
 * column honestly sorts, a right-click menu with column-visibility
 * checkboxes and an explicit sort submenu, per-column header tooltips, and a
 * {@link ColumnState} that survives {@code JTable.setModel} swaps and — once
 * {@link #attachPersistence(Path, String) attached} — application restarts.
 *
 * <h2>The 3-state cycle</h2>
 *
 * <p>A left-click on a sortable header cycles ascending → descending →
 * default order. The third state is deliberate: every view here has a
 * default ordering that means something (arrival order, kind priority), and
 * a sort control that can never return to it silently overrides the design
 * (rule 12). What a click means for the data is the view's business: the
 * per-view {@link Adapter} receives the new choice and applies it — a
 * backend reload for the Actions tab, a {@code TableRowSorter} for Errors —
 * and a view whose order is fixed by design exposes no sortable column at
 * all, with the header tooltip saying exactly why
 * ({@link Adapter#unsortable}).
 *
 * <p>Nothing here ever attaches a client-side {@code RowSorter}: for a
 * paged model that would mean fetching every page to compare rows, which is
 * the one thing {@link PagedTableModel} exists to avoid.
 *
 * <h2>Where the listeners live</h2>
 *
 * <p>On {@code table.getTableHeader()} — a distinct component from the table
 * body, so this coexists with {@code EntityActions.installRowMenu}'s
 * body-row menu without either seeing the other's events. The installer
 * shape mirrors that method deliberately.
 *
 * <h2>Surviving {@code setModel}</h2>
 *
 * <p>{@code JTable.setModel} discards the whole {@code TableColumnModel} —
 * widths, order and visibility with it — which is why {@code EventsView}
 * grew its private read-before-reapply width preservation for live refresh.
 * That pattern is generalized here: {@link #captureNow()} reads the live
 * columns into the state, and {@link #modelInstalled()} — which the view
 * calls after every {@code setModel} + default sizing — rebinds the fresh
 * columns and reapplies the state over the defaults. Hidden columns keep
 * their remembered widths and positions while hidden; hiding is
 * presentation only, the model never changes shape.
 *
 * <h2>Threading</h2>
 *
 * <p>EDT-only, like the widgets it drives, except persistence: loads and
 * saves run on one shared daemon I/O thread and only the finished
 * {@link ColumnState} value crosses to the EDT. Saves are debounced — a
 * column drag fires a margin event per pixel — and skipped when nothing
 * changed since the last write.
 */
public final class TableHeaderInteractions {

    /** What one view tells this installer about its columns' orderings. */
    public interface Adapter {

        /** Whether {@code columnId} has a real ordering behind it. */
        boolean isSortable(String columnId);

        /**
         * Applies a sort choice: a key and direction, or — empty — the view's
         * default order. Called on the EDT; anything slow behind it (the
         * Actions tab's reload) must leave for its own thread, exactly as the
         * toolbar controls already do.
         */
        void applySort(Optional<String> sortKey, boolean descending);

        /**
         * Why {@code columnId} does not sort — shown as its header tooltip,
         * so the absence of the affordance is explained where the user looks
         * for it.
         */
        String sortUnavailableExplanation(String columnId);

        /** An adapter for a view whose row order is fixed by design. */
        static Adapter unsortable(String explanation) {
            Objects.requireNonNull(explanation, "explanation");
            return new Adapter() {
                @Override
                public boolean isSortable(String columnId) {
                    return false;
                }

                @Override
                public void applySort(Optional<String> sortKey, boolean descending) {
                    // The order is fixed; there is nothing to apply and no
                    // path that calls this.
                }

                @Override
                public String sortUnavailableExplanation(String columnId) {
                    return explanation;
                }
            };
        }
    }

    /** How long a burst of column changes settles before one save is written. */
    private static final int SAVE_DELAY_MILLIS = 500;

    private final JTable table;
    private final Adapter adapter;

    /**
     * Column id → its live {@link TableColumn}, hidden columns included —
     * hidden means removed from the column model, never from here. Rebuilt
     * from the table by {@link #modelInstalled()}.
     */
    private final Map<String, TableColumn> columnsById = new LinkedHashMap<>();

    private final Map<String, Integer> widths = new LinkedHashMap<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private List<String> order = new ArrayList<>();

    /** The sort key, or null for the view's default order. */
    private String sortKey;
    private boolean sortDescending;

    /** True while this class is manipulating the columns it also listens to. */
    private boolean adjusting;

    private ColumnStateStore store;
    private Executor ioExecutor;
    private ColumnState lastSaved = ColumnState.empty();
    private final javax.swing.Timer saveDebounce =
            new javax.swing.Timer(SAVE_DELAY_MILLIS, event -> flushSave());

    private TableHeaderInteractions(JTable table, Adapter adapter) {
        this.table = Objects.requireNonNull(table, "table");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        saveDebounce.setRepeats(false);
    }

    /**
     * Installs the header behaviour on {@code table} and returns the handle
     * the view keeps: it calls {@link #modelInstalled()} after each
     * {@code setModel}, {@link #captureNow()} before one it wants preserved
     * exactly, and {@link #attachPersistence} once at wiring time.
     */
    public static TableHeaderInteractions install(JTable table, Adapter adapter) {
        TableHeaderInteractions interactions = new TableHeaderInteractions(table, adapter);
        interactions.installListeners();
        if (table.getColumnModel().getColumnCount() > 0) {
            interactions.modelInstalled();
        }
        return interactions;
    }

    private void installListeners() {
        JTableHeader header = table.getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && !event.isPopupTrigger()) {
                    int viewColumn = header.columnAtPoint(event.getPoint());
                    if (viewColumn >= 0) {
                        headerClicked(viewColumn);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                maybeShowMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShowMenu(event);
            }

            private void maybeShowMenu(MouseEvent event) {
                if (!event.isPopupTrigger() || !columnsCurrent() || columnsById.isEmpty()) {
                    return;
                }
                JPopupMenu menu = buildHeaderMenu();
                menu.show(header, event.getX(), event.getY());
            }
        });
        // Tooltips are per column, and the header is one component: the text
        // under the pointer is decided as the pointer moves.
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int viewColumn = header.columnAtPoint(event.getPoint());
                header.setToolTipText(PlainText.tooltip(tooltipForColumn(viewColumn)));
            }
        });
        header.setDefaultRenderer(new SortIndicatorRenderer(header.getDefaultRenderer()));
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent event) {
                liveColumnsChanged();
            }

            @Override
            public void columnRemoved(TableColumnModelEvent event) {
                liveColumnsChanged();
            }

            @Override
            public void columnMoved(TableColumnModelEvent event) {
                if (event.getFromIndex() != event.getToIndex()) {
                    liveColumnsChanged();
                }
            }

            @Override
            public void columnMarginChanged(ChangeEvent event) {
                liveColumnsChanged();
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent event) {
                // Selection is not presentation state.
            }
        });
    }

    /**
     * A column change this installer did not make itself: a user's resize or
     * drag-reorder, arriving as column-model events. Captured into the state
     * immediately — this is what lets the next {@code setModel} swap (a sort
     * change's reload, a live refresh) reapply it — and scheduled for a
     * debounced save.
     *
     * <p>{@link #columnsCurrent()} is the guard that keeps {@code setModel}'s
     * own column-rebuild storm out: those events arrive while the column
     * model no longer matches this installer's binding (columns removed, or
     * fresh objects added), and capturing mid-storm would overwrite the
     * user's state with construction defaults.
     */
    private void liveColumnsChanged() {
        if (adjusting || columnsById.isEmpty() || !columnsCurrent()) {
            return;
        }
        captureNow();
        markDirty();
    }

    // ---------------------------------------------------------------- sorting

    /** The 3-state cycle: ascending → descending → default order. */
    private void headerClicked(int viewColumn) {
        String id = idAtView(viewColumn);
        if (id == null || !adapter.isSortable(id)) {
            return;
        }
        if (id.equals(sortKey) && !sortDescending) {
            setSortAndApply(id, true);
        } else if (id.equals(sortKey)) {
            setSortAndApply(null, false);
        } else {
            setSortAndApply(id, false);
        }
    }

    private void setSortAndApply(String key, boolean descending) {
        sortKey = key;
        sortDescending = descending;
        adapter.applySort(Optional.ofNullable(key), descending);
        repaintHeader();
        markDirty();
    }

    /**
     * Adopts a sort choice the view's own controls made — the Actions tab's
     * toolbar combo — without calling the adapter back, so a view syncing
     * both directions cannot loop. The header indicator and the persisted
     * state follow the choice.
     */
    public void setSortFromView(Optional<String> key, boolean descending) {
        String newKey = key.orElse(null);
        if (Objects.equals(newKey, sortKey) && sortDescending == descending) {
            return;
        }
        sortKey = newKey;
        sortDescending = descending;
        repaintHeader();
        markDirty();
    }

    /** The current sort choice, or empty for the default order. */
    public Optional<ColumnState.Sort> currentSort() {
        return Optional.ofNullable(sortKey)
                .map(key -> new ColumnState.Sort(key, sortDescending));
    }

    // ------------------------------------------------------------- the columns

    /**
     * Runs a view's default column sizing without it being captured as the
     * user's doing, then reapplies the remembered state over it. For a view
     * whose table keeps one model for its whole life (Errors), whose default
     * sizing would otherwise arrive as ordinary column events on a live
     * binding and overwrite the remembered widths with the defaults.
     */
    public void installDefaults(Runnable defaults) {
        Objects.requireNonNull(defaults, "defaults");
        adjusting = true;
        try {
            defaults.run();
        } finally {
            adjusting = false;
        }
        modelInstalled();
    }

    /**
     * Rebinds this installer to the table's current columns and reapplies the
     * remembered state over the view's defaults. Call on the EDT after every
     * {@code setModel} + default column sizing; a table whose model was just
     * cleared (zero columns) simply unbinds.
     */
    public void modelInstalled() {
        adjusting = true;
        try {
            if (columnsById.isEmpty() || !columnsCurrent()) {
                // A genuinely new column set (setModel rebuilt it, hidden
                // columns and all). A still-valid binding — a view that only
                // re-ran its default sizing — keeps its columns, hidden ones
                // included, and just gets the state reapplied.
                rebind();
            }
            applyState();
        } finally {
            adjusting = false;
        }
    }

    private void rebind() {
        columnsById.clear();
        TableColumnModel columnModel = table.getColumnModel();
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            TableColumn column = columnModel.getColumn(i);
            String name = String.valueOf(column.getHeaderValue());
            int occurrence = seen.merge(name, 1, Integer::sum);
            // Duplicate names — possible in an ad hoc SQL result — get
            // distinct ids so hiding one cannot ambiguously mean another.
            String id = occurrence == 1 ? name : name + " #" + occurrence;
            column.setIdentifier(id);
            columnsById.put(id, column);
        }
    }

    /** Applies widths, visibility and order onto the bound columns. */
    private void applyState() {
        TableColumnModel columnModel = table.getColumnModel();
        for (Map.Entry<String, TableColumn> entry : columnsById.entrySet()) {
            Integer width = widths.get(entry.getKey());
            if (width != null) {
                // Both, as EventsView's restore always did: sizeColumns-style
                // defaults only ever set preferredWidth and an interactive
                // resize only ever sets width; a restore disturbs neither.
                entry.getValue().setPreferredWidth(width);
                entry.getValue().setWidth(width);
            }
        }
        for (String id : hidden) {
            TableColumn column = columnsById.get(id);
            if (column != null && viewIndexOf(id) >= 0) {
                columnModel.removeColumn(column);
            }
        }
        applyOrder();
    }

    private void applyOrder() {
        List<String> target = targetVisibleOrder();
        for (int to = 0; to < target.size(); to++) {
            int from = viewIndexOf(target.get(to));
            if (from >= 0 && from != to) {
                table.getColumnModel().moveColumn(from, to);
            }
        }
    }

    /** The remembered order filtered to visible columns, unknowns appended. */
    private List<String> targetVisibleOrder() {
        List<String> target = new ArrayList<>();
        for (String id : order) {
            if (!hidden.contains(id) && columnsById.containsKey(id)) {
                target.add(id);
            }
        }
        TableColumnModel columnModel = table.getColumnModel();
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            String id = String.valueOf(columnModel.getColumn(i).getIdentifier());
            if (!target.contains(id)) {
                target.add(id);
            }
        }
        return target;
    }

    /**
     * Reads the live columns — widths and view order — into the state, ahead
     * of something that will discard them. This is {@code EventsView}'s old
     * {@code currentColumnWidths()} read-before-reapply, generalized: hidden
     * columns keep the width and position they already have on record, since
     * the live table cannot say anything about them.
     */
    public void captureNow() {
        if (columnsById.isEmpty() || !columnsCurrent()) {
            return;
        }
        TableColumnModel columnModel = table.getColumnModel();
        // A realized table's truth is the actual width (a user's drag sets
        // only that); an unrealized one's is the preferred width (defaults
        // set only that, and actual is still the construction-time 75).
        boolean realized = table.isDisplayable();
        List<String> visibleOrder = new ArrayList<>();
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            TableColumn column = columnModel.getColumn(i);
            String id = String.valueOf(column.getIdentifier());
            visibleOrder.add(id);
            widths.put(id, realized ? column.getWidth() : column.getPreferredWidth());
        }
        order = mergeHiddenIntoOrder(visibleOrder);
    }

    /** Re-seats each hidden id at the position the previous order gave it. */
    private List<String> mergeHiddenIntoOrder(List<String> visibleOrder) {
        List<String> merged = new ArrayList<>(visibleOrder);
        for (String id : hidden) {
            int remembered = order.indexOf(id);
            int at = remembered < 0 ? merged.size() : Math.min(remembered, merged.size());
            merged.add(at, id);
        }
        return merged;
    }

    /**
     * Shows or hides one column. Presentation only: the column leaves the
     * column model but its {@link TableColumn}, width and position stay on
     * record, and the table model underneath is untouched. The last visible
     * column cannot be hidden — a table with no columns reads as no data.
     */
    public void setColumnVisible(String id, boolean visible) {
        TableColumn column = columnsById.get(id);
        if (column == null) {
            return;
        }
        if (!visible) {
            if (hidden.contains(id) || table.getColumnModel().getColumnCount() <= 1) {
                return;
            }
            captureNow();
            adjusting = true;
            try {
                hidden.add(id);
                table.getColumnModel().removeColumn(column);
            } finally {
                adjusting = false;
            }
        } else {
            if (!hidden.contains(id)) {
                return;
            }
            adjusting = true;
            try {
                hidden.remove(id);
                table.getColumnModel().addColumn(column);
                Integer width = widths.get(id);
                if (width != null) {
                    column.setPreferredWidth(width);
                    column.setWidth(width);
                }
                applyOrder();
            } finally {
                adjusting = false;
            }
        }
        markDirty();
    }

    /** The visible column ids, in view order. */
    public List<String> visibleColumnIds() {
        List<String> ids = new ArrayList<>();
        TableColumnModel columnModel = table.getColumnModel();
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            ids.add(String.valueOf(columnModel.getColumn(i).getIdentifier()));
        }
        return ids;
    }

    // ------------------------------------------------------------ persistence

    /**
     * Points this view's state at {@code settings/columns/<viewId>.json},
     * loading what a previous run saved (on the shared I/O thread, applied on
     * the EDT when it lands) and saving every settled change from then on.
     */
    public void attachPersistence(Path settingsDirectory, String viewId) {
        attachPersistence(new ColumnStateStore(settingsDirectory, viewId), SharedIo.EXECUTOR);
    }

    /** As above with an explicit store and executor; tests pass a direct one. */
    public void attachPersistence(ColumnStateStore newStore, Executor io) {
        this.store = Objects.requireNonNull(newStore, "newStore");
        this.ioExecutor = Objects.requireNonNull(io, "io");
        io.execute(() -> {
            ColumnState loaded = newStore.load();
            SwingUtilities.invokeLater(() -> adopt(loaded));
        });
    }

    /** Installs a loaded state over whatever is currently shown. EDT. */
    private void adopt(ColumnState loaded) {
        lastSaved = loaded;
        if (loaded.isEmpty()) {
            return;
        }
        widths.putAll(loaded.widths());
        if (!loaded.order().isEmpty()) {
            order = new ArrayList<>(loaded.order());
        }
        Set<String> previouslyHidden = new LinkedHashSet<>(hidden);
        hidden.clear();
        hidden.addAll(loaded.hidden());
        sortKey = loaded.sort().map(ColumnState.Sort::key).orElse(null);
        sortDescending = loaded.sort().map(ColumnState.Sort::descending).orElse(false);
        if (!columnsById.isEmpty()) {
            adjusting = true;
            try {
                // Anything hidden before the load landed comes back first, so
                // the loaded state decides visibility from the full set.
                for (String id : previouslyHidden) {
                    TableColumn column = columnsById.get(id);
                    if (column != null && viewIndexOf(id) < 0) {
                        table.getColumnModel().addColumn(column);
                    }
                }
                applyState();
            } finally {
                adjusting = false;
            }
        }
        if (loaded.sort().isPresent()) {
            adapter.applySort(Optional.ofNullable(sortKey), sortDescending);
        }
        repaintHeader();
    }

    /** Schedules a debounced save, when there is anywhere to save to. */
    private void markDirty() {
        if (store == null) {
            return;
        }
        saveDebounce.restart();
    }

    private void flushSave() {
        if (store == null) {
            return;
        }
        captureNow();
        ColumnState snapshot = snapshotState();
        if (snapshot.equals(lastSaved)) {
            return;
        }
        lastSaved = snapshot;
        ColumnStateStore target = store;
        ioExecutor.execute(() -> target.save(snapshot));
    }

    /** The current state as a value — what a save would write. */
    public ColumnState snapshotState() {
        return new ColumnState(order, widths, hidden, currentSort());
    }

    // ----------------------------------------------------------------- the UI

    /** The header context menu, built fresh per popup. Visible for tests. */
    JPopupMenu buildHeaderMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<String> sortable = new ArrayList<>();
        for (String id : columnsById.keySet()) {
            if (adapter.isSortable(id)) {
                sortable.add(id);
            }
        }
        if (!sortable.isEmpty()) {
            JMenu sortMenu = new JMenu("Sort");
            JCheckBoxMenuItem byDefault =
                    new JCheckBoxMenuItem("Default order", sortKey == null);
            byDefault.addActionListener(event -> setSortAndApply(null, false));
            sortMenu.add(PlainText.disableHtml(byDefault));
            sortMenu.addSeparator();
            for (String id : sortable) {
                JCheckBoxMenuItem ascending = new JCheckBoxMenuItem(
                        id + " — ascending", id.equals(sortKey) && !sortDescending);
                ascending.addActionListener(event -> setSortAndApply(id, false));
                sortMenu.add(PlainText.disableHtml(ascending));
                JCheckBoxMenuItem descending = new JCheckBoxMenuItem(
                        id + " — descending", id.equals(sortKey) && sortDescending);
                descending.addActionListener(event -> setSortAndApply(id, true));
                sortMenu.add(PlainText.disableHtml(descending));
            }
            menu.add(sortMenu);
        } else if (!columnsById.isEmpty()) {
            // Honest absence: the menu names why there is no sort submenu
            // rather than silently not having one.
            JMenuItem why = new JMenuItem(adapter.sortUnavailableExplanation(
                    columnsById.keySet().iterator().next()));
            why.setEnabled(false);
            menu.add(PlainText.disableHtml(why));
        }
        menu.addSeparator();
        int visibleCount = columnsById.size() - hidden.size();
        for (String id : columnsById.keySet()) {
            boolean visible = !hidden.contains(id);
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(id, visible);
            // The last visible column stays: unhiding is always allowed,
            // hiding the only column left is not.
            item.setEnabled(!visible || visibleCount > 1);
            item.addActionListener(event -> setColumnVisible(id, !visible));
            menu.add(PlainText.disableHtml(item));
        }
        return menu;
    }

    /** The header tooltip for one view column, or null off any column. */
    String tooltipForColumn(int viewColumn) {
        String id = idAtView(viewColumn);
        if (id == null) {
            return null;
        }
        if (adapter.isSortable(id)) {
            return "Click to sort by " + id + "; click again to reverse; a third click"
                    + " restores the default order. Right-click to choose columns.";
        }
        return adapter.sortUnavailableExplanation(id) + " Right-click to choose columns.";
    }

    private String idAtView(int viewColumn) {
        TableColumnModel columnModel = table.getColumnModel();
        if (viewColumn < 0 || viewColumn >= columnModel.getColumnCount()) {
            return null;
        }
        return String.valueOf(columnModel.getColumn(viewColumn).getIdentifier());
    }

    private int viewIndexOf(String id) {
        TableColumnModel columnModel = table.getColumnModel();
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            if (id.equals(String.valueOf(columnModel.getColumn(i).getIdentifier()))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether the table's columns are still the ones this installer bound.
     * False after a {@code setModel} the view has not yet followed with
     * {@link #modelInstalled()} — most visibly the close-session clear — in
     * which case captures and menus stand down rather than acting on columns
     * that no longer exist.
     */
    private boolean columnsCurrent() {
        TableColumnModel columnModel = table.getColumnModel();
        int expectedVisible = 0;
        for (String id : columnsById.keySet()) {
            if (!hidden.contains(id)) {
                expectedVisible++;
            }
        }
        if (columnModel.getColumnCount() != expectedVisible) {
            return false;
        }
        for (int i = 0; i < columnModel.getColumnCount(); i++) {
            TableColumn column = columnModel.getColumn(i);
            if (columnsById.get(String.valueOf(column.getIdentifier())) != column) {
                return false;
            }
        }
        return true;
    }

    private void repaintHeader() {
        JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.repaint();
        }
    }

    /**
     * Adds the sort arrow to whatever the look and feel's header renderer
     * produced. Set as the header's default renderer — a header property, so
     * it survives {@code setModel} where a per-column renderer would not —
     * and it also overrides the icon a {@code RowSorter}-aware delegate set,
     * so a view sorting client-side does not show two indicators.
     */
    private final class SortIndicatorRenderer implements TableCellRenderer {

        private final TableCellRenderer delegate;

        private SortIndicatorRenderer(TableCellRenderer delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public Component getTableCellRendererComponent(JTable rendered, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component component = delegate.getTableCellRendererComponent(
                    rendered, value, isSelected, hasFocus, row, column);
            if (component instanceof JLabel label) {
                Icon icon = null;
                String id = idAtView(column);
                if (id != null && id.equals(sortKey)) {
                    icon = UIManager.getIcon(sortDescending
                            ? "Table.descendingSortIcon" : "Table.ascendingSortIcon");
                }
                label.setIcon(icon);
                label.setHorizontalTextPosition(SwingConstants.LEADING);
            }
            return component;
        }
    }

    // -------------------------------------------------------- visible for tests

    /** Drives the 3-state cycle as a header click on {@code columnId} would. */
    public void clickForTest(String columnId) {
        int viewColumn = viewIndexOf(columnId);
        if (viewColumn >= 0) {
            headerClicked(viewColumn);
        }
    }

    String tooltipForTest(int viewColumn) {
        return tooltipForColumn(viewColumn);
    }

    /** Visible for tests: whether the view's adapter sorts this column. */
    public boolean isSortableForTest(String columnId) {
        return adapter.isSortable(columnId);
    }

    /** Visible for tests: the adapter's reason a column does not sort. */
    public String explanationForTest(String columnId) {
        return adapter.sortUnavailableExplanation(columnId);
    }

    /** Runs the debounced capture-and-save immediately. */
    public void flushSaveForTest() {
        saveDebounce.stop();
        flushSave();
    }

    /** The one thread every attached store's I/O runs on. */
    private static final class SharedIo {
        static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-column-state");
            thread.setDaemon(true);
            return thread;
        });

        private SharedIo() {}
    }
}
