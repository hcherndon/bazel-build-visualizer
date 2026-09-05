package com.holtherndon.bazelviz.ui.errors;

import com.holtherndon.bazelviz.ui.capture.ConsoleModel;
import com.holtherndon.bazelviz.ui.capture.ConsoleTextPane;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.util.Objects;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.text.BadLocationException;

/** ANSI-aware output from one recorded Bazel progress event. */
final class ErrorConsolePanel extends JPanel {

  private static final long serialVersionUID = 1L;

  /** Lines retained from each stream in the Errors detail pane. */
  static final int MAX_LINES = 400;

  private final JTabbedPane tabs = new JTabbedPane();
  private final StreamPane stderr = new StreamPane("stderr");
  private final StreamPane stdout = new StreamPane("stdout");

  ErrorConsolePanel() {
    super(new BorderLayout());
    setBorder(BorderFactory.createTitledBorder("Console output"));
    getAccessibleContext().setAccessibleName("Console output");
    add(tabs, BorderLayout.CENTER);
    clear();
  }

  /** Interprets a decoded event away from the EDT. */
  static Content parse(String stderr, String stdout) {
    return new Content(parseStream(stderr), parseStream(stdout));
  }

  private static Optional<ConsoleModel.Transcript> parseStream(String text) {
    if (text.isEmpty()) {
      return Optional.empty();
    }
    ConsoleModel model = new ConsoleModel(MAX_LINES);
    model.append(text);
    return Optional.of(model.transcript());
  }

  /** Replaces the displayed event. EDT only. */
  void show(Content content) {
    Objects.requireNonNull(content, "content");
    tabs.removeAll();
    content.stderr().ifPresent(value -> addStream("stderr", stderr, value));
    content.stdout().ifPresent(value -> addStream("stdout", stdout, value));
    setVisible(tabs.getTabCount() > 0);
    revalidate();
    repaint();
  }

  /** Hides stale output while another row is selected or loading. EDT only. */
  void clear() {
    tabs.removeAll();
    setVisible(false);
  }

  private void addStream(String title, StreamPane pane, ConsoleModel.Transcript transcript) {
    pane.show(transcript);
    tabs.addTab(title, pane);
  }

  String textForTest(String stream) {
    return switch (stream) {
      case "stderr" -> stderr.text();
      case "stdout" -> stdout.text();
      default -> throw new IllegalArgumentException("unknown stream " + stream);
    };
  }

  ConsoleTextPane textPaneForTest(String stream) {
    return switch (stream) {
      case "stderr" -> stderr.text;
      case "stdout" -> stdout.text;
      default -> throw new IllegalArgumentException("unknown stream " + stream);
    };
  }

  String limitTextForTest(String stream) {
    return switch (stream) {
      case "stderr" -> stderr.limit.getText();
      case "stdout" -> stdout.limit.getText();
      default -> throw new IllegalArgumentException("unknown stream " + stream);
    };
  }

  record Content(
      Optional<ConsoleModel.Transcript> stderr, Optional<ConsoleModel.Transcript> stdout) {
    Content {
      Objects.requireNonNull(stderr, "stderr");
      Objects.requireNonNull(stdout, "stdout");
    }
  }

  private static final class StreamPane extends JPanel {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final JLabel limit = new JLabel(" ");
    private final ConsoleTextPane text = new ConsoleTextPane();

    private StreamPane(String name) {
      super(new BorderLayout());
      this.name = name;
      PlainText.disableHtml(limit);
      limit.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
      limit.setVisible(false);
      text.getAccessibleContext().setAccessibleName("Console output (" + name + ")");
      add(limit, BorderLayout.NORTH);
      add(new JScrollPane(text), BorderLayout.CENTER);
    }

    private void show(ConsoleModel.Transcript transcript) {
      text.showTranscript(transcript);
      long dropped = transcript.droppedLines();
      limit.setVisible(dropped > 0);
      if (dropped > 0) {
        limit.setText(
            "Showing the last "
                + EntityFormat.count(MAX_LINES)
                + " lines of "
                + name
                + "; "
                + EntityFormat.count(dropped)
                + " earlier line(s) are not shown. The event remains complete in the journal.");
      }
    }

    private String text() {
      try {
        return text.getDocument().getText(0, text.getDocument().getLength());
      } catch (BadLocationException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }
}
