package com.holtherndon.bazelviz.ui.preferences;

import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.SyntaxTextTheme;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/** Callback-only Preferences content for the optional local workspace-discovery script. */
public final class WorkspaceDiscoveryPreferencesPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "dash", "ksh", "ash");
  private static final Set<String> JAVASCRIPT_RUNTIMES =
      Set.of("node", "nodejs", "deno", "bun", "js", "javascript");

  private static final String INSTRUCTIONS =
      """
      This optional script runs on this computer. A non-empty script must begin with a
      shebang. Each run replaces the previous discovered workspace list; discovered
      workspaces are temporary and use bazel as their executable.

      Print one workspace per non-comment stdout line using one of these formats:
      local|name|working-directory
      ssh|name|destination|working-directory

      For an ssh row, destination is an OpenSSH destination or configured Host alias.
      Configure custom ports, jump hosts, identity files, and similar connection details
      in ~/.ssh/config.

      Run Discovery Now saves the current editor text before starting discovery.
      """;

  private final Consumer<String> saveCallback;
  private final Consumer<String> runDiscoveryCallback;
  private final RSyntaxTextArea editor;
  private final RTextScrollPane editorScroll;
  private final JLabel detectedLanguage = new JLabel();
  private final JLabel operationStatus = new JLabel(" ");
  private final JTextArea instructions = WrappingLabel.create(INSTRUCTIONS);
  private final JButton runDiscovery = new JButton("Run Discovery Now");
  private final JButton save = new JButton("Save");

  /**
   * Creates reusable Preferences content. Callbacks run on the Swing event thread and perform no
   * work unless the host chooses to do so.
   *
   * @param initialScript script currently stored by the host
   * @param saveCallback receives the current editor text when Save is pressed
   * @param runDiscoveryCallback receives the current editor text when Run Discovery Now is pressed;
   *     the host saves that text before starting discovery
   */
  public WorkspaceDiscoveryPreferencesPanel(
      String initialScript, Consumer<String> saveCallback, Consumer<String> runDiscoveryCallback) {
    super(new BorderLayout());
    this.saveCallback = Objects.requireNonNull(saveCallback, "saveCallback");
    this.runDiscoveryCallback =
        Objects.requireNonNull(runDiscoveryCallback, "runDiscoveryCallback");

    editor = new RSyntaxTextArea(Objects.requireNonNull(initialScript, "initialScript"), 13, 72);
    editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    editor.setTabSize(4);
    editor.setMarkOccurrences(true);
    editor.setHighlightCurrentLine(true);
    editor.setBracketMatchingEnabled(true);
    editor.setAnimateBracketMatching(false);
    editor.getAccessibleContext().setAccessibleName("Workspace discovery script");

    editorScroll = new RTextScrollPane(editor);
    editorScroll.setLineNumbersEnabled(true);
    editorScroll.setMinimumSize(new Dimension(360, 180));

    PlainText.disableHtml(detectedLanguage);
    detectedLanguage.getAccessibleContext().setAccessibleName("Detected script language");
    detectedLanguage.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
    instructions.getAccessibleContext().setAccessibleName("Workspace discovery instructions");

    JPanel editorPanel = new JPanel(new BorderLayout(0, 4));
    JLabel scriptLabel = PlainText.disableHtml(new JLabel("Discovery script"));
    scriptLabel.setLabelFor(editor);
    editorPanel.add(scriptLabel, BorderLayout.NORTH);
    editorPanel.add(editorScroll, BorderLayout.CENTER);
    editorPanel.add(detectedLanguage, BorderLayout.SOUTH);

    JPanel content = new JPanel(new BorderLayout(0, 10));
    content.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
    content.add(instructions, BorderLayout.NORTH);
    content.add(editorPanel, BorderLayout.CENTER);
    content.add(buttons(), BorderLayout.SOUTH);

    SectionPane discovery = new SectionPane("Workspace Discovery", content);
    add(discovery, BorderLayout.CENTER);

    editor
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent event) {
                updateDetectedLanguage();
              }

              @Override
              public void removeUpdate(DocumentEvent event) {
                updateDetectedLanguage();
              }

              @Override
              public void changedUpdate(DocumentEvent event) {
                updateDetectedLanguage();
              }
            });
    updateDetectedLanguage();
  }

  /** Updates progress without performing work. Must be called on the Swing event thread. */
  public void setOperationState(boolean busy, String status) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("preferences UI changes must run on the EDT");
    }
    runDiscovery.setEnabled(!busy);
    save.setEnabled(!busy);
    editor.setEditable(!busy);
    String text = Objects.requireNonNull(status, "status").strip();
    operationStatus.setText(text.isEmpty() ? " " : text);
    operationStatus.setToolTipText(text.isEmpty() ? null : text);
  }

  /** Replaces the editor text after the host loads it away from the EDT. */
  public void setScript(String script) {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("preferences UI changes must run on the EDT");
    }
    editor.setText(Objects.requireNonNull(script, "script"));
    editor.setCaretPosition(0);
  }

  private JPanel buttons() {
    runDiscovery.setToolTipText("Save the current script, then run workspace discovery.");
    runDiscovery.addActionListener(event -> runDiscoveryCallback.accept(editor.getText()));

    save.setToolTipText("Save the current workspace-discovery script.");
    save.addActionListener(event -> saveCallback.accept(editor.getText()));

    PlainText.disableHtml(operationStatus);
    operationStatus
        .getAccessibleContext()
        .setAccessibleName("Workspace discovery preference status");

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
    actions.add(runDiscovery);
    actions.add(save);
    JPanel footer = new JPanel(new BorderLayout(8, 0));
    footer.add(operationStatus, BorderLayout.CENTER);
    footer.add(actions, BorderLayout.EAST);
    return footer;
  }

  private void updateDetectedLanguage() {
    ScriptLanguage language = detectLanguage(editor.getText());
    boolean firstUpdate = detectedLanguage.getText().isEmpty();
    boolean styleChanged = !language.syntaxStyle().equals(editor.getSyntaxEditingStyle());
    if (styleChanged) {
      editor.setSyntaxEditingStyle(language.syntaxStyle());
    }
    detectedLanguage.setText("Detected language: " + language.displayName());
    if (firstUpdate || styleChanged) {
      SyntaxTextTheme.apply(editor, editorScroll);
    }
  }

  /** Pure first-line shebang detection; package-visible for focused UI tests. */
  static ScriptLanguage detectLanguage(String script) {
    Objects.requireNonNull(script, "script");
    int lineBreak = script.indexOf('\n');
    String firstLine = lineBreak < 0 ? script : script.substring(0, lineBreak);
    if (firstLine.endsWith("\r")) {
      firstLine = firstLine.substring(0, firstLine.length() - 1);
    }
    if (!firstLine.startsWith("#!")) {
      return ScriptLanguage.PLAIN_TEXT;
    }
    String command = firstLine.substring(2).strip();
    if (command.isEmpty()) {
      return ScriptLanguage.PLAIN_TEXT;
    }
    String[] words = command.split("\\s+");
    String interpreter = executableName(words[0]);
    if ("env".equals(interpreter)) {
      interpreter = envInterpreter(words);
    }
    if (SHELLS.contains(interpreter)) {
      return ScriptLanguage.SHELL;
    }
    if (interpreter.startsWith("python")) {
      return ScriptLanguage.PYTHON;
    }
    if (interpreter.startsWith("ruby")) {
      return ScriptLanguage.RUBY;
    }
    if (interpreter.startsWith("perl")) {
      return ScriptLanguage.PERL;
    }
    if (JAVASCRIPT_RUNTIMES.contains(interpreter)) {
      return ScriptLanguage.JAVASCRIPT;
    }
    return ScriptLanguage.PLAIN_TEXT;
  }

  private static String envInterpreter(String[] words) {
    for (int index = 1; index < words.length; index++) {
      String candidate = words[index];
      if (candidate.startsWith("-") || candidate.contains("=")) {
        continue;
      }
      return executableName(candidate);
    }
    return "";
  }

  private static String executableName(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return path.substring(slash + 1).toLowerCase(Locale.ROOT);
  }

  RSyntaxTextArea editorForTest() {
    return editor;
  }

  RTextScrollPane editorScrollForTest() {
    return editorScroll;
  }

  JLabel detectedLanguageForTest() {
    return detectedLanguage;
  }

  JTextArea instructionsForTest() {
    return instructions;
  }

  JLabel operationStatusForTest() {
    return operationStatus;
  }

  enum ScriptLanguage {
    PLAIN_TEXT("Plain text", SyntaxConstants.SYNTAX_STYLE_NONE),
    SHELL("Shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
    PYTHON("Python", SyntaxConstants.SYNTAX_STYLE_PYTHON),
    RUBY("Ruby", SyntaxConstants.SYNTAX_STYLE_RUBY),
    PERL("Perl", SyntaxConstants.SYNTAX_STYLE_PERL),
    JAVASCRIPT("JavaScript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);

    private final String displayName;
    private final String syntaxStyle;

    ScriptLanguage(String displayName, String syntaxStyle) {
      this.displayName = displayName;
      this.syntaxStyle = syntaxStyle;
    }

    String displayName() {
      return displayName;
    }

    String syntaxStyle() {
      return syntaxStyle;
    }
  }
}
