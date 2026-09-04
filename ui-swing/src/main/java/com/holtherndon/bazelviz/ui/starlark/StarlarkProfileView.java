package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ResponsiveGridLayout;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Queryable Starlark CPU profile summary, tables, call graph, and flame view. */
public final class StarlarkProfileView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(StarlarkProfileView.class);
  private static final String CARD_EMPTY = "empty";
  private static final String CARD_CONTENT = "content";

  /** Rows fetched at once; paging changes memory use, never the exact reachable total. */
  public static final int PAGE_SIZE = 200;

  /** Recently visited pages retained by each table. Evicted pages remain queryable. */
  public static final int PAGE_CACHE_SIZE = 8;

  /** Default call contexts requested for one flame scope. Omitted contexts are counted. */
  public static final int DEFAULT_FLAME_NODE_LIMIT = 5_000;

  /** Hot functions in the initial directed call-graph projection. */
  public static final int DEFAULT_CALL_GRAPH_NODE_LIMIT = 80;

  /** Largest directed function graph the UI will retain and paint. */
  public static final int MAX_CALL_GRAPH_NODE_LIMIT = 250;

  /** Strongest caller-to-callee arrows drawn in the initial projection. */
  public static final int DEFAULT_CALL_GRAPH_EDGE_LIMIT = 600;

  /** Largest directed edge projection the UI will retain and paint. */
  public static final int MAX_CALL_GRAPH_EDGE_LIMIT = 5_000;

  private static final int TAB_SUMMARY = 0;
  private static final int TAB_HOT_FUNCTIONS = 1;
  private static final int TAB_FILES = 2;
  private static final int TAB_CALL_GRAPH = 3;
  private static final int TAB_FLAME = 4;

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel("No session is open.");
  private final JTabbedPane tabs = new JTabbedPane();
  private final JPanel content = new JPanel(new BorderLayout());
  private final JPanel localHeader = header();

  private final JPanel summaryCards = new JPanel(new ResponsiveGridLayout(4, 180, 10, 10));
  private final JTextArea summaryDetail = WrappingLabel.create(" ");
  private final SectionPane summaryDetailPane =
      new SectionPane("How to read these numbers", summaryDetail);
  private final ScrollableViewport summaryBody = new ScrollableViewport(new BorderLayout(0, 8));
  private final JScrollPane summaryScroll = new JScrollPane(summaryBody);

  private final JTextField functionSearch = new JTextField(24);
  private final JComboBox<StarlarkProfileReader.FunctionSort> functionSort =
      new JComboBox<>(new DefaultComboBoxModel<>(StarlarkProfileReader.FunctionSort.values()));
  private final JCheckBox functionDescending = new JCheckBox("Descending", true);
  private final JTable functionTable = new JTable(emptyModel("Function", "Source", "Self CPU"));
  private final JTextArea functionStatus = WrappingLabel.create(" ");

  private final JTextField fileSearch = new JTextField(24);
  private final JComboBox<StarlarkProfileReader.FileSort> fileSort =
      new JComboBox<>(new DefaultComboBoxModel<>(StarlarkProfileReader.FileSort.values()));
  private final JCheckBox fileDescending = new JCheckBox("Descending", true);
  private final JTable fileTable = new JTable(emptyModel("Source file", "Self CPU"));
  private final JTextArea fileStatus = WrappingLabel.create(" ");

  private final JLabel callGraphTitle =
      PlainText.disableHtml(
          new JLabel("Choose a function in Hot Functions to inspect its callers and callees."));
  private final JTextArea callGraphSource = WrappingLabel.create(" ");
  private final JTable callersTable = new JTable(emptyModel("Caller", "CPU"));
  private final JTable calleesTable = new JTable(emptyModel("Callee", "CPU"));
  private final JTextArea callGraphStatus = WrappingLabel.create(" ");
  private final StarlarkCallGraph directedCallGraph = new StarlarkCallGraph();
  private final JSpinner callGraphNodeLimit =
      new JSpinner(
          new SpinnerNumberModel(DEFAULT_CALL_GRAPH_NODE_LIMIT, 10, MAX_CALL_GRAPH_NODE_LIMIT, 10));
  private final JSpinner callGraphEdgeLimit =
      new JSpinner(
          new SpinnerNumberModel(
              DEFAULT_CALL_GRAPH_EDGE_LIMIT, 10, MAX_CALL_GRAPH_EDGE_LIMIT, 100));
  private final JComboBox<StarlarkCallGraphLayout.NodeWeight> callGraphNodeWeight =
      new JComboBox<>(new DefaultComboBoxModel<>(StarlarkCallGraphLayout.NodeWeight.values()));
  private final JButton refreshDirectedGraph = new JButton("Refresh graph");
  private final JButton fitDirectedGraph = new JButton("Fit graph");
  private final JButton resetDirectedGraphNodes = new JButton("Reset moved nodes");
  private final JCheckBox showCallGraphEdgeLabels = new JCheckBox("Arrow labels", true);
  private final JTextArea directedCallGraphStatus =
      WrappingLabel.create("Open this tab to load the directed call graph.");

  private final StarlarkFlameGraph flameGraph = new StarlarkFlameGraph();
  private final JButton resetFlame = new JButton("Reset to all roots");
  private final JTextArea flameStatus =
      WrappingLabel.create("Open this tab to load call contexts.");

  private final Timer functionSearchDelay;
  private final Timer fileSearchDelay;

  private Consumer<StarlarkProfileReader.SourceLocation> sourceListener = ignored -> {};
  private SessionSource session;
  private StarlarkProfileReader reader;
  private StarlarkProfileReader.Summary summary;
  private ExecutorService worker;
  private PagedTableModel<StarlarkProfileReader.HotFunction> functionModel;
  private PagedTableModel<StarlarkProfileReader.SourceFile> fileModel;
  private PagedTableModel<StarlarkProfileReader.CallEdge> callersModel;
  private PagedTableModel<StarlarkProfileReader.CallEdge> calleesModel;
  private StarlarkProfileReader.HotFunction selectedFunction;
  private OptionalLong flameFocus = OptionalLong.empty();
  private long generation;
  private long functionQueryGeneration;
  private long fileQueryGeneration;
  private long callQueryGeneration;
  private long directedGraphQueryGeneration;
  private long flameQueryGeneration;
  private PageToolbar pageToolbar;
  private boolean filesLoaded;
  private boolean directedGraphLoaded;
  private boolean flameLoaded;

  public StarlarkProfileView() {
    super(new BorderLayout());
    configureTable(functionTable);
    configureTable(fileTable);
    configureTable(callersTable);
    configureTable(calleesTable);

    functionSearch.setToolTipText("Match a function name or source path");
    fileSearch.setToolTipText("Match a source path");
    functionSearchDelay = searchDelay(this::refreshFunctions);
    fileSearchDelay = searchDelay(this::refreshFiles);
    functionSearch.getDocument().addDocumentListener(documentListener(functionSearchDelay));
    fileSearch.getDocument().addDocumentListener(documentListener(fileSearchDelay));
    functionSort.addActionListener(event -> refreshFunctions());
    functionDescending.addActionListener(event -> refreshFunctions());
    fileSort.addActionListener(event -> refreshFiles());
    fileDescending.addActionListener(event -> refreshFiles());

    functionTable
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                selectFunctionFromTable();
              }
            });
    functionTable.addMouseListener(
        openSourceMouseListener(
            () -> selectedFunctionRow().flatMap(StarlarkProfileReader.HotFunction::source)));
    fileTable.addMouseListener(
        openSourceMouseListener(
            () -> selectedFileRow().map(StarlarkProfileReader.SourceFile::source)));
    callersTable.addMouseListener(
        openSourceMouseListener(
            () ->
                selectedEdgeRow(callersTable, callersModel)
                    .flatMap(StarlarkProfileReader.CallEdge::source)));
    calleesTable.addMouseListener(
        openSourceMouseListener(
            () ->
                selectedEdgeRow(calleesTable, calleesModel)
                    .flatMap(StarlarkProfileReader.CallEdge::source)));

    directedCallGraph.onSelection(this::selectFunctionFromGraph);
    directedCallGraph.onOpenSource(location -> sourceListener.accept(location));
    refreshDirectedGraph.addActionListener(
        event -> {
          directedGraphLoaded = false;
          loadDirectedGraph();
        });
    callGraphNodeWeight.addActionListener(
        event -> {
          directedGraphLoaded = false;
          directedGraphQueryGeneration++;
          if (tabs.getSelectedIndex() == TAB_CALL_GRAPH) {
            loadDirectedGraph();
          }
        });
    fitDirectedGraph.addActionListener(event -> directedCallGraph.fitToView());
    resetDirectedGraphNodes.addActionListener(event -> directedCallGraph.resetMovedNodes());
    showCallGraphEdgeLabels.addActionListener(
        event -> directedCallGraph.setEdgeLabels(showCallGraphEdgeLabels.isSelected()));
    callGraphNodeLimit.setToolTipText(
        "Hottest functions retained in the graph; the selected function is pinned");
    callGraphEdgeLimit.setToolTipText(
        "Strongest caller-to-callee relationships retained between shown functions");
    callGraphNodeWeight.setToolTipText(
        "Choose which sampled CPU value controls each function box's size");
    resetDirectedGraphNodes.setToolTipText(
        "Put every dragged function back where the layout placed it");

    flameGraph.onFocus(id -> loadFlame(OptionalLong.of(id)));
    flameGraph.onOpenSource(location -> sourceListener.accept(location));
    resetFlame.addActionListener(event -> loadFlame(OptionalLong.empty()));
    resetFlame.setEnabled(false);

    tabs.addTab("Summary", summaryTab());
    tabs.addTab("Hot Functions", functionsTab());
    tabs.addTab("Files", filesTab());
    tabs.addTab("Call Graph", callGraphTab());
    tabs.addTab("Flame", flameTab());
    tabs.addChangeListener(event -> tabSelected());

    content.add(localHeader, BorderLayout.NORTH);
    content.add(tabs, BorderLayout.CENTER);
    deck.add(emptyState, CARD_EMPTY);
    deck.add(content, CARD_CONTENT);
    add(deck, BorderLayout.CENTER);
    showEmpty("No session is open.");
  }

  /** Moves the page introduction into the shared window chrome. */
  @Override
  public void installPageToolbar(PageToolbar toolbar) {
    Objects.requireNonNull(toolbar, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = toolbar;
    content.remove(localHeader);
    syncPageMetadata("", "");
    content.revalidate();
    content.repaint();
  }

  /** Opens a session asynchronously and reads only its summary initially. */
  public void openSession(SessionSource opened) {
    Objects.requireNonNull(opened, "opened");
    closeSessionAsync();
    session = opened;
    long wanted = ++generation;
    worker = singleThreadExecutor();
    ExecutorService openingWorker = worker;
    showEmpty("Reading the Starlark CPU profile…");
    openingWorker.execute(
        () -> {
          StarlarkProfileReader candidate = null;
          try {
            candidate = opened.openStarlarkProfileReader();
            StarlarkProfileReader.Summary loaded = candidate.summary();
            StarlarkProfileReader ready = candidate;
            SwingUtilities.invokeLater(() -> installReader(opened, wanted, ready, loaded));
          } catch (RuntimeException failure) {
            closeQuietly(candidate);
            SwingUtilities.invokeLater(
                () -> {
                  if (wanted == generation && session == opened) {
                    showEmpty("The Starlark CPU profile could not be opened: " + describe(failure));
                  }
                });
          }
        });
  }

  private void installReader(
      SessionSource opened,
      long wanted,
      StarlarkProfileReader ready,
      StarlarkProfileReader.Summary loaded) {
    if (wanted != generation || session != opened) {
      ViewClose.runAsync("bbv-stale-starlark-profile-close", () -> closeQuietly(ready));
      return;
    }
    reader = ready;
    summary = loaded;
    if (loaded.isAvailable()) {
      String cpu = EntityFormat.duration(loaded.sampledCpuMicros());
      String functions = EntityFormat.count(loaded.functionCount());
      syncPageMetadata(
          cpu + " sampled CPU · " + functions + " functions",
          loaded.detail() + " Sampled CPU: " + cpu + ". Functions: " + functions + ".");
    } else {
      syncPageMetadata(loaded.availability().displayName(), loaded.detail());
    }
    cards.show(deck, CARD_CONTENT);
    renderSummary(loaded);
    boolean available = loaded.isAvailable();
    for (int index = TAB_HOT_FUNCTIONS; index < tabs.getTabCount(); index++) {
      tabs.setEnabledAt(index, available);
    }
    if (available) {
      refreshFunctions();
    } else {
      functionStatus.setText(loaded.detail());
      fileStatus.setText(loaded.detail());
      callGraphStatus.setText(loaded.detail());
      directedCallGraphStatus.setText(loaded.detail());
      flameStatus.setText(loaded.detail());
    }
  }

  /** Installs the host callback used by table and flame source actions. */
  public void onOpenSource(Consumer<StarlarkProfileReader.SourceLocation> listener) {
    sourceListener = Objects.requireNonNull(listener, "listener");
  }

  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately, then closes the reader only after queued work has stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    generation++;
    functionQueryGeneration++;
    fileQueryGeneration++;
    callQueryGeneration++;
    directedGraphQueryGeneration++;
    flameQueryGeneration++;
    functionSearchDelay.stop();
    fileSearchDelay.stop();
    session = null;
    summary = null;
    selectedFunction = null;
    filesLoaded = false;
    directedGraphLoaded = false;
    flameLoaded = false;
    flameFocus = OptionalLong.empty();
    functionModel = null;
    fileModel = null;
    callersModel = null;
    calleesModel = null;
    functionTable.setModel(emptyModel("Function", "Source", "Self CPU"));
    fileTable.setModel(emptyModel("Source file", "Self CPU"));
    callersTable.setModel(emptyModel("Caller", "CPU"));
    calleesTable.setModel(emptyModel("Callee", "CPU"));
    callGraphTitle.setText(
        "Choose a function in Hot Functions to inspect its callers and callees.");
    callGraphSource.setText(" ");
    directedCallGraph.setLayoutModel(
        StarlarkCallGraphLayout.layout(
            new StarlarkProfileReader.DirectedCallGraph(
                0, 0, 0, 0, OptionalLong.empty(), List.of(), List.of())));
    directedCallGraphStatus.setText("Open this tab to load the directed call graph.");
    flameGraph.setSlice(
        new StarlarkProfileReader.FlameSlice(
            OptionalLong.empty(), 0, 0, OptionalLong.empty(), List.of()));
    resetFlame.setEnabled(false);
    tabs.setSelectedIndex(TAB_SUMMARY);
    showEmpty("No session is open.");

    ExecutorService closingWorker = worker;
    worker = null;
    StarlarkProfileReader closingReader = reader;
    reader = null;
    if (closingReader != null) {
      try {
        closingReader.cancelRunningQuery();
      } catch (RuntimeException failure) {
        log.debug("could not interrupt a Starlark profile query", failure);
      }
    }
    if (closingWorker == null && closingReader == null) {
      return CompletableFuture.completedFuture(null);
    }
    CompletionStage<Void> workerStopped =
        closingWorker == null
            ? CompletableFuture.completedFuture(null)
            : ExecutorClose.cancelAsync(closingWorker, "bbv-starlark-profile");
    if (closingReader == null) {
      return workerStopped;
    }
    return workerStopped.thenCompose(
        ignored ->
            ViewClose.runAsync(
                "bbv-starlark-profile-reader-close", () -> closeQuietly(closingReader)));
  }

  private JPanel header() {
    JPanel panel = new JPanel(new BorderLayout(10, 0));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
    JPanel copy = new JPanel(new BorderLayout(0, 3));
    JLabel title = PlainText.disableHtml(new JLabel("Starlark CPU profile"));
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 4f));
    copy.add(title, BorderLayout.NORTH);
    copy.add(
        WrappingLabel.create(
            "Find expensive Starlark functions and their call contexts. This is sampled CPU,"
                + " not elapsed time or a record of what Bazel waited for."),
        BorderLayout.CENTER);
    panel.add(copy, BorderLayout.CENTER);
    return panel;
  }

  private JPanel summaryTab() {
    summaryCards.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    summaryBody.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    summaryBody.add(new SectionPane("Profile summary", summaryCards), BorderLayout.NORTH);
    summaryBody.add(summaryDetailPane, BorderLayout.CENTER);
    summaryScroll.setBorder(BorderFactory.createEmptyBorder());
    summaryScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    summaryScroll.getVerticalScrollBar().setUnitIncrement(16);
    JPanel tab = new JPanel(new BorderLayout());
    tab.add(summaryScroll, BorderLayout.CENTER);
    return tab;
  }

  private JPanel functionsTab() {
    JPanel toolbar =
        toolbar(
            new JLabel("Find:"),
            functionSearch,
            new JLabel("Sort:"),
            functionSort,
            functionDescending);
    JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    content.add(toolbar, BorderLayout.NORTH);
    content.add(new SectionPane("Functions", new JScrollPane(functionTable)), BorderLayout.CENTER);
    content.add(functionStatus, BorderLayout.SOUTH);
    return content;
  }

  private JPanel filesTab() {
    JPanel toolbar =
        toolbar(new JLabel("Find:"), fileSearch, new JLabel("Sort:"), fileSort, fileDescending);
    JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    content.add(toolbar, BorderLayout.NORTH);
    content.add(new SectionPane("Source files", new JScrollPane(fileTable)), BorderLayout.CENTER);
    content.add(fileStatus, BorderLayout.SOUTH);
    return content;
  }

  private JPanel callGraphTab() {
    JPanel controls =
        toolbar(
            new JLabel("Functions:"),
            callGraphNodeLimit,
            new JLabel("Arrows:"),
            callGraphEdgeLimit,
            new JLabel("Node size:"),
            callGraphNodeWeight,
            refreshDirectedGraph,
            fitDirectedGraph,
            resetDirectedGraphNodes,
            showCallGraphEdgeLabels);

    JPanel graphPane = new JPanel(new BorderLayout(0, 6));
    graphPane.add(new SectionPane("Directed CPU graph", directedCallGraph), BorderLayout.CENTER);
    graphPane.add(directedCallGraphStatus, BorderLayout.SOUTH);
    graphPane.setMinimumSize(new Dimension(460, 260));

    JPanel title = new JPanel(new BorderLayout(0, 2));
    title.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    title.add(callGraphTitle, BorderLayout.NORTH);
    title.add(callGraphSource, BorderLayout.CENTER);
    SectionPane callers = new SectionPane("Callers", new JScrollPane(callersTable));
    SectionPane callees = new SectionPane("Callees", new JScrollPane(calleesTable));
    callers.setMinimumSize(new Dimension(320, 120));
    callees.setMinimumSize(new Dimension(320, 120));
    JSplitPane relationshipSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, callers, callees);
    relationshipSplit.setResizeWeight(0.5);
    relationshipSplit.setContinuousLayout(true);
    JPanel relationships = new JPanel(new BorderLayout(0, 6));
    relationships.add(title, BorderLayout.NORTH);
    relationships.add(relationshipSplit, BorderLayout.CENTER);
    relationships.add(callGraphStatus, BorderLayout.SOUTH);
    relationships.setMinimumSize(new Dimension(350, 260));

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphPane, relationships);
    split.setResizeWeight(0.72);
    split.setContinuousLayout(true);
    JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    content.add(controls, BorderLayout.NORTH);
    content.add(split, BorderLayout.CENTER);
    return content;
  }

  private JPanel flameTab() {
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
    actions.add(resetFlame);
    actions.add(new JLabel("Double-click a context to focus it; right-click for source."));
    JPanel content = new JPanel(new BorderLayout(0, 6));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    content.add(actions, BorderLayout.NORTH);
    content.add(new SectionPane("Call contexts", new JScrollPane(flameGraph)), BorderLayout.CENTER);
    content.add(flameStatus, BorderLayout.SOUTH);
    return content;
  }

  private void renderSummary(StarlarkProfileReader.Summary value) {
    summaryCards.removeAll();
    if (!value.isAvailable()) {
      summaryCards.add(
          summaryCard("Profile status", value.availability().displayName(), value.detail()));
      summaryDetailPane.setVisible(false);
      summaryCards.revalidate();
      summaryCards.repaint();
      return;
    }
    summaryDetailPane.setVisible(true);
    summaryCards.add(
        summaryCard(
            "Sampled Starlark CPU",
            EntityFormat.duration(value.sampledCpuMicros()),
            "sum across profiled Starlark threads"));
    summaryCards.add(
        summaryCard(
            "Profile wall duration",
            EntityFormat.duration(value.wallDurationMicros()),
            "the profile window, when reported"));
    summaryCards.add(
        summaryCard(
            "Average sampled CPU cores",
            averageCores(value),
            "sampled CPU divided by profile wall duration; may exceed 1"));
    summaryCards.add(
        summaryCard(
            "Profile records",
            EntityFormat.count(value.sampleRecordCount()),
            "one record may aggregate several sampling ticks"));
    summaryCards.add(
        summaryCard(
            "Sampling period",
            EntityFormat.duration(value.samplePeriodMicros()),
            "statistical interval reported by the profile"));
    summaryCards.add(
        summaryCard(
            "Functions",
            EntityFormat.count(value.functionCount()),
            "distinct functions represented"));
    summaryCards.add(
        summaryCard(
            "Source files",
            EntityFormat.count(value.fileCount()),
            value.correlation().displayName()));
    summaryCards.add(
        summaryCard(
            "Function-attributed CPU",
            coverageValue(value.functionAttribution()),
            coverageNote(value.functionAttribution())));
    summaryCards.add(
        summaryCard(
            "File-attributed CPU",
            coverageValue(value.fileAttribution()),
            coverageNote(value.fileAttribution())));
    summaryCards.add(
        summaryCard(
            "Fully labelled contexts",
            coverageValue(value.contextAttribution()),
            coverageNote(value.contextAttribution())));
    summaryDetail.setText(
        "CPU samples measure time when Starlark threads were running. They exclude time"
            + " blocked on I/O and time a runnable thread did not receive CPU. Several threads"
            + " can run during the same wall-clock interval, so sampled CPU can exceed profile"
            + " duration. The profile is statistical, not an exact trace. File and line values"
            + " are navigation hints; they are not reliable line-level heat measurements."
            + " Attribution cards state CPU and records that could and could not be assigned."
            + " Inline functions contribute to flat cumulative metrics; a call context is"
            + " fully labelled only when every physical location has exactly one named"
            + " function.\n\n"
            + "Provenance: "
            + value.correlation().displayName()
            + ".");
    summaryCards.revalidate();
    summaryCards.repaint();
  }

  private static String coverageValue(StarlarkProfileReader.AttributionCoverage coverage) {
    return coverage.attributedCpuMicros().isPresent()
        ? EntityFormat.duration(coverage.attributedCpuMicros())
        : "—";
  }

  private static String coverageNote(StarlarkProfileReader.AttributionCoverage coverage) {
    if (coverage.attributedCpuMicros().isEmpty()
        || coverage.unattributedCpuMicros().isEmpty()
        || coverage.attributedSampleRecords().isEmpty()
        || coverage.unattributedSampleRecords().isEmpty()) {
      return "attribution coverage was not recorded";
    }
    return EntityFormat.duration(coverage.unattributedCpuMicros())
        + " unattributed; "
        + EntityFormat.count(coverage.attributedSampleRecords())
        + " attributed / "
        + EntityFormat.count(coverage.unattributedSampleRecords())
        + " unattributed records";
  }

  private static JPanel summaryCard(String name, String value, String note) {
    JPanel card = new JPanel();
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    card.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor") == null
                    ? Color.GRAY
                    : UIManager.getColor("Component.borderColor")),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)));
    JLabel nameLabel = PlainText.disableHtml(new JLabel(name));
    JLabel valueLabel = PlainText.disableHtml(new JLabel(value));
    valueLabel.setFont(
        valueLabel.getFont().deriveFont(Font.BOLD, valueLabel.getFont().getSize() + 4f));
    JTextArea noteLabel = WrappingLabel.create(note);
    nameLabel.setAlignmentX(LEFT_ALIGNMENT);
    valueLabel.setAlignmentX(LEFT_ALIGNMENT);
    noteLabel.setAlignmentX(LEFT_ALIGNMENT);
    card.add(nameLabel);
    card.add(valueLabel);
    card.add(noteLabel);
    return card;
  }

  private void refreshFunctions() {
    StarlarkProfileReader active = reader;
    ExecutorService fetcher = worker;
    StarlarkProfileReader.Summary currentSummary = summary;
    if (active == null
        || fetcher == null
        || currentSummary == null
        || !currentSummary.isAvailable()) {
      return;
    }
    long wantedSession = generation;
    long wantedQuery = ++functionQueryGeneration;
    StarlarkProfileReader.FunctionQuery query =
        new StarlarkProfileReader.FunctionQuery(
            functionSearch.getText(),
            (StarlarkProfileReader.FunctionSort) functionSort.getSelectedItem(),
            functionDescending.isSelected());
    functionModel = null;
    functionTable.setModel(emptyModel("Function", "Source", "Self CPU"));
    functionStatus.setText("Counting matching functions…");
    fetcher.execute(
        () -> {
          try {
            StarlarkFunctionRowSource source = StarlarkFunctionRowSource.open(active, query);
            SwingUtilities.invokeLater(
                () -> {
                  if (!isCurrent(wantedSession, wantedQuery, functionQueryGeneration, active)) {
                    return;
                  }
                  installFunctionSource(source, query);
                });
          } catch (RuntimeException failure) {
            showWorkerFailure(
                wantedSession,
                wantedQuery,
                functionQueryGeneration,
                active,
                functionStatus,
                "Functions could not be read",
                failure);
          }
        });
  }

  private void installFunctionSource(
      StarlarkFunctionRowSource source, StarlarkProfileReader.FunctionQuery query) {
    try {
      PagedTableModel<StarlarkProfileReader.HotFunction> model =
          new PagedTableModel<>(source, functionColumns(), worker, PAGE_SIZE, PAGE_CACHE_SIZE);
      model.addTableModelListener(event -> selectFunctionFromTable());
      functionModel = model;
      functionTable.setModel(model);
      configureFunctionColumns();
      functionStatus.setText(
          EntityFormat.count(source.rowCount())
              + " matching functions"
              + (query.search().isEmpty() ? "." : " for ‘" + query.search() + "’."));
    } catch (IllegalArgumentException failure) {
      functionStatus.setText("Functions cannot be displayed: " + describe(failure));
    }
  }

  private void refreshFiles() {
    StarlarkProfileReader active = reader;
    ExecutorService fetcher = worker;
    StarlarkProfileReader.Summary currentSummary = summary;
    if (active == null
        || fetcher == null
        || currentSummary == null
        || !currentSummary.isAvailable()) {
      return;
    }
    filesLoaded = true;
    long wantedSession = generation;
    long wantedQuery = ++fileQueryGeneration;
    StarlarkProfileReader.FileQuery query =
        new StarlarkProfileReader.FileQuery(
            fileSearch.getText(),
            (StarlarkProfileReader.FileSort) fileSort.getSelectedItem(),
            fileDescending.isSelected());
    fileModel = null;
    fileTable.setModel(emptyModel("Source file", "Self CPU"));
    fileStatus.setText("Counting matching source files…");
    fetcher.execute(
        () -> {
          try {
            StarlarkFileRowSource source = StarlarkFileRowSource.open(active, query);
            SwingUtilities.invokeLater(
                () -> {
                  if (!isCurrent(wantedSession, wantedQuery, fileQueryGeneration, active)) {
                    return;
                  }
                  installFileSource(source, query);
                });
          } catch (RuntimeException failure) {
            showWorkerFailure(
                wantedSession,
                wantedQuery,
                fileQueryGeneration,
                active,
                fileStatus,
                "Source files could not be read",
                failure);
          }
        });
  }

  private void installFileSource(
      StarlarkFileRowSource source, StarlarkProfileReader.FileQuery query) {
    try {
      PagedTableModel<StarlarkProfileReader.SourceFile> model =
          new PagedTableModel<>(source, fileColumns(), worker, PAGE_SIZE, PAGE_CACHE_SIZE);
      fileModel = model;
      fileTable.setModel(model);
      configureFileColumns();
      fileStatus.setText(
          EntityFormat.count(source.rowCount())
              + " matching source files"
              + (query.search().isEmpty() ? "." : " for ‘" + query.search() + "’."));
    } catch (IllegalArgumentException failure) {
      fileStatus.setText("Source files cannot be displayed: " + describe(failure));
    }
  }

  private void selectFunctionFromTable() {
    Optional<StarlarkProfileReader.HotFunction> selected = selectedFunctionRow();
    if (selected.isEmpty() || selected.orElseThrow().equals(selectedFunction)) {
      return;
    }
    selectedFunction = selected.orElseThrow();
    showCallGraphSelection(selectedFunction.name(), selectedFunction.source());
    if (directedGraphLoaded && !directedCallGraph.selectFunction(selectedFunction.id(), false)) {
      directedGraphLoaded = false;
      directedGraphQueryGeneration++;
    }
    if (tabs.getSelectedIndex() == TAB_CALL_GRAPH) {
      loadDirectedGraph();
      refreshCallGraph();
    } else {
      callGraphStatus.setText(
          "Open Call Graph to load callers and callees for " + selectedFunction.name() + ".");
    }
  }

  private void selectFunctionFromGraph(StarlarkProfileReader.CallGraphNode node) {
    StarlarkProfileReader.HotFunction function =
        new StarlarkProfileReader.HotFunction(
            node.functionId(),
            node.function(),
            node.source(),
            node.selfCpuMicros(),
            node.cumulativeCpuMicros(),
            node.selfSampleRecords(),
            node.cumulativeSampleRecords(),
            node.contextCount());
    selectedFunction = function;
    showCallGraphSelection(function.name(), function.source());
    refreshCallGraph();
  }

  private void showCallGraphSelection(
      String function, Optional<StarlarkProfileReader.SourceLocation> source) {
    callGraphTitle.setText("Call relationships for " + function);
    callGraphSource.setText(
        source
            .map(
                location ->
                    location.path()
                        + (location.line().isPresent() ? ":" + location.line().getAsInt() : ""))
            .orElse("Source unavailable"));
  }

  private void loadDirectedGraph() {
    StarlarkProfileReader active = reader;
    ExecutorService fetcher = worker;
    StarlarkProfileReader.Summary currentSummary = summary;
    if (active == null
        || fetcher == null
        || currentSummary == null
        || !currentSummary.isAvailable()
        || directedGraphLoaded) {
      return;
    }
    directedGraphLoaded = true;
    int nodeLimit = ((Number) callGraphNodeLimit.getValue()).intValue();
    int edgeLimit = ((Number) callGraphEdgeLimit.getValue()).intValue();
    StarlarkCallGraphLayout.NodeWeight nodeWeight =
        (StarlarkCallGraphLayout.NodeWeight) callGraphNodeWeight.getSelectedItem();
    OptionalLong pinned =
        selectedFunction == null ? OptionalLong.empty() : OptionalLong.of(selectedFunction.id());
    long wantedSession = generation;
    long wantedQuery = ++directedGraphQueryGeneration;
    directedCallGraphStatus.setText("Loading the directed Starlark call graph…");
    fetcher.execute(
        () -> {
          try {
            StarlarkProfileReader.DirectedCallGraph graph =
                active.directedCallGraph(pinned, nodeLimit, edgeLimit);
            if (graph.nodes().size() > nodeLimit || graph.edges().size() > edgeLimit) {
              throw new IllegalStateException(
                  "the profile reader exceeded the requested" + " directed graph budget");
            }
            StarlarkCallGraphLayout.Layout graphLayout =
                StarlarkCallGraphLayout.layout(graph, nodeWeight);
            SwingUtilities.invokeLater(
                () -> {
                  if (!isCurrent(
                      wantedSession, wantedQuery, directedGraphQueryGeneration, active)) {
                    return;
                  }
                  directedCallGraph.setLayoutModel(graphLayout);
                  StarlarkProfileReader.HotFunction currentSelection = selectedFunction;
                  if (currentSelection != null
                      && !directedCallGraph.selectFunction(currentSelection.id(), false)) {
                    directedGraphLoaded = false;
                    loadDirectedGraph();
                    return;
                  }
                  directedCallGraphStatus.setText(
                      directedGraphStatus(graph, nodeLimit, edgeLimit, nodeWeight));
                });
          } catch (RuntimeException failure) {
            SwingUtilities.invokeLater(
                () -> {
                  if (isCurrent(wantedSession, wantedQuery, directedGraphQueryGeneration, active)) {
                    directedGraphLoaded = false;
                    directedCallGraphStatus.setText(
                        "The directed call graph could not be read: " + describe(failure));
                  }
                });
          }
        });
  }

  private void refreshCallGraph() {
    StarlarkProfileReader active = reader;
    ExecutorService fetcher = worker;
    StarlarkProfileReader.HotFunction function = selectedFunction;
    if (active == null || fetcher == null || function == null) {
      callGraphStatus.setText(
          "Choose a function in Hot Functions to inspect its callers and callees.");
      return;
    }
    long wantedSession = generation;
    long wantedQuery = ++callQueryGeneration;
    callersModel = null;
    calleesModel = null;
    callersTable.setModel(emptyModel("Caller", "CPU"));
    calleesTable.setModel(emptyModel("Callee", "CPU"));
    callGraphStatus.setText("Counting callers and callees…");
    fetcher.execute(
        () -> {
          try {
            StarlarkCallEdgeRowSource callers =
                StarlarkCallEdgeRowSource.open(
                    active, function.id(), StarlarkProfileReader.CallDirection.CALLERS);
            StarlarkCallEdgeRowSource callees =
                StarlarkCallEdgeRowSource.open(
                    active, function.id(), StarlarkProfileReader.CallDirection.CALLEES);
            SwingUtilities.invokeLater(
                () -> {
                  if (!isCurrent(wantedSession, wantedQuery, callQueryGeneration, active)
                      || selectedFunction == null
                      || selectedFunction.id() != function.id()) {
                    return;
                  }
                  installCallSources(function, callers, callees);
                });
          } catch (RuntimeException failure) {
            showWorkerFailure(
                wantedSession,
                wantedQuery,
                callQueryGeneration,
                active,
                callGraphStatus,
                "Call relationships could not be read",
                failure);
          }
        });
  }

  private void installCallSources(
      StarlarkProfileReader.HotFunction function,
      StarlarkCallEdgeRowSource callers,
      StarlarkCallEdgeRowSource callees) {
    try {
      callersModel =
          new PagedTableModel<>(callers, edgeColumns(), worker, PAGE_SIZE, PAGE_CACHE_SIZE);
      calleesModel =
          new PagedTableModel<>(callees, edgeColumns(), worker, PAGE_SIZE, PAGE_CACHE_SIZE);
      callersTable.setModel(callersModel);
      calleesTable.setModel(calleesModel);
      configureEdgeColumns(callersTable);
      configureEdgeColumns(calleesTable);
      callGraphStatus.setText(
          EntityFormat.count(callers.rowCount())
              + " callers and "
              + EntityFormat.count(callees.rowCount())
              + " callees for "
              + function.name()
              + ". Values are aggregate sampled CPU on each relationship.");
    } catch (IllegalArgumentException failure) {
      callGraphStatus.setText("Call relationships cannot be displayed: " + describe(failure));
    }
  }

  private void loadFlame(OptionalLong focus) {
    StarlarkProfileReader active = reader;
    ExecutorService fetcher = worker;
    if (active == null || fetcher == null || summary == null || !summary.isAvailable()) {
      return;
    }
    flameLoaded = true;
    long wantedSession = generation;
    long wantedQuery = ++flameQueryGeneration;
    flameStatus.setText("Loading bounded call contexts…");
    fetcher.execute(
        () -> {
          try {
            StarlarkProfileReader.FlameSlice loaded =
                active.flameRows(focus, DEFAULT_FLAME_NODE_LIMIT);
            if (loaded.nodes().size() > DEFAULT_FLAME_NODE_LIMIT) {
              throw new IllegalStateException(
                  "the profile reader returned "
                      + loaded.nodes().size()
                      + " call contexts for a limit of "
                      + DEFAULT_FLAME_NODE_LIMIT);
            }
            SwingUtilities.invokeLater(
                () -> {
                  if (!isCurrent(wantedSession, wantedQuery, flameQueryGeneration, active)) {
                    return;
                  }
                  flameFocus = focus;
                  flameGraph.setSlice(loaded);
                  resetFlame.setEnabled(focus.isPresent());
                  flameStatus.setText(flameStatus(loaded));
                });
          } catch (RuntimeException failure) {
            showWorkerFailure(
                wantedSession,
                wantedQuery,
                flameQueryGeneration,
                active,
                flameStatus,
                "Call contexts could not be read",
                failure);
          }
        });
  }

  private void tabSelected() {
    if (tabs.getSelectedIndex() == TAB_FILES && !filesLoaded) {
      refreshFiles();
    } else if (tabs.getSelectedIndex() == TAB_CALL_GRAPH) {
      loadDirectedGraph();
      refreshCallGraph();
    } else if (tabs.getSelectedIndex() == TAB_FLAME && !flameLoaded) {
      loadFlame(flameFocus);
    }
  }

  private List<ColumnSpec<StarlarkProfileReader.HotFunction>> functionColumns() {
    return List.of(
        new ColumnSpec<>("Function", StarlarkProfileReader.HotFunction::name),
        new ColumnSpec<>(
            "Source",
            function ->
                function
                    .source()
                    .map(StarlarkProfileReader.SourceLocation::path)
                    .orElse(EntityFormat.UNKNOWN)),
        new ColumnSpec<>(
            "Line",
            function ->
                function
                    .source()
                    .map(StarlarkProfileReader.SourceLocation::line)
                    .map(EntityFormat::count)
                    .orElse(EntityFormat.UNKNOWN)),
        new ColumnSpec<>("Self CPU", function -> EntityFormat.duration(function.selfCpuMicros())),
        new ColumnSpec<>("Self %", function -> percentOfTotal(function.selfCpuMicros())),
        new ColumnSpec<>(
            "Cumulative CPU", function -> EntityFormat.duration(function.cumulativeCpuMicros())),
        new ColumnSpec<>(
            "Cumulative %", function -> percentOfTotal(function.cumulativeCpuMicros())),
        new ColumnSpec<>(
            "Self records", function -> EntityFormat.count(function.selfSampleRecords())),
        new ColumnSpec<>("Contexts", function -> EntityFormat.count(function.contextCount())));
  }

  private List<ColumnSpec<StarlarkProfileReader.SourceFile>> fileColumns() {
    return List.of(
        new ColumnSpec<>("Source file", StarlarkProfileReader.SourceFile::path),
        new ColumnSpec<>("Self CPU", file -> EntityFormat.duration(file.selfCpuMicros())),
        new ColumnSpec<>("Self %", file -> percentOfTotal(file.selfCpuMicros())),
        new ColumnSpec<>(
            "Cumulative CPU", file -> EntityFormat.duration(file.cumulativeCpuMicros())),
        new ColumnSpec<>("Cumulative %", file -> percentOfTotal(file.cumulativeCpuMicros())),
        new ColumnSpec<>("Functions", file -> EntityFormat.count(file.functionCount())),
        new ColumnSpec<>("Records", file -> EntityFormat.count(file.sampleRecords())));
  }

  private List<ColumnSpec<StarlarkProfileReader.CallEdge>> edgeColumns() {
    return List.of(
        new ColumnSpec<>("Function", StarlarkProfileReader.CallEdge::relatedFunction),
        new ColumnSpec<>(
            "Source",
            edge ->
                edge.source()
                    .map(StarlarkProfileReader.SourceLocation::path)
                    .orElse(EntityFormat.UNKNOWN)),
        new ColumnSpec<>("CPU", edge -> EntityFormat.duration(edge.cpuMicros())),
        new ColumnSpec<>("CPU %", edge -> percentOfTotal(edge.cpuMicros())),
        new ColumnSpec<>("Records", edge -> EntityFormat.count(edge.sampleRecords())));
  }

  private String percentOfTotal(OptionalLong value) {
    StarlarkProfileReader.Summary current = summary;
    if (value.isEmpty()
        || current == null
        || current.sampledCpuMicros().isEmpty()
        || current.sampledCpuMicros().getAsLong() <= 0) {
      return EntityFormat.UNKNOWN;
    }
    return "%.1f%%".formatted(100.0 * value.getAsLong() / current.sampledCpuMicros().getAsLong());
  }

  private static String averageCores(StarlarkProfileReader.Summary summary) {
    if (summary.sampledCpuMicros().isEmpty()
        || summary.wallDurationMicros().isEmpty()
        || summary.wallDurationMicros().getAsLong() <= 0) {
      return EntityFormat.UNKNOWN;
    }
    return "%.2f"
        .formatted(
            (double) summary.sampledCpuMicros().getAsLong()
                / summary.wallDurationMicros().getAsLong());
  }

  private static String flameStatus(StarlarkProfileReader.FlameSlice loaded) {
    String status =
        "Loaded "
            + EntityFormat.count(loaded.nodes().size())
            + " of "
            + EntityFormat.count(loaded.totalNodeCount())
            + " call contexts";
    if (loaded.omittedNodeCount() > 0) {
      status +=
          "; "
              + EntityFormat.count(loaded.omittedNodeCount())
              + " are not drawn at the current "
              + EntityFormat.count(DEFAULT_FLAME_NODE_LIMIT)
              + "-context limit";
    }
    return status + ". The table data remains queryable; focus a context to narrow the view.";
  }

  private static String directedGraphStatus(
      StarlarkProfileReader.DirectedCallGraph graph,
      int nodeLimit,
      int edgeLimit,
      StarlarkCallGraphLayout.NodeWeight nodeWeight) {
    StringBuilder status = new StringBuilder();
    if (graph.omittedFunctionCount() == 0) {
      status
          .append("Showing all ")
          .append(EntityFormat.count(graph.totalFunctionCount()))
          .append(" functions.");
    } else {
      status
          .append("Showing ")
          .append(EntityFormat.count(graph.nodes().size()))
          .append(" of ")
          .append(EntityFormat.count(graph.totalFunctionCount()))
          .append(" functions by cumulative sampled CPU; ")
          .append(EntityFormat.count(graph.omittedFunctionCount()))
          .append(" are outside the current ")
          .append(EntityFormat.count(nodeLimit))
          .append("-function projection.");
    }
    if (graph.omittedVisibleEdgeCount() == 0) {
      status
          .append(" Drawing all ")
          .append(EntityFormat.count(graph.visibleEdgeCount()))
          .append(" call arrows between the shown functions.");
    } else {
      status
          .append(" Drawing ")
          .append(EntityFormat.count(graph.edges().size()))
          .append(" of ")
          .append(EntityFormat.count(graph.visibleEdgeCount()))
          .append(" call arrows between the shown functions; ")
          .append(EntityFormat.count(graph.omittedVisibleEdgeCount()))
          .append(" lower-CPU arrows are outside the current ")
          .append(EntityFormat.count(edgeLimit))
          .append("-arrow projection.");
    }
    if (graph.omittedFunctionCount() > 0) {
      status.append(
          " Relationships touching omitted functions are not part of that" + " arrow total.");
    }
    return status
        .append(" Box size is ")
        .append(nodeWeight)
        .append(
            "; box heat is cumulative CPU; arrow width, colour, and labels are"
                + " relationship CPU. An unavailable size value uses a neutral box size."
                + " Drag a function to move it, drag empty space to pan, scroll to zoom,"
                + " and right-click a function for source.")
        .toString();
  }

  private Optional<StarlarkProfileReader.HotFunction> selectedFunctionRow() {
    int row = functionTable.getSelectedRow();
    if (row < 0 || functionModel == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(functionModel.rowAt(functionTable.convertRowIndexToModel(row)));
  }

  private Optional<StarlarkProfileReader.SourceFile> selectedFileRow() {
    int row = fileTable.getSelectedRow();
    if (row < 0 || fileModel == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(fileModel.rowAt(fileTable.convertRowIndexToModel(row)));
  }

  private static Optional<StarlarkProfileReader.CallEdge> selectedEdgeRow(
      JTable table, PagedTableModel<StarlarkProfileReader.CallEdge> model) {
    int row = table.getSelectedRow();
    if (row < 0 || model == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(model.rowAt(table.convertRowIndexToModel(row)));
  }

  private MouseAdapter openSourceMouseListener(SourceSelection selection) {
    return new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent event) {
        popup(event);
      }

      @Override
      public void mouseReleased(MouseEvent event) {
        popup(event);
      }

      @Override
      public void mouseClicked(MouseEvent event) {
        if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
          selection.source().ifPresent(sourceListener);
        }
      }

      private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
          return;
        }
        JTable table = (JTable) event.getComponent();
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0) {
          table.setRowSelectionInterval(row, row);
        }
        Optional<StarlarkProfileReader.SourceLocation> source = selection.source();
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem("Open source file…");
        open.setEnabled(source.isPresent());
        open.addActionListener(ignored -> source.ifPresent(sourceListener));
        menu.add(open);
        menu.show(table, event.getX(), event.getY());
      }
    };
  }

  private void showWorkerFailure(
      long wantedSession,
      long wantedQuery,
      long currentQuery,
      StarlarkProfileReader active,
      JTextArea target,
      String prefix,
      RuntimeException failure) {
    SwingUtilities.invokeLater(
        () -> {
          if (isCurrent(wantedSession, wantedQuery, currentQuery, active)) {
            target.setText(prefix + ": " + describe(failure));
          }
        });
  }

  private boolean isCurrent(
      long wantedSession, long wantedQuery, long currentQuery, StarlarkProfileReader active) {
    return wantedSession == generation && wantedQuery == currentQuery && reader == active;
  }

  private static JPanel toolbar(Component... components) {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
    for (Component component : components) {
      panel.add(component);
    }
    return panel;
  }

  private static void configureTable(JTable table) {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setFillsViewportHeight(true);
  }

  private void configureFunctionColumns() {
    int[] widths = {260, 360, 60, 105, 70, 120, 90, 100, 80};
    applyWidths(functionTable, widths);
  }

  private void configureFileColumns() {
    int[] widths = {500, 105, 70, 120, 90, 90, 90};
    applyWidths(fileTable, widths);
  }

  private static void configureEdgeColumns(JTable table) {
    int[] widths = {260, 360, 105, 75, 90};
    applyWidths(table, widths);
  }

  private static void applyWidths(JTable table, int[] widths) {
    for (int index = 0; index < widths.length && index < table.getColumnCount(); index++) {
      table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
    }
  }

  private static DefaultTableModel emptyModel(String... columns) {
    return new DefaultTableModel(columns, 0) {
      private static final long serialVersionUID = 1L;

      @Override
      public boolean isCellEditable(int row, int column) {
        return false;
      }
    };
  }

  private void showEmpty(String message) {
    emptyState.setText(message);
    cards.show(deck, CARD_EMPTY);
    syncPageMetadata(message.startsWith("No session") ? "" : message, message);
  }

  private void syncPageMetadata(String concise, String detail) {
    if (pageToolbar != null) {
      pageToolbar.setMetadata(concise, detail);
    }
  }

  private static Timer searchDelay(Runnable action) {
    Timer timer = new Timer(250, event -> action.run());
    timer.setRepeats(false);
    return timer;
  }

  private static DocumentListener documentListener(Timer timer) {
    return new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent event) {
        timer.restart();
      }

      @Override
      public void removeUpdate(DocumentEvent event) {
        timer.restart();
      }

      @Override
      public void changedUpdate(DocumentEvent event) {
        timer.restart();
      }
    };
  }

  private static ExecutorService singleThreadExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "bbv-starlark-profile");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static void closeQuietly(StarlarkProfileReader candidate) {
    if (candidate == null) {
      return;
    }
    try {
      candidate.close();
    } catch (RuntimeException failure) {
      log.debug("could not close a Starlark profile reader", failure);
    }
  }

  private static String describe(Throwable failure) {
    return failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
        ? "unknown error"
        : failure.getMessage();
  }

  // Headless test hooks. All are EDT-only snapshots and perform no I/O.
  String emptyMessageForTest() {
    return emptyState.getText();
  }

  JTabbedPane tabsForTest() {
    return tabs;
  }

  JTable functionTableForTest() {
    return functionTable;
  }

  JTextArea functionStatusForTest() {
    return functionStatus;
  }

  JTextArea summaryDetailForTest() {
    return summaryDetail;
  }

  JScrollPane summaryScrollForTest() {
    return summaryScroll;
  }

  int summaryCardCountForTest() {
    return summaryCards.getComponentCount();
  }

  String summaryCardsTextForTest() {
    StringBuilder text = new StringBuilder();
    appendComponentText(summaryCards, text);
    return text.toString();
  }

  private static void appendComponentText(Component component, StringBuilder text) {
    if (component instanceof JLabel label) {
      text.append(label.getText()).append('\n');
    } else if (component instanceof JTextArea area) {
      text.append(area.getText()).append('\n');
    }
    if (component instanceof JPanel panel) {
      for (Component child : panel.getComponents()) {
        appendComponentText(child, text);
      }
    }
  }

  boolean summaryExplanationVisibleForTest() {
    return summaryDetailPane.isVisible();
  }

  StarlarkFlameGraph flameGraphForTest() {
    return flameGraph;
  }

  JTextArea flameStatusForTest() {
    return flameStatus;
  }

  JTextArea fileStatusForTest() {
    return fileStatus;
  }

  JTextArea callGraphStatusForTest() {
    return callGraphStatus;
  }

  JTextArea callGraphSourceForTest() {
    return callGraphSource;
  }

  StarlarkCallGraph directedCallGraphForTest() {
    return directedCallGraph;
  }

  JTextArea directedCallGraphStatusForTest() {
    return directedCallGraphStatus;
  }

  void openSelectedFunctionSourceForTest() {
    selectedFunctionRow()
        .flatMap(StarlarkProfileReader.HotFunction::source)
        .ifPresent(sourceListener);
  }

  private interface SourceSelection {
    Optional<StarlarkProfileReader.SourceLocation> source();
  }
}
