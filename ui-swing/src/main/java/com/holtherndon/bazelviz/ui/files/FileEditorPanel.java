package com.holtherndon.bazelviz.ui.files;

import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SyntaxTextTheme;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

/** One bounded local or remote text file, loaded and saved away from the EDT. */
public final class FileEditorPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final LocalExecutionFileSystem COMPATIBILITY_LOCAL_FILES =
      new LocalExecutionFileSystem();

  private final ExecutionFileSystem files;
  private final ExecutionPath requestedPath;
  private final boolean requestedEditable;
  private final Executor worker;
  private final Executor saveWorker;
  private final BooleanSupplier confirmDiscard;
  private final RSyntaxTextArea editor = new RSyntaxTextArea();
  private final RTextScrollPane scroll = new RTextScrollPane(editor);
  private final JLabel pathLabel = new JLabel(" ");
  private final JLabel status = new JLabel("Loading…");
  private final JButton save = new JButton("Save");
  private final JButton reload = new JButton("Reload");

  private TextFileDocument.ExecutionLoaded loaded;
  private boolean applyingText;
  private boolean dirty;
  private boolean closed;
  private long operationGeneration;
  private OptionalInt requestedLine = OptionalInt.empty();

  public FileEditorPanel(Path path, boolean editable, Executor worker) {
    this(
        COMPATIBILITY_LOCAL_FILES,
        COMPATIBILITY_LOCAL_FILES.path(path),
        editable,
        worker,
        worker,
        () -> true);
  }

  FileEditorPanel(Path path, boolean editable, Executor worker, BooleanSupplier confirmDiscard) {
    this(
        COMPATIBILITY_LOCAL_FILES,
        COMPATIBILITY_LOCAL_FILES.path(path),
        editable,
        worker,
        worker,
        confirmDiscard);
  }

  public FileEditorPanel(
      ExecutionFileSystem files, ExecutionPath path, boolean editable, Executor worker) {
    this(files, path, editable, worker, worker, () -> true);
  }

  FileEditorPanel(
      ExecutionFileSystem files,
      ExecutionPath path,
      boolean editable,
      Executor worker,
      BooleanSupplier confirmDiscard) {
    this(files, path, editable, worker, worker, confirmDiscard);
  }

  FileEditorPanel(
      ExecutionFileSystem files,
      ExecutionPath path,
      boolean editable,
      Executor worker,
      Executor saveWorker,
      BooleanSupplier confirmDiscard) {
    super(new BorderLayout(0, 4));
    this.files = Objects.requireNonNull(files, "files");
    this.requestedPath = Objects.requireNonNull(path, "path");
    this.requestedEditable = editable;
    this.worker = Objects.requireNonNull(worker, "worker");
    this.saveWorker = Objects.requireNonNull(saveWorker, "saveWorker");
    this.confirmDiscard = Objects.requireNonNull(confirmDiscard, "confirmDiscard");

    pathLabel.setText(path.toString());
    pathLabel.setToolTipText(PlainText.tooltip(path.toString()));
    pathLabel.getAccessibleContext().setAccessibleName("Open file path");
    PlainText.disableHtml(pathLabel);
    PlainText.disableHtml(status);

    editor.setEditable(false);
    editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    editor.setHighlightCurrentLine(true);
    editor.setBracketMatchingEnabled(true);
    editor.setAnimateBracketMatching(false);
    editor.setSyntaxEditingStyle(syntaxFor(path));
    editor
        .getAccessibleContext()
        .setAccessibleName(editable ? "Editable BUILD file" : "Read-only text file");
    scroll.setLineNumbersEnabled(true);
    SyntaxTextTheme.apply(editor, scroll);

    save.setVisible(editable);
    save.setEnabled(false);
    save.setToolTipText("Save this UTF-8 file (the editor refuses to overwrite a newer copy).");
    save.addActionListener(event -> save());
    reload.setEnabled(false);
    reload.setToolTipText("Read the file from disk again.");
    reload.addActionListener(event -> reload());

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
    actions.add(reload);
    actions.add(save);
    JPanel header = new JPanel(new BorderLayout(8, 0));
    header.add(pathLabel, BorderLayout.CENTER);
    header.add(actions, BorderLayout.EAST);
    header.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));

    status.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
    add(header, BorderLayout.NORTH);
    add(scroll, BorderLayout.CENTER);
    add(status, BorderLayout.SOUTH);

    editor
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent event) {
                changed();
              }

              @Override
              public void removeUpdate(DocumentEvent event) {
                changed();
              }

              @Override
              public void changedUpdate(DocumentEvent event) {
                changed();
              }
            });
    installSaveShortcut();
    load();
  }

  /** True when closing this panel would discard an unsaved edit. */
  public boolean isDirty() {
    return dirty;
  }

  /** Moves the caret to a one-based source line now, or after the pending load completes. */
  void revealLine(int line) {
    if (line < 1) {
      throw new IllegalArgumentException("source line must be positive");
    }
    requestedLine = OptionalInt.of(line);
    if (loaded != null) {
      moveCaretToRequestedLine();
    }
  }

  /** Prevents late worker callbacks from changing a disposed window. */
  public void close() {
    closed = true;
    operationGeneration++;
  }

  private void changed() {
    if (closed || applyingText || !requestedEditable || loaded == null) {
      return;
    }
    dirty = true;
    save.setEnabled(true);
    status.setText("Modified — not saved");
  }

  private void load() {
    if (closed) {
      return;
    }
    if (dirty && !confirmDiscard.getAsBoolean()) {
      return;
    }
    long wanted = ++operationGeneration;
    editor.setEditable(false);
    save.setEnabled(false);
    reload.setEnabled(false);
    status.setText("Loading…");
    worker.execute(
        () -> {
          try {
            TextFileDocument.ExecutionLoaded result = TextFileDocument.load(files, requestedPath);
            SwingUtilities.invokeLater(() -> loaded(result, wanted));
          } catch (IOException failure) {
            SwingUtilities.invokeLater(
                () -> failed("Could not read file: " + describe(failure), wanted));
          }
        });
  }

  private void loaded(TextFileDocument.ExecutionLoaded result, long wanted) {
    if (closed || wanted != operationGeneration) {
      return;
    }
    loaded = result;
    applyingText = true;
    try {
      editor.setText(result.text());
      moveCaretToRequestedLine();
    } finally {
      applyingText = false;
    }
    dirty = false;
    pathLabel.setText(result.path().toString());
    pathLabel.setToolTipText(PlainText.tooltip(result.path().toString()));
    editor.setEditable(requestedEditable);
    save.setEnabled(false);
    reload.setEnabled(true);
    status.setText(
        result.version().bytes()
            + " bytes · UTF-8 · "
            + (requestedEditable ? "editable" : "read-only"));
  }

  private void moveCaretToRequestedLine() {
    int line = requestedLine.orElse(1);
    Element root = editor.getDocument().getDefaultRootElement();
    int lineIndex = Math.min(line - 1, Math.max(0, root.getElementCount() - 1));
    Element target = root.getElement(lineIndex);
    int offset =
        target == null ? 0 : Math.min(target.getStartOffset(), editor.getDocument().getLength());
    editor.setCaretPosition(offset);
    SwingUtilities.invokeLater(() -> scrollCaretIntoView(offset));
  }

  private void scrollCaretIntoView(int offset) {
    if (closed || offset > editor.getDocument().getLength()) {
      return;
    }
    try {
      Rectangle2D location = editor.modelToView2D(offset);
      if (location != null) {
        editor.scrollRectToVisible(location.getBounds());
      }
    } catch (BadLocationException ignored) {
      // The document changed between the queued caret move and this repaint.
    }
  }

  private void save() {
    TextFileDocument.ExecutionLoaded baseline = loaded;
    if (closed || !requestedEditable || baseline == null || !dirty) {
      return;
    }
    String text = editor.getText();
    long wanted = ++operationGeneration;
    editor.setEditable(false);
    save.setEnabled(false);
    reload.setEnabled(false);
    status.setText("Saving…");
    saveWorker.execute(
        () -> {
          try {
            TextFileDocument.ExecutionLoaded result = TextFileDocument.save(files, baseline, text);
            SwingUtilities.invokeLater(() -> saved(result, text, wanted));
          } catch (IOException failure) {
            SwingUtilities.invokeLater(
                () -> failed("Could not save file: " + describe(failure), wanted));
          }
        });
  }

  private void saved(TextFileDocument.ExecutionLoaded result, String savedText, long wanted) {
    if (closed || wanted != operationGeneration) {
      return;
    }
    loaded = result;
    // A user cannot edit while the worker owns the save, so this is the
    // exact text on screen. Keeping it avoids resetting caret/selection.
    dirty = !editor.getText().equals(savedText);
    editor.setEditable(requestedEditable);
    save.setEnabled(dirty);
    reload.setEnabled(true);
    status.setText(
        dirty ? "Modified — not saved" : result.version().bytes() + " bytes · saved as UTF-8");
  }

  private void failed(String message, long wanted) {
    if (closed || wanted != operationGeneration) {
      return;
    }
    status.setText(message);
    status.setToolTipText(PlainText.tooltip(message));
    editor.setEditable(requestedEditable && loaded != null);
    save.setEnabled(requestedEditable && loaded != null && dirty);
    reload.setEnabled(true);
  }

  private void reload() {
    load();
  }

  private void installSaveShortcut() {
    int mask;
    try {
      mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    } catch (HeadlessException headless) {
      mask = InputEvent.CTRL_DOWN_MASK;
    }
    editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), "save-file");
    editor
        .getActionMap()
        .put(
            "save-file",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                save();
              }
            });
  }

  static String syntaxFor(Path path) {
    return syntaxForName(path.getFileName().toString());
  }

  static String syntaxFor(ExecutionPath path) {
    return syntaxForName(path.fileName());
  }

  private static String syntaxForName(String fileName) {
    return FileNameType.classify(fileName).syntaxStyle();
  }

  /** Visible to focused headless tests. */
  RSyntaxTextArea editorForTest() {
    return editor;
  }

  JButton saveForTest() {
    return save;
  }

  JButton reloadForTest() {
    return reload;
  }

  String statusForTest() {
    return status.getText();
  }

  private static String describe(Throwable failure) {
    return failure.getMessage() == null ? failure.toString() : failure.getMessage();
  }
}
