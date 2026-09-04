package com.holtherndon.bazelviz.ui.configurations;

import com.holtherndon.bazelviz.storage.entities.ConfigurationQueries;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.session.ViewClose;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import com.holtherndon.bazelviz.ui.table.PagedTableModel;
import com.holtherndon.bazelviz.ui.theme.EmptyStatePanel;
import com.holtherndon.bazelviz.ui.theme.PageChrome;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.SelectableLabel;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import javax.swing.table.DefaultTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Explores captured build configurations and compares their effective options. */
public final class ConfigurationsView extends JPanel implements PageChrome {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(ConfigurationsView.class);
  private static final String CARD_EMPTY = "empty";
  private static final String CARD_CONTENT = "content";

  private final CardLayout cards = new CardLayout();
  private final JPanel deck = new JPanel(cards);
  private final EmptyStatePanel emptyState = new EmptyStatePanel("No session is open.");
  private final JTable configurations = table("configurations.list");
  private final JTable values = table("configurations.values");
  private final JTable differences = table("configurations.differences");
  private final JTextArea details = textArea("configurations.details");
  private final JTextArea compareSummary = textArea("configurations.compareSummary");
  private final JLabel status = new JLabel(" ");
  private final JLabel valueStatus = new JLabel("Select a configuration.");
  private final JTextField baselineLabel = SelectableLabel.create("Not set");
  private final JTextField candidateLabel = SelectableLabel.create("Not selected");
  private final JButton setBaseline = new JButton("Use selected as baseline");
  private final JButton clearBaseline = new JButton("Clear baseline");
  private final JTextArea introduction =
      WrappingLabel.create(
          "Inspect each configuration checksum, where it was used, and the effective"
              + " options cquery reported. Set one configuration as a baseline, then"
              + " select another to compare them.");
  private final JPanel content = new JPanel(new BorderLayout(0, 6));

  private SessionSource source;
  private EntityReader reader;
  private ExecutorService executor;
  private PagedTableModel<ConfigurationQueries.Summary> configurationModel;
  private PagedTableModel<ConfigurationQueries.Value> valueModel;
  private PagedTableModel<ConfigurationQueries.Difference> differenceModel;
  private ConfigurationQueries.Summary selected;
  private ConfigurationQueries.Summary baseline;
  private Optional<ConfigurationQueries.Source> configurationSource = Optional.empty();
  private boolean active;
  private boolean loading;
  private long generation;
  private long valueGeneration;
  private long comparisonGeneration;
  private long revealGeneration;
  private String pendingChecksum;
  private PageToolbar pageToolbar;

  public ConfigurationsView() {
    super(new BorderLayout());
    buildUi();
    configurations
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                selectionChanged();
              }
            });
    setBaseline.addActionListener(event -> setSelectedAsBaseline());
    clearBaseline.addActionListener(
        event -> {
          baseline = null;
          baselineLabel.setText("Not set");
          updateComparison();
        });
    setBaseline.setEnabled(false);
    clearBaseline.setEnabled(false);
  }

  private void buildUi() {
    introduction.setName("configurations.introduction");

    JScrollPane configurationScroll = new JScrollPane(configurations);
    configurationScroll.setMinimumSize(new Dimension(430, 180));

    JTabbedPane detailTabs = new JTabbedPane();
    detailTabs.setName("configurations.detailTabs");
    detailTabs.addTab("Details", new JScrollPane(details));

    JPanel valuesPanel = new JPanel(new BorderLayout());
    valuesPanel.add(new JScrollPane(values), BorderLayout.CENTER);
    JPanel valueBar = new JPanel(new BorderLayout());
    valueBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    valueBar.add(valueStatus, BorderLayout.WEST);
    valuesPanel.add(valueBar, BorderLayout.SOUTH);
    detailTabs.addTab("Effective values", valuesPanel);
    detailTabs.addTab("Compare", comparisonPanel());

    JSplitPane split =
        new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new SectionPane("Configurations", configurationScroll),
            new SectionPane("Configuration details", detailTabs));
    split.setResizeWeight(0.45);

    content.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
    content.add(introduction, BorderLayout.NORTH);
    content.add(split, BorderLayout.CENTER);
    JPanel statusBar = new JPanel(new BorderLayout());
    statusBar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
    statusBar.add(status, BorderLayout.WEST);
    content.add(statusBar, BorderLayout.SOUTH);

    deck.add(emptyState, CARD_EMPTY);
    deck.add(content, CARD_CONTENT);
    add(deck, BorderLayout.CENTER);
    cards.show(deck, CARD_EMPTY);
  }

  /** Moves the page introduction into the shared window chrome. */
  @Override
  public void installPageToolbar(PageToolbar toolbar) {
    Objects.requireNonNull(toolbar, "toolbar");
    if (pageToolbar != null) {
      return;
    }
    pageToolbar = toolbar;
    content.remove(introduction);
    syncPageMetadata("Inspect and compare build configurations", introduction.getText());
    content.revalidate();
    content.repaint();
  }

  private void syncPageMetadata(String concise, String detail) {
    if (pageToolbar != null) {
      pageToolbar.setMetadata(concise, detail);
    }
  }

  private JPanel comparisonPanel() {
    JPanel panel = new JPanel(new BorderLayout(0, 6));
    JPanel choices = new JPanel(new GridBagLayout());
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 5, 3, 5);
    c.anchor = GridBagConstraints.WEST;
    choices.add(new JLabel("Baseline:"), c);
    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    choices.add(baselineLabel, c);
    c.gridx = 0;
    c.gridy = 1;
    c.weightx = 0;
    c.fill = GridBagConstraints.NONE;
    choices.add(new JLabel("Selected:"), c);
    c.gridx = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    choices.add(candidateLabel, c);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    actions.add(setBaseline);
    actions.add(clearBaseline);
    c.gridx = 0;
    c.gridy = 2;
    c.gridwidth = 2;
    c.weightx = 1;
    choices.add(actions, c);

    JPanel header = new JPanel(new BorderLayout());
    header.add(choices, BorderLayout.NORTH);
    header.add(compareSummary, BorderLayout.CENTER);
    panel.add(header, BorderLayout.NORTH);
    panel.add(new JScrollPane(differences), BorderLayout.CENTER);
    return panel;
  }

  /** Starts reading only when this navigation card is visited. */
  public void activate() {
    active = true;
    ensureLoaded();
  }

  /** Selects one exact checksum once the lazy configuration list is ready. */
  public void revealChecksum(String checksum) {
    Objects.requireNonNull(checksum, "checksum");
    if (checksum.isBlank()) {
      throw new IllegalArgumentException("a configuration checksum is required");
    }
    active = true;
    pendingChecksum = checksum;
    long mine = ++revealGeneration;
    ensureLoaded();
    locateChecksum(mine, checksum);
  }

  public void openSession(SessionSource newSource) {
    Objects.requireNonNull(newSource, "newSource");
    closeSession();
    source = newSource;
    executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "bbv-configurations");
              thread.setDaemon(true);
              return thread;
            });
    emptyState.setText("Open Configurations to read configuration checksums.");
    cards.show(deck, CARD_EMPTY);
    ensureLoaded();
  }

  public void closeSession() {
    closeSessionAsync();
  }

  /** Detaches immediately and completes after this session's configuration reads have stopped. */
  public CompletionStage<Void> closeSessionAsync() {
    active = false;
    loading = false;
    generation++;
    valueGeneration++;
    comparisonGeneration++;
    revealGeneration++;
    pendingChecksum = null;
    selected = null;
    baseline = null;
    configurationSource = Optional.empty();
    configurationModel = null;
    valueModel = null;
    differenceModel = null;
    configurations.setModel(emptyModel());
    values.setModel(emptyModel());
    differences.setModel(emptyModel());
    details.setText("");
    compareSummary.setText("");
    baselineLabel.setText("Not set");
    candidateLabel.setText("Not selected");
    setBaseline.setEnabled(false);
    clearBaseline.setEnabled(false);
    emptyState.setText("No session is open.");
    cards.show(deck, CARD_EMPTY);

    ExecutorService stopping = executor;
    EntityReader closing = reader;
    source = null;
    executor = null;
    reader = null;
    if (stopping == null && closing == null) {
      return CompletableFuture.completedFuture(null);
    }
    return ViewClose.runAsync(
        "bbv-configurations-close",
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
        });
  }

  private void ensureLoaded() {
    SessionSource opened = source;
    ExecutorService running = executor;
    if (!active || loading || reader != null || opened == null || running == null) {
      return;
    }
    loading = true;
    long mine = ++generation;
    emptyState.setText("Reading configurations…");
    cards.show(deck, CARD_EMPTY);
    running.execute(
        () -> {
          EntityReader openedReader = null;
          try {
            openedReader = opened.openEntityReader();
            ConfigurationRowSource rows = ConfigurationRowSource.open(openedReader);
            Optional<ConfigurationQueries.Source> sourceStatus = openedReader.configurationSource();
            EntityReader ready = openedReader;
            SwingUtilities.invokeLater(() -> install(mine, opened, ready, rows, sourceStatus));
          } catch (RuntimeException failure) {
            if (openedReader != null) {
              openedReader.close();
            }
            log.warn("could not open configurations", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (mine == generation) {
                    loading = false;
                    emptyState.setText("Could not read configurations: " + failure.getMessage());
                    cards.show(deck, CARD_EMPTY);
                  }
                });
          }
        });
  }

  private void install(
      long mine,
      SessionSource opened,
      EntityReader openedReader,
      ConfigurationRowSource rows,
      Optional<ConfigurationQueries.Source> sourceStatus) {
    if (mine != generation || source != opened) {
      openedReader.close();
      return;
    }
    loading = false;
    reader = openedReader;
    configurationSource = sourceStatus;
    if (rows.rowCount() == 0) {
      emptyState.setText("This session contains no BEP or cquery configurations.");
      cards.show(deck, CARD_EMPTY);
      return;
    }
    configurationModel =
        new PagedTableModel<>(
            rows, summaryColumns(), executor, ConfigurationRowSource.PAGE_SIZE, 12);
    configurationModel.addTableModelListener(event -> selectionChanged());
    configurations.setModel(configurationModel);
    sizeSummaryColumns();
    status.setText(
        EntityFormat.count(rows.rowCount())
            + (rows.rowCount() == 1 ? " configuration" : " configurations")
            + sourceStatus
                .map(value -> " · cquery " + value.state() + ", " + value.configurationMatch())
                .orElse(" · no cquery source"));
    cards.show(deck, CARD_CONTENT);
    if (pendingChecksum != null) {
      locateChecksum(revealGeneration, pendingChecksum);
    } else {
      configurations.setRowSelectionInterval(0, 0);
      // Schedules the first page even in an offscreen window; the read still
      // runs on the executor and selection is described when that page lands.
      configurationModel.getValueAt(0, 0);
    }
  }

  /** Resolves the sorted row off the EDT, then lets the paged model fetch its page. */
  private void locateChecksum(long mine, String checksum) {
    EntityReader current = reader;
    ExecutorService running = executor;
    PagedTableModel<ConfigurationQueries.Summary> model = configurationModel;
    if (current == null || running == null || model == null) {
      return;
    }
    running.execute(
        () -> {
          try {
            OptionalLong position = current.configurationPosition(checksum);
            SwingUtilities.invokeLater(
                () -> {
                  if (mine != revealGeneration
                      || !checksum.equals(pendingChecksum)
                      || model != configurationModel) {
                    return;
                  }
                  if (position.isEmpty()) {
                    pendingChecksum = null;
                    status.setText("Configuration " + checksum + " was not found in this session.");
                    return;
                  }
                  int row = Math.toIntExact(position.getAsLong());
                  pendingChecksum = null;
                  configurations.setRowSelectionInterval(row, row);
                  configurations.scrollRectToVisible(configurations.getCellRect(row, 0, true));
                  model.getValueAt(row, 0);
                });
          } catch (RuntimeException failure) {
            log.warn("could not locate configuration {}", checksum, failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (mine == revealGeneration && checksum.equals(pendingChecksum)) {
                    pendingChecksum = null;
                    status.setText(
                        "Could not locate configuration " + checksum + ": " + failure.getMessage());
                  }
                });
          }
        });
  }

  private void selectionChanged() {
    PagedTableModel<ConfigurationQueries.Summary> model = configurationModel;
    int row = configurations.getSelectedRow();
    if (model == null || row < 0) {
      return;
    }
    ConfigurationQueries.Summary choice = model.rowAt(row);
    if (choice == null || selected != null && selected.checksum().equals(choice.checksum())) {
      return;
    }
    selected = choice;
    setBaseline.setEnabled(true);
    candidateLabel.setText(describeIdentity(choice));
    details.setText(detailText(choice));
    details.setCaretPosition(0);
    loadValues(choice);
    updateComparison();
  }

  private void loadValues(ConfigurationQueries.Summary choice) {
    EntityReader current = reader;
    ExecutorService running = executor;
    if (current == null || running == null) {
      return;
    }
    long mine = ++valueGeneration;
    valueStatus.setText("Reading effective values…");
    values.setModel(emptyModel());
    running.execute(
        () -> {
          try {
            ConfigurationValueRowSource rows =
                ConfigurationValueRowSource.open(current, choice.checksum());
            SwingUtilities.invokeLater(
                () -> {
                  if (mine != valueGeneration || selected != choice) {
                    return;
                  }
                  valueModel =
                      new PagedTableModel<>(
                          rows, valueColumns(), executor, ConfigurationRowSource.PAGE_SIZE, 12);
                  values.setModel(valueModel);
                  sizeValueColumns();
                  valueStatus.setText(valueStatus(choice, rows.rowCount()));
                });
          } catch (RuntimeException failure) {
            log.warn("could not read values for configuration {}", choice.checksum(), failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (mine == valueGeneration) {
                    valueStatus.setText("Could not read values: " + failure.getMessage());
                  }
                });
          }
        });
  }

  private void setSelectedAsBaseline() {
    if (selected == null) {
      return;
    }
    baseline = selected;
    baselineLabel.setText(describeIdentity(baseline));
    clearBaseline.setEnabled(true);
    updateComparison();
  }

  private void updateComparison() {
    long mine = ++comparisonGeneration;
    differenceModel = null;
    differences.setModel(emptyModel());
    ConfigurationQueries.Summary left = baseline;
    ConfigurationQueries.Summary right = selected;
    if (left == null) {
      compareSummary.setText("Choose a configuration in the list, then use it as the baseline.");
      return;
    }
    if (right == null || left.checksum().equals(right.checksum())) {
      compareSummary.setText(
          metadataComparison(left, right)
              + "\n\nSelect a different configuration to compare effective options.");
      return;
    }
    String metadata = metadataComparison(left, right);
    if (!left.optionsAvailable() || !right.optionsAvailable()) {
      compareSummary.setText(
          metadata
              + "\n\nEffective option comparison is unavailable"
              + " because cquery did not publish option details for "
              + (!left.optionsAvailable() && !right.optionsAvailable()
                  ? "either configuration."
                  : "one configuration."));
      return;
    }
    compareSummary.setText(metadata + "\n\nReading effective-option differences…");
    EntityReader current = reader;
    ExecutorService running = executor;
    running.execute(
        () -> {
          try {
            ConfigurationDifferenceRowSource rows =
                ConfigurationDifferenceRowSource.open(current, left.checksum(), right.checksum());
            SwingUtilities.invokeLater(
                () -> {
                  if (mine != comparisonGeneration || baseline != left || selected != right) {
                    return;
                  }
                  differenceModel =
                      new PagedTableModel<>(
                          rows,
                          differenceColumns(),
                          executor,
                          ConfigurationRowSource.PAGE_SIZE,
                          12);
                  differences.setModel(differenceModel);
                  sizeDifferenceColumns();
                  compareSummary.setText(
                      metadata
                          + "\n\n"
                          + (rows.rowCount() == 0
                              ? "No effective-option differences were reported. Distinct"
                                  + " checksums can still reflect fragment or transition"
                                  + " provenance; this is not proof they are safe to merge."
                              : EntityFormat.count(rows.rowCount())
                                  + " effective-option differences. A withheld value is"
                                  + " unknown, not equal. Remove the flag or transition"
                                  + " creating a difference only after checking the rule"
                                  + " semantics."));
                });
          } catch (RuntimeException failure) {
            log.warn("could not compare configurations", failure);
            SwingUtilities.invokeLater(
                () -> {
                  if (mine == comparisonGeneration) {
                    compareSummary.setText(
                        metadata + "\n\nCould not compare options: " + failure.getMessage());
                  }
                });
          }
        });
  }

  private String detailText(ConfigurationQueries.Summary value) {
    String bep =
        !value.bepReported()
            ? "not reported"
            : value.bepDeclared() ? "declared" : "referenced, not declared";
    String options =
        value.optionsAvailable()
            ? EntityFormat.count(value.options())
                + " values across "
                + EntityFormat.count(value.optionSets())
                + " option sets"
            : value.queryReported()
                ? "unavailable — this cquery did not publish effective options"
                : "unavailable — this checksum was not in cquery output";
    String sourceText =
        configurationSource
            .map(
                source ->
                    source.state()
                        + ", "
                        + source.configurationMatch()
                        + source.mismatchDetail().map(detail -> "\n  " + detail).orElse("")
                        + source.error().map(error -> "\n  " + error).orElse(""))
            .orElse("not captured");
    return "Identity\n"
        + "Checksum: "
        + value.checksum()
        + "\n"
        + "Mnemonic: "
        + show(value.mnemonic())
        + "\n"
        + "Platform: "
        + show(value.platform())
        + "\n"
        + "CPU: "
        + show(value.cpu())
        + "\n"
        + "Tool configuration: "
        + value.tool().map(Object::toString).orElse("unknown")
        + "\n\nCapture sources\n"
        + "BEP configuration: "
        + bep
        + "\n"
        + "Configured-target cquery: "
        + (value.queryReported() ? "reported" : "not reported")
        + "\n"
        + "cquery source: "
        + sourceText
        + "\n\nUsage\n"
        + "Top-level configured target records (BEP): "
        + EntityFormat.count(value.bepTargets())
        + "\n"
        + "Configured target nodes (cquery): "
        + EntityFormat.count(value.queriedTargets())
        + "\n"
        + "Executed actions: "
        + EntityFormat.count(value.executedActions())
        + "\n\n"
        + "Configuration values\n"
        + "Effective options: "
        + options
        + "\n"
        + "Fragments: "
        + (value.queryReported() ? EntityFormat.count(value.fragments()) : "unknown")
        + "\n"
        + "BEP make variables: "
        + EntityFormat.count(value.makeVariables())
        + "\n\n"
        + "Multiple configurations are normal when platforms, command-line flags,"
        + " tool/exec transitions, or rule transitions differ. The comparison shows"
        + " recorded differences; it cannot prove that removing a transition is safe.";
  }

  private static String metadataComparison(
      ConfigurationQueries.Summary left, ConfigurationQueries.Summary right) {
    if (right == null) {
      return "Baseline: " + describeIdentity(left);
    }
    return "Metadata\n"
        + comparisonLine("Mnemonic", left.mnemonic(), right.mnemonic())
        + "\n"
        + comparisonLine("Platform", left.platform(), right.platform())
        + "\n"
        + comparisonLine("CPU", left.cpu(), right.cpu())
        + "\n"
        + comparisonLine(
            "Tool configuration",
            left.tool().map(Object::toString),
            right.tool().map(Object::toString));
  }

  private static String comparisonLine(String name, Optional<String> left, Optional<String> right) {
    String a = show(left);
    String b = show(right);
    return name + ": " + (a.equals(b) ? a + " (same)" : a + " → " + b);
  }

  private static String valueStatus(ConfigurationQueries.Summary value, long rows) {
    String count = EntityFormat.count(rows) + (rows == 1 ? " value" : " values");
    if (!value.optionsAvailable()) {
      return count
          + " · effective options unavailable; any listed values are BEP make"
          + " variables";
    }
    return count + " · effective cquery options and BEP make variables";
  }

  private static List<ColumnSpec<ConfigurationQueries.Summary>> summaryColumns() {
    return List.of(
        new ColumnSpec<>("Configuration", ConfigurationsView::describeIdentity),
        new ColumnSpec<>("Checksum", ConfigurationQueries.Summary::checksum),
        new ColumnSpec<>("Platform", value -> show(value.platform())),
        new ColumnSpec<>("BEP targets", ConfigurationQueries.Summary::bepTargets),
        new ColumnSpec<>("All targets", ConfigurationQueries.Summary::queriedTargets),
        new ColumnSpec<>("Actions", ConfigurationQueries.Summary::executedActions),
        new ColumnSpec<>(
            "Options",
            value ->
                value.optionsAvailable() ? EntityFormat.count(value.options()) : "unavailable"));
  }

  private static List<ColumnSpec<ConfigurationQueries.Value>> valueColumns() {
    return List.of(
        new ColumnSpec<>("Kind", ConfigurationQueries.Value::kind),
        new ColumnSpec<>("Group", ConfigurationQueries.Value::group),
        new ColumnSpec<>("Name", ConfigurationQueries.Value::name),
        new ColumnSpec<>("Value", value -> displayValue(value.value(), value.withheld(), true)));
  }

  private static List<ColumnSpec<ConfigurationQueries.Difference>> differenceColumns() {
    return List.of(
        new ColumnSpec<>("Change", ConfigurationQueries.Difference::change),
        new ColumnSpec<>("Option set", ConfigurationQueries.Difference::group),
        new ColumnSpec<>("Option", ConfigurationQueries.Difference::name),
        new ColumnSpec<>(
            "Baseline",
            value ->
                displayValue(
                    value.baselineValue(), value.baselineWithheld(), value.baselinePresent())),
        new ColumnSpec<>(
            "Selected",
            value ->
                displayValue(
                    value.candidateValue(), value.candidateWithheld(), value.candidatePresent())));
  }

  private static JTable table(String name) {
    JTable table = new JTable(emptyModel());
    table.setName(name);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoCreateRowSorter(false);
    table.setFillsViewportHeight(true);
    PlainText.install(table);
    return table;
  }

  private static JTextArea textArea(String name) {
    JTextArea text = new JTextArea();
    text.setName(name);
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setOpaque(false);
    text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    return text;
  }

  private static DefaultTableModel emptyModel() {
    return new DefaultTableModel();
  }

  private static String describeIdentity(ConfigurationQueries.Summary value) {
    return value.mnemonic().orElse("Configuration") + " · " + shortChecksum(value.checksum());
  }

  private static String shortChecksum(String checksum) {
    return checksum.length() <= 12 ? checksum : checksum.substring(0, 12) + "…";
  }

  private static String displayValue(Optional<String> value, boolean withheld, boolean present) {
    if (!present) {
      return "not present";
    }
    if (withheld) {
      return "<withheld>";
    }
    return value.orElse("unknown");
  }

  private static String show(Optional<String> value) {
    return value.orElse("unknown");
  }

  private void sizeSummaryColumns() {
    int[] widths = {190, 190, 180, 85, 85, 75, 90};
    sizeColumns(configurations, widths);
  }

  private void sizeValueColumns() {
    sizeColumns(values, new int[] {115, 170, 190, 360});
  }

  private void sizeDifferenceColumns() {
    sizeColumns(differences, new int[] {120, 160, 190, 290, 290});
  }

  private static void sizeColumns(JTable table, int[] widths) {
    for (int index = 0; index < widths.length && index < table.getColumnCount(); index++) {
      table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
    }
  }

  // Focused test accessors.
  JTable configurationTableForTest() {
    return configurations;
  }

  JTable valueTableForTest() {
    return values;
  }

  JTable differenceTableForTest() {
    return differences;
  }

  String detailsForTest() {
    return details.getText();
  }

  String comparisonForTest() {
    return compareSummary.getText();
  }

  JButton baselineButtonForTest() {
    return setBaseline;
  }

  String statusForTest() {
    return status.getText();
  }

  JPanel emptyStateForTest() {
    return emptyState;
  }

  String emptyTextForTest() {
    return emptyState.getText();
  }
}
