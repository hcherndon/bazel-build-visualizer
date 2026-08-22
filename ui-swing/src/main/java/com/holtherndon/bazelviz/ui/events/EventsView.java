package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.ui.session.ImportProgressPanel;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel(" ", SwingConstants.CENTER);
    private final ImportProgressPanel progressPanel = new ImportProgressPanel();
    private final EventInspectorPanel inspector = new EventInspectorPanel();
    private final JTable table = new JTable();
    private final JLabel statusLabel = new JLabel(" ");

    private ExecutorService pageExecutor;
    private ExecutorService detailExecutor;
    private SessionSource source;
    private PagedTableModel<EventRow> tableModel;
    private EventInspectorModel inspectorModel;
    private Consumer<String> openFailureHandler = message -> { };
    private LongConsumer rowCountListener = count -> { };

    /** Model row whose id could not be resolved yet because its page was still loading. */
    private int pendingSelectionRow = -1;

    public EventsView() {
        super(new BorderLayout());

        JPanel empty = new JPanel(new BorderLayout());
        emptyLabel.setEnabled(false);
        empty.add(emptyLabel, BorderLayout.CENTER);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectionChanged();
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
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
        showEmpty("Opening " + newSource.info().root() + "…");
        ExecutorService opening = pageExecutor;
        opening.execute(() -> {
            try {
                SessionReader pageReader = newSource.openReader();
                SessionReader detailReader = newSource.openReader();
                EventRowSource rows =
                        EventRowSource.open(pageReader, EventRowSource.DEFAULT_PAGE_SIZE);
                SwingUtilities.invokeLater(() -> install(newSource, detailReader, rows));
            } catch (RuntimeException failure) {
                log.error("could not open session {}", newSource.info().root(), failure);
                SwingUtilities.invokeLater(() -> onFailure.accept(failure.toString()));
            }
        });
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
        table.setModel(new javax.swing.table.DefaultTableModel());
        inspector.show(EventInspection.none());
        ExecutorService pages = pageExecutor;
        ExecutorService details = detailExecutor;
        source = null;
        pageExecutor = null;
        detailExecutor = null;
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
        text.append(EventValueFormat.count(rows.rowCount())).append(" events");
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
