package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.core.graph.GraphKind;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.graph.GraphIndexBuilder;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.QueryReader;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Tree card's source selector as a control: one card, two graphs, and every question asked of
 * the one the selector names.
 *
 * <p>The selector used to be a caption — it described a graph and changed nothing, and the trees
 * traversed the action graph whatever it said. These tests open a session holding <em>both</em>
 * graphs and check that searching and tree expansion follow the selection, and that the trees'
 * direction matches their titles. The canvas that once shared this card has its own — {@code
 * GraphExplorerViewTest} covers its half of the selector contract.
 *
 * <h2>The fixture</h2>
 *
 * <p>An action chain {@code //pkg:t0 → //pkg:t1 → //pkg:t2} (producer-to-consumer) and a
 * configured-target graph with the same three labels plus {@code //pkg:libextra}, a target only the
 * cquery knows — which is what proves a label-graph search is not an action-graph search wearing a
 * different name.
 */
final class TreeViewSourceTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private TreeView view;

  @BeforeEach
  void buildSession() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    Connection connection = database.writerConnection();
    exec(
        connection,
        "INSERT INTO graph_sources (id, kind, state, configuration_match,"
            + " target_scope,"
            + " declared_actions, correlated_actions, unresolved_artifacts,"
            + " unresolved_depset_references)"
            + " VALUES (1, 'DECLARED_ACTIONS', 'SUCCEEDED', 'EXACT',"
            + " 'EXACT_BEP_TARGETS', 3, 0, 0, 0)");
    exec(
        connection,
        "INSERT INTO graph_sources (id, kind, state, configuration_match,"
            + " target_scope, declared_actions)"
            + " VALUES (2, 'CONFIGURED_TARGETS', 'SUCCEEDED', 'EXACT',"
            + " 'EXACT_BEP_TARGETS', 4)");
    exec(connection, "INSERT INTO mnemonics (id, value) VALUES (1, 'Genrule')");
    for (int i = 0; i < 3; i++) {
      exec(
          connection,
          "INSERT INTO labels (id, value) VALUES (" + (i + 1) + ", '//pkg:t" + i + "')");
      exec(
          connection,
          "INSERT INTO declared_actions"
              + " (id, source_id, graph_id, label_id, mnemonic_id, node_index)"
              + " VALUES ("
              + (i + 1)
              + ", 1, "
              + i
              + ", "
              + (i + 1)
              + ", 1, "
              + i
              + ")");
    }
    exec(connection, "INSERT INTO labels (id, value) VALUES (4, '//pkg:libextra')");
    exec(
        connection,
        "INSERT INTO action_edges (producer_id, consumer_id, derivation)"
            + " VALUES (1, 2, 'DECLARED'), (2, 3, 'DECLARED')");
    exec(
        connection,
        "INSERT INTO configured_target_nodes (id, source_id, label_id,"
            + " rule_class) VALUES (1, 2, 1, 'genrule'), (2, 2, 2, 'genrule'),"
            + " (3, 2, 3, 'genrule'), (4, 2, 4, 'cc_library')");
    exec(
        connection,
        "INSERT INTO configured_target_edges (from_node_id, to_label_id,"
            + " attribute) VALUES (2, 1, 'srcs'), (3, 2, 'srcs')");
    GraphIndexBuilder builder = new GraphIndexBuilder(connection, tempDir.resolve("indexes"));
    builder.build(EdgeDerivation.DECLARED);
    builder.buildConfiguredTargets();

    view = new TreeView();
    view.setSize(1000, 700);
    view.openSession(new GraphOnlySource());
    awaitCondition(() -> view.sourceSelector().getItemCount() == 2, "the sources to install");
  }

  @AfterEach
  void closeSession() throws Exception {
    view.closeSession();
    database.close();
  }

  @Test
  @DisplayName("the trustworthy action graph is what a user who chooses nothing reads")
  void actionGraphIsPreferred() {
    PageToolbar toolbar = new PageToolbar("Tree");
    view.installPageToolbar(toolbar);

    assertThat(view.shownGraphForTesting()).isEqualTo(GraphKind.DECLARED_ACTIONS);
    assertThat(view.detailLabel().getText()).contains("A node is one declared action");
    assertThat(toolbar.actionCount()).isEqualTo(5);
    assertThat(toolbar.metadata()).containsIgnoringCase("action");
  }

  @Test
  @DisplayName("tree explanations wrap and every text field has an accessible label")
  void explanationsAndLabelsAreWidthSafe() {
    assertWrapping(view.detailLabel());
    assertWrapping(view.warningLabel());

    assertThat(namedLabel("tree.graphLabel").getLabelFor()).isSameAs(view.sourceSelector());
    assertThat(namedLabel("tree.findLabel").getLabelFor()).isNotNull();
    assertThat(namedLabel("tree.pathFromLabel").getLabelFor()).isNotNull();
    assertThat(namedLabel("tree.pathToLabel").getLabelFor()).isNotNull();
  }

  @Test
  @DisplayName("the Depends on tree lists what the root needs, not what needs it")
  void dependsOnMeansDependsOn() throws Exception {
    searchOnEdt("t1");
    awaitCondition(
        () -> rootLabel(view.dependenciesTree()).contains("//pkg:t1"), "the trees to root at t1");

    // t0 feeds t1 feeds t2. Before the direction fix this tree showed t2
    // here — the thing that depends on t1 — under the title "Depends on".
    awaitCondition(
        () -> childLabels(view.dependenciesTree()).contains("//pkg:t0"),
        "the dependency children to load");
    assertThat(childLabels(view.dependenciesTree()))
        .contains("//pkg:t0")
        .doesNotContain("//pkg:t2");
    awaitCondition(
        () -> childLabels(view.dependentsTree()).contains("//pkg:t2"),
        "the dependent children to load");
    assertThat(childLabels(view.dependentsTree())).contains("//pkg:t2").doesNotContain("//pkg:t0");
  }

  @Test
  @DisplayName("switching the source switches what every control talks to")
  void selectorSwitchesTheGraph() throws Exception {
    selectConfiguredTargets();

    assertThat(view.shownGraphForTesting()).isEqualTo(GraphKind.CONFIGURED_TARGETS);
    // The sentence under the selector says what a node now means, because
    // the two graphs share label text and nothing else (rule 13).
    assertThat(view.detailLabel().getText())
        .contains("4 configured targets were analysed")
        .contains("A node is one target label");
  }

  @Test
  @DisplayName("a label search over the target graph finds what no aquery declared")
  void labelSearchIsRealLabelSearch() throws Exception {
    selectConfiguredTargets();

    searchOnEdt("libextra");
    awaitCondition(
        () -> rootLabel(view.dependenciesTree()).contains("libextra"),
        "the trees to root at the label");

    // A target with no actions exists only in the label graph, and its
    // row does not carry the action-graph "not executed" marker: a label
    // is never executed, so the claim has no meaning here.
    assertThat(rootLabel(view.dependenciesTree()))
        .contains("//pkg:libextra")
        .doesNotContain("not executed");
  }

  @Test
  @DisplayName("deps and rdeps of a target expand over labels")
  void labelTreesTraverseLabels() throws Exception {
    selectConfiguredTargets();

    searchOnEdt("t1");
    awaitCondition(
        () -> rootLabel(view.dependenciesTree()).contains("//pkg:t1"), "the trees to root at t1");
    awaitCondition(
        () -> childLabels(view.dependenciesTree()).contains("//pkg:t0"),
        "the label-graph dependency children");

    assertThat(childLabels(view.dependenciesTree())).contains("//pkg:t0");
    awaitCondition(
        () -> childLabels(view.dependentsTree()).contains("//pkg:t2"),
        "the label-graph dependent children");
    assertThat(childLabels(view.dependentsTree())).contains("//pkg:t2");
  }

  @Test
  @DisplayName("a selection from the last session cannot leak into the next")
  void selectionDoesNotLeakAcrossSessions() throws Exception {
    selectConfiguredTargets();
    assertThat(view.shownGraphForTesting()).isEqualTo(GraphKind.CONFIGURED_TARGETS);

    // Session B prefers the configured-target source too: with a stale
    // selection surviving closeSession, the change detection would see
    // "no change" and skip the switch entirely — which is why
    // closeSession resets the shown graph to the default.
    exec(
        database.writerConnection(),
        "UPDATE graph_sources SET state = 'FAILED' WHERE kind = 'DECLARED_ACTIONS'");
    SwingUtilities.invokeAndWait(
        () -> {
          view.closeSession();
          view.openSession(new GraphOnlySource());
        });
    awaitCondition(
        () -> view.sourceSelector().getItemCount() == 2, "the second session's sources to install");

    assertThat(view.shownGraphForTesting())
        .as("the preferred source of the new session is what is shown")
        .isEqualTo(GraphKind.CONFIGURED_TARGETS);
    // And the path pane's title follows, which is the visible face of the
    // same selection.
    assertThat(view.detailLabel().getText()).contains("A node is one target label");
  }

  @Test
  @DisplayName("replacement waits for active graph work and drops its stale tree delivery")
  void replacementFencesActiveGraphWorkAndDropsStaleDelivery() throws Exception {
    view.closeSessionAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
    BlockingGraphSource stale = new BlockingGraphSource();
    GraphOnlySource replacement = new GraphOnlySource();
    try {
      SwingUtilities.invokeAndWait(() -> view.openSession(stale));
      awaitCondition(() -> view.sourceSelector().getItemCount() == 2, "the blocking source");

      stale.armNextQuery();
      searchOnEdt("t1");
      stale.awaitQuery();

      AtomicReference<CompletableFuture<Void>> closing = new AtomicReference<>();
      SwingUtilities.invokeAndWait(
          () -> {
            closing.set(view.closeSessionAsync().toCompletableFuture());
            view.openSession(replacement);
          });
      SwingUtilities.invokeAndWait(() -> {});

      assertThat(closing.get()).isNotDone();
      assertThat(stale.connection().isClosed()).isFalse();
      assertThat(view.sourceSelector().getItemCount())
          .as("the replacement waits for the old reader's termination fence")
          .isZero();

      stale.releaseQuery();
      closing.get().get(10, TimeUnit.SECONDS);
      awaitCondition(
          () -> view.sourceSelector().getItemCount() == 2, "the replacement source to install");

      assertThat(stale.connection().isClosed()).isTrue();
      assertThat(rootLabel(view.dependenciesTree()))
          .as("the old search callback cannot overwrite the replacement session")
          .isEqualTo("Dependencies");
    } finally {
      stale.releaseQuery();
    }
  }

  // ------------------------------------------------------------- plumbing

  private void searchOnEdt(String pattern) throws Exception {
    SwingUtilities.invokeAndWait(() -> view.searchForTesting(pattern));
  }

  private void selectConfiguredTargets() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (int i = 0; i < view.sourceSelector().getItemCount(); i++) {
            GraphQueries.GraphSource source = view.sourceSelector().getItemAt(i);
            if (source.graphKind().filter(GraphKind.CONFIGURED_TARGETS::equals).isPresent()) {
              view.sourceSelector().setSelectedIndex(i);
              return;
            }
          }
          throw new AssertionError("no configured-target source installed");
        });
  }

  private static String rootLabel(JTree tree) {
    Object root = tree.getModel().getRoot();
    return String.valueOf(root);
  }

  private JLabel namedLabel(String name) {
    return allComponents(view).stream()
        .filter(JLabel.class::isInstance)
        .map(JLabel.class::cast)
        .filter(label -> name.equals(label.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No JLabel named " + name));
  }

  private static List<Component> allComponents(Container root) {
    List<Component> result = new ArrayList<>();
    for (Component child : root.getComponents()) {
      result.add(child);
      if (child instanceof Container nested) {
        result.addAll(allComponents(nested));
      }
    }
    return result;
  }

  private static void assertWrapping(JTextArea area) {
    assertThat(area.getLineWrap()).isTrue();
    assertThat(area.getWrapStyleWord()).isTrue();
    assertThat(area.isFocusable()).isTrue();
    assertThat(area.getMinimumSize().width).isZero();
  }

  /** Every child row of the root, joined, so containment means substring. */
  private static String childLabels(JTree tree) {
    Object root = tree.getModel().getRoot();
    if (!(root instanceof DefaultMutableTreeNode node)) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < node.getChildCount(); i++) {
      out.append(String.valueOf(node.getChildAt(i))).append('\n');
    }
    return out.toString();
  }

  private void awaitCondition(BooleanSupplier done, String what) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      SwingUtilities.invokeAndWait(() -> {});
      if (done.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("timed out waiting for " + what);
  }

  private static void exec(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** A session source that answers only the graph question. */
  private class GraphOnlySource implements SessionSource {

    @Override
    public GraphQueries openGraphQueries() {
      try {
        return new GraphQueries(database.newReadConnection(), tempDir.resolve("indexes"));
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public SessionInfo info() {
      throw new UnsupportedOperationException("the graph view never asks");
    }

    @Override
    public SessionReader openReader() {
      throw new UnsupportedOperationException("the graph view never asks");
    }

    @Override
    public EntityReader openEntityReader() {
      throw new UnsupportedOperationException("the graph view never asks");
    }

    @Override
    public MetricQueries openMetricQueries() {
      throw new UnsupportedOperationException("the graph view never asks");
    }

    @Override
    public QueryReader openQueryReader() {
      throw new UnsupportedOperationException("the graph view never asks");
    }

    @Override
    public Connection openTimelineConnection() {
      throw new UnsupportedOperationException("the graph view never asks");
    }

    @Override
    public void close() {
      // The view closes the readers it opened; nothing else to release.
    }
  }

  private final class BlockingGraphSource extends GraphOnlySource {

    private final AtomicBoolean blockNextQuery = new AtomicBoolean();
    private final CountDownLatch queryEntered = new CountDownLatch(1);
    private final CountDownLatch releaseQuery = new CountDownLatch(1);
    private Connection connection;

    @Override
    public GraphQueries openGraphQueries() {
      try {
        Connection delegate = database.newReadConnection();
        connection =
            (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                      if (method.getName().equals("prepareStatement")
                          && blockNextQuery.compareAndSet(true, false)) {
                        queryEntered.countDown();
                        boolean interrupted = false;
                        while (releaseQuery.getCount() != 0) {
                          try {
                            releaseQuery.await();
                          } catch (InterruptedException cancelled) {
                            interrupted = true;
                          }
                        }
                        if (interrupted) {
                          Thread.currentThread().interrupt();
                        }
                      }
                      try {
                        return method.invoke(delegate, arguments);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        return new GraphQueries(connection, tempDir.resolve("indexes"));
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    void armNextQuery() {
      blockNextQuery.set(true);
    }

    void awaitQuery() throws InterruptedException {
      assertThat(queryEntered.await(10, TimeUnit.SECONDS)).isTrue();
    }

    void releaseQuery() {
      releaseQuery.countDown();
    }

    Connection connection() {
      return connection;
    }
  }
}
