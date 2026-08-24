package com.holtherndon.bazelviz.ui.targets;

import com.holtherndon.bazelviz.storage.entities.TargetQueries;
import com.holtherndon.bazelviz.storage.entities.TargetRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SessionSource;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Targets card: packages, and the targets inside them.
 *
 * <h2>Two levels, loaded separately</h2>
 *
 * <p>The tree shows packages collapsed and fetches a package's targets when it
 * is expanded. A tree that loaded every target to draw its collapsed view would
 * hold the whole build in memory to show a screen of folder names.
 *
 * <h2>What a package node says</h2>
 *
 * <p>Its target count, and — when either is non-zero — how many failed and how
 * many were configured but never completed. The second number is the one worth
 * surfacing: a build interrupted during analysis consists entirely of targets
 * that were configured and never built, and a tree that showed only completions
 * would report such a build as empty.
 */
public final class TargetsView extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(TargetsView.class);

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TREE = "tree";

    /** Placeholder child that makes a package node expandable before it is loaded. */
    private static final String PENDING = "…";

    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel emptyLabel = new JLabel(" ", SwingConstants.CENTER);
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("targets");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final InspectorPanel inspector = new InspectorPanel();
    private final JLabel statusLabel = new JLabel(" ");

    private ExecutorService executor;
    private EntityReader reader;
    private SessionSource source;
    private LongConsumer showEventHandler = eventId -> { };
    private long selectionGeneration;

    /**
     * The target a {@link #revealLabel} is waiting to select, once its
     * package's children have loaded. Null when no reveal is pending. Only
     * ever touched on the EDT.
     */
    private Long pendingRevealTargetId;

    /** The package the pending reveal lives in, so another package's load cannot resolve it. */
    private String pendingRevealPackagePath;

    /** Bumped per reveal so a slow lookup cannot land after a newer one. */
    private long revealGeneration;

    public TargetsView() {
        super(new BorderLayout());

        emptyLabel.setEnabled(false);
        JPanel empty = new JPanel(new BorderLayout());
        empty.add(emptyLabel, BorderLayout.CENTER);

        PlainText.install(tree);
        PlainText.disableHtml(statusLabel);
        PlainText.disableHtml(emptyLabel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(event -> selectionChanged());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
                // Nothing to release: a collapsed package keeps its children,
                // which is what makes re-expanding it free.
            }
        });
        inspector.onShowSourceEvent(eventId -> showEventHandler.accept(eventId));

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setMinimumSize(new Dimension(320, 160));
        inspector.setMinimumSize(new Dimension(300, 160));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, inspector);
        split.setResizeWeight(0.55);

        JPanel status = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(statusLabel, BorderLayout.WEST);

        JPanel session = new JPanel(new BorderLayout());
        session.add(split, BorderLayout.CENTER);
        session.add(status, BorderLayout.SOUTH);

        deck.add(empty, CARD_EMPTY);
        deck.add(session, CARD_TREE);
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

    /** Opens a session and loads the package level. Returns immediately. */
    public void openSession(SessionSource newSource) {
        Objects.requireNonNull(newSource, "newSource");
        closeSession();
        source = newSource;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bbv-targets");
            thread.setDaemon(true);
            return thread;
        });
        showEmpty("Reading targets…");
        ExecutorService opening = executor;
        opening.execute(() -> {
            try {
                EntityReader opened = newSource.openEntityReader();
                List<TargetQueries.PackageSummary> packages = opened.packages();
                SwingUtilities.invokeLater(() -> {
                    if (source != newSource) {
                        opened.close();
                        return;
                    }
                    reader = opened;
                    install(packages);
                });
            } catch (RuntimeException failure) {
                log.error("could not read targets", failure);
                SwingUtilities.invokeLater(() -> showEmpty(failure.getMessage()));
            }
        });
    }

    public void closeSession() {
        pendingRevealTargetId = null;
        pendingRevealPackagePath = null;
        revealGeneration++;
        root.removeAllChildren();
        treeModel.reload();
        inspector.show(Inspection.NONE);
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
        }, "bbv-targets-close");
        closer.setDaemon(true);
        closer.start();
    }

    /**
     * Shows one target by its label: the package path expanded, the target's
     * row selected and inspected — random access by label, where the tree is
     * otherwise browsed top-down.
     *
     * <p>The label is looked up off the EDT ({@code targetsByLabel}), because
     * the package path shown in the tree is the one the database derived and
     * deriving it here again would be a second definition that could drift.
     * A label this session never declared changes nothing but the status
     * line, which says so — cross-view navigation arrives here with labels
     * parsed out of events, and an event can name a label no target row
     * carries.
     *
     * <p>Must be called on the EDT. Does nothing when no session is open.
     */
    public void revealLabel(String label) {
        Objects.requireNonNull(label, "label");
        ExecutorService running = executor;
        EntityReader current = reader;
        if (running == null || current == null) {
            return;
        }
        long generation = ++revealGeneration;
        running.execute(() -> {
            try {
                List<TargetRow> rows = current.targetsByLabel(label);
                SwingUtilities.invokeLater(() -> {
                    if (generation != revealGeneration) {
                        return;
                    }
                    if (rows.isEmpty()) {
                        statusLabel.setText("No target named " + label
                                + " in this session.");
                        return;
                    }
                    revealRow(rows.getFirst());
                });
            } catch (RuntimeException failure) {
                log.warn("could not look up {}", label, failure);
                SwingUtilities.invokeLater(() -> {
                    if (generation == revealGeneration) {
                        statusLabel.setText("Could not look up " + label + ": "
                                + failure.getMessage());
                    }
                });
            }
        });
    }

    /** EDT: expands the row's package and selects the row, loading it first if needed. */
    private void revealRow(TargetRow row) {
        DefaultMutableTreeNode packageNode = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(i);
            if (child.getUserObject() instanceof PackageNode candidate
                    && candidate.summary.path().equals(row.packagePath())) {
                packageNode = child;
                break;
            }
        }
        if (packageNode == null) {
            // The row exists and its package is not in the tree: the two
            // reads disagree, most plausibly because the session grew between
            // them. Saying so beats selecting nothing silently.
            statusLabel.setText("The package " + row.packagePath()
                    + " is not in the tree; reopen the session to refresh it.");
            return;
        }
        pendingRevealTargetId = row.id();
        pendingRevealPackagePath = row.packagePath();
        PackageNode node = (PackageNode) packageNode.getUserObject();
        if (node.loaded && !hasPendingPlaceholder(packageNode)) {
            // Already populated; select right away.
            selectPendingIn(packageNode);
        } else {
            // Expanding triggers loadChildren, whose arrival completes the
            // reveal. Expanding an already-expanding node is harmless.
            tree.expandPath(new TreePath(packageNode.getPath()));
        }
    }

    private static boolean hasPendingPlaceholder(DefaultMutableTreeNode packageNode) {
        return packageNode.getChildCount() == 1
                && PENDING.equals(
                        ((DefaultMutableTreeNode) packageNode.getChildAt(0)).getUserObject());
    }

    /** EDT: selects the pending reveal's target under {@code packageNode}, if both exist. */
    private void selectPendingIn(DefaultMutableTreeNode packageNode) {
        Long wanted = pendingRevealTargetId;
        if (wanted == null) {
            return;
        }
        // Another package loading — a user browsing while a reveal is in
        // flight — must not resolve, or fail, a reveal that lives elsewhere.
        if (!(packageNode.getUserObject() instanceof PackageNode loaded)
                || !loaded.summary.path().equals(pendingRevealPackagePath)) {
            return;
        }
        for (int i = 0; i < packageNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) packageNode.getChildAt(i);
            if (child.getUserObject() instanceof TargetNode targetNode
                    && targetNode.row.id() == wanted) {
                pendingRevealTargetId = null;
                pendingRevealPackagePath = null;
                TreePath path = new TreePath(child.getPath());
                tree.expandPath(new TreePath(packageNode.getPath()));
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                return;
            }
        }
        // The package loaded and the row is not among its children: the reads
        // disagree (a live session can grow between them). Say so.
        pendingRevealTargetId = null;
        pendingRevealPackagePath = null;
        statusLabel.setText("The target is no longer in its package's rows;"
                + " reopen the session to refresh it.");
    }

    /** Visible for testing: the package nodes currently in the tree. */
    int packageCountForTest() {
        return root.getChildCount();
    }

    /** Visible for testing: the selected target row's label, or null. */
    String selectedLabelForTest() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object selected = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return selected instanceof TargetNode targetNode ? targetNode.row.label() : null;
    }

    /** Visible for testing. */
    String statusForTest() {
        return statusLabel.getText();
    }

    private void install(List<TargetQueries.PackageSummary> packages) {
        root.removeAllChildren();
        long targets = 0;
        long failed = 0;
        long notCompleted = 0;
        for (TargetQueries.PackageSummary summary : packages) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(new PackageNode(summary));
            node.add(new DefaultMutableTreeNode(PENDING));
            root.add(node);
            targets += summary.targets();
            failed += summary.failed();
            notCompleted += summary.notCompleted();
        }
        treeModel.reload();
        if (packages.isEmpty()) {
            showEmpty("This session recorded no targets.");
            return;
        }
        StringBuilder status = new StringBuilder()
                .append(EntityFormat.count(targets))
                .append(" target rows in ")
                .append(EntityFormat.count(packages.size()))
                .append(packages.size() == 1 ? " package" : " packages");
        if (failed > 0) {
            status.append("  ·  ").append(EntityFormat.count(failed)).append(" failed");
        }
        if (notCompleted > 0) {
            status.append("  ·  ").append(EntityFormat.count(notCompleted))
                    .append(" configured but never completed");
        }
        statusLabel.setText(status.toString());
        cards.show(deck, CARD_TREE);
    }

    /**
     * Fills in a package's targets the first time it is expanded.
     *
     * <p>Synchronous on the EDT would be a query per expansion on the EDT, so
     * the placeholder stays until the rows arrive and the node repopulates
     * itself. The expansion is not vetoed meanwhile: a node that refused to
     * open while loading would feel broken.
     */
    private void loadChildren(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof PackageNode packageNode) || packageNode.loaded) {
            return;
        }
        packageNode.loaded = true;
        ExecutorService running = executor;
        EntityReader current = reader;
        if (running == null || current == null) {
            return;
        }
        running.execute(() -> {
            try {
                List<TargetRow> rows = current.targetsInPackage(packageNode.summary.path());
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    for (TargetRow row : rows) {
                        node.add(new DefaultMutableTreeNode(new TargetNode(row)));
                    }
                    treeModel.nodeStructureChanged(node);
                    // A reveal that expanded this package is waiting for
                    // exactly this arrival.
                    selectPendingIn(node);
                });
            } catch (RuntimeException failure) {
                log.warn("could not read targets in {}", packageNode.summary.path(), failure);
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    node.add(new DefaultMutableTreeNode("could not be read: " + failure.getMessage()));
                    treeModel.nodeStructureChanged(node);
                });
            }
        });
    }

    private void selectionChanged() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            inspector.show(Inspection.NONE);
            return;
        }
        Object selected = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(selected instanceof TargetNode targetNode)) {
            inspector.show(Inspection.NONE);
            return;
        }
        long generation = ++selectionGeneration;
        ExecutorService running = executor;
        EntityReader current = reader;
        if (running == null || current == null) {
            return;
        }
        running.execute(() -> {
            try {
                Inspection inspection = TargetInspection.of(
                        targetNode.row,
                        current.targetTags(targetNode.row.id()),
                        targetNode.row.configuredTargetId().isPresent()
                                ? current.outputGroups(
                                        targetNode.row.configuredTargetId().getAsLong())
                                : List.of());
                SwingUtilities.invokeLater(() -> {
                    if (generation == selectionGeneration) {
                        inspector.show(inspection);
                    }
                });
            } catch (RuntimeException failure) {
                log.warn("could not describe target {}", targetNode.row.label(), failure);
            }
        });
    }

    /** A package row in the tree. */
    private static final class PackageNode {
        private final TargetQueries.PackageSummary summary;
        private boolean loaded;

        PackageNode(TargetQueries.PackageSummary summary) {
            this.summary = summary;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder(summary.path())
                    .append("  (").append(EntityFormat.count(summary.targets()));
            if (summary.failed() > 0) {
                text.append(", ").append(summary.failed()).append(" failed");
            }
            if (summary.notCompleted() > 0) {
                text.append(", ").append(summary.notCompleted()).append(" not completed");
            }
            return text.append(')').toString();
        }
    }

    /** A target row in the tree. */
    private static final class TargetNode {
        private final TargetRow row;

        TargetNode(TargetRow row) {
            this.row = row;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder(row.targetName());
            row.aspect().ifPresent(aspect -> text.append(" (aspect ").append(aspect).append(')'));
            // The outcome of building it, or -- when it never got that far --
            // what analysis said. The two are different questions and the node
            // shows whichever one has an answer.
            text.append("  ").append(row.outcome()
                    .map(Enum::name)
                    .orElseGet(() -> row.analysisOutcome().name() + ", not completed"));
            return text.toString();
        }
    }
}
