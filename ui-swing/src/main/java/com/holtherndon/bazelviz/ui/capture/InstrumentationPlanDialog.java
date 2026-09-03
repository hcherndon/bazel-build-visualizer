package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.AuxiliaryCommandPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

/**
 * Shows what will run before it runs (plan 4.3, ADR-007).
 *
 * <p>The dialog is the visible form of the promise: the command the user typed
 * and the command that will execute, side by side, with one line of explanation
 * for every difference and the cost of each addition. A conflict is presented
 * as the choices plan 8.5 requires, each labelled with what it gives up, and
 * Launch stays disabled until one is chosen — the type system enforces the same
 * rule underneath, and this is where the user meets it.
 *
 * <p>Modal, and constructed on the EDT. Nothing here does I/O: a plan is an
 * inert value, and every button hands its decision back to the caller.
 */
public final class InstrumentationPlanDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** What the user chose. */
    public enum Choice {
        LAUNCH,
        CANCEL,
        /** A conflict resolution was picked; the caller re-plans and reopens. */
        RESOLVE,
        /** A flag was turned off; the caller re-plans and reopens. */
        VETO
    }

    private Choice choice = Choice.CANCEL;
    private String resolutionId;
    private PlanConflict.Kind resolvedKind;
    private Capability vetoedCapability;

    public InstrumentationPlanDialog(java.awt.Window owner, Preflight preflight) {
        super(owner, "Review build", ModalityType.APPLICATION_MODAL);
        InstrumentationPlan plan = preflight.plan();

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel(plan.canLaunch() ? "Ready to launch" : "Review required");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(wrapped(plan.canLaunch()
                ? "Confirm the command below. Capture additions and technical details are"
                        + " available without crowding the launch decision."
                : "Resolve the decisions below before this build can start."));
        body.add(Box.createVerticalStrut(8));

        body.add(section("Command", commandSummary(preflight)));
        body.add(Box.createVerticalStrut(8));

        int changed = plan.addedFlags().size() + plan.replacedFlags().size();
        if (changed > 0) {
            body.add(new DisclosurePanel(
                    "Capture changes · " + changed,
                    captureChanges(plan),
                    false));
            body.add(Box.createVerticalStrut(8));
        }

        if (!plan.auxiliaryCommands().isEmpty()) {
            body.add(new DisclosurePanel(
                    "Post-build commands · " + plan.auxiliaryCommands().size(),
                    auxiliaryCommands(plan),
                    false));
            body.add(Box.createVerticalStrut(8));
        }

        if (!plan.conflicts().isEmpty() || !plan.warnings().isEmpty()
                || !plan.errors().isEmpty()) {
            body.add(section("Decisions & warnings", attention(plan)));
            body.add(Box.createVerticalStrut(8));
        }

        body.add(new DisclosurePanel(
                "Bazel, workspace & captured outputs",
                technicalDetails(preflight),
                false));

        JScrollPane scroll = new JScrollPane(body);
        scroll.setPreferredSize(new Dimension(820, 600));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JButton launch = new JButton("Launch");
        launch.setEnabled(plan.canLaunch());
        launch.setToolTipText(plan.canLaunch()
                ? "Run the command shown above"
                : "Resolve the highlighted conflict first");
        launch.addActionListener(event -> {
            choice = Choice.LAUNCH;
            dispose();
        });

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> {
            choice = Choice.CANCEL;
            dispose();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancel);
        buttons.add(launch);

        setLayout(new BorderLayout());
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(plan.canLaunch() ? launch : cancel);
        pack();
        setLocationRelativeTo(owner);
    }

    /** What the user decided. */
    public Choice choice() {
        return choice;
    }

    /** The resolution chosen, when {@link #choice()} is {@link Choice#RESOLVE}. */
    public Optional<String> resolutionId() {
        return Optional.ofNullable(resolutionId);
    }

    /** The conflict that was resolved. */
    public Optional<PlanConflict.Kind> resolvedKind() {
        return Optional.ofNullable(resolvedKind);
    }

    /** The capability the user turned off, when {@link #choice()} is {@link Choice#VETO}. */
    public Optional<Capability> vetoedCapability() {
        return Optional.ofNullable(vetoedCapability);
    }

    // ------------------------------------------------------------------ parts

    private static JComponent commandSummary(Preflight preflight) {
        InstrumentationPlan plan = preflight.plan();
        JPanel content = stack();
        content.add(labelled("Working directory",
                wrapped(preflight.workspace().workingDirectory().toString())));
        content.add(Box.createVerticalStrut(6));
        String original = preflight.executable().entered() + " "
                + String.join(" ", plan.original().userVisibleArgs());
        content.add(labelled("Your command", monospace(original.stripTrailing())));
        content.add(Box.createVerticalStrut(6));
        content.add(labelled("Command to run",
                monospace(String.join(" ", plan.effective().toArgv()))));
        content.add(Box.createVerticalStrut(6));
        String environment = plan.effective().inheritance().toString().replace('_', ' ')
                .toLowerCase(java.util.Locale.ROOT) + " · "
                + plan.effective().environmentOverrides().size() + " explicit override(s)";
        content.add(labelled("Environment", wrapped(environment)));
        return content;
    }

    private JComponent captureChanges(InstrumentationPlan plan) {
        JPanel content = stack();
        if (!plan.addedFlags().isEmpty()) {
            content.add(heading("Added instrumentation"));
            for (AddedFlag flag : plan.addedFlags()) {
                content.add(addedFlagRow(flag));
                content.add(Box.createVerticalStrut(6));
            }
        }
        if (!plan.replacedFlags().isEmpty()) {
            content.add(heading("Approved replacements"));
            for (var replaced : plan.replacedFlags()) {
                content.add(wrapped(replaced.original() + "  →  "
                        + (replaced.isRemoval() ? "(removed)" : replaced.replacement())
                        + "\n" + replaced.reason()));
                content.add(Box.createVerticalStrut(6));
            }
        }
        return content;
    }

    private static JComponent auxiliaryCommands(InstrumentationPlan plan) {
        JPanel content = stack();
        for (AuxiliaryCommandPlan command : plan.auxiliaryCommands()) {
            StringBuilder text = new StringBuilder(command.purpose())
                    .append("\nWhen: ").append(command.timing())
                    .append(" · cost: ").append(command.estimatedCost().displayName())
                    .append(" · failure: ")
                    .append(command.failureIsFatal() ? "stops the capture" : "non-fatal")
                    .append("\nCommand: ").append(String.join(" ", command.argv()))
                    .append("\nOutput: ").append(command.outputPath());
            if (!command.carriedOptions().isEmpty()) {
                text.append("\nCarries forward: ")
                        .append(String.join(" ", command.carriedOptions()));
            }
            if (!command.droppedOptions().isEmpty()) {
                text.append("\nNot carried forward: ")
                        .append(String.join(" ", command.droppedOptions()));
            }
            content.add(labelled(command.label(), wrapped(text.toString())));
            content.add(Box.createVerticalStrut(8));
        }
        return content;
    }

    private JComponent attention(InstrumentationPlan plan) {
        JPanel content = stack();
        for (PlanConflict conflict : plan.conflicts()) {
            content.add(conflictPanel(conflict));
            content.add(Box.createVerticalStrut(6));
        }
        for (String error : plan.errors()) {
            content.add(wrapped("Cannot launch: " + error));
        }
        for (String warning : plan.warnings()) {
            content.add(wrapped("Note: " + warning));
        }
        return content;
    }

    private static JComponent technicalDetails(Preflight preflight) {
        InstrumentationPlan plan = preflight.plan();
        JPanel content = stack();
        preflight.remote().ifPresent(remote -> {
            content.add(labelled("Execution host", wrapped(
                    remote.host() + " · SSH\n"
                            + "Remote working directory: " + remote.workingDirectory()
                            + "\nRemote BES address: " + remote.remoteBesBackend()
                            + "\nDesktop listener: " + remote.localBesListener()
                            + "\nPrivate staging: " + remote.stagingDirectory()
                            + "\nBuild output uses a forced TTY; stdout and stderr are merged."
                            + " Files are copied with SFTP.")));
            content.add(Box.createVerticalStrut(6));
        });
        content.add(labelled("Bazel",
                wrapped(preflight.executable().displayName() + "\n"
                        + preflight.executable().resolved())));
        content.add(Box.createVerticalStrut(6));
        String workspace = preflight.workspace().workspaceRoot()
                .map(Object::toString).orElse("not detected");
        content.add(labelled("Workspace root",
                wrapped(workspace + " · " + preflight.workspace().detection())));
        content.add(Box.createVerticalStrut(6));
        content.add(labelled("Capability check",
                wrapped(preflight.capabilities().detection().toString())));
        content.add(Box.createVerticalStrut(6));
        content.add(labelled("Capture preset", wrapped(plan.preset().displayName())));
        if (!plan.expectedOutputs().isEmpty()) {
            content.add(Box.createVerticalStrut(6));
            content.add(labelled("Files this plan may create",
                    wrapped(plan.expectedOutputs().stream()
                            .map(Object::toString)
                            .collect(java.util.stream.Collectors.joining("\n")))));
        }
        if (!plan.sourceAvailability().bySource().isEmpty()) {
            content.add(Box.createVerticalStrut(6));
            String availability = plan.sourceAvailability().bySource().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + ": "
                            + entry.getValue().availability() + " — "
                            + entry.getValue().reason())
                    .collect(java.util.stream.Collectors.joining("\n"));
            content.add(labelled("Session data", wrapped(availability)));
        }
        return content;
    }

    private static JPanel stack() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JComponent labelled(String label, JComponent value) {
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(label);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        row.add(name, BorderLayout.NORTH);
        row.add(value, BorderLayout.CENTER);
        return row;
    }

    private static JComponent section(String title, JComponent content) {
        SectionPane section = new SectionPane(title, content);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        return section;
    }

    private JComponent addedFlagRow(AddedFlag flag) {
        JComponent description = addedFlagDescription(flag);
        if (!flag.userCanDisable() || !flag.isApplied()) {
            return description;
        }
        // ADR-007: "any flag can be vetoed". The dialog said which flags could
        // be turned off and offered no way to turn one off, which is a promise
        // rendered as text. Unticking re-plans and reopens, so the effective
        // command the user finally approves is the one that runs.
        javax.swing.JCheckBox enabled = new javax.swing.JCheckBox(flag.argv(), true);
        enabled.setAlignmentX(Component.LEFT_ALIGNMENT);
        enabled.setToolTipText("Untick to leave this flag out. "
                + describeLoss(flag));
        enabled.addActionListener(event -> {
            if (!enabled.isSelected()) {
                choice = Choice.VETO;
                vetoedCapability = flag.capability();
                dispose();
            }
        });

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(enabled);
        row.add(description);
        return row;
    }

    private static String describeLoss(AddedFlag flag) {
        return "Without it, " + flag.enables().name().toLowerCase(java.util.Locale.ROOT)
                + " data this flag provides will not be in the session.";
    }

    private JComponent addedFlagDescription(AddedFlag flag) {
        StringBuilder text = new StringBuilder();
        if (!flag.userCanDisable() || !flag.isApplied()) {
            text.append(flag.isApplied() ? "+ " : "· ").append(flag.argv()).append("      ");
        }
        text.append(flag.overhead().displayName()).append(" overhead");
        if (!flag.isApplied()) {
            text.append("   — not applied: ").append(explain(flag));
        }
        text.append("      enables: ").append(flag.enables());
        text.append('\n').append(flag.reason());
        flag.writesFile().ifPresent(file -> {
            text.append("\nWrites ").append(file);
            if (flag.mayContainSensitiveData()) {
                // Plan 22.2: the user is told before the file exists, not after
                // they have shared the session.
                text.append(" — may contain absolute paths and command arguments.");
            }
        });
        if (!flag.userCanDisable()) {
            text.append("\nRequired: without it there is no capture.");
        }
        return wrapped(text.toString());
    }

    private static String explain(AddedFlag flag) {
        return switch (flag.capabilityStatus()) {
            case SUPPORTED -> "available";
            case UNSUPPORTED -> "this Bazel does not accept it";
            case UNKNOWN -> "this Bazel could not be probed, so it was not used";
        };
    }

    private JComponent conflictPanel(PlanConflict conflict) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        conflict.mandatory() ? "Decision needed" : "Note"),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));

        panel.add(wrapped(conflict.summary() + "\n" + conflict.detail()));
        for (PlanConflict.Resolution resolution : conflict.resolutions()) {
            JButton button = new JButton(resolution.label());
            button.setAlignmentX(Component.LEFT_ALIGNMENT);
            button.setToolTipText(resolution.consequence());
            button.addActionListener(event -> {
                if (PlanConflict.RESOLUTION_CANCEL.equals(resolution.id())) {
                    choice = Choice.CANCEL;
                } else {
                    choice = Choice.RESOLVE;
                    resolutionId = resolution.id();
                    resolvedKind = conflict.kind();
                }
                dispose();
            });
            panel.add(Box.createVerticalStrut(4));
            panel.add(button);
            // The consequence is shown, not only offered as a tooltip: a choice
            // whose cost is hidden behind a hover is not an informed one.
            panel.add(wrapped(resolution.consequence()));
        }
        return panel;
    }

    private static JComponent heading(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(12, 0, 4, 0));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private static JComponent monospace(String text) {
        JTextArea area = readOnlyArea(text);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        return area;
    }

    private static JComponent wrapped(String text) {
        JTextArea area = readOnlyArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JTextArea readOnlyArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Selectable on purpose: the effective command is something people copy
        // into a terminal, and a label cannot be copied.
        area.setFocusable(true);
        return area;
    }
}
