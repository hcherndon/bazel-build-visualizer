package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.ui.session.ImportProgressPanel;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Point;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Events card: import progress while a file is being read, and the
 * chronological table plus raw inspector once a session is open.
 *
 * <h2>Threads</h2>
 *
 * <p>Two single-threaded executors, each owning one {@link SessionReader} and
 * therefore one database connection:
 *
 * <ul>
 *   <li><b>pages</b> — {@code PagedTableModel}'s fetch executor. Every page of
 *       the table is read here.</li>
 *   <li><b>detail</b> — the inspector's payload reads. Separate so that reading
 *       a multi-megabyte payload back out of the journal cannot stall
 *       scrolling.</li>
 * </ul>
 *
 * <p>The EDT does no I/O at any point. Even the table model's construction is
 * arranged so that its {@code rowCount()} call is a field read: the row source
 * is opened on the page executor, where the counting query runs, and only the
 * finished object crosses to the EDT.
 */
public final class EventsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(EventsView.class);

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_IMPORT = "import";
    private static final String CARD_SESSION = "session";

    /**
     * Pages held in the LRU cache. At {@link EventRowSource#DEFAULT_PAGE_SIZE}
     * rows each this is a bounded few thousand rows in memory whatever the size
     * of the session — project rule 9, never retain all events as objects.
     */
    private static final int CACHE_PAGES = 24;

    /**
     * How often a live capture may rebuild the table's row source, and how
     * often this view's own timer nudges one even if nothing tells it to.
     *
     * <p>Shared with {@code TimelineController}'s and {@code OverviewPanel}'s
     * own two-second intervals, for the same reason: it is short enough that a
     * running build feels current and long enough that ticking it does not
     * become the dominant cost of watching one.
     */
    private static final long LIVE_REFRESH_INTERVAL_MICROS = 2_000_000;

    private final long liveRefreshIntervalMicros;

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel(" ", SwingConstants.CENTER);
    private final ImportProgressPanel progressPanel = new ImportProgressPanel();
    private final EventInspectorPanel inspector = new EventInspectorPanel();
    private final JTable table = new JTable();
    private final JLabel statusLabel = new JLabel(" ");
    private final JScrollPane tableScroll;

    private ExecutorService pageExecutor;
    private ExecutorService detailExecutor;
    /**
     * This view's own clock, independent of whatever else might call
     * {@link #refreshLive}. Mirrors {@code TimelineController}'s ticker and
     * exists for the same reason: a live view driven only by BES progress
     * ticks stalls on a quiet build (a long-running action between progress
     * events), even though the session is still growing underneath it.
     */
    private ScheduledExecutorService ticker;
    private SessionSource source;
    private PagedTableModel<EventRow> tableModel;
    private EventInspectorModel inspectorModel;
    /** The row source currently installed, kept so a live refresh can rebuild over its reader. */
    private EventRowSource rows;
    private Consumer<String> openFailureHandler = message -> { };
    private LongConsumer rowCountListener = count -> { };
    private long lastLiveRefreshMicros;
    private volatile boolean refreshInFlight;

    /** Model row whose id could not be resolved yet because its page was still loading. */
    private int pendingSelectionRow = -1;

    public EventsView() {
        this(LIVE_REFRESH_INTERVAL_MICROS);
    }

    /**
     * @param liveRefreshIntervalMicros the throttle in {@link #refreshLive} and
     *     this view's own ticker's period, both at once — a test's hook to
     *     drive several ticks in a fraction of a second, the same reason
     *     {@code TimelineController} and {@code OverviewPanel} take theirs as
     *     a constructor parameter.
     */
    EventsView(long liveRefreshIntervalMicros) {
        super(new BorderLayout());
        this.liveRefreshIntervalMicros = liveRefreshIntervalMicros;

        JPanel empty = new JPanel(new BorderLayout());
        emptyLabel.setEnabled(false);
        empty.add(emptyLabel, BorderLayout.CENTER);

        // The event table shows identifiers and display strings taken from the
        // stream, so it is exposed exactly as the Phase 3 tables are.
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

        tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(320, 160));
        inspector.setMinimumSize(new Dimension(320, 160));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, inspector);
        split.setResizeWeight(0.6);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(statusLabel, BorderLayout.WEST);

        JPanel session = new JPanel(new BorderLayout());
        session.add(status, BorderLayout.NORTH);
        session.add(split, BorderLayout.CENTER);

        deck.add(empty, CARD_EMPTY);
        deck.add(progressPanel, CARD_IMPORT);
        deck.add(session, CARD_SESSION);
        add(deck, BorderLayout.CENTER);
        showEmpty("No session is open. Use File ▸ Open BEP File… or File ▸ Open Session…");
    }

    /** The progress view the import controller drives. */
    public ImportProgressPanel progressPanel() {
        return progressPanel;
    }

    /** Shows an explanatory message in place of any session. */
    public void showEmpty(String message) {
        emptyLabel.setText(Objects.requireNonNull(message, "message"));
        cards.show(deck, CARD_EMPTY);
    }

    /** Brings the import progress view forward. */
    public void showImportProgress() {
        cards.show(deck, CARD_IMPORT);
    }

    /**
     * Opens {@code newSource} and installs the table and inspector over it.
     * Returns immediately; the counting and probing happen on the page
     * executor and the views appear when they are ready.
     *
     * @param onFailure called on the EDT if the session could not be read
     */
    public void openSession(SessionSource newSource, Consumer<String> onFailure) {
        Objects.requireNonNull(newSource, "newSource");
        Objects.requireNonNull(onFailure, "onFailure");
        closeSession();
        openFailureHandler = onFailure;
        source = newSource;
        pageExecutor = singleThreadExecutor("bbv-events-pages");
        detailExecutor = singleThreadExecutor("bbv-events-detail");
        startTicker();
        showEmpty("Opening " + newSource.info().root() + "…");
        ExecutorService opening = pageExecutor;
        opening.execute(() -> {
            try {
                SessionReader pageReader = newSource.openReader();
                SessionReader detailReader = newSource.openReader();
                EventRowSource builtRows =
                        EventRowSource.open(pageReader, EventRowSource.DEFAULT_PAGE_SIZE);
                SwingUtilities.invokeLater(() -> install(newSource, detailReader, builtRows));
            } catch (RuntimeException failure) {
                log.error("could not open session {}", newSource.info().root(), failure);
                SwingUtilities.invokeLater(() -> onFailure.accept(failure.toString()));
            }
        });
    }

    /**
     * Starts this view's own clock, ticking at {@link #liveRefreshIntervalMicros}
     * for as long as this session is open. Every tick just calls
     * {@link #refreshLive} back on the EDT — the same call a live capture's
     * progress tick can make — so a quiet stretch of a build with no progress
     * event still gets picked up, and the shared throttle inside
     * {@link #refreshLive} is what keeps two nearly-simultaneous callers from
     * doing the work twice.
     */
    private void startTicker() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-events-ticker");
            thread.setDaemon(true);
            return thread;
        });
        ticker = scheduler;
        long periodMillis = Math.max(1, liveRefreshIntervalMicros / 1_000);
        scheduler.scheduleWithFixedDelay(() -> SwingUtilities.invokeLater(this::refreshLive),
                periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Releases this view's hold on the session and stops its executors.
     *
     * <p>The teardown itself — waiting for in-flight fetches to stop — happens
     * on a separate thread. Doing it inline would put a bounded-but-real wait
     * plus file I/O on the EDT, which is the one thing this view exists to
     * avoid.
     *
     * <p>It does <em>not</em> close the {@link SessionSource}. Several views
     * share one source, and the first to be torn down closing it would leave
     * the others querying a closed database. The window that opened the source
     * closes it, once, after every view has let go; the source closes the
     * readers it handed out.
     */
    public void closeSession() {
        pendingSelectionRow = -1;
        inspectorModel = null;
        tableModel = null;
        rows = null;
        table.setModel(new javax.swing.table.DefaultTableModel());
        inspector.show(EventInspection.none());
        ExecutorService pages = pageExecutor;
        ExecutorService details = detailExecutor;
        ScheduledExecutorService tick = ticker;
        source = null;
        pageExecutor = null;
        detailExecutor = null;
        ticker = null;
        if (tick != null) {
            tick.shutdownNow();
        }
        if (pages == null && details == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            shutdown(pages);
            shutdown(details);
        }, "bbv-session-close");
        closer.setDaemon(true);
        closer.start();
    }

    /**
     * Shows one event's raw bytes, whatever the table is scrolled to.
     *
     * <p>This is the far end of "event-to-domain provenance is inspectable":
     * an entity view hands over the event id its row came from, and the raw
     * payload appears here. The table's selection is deliberately left alone —
     * finding the row would mean mapping an id back to a position, which under
     * a filter or a sort is a different question from the one the user asked,
     * and getting it wrong would scroll them somewhere misleading.
     *
     * @return false when no session is open, so the caller can say so rather
     *     than switching to a blank card
     */
    public boolean revealEvent(long eventId) {
        if (inspectorModel == null) {
            return false;
        }
        inspectorModel.select(eventId);
        return true;
    }

    /** The session currently open, or null. */
    public SessionSource openSession() {
        return source;
    }

    // The two accessors below exist so the wiring between the table model, the
    // selection and the inspector can be tested headlessly. They are
    // package-private: the untested alternative was to trust that the chain
    // from "a page arrives" to "the inspector shows that row" works.

    /** Visible for testing: the installed table model, or null before one is. */
    PagedTableModel<EventRow> tableModelForTest() {
        return tableModel;
    }

    /** Visible for testing: the installed inspector model, or null before one is. */
    EventInspectorModel inspectorModelForTest() {
        return inspectorModel;
    }

    /** Visible for testing: the table itself, for selection and scroll assertions. */
    JTable tableForTest() {
        return table;
    }

    /** Visible for testing: the table's scroll pane, for scroll-position assertions. */
    JScrollPane scrollForTest() {
        return tableScroll;
    }

    /** Visible for testing: the status line's text (rule 11's "at least ... so far" wording). */
    String statusTextForTest() {
        return statusLabel.getText();
    }

    // ------------------------------------------------------------------ EDT

    private void install(SessionSource opened, SessionReader detailReader, EventRowSource rows) {
        if (source != opened) {
            // A newer open superseded this one while it was being prepared.
            detailReader.close();
            return;
        }
        try {
            buildViews(opened, detailReader, rows);
        } catch (RuntimeException failure) {
            // The most likely one is a session with more rows than JTable's
            // int-based geometry can address, which PagedTableModel refuses
            // rather than clamping. Refusing visibly beats a blank card.
            log.error("could not build the events view over {}", opened.info().root(), failure);
            detailReader.close();
            openFailureHandler.accept(failure.toString());
        }
    }

    private void buildViews(SessionSource opened, SessionReader detailReader, EventRowSource rows) {
        this.rows = rows;
        tableModel = new PagedTableModel<>(
                rows, EventTableColumns.columns(), pageExecutor, rows.pageSize(), CACHE_PAGES);
        tableModel.addTableModelListener(this::rowsUpdated);
        table.setModel(tableModel);
        sizeColumns();
        inspectorModel = new EventInspectorModel(
                detailReader, detailExecutor, SwingUtilities::invokeLater);
        inspectorModel.addListener(inspector::show);
        inspector.show(EventInspection.none());
        statusLabel.setText(describe(opened.info(), rows));
        cards.show(deck, CARD_SESSION);
        rowCountListener.accept(rows.rowCount());
        if (rows.rowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    /**
     * Rebuilds the row source and swaps in a fresh table model if the session
     * has grown since the last refresh — the Events-tab analogue of
     * {@code TimelineController.refreshLive}, and called the same two ways:
     * from this view's own {@link #startTicker() ticker}, and optionally by
     * whatever else is watching a live capture (today, nothing else calls it,
     * but the throttle below is shared-safe if that changes).
     *
     * <h2>Rebuild-and-swap, chosen over growing the model in place</h2>
     *
     * <p>{@link EventRowSource#rowCount()} is captured once at
     * {@link EventRowSource#open}; {@link EventRowIndex} decides
     * {@code DENSE} vs {@code SPARSE_ANCHORS} from three queries at open and
     * never re-queries; {@link PagedTableModel} stores its row count in a
     * {@code private final int} with no growth API, precisely so that
     * {@link PagedTableModel#getRowCount} stays the non-blocking field read
     * the EDT is allowed to do. Giving any of the three a way to grow in
     * place would mean turning that field read into something that has to
     * account for a concurrent writer — the one thing {@code PagedTableModel}
     * exists to avoid.
     *
     * <p>{@code TimelineController} already makes the opposite trade for the
     * same reason: build the next version off the EDT, then swap the
     * finished object in on it. This view now makes the same trade rather
     * than inventing a second way for a view to stay live. The swap does
     * lose whatever pages were cached — the visible cells briefly show
     * {@link PagedTableModel#PLACEHOLDER} again until they refetch — which is
     * an acceptable cost at a several-second cadence, and one this method
     * pays only when the row count or the row-index mode actually changed,
     * never on an idle tick.
     *
     * <h2>Selection, scroll, and column widths, preserved explicitly</h2>
     *
     * <p>{@code JTable.setModel} clears the current selection unconditionally
     * and, because {@code autoCreateColumnsFromModel} is never disabled,
     * discards the whole {@code TableColumnModel} along with it -- it
     * delivers a structural {@code TableModelEvent} and {@code JTable} treats
     * that as "the whole table changed". Losing the row a user is reading,
     * the scroll position they navigated to, and a column they widened to
     * read a long event id, on every tick of a background timer, would make
     * the table nearly unusable to actually read during a build. BEP events
     * are only ever appended, never reordered or deleted, so a row index or a
     * pixel offset valid before the swap still names the same event and the
     * same place in the table afterwards, and column widths do not depend on
     * row content at all; {@link #swapRows} reapplies all three once the new
     * model is installed.
     */
    public void refreshLive() {
        if (pageExecutor == null || source == null || rows == null) {
            return;
        }
        long now = System.currentTimeMillis() * 1_000L;
        if (now - lastLiveRefreshMicros < liveRefreshIntervalMicros) {
            return;
        }
        // One refresh at a time. Without this a session whose ticks outpace
        // the rebuild queues them up and the page executor never catches up.
        if (refreshInFlight) {
            return;
        }
        lastLiveRefreshMicros = now;
        refreshInFlight = true;
        SessionSource opened = source;
        SessionReader reader = rows.reader();
        int pageSize = rows.pageSize();
        ExecutorService executor = pageExecutor;
        executor.execute(() -> {
            EventRowSource freshRows;
            try {
                freshRows = EventRowSource.open(reader, pageSize);
            } catch (RuntimeException failure) {
                log.debug("live events refresh failed", failure);
                return;
            } finally {
                refreshInFlight = false;
            }
            SwingUtilities.invokeLater(() -> {
                if (source != opened) {
                    // Superseded while this refresh was running.
                    return;
                }
                if (freshRows.rowCount() == rows.rowCount()
                        && freshRows.rowIndexMode() == rows.rowIndexMode()) {
                    return; // nothing new since the last refresh
                }
                swapRows(opened, freshRows);
            });
        });
    }

    /**
     * Installs {@code freshRows} in place of the current row source,
     * preserving the table's selection, scroll position, and column widths
     * across the swap. See {@link #refreshLive} for why the swap happens at
     * all and why that loses all three without this.
     */
    private void swapRows(SessionSource opened, EventRowSource freshRows) {
        int viewRow = table.getSelectedRow();
        int modelRowToReselect = viewRow >= 0 ? table.convertRowIndexToModel(viewRow) : -1;
        Point viewPosition = tableScroll.getViewport().getViewPosition();
        int[] columnWidths = currentColumnWidths();

        rows = freshRows;
        tableModel = new PagedTableModel<>(freshRows, EventTableColumns.columns(), pageExecutor,
                freshRows.pageSize(), CACHE_PAGES);
        tableModel.addTableModelListener(this::rowsUpdated);
        table.setModel(tableModel);
        restoreColumnWidths(columnWidths);
        statusLabel.setText(describe(opened.info(), freshRows));
        rowCountListener.accept(freshRows.rowCount());

        if (modelRowToReselect >= 0 && modelRowToReselect < tableModel.getRowCount()) {
            table.setRowSelectionInterval(modelRowToReselect, modelRowToReselect);
        }
        tableScroll.getViewport().setViewPosition(viewPosition);
    }

    /**
     * The current column widths, in view order, captured just before a live
     * refresh replaces the table's model. See {@link #restoreColumnWidths}
     * for why this is captured at all.
     */
    private int[] currentColumnWidths() {
        int count = table.getColumnCount();
        int[] widths = new int[count];
        for (int column = 0; column < count; column++) {
            widths[column] = table.getColumnModel().getColumn(column).getWidth();
        }
        return widths;
    }

    /**
     * Reapplies widths captured by {@link #currentColumnWidths}, in place of
     * {@link #sizeColumns}'s hardcoded defaults.
     *
     * <h2>Why this instead of {@link #sizeColumns}</h2>
     *
     * <p>{@code EventsView} never disables {@code autoCreateColumnsFromModel},
     * so every {@code JTable.setModel} call -- including the one a live
     * refresh makes every couple of seconds for the length of a build --
     * discards the existing {@code TableColumnModel} and builds a fresh one
     * from scratch, each column back at its default width. Calling
     * {@link #sizeColumns} there, as {@link #swapRows} used to, would
     * silently snap a column the user had resized back to its hardcoded
     * default on the very next tick (rule 12: never silently override) --
     * so this reads the previous widths back first instead, the same "read
     * before, reapply after" treatment already given to selection and scroll
     * a few lines above. Both {@code width} and {@code preferredWidth} are
     * restored because {@link #sizeColumns} only ever sets the latter and a
     * user's interactive resize only ever sets the former; a live refresh
     * should disturb neither kind of sizing.
     */
    private void restoreColumnWidths(int[] widths) {
        int count = Math.min(widths.length, table.getColumnCount());
        for (int column = 0; column < count; column++) {
            javax.swing.table.TableColumn tableColumn = table.getColumnModel().getColumn(column);
            tableColumn.setPreferredWidth(widths[column]);
            tableColumn.setWidth(widths[column]);
        }
    }

    /**
     * Called on the EDT with the number of events actually in the database once
     * a session is open. The manifest's count is absent for a session that
     * never finished importing, and this is where the real one comes from.
     */
    public void setRowCountListener(LongConsumer listener) {
        rowCountListener = Objects.requireNonNull(listener, "listener");
    }

    private void rowsUpdated(TableModelEvent event) {
        if (pendingSelectionRow < 0 || tableModel == null) {
            return;
        }
        if (event.getType() == TableModelEvent.UPDATE
                && pendingSelectionRow >= event.getFirstRow()
                && pendingSelectionRow <= event.getLastRow()) {
            selectionChanged();
        }
    }

    private void selectionChanged() {
        if (tableModel == null || inspectorModel == null) {
            return;
        }
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            pendingSelectionRow = -1;
            inspectorModel.clearSelection();
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        Object value = tableModel.getValueAt(modelRow, EventTableColumns.ID_COLUMN);
        if (value instanceof Long id) {
            pendingSelectionRow = -1;
            inspectorModel.select(id);
        } else {
            // The page holding this row has not arrived. Remember the row and
            // resolve it when the model reports the page in; asking the store
            // for "the id at row N" from the EDT would be I/O on the EDT.
            pendingSelectionRow = modelRow;
        }
    }

    private void sizeColumns() {
        int[] widths = {80, 90, 170, 120, 320, 80, 90, 190, 190};
        for (int column = 0; column < table.getColumnCount() && column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
    }

    private static String describe(SessionInfo info, EventRowSource rows) {
        StringBuilder text = new StringBuilder();
        // Rule 11: while a capture is live the row count is a lower bound, not
        // a total, and this is the one place in the view that states a total
        // count out loud — so this is the one place that has to say which.
        boolean stillCapturing = !info.state().isTerminal();
        if (stillCapturing) {
            text.append("at least ");
        }
        text.append(EventValueFormat.count(rows.rowCount())).append(" events");
        if (stillCapturing) {
            text.append(" (still capturing)");
        }
        text.append("  ·  state ").append(info.state());
        text.append("  ·  row index ").append(rows.rowIndexMode());
        List<SessionInfo.SourceInfo> sources = info.sources();
        if (!sources.isEmpty()) {
            text.append("  ·  source ").append(sources.getFirst().completeness());
        }
        if (info.isPartial()) {
            text.append("  ·  this capture is partial; events after the damage are absent");
        }
        return text.toString();
    }

    private static ExecutorService singleThreadExecutor(String name) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    private static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("a session executor did not stop within 2 s; its connection will be"
                        + " closed underneath it");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
