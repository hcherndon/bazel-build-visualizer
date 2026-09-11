package com.holtherndon.bazelviz.ui.repro;

import com.holtherndon.bazelviz.core.repro.ReproComparison;
import com.holtherndon.bazelviz.ui.filter.FilterBuilder;
import com.holtherndon.bazelviz.ui.filter.FilterField;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;

/** Paged, service-backed reproducibility evidence. All public view methods belong on the EDT. */
public final class HermeticityView extends JPanel implements PageChrome {
  private static final long serialVersionUID = 1L;

  private final JButton chooseA = new JButton("Choose A…");
  private final JButton chooseB = new JButton("Choose B…");
  private final JButton compare = new JButton("Compare");
  private final JButton cancel = new JButton("Cancel");
  private final JButton openA = new JButton("Open run A");
  private final JButton openB = new JButton("Open run B");
  private final JButton openBuild = new JButton("Open BUILD file…");
  private final JButton firstPage = new JButton("First page");
  private final JButton nextPage = new JButton("Next page");
  private final JButton previousDetails = new JButton("Previous changes");
  private final JButton nextDetails = new JButton("Next changes");
  private final JTextField sourceA = sourceField("Execution log A");
  private final JTextField sourceB = sourceField("Execution log B");
  private final JTextArea summary = WrappingLabel.create("Choose two execution logs to compare.");
  private final JTextArea coverage = WrappingLabel.create("");
  private final JTextArea status = WrappingLabel.create("No comparison is open.");
  private final JTextArea rowReason =
      WrappingLabel.create("Select an action to inspect its changes.");
  private final JTextArea selectedValue =
      WrappingLabel.create("Select a change cell to read and copy its full value.");
  private final JLabel pageStatus = PlainText.disableHtml(new JLabel("No rows loaded"));
  private final JLabel detailStatus = PlainText.disableHtml(new JLabel("No action selected"));
  private final RowsModel rows = new RowsModel();
  private final ChangesModel changes = new ChangesModel();
  private final JTable actions = new JTable(rows);
  private final JTable details = new JTable(changes);
  private final JTabbedPane tabs = new JTabbedPane();
  private final JPanel localHeader = new JPanel(new BorderLayout());
  private final JPanel sources = new JPanel(new GridBagLayout());
  private final FilterBuilder filters = new FilterBuilder(filterFields());
  private PageToolbar toolbar;
  private Runnable onChooseA = () -> {};
  private Runnable onChooseB = () -> {};
  private Runnable onCompare = () -> {};
  private Runnable onOpenA;
  private Runnable onOpenB;
  private Consumer<String> onOpenBuild;
  private ComparisonController controller;
  private CompletableFuture<Void> draining = CompletableFuture.completedFuture(null);
  private ReproComparison.Summary resultSummary;
  private List<String> contextNotes = List.of();
  private long generation;
  private long pageOffset;
  private long pageTotal;
  private long detailOffset;
  private long detailTotal;
  private boolean busy;
  private boolean ready;
  private boolean fetchingPage;
  private boolean fetchingDetails;
  private boolean disposed;

  public HermeticityView() {
    super(new BorderLayout(0, 6));
    setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    configure(actions, false);
    configure(details, true);
    actions.setName("hermeticity.actions");
    details.setName("hermeticity.changes");
    tabs.setName("hermeticity.tabs");
    summary.setName("hermeticity.summary");
    coverage.setName("hermeticity.coverage");
    selectedValue.setName("hermeticity.value");
    status.setName("hermeticity.status");
    for (int column = 0; column < actions.getColumnCount(); column++) {
      actions
          .getColumnModel()
          .getColumn(column)
          .setPreferredWidth(column == 1 || column == 3 ? 340 : 150);
    }
    for (int column = 0; column < details.getColumnCount(); column++) {
      details.getColumnModel().getColumn(column).setPreferredWidth(column == 0 ? 110 : 280);
    }
    JPanel localActions = flow();
    for (JButton button : List.of(chooseA, chooseB, compare, cancel)) localActions.add(button);
    sourceRow(0, "Run A", sourceA);
    sourceRow(1, "Run B", sourceB);
    localHeader.add(localActions, BorderLayout.NORTH);
    localHeader.add(sources, BorderLayout.CENTER);
    add(localHeader, BorderLayout.NORTH);

    tabs.addTab("Summary", scrollText(summary, "Repeat-build evidence"));
    tabs.addTab("Action differences", differencesPanel());
    JPanel coveragePanel = new JPanel(new BorderLayout(0, 6));
    JPanel runActions = flow();
    runActions.add(openA);
    runActions.add(openB);
    coveragePanel.add(runActions, BorderLayout.NORTH);
    coveragePanel.add(scrollText(coverage, "Coverage and interpretation"), BorderLayout.CENTER);
    tabs.addTab("Coverage & runs", coveragePanel);
    add(tabs, BorderLayout.CENTER);
    add(status, BorderLayout.SOUTH);

    chooseA.addActionListener(event -> onChooseA.run());
    chooseB.addActionListener(event -> onChooseB.run());
    compare.addActionListener(event -> onCompare.run());
    cancel.addActionListener(event -> cancelComparison());
    openA.addActionListener(
        event -> {
          if (onOpenA != null) onOpenA.run();
        });
    openB.addActionListener(
        event -> {
          if (onOpenB != null) onOpenB.run();
        });
    openBuild.addActionListener(
        event -> {
          ReproComparison.Row row = selectedRow();
          if (row != null && onOpenBuild != null) onOpenBuild.accept(row.target());
        });
    firstPage.addActionListener(event -> requestPage(0, 0));
    nextPage.addActionListener(
        event -> {
          if (!rows.values.isEmpty())
            requestPage(rows.values.getLast().id(), pageOffset + rows.values.size());
        });
    previousDetails.addActionListener(event -> requestDetails(Math.max(0, detailOffset - 100)));
    nextDetails.addActionListener(event -> requestDetails(detailOffset + changes.values.size()));
    filters.onChange(
        ignored -> {
          if (ready) requestPage(0, 0);
        });
    actions
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting() && !fetchingPage) requestDetails(0);
            });
    details.getSelectionModel().addListSelectionListener(event -> showSelectedValue());
    details
        .getColumnModel()
        .getSelectionModel()
        .addListSelectionListener(event -> showSelectedValue());
    refreshCoverage();
    updateControls();
  }

  private JScrollPane differencesPanel() {
    JPanel list = new JPanel(new BorderLayout(0, 4));
    JPanel paging = flow();
    paging.add(firstPage);
    paging.add(nextPage);
    paging.add(pageStatus);
    list.add(filters, BorderLayout.NORTH);
    list.add(new JScrollPane(actions), BorderLayout.CENTER);
    list.add(paging, BorderLayout.SOUTH);

    JPanel inspector = new JPanel(new BorderLayout(0, 5));
    JPanel title = new JPanel(new BorderLayout(6, 0));
    title.add(rowReason, BorderLayout.CENTER);
    title.add(openBuild, BorderLayout.EAST);
    inspector.add(title, BorderLayout.NORTH);
    JPanel fields = new JPanel(new BorderLayout(0, 4));
    fields.add(new JScrollPane(details), BorderLayout.CENTER);
    JPanel detailPaging = flow();
    detailPaging.add(previousDetails);
    detailPaging.add(nextDetails);
    detailPaging.add(detailStatus);
    fields.add(detailPaging, BorderLayout.SOUTH);
    JScrollPane valueScroll = new JScrollPane(selectedValue);
    valueScroll.setPreferredSize(new Dimension(0, 90));
    JSplitPane inspectionSplit =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            fields,
            new SectionPane("Selected value · select text to copy", valueScroll));
    inspectionSplit.setResizeWeight(0.65);
    inspectionSplit.setDividerLocation(145);
    fields.setMinimumSize(new Dimension(0, 60));
    valueScroll.setMinimumSize(new Dimension(0, 40));
    inspector.add(inspectionSplit, BorderLayout.CENTER);
    JSplitPane split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new SectionPane("Compared actions", list),
            new SectionPane("Action changes", inspector));
    split.setResizeWeight(0.55);
    split.setDividerLocation(260);
    split.setOneTouchExpandable(true);
    split.getTopComponent().setMinimumSize(new Dimension(0, 90));
    split.getBottomComponent().setMinimumSize(new Dimension(0, 90));
    ScrollableViewport body =
        new ScrollableViewport(new BorderLayout()) {
          @Override
          public Dimension getPreferredSize() {
            // Keep space for both table bodies, their paging controls and the selected value.
            // A short window scrolls this layout instead of squeezing rows behind table headers.
            return new Dimension(0, 520);
          }

          @Override
          public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getParent().getHeight() >= getPreferredSize().height;
          }
        };
    body.add(split, BorderLayout.CENTER);
    JScrollPane scroll = new JScrollPane(body);
    scroll.setName("hermeticity.differencesScroll");
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    return scroll;
  }

  @Override
  public void installPageToolbar(PageToolbar value) {
    toolbar = Objects.requireNonNull(value);
    remove(localHeader);
    for (JButton button : List.of(chooseA, chooseB, compare, cancel)) toolbar.addAction(button);
    toolbar.setControls(sources);
    updateControls();
    revalidate();
  }

  public void onChooseA(Runnable callback) {
    onChooseA = Objects.requireNonNull(callback);
  }

  public void onChooseB(Runnable callback) {
    onChooseB = Objects.requireNonNull(callback);
  }

  public void onCompare(Runnable callback) {
    onCompare = Objects.requireNonNull(callback);
  }

  public void onOpenRunA(Runnable callback) {
    onOpenA = callback;
    updateControls();
  }

  public void onOpenRunB(Runnable callback) {
    onOpenB = callback;
    updateControls();
  }

  public void onOpenBuildFile(Consumer<String> callback) {
    onOpenBuild = callback;
    updateControls();
  }

  /** Source labels are descriptive only; they never trigger filesystem access or commands. */
  public void setSources(String labelA, String labelB) {
    requireEdt();
    if (disposed) return;
    if (sourceA.getText().equals(labelA) && sourceB.getText().equals(labelB)) return;
    long wanted = ++generation;
    stopCurrent();
    setSourceLabels(labelA, labelB);
    clearResults();
    busy = !draining.isDone();
    status.setText(busy ? "Closing previous comparison…" : "Choose both sources, then Compare.");
    updateControls();
    afterCleanup(
        wanted,
        () -> {
          busy = false;
          status.setText("Choose both sources, then Compare.");
          updateControls();
        });
  }

  /** Replacement waits for old private files to close before opening the new service. */
  public void openComparison(ComparisonController.Factory factory, String labelA, String labelB) {
    requireEdt();
    Objects.requireNonNull(factory);
    if (disposed) return;
    long wanted = ++generation;
    stopCurrent();
    setSourceLabels(labelA, labelB);
    clearResults();
    busy = true;
    status.setText("Preparing comparison…");
    updateControls();
    afterCleanup(
        wanted,
        () ->
            controller =
                new ComparisonController(factory, SwingUtilities::invokeLater, listener(wanted)));
  }

  public void setContextNotes(List<String> notes) {
    contextNotes = List.copyOf(notes);
    refreshCoverage();
  }

  /** Already-loaded managed-audit progress or failure text; no work is started here. */
  public void displayAuditStatus(String message) {
    status.setText(Objects.requireNonNullElse(message, ""));
  }

  public CompletionStage<Void> closeAsync() {
    requireEdt();
    disposed = true;
    generation++;
    stopCurrent();
    ready = false;
    busy = true;
    updateControls();
    return draining;
  }

  private void cancelComparison() {
    long wanted = ++generation;
    stopCurrent();
    clearResults();
    busy = true;
    status.setText("Cancelling and closing private comparison files…");
    updateControls();
    afterCleanup(
        wanted,
        () -> {
          busy = false;
          status.setText("Comparison cancelled. No source files were changed.");
          updateControls();
        });
  }

  private void stopCurrent() {
    if (controller != null) {
      draining = controller.closeAsync().toCompletableFuture();
      controller = null;
    }
  }

  private void afterCleanup(long wanted, Runnable action) {
    draining.whenComplete(
        (ignored, failure) ->
            SwingUtilities.invokeLater(
                () -> {
                  if (disposed || wanted != generation) return;
                  if (failure != null) {
                    busy = false;
                    status.setText(
                        "Previous comparison cleanup failed. New comparison was not started: "
                            + failure.getMessage());
                    updateControls();
                  } else action.run();
                }));
  }

  private ComparisonController.Listener listener(long wanted) {
    return new ComparisonController.Listener() {
      private boolean current() {
        return !disposed && generation == wanted;
      }

      @Override
      public void opened(ReproComparison.Summary result) {
        if (!current()) return;
        resultSummary = result;
        ready = true;
        busy = false;
        summary.setText(
            "Run A: "
                + result.actionsA()
                + " recorded actions\nRun B: "
                + result.actionsB()
                + " recorded actions\n\nMatched pairs: "
                + result.matched()
                + "\nNo differences observed: "
                + result.unchanged()
                + "\nOutput divergences with complete recorded input evidence: "
                + result.outputDivergences()
                + "\nRecipe or input drift: "
                + result.drift()
                + "\nSupported downstream changes: "
                + result.downstream()
                + "\nRows with incomplete evidence: "
                + result.inconclusive()
                + "\n\n"
                + "These counts describe recorded observations, not every action Bazel declared."
                + " Equal results do not prove hermeticity. Inspect Coverage & runs before drawing"
                + " conclusions.");
        summary.setCaretPosition(0);
        refreshCoverage();
        requestPage(0, 0);
      }

      @Override
      public void pageLoaded(ReproComparison.Page page, long afterId) {
        if (!current()) return;
        rows.set(page.rows());
        pageTotal = page.total();
        fetchingPage = false;
        pageStatus.setText(range(pageOffset, rows.values.size(), pageTotal, "matching rows"));
        status.setText("Comparison ready. Equal results do not prove hermeticity.");
        updateControls();
        if (!rows.values.isEmpty()) actions.setRowSelectionInterval(0, 0);
      }

      @Override
      public void detailsLoaded(ReproComparison.Details result, long offset) {
        if (!current()) return;
        ReproComparison.Row selected = selectedRow();
        if (selected == null || selected.id() != result.row().id()) return;
        detailOffset = offset;
        detailTotal = result.total();
        changes.set(result.differences());
        fetchingDetails = false;
        rowReason.setText(result.row().reason());
        detailStatus.setText(range(offset, changes.values.size(), detailTotal, "field changes"));
        if (detailTotal == 0)
          selectedValue.setText(
              "No recorded field differences. The action's evidence may still be incomplete.");
        updateControls();
      }

      @Override
      public void failed(String message) {
        if (!current()) return;
        boolean pageFailed = fetchingPage;
        fetchingPage = false;
        fetchingDetails = false;
        busy = false;
        status.setText("Comparison could not finish: " + message);
        if (pageFailed) {
          pageStatus.setText("Page not loaded; change the filter or retry First page.");
        }
        updateControls();
      }
    };
  }

  private void requestPage(long after, long offset) {
    if (!ready || controller == null || disposed) return;
    fetchingPage = true;
    fetchingDetails = false;
    actions.clearSelection();
    rows.set(List.of());
    clearDetails();
    pageOffset = offset;
    pageStatus.setText("Loading matching rows…");
    updateControls();
    controller.page(filters.expression(), after, 100);
  }

  private void requestDetails(long offset) {
    ReproComparison.Row row = selectedRow();
    if (fetchingPage || !ready || controller == null || row == null) {
      clearDetails();
      updateControls();
      return;
    }
    fetchingDetails = true;
    detailOffset = offset;
    changes.set(List.of());
    rowReason.setText(row.reason());
    detailStatus.setText("Loading field changes…");
    selectedValue.setText("Select a change cell to read and copy its full value.");
    updateControls();
    controller.details(row.id(), offset, 100);
  }

  private void showSelectedValue() {
    int row = details.getSelectedRow();
    int column = details.getSelectedColumn();
    if (row >= 0 && column >= 0 && row < changes.getRowCount()) {
      selectedValue.setText(Objects.toString(details.getValueAt(row, column), ""));
      selectedValue.setCaretPosition(0);
    }
  }

  private ReproComparison.Row selectedRow() {
    int index = actions.getSelectedRow();
    return index >= 0 && index < rows.values.size() ? rows.values.get(index) : null;
  }

  private void clearResults() {
    ready = false;
    fetchingPage = true;
    actions.clearSelection();
    rows.set(List.of());
    fetchingPage = false;
    resultSummary = null;
    pageOffset = 0;
    pageTotal = 0;
    summary.setText("Choose two execution logs to compare. No differences have been calculated.");
    pageStatus.setText("No rows loaded");
    clearDetails();
    refreshCoverage();
  }

  private void clearDetails() {
    fetchingDetails = false;
    changes.set(List.of());
    detailOffset = 0;
    detailTotal = 0;
    detailStatus.setText("No action selected");
    rowReason.setText("Select an action to inspect its changes.");
    selectedValue.setText("Select a change cell to read and copy its full value.");
  }

  private void refreshCoverage() {
    StringBuilder text =
        new StringBuilder("A: ")
            .append(sourceA.getText())
            .append("\nB: ")
            .append(sourceB.getText());
    text.append(
        "\n\n"
            + "Execution logs cannot prove the absence of undeclared filesystem, network, clock or"
            + " host dependencies.");
    for (String note : contextNotes) text.append("\n\n").append(note);
    if (resultSummary != null) {
      for (String note : resultSummary.coverageNotes()) text.append("\n\n").append(note);
    } else text.append("\n\nComparison coverage has not been calculated.");
    coverage.setText(text.toString());
    coverage.setCaretPosition(0);
  }

  private void updateControls() {
    boolean available = !disposed && !busy;
    chooseA.setEnabled(available);
    chooseB.setEnabled(available);
    compare.setEnabled(
        available
            && !sourceA.getText().isBlank()
            && !sourceB.getText().isBlank()
            && !draining.isCompletedExceptionally());
    cancel.setEnabled(!disposed && (busy || controller != null));
    actions.setEnabled(ready && !fetchingPage);
    details.setEnabled(ready && !fetchingPage && !fetchingDetails);
    firstPage.setEnabled(ready && !fetchingPage);
    nextPage.setEnabled(
        ready
            && !fetchingPage
            && !rows.values.isEmpty()
            && pageOffset + rows.values.size() < pageTotal);
    previousDetails.setEnabled(ready && !fetchingPage && !fetchingDetails && detailOffset > 0);
    nextDetails.setEnabled(
        ready
            && !fetchingPage
            && !fetchingDetails
            && !changes.values.isEmpty()
            && detailOffset + changes.values.size() < detailTotal);
    openA.setEnabled(!disposed && onOpenA != null);
    openB.setEnabled(!disposed && onOpenB != null);
    openBuild.setEnabled(ready && !fetchingPage && selectedRow() != null && onOpenBuild != null);
    if (toolbar != null)
      toolbar.setMetadata(
          busy ? "Working…" : ready ? "Recorded execution comparison" : "Choose two runs");
  }

  private void setSourceLabels(String a, String b) {
    sourceA.setText(Objects.requireNonNullElse(a, ""));
    sourceB.setText(Objects.requireNonNullElse(b, ""));
    sourceA.setCaretPosition(0);
    sourceB.setCaretPosition(0);
  }

  private void sourceRow(int row, String label, JTextField field) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = row;
    c.insets = new Insets(2, 5, 2, 5);
    c.anchor = GridBagConstraints.LINE_START;
    sources.add(new JLabel(label), c);
    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    sources.add(field, c);
  }

  private static JTextField sourceField(String name) {
    JTextField field = new JTextField();
    field.setEditable(false);
    field.setMinimumSize(new Dimension(0, field.getPreferredSize().height));
    field.getAccessibleContext().setAccessibleName(name);
    return field;
  }

  private static JScrollPane scrollText(JTextArea text, String title) {
    ScrollableViewport body = new ScrollableViewport(new BorderLayout());
    body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    body.add(new SectionPane(title, text), BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(body);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    return scroll;
  }

  private static JPanel flow() {
    return new JPanel(new WrapLayout(FlowLayout.LEADING, 6, 3));
  }

  private static void configure(JTable table, boolean cells) {
    PlainText.install(table);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setCellSelectionEnabled(cells);
    table.setRowSelectionAllowed(true);
    table.setFillsViewportHeight(true);
    table.getTableHeader().setReorderingAllowed(false);
  }

  private static String range(long offset, int size, long total, String unit) {
    return (size == 0 ? "0" : (offset + 1) + "–" + (offset + size)) + " of " + total + " " + unit;
  }

  private static List<FilterField> filterFields() {
    return List.of(
        new FilterField(
            "target",
            "Target",
            FilterField.Kind.TEXT,
            List.of(),
            "Bazel target label; supports text and regex filters."),
        new FilterField(
            "mnemonic",
            "Mnemonic",
            FilterField.Kind.TEXT,
            List.of(),
            "Action kind, for example Javac or Genrule."),
        new FilterField(
            "output",
            "Output",
            FilterField.Kind.TEXT,
            List.of(),
            "Representative recorded output path."),
        new FilterField(
            "finding",
            "Finding",
            FilterField.Kind.CHOICE,
            Arrays.stream(ReproComparison.Finding.values())
                .map(f -> new FilterField.Choice(f.name(), findingLabel(f)))
                .toList(),
            "Evidence classification; incomplete is not a successful check."));
  }

  private static String findingLabel(ReproComparison.Finding finding) {
    return switch (finding) {
      case OUTPUT_DIVERGENCE -> "Output divergence";
      case OUTPUT_CHANGED -> "Output changed · partial evidence";
      case RECIPE_DRIFT -> "Recipe drift";
      case INPUT_DRIFT -> "Input drift";
      case CACHE_IDENTITY_DRIFT -> "Cache identity drift";
      case DOWNSTREAM -> "Downstream change";
      case ADDED -> "Added in B";
      case REMOVED -> "Absent in B";
      case INCONCLUSIVE -> "Inconclusive";
      case UNCHANGED -> "No differences observed";
    };
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread())
      throw new IllegalStateException("Hermeticity view updates require the EDT.");
  }

  private static final class RowsModel extends AbstractTableModel {
    private List<ReproComparison.Row> values = List.of();

    void set(List<ReproComparison.Row> value) {
      values = List.copyOf(value);
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return values.size();
    }

    @Override
    public int getColumnCount() {
      return 4;
    }

    @Override
    public String getColumnName(int column) {
      return List.of("Finding", "Target", "Mnemonic", "Output").get(column);
    }

    @Override
    public Object getValueAt(int row, int column) {
      ReproComparison.Row value = values.get(row);
      return switch (column) {
        case 0 -> findingLabel(value.finding());
        case 1 -> value.target();
        case 2 -> value.mnemonic();
        default -> value.output();
      };
    }
  }

  private static final class ChangesModel extends AbstractTableModel {
    private List<ReproComparison.FieldDifference> values = List.of();

    void set(List<ReproComparison.FieldDifference> value) {
      values = List.copyOf(value);
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return values.size();
    }

    @Override
    public int getColumnCount() {
      return 4;
    }

    @Override
    public String getColumnName(int column) {
      return List.of("Section", "Field", "Before (A)", "After (B)").get(column);
    }

    @Override
    public Object getValueAt(int row, int column) {
      ReproComparison.FieldDifference value = values.get(row);
      return switch (column) {
        case 0 -> value.section();
        case 1 -> value.field();
        case 2 -> value.before();
        default -> value.after();
      };
    }
  }
}
