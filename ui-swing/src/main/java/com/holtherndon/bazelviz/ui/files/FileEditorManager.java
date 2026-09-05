package com.holtherndon.bazelviz.ui.files;

import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose;
import com.holtherndon.bazelviz.ui.session.SessionInfo;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Owns modeless execution-file windows and their blocking-I/O workers. */
public final class FileEditorManager implements AutoCloseable {

  private static final String CLOSE_EDITOR_ACTION = "close-file-editor";

  private final Window owner;
  private final LocalExecutionFileSystem compatibilityLocalFiles =
      new LocalExecutionFileSystem("local-editor");
  private ExecutorService worker;
  private ExecutorService saveWorker;
  private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
  private CompletableFuture<Void> retiredWorkerCloses = CompletableFuture.completedFuture(null);

  /** EDT-only: one window per canonical path. */
  private final Map<ExecutionPath, OpenWindow> windows = new LinkedHashMap<>();

  private final DirtyEditorConfirmation closeConfirmation;
  private final DirtyEditorConfirmation contextChangeConfirmation;
  private volatile long contextGeneration;
  private volatile boolean closed;

  public FileEditorManager(Window owner) {
    this(
        owner,
        FileEditorManager::confirmDiscardAll,
        FileEditorManager::confirmDiscardForContextChange,
        singleWorker("bbv-file-editor"),
        singleWorker("bbv-file-editor-save"));
  }

  FileEditorManager(Window owner, DirtyEditorConfirmation dirtyEditorConfirmation) {
    this(
        owner,
        dirtyEditorConfirmation,
        dirtyEditorConfirmation,
        singleWorker("bbv-file-editor"),
        singleWorker("bbv-file-editor-save"));
  }

  FileEditorManager(
      Window owner,
      DirtyEditorConfirmation closeConfirmation,
      DirtyEditorConfirmation contextChangeConfirmation) {
    this(
        owner,
        closeConfirmation,
        contextChangeConfirmation,
        singleWorker("bbv-file-editor"),
        singleWorker("bbv-file-editor-save"));
  }

  FileEditorManager(
      Window owner,
      DirtyEditorConfirmation closeConfirmation,
      DirtyEditorConfirmation contextChangeConfirmation,
      ExecutorService worker,
      ExecutorService saveWorker) {
    this.owner = owner;
    this.closeConfirmation = Objects.requireNonNull(closeConfirmation, "closeConfirmation");
    this.contextChangeConfirmation =
        Objects.requireNonNull(contextChangeConfirmation, "contextChangeConfirmation");
    this.worker = Objects.requireNonNull(worker, "worker");
    this.saveWorker = Objects.requireNonNull(saveWorker, "saveWorker");
  }

  /**
   * Checks whether a Workspace close may discard its open file edits.
   *
   * <p>This EDT-only preflight asks at most once and never saves, closes, or changes an editor.
   * Individual editor windows retain their own close confirmation.
   */
  public boolean confirmCloseAllowed() {
    requireEventDispatchThread();
    return confirmOpenWindowsAllowed(closeConfirmation);
  }

  boolean confirmCloseAllowed(Iterable<FileEditorPanel> editors) {
    return confirmDiscardAllowed(editors, closeConfirmation);
  }

  /** Checks whether changing execution filesystems may discard open edits. EDT-only. */
  public boolean confirmContextChangeAllowed() {
    requireEventDispatchThread();
    return confirmOpenWindowsAllowed(contextChangeConfirmation);
  }

  boolean confirmContextChangeAllowed(Iterable<FileEditorPanel> editors) {
    return confirmDiscardAllowed(editors, contextChangeConfirmation);
  }

  private boolean confirmDiscardAllowed(
      Iterable<FileEditorPanel> editors, DirtyEditorConfirmation confirmation) {
    requireEventDispatchThread();
    int dirtyCount = 0;
    for (FileEditorPanel editor : editors) {
      if (Objects.requireNonNull(editor, "editor").isDirty()) {
        dirtyCount++;
      }
    }
    return dirtyCount == 0 || confirmation.confirm(owner, dirtyCount);
  }

  private boolean confirmOpenWindowsAllowed(DirtyEditorConfirmation confirmation) {
    int dirtyCount = 0;
    for (OpenWindow open : windows.values()) {
      if (open.panel().isDirty()) {
        dirtyCount++;
      }
    }
    return dirtyCount == 0 || confirmation.confirm(owner, dirtyCount);
  }

  /**
   * Disposes every editor backed by the old execution filesystem and invalidates any file
   * resolution that has not reached the EDT yet. EDT-only, after confirmation.
   */
  public void closeEditorsForContextChange() {
    closeEditorsForContextChangeAsync();
  }

  /**
   * Invalidates old-context windows and completes after their accepted I/O has stopped.
   *
   * <p>The manager installs fresh workers immediately and remains reusable. Reads are cancelled;
   * accepted saves receive the full bounded save drain before the caller may close the old SSH
   * transport.
   */
  public CompletionStage<Void> closeEditorsForContextChangeAsync() {
    requireEventDispatchThread();
    if (closed) {
      return closeCompletion;
    }
    contextGeneration++;
    disposeOpenWindows();
    ExecutorService retiringReads = worker;
    ExecutorService retiringSaves = saveWorker;
    worker = singleWorker("bbv-file-editor");
    saveWorker = singleWorker("bbv-file-editor-save");
    CompletableFuture<Void> retired =
        closeWorkers(retiringReads, retiringSaves, "bbv-file-editor-context");
    retiredWorkerCloses = CompletableFuture.allOf(retiredWorkerCloses, retired);
    return retired;
  }

  /** Resolves and opens the BUILD file owning a target label. */
  public void openBuildFile(String label, SessionInfo session) {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(session, "session");
    Optional<WorkspaceFileAccess> access = WorkspaceFileAccess.fromSession(session);
    if (access.isEmpty()) {
      showFailure(
          "Build file for " + label,
          "This build ran through SSH. Reconnect to that execution before opening "
              + label
              + "; recorded paths are never opened on the local machine.");
      return;
    }
    if (access
        .orElseThrow()
        .workspaceRoot()
        .or(() -> access.orElseThrow().workingDirectory())
        .isEmpty()) {
      showFailure(
          "Build file for " + label,
          "This session did not record a local Bazel workspace path, so "
              + label
              + " cannot be mapped to a BUILD file.");
      return;
    }
    openBuildFile(label, access.orElseThrow());
  }

  /** Resolves and opens the BUILD file through an explicit execution context. */
  public void openBuildFile(String label, WorkspaceFileAccess access) {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(access, "access");
    resolve(
        "Build file for " + label,
        () -> new ResolvedFile(access.files(), WorkspaceFileResolver.buildFile(access, label)),
        true);
  }

  /** Resolves and opens a test log or action output read-only. */
  public void open(FileLink link, SessionInfo session) {
    Objects.requireNonNull(link, "link");
    Objects.requireNonNull(session, "session");
    Optional<WorkspaceFileAccess> access = WorkspaceFileAccess.fromSession(session);
    if (access.isEmpty()) {
      showFailure(
          link.title(),
          "This build ran through SSH. Reconnect to that execution before opening "
              + link.location()
              + ".");
      return;
    }
    open(link, access.orElseThrow());
  }

  /** Resolves and opens an inspector link through an explicit execution context. */
  public void open(FileLink link, WorkspaceFileAccess access) {
    Objects.requireNonNull(link, "link");
    Objects.requireNonNull(access, "access");
    resolve(
        link.title(),
        () -> new ResolvedFile(access.files(), WorkspaceFileResolver.linkedFile(link, access)),
        false,
        link.line());
  }

  /** Opens an already-resolved event file read-only in the shared modeless viewer. */
  public void openLocal(Path path) {
    Objects.requireNonNull(path, "path");
    String title = "Open " + path.getFileName();
    ExecutionPath executionPath = compatibilityLocalFiles.path(path);
    resolve(
        title,
        () -> {
          FileMetadata metadata = compatibilityLocalFiles.stat(executionPath);
          if (metadata.state() != FileMetadata.State.PRESENT) {
            throw new IOException("the recorded file is not a regular file: " + path);
          }
          ExecutionPath canonical = compatibilityLocalFiles.canonicalize(executionPath);
          if (!compatibilityLocalFiles.stat(canonical).isRegularFile()) {
            throw new IOException("the recorded file is not a regular file: " + path);
          }
          return new ResolvedFile(compatibilityLocalFiles, canonical);
        },
        false);
  }

  /** Opens an already-resolved execution file in the shared modeless viewer. */
  public void open(ExecutionFileSystem files, ExecutionPath path) {
    open(files, path, false);
  }

  /** Opens an already-resolved execution file in the shared modeless viewer. */
  public void open(ExecutionFileSystem files, ExecutionPath path, boolean editable) {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(path, "path");
    resolve(
        "Open " + path.fileName(),
        () -> {
          FileMetadata metadata = files.stat(path);
          if (metadata.state() != FileMetadata.State.PRESENT) {
            throw new IOException(
                "the recorded file is " + metadata.state().name().toLowerCase() + ": " + path);
          }
          ExecutionPath canonical = files.canonicalize(path);
          if (!files.stat(canonical).isRegularFile()) {
            throw new IOException("the recorded path is not a regular file: " + path);
          }
          return new ResolvedFile(files, canonical);
        },
        editable);
  }

  /** Copies a displayed event-file path without touching the filesystem. */
  public void copyPath(String path) {
    Objects.requireNonNull(path, "path");
    if (closed) {
      return;
    }
    worker.execute(
        () -> {
          try {
            Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(path), null);
          } catch (RuntimeException failure) {
            SwingUtilities.invokeLater(() -> showFailure("Copy file path", describe(failure)));
          }
        });
  }

  /** Reveals an existing local file in the platform file browser, off the EDT. */
  public void reveal(Path path) {
    Objects.requireNonNull(path, "path");
    if (closed) {
      return;
    }
    worker.execute(
        () -> {
          try {
            if (!Files.exists(path)) {
              throw new IOException("the recorded file no longer exists: " + path);
            }
            if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
              throw new IOException("revealing files is not supported on this platform");
            }
            Desktop.getDesktop().browseFileDirectory(path.toFile());
          } catch (IOException | RuntimeException failure) {
            SwingUtilities.invokeLater(
                () -> showFailure("Reveal " + path.getFileName(), describe(failure)));
          }
        });
  }

  /** Reveals an execution path only when its filesystem exposes a local platform path. */
  public void reveal(ExecutionFileSystem files, ExecutionPath path) {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(path, "path");
    Optional<Path> local = files.localPath(path);
    if (local.isEmpty()) {
      showFailure(
          "Reveal " + path.fileName(),
          "This file is not on the local machine and cannot be revealed in Finder.");
      return;
    }
    reveal(local.orElseThrow());
  }

  private void resolve(String title, Resolve work, boolean editable) {
    resolve(title, work, editable, OptionalInt.empty());
  }

  private void resolve(String title, Resolve work, boolean editable, OptionalInt line) {
    if (closed) {
      return;
    }
    long wantedContext = contextGeneration;
    worker.execute(
        () -> {
          try {
            ResolvedFile resolved = work.run();
            SwingUtilities.invokeLater(
                () -> openResolved(title, resolved, editable, wantedContext, line));
          } catch (IOException | RuntimeException failure) {
            SwingUtilities.invokeLater(
                () -> {
                  if (wantedContext == contextGeneration) {
                    showFailure(title, describe(failure));
                  }
                });
          }
        });
  }

  private void openResolved(
      String title, ResolvedFile resolved, boolean editable, long wantedContext, OptionalInt line) {
    if (closed || wantedContext != contextGeneration) {
      return;
    }
    ExecutionPath path = resolved.path();
    OpenWindow existing = windows.get(path);
    if (existing != null) {
      line.ifPresent(existing.panel()::revealLine);
      existing.window.setVisible(true);
      existing.window.toFront();
      existing.window.requestFocus();
      return;
    }
    EditorWindow window = new EditorWindow(title);
    FileEditorPanel panel =
        new FileEditorPanel(
            resolved.files(), path, editable, worker, saveWorker, () -> confirmDiscard(window));
    line.ifPresent(panel::revealLine);
    window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    window.setContentPane(panel);
    window.setMinimumSize(new Dimension(560, 360));
    window.setSize(920, 700);
    window.setLocationRelativeTo(owner);
    OpenWindow opened = new OpenWindow(window, panel);
    windows.put(path, opened);
    window.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            closeEditor(path, opened);
          }
        });
    installCloseShortcut(window.getRootPane(), menuShortcutMask(), () -> closeEditor(path, opened));
    window.setVisible(true);
  }

  private void closeEditor(ExecutionPath path, OpenWindow opened) {
    if (windows.get(path) != opened) {
      return;
    }
    if (opened.panel().isDirty() && !confirmDiscard(opened.window())) {
      return;
    }
    windows.remove(path);
    opened.panel().close();
    opened.window().dispose();
  }

  private boolean confirmDiscard(Window window) {
    return JOptionPane.showConfirmDialog(
            window,
            "Discard the unsaved edits in this window?",
            "Unsaved changes",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }

  private static boolean confirmDiscardAll(Window owner, int dirtyCount) {
    return JOptionPane.showConfirmDialog(
            owner,
            dirtyEditorCloseMessage(dirtyCount),
            "Unsaved file edits",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }

  private static boolean confirmDiscardForContextChange(Window owner, int dirtyCount) {
    return JOptionPane.showConfirmDialog(
            owner,
            dirtyEditorContextChangeMessage(dirtyCount),
            "Unsaved file edits",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }

  static String dirtyEditorCloseMessage(int dirtyCount) {
    if (dirtyCount < 1) {
      throw new IllegalArgumentException("dirty editor count must be positive");
    }
    return dirtyCount == 1
        ? "1 open file editor has unsaved edits. Close the Workspace and discard them?"
        : dirtyCount
            + " open file editors have unsaved edits. Close the Workspace and discard them?";
  }

  static String dirtyEditorContextChangeMessage(int dirtyCount) {
    if (dirtyCount < 1) {
      throw new IllegalArgumentException("dirty editor count must be positive");
    }
    return dirtyCount == 1
        ? "1 open file editor has unsaved edits. Apply the new Workspace connection"
            + " and discard it?"
        : dirtyCount
            + " open file editors have unsaved edits. Apply the new Workspace"
            + " connection and discard them?";
  }

  /** Failures use a modeless window too, so one missing stale path blocks nothing else. */
  private void showFailure(String title, String message) {
    if (closed) {
      return;
    }
    EditorWindow window = new EditorWindow(title);
    JTextArea text = WrappingLabel.create(message);
    text.setToolTipText(PlainText.tooltip(message));
    text.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
    JButton close = new JButton("Close");
    close.addActionListener(event -> window.dispose());
    installCloseShortcut(window.getRootPane(), menuShortcutMask(), window::dispose);
    JPanel actions = new JPanel();
    actions.add(close);
    JPanel content = new JPanel(new BorderLayout());
    content.add(text, BorderLayout.CENTER);
    content.add(actions, BorderLayout.SOUTH);
    window.setContentPane(content);
    window.setMinimumSize(new Dimension(480, 180));
    window.pack();
    window.setLocationRelativeTo(owner);
    window.setVisible(true);
  }

  @Override
  public void close() {
    closeAsync();
  }

  /**
   * Cancels file reads and completes after every accepted save has finished.
   *
   * <p>Closing a Workspace may discard dirty editor state after the caller's confirmation, but a
   * save already handed to the filesystem must finish before its SSH transport is closed. Waiting
   * happens on a virtual thread and never on the EDT.
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
    contextGeneration++;
    disposeOpenWindows();
    CompletableFuture<Void> activeClose = closeWorkers(worker, saveWorker, "bbv-file-editor");
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

  private static CompletableFuture<Void> closeWorkers(
      ExecutorService reads, ExecutorService saves, String owner) {
    CompletableFuture<Void> readClose =
        ExecutorClose.cancelAsync(reads, owner + "-reads").toCompletableFuture();
    CompletableFuture<Void> saveClose =
        ExecutorClose.drainAsync(saves, ExecutorClose.FILE_SAVE_DRAIN_GRACE, owner + "-saves")
            .toCompletableFuture();
    return CompletableFuture.allOf(readClose, saveClose);
  }

  private static ExecutorService singleWorker(String name) {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  private void disposeOpenWindows() {
    for (OpenWindow open : windows.values()) {
      open.panel.close();
      open.window.dispose();
    }
    windows.clear();
  }

  long contextGenerationForTest() {
    return contextGeneration;
  }

  /** A normal application window, not an owner-bound dialog that floats above the main UI. */
  static final class EditorWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    EditorWindow(String title) {
      super(title);
    }
  }

  static void installCloseShortcut(JRootPane rootPane, int shortcutMask, Runnable closeRequest) {
    Objects.requireNonNull(rootPane, "rootPane");
    Objects.requireNonNull(closeRequest, "closeRequest");
    KeyStroke shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_W, shortcutMask);
    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(shortcut, CLOSE_EDITOR_ACTION);
    rootPane
        .getActionMap()
        .put(
            CLOSE_EDITOR_ACTION,
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                closeRequest.run();
              }
            });
  }

  private static int menuShortcutMask() {
    try {
      return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    } catch (HeadlessException ignored) {
      return InputEvent.CTRL_DOWN_MASK;
    }
  }

  private record OpenWindow(EditorWindow window, FileEditorPanel panel) {}

  private record ResolvedFile(ExecutionFileSystem files, ExecutionPath path) {
    private ResolvedFile {
      Objects.requireNonNull(files, "files");
      Objects.requireNonNull(path, "path");
    }
  }

  @FunctionalInterface
  private interface Resolve {
    ResolvedFile run() throws IOException;
  }

  private static String describe(Throwable failure) {
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }

  private static void requireEventDispatchThread() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("file editor close checks must run on the EDT");
    }
  }

  @FunctionalInterface
  interface DirtyEditorConfirmation {
    boolean confirm(Window owner, int dirtyCount);
  }
}
