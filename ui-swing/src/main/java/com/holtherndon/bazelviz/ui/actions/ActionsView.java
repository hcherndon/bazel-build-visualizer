package com.holtherndon.bazelviz.ui.actions;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Actions card: every observed action, filtered, sorted and paged.
 *
 * <h2>What the status line has to say</h2>
 *
 * <p>Two facts that a table alone cannot carry, and that make the difference
 * between a number and a misleading number:
 *
 * <ul>
 *   <li>When a filter is on, the filtered count <em>and</em> the total. A table
 *       showing 12 rows above the number 12 is indistinguishable from a build
 *       that ran 12 actions.</li>
 *   <li>When the capture did not pass
 *       {@code --build_event_publish_all_actions}, that the table holds
 *       failures only. Bazel publishes an action event for a successful action
 *       only under that flag, so an imported BEP usually has almost none — and
 *       an empty table with no explanation reads as a build that did no work
 *       (finding 22).</li>
 * </ul>
 *
 * <h2>Threads</h2>
 *
 * <p>Two single-threaded executors, each owning one {@link EntityReader}: one
 * behind the table's pages and the anchor index, one behind the inspector. The
 * EDT does no I/O, including when the sort changes — rebuilding the anchor
 * index is a scan, and it happens on the page executor with the table showing
 * its previous contents until the new source is ready.
 */
public final class ActionsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ActionsView.class);

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABLE = "table";

    /** Pages held in the LRU cache; a bounded few thousand rows in memory. */
    private static final int CACHE_PAGES = 24;

    /** Any mnemonic. The combo's first entry. */
    private static final String ANY_MNEMONIC = "All mnemonics";

    private static final String ANY_OUTCOME = "All outcomes";

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JTable table = new JTable();
    private final InspectorPanel inspector = new InspectorPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel captureNote = new JLabel(" ");

    private final JComboBox<String> mnemonicChoice = new JComboBox<>();
    private final JComboBox<String> outcomeChoice = new JComboBox<>();
    private final JTextField textFilter = new JTextField(18);
    private final JComboBox<ActionSort> sortChoice = new JComboBox<>(ActionSort.values());
    private final JCheckBox descendingBox = new JCheckBox("Descending");

    private ExecutorService pageExecutor;
    private ExecutorService detailExecutor;
    private SessionSource source;
    private EntityReader detailReader;

    /**
     * The reader the current table's pages are read through.
     *
     * <p>It belongs to the source, not to the reload that built it: an
     * {@link ActionRowSource} holds it and uses it for every page it will ever
     * serve. Closing it at the end of the reload — which this used to do —
     * failed every subsequent page fetch, and the table rendered the error
     * placeholder rather than rows.
     */
    private EntityReader pageReader;
    private PagedTableModel<ActionRow> tableModel;
    private ActionRowSource rowSource;
    private LongConsumer showEventHandler = eventId -> { };

    /** Bumped on every reload so a slow one cannot overwrite a newer one. */
    private long reloadGeneration;

    /**
     * Coalesces the keystrokes in the filter box.
     *
     * <p>Each reload rescans the table to build its anchors — 61 ms at a
     * million actions — so a five-letter search issued a letter at a time is
     * five scans, four of which are discarded by the generation check after
     * they have already run. The timer restarts on every keystroke and fires
     * once when the typing stops.
     */
    private final javax.swing.Timer filterDebounce = new javax.swing.Timer(
            250, event -> reload());

    /**
     * True while the toolbar is being populated rather than used.
     *
     * <p>Filling the mnemonic combo fires its own action listener, so without
     * this, opening a session starts a reload for every item added.
     */
    private boolean populating;

    public ActionsView() {
        super(new BorderLayout());

        emptyLabel.setEnabled(false);
        JPanel empty = new JPanel(new BorderLayout());
        empty.add(emptyLabel, BorderLayout.CENTER);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectionChanged();
            }
        });

        inspector.onShowSourceEvent(eventId -> showEventHandler.accept(eventId));

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new Dimension(320, 160));
        inspector.setMinimumSize(new Dimension(300, 160));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, inspector);
        split.setResizeWeight(0.68);

        JPanel status = new JPanel(new BorderLayout(12, 0));
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(statusLabel, BorderLayout.WEST);
        captureNote.setEnabled(false);
        status.add(captureNote, BorderLayout.CENTER);

        JPanel session = new JPanel(new BorderLayout());
        session.add(buildToolbar(), BorderLayout.NORTH);
        session.add(split, BorderLayout.CENTER);
        session.add(status, BorderLayout.SOUTH);

        deck.add(empty, CARD_EMPTY);
        deck.add(session, CARD_TABLE);
        add(deck, BorderLayout.CENTER);
        showEmpty("No session is open.");
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        mnemonicChoice.addItem(ANY_MNEMONIC);
        outcomeChoice.addItem(ANY_OUTCOME);
        for (ActionOutcome outcome : ActionOutcome.values()) {
            outcomeChoice.addItem(outcome.name());
        }
        sortChoice.setSelectedItem(ActionSort.ARRIVAL);
        textFilter.setToolTipText("Substring of the primary output path");

        mnemonicChoice.addActionListener(event -> reloadUnlessPopulating());
        outcomeChoice.addActionListener(event -> reloadUnlessPopulating());
        sortChoice.addActionListener(event -> reloadUnlessPopulating());
        descendingBox.addActionListener(event -> reloadUnlessPopulating());
        filterDebounce.setRepeats(false);
        textFilter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                filterDebounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filterDebounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filterDebounce.restart();
            }
        });

        bar.add(mnemonicChoice);
        bar.add(outcomeChoice);
        bar.add(new JLabel("Output contains:"));
        bar.add(textFilter);
        bar.add(new JLabel("Sort:"));
        bar.add(sortChoice);
        bar.add(descendingBox);
        return bar;
    }

    /** Called on the EDT with an event id when the user asks to see the source. */
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
        pageExecutor = singleThreadExecutor("bbv-actions-pages");
        detailExecutor = singleThreadExecutor("bbv-actions-detail");
        showEmpty("Reading actions…");
        ExecutorService opening = detailExecutor;
        opening.execute(() -> {
            try {
                EntityReader reader = newSource.openEntityReader();
                List<String> mnemonics = new ArrayList<>();
                reader.mnemonics().forEach(count ->
                        mnemonics.add(count.mnemonic() + "  (" + EntityFormat.count(count.actions()) + ")"));
                boolean failuresOnly = reader.overview().actionsAreFailuresOnly();
                SwingUtilities.invokeLater(() -> {
                    if (source != newSource) {
                        reader.close();
                        return;
                    }
                    detailReader = reader;
                    populateMnemonics(mnemonics);
                    // Worded about the flag rather than about the table: what
                    // is known is what Bazel was asked to publish, not whether
                    // a particular row is missing. "Only failures appear here"
                    // would be a claim about the rows, and a stream captured
                    // some other way can contradict it.
                    captureNote.setText(failuresOnly
                            ? "--build_event_publish_all_actions was not in effect; Bazel"
                                    + " publishes an event for a successful action only under"
                                    + " that flag, so successful actions may be missing."
                            : " ");
                    reload();
                });
            } catch (RuntimeException failure) {
                log.error("could not open the actions view", failure);
                SwingUtilities.invokeLater(() -> showEmpty(failure.getMessage()));
            }
        });
    }

    /** Closes the session and stops the executors, off the EDT. */
    public void closeSession() {
        filterDebounce.stop();
        tableModel = null;
        rowSource = null;
        table.setModel(new DefaultTableModel());
        inspector.show(Inspection.NONE);
        SessionSource closing = source;
        ExecutorService pages = pageExecutor;
        ExecutorService details = detailExecutor;
        EntityReader detail = detailReader;
        EntityReader page = pageReader;
        source = null;
        pageExecutor = null;
        detailExecutor = null;
        detailReader = null;
        pageReader = null;
        if (closing == null && pages == null && details == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            shutdown(pages);
            shutdown(details);
            if (detail != null) {
                detail.close();
            }
            if (page != null) {
                page.close();
            }
        }, "bbv-actions-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Visible for testing: the installed model, or null before one is. */
    public PagedTableModel<ActionRow> tableModelForTest() {
        return tableModel;
    }

    /** Visible for testing: the source behind the current table. */
    public ActionRowSource rowSourceForTest() {
        return rowSource;
    }

    /** Visible for testing: what the status line says. */
    public String statusForTest() {
        return statusLabel.getText();
    }

    /** Visible for testing: the note about what the capture published. */
    public String captureNoteForTest() {
        return captureNote.getText();
    }

    /** Visible for testing: drives the toolbar as a user would. */
    public void applyForTest(String mnemonic, ActionSort sort, boolean descending) {
        populating = true;
        try {
            mnemonicChoice.setSelectedItem(mnemonic);
            sortChoice.setSelectedItem(sort);
            descendingBox.setSelected(descending);
        } finally {
            populating = false;
        }
        reload();
    }

    /** Visible for testing: the mnemonic entries the filter offers. */
    public List<String> mnemonicChoicesForTest() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < mnemonicChoice.getItemCount(); i++) {
            items.add(mnemonicChoice.getItemAt(i));
        }
        return items;
    }

    // ------------------------------------------------------------------ EDT

    /**
     * Rebuilds the table for the current filter and sort.
     *
     * <p>Every change goes through here, including a keystroke in the text
     * field, and each one starts a generation. A reload that finishes after a
     * newer one started is discarded rather than installed — without that, a
     * slow index scan for "fo" would land after a fast one for "foo" and the
     * table would disagree with the box above it.
     */
    private void reload() {
        ExecutorService pages = pageExecutor;
        SessionSource opened = source;
        if (pages == null || opened == null) {
            return;
        }
        ActionFilter filter = currentFilter();
        ActionSort sort = (ActionSort) sortChoice.getSelectedItem();
        boolean descending = descendingBox.isSelected();
        long generation = ++reloadGeneration;
        statusLabel.setText("Reading…");

        pages.execute(() -> {
            try {
                EntityReader reader = opened.openEntityReader();
                try {
                    ActionRowSource built = ActionRowSource.open(
                            reader, filter, sort, descending, ActionRowSource.DEFAULT_PAGE_SIZE);
                    SwingUtilities.invokeLater(() -> {
                        if (generation != reloadGeneration || source != opened) {
                            reader.close();
                            return;
                        }
                        install(built, reader);
                    });
                } catch (RuntimeException failure) {
                    reader.close();
                    throw failure;
                }
            } catch (RuntimeException failure) {
                log.error("could not read actions", failure);
                SwingUtilities.invokeLater(() -> {
                    if (generation == reloadGeneration) {
                        showEmpty(failure.getMessage());
                    }
                });
            }
        });
    }

    private void install(ActionRowSource built, EntityReader reader) {
        // The previous table's reader is finished with; this one's is not.
        EntityReader previous = pageReader;
        pageReader = reader;
        rowSource = built;
        tableModel = new PagedTableModel<>(
                built, ActionTableColumns.columns(), pageExecutor, built.pageSize(), CACHE_PAGES);
        // A row selected before its page arrived cannot be described yet. The
        // page landing fires a row update, and this re-runs the handler --
        // otherwise clicking a row during a fast scroll leaves the inspector
        // showing the row before it, with no sign that it is stale.
        tableModel.addTableModelListener(event -> {
            if (table.getSelectedRow() >= 0 && inspector.displayed().isEmpty()) {
                selectionChanged();
            }
        });
        table.setModel(tableModel);
        sizeColumns();
        inspector.show(Inspection.NONE);
        statusLabel.setText(describe(built));
        cards.show(deck, CARD_TABLE);
        if (previous != null) {
            // Queued behind any page fetch already scheduled against it, on the
            // one thread that touches these readers.
            pageExecutor.execute(previous::close);
        }
    }

    private String describe(ActionRowSource built) {
        long shown = built.rowCount();
        long total = built.unfilteredCount();
        if (built.filter().isEmpty()) {
            return EntityFormat.count(shown) + (shown == 1 ? " action" : " actions");
        }
        return EntityFormat.count(shown) + " of " + EntityFormat.count(total)
                + " actions match the filter";
    }

    private ActionFilter currentFilter() {
        Optional<String> mnemonic = Optional.empty();
        Object selected = mnemonicChoice.getSelectedItem();
        if (selected != null && !ANY_MNEMONIC.equals(selected)) {
            // The combo shows "CppCompile  (1,204)"; the filter wants the name.
            String text = selected.toString();
            int gap = text.indexOf("  (");
            mnemonic = Optional.of(gap < 0 ? text : text.substring(0, gap));
        }
        Optional<ActionOutcome> outcome = Optional.empty();
        Object chosenOutcome = outcomeChoice.getSelectedItem();
        if (chosenOutcome != null && !ANY_OUTCOME.equals(chosenOutcome)) {
            outcome = Optional.of(ActionOutcome.valueOf(chosenOutcome.toString()));
        }
        String text = textFilter.getText().trim();
        return new ActionFilter(
                mnemonic,
                outcome,
                Optional.empty(),
                text.isEmpty() ? Optional.empty() : Optional.of(text));
    }

    private void populateMnemonics(List<String> mnemonics) {
        populating = true;
        try {
            mnemonicChoice.removeAllItems();
            mnemonicChoice.addItem(ANY_MNEMONIC);
            mnemonics.forEach(mnemonicChoice::addItem);
        } finally {
            populating = false;
        }
    }

    private void reloadUnlessPopulating() {
        if (!populating) {
            reload();
        }
    }

    private void selectionChanged() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || tableModel == null) {
            inspector.show(Inspection.NONE);
            return;
        }
        ActionRow row = tableModel.rowAt(viewRow);
        if (row == null) {
            // The page is still loading. The inspector stays as it was rather
            // than flashing empty; the next page arrival re-fires selection.
            return;
        }
        inspector.show(ActionInspection.of(row));
    }

    private void sizeColumns() {
        int[] widths = ActionTableColumns.widths();
        for (int i = 0; i < widths.length && i < table.getColumnModel().getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(widths[i]);
        }
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
