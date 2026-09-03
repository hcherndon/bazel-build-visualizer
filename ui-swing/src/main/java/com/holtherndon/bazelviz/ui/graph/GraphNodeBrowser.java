package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * The browsable list of a graph's nodes: filter on top, tree below.
 *
 * <p>The same shape as the Query card's schema browser, rebuilt here rather than imported because
 * that one is package-private to {@code ui.query} and describes tables, not graphs. The pattern is
 * what carries over: a filter field, a tree whose root states the totals, a summary line that
 * admits when the listing is filtered or truncated, and a double-click that chooses.
 *
 * <h2>Holds no reader and no connection</h2>
 *
 * <p>Like the schema browser, this is handed a finished list on the event thread. Typing in the
 * filter cannot query anything from here — it reports the text through {@link #onFilterChanged},
 * and the view runs the search on its worker and calls {@link #show} with the answer. That keeps
 * every query off the EDT and keeps this class paintable from what it holds.
 *
 * <h2>The listing is bounded, and says so</h2>
 *
 * <p>A build has tens of thousands of actions; the browser lists at most what the view's browse
 * limit allows and the summary states "first N of M" when the graph holds more, because a
 * complete-looking list that silently stopped would claim the unlisted nodes do not exist.
 */
final class GraphNodeBrowser extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JTextField filter = new JTextField();
  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("No graph loaded");
  private final DefaultTreeModel model = new DefaultTreeModel(root);
  private final JTree tree = new JTree(model);
  private final JLabel summary = new JLabel(" ");

  private IntConsumer nodeChosen = nodeIndex -> {};
  private Consumer<String> filterChanged = text -> {};

  GraphNodeBrowser() {
    super(new BorderLayout(0, 4));
    PlainText.install(tree);
    PlainText.disableHtml(summary);
    PlainText.disableHtml(filter);
    tree.setRootVisible(true);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2) {
              chooseSelected();
            }
          }
        });

    filter.setToolTipText(PlainText.tooltip("Filter the listed nodes by name"));
    filter
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent event) {
                filterChanged.accept(filter.getText());
              }

              @Override
              public void removeUpdate(DocumentEvent event) {
                filterChanged.accept(filter.getText());
              }

              @Override
              public void changedUpdate(DocumentEvent event) {
                filterChanged.accept(filter.getText());
              }
            });

    JPanel head = new JPanel(new BorderLayout(0, 2));
    JLabel caption = PlainText.disableHtml(new JLabel("Browse"));
    head.add(caption, BorderLayout.NORTH);
    head.add(filter, BorderLayout.CENTER);
    head.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

    summary.setEnabled(false);
    summary.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

    add(head, BorderLayout.NORTH);
    add(new JScrollPane(tree), BorderLayout.CENTER);
    add(summary, BorderLayout.SOUTH);
    setPreferredSize(new Dimension(260, 200));
  }

  /** Called with a graph node index when the user double-clicks an entry. */
  void onNodeChosen(IntConsumer handler) {
    this.nodeChosen = Objects.requireNonNull(handler, "handler");
  }

  /** Called with the filter text whenever it changes; the view queries. */
  void onFilterChanged(Consumer<String> handler) {
    this.filterChanged = Objects.requireNonNull(handler, "handler");
  }

  /**
   * Replaces the listing. EDT only; the list arrives from a worker.
   *
   * @param nodes at most {@code limit} entries, in the order the search returned them
   * @param noun what one node is called, "action" or "target"
   * @param limit how many the view asked for; more matches exist when {@code truncated}
   * @param truncated true when the search stopped at the limit
   * @param graphTotal the whole graph's node count, when the session knows it, so the summary can
   *     name what the listing is a fraction of
   */
  void show(
      List<GraphQueries.GraphNode> nodes,
      String noun,
      int limit,
      boolean truncated,
      OptionalLong graphTotal) {
    root.removeAllChildren();
    // Grouped by package, the way a person scans a build: the label's text
    // before the colon, with the unnamed in a group that says so rather
    // than a package called nothing (plan 11.4).
    Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
    for (GraphQueries.GraphNode node : nodes) {
      String pack = node.label().map(GraphNodeBrowser::packageOf).orElse("(name not recorded)");
      DefaultMutableTreeNode group =
          groups.computeIfAbsent(
              pack,
              name -> {
                DefaultMutableTreeNode made = new DefaultMutableTreeNode(name);
                root.add(made);
                return made;
              });
      group.add(new DefaultMutableTreeNode(new Entry(node)));
    }
    String total = graphTotal.isPresent() ? " of " + graphTotal.getAsLong() + " in this graph" : "";
    root.setUserObject(
        nodes.isEmpty()
            ? "No " + noun + " matches"
            : (truncated ? "First " + nodes.size() : nodes.size())
                + " "
                + noun
                + (nodes.size() == 1 ? "" : "s")
                + total);
    model.reload();
    tree.expandPath(new TreePath(root));
    if (nodes.isEmpty()) {
      summary.setText("Nothing matches. Clear or change the filter.");
    } else if (truncated) {
      // Rule 12: a truncated listing must say it stopped, or the last
      // row reads as the last node.
      summary.setText("Only the first " + limit + " matches are listed. Filter to narrow.");
    } else {
      summary.setText("Double-click a " + noun + " to draw around it.");
    }
  }

  /** Clears the listing, for a session being closed. */
  void clear() {
    root.removeAllChildren();
    root.setUserObject("No graph loaded");
    model.reload();
    summary.setText(" ");
  }

  /** The label's package: the text before the colon. */
  private static String packageOf(String label) {
    int colon = label.lastIndexOf(':');
    return colon <= 0 ? label : label.substring(0, colon);
  }

  private void chooseSelected() {
    TreePath path = tree.getSelectionPath();
    if (path == null) {
      return;
    }
    Object last = path.getLastPathComponent();
    if (last instanceof DefaultMutableTreeNode node
        && node.getUserObject() instanceof Entry entry) {
      nodeChosen.accept(entry.node.nodeIndex());
    }
  }

  /** One listed node; renders as its exact, distinct name. */
  private static final class Entry {

    private final GraphQueries.GraphNode node;

    Entry(GraphQueries.GraphNode node) {
      this.node = node;
    }

    @Override
    public String toString() {
      String distinct =
          GraphQueries.composeDisplayLabel(
              node.mnemonic().orElse(null), node.primaryOutput().orElse(null), null);
      String label = node.label().orElse(null);
      if (label == null) {
        return distinct == null ? "(name not recorded)" : distinct;
      }
      String name = label.substring(label.lastIndexOf(':') + 1);
      return distinct == null ? name : name + "  (" + distinct + ")";
    }
  }

  // -------------------------------------------------------------- testing

  /** The filter box's current text. */
  String filterText() {
    return filter.getText().trim();
  }

  /** Visible for testing: drives the filter box as a user would. */
  void filterForTesting(String text) {
    filter.setText(text);
  }

  /** Visible for testing: the root label. */
  String rootLabelForTesting() {
    return String.valueOf(root.getUserObject());
  }

  /** Visible for testing: the line under the tree. */
  String summaryForTesting() {
    return summary.getText();
  }

  /** Visible for testing: every leaf entry's text, in listed order. */
  List<String> listedEntriesForTesting() {
    List<String> entries = new ArrayList<>();
    for (int g = 0; g < root.getChildCount(); g++) {
      DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(g);
      for (int i = 0; i < group.getChildCount(); i++) {
        entries.add(String.valueOf(((DefaultMutableTreeNode) group.getChildAt(i)).getUserObject()));
      }
    }
    return entries;
  }

  /** Visible for testing: chooses the {@code index}th listed leaf. */
  void chooseForTesting(int index) {
    int seen = 0;
    for (int g = 0; g < root.getChildCount(); g++) {
      DefaultMutableTreeNode group = (DefaultMutableTreeNode) root.getChildAt(g);
      for (int i = 0; i < group.getChildCount(); i++) {
        if (seen++ == index) {
          Object entry = ((DefaultMutableTreeNode) group.getChildAt(i)).getUserObject();
          nodeChosen.accept(((Entry) entry).node.nodeIndex());
          return;
        }
      }
    }
  }
}
