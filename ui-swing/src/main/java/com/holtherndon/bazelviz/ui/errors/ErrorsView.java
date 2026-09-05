package com.holtherndon.bazelviz.ui.errors;

import com.holtherndon.bazelviz.storage.entities.ErrorQueries;
import com.holtherndon.bazelviz.storage.entities.ErrorRow;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.ui.capture.ConsoleTextPane;
import com.holtherndon.bazelviz.ui.events.RawPayloadRenderer;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileResolver;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.table.TableHeaderInteractions;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Errors card: what broke, what Bazel said about it, and what merely did not get built.
 *
 * <h2>Why "Errors" and not "Failures"</h2>
 *
 * <p>Because {@link ErrorRow.Kind#OUTPUT} rows are here. Those carry whatever Bazel wrote to
 * stderr, which for most builds is the only diagnostic there is — and a compiler warning printed on
 * the way to a successful action is not a failure. The card was named for two of its four kinds and
 * showed all four.
 *
 * <h2>Aborted targets are summarized, not listed</h2>
 *
 * <p>A failed action and a failed target are things that went wrong. An aborted target usually is
 * not: under {@code --nokeep_going} a single broken target aborts every sibling, and an interrupt
 * during analysis was measured producing twelve thousand aborts. So the aborts are summarized by
 * reason and listed only on request (finding 51). Interleaving them with the real failures would
 * bury the one row the user opened this view to find.
 *
 * <h2>Nothing is capped silently</h2>
 *
 * <p>Rows load a page at a time and the status line always says how many of how many are shown. A
 * view that displayed the first five hundred and said nothing would be indistinguishable from a
 * build with five hundred failures.
 *
 * <h2>Console text is read on selection, off the EDT</h2>
 *
 * <p>An {@link ErrorRow.Kind#OUTPUT} row has no message column: the diagnostic is {@code
 * progress.stderr}, which stays in the journal (ADR-004). Selecting one starts a journal read on
 * this view's executor and shows the text when it lands — the same read-on-selection shape {@code
 * EventInspectorModel} uses, and for the same two reasons: the payload can be hundreds of
 * kilobytes, and {@code JTable} fires selection events far more often than a user changes their
 * mind, so a superseded read is dropped rather than rendered late.
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
   * <p>A cap, and one the status line accounts for: a long build writes thousands of progress
   * events and listing every one would bury the failures. The ones that matter are few, because
   * only the events carrying stderr are indexed at all.
   */
  private static final int OUTPUT_EVENTS = 200;

  /**
   * What the Message column says for a row whose text is in the journal.
   *
   * <p>Not an em dash. The column stays size-only by design — bulk text lives in the journal, not
   * in a table cell (ADR-004) — but "—" claims the message is unknown, and it is not: it is
   * elsewhere, and one click away.
   */
  static final String MESSAGE_IN_JOURNAL = "text in journal — select to view";

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel(" ");
  private final ErrorTableModel tableModel = new ErrorTableModel();
  private final JTable table = new JTable(tableModel);

  /**
   * Client-side sorting over the in-memory rows — legitimate here and only here among the entity
   * tables, because every loaded row is already in memory. Every column is {@code
   * setSortable(false)} so the L&amp;F's own header-click toggle (a two-state asc/desc cycle with
   * no way back to the load order) stays out of the way; {@link #headerInteractions} drives {@code
   * setSortKeys} through the shared 3-state cycle instead, whose third state is the deliberate
   * kind-priority load order.
   */
  private final TableRowSorter<ErrorTableModel> sorter = new TableRowSorter<>(tableModel);

  private final TableHeaderInteractions headerInteractions;
  private final InspectorPanel inspector = new InspectorPanel();
  private final ErrorConsolePanel console = new ErrorConsolePanel();
  private final JLabel statusLabel = new JLabel(" ");
  private final JLabel abortSummary = new JLabel(" ");
  private final JButton loadMore = new JButton("Load more");
  private final JButton listAborts = new JButton("List targets that were not built");

  private ExecutorService executor;
  private EntityReader reader;

  /**
   * The journal reader behind the console rows, bound to {@link #executor}'s one thread exactly as
   * {@link EntityReader} is.
   */
  private SessionReader payloadReader;

  /**
   * Why {@link #payloadReader} is null, when it is null for a reason other than the session being
   * closed. Kept so a console row can say what went wrong rather than the whole card refusing to
   * open over it.
   */
  private String payloadFailure;

  private SessionSource source;
  private LongConsumer showEventHandler = eventId -> {};
  private EntityReader.ErrorCounts counts = new EntityReader.ErrorCounts(0, 0, 0);
  private boolean abortsListed;

  /** Bumped by every selection; a console read whose turn has passed is dropped. */
  private final AtomicLong selection = new AtomicLong();

  public ErrorsView() {
    super(new BorderLayout());

    PlainText.install(table);
    PlainText.disableHtml(statusLabel);
    PlainText.disableHtml(abortSummary);
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
    for (int column = 0; column < tableModel.getColumnCount(); column++) {
      sorter.setSortable(column, false);
    }
    table.setRowSorter(sorter);
    headerInteractions =
        TableHeaderInteractions.install(
            table,
            new TableHeaderInteractions.Adapter() {
              @Override
              public boolean isSortable(String columnId) {
                return ErrorTableModel.columnIndexOf(columnId) >= 0;
              }

              @Override
              public void applySort(Optional<String> sortKey, boolean descending) {
                applyRowSort(sortKey, descending);
              }

              @Override
              public String sortUnavailableExplanation(String columnId) {
                // Unreached: every column here sorts. Kept honest for
                // a column this model does not know.
                return "This column is not one this table can sort.";
              }
            });
    inspector.onShowSourceEvent(eventId -> showEventHandler.accept(eventId));

    loadMore.setVisible(false);
    loadMore.addActionListener(event -> loadNextPage());
    listAborts.setVisible(false);
    listAborts.addActionListener(
        event -> {
          abortsListed = true;
          listAborts.setVisible(false);
          loadNextPage();
        });

    JScrollPane scroll = new JScrollPane(table);
    scroll.setMinimumSize(new Dimension(320, 160));
    inspector.setMinimumSize(new Dimension(300, 120));
    JPanel details = new JPanel(new GridBagLayout());
    GridBagConstraints detail = new GridBagConstraints();
    detail.gridx = 0;
    detail.weightx = 1;
    detail.fill = GridBagConstraints.BOTH;
    detail.gridy = 0;
    detail.weighty = 0.4;
    details.add(inspector, detail);
    detail.gridy = 1;
    detail.weighty = 0.6;
    details.add(console, detail);
    JSplitPane split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new SectionPane("Errors", scroll),
            new SectionPane("Error details", details));
    split.setResizeWeight(0.55);

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

    deck.add(emptyState, CARD_EMPTY);
    deck.add(session, CARD_TABLE);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
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

  List<EntityRef> refsAtRow(int modelRow) {
    if (modelRow < 0 || modelRow >= tableModel.getRowCount()) {
      return List.of();
    }
    ErrorRow row = tableModel.rowAt(modelRow);
    List<EntityRef> refs = new ArrayList<>();
    if (row.kind() == ErrorRow.Kind.ACTION) {
      refs.add(new EntityRef.ActionId(row.id()));
    }
    WorkspaceFileResolver.mainRepositoryLabel(row.subject())
        .ifPresent(label -> refs.add(new EntityRef.TargetLabel(label)));
    row.bepEventId().ifPresent(eventId -> refs.add(new EntityRef.EventId(eventId)));
    return refs;
  }

  /**
   * The shared 3-state cycle landing on the client-side sorter: a column and direction, or — empty,
   * the third click — no sort keys at all, which is the deliberate kind-priority load order the
   * pages arrived in.
   */
  private void applyRowSort(Optional<String> sortKey, boolean descending) {
    if (sortKey.isEmpty()) {
      sorter.setSortKeys(null);
      return;
    }
    int column = ErrorTableModel.columnIndexOf(sortKey.get());
    if (column < 0) {
      // A persisted key from some other shape of this table: the
      // default order, not an exception over a preference file.
      sorter.setSortKeys(null);
      return;
    }
    sorter.setSortKeys(
        List.of(
            new RowSorter.SortKey(
                column, descending ? SortOrder.DESCENDING : SortOrder.ASCENDING)));
  }

  /**
   * Persists this table's column state (widths, visibility, order, sort) under the application
   * settings directory. Called once at wiring time; the load and every save run on the store's I/O
   * thread, never the EDT.
   */
  public void attachColumnState(Path settingsDirectory) {
    headerInteractions.attachPersistence(settingsDirectory, "errors");
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

  /** Visible for testing: the sorter the header cycle drives. */
  TableRowSorter<?> sorterForTest() {
    return sorter;
  }

  public void showEmpty(String message) {
    emptyState.setText(Objects.requireNonNull(message, "message"));
    cards.show(deck, CARD_EMPTY);
  }

  /** Opens a session and loads the first page. Returns immediately. */
  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    source = newSource;
    abortsListed = false;
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-errors");
              thread.setDaemon(true);
              return thread;
            });
    showEmpty("Reading errors…");
    ExecutorService opening = executor;
    opening.execute(
        () -> {
          try {
            EntityReader opened = newSource.openEntityReader();
            // The journal reader is opened here, on the thread that will
            // use it, because a SessionReader is bound to one thread. It is
            // opened whether or not this session has console rows: finding
            // that out is itself a query, and one connection is cheaper
            // than deferring and re-deciding on the EDT.
            //
            // Its own failure is caught separately and kept as a sentence.
            // Failing to reach the journal costs the console rows their
            // text; it does not cost the card its failures, and refusing to
            // open over it would hide the rows that did read.
            SessionReader payloads = null;
            String payloadsFailed = null;
            try {
              payloads = newSource.openReader();
            } catch (RuntimeException failure) {
              log.warn("could not open a journal reader for the Errors card", failure);
              payloadsFailed = describe(failure);
            }
            EntityReader.ErrorCounts read = opened.errorCounts();
            List<ErrorQueries.ReasonCount> reasons =
                read.aborted() > 0 ? opened.abortReasons() : List.of();
            SessionReader openedPayloads = payloads;
            String openFailure = payloadsFailed;
            SwingUtilities.invokeLater(
                () -> {
                  if (source != newSource) {
                    opened.close();
                    if (openedPayloads != null) {
                      openedPayloads.close();
                    }
                    return;
                  }
                  reader = opened;
                  payloadReader = openedPayloads;
                  payloadFailure = openFailure;
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
    closeSessionAsync();
  }

  /** Detaches immediately and completes after this session's error reads have stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    tableModel.clear();
    inspector.show(Inspection.NONE);
    console.clear();
    loadMore.setVisible(false);
    listAborts.setVisible(false);
    selection.incrementAndGet();
    ExecutorService stopping = executor;
    EntityReader closing = reader;
    SessionReader closingPayloads = payloadReader;
    source = null;
    executor = null;
    reader = null;
    payloadReader = null;
    payloadFailure = null;
    if (stopping == null && closing == null && closingPayloads == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-errors-close",
        () -> {
          if (stopping != null) {
            stopping.shutdownNow();
            try {
              stopping.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
          if (closing != null) {
            closing.close();
          }
          if (closingPayloads != null) {
            closingPayloads.close();
          }
        });
  }

  /** Visible for testing. */
  String statusForTest() {
    return statusLabel.getText();
  }

  /** Visible for testing. */
  int rowCountForTest() {
    return tableModel.getRowCount();
  }

  /** Visible for testing: what the inspector is showing right now. */
  Inspection inspectionForTest() {
    return inspector.displayed();
  }

  /** Visible for testing: one Message cell, as the table renders it. */
  String messageCellForTest(int row) {
    return String.valueOf(tableModel.getValueAt(row, 3));
  }

  /** Visible for testing: selects a row the way a click would. */
  void selectForTest(int row) {
    table.setRowSelectionInterval(row, row);
  }

  /** Visible for testing: rendered terminal text after ANSI interpretation. */
  String consoleTextForTest(String stream) {
    return console.textForTest(stream);
  }

  /** Visible for testing: styled terminal document after ANSI interpretation. */
  ConsoleTextPane consolePaneForTest(String stream) {
    return console.textPaneForTest(stream);
  }

  /** Visible for testing: the "Load more" button, without the button. */
  void loadMoreForTest() {
    loadNextPage();
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
      StringBuilder text =
          new StringBuilder(EntityFormat.count(counts.aborted()))
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
    // The defaults go on without being captured as the user's doing —
    // this table keeps one model for its whole life, so its sizing would
    // otherwise arrive as ordinary column events — and anything the user
    // arranged or a previous run persisted goes back on top.
    headerInteractions.installDefaults(this::sizeColumns);
    loadNextPage();
  }

  /**
   * Loads the next page, in kind order: real failures first, then aborts if the user asked for
   * them.
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
    OptionalLong after =
        last != null && last.kind() == kind ? OptionalLong.of(last.id()) : OptionalLong.empty();
    running.execute(
        () -> {
          try {
            List<ErrorRow> rows =
                switch (kind) {
                  case ACTION -> current.failedActions(after, PAGE);
                  case TARGET -> current.failedTargets(after, PAGE);
                  case OUTPUT -> outputRows(current);
                  case NOT_BUILT -> current.abortedTargets(after, PAGE);
                };
            SwingUtilities.invokeLater(
                () -> {
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
   * <p>Read once and in full: there are a handful of these on a failing build and they are the only
   * diagnostic most failures have. The rows carry the byte counts and the journal address of the
   * bytes, not the bytes: copying the text into every row would duplicate the largest thing in the
   * stream (ADR-004). The address is what makes the row openable — selecting it reads that one
   * payload and shows the stderr inside it.
   */
  private static List<ErrorRow> outputRows(EntityReader reader) {
    List<ErrorRow> rows = new ArrayList<>();
    for (ErrorQueries.ProgressRef ref : reader.progressOutputEvents(OUTPUT_EVENTS)) {
      rows.add(
          new ErrorRow(
              ErrorRow.Kind.OUTPUT,
              ref.bepEventId(),
              "console output at event " + ref.sequence(),
              Optional.of(EntityFormat.count(ref.stderrBytes()) + " bytes on stderr"),
              Optional.empty(),
              OptionalLong.of(ref.bepEventId()),
              Optional.of(ref.rawLocation())));
    }
    return rows;
  }

  /** Which kind the next page comes from, given what is already loaded. */
  private ErrorRow.Kind nextKind(ErrorRow last) {
    if (last == null) {
      return counts.failedActions() > 0
          ? ErrorRow.Kind.ACTION
          : counts.failedTargets() > 0 ? ErrorRow.Kind.TARGET : ErrorRow.Kind.OUTPUT;
    }
    long loadedOfKind = tableModel.countOf(last.kind());
    return switch (last.kind()) {
      case ACTION ->
          loadedOfKind < counts.failedActions()
              ? ErrorRow.Kind.ACTION
              : counts.failedTargets() > 0 ? ErrorRow.Kind.TARGET : ErrorRow.Kind.OUTPUT;
      case TARGET ->
          loadedOfKind < counts.failedTargets() ? ErrorRow.Kind.TARGET : ErrorRow.Kind.OUTPUT;
      // Output rows arrive in one batch, so the next kind after them is
      // always the aborts.
      case OUTPUT -> ErrorRow.Kind.NOT_BUILT;
      case NOT_BUILT -> ErrorRow.Kind.NOT_BUILT;
    };
  }

  private void updateStatus() {
    long shown = tableModel.getRowCount();
    long available =
        counts.failedActions()
            + counts.failedTargets()
            + tableModel.countOf(ErrorRow.Kind.OUTPUT)
            + (abortsListed ? counts.aborted() : 0);
    statusLabel.setText(
        EntityFormat.count(shown)
            + " of "
            + EntityFormat.count(available)
            + " shown  ·  "
            + EntityFormat.count(counts.failedActions())
            + " failed action(s), "
            + EntityFormat.count(counts.failedTargets())
            + " failed target(s), "
            + EntityFormat.count(counts.aborted())
            + " not built");
    loadMore.setVisible(shown < available);
  }

  private void selectionChanged() {
    int row = table.getSelectedRow();
    long mine = selection.incrementAndGet();
    if (row < 0) {
      inspector.show(Inspection.NONE);
      console.clear();
      return;
    }
    // View to model: with a sort active the row on screen is not the row
    // in load order, and inspecting the wrong error would be worse than
    // no sort at all.
    ErrorRow selected = tableModel.rowAt(table.convertRowIndexToModel(row));
    Optional<RawLocation> location = selected.rawLocation();
    if (location.isEmpty()) {
      // Everything but a console row: its text is in the row already, so
      // there is nothing to read and nothing to wait for.
      inspector.show(ErrorInspection.of(selected));
      console.clear();
      return;
    }
    ExecutorService running = executor;
    SessionReader reading = payloadReader;
    if (running == null || reading == null) {
      inspector.show(
          ErrorInspection.of(
              selected,
              ErrorInspection.Console.unavailable(
                  payloadFailure != null
                      ? "this session's journal could not be opened: " + payloadFailure
                      : "this session is no longer open for reading")));
      console.clear();
      return;
    }
    inspector.show(ErrorInspection.of(selected, ErrorInspection.Console.reading()));
    console.clear();
    running.execute(
        () -> {
          if (selection.get() != mine) {
            // Superseded before the read started. A journal seek for a row
            // the user has already left is a cost with no reader.
            return;
          }
          ConsoleRead read = readConsole(reading, location.get());
          SwingUtilities.invokeLater(
              () -> {
                if (selection.get() != mine) {
                  return;
                }
                inspector.show(ErrorInspection.of(selected, read.state()));
                console.show(read.content());
              });
        });
  }

  /**
   * Reads one console row's payload back and decodes it. Runs on the view's executor; never throws.
   *
   * <p>Every failure becomes a stated absence rather than a dialog. A redacted session keeps its
   * database and drops its {@code raw/} directory, so a journal read there fails by design — and a
   * modal error for a row the user merely clicked would turn a session that is working as intended
   * into something that looks broken.
   */
  private static ConsoleRead readConsole(SessionReader reader, RawLocation location) {
    try {
      RawPayload payload = reader.rawPayload(location);
      RawPayloadRenderer.Console console = RawPayloadRenderer.console(payload);
      if (console.absence().isPresent()) {
        return ConsoleRead.unavailable(console.absence().orElseThrow());
      }
      return new ConsoleRead(
          ErrorInspection.Console.text(console.stderr(), console.stdout()),
          ErrorConsolePanel.parse(console.stderr(), console.stdout()));
    } catch (RuntimeException failure) {
      log.warn("could not read the console output at {}", location, failure);
      return ConsoleRead.unavailable(
          "the bytes could not be read back from this session's journal: " + describe(failure));
    }
  }

  private record ConsoleRead(ErrorInspection.Console state, ErrorConsolePanel.Content content) {
    private ConsoleRead {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(content, "content");
    }

    private static ConsoleRead unavailable(String reason) {
      return new ConsoleRead(
          ErrorInspection.Console.unavailable(reason), ErrorConsolePanel.parse("", ""));
    }
  }

  /** A failure and its causes, in one line each, with no stack trace. */
  private static String describe(Throwable failure) {
    StringBuilder text =
        new StringBuilder(failure.getMessage() != null ? failure.getMessage() : failure.toString());
    Throwable cause = failure.getCause();
    while (cause != null) {
      text.append("; caused by ")
          .append(cause.getMessage() != null ? cause.getMessage() : cause.toString());
      cause = cause.getCause();
    }
    return text.toString();
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

    /** The model index of a column name, or -1 for a name not here. */
    static int columnIndexOf(String columnId) {
      for (int i = 0; i < COLUMNS.length; i++) {
        if (COLUMNS[i].equals(columnId)) {
          return i;
        }
      }
      return -1;
    }

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
        default -> messageCell(row);
      };
    }

    /**
     * The Message cell: the text when the row has it, a pointer when the row knows where it is, and
     * an em dash only when neither is true.
     */
    private static String messageCell(ErrorRow row) {
      if (row.message().filter(text -> !text.isEmpty()).isPresent()) {
        return row.message().orElseThrow();
      }
      return row.rawLocation().isPresent() ? MESSAGE_IN_JOURNAL : EntityFormat.UNKNOWN;
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
