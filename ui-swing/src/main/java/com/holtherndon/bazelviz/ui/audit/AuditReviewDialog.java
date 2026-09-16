package com.holtherndon.bazelviz.ui.audit;

import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Review;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;

/**
 * Inert, worker-prepared review of both builds; this dialog performs no filesystem or process I/O.
 */
public final class AuditReviewDialog extends JDialog {
  private static final long serialVersionUID = 1L;

  public enum Choice {
    RUN_BOTH,
    REVIEW_A,
    REVIEW_B,
    ENABLE_LOGS,
    CANCEL
  }

  private Choice choice = Choice.CANCEL;

  public AuditReviewDialog(Window owner, Review review) {
    super(owner, "Review hermeticity diagnostic", ModalityType.DOCUMENT_MODAL);
    Content content =
        content(
            review,
            selected -> {
              choice = selected;
              dispose();
            });
    setContentPane(content.panel());
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    getRootPane().setDefaultButton(content.cancel());
    setMinimumSize(new Dimension(480, 360));
    setSize(820, 660);
    if (owner != null && owner.getGraphicsConfiguration() != null) {
      var screen = owner.getGraphicsConfiguration().getBounds();
      setSize(
          Math.min(getWidth(), Math.max(480, screen.width - 64)),
          Math.min(getHeight(), Math.max(360, screen.height - 64)));
    }
    setLocationRelativeTo(owner);
  }

  public Choice choice() {
    return choice;
  }

  /** Package seam keeps safety gates and narrow-layout checks testable without a desktop. */
  record Content(JPanel panel, JButton run, JButton cancel, JTabbedPane tabs) {}

  static Content content(Review review, Consumer<Choice> decide) {
    Objects.requireNonNull(review, "review");
    Objects.requireNonNull(decide, "decide");
    JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    JTextArea title = WrappingLabel.create("Hermeticity diagnostic · two controlled builds");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
    panel.add(title, BorderLayout.NORTH);
    JTabbedPane tabs = new JTabbedPane();
    PlainText.disableHtml(tabs);
    tabs.addTab("Summary", scroll(summary(review, decide)));
    tabs.addTab("Commands & capture", scroll(commands(review, decide)));
    tabs.addTab("Protocol changes", scroll(changes(review)));
    tabs.addTab("Coverage & blockers", scroll(coverage(review)));
    panel.add(tabs, BorderLayout.CENTER);

    JButton run = button("Run both builds", Choice.RUN_BOTH, decide);
    run.setEnabled(review.canLaunch());
    run.setToolTipText(
        review.canLaunch()
            ? "Approves the rc-free check, runs both builds and opens their comparison."
            : "Cannot start: " + String.join("; ", review.blockers()));
    PlainText.disableHtml(run);
    JButton cancel = button("Cancel", Choice.CANCEL, decide);
    JPanel footer = new JPanel(new BorderLayout(0, 4));
    footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
    footer.add(
        WrappingLabel.create(
            review.canLaunch()
                ? "Run both builds approves two uncached builds with all bazelrc files ignored."
                : "Cannot start yet. Fix the setup issues at the top of Summary."),
        BorderLayout.NORTH);
    JPanel buttons = new JPanel(new WrapLayout(FlowLayout.RIGHT, 6, 4));
    buttons.add(cancel);
    buttons.add(run);
    footer.add(buttons, BorderLayout.CENTER);
    panel.add(footer, BorderLayout.SOUTH);
    panel
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-audit");
    panel
        .getActionMap()
        .put(
            "cancel-audit",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                decide.accept(Choice.CANCEL);
              }
            });
    return new Content(panel, run, cancel, tabs);
  }

  private static JPanel summary(Review review, Consumer<Choice> decide) {
    JPanel body = stack();
    if (!review.canLaunch()) {
      JPanel issues = stack();
      issues.add(WrappingLabel.create(String.join("\n\n", review.blockers())));
      if (executionLogsDisabled(review)) {
        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 4));
        JButton enable = button("Enable execution logs", Choice.ENABLE_LOGS, decide);
        enable.setToolTipText(
            "Re-enable execution-log capture for both builds, then check setup again.");
        actions.add(enable);
        issues.add(actions);
      }
      addSection(body, "Cannot start yet", issues);
    }
    addSection(
        body,
        "Two builds, one comparison",
        WrappingLabel.create(
            "Clean a private output base → build A → preserve its log → clean again → build B"
                + " → compare automatically. Your normal output base and Bazel links are untouched."
                + " Keep repository files unchanged until the check finishes."));
    addSection(
        body,
        "Different from your normal build",
        WrappingLabel.create(
            "This check ignores system, user and workspace rc files, which may change toolchains,"
                + " platforms and build behavior. Both builds run on the selected machine without"
                + " disk or remote action-cache reuse. Two full builds can take much longer than"
                + " a cached build (two jobs, 1 GiB private server heap). Only run repositories you"
                + " trust: builds execute their code."));
    JPanel location = stack();
    addField(
        location,
        "Machine",
        review.a().remote().map(Preflight.RemoteDetails::host).orElse("This computer"));
    addField(
        location, "Working directory", review.protocol().original().workingDirectory().toString());
    addField(
        location, "Private output base — used for both builds", review.protocol().outputBase());
    addField(location, "Retained audit evidence", review.directory().toString());
    addSection(body, "Scope & storage", location);
    addSection(
        body,
        "Meaning of the result",
        WrappingLabel.create(
            "Matching results mean no differences were observed in the compared actions, not that"
                + " the build is proven hermetic. Cached, ambiguous or missing observations remain"
                + " visible as coverage gaps. Historic output digests do not mean A's file contents"
                + " will still be available after the second clean."));
    return body;
  }

  private static boolean executionLogsDisabled(Review review) {
    return List.of(review.a(), review.b()).stream()
        .anyMatch(
            capture ->
                capture.request().vetoed().contains(Capability.EXECUTION_LOG_COMPACT)
                    || capture.request().vetoed().contains(Capability.EXECUTION_LOG_BINARY));
  }

  private static JPanel commands(Review review, Consumer<Choice> decide) {
    JPanel body = stack();
    body.add(
        WrappingLabel.create(
            "Capture settings are optional to inspect; you do not need to approve each build"
                + " separately. Changes apply to both captures."));
    JPanel settings = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
    settings.add(button("Capture A settings…", Choice.REVIEW_A, decide));
    settings.add(button("Capture B settings…", Choice.REVIEW_B, decide));
    body.add(settings);
    body.add(
        WrappingLabel.create(
            "Each numbered line is one exact argument; quotes and escapes make spaces and control"
                + " characters explicit. These are displayed data, not shell commands executed by"
                + " this dialog."));
    addCommand(body, "Your original command", review.protocol().original());
    addCommand(
        body,
        "Before each clean — verify measured Bazel version",
        review.protocol().build().toBuilder()
            .command("version")
            .commandArgs(List.of("--gnu_format"))
            .targets(List.of())
            .build());
    addCommand(
        body,
        "Before each clean — verify private output base",
        review.protocol().outputBaseProbe());
    addCommand(body, "Clean A — private output base only", review.protocol().clean());
    addCommand(body, "Build A — reviewed capture command", review.a().plan().effective());
    addCommand(
        body, "Clean B — after preserving A and checking sources", review.protocol().clean());
    addCommand(body, "Build B — reviewed capture command", review.b().plan().effective());
    addCommand(
        body, "Shutdown — only after known client termination", review.protocol().shutdown());
    body.add(
        WrappingLabel.create(
            "Both captures allocate their own final session/raw paths at launch. Their executed"
                + " instrumentation plans retain the final paths. The audit never cleans your"
                + " ordinary Bazel output base or removes your normal convenience links."));
    return body;
  }

  private static JPanel changes(Review review) {
    JPanel body = stack();
    for (var change : review.protocol().changes()) {
      JPanel row = stack();
      row.add(code(change.argument()));
      row.add(WrappingLabel.create(change.explanation()));
      addSection(body, "Protocol option", row);
    }
    body.add(
        WrappingLabel.create(
            "Use Commands & capture to inspect added capture flags, resolve conflicts or decline"
                + " optional capture data. Declining a required execution log disables this audit."
                + " No aquery/cquery or ordinary auxiliary imports run between these builds."));
    return body;
  }

  private static JPanel coverage(Review review) {
    JPanel body = stack();
    addSection(
        body,
        review.canLaunch() ? "Launch checks" : "Blockers",
        WrappingLabel.create(
            review.canLaunch()
                ? "The current protocol and capture plans can launch. Source checks and evidence"
                    + " validation still run before and between the builds."
                : String.join("\n\n", review.blockers())));
    for (String notice : review.notices()) {
      body.add(WrappingLabel.create(notice));
      body.add(Box.createVerticalStrut(10));
    }
    for (Preflight capture : List.of(review.a(), review.b())) {
      if (!capture.plan().warnings().isEmpty()) {
        addSection(
            body,
            capture == review.a() ? "Capture A warnings" : "Capture B warnings",
            WrappingLabel.create(String.join("\n\n", capture.plan().warnings())));
      }
    }
    return body;
  }

  private static JButton button(String text, Choice choice, Consumer<Choice> decide) {
    JButton button = new JButton(text);
    button.addActionListener(event -> decide.accept(choice));
    return button;
  }

  private static JScrollPane scroll(JPanel body) {
    // BoxLayout shares an alignment axis. Mixing centered text with left-aligned
    // sections pushes those sections into the right half of the available width.
    for (Component child : body.getComponents()) {
      if (child instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
    }
    ScrollableViewport viewport = new ScrollableViewport(new BorderLayout());
    viewport.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    viewport.add(body, BorderLayout.NORTH);
    JScrollPane scroll =
        new JScrollPane(
            viewport,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    return scroll;
  }

  private static JPanel stack() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    return panel;
  }

  private static void addSection(JPanel parent, String name, JComponent content) {
    SectionPane section = new SectionPane(name, content);
    section.setAlignmentX(Component.LEFT_ALIGNMENT);
    parent.add(section);
    parent.add(Box.createVerticalStrut(8));
  }

  private static void addField(JPanel parent, String name, String value) {
    JTextArea label = WrappingLabel.create(name);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    parent.add(label);
    parent.add(WrappingLabel.create(value));
    parent.add(Box.createVerticalStrut(6));
  }

  private static void addCommand(JPanel parent, String name, BazelCommand command) {
    addSection(parent, name, code(commandText(command)));
  }

  static String commandText(BazelCommand command) {
    StringBuilder text = new StringBuilder();
    List<String> argv = command.toArgv();
    for (int index = 0; index < argv.size(); index++) {
      if (index > 0) {
        text.append('\n');
      }
      String argument =
          argv.get(index)
              .replace("\\", "\\\\")
              .replace("\"", "\\\"")
              .replace("\n", "\\n")
              .replace("\r", "\\r")
              .replace("\t", "\\t");
      text.append('[').append(index).append("] \"").append(argument).append('"');
    }
    return text.toString();
  }

  private static JTextArea code(String text) {
    JTextArea area = WrappingLabel.create(text);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
    area.setWrapStyleWord(false);
    return area;
  }
}
