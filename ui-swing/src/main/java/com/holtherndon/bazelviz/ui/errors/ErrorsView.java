package com.holtherndon.bazelviz.ui.errors;

import com.holtherndon.bazelviz.storage.entities.ErrorQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Errors card: what broke, what Bazel said about it, and what merely did
 * not get built.
 *
 * <h2>Why "Errors" and not "Failures"</h2>
 *
 * <p>Because {@link ErrorRow.Kind#OUTPUT} rows are here. Those carry whatever
 * Bazel wrote to stderr, which for most builds is the only diagnostic there is
 * — and a compiler warning printed on the way to a successful action is not a
 * failure. The card was named for two of its four kinds and showed all four.
 *
 * <h2>Aborted targets are summarized, not listed</h2>
 *
 * <p>A failed action and a failed target are things that went wrong. An aborted
 * target usually is not: under {@code --nokeep_going} a single broken target
 * aborts every sibling, and an interrupt during analysis was measured producing
 * twelve thousand aborts. So the aborts are summarized by reason and listed
 * only on request (finding 51). Interleaving them with the real failures would
 * bury the one row the user opened this view to find.
 *
 * <h2>Nothing is capped silently</h2>
 *
 * <p>Rows load a page at a time and the status line always says how many of how
 * many are shown. A view that displayed the first five hundred and said nothing
 * would be indistinguishable from a build with five hundred failures.
 */
public final class ErrorsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ErrorsView.class);

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TABLE = "table";

    /** Rows added per load. */
    private static final int PAGE = 500;

    /**
     * Console-output rows shown.
     *
     * <p>A cap, and one the status line accounts for: a long build writes
     * thousands of progress events and listing every one would bury the
     * failures. The ones that matter are few, because only the events carrying
     * stderr are indexed at all.
     */
    private static final int OUTPUT_EVENTS = 200;

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel(" ", SwingConstants.CENTER);
    private final ErrorTableModel tableModel = new ErrorTableModel();
    private final JTable table = new JTable(tableModel);
    private final InspectorPanel inspector = new InspectorPanel();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel abortSummary = new JLabel(" ");
    private final JButton loadMore = new JButton("Load more");
    private final JButton listAborts = new JButton("List targets that were not built");

    private ExecutorService executor;
    private EntityReader reader;
    private SessionSource source;
    private LongConsumer showEventHandler = eventId -> { };
    private EntityReader.ErrorCounts counts = new EntityReader.ErrorCounts(0, 0, 0);
    private boolean abortsListed;

    public ErrorsView() {
        super(new BorderLayout());

        emptyLabel.setEnabled(false);
        JPanel empty = new JPanel(new BorderLayout());
        empty.add(emptyLabel, BorderLayout.CENTER);

        PlainText.install(table);
        PlainText.disableHtml(statusLabel);
        PlainText.disableHtml(abortSummary);
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

        loadMore.setVisible(false);
        loadMore.addActionListener(event -> loadNextPage());
        listAborts.setVisible(false);
        listAborts.addActionListener(event -> {
            abortsListed = true;
            listAborts.setVisible(false);
            loadNextPage();
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setMinimumSize(new Dimension(320, 160));
        inspector.setMinimumSize(new Dimension(300, 160));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, inspector);
        split.setResizeWeight(0.62);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        controls.add(abortSummary);
        controls.add(listAborts);
        controls.add(loadMore);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(statusLabel, BorderLayout.WEST);

        JPanel session = new JPanel(new BorderLayout());
        session.add(controls, BorderLayout.NORTH);
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

    /** Opens a session and loads the first page. Returns immediately. */
    public void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        closeSession();
        source = newSource;
        abortsListed = false;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-errors");
            thread.setDaemon(true);
            return thread;
        });
        showEmpty("Reading errors…");
        ExecutorService opening = executor;
        opening.execute(() -> {
            try {
                EntityReader opened = newSource.openEntityReader();
                EntityReader.ErrorCounts read = opened.errorCounts();
                List<ErrorQueries.ReasonCount> reasons =
                        read.aborted() > 0 ? opened.abortReasons() : List.of();
                SwingUtilities.invokeLater(() -> {
                    if (source != newSource) {
                        opened.close();
                        return;
                    }
                    reader = opened;
                    counts = read;
                    installCounts(reasons);
                });
            } catch (RuntimeException failure) {
                log.error("could not read errors", failure);
                SwingUtilities.invokeLater(() -> showEmpty(failure.getMessage()));
            }
        });
    }

    public void closeSession() {
        tableModel.clear();
        inspector.show(Inspection.NONE);
        loadMore.setVisible(false);
        listAborts.setVisible(false);
        ExecutorService stopping = executor;
        EntityReader closing = reader;
        source = null;
        executor = null;
        reader = null;
        if (stopping == null) {
            return;
        }
        Thread closer = new Thread(() -> {
            stopping.shutdownNow();
            try {
                stopping.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (closing != null) {
                closing.close();
            }
        }, "bbv-errors-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Visible for testing. */
    String statusForTest() {
        return statusLabel.getText();
    }

    /** Visible for testing. */
    int rowCountForTest() {
        return tableModel.getRowCount();
    }

    private void installCounts(List<ErrorQueries.ReasonCount> reasons) {
        if (counts.isEmpty()) {
            // A statement about this session, not about the build. Aborted
            // events arrive after buildFinished, so a stream that stopped early
            // has no failures recorded whether or not the build had any.
            showEmpty("This session recorded no errors and no skipped targets.");
            return;
        }
        // When aborts are all there is, listing them is the only thing to
        // show. Leaving the button up would offer to load rows already on
        // screen, and the "shown of available" arithmetic would count them as
        // unavailable while displaying them.
        abortsListed = counts.failedActions() == 0 && counts.failedTargets() == 0;
        if (counts.aborted() > 0) {
            // "Abort events", not "targets": patterns and other id kinds abort
            // too and name no target, so the two counts differ.
            StringBuilder text = new StringBuilder(EntityFormat.count(counts.aborted()))
                    .append(" abort event(s) recorded");
            if (!reasons.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (ErrorQueries.ReasonCount reason : reasons) {
                    parts.add(reason.reason() + " ×" + reason.events());
                }
                text.append(": ").append(String.join(", ", parts));
            }
            abortSummary.setText(text.toString());
            listAborts.setVisible(!abortsListed);
        } else {
            abortSummary.setText(" ");
        }
        cards.show(deck, CARD_TABLE);
        sizeColumns();
        loadNextPage();
    }

    /**
     * Loads the next page, in kind order: real failures first, then aborts if
     * the user asked for them.
     */
    private void loadNextPage() {
        ExecutorService running = executor;
        EntityReader current = reader;
        if (running == null || current == null) {
            return;
        }
        loadMore.setEnabled(false);
        ErrorRow last = tableModel.lastRow();
        ErrorRow.Kind kind = nextKind(last);
        OptionalLong after = last != null && last.kind() == kind
                ? OptionalLong.of(last.id())
                : OptionalLong.empty();
        running.execute(() -> {
            try {
                List<ErrorRow> rows = switch (kind) {
                    case ACTION -> current.failedActions(after, PAGE);
                    case TARGET -> current.failedTargets(after, PAGE);
                    case OUTPUT -> outputRows(current);
                    case NOT_BUILT -> current.abortedTargets(after, PAGE);
                };
                SwingUtilities.invokeLater(() -> {
                    tableModel.append(rows);
                    updateStatus();
                    loadMore.setEnabled(true);
                });
            } catch (RuntimeException failure) {
                log.warn("could not read a page of errors", failure);
                SwingUtilities.invokeLater(() -> loadMore.setEnabled(true));
            }
        });
    }

    /**
     * Bazel's console error output, as rows.
     *
     * <p>Read once and in full: there are a handful of these on a failing build
     * and they are the only diagnostic most failures have. The rows carry the
     * byte counts; the text is reached through the inspector's source-event
     * button, because the bytes live in the journal and copying them here would
     * duplicate the largest thing in the stream.
     */
    private static List<ErrorRow> outputRows(EntityReader reader) {
        List<ErrorRow> rows = new ArrayList<>();
        for (ErrorQueries.ProgressRef ref : reader.progressOutputEvents(OUTPUT_EVENTS)) {
            rows.add(new ErrorRow(
                    ErrorRow.Kind.OUTPUT,
                    ref.bepEventId(),
                    "console output at event " + ref.sequence(),
                    Optional.of(EntityFormat.count(ref.stderrBytes()) + " bytes on stderr"),
                    Optional.empty(),
                    OptionalLong.of(ref.bepEventId())));
        }
        return rows;
    }

    /** Which kind the next page comes from, given what is already loaded. */
    private ErrorRow.Kind nextKind(ErrorRow last) {
        if (last == null) {
            return counts.failedActions() > 0 ? ErrorRow.Kind.ACTION
                    : counts.failedTargets() > 0 ? ErrorRow.Kind.TARGET
                    : ErrorRow.Kind.OUTPUT;
        }
        long loadedOfKind = tableModel.countOf(last.kind());
        return switch (last.kind()) {
            case ACTION -> loadedOfKind < counts.failedActions()
                    ? ErrorRow.Kind.ACTION
                    : counts.failedTargets() > 0 ? ErrorRow.Kind.TARGET : ErrorRow.Kind.OUTPUT;
            case TARGET -> loadedOfKind < counts.failedTargets()
                    ? ErrorRow.Kind.TARGET
                    : ErrorRow.Kind.OUTPUT;
            // Output rows arrive in one batch, so the next kind after them is
            // always the aborts.
            case OUTPUT -> ErrorRow.Kind.NOT_BUILT;
            case NOT_BUILT -> ErrorRow.Kind.NOT_BUILT;
        };
    }

    private void updateStatus() {
        long shown = tableModel.getRowCount();
        long available = counts.failedActions() + counts.failedTargets()
                + tableModel.countOf(ErrorRow.Kind.OUTPUT)
                + (abortsListed ? counts.aborted() : 0);
        statusLabel.setText(EntityFormat.count(shown) + " of " + EntityFormat.count(available)
                + " shown  ·  " + EntityFormat.count(counts.failedActions()) + " failed action(s), "
                + EntityFormat.count(counts.failedTargets()) + " failed target(s), "
                + EntityFormat.count(counts.aborted()) + " not built");
        loadMore.setVisible(shown < available);
    }

    private void selectionChanged() {
        int row = table.getSelectedRow();
        if (row < 0) {
            inspector.show(Inspection.NONE);
            return;
        }
        inspector.show(ErrorInspection.of(tableModel.rowAt(row)));
    }

    private void sizeColumns() {
        int[] widths = {140, 420, 200, 520};
        for (int i = 0; i < widths.length && i < table.getColumnModel().getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            column.setPreferredWidth(widths[i]);
        }
    }

    /** A plain accumulating model: the rows here are bounded by what is loaded. */
    private static final class ErrorTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Kind", "Subject", "Detail", "Message"};

        private final List<ErrorRow> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ErrorRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.kind().title();
                case 1 -> row.subject();
                case 2 -> EntityFormat.text(row.detail());
                default -> EntityFormat.text(row.message());
            };
        }

        void append(List<ErrorRow> more) {
            if (more.isEmpty()) {
                return;
            }
            int from = rows.size();
            rows.addAll(more);
            fireTableRowsInserted(from, rows.size() - 1);
        }

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        ErrorRow rowAt(int index) {
            return rows.get(index);
        }

        ErrorRow lastRow() {
            return rows.isEmpty() ? null : rows.getLast();
        }

        long countOf(ErrorRow.Kind kind) {
            return rows.stream().filter(row -> row.kind() == kind).count();
        }
    }
}
