package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.storage.query.SchemaColumn;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
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
 * The tables, views and columns of the open session, read from the file.
 *
 * <h2>Why the feature is unusable without this</h2>
 *
 * <p>A session database carries many tables across evolving schema versions and this
 * repository has no generated schema page — the DDL in {@code storage-sqlite}
 * and its javadoc are the only description of it, and neither is reachable from
 * the application. A query surface with no way to see column names is a query
 * surface for somebody who already has the source open.
 *
 * <p>So the tree is built from {@code sqlite_master} and
 * {@code PRAGMA table_info} at run time, which is also the only description
 * that stays true for a session written by a different build.
 *
 * <h2>No row counts</h2>
 *
 * <p>Counting every table is a scan per table, and a count shown before it was
 * taken would be a zero standing in for "not known yet" (rule 11). The column
 * list is the thing that was missing; a count is one {@code SELECT COUNT(*)}
 * away in the editor beside it.
 *
 * <p>Holds no reader and no connection: it is handed a finished list, so it can
 * do no I/O and needs no thread of its own.
 */
final class SchemaBrowser extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTextField filter = new JTextField();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("No session open");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JLabel summary = new JLabel(" ");

    private List<SchemaTable> schema = List.of();
    private Consumer<String> tableChosen = name -> { };

    SchemaBrowser() {
        super(new BorderLayout(0, 4));
        PlainText.install(tree);
        PlainText.disableHtml(summary);
        PlainText.disableHtml(filter);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    chooseSelected();
                }
            }
        });

        filter.setToolTipText(PlainText.tooltip("Filter tables and columns by name"));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                rebuild();
            }
        });

        JPanel head = new JPanel(new BorderLayout(0, 2));
        JLabel caption = PlainText.disableHtml(new JLabel("Schema"));
        head.add(caption, BorderLayout.NORTH);
        head.add(filter, BorderLayout.CENTER);
        head.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        summary.setEnabled(false);
        summary.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));

        add(head, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);
    }

    /** Called with a table name when the user double-clicks one. */
    void onTableChosen(Consumer<String> handler) {
        this.tableChosen = Objects.requireNonNull(handler, "handler");
    }

    /** Replaces the shown schema. EDT only; the list arrives from a worker. */
    void show(List<SchemaTable> tables) {
        this.schema = List.copyOf(tables);
        rebuild();
    }

    /** Clears the tree, for a session being closed. */
    void clear() {
        this.schema = List.of();
        rebuild();
    }

    private void rebuild() {
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        List<SchemaTable> shown = new ArrayList<>();
        for (SchemaTable table : schema) {
            if (needle.isEmpty() || matches(table, needle)) {
                shown.add(table);
            }
        }
        long views = schema.stream().filter(SchemaTable::isView).count();
        long tables = schema.size() - views;
        root.setUserObject(schema.isEmpty()
                ? "No session open"
                : describe(tables, "table") + ", " + describe(views, "view"));
        root.removeAllChildren();
        for (SchemaTable table : shown) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(label(table));
            for (SchemaColumn column : table.columns()) {
                node.add(new DefaultMutableTreeNode(label(column)));
            }
            root.add(node);
        }
        model.reload();
        tree.expandPath(new TreePath(root));
        if (schema.isEmpty()) {
            summary.setText(" ");
        } else if (needle.isEmpty()) {
            summary.setText("Double-click a table to query it.");
        } else {
            // Rule 12: a filtered tree must say it is filtered, or an empty one
            // reads as a session with no such table.
            summary.setText(shown.size() + " of " + schema.size() + " match \"" + needle + "\"");
        }
    }

    private static String describe(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static boolean matches(SchemaTable table, String needle) {
        if (table.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (SchemaColumn column : table.columns()) {
            if (column.name().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String label(SchemaTable table) {
        if (table.isTemp()) {
            // Named as what it is: this connection's, not the session's. A
            // temp view listed like a table would read as part of the file.
            return table.name() + "  (temp view, this tab only)";
        }
        return table.name() + (table.isView() ? "  (view)" : "");
    }

    private static String label(SchemaColumn column) {
        StringBuilder text = new StringBuilder(column.name());
        if (!column.declaredType().isEmpty()) {
            text.append("  ").append(column.declaredType());
        }
        if (column.isPrimaryKey()) {
            text.append("  PK");
        }
        if (column.notNull()) {
            text.append("  NOT NULL");
        }
        return text.toString();
    }

    private void chooseSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node) || node.getParent() != root) {
            return;
        }
        String label = String.valueOf(node.getUserObject());
        int marker = label.indexOf("  (");
        tableChosen.accept(marker < 0 ? label : label.substring(0, marker));
    }

    /** Visible for testing: the table rows currently listed, in order. */
    List<String> listedTablesForTest() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            names.add(String.valueOf(
                    ((DefaultMutableTreeNode) root.getChildAt(i)).getUserObject()));
        }
        return names;
    }

    /** Visible for testing: the column rows listed under {@code table}. */
    List<String> listedColumnsForTest(String table) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) root.getChildAt(i);
            String label = String.valueOf(node.getUserObject());
            if (label.equals(table) || label.startsWith(table + "  ")) {
                List<String> columns = new ArrayList<>();
                for (int c = 0; c < node.getChildCount(); c++) {
                    columns.add(String.valueOf(
                            ((DefaultMutableTreeNode) node.getChildAt(c)).getUserObject()));
                }
                return columns;
            }
        }
        return List.of();
    }

    /** Visible for testing: drives the filter box as a user would. */
    void filterForTest(String text) {
        filter.setText(text);
    }

    /** Visible for testing: the line under the tree. */
    String summaryForTest() {
        return summary.getText();
    }

    /** Visible for testing: the root label. */
    String rootLabelForTest() {
        return String.valueOf(root.getUserObject());
    }
}
