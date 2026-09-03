package com.holtherndon.bazelviz.ui.repository;

import com.holtherndon.bazelviz.runner.files.DirectoryPage;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SelectableLabel;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/** A bounded, lazy browser for one local or remote execution workspace. */
public final class RepositoryBrowserView extends JPanel implements AutoCloseable {

  private static final long serialVersionUID = 1L;

  /** Maximum number of filesystem entries shown by a newly constructed browser. */
  public static final int DEFAULT_VISIBLE_ENTRY_LIMIT = 5_000;

  private static final String LOADING = "Loading…";

  private final int visibleEntryLimit;
  private ExecutorService worker;
  private final JTextField location = SelectableLabel.create("No repository is connected.");
  private final JTextArea status = WrappingLabel.create(" ");
  private final JButton refresh = new JButton("Refresh");
  private final DefaultMutableTreeNode emptyRoot = new DefaultMutableTreeNode("Repository");
  private final DefaultTreeModel treeModel = new DefaultTreeModel(emptyRoot);
  private final JTree tree = new JTree(treeModel);
  private final RepositoryTreeRenderer treeRenderer = new RepositoryTreeRenderer();
  private final ArrayDeque<RepositoryNode> loadQueue = new ArrayDeque<>();

  private ExecutionFileSystem fileSystem;
  private ExecutionPath repositoryRoot;
  private String hostDescription = "";
  private Consumer<ExecutionPath> openFile = ignored -> {};
  private RepositoryNode rootNode;
  private RepositoryFileIcons fileIcons;
  private boolean loadInFlight;
  private boolean partial;
  private boolean closed;
  private int visibleEntries;
  private long generation;
  private String lastError;
  private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
  private CompletableFuture<Void> retiredWorkerCloses = CompletableFuture.completedFuture(null);

  public RepositoryBrowserView() {
    this(DEFAULT_VISIBLE_ENTRY_LIMIT);
  }

  /**
   * Creates a browser with an explicit whole-tree display cap.
   *
   * <p>The cap is always shown in the status text. Reaching it marks the tree partial rather than
   * silently making a large directory look complete.
   */
  public RepositoryBrowserView(int visibleEntryLimit) {
    super(new BorderLayout(0, 6));
    if (visibleEntryLimit < 1) {
      throw new IllegalArgumentException(
          "visibleEntryLimit must be positive, got " + visibleEntryLimit);
    }
    this.visibleEntryLimit = visibleEntryLimit;
    worker = newWorker();

    PlainText.disableHtml(tree);
    tree.setCellRenderer(treeRenderer);
    tree.setRootVisible(true);
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.addTreeWillExpandListener(
        new TreeWillExpandListener() {
          @Override
          public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
            Object value =
                ((DefaultMutableTreeNode) event.getPath().getLastPathComponent()).getUserObject();
            if (value instanceof RepositoryNode node) {
              queueLoad(node);
            }
          }

          @Override
          public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
            // Loaded direct children remain cached until Refresh is pressed.
          }
        });
    tree.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 2) {
              open(tree.getPathForLocation(event.getX(), event.getY()));
            }
          }
        });

    refresh.setEnabled(false);
    refresh.setToolTipText("Reload the repository root and discard cached directory listings.");
    refresh.addActionListener(event -> refresh());

    JPanel header = new JPanel(new BorderLayout(8, 0));
    header.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
    JPanel heading = new JPanel(new BorderLayout(8, 2));
    JLabel label = new JLabel("Repository:");
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    heading.add(label, BorderLayout.WEST);
    heading.add(location, BorderLayout.CENTER);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    actions.add(refresh);
    header.add(heading, BorderLayout.CENTER);
    header.add(actions, BorderLayout.EAST);

    status.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
    add(header, BorderLayout.NORTH);
    add(new JScrollPane(tree), BorderLayout.CENTER);
    add(status, BorderLayout.SOUTH);
    showDisconnected();
    loadFileIcons();
  }

  /** Classpath reads and SVG parsing happen once on the existing browser worker. */
  private void loadFileIcons() {
    worker.execute(
        () -> {
          RepositoryFileIcons loaded = RepositoryFileIcons.load();
          SwingUtilities.invokeLater(
              () -> {
                if (closed) {
                  return;
                }
                fileIcons = loaded;
                tree.repaint();
              });
        });
  }

  /** Opens regular files when their tree row is double-clicked. */
  public void onOpenFile(Consumer<ExecutionPath> callback) {
    openFile = Objects.requireNonNull(callback, "callback");
  }

  /**
   * Binds this view to one execution host and begins the root listing.
   *
   * <p>The filesystem remains owned by the execution session. This view only closes its own loader
   * from {@link #close()}.
   */
  public void openRepository(
      String host, ExecutionFileSystem newFileSystem, ExecutionPath newRoot) {
    requireEdt();
    if (closed) {
      throw new IllegalStateException("repository browser is closed");
    }
    hostDescription = requireText(host, "host");
    fileSystem = Objects.requireNonNull(newFileSystem, "newFileSystem");
    repositoryRoot = Objects.requireNonNull(newRoot, "newRoot");
    location.setText(hostDescription + " · " + repositoryRoot.value());
    location.setToolTipText(PlainText.tooltip(location.getText()));
    refresh.setEnabled(true);
    refresh();
  }

  /** Clears the bound execution without closing its externally owned filesystem. */
  public void clearRepository() {
    clearRepositoryAsync();
  }

  /**
   * Clears the bound execution and completes after its accepted directory reads have stopped.
   *
   * <p>A fresh worker is installed immediately so this view remains reusable for the next Workspace
   * after the caller closes the previous execution transport.
   */
  public CompletionStage<Void> clearRepositoryAsync() {
    requireEdt();
    if (closed) {
      return closeCompletion;
    }
    boolean hadRepository =
        fileSystem != null || repositoryRoot != null || loadInFlight || !loadQueue.isEmpty();
    generation++;
    fileSystem = null;
    repositoryRoot = null;
    rootNode = null;
    loadQueue.clear();
    loadInFlight = false;
    showDisconnected();
    if (!hadRepository) {
      return CompletableFuture.completedFuture(null);
    }
    ExecutorService retiring = worker;
    worker = newWorker();
    if (fileIcons == null) {
      loadFileIcons();
    }
    CompletableFuture<Void> retired =
        ExecutorClose.cancelAsync(retiring, "bbv-repository-browser-context").toCompletableFuture();
    retiredWorkerCloses = CompletableFuture.allOf(retiredWorkerCloses, retired);
    return retired;
  }

  /** Reloads from the root. Previously expanded directories load again on demand. */
  public void refresh() {
    requireEdt();
    if (closed || fileSystem == null || repositoryRoot == null) {
      return;
    }
    generation++;
    loadQueue.clear();
    loadInFlight = false;
    visibleEntries = 0;
    partial = false;
    lastError = null;

    rootNode = RepositoryNode.root(repositoryRoot);
    DefaultMutableTreeNode swingRoot = swingNode(rootNode);
    swingRoot.add(messageNode(LOADING));
    treeModel.setRoot(swingRoot);
    tree.expandPath(new TreePath(swingRoot.getPath()));
    updateStatus(LOADING + " " + repositoryRoot.value());
    queueLoad(rootNode);
  }

  @Override
  public void close() {
    closeAsync();
  }

  /**
   * Cancels queued listings and completes after an in-flight filesystem call has returned.
   *
   * <p>State is detached on the EDT. Waiting happens on a virtual thread, so a window can keep
   * processing events while its SSH filesystem operation observes interruption or its own transport
   * timeout.
   */
  public CompletionStage<Void> closeAsync() {
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(this::beginClose);
      return closeCompletion;
    }
    beginClose();
    return closeCompletion;
  }

  private void beginClose() {
    if (closed) {
      return;
    }
    closed = true;
    generation++;
    fileSystem = null;
    repositoryRoot = null;
    loadQueue.clear();
    loadInFlight = false;
    refresh.setEnabled(false);
    CompletableFuture<Void> activeClose =
        ExecutorClose.cancelAsync(worker, "bbv-repository-browser").toCompletableFuture();
    CompletableFuture.allOf(retiredWorkerCloses, activeClose)
        .whenComplete(
            (ignored, failure) -> {
              if (failure == null) {
                closeCompletion.complete(null);
              } else {
                closeCompletion.completeExceptionally(failure);
              }
            });
  }

  private static ExecutorService newWorker() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "bbv-repository-browser");
          thread.setDaemon(true);
          return thread;
        });
  }

  private void queueLoad(RepositoryNode node) {
    requireEdt();
    if (closed
        || fileSystem == null
        || !node.isExpandable()
        || node.loadState != LoadState.NOT_LOADED) {
      return;
    }
    node.loadState = LoadState.QUEUED;
    DefaultMutableTreeNode swingNode = node.swingNode;
    swingNode.removeAllChildren();
    swingNode.add(messageNode(LOADING));
    treeModel.nodeStructureChanged(swingNode);
    loadQueue.addLast(node);
    startNextLoad();
  }

  /** Serializing listings makes the whole-tree cap exact even when many rows expand quickly. */
  private void startNextLoad() {
    requireEdt();
    if (closed || loadInFlight || fileSystem == null) {
      return;
    }
    RepositoryNode node = loadQueue.pollFirst();
    if (node == null) {
      return;
    }
    if (visibleEntries >= visibleEntryLimit) {
      node.loadState = LoadState.PARTIAL;
      node.swingNode.removeAllChildren();
      node.swingNode.add(
          messageNode(
              "Not loaded: the %,d-entry view limit is reached.".formatted(visibleEntryLimit)));
      treeModel.nodeStructureChanged(node.swingNode);
      partial = true;
      updateStatus(null);
      startNextLoad();
      return;
    }

    loadInFlight = true;
    node.loadState = LoadState.LOADING;
    long wantedGeneration = generation;
    int remaining = visibleEntryLimit - visibleEntries;
    ExecutionFileSystem wantedFileSystem = fileSystem;
    worker.execute(
        () -> {
          try {
            LoadedDirectory loaded =
                readDirectory(wantedFileSystem, node.path, node.followsSymbolicLink, remaining);
            SwingUtilities.invokeLater(() -> directoryLoaded(node, loaded, wantedGeneration));
          } catch (IOException | RuntimeException failure) {
            SwingUtilities.invokeLater(() -> directoryFailed(node, failure, wantedGeneration));
          }
        });
  }

  private static LoadedDirectory readDirectory(
      ExecutionFileSystem fileSystem,
      ExecutionPath directory,
      boolean followSymbolicLink,
      int maximum)
      throws IOException {
    ExecutionPath listingDirectory =
        followSymbolicLink ? fileSystem.canonicalize(directory) : directory;
    List<FileMetadata> entries = new ArrayList<>();
    Optional<String> token = Optional.empty();
    OptionalLong total = OptionalLong.empty();
    boolean more;
    do {
      int requested = Math.min(250, maximum - entries.size());
      if (requested <= 0) {
        return new LoadedDirectory(entries, true, total);
      }
      DirectoryPage page = fileSystem.list(listingDirectory, token, requested);
      if (!page.directory().equals(listingDirectory)) {
        throw new IOException(
            "filesystem returned a page for "
                + page.directory().value()
                + " while listing "
                + listingDirectory.value());
      }
      int accepted = Math.min(page.entries().size(), maximum - entries.size());
      entries.addAll(page.entries().subList(0, accepted));
      if (page.totalEntries().isPresent()) {
        total = page.totalEntries();
      }
      more = page.nextToken().isPresent() || accepted < page.entries().size();
      if (page.nextToken().isPresent() && page.entries().isEmpty()) {
        throw new IOException("filesystem returned an empty page with a continuation");
      }
      token = page.nextToken();
    } while (more && entries.size() < maximum);
    return new LoadedDirectory(entries, more, total);
  }

  private void directoryLoaded(RepositoryNode node, LoadedDirectory loaded, long wantedGeneration) {
    requireEdt();
    if (closed || wantedGeneration != generation || node.loadState != LoadState.LOADING) {
      return;
    }
    loadInFlight = false;
    node.swingNode.removeAllChildren();
    boolean directRepositoryChildren = node == rootNode;
    String workspaceDirectoryName = repositoryRoot.fileName();
    for (FileMetadata metadata : loaded.entries) {
      RepositoryNode child =
          RepositoryNode.from(metadata, directRepositoryChildren, workspaceDirectoryName);
      DefaultMutableTreeNode swingChild = swingNode(child);
      if (child.isExpandable()) {
        swingChild.add(messageNode("Expand to load this directory."));
      }
      node.swingNode.add(swingChild);
    }
    visibleEntries += loaded.entries.size();
    boolean knownRemainder =
        loaded.totalEntries.isPresent() && loaded.totalEntries.getAsLong() > loaded.entries.size();
    node.loadState = loaded.more || knownRemainder ? LoadState.PARTIAL : LoadState.LOADED;
    partial |= node.loadState == LoadState.PARTIAL;
    treeModel.nodeStructureChanged(node.swingNode);
    updateStatus(null);
    startNextLoad();
  }

  private void directoryFailed(RepositoryNode node, Throwable failure, long wantedGeneration) {
    requireEdt();
    if (closed || wantedGeneration != generation || node.loadState != LoadState.LOADING) {
      return;
    }
    loadInFlight = false;
    node.loadState = LoadState.FAILED;
    node.swingNode.removeAllChildren();
    String detail = describe(failure);
    node.swingNode.add(messageNode("Could not load: " + detail));
    treeModel.nodeStructureChanged(node.swingNode);
    lastError = "Could not list " + node.path.value() + ": " + detail;
    updateStatus(null);
    startNextLoad();
  }

  private void open(TreePath selectedPath) {
    if (selectedPath == null) {
      return;
    }
    Object value = ((DefaultMutableTreeNode) selectedPath.getLastPathComponent()).getUserObject();
    if (value instanceof RepositoryNode node && node.kind == FileMetadata.Kind.REGULAR_FILE) {
      openFile.accept(node.path);
    }
  }

  private void updateStatus(String activity) {
    StringBuilder text = new StringBuilder();
    if (activity != null) {
      text.append(activity).append(" · ");
    }
    text.append(
        "%,d entries loaded. View limit: %,d.".formatted(visibleEntries, visibleEntryLimit));
    if (partial) {
      text.append(" More entries exist; this repository view is partial.");
    } else {
      text.append(" Expand a directory to load only its direct children.");
    }
    if (lastError != null) {
      text.append(' ').append(lastError);
    }
    status.setText(text.toString());
  }

  private void showDisconnected() {
    location.setText("No repository is connected.");
    location.setToolTipText(null);
    refresh.setEnabled(false);
    emptyRoot.removeAllChildren();
    emptyRoot.add(messageNode("Choose a build host to browse its repository."));
    treeModel.setRoot(emptyRoot);
    status.setText("0 entries loaded. View limit: %,d.".formatted(visibleEntryLimit));
  }

  private static DefaultMutableTreeNode swingNode(RepositoryNode value) {
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(value);
    value.swingNode = node;
    return node;
  }

  private static DefaultMutableTreeNode messageNode(String text) {
    return new DefaultMutableTreeNode(text, false);
  }

  /** Keeps selection colours from the look and feel while replacing only the row icon. */
  private final class RepositoryTreeRenderer extends DefaultTreeCellRenderer {

    private static final long serialVersionUID = 1L;

    @Override
    public Component getTreeCellRendererComponent(
        JTree renderedTree,
        Object value,
        boolean selected,
        boolean expanded,
        boolean leaf,
        int row,
        boolean focused) {
      Component rendered =
          super.getTreeCellRendererComponent(
              renderedTree, value, selected, expanded, leaf, row, focused);
      if (rendered instanceof JComponent component) {
        PlainText.disableHtml(component);
      }

      Object userObject =
          value instanceof DefaultMutableTreeNode swingNode ? swingNode.getUserObject() : null;
      if (!(userObject instanceof RepositoryNode node)) {
        setIcon(null);
        return rendered;
      }
      RepositoryFileIcons availableIcons = fileIcons;
      if (availableIcons != null) {
        Icon icon =
            availableIcons.iconFor(node.kind, node.path.fileName(), node.followsSymbolicLink);
        if (icon != null) {
          setIcon(icon);
        }
      }
      return rendered;
    }
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " cannot be blank");
    }
    return value;
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("repository UI changes must run on the EDT");
    }
  }

  private enum LoadState {
    NOT_LOADED,
    QUEUED,
    LOADING,
    LOADED,
    PARTIAL,
    FAILED
  }

  private static final class RepositoryNode {
    private final ExecutionPath path;
    private final FileMetadata.Kind kind;
    private final boolean followsSymbolicLink;
    private final String text;
    private LoadState loadState;
    private DefaultMutableTreeNode swingNode;

    private RepositoryNode(
        ExecutionPath path,
        FileMetadata.Kind kind,
        boolean followsSymbolicLink,
        String text,
        LoadState loadState) {
      this.path = path;
      this.kind = kind;
      this.followsSymbolicLink = followsSymbolicLink;
      this.text = text;
      this.loadState = loadState;
    }

    static RepositoryNode root(ExecutionPath root) {
      return new RepositoryNode(
          root,
          FileMetadata.Kind.DIRECTORY,
          false,
          root.fileName().isBlank() ? root.value() : root.fileName(),
          LoadState.NOT_LOADED);
    }

    static RepositoryNode from(
        FileMetadata metadata, boolean directRepositoryChild, String workspaceDirectoryName) {
      String name = metadata.path().fileName();
      boolean followsSymbolicLink =
          BazelOutputSymlink.isCandidate(
              metadata.kind(), name, directRepositoryChild, workspaceDirectoryName);
      StringBuilder display = new StringBuilder(name.isBlank() ? metadata.path().value() : name);
      if (metadata.state() != FileMetadata.State.PRESENT) {
        display.append(" · ").append(metadata.state().name().toLowerCase());
        metadata.detail().ifPresent(detail -> display.append(" — ").append(detail));
      } else {
        switch (metadata.kind()) {
          case DIRECTORY -> display.append('/');
          case SYMBOLIC_LINK -> {
            if (followsSymbolicLink) {
              display.append("/ · Bazel symlink");
            } else {
              display.append(" · symbolic link");
            }
          }
          case OTHER -> display.append(" · special file");
          case UNKNOWN -> display.append(" · unknown type");
          case REGULAR_FILE ->
              metadata.bytes().ifPresent(bytes -> display.append(" · %,d bytes".formatted(bytes)));
        }
      }
      return new RepositoryNode(
          metadata.path(),
          metadata.kind(),
          followsSymbolicLink,
          display.toString(),
          LoadState.NOT_LOADED);
    }

    boolean isExpandable() {
      return kind == FileMetadata.Kind.DIRECTORY || followsSymbolicLink;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  private record LoadedDirectory(
      List<FileMetadata> entries, boolean more, OptionalLong totalEntries) {
    private LoadedDirectory {
      entries = List.copyOf(entries);
    }
  }

  // Focused package tests avoid reaching through Swing's private tree renderer internals.
  int visibleEntriesForTest() {
    return visibleEntries;
  }

  String statusForTest() {
    return status.getText();
  }

  String locationForTest() {
    return location.getText();
  }

  List<String> rootChildrenForTest() {
    if (rootNode == null) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (int index = 0; index < rootNode.swingNode.getChildCount(); index++) {
      values.add(rootNode.swingNode.getChildAt(index).toString());
    }
    return List.copyOf(values);
  }

  List<String> rootChildChildrenForTest(int index) {
    DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.swingNode.getChildAt(index);
    List<String> values = new ArrayList<>();
    for (int childIndex = 0; childIndex < child.getChildCount(); childIndex++) {
      values.add(child.getChildAt(childIndex).toString());
    }
    return List.copyOf(values);
  }

  boolean iconsReadyForTest() {
    return fileIcons != null;
  }

  List<Icon> rootChildIconsForTest() {
    if (rootNode == null) {
      return List.of();
    }
    List<Icon> values = new ArrayList<>();
    for (int index = 0; index < rootNode.swingNode.getChildCount(); index++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.swingNode.getChildAt(index);
      treeRenderer.getTreeCellRendererComponent(
          tree, child, false, false, child.isLeaf(), index, false);
      values.add(treeRenderer.getIcon());
    }
    return List.copyOf(values);
  }

  Icon messageIconForTest() {
    DefaultMutableTreeNode message = messageNode("Loading…");
    treeRenderer.getTreeCellRendererComponent(tree, message, false, false, true, 0, false);
    return treeRenderer.getIcon();
  }

  Object rendererHtmlDisabledForTest() {
    DefaultMutableTreeNode message = messageNode("<html><b>not markup</b>");
    Component rendered =
        treeRenderer.getTreeCellRendererComponent(tree, message, false, false, true, 0, false);
    return rendered instanceof JComponent component
        ? component.getClientProperty("html.disable")
        : null;
  }

  void expandRootChildForTest(int index) {
    DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.swingNode.getChildAt(index);
    Object value = child.getUserObject();
    if (value instanceof RepositoryNode node) {
      queueLoad(node);
    }
  }

  void openRootChildForTest(int index) {
    DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.swingNode.getChildAt(index);
    open(new TreePath(child.getPath()));
  }
}
