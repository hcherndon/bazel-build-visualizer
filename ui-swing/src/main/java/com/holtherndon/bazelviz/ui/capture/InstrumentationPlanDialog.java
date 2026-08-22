package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
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
        RESOLVE
    }

    private Choice choice = Choice.CANCEL;
    private String resolutionId;
    private PlanConflict.Kind resolvedKind;

    public InstrumentationPlanDialog(java.awt.Window owner, Preflight preflight) {
        super(owner, "Instrumentation plan", ModalityType.APPLICATION_MODAL);
        InstrumentationPlan plan = preflight.plan();

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        body.add(heading("Bazel"));
        body.add(monospace(preflight.executable().displayName()
                + "\n" + preflight.executable().resolved()
                + "\ncapabilities: " + preflight.capabilities().detection()));

        body.add(heading("Your command"));
        body.add(monospace(String.join(" ", plan.original().userVisibleArgs())));

        body.add(heading("What will run"));
        body.add(monospace(String.join(" ", plan.effective().userVisibleArgs())));

        if (!plan.addedFlags().isEmpty()) {
            body.add(heading("Added"));
            for (AddedFlag flag : plan.addedFlags()) {
                body.add(addedFlagRow(flag));
            }
        }

        if (!plan.replacedFlags().isEmpty()) {
            body.add(heading("Replaced"));
            for (var replaced : plan.replacedFlags()) {
                body.add(wrapped(replaced.original() + "  →  "
                        + (replaced.isRemoval() ? "(removed)" : replaced.replacement())
                        + "\n" + replaced.reason()));
            }
        }

        for (PlanConflict conflict : plan.conflicts()) {
            body.add(conflictPanel(conflict));
        }

        if (!plan.warnings().isEmpty()) {
            body.add(heading("Notes"));
            for (String warning : plan.warnings()) {
                body.add(wrapped(warning));
            }
        }
        for (String error : plan.errors()) {
            body.add(wrapped("Cannot launch: " + error));
        }

        JScrollPane scroll = new JScrollPane(body);
        scroll.setPreferredSize(new Dimension(760, 520));
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

    // ------------------------------------------------------------------ parts

    private JComponent addedFlagRow(AddedFlag flag) {
        StringBuilder text = new StringBuilder();
        text.append(flag.isApplied() ? "+ " : "· ").append(flag.argv());
        text.append("      ").append(flag.overhead().displayName()).append(" overhead");
        if (!flag.isApplied()) {
            text.append("   — not applied: ").append(explain(flag));
        }
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
