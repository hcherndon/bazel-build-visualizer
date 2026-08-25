package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LauncherPanelTest {

    @Test
    void compactFormHasExplicitAccessibleLabelsAndChooserButtons() throws Exception {
        LauncherPanel panel = panel();

        assertThat(namedLabel(panel, "launcher.workspaceLabel").getLabelFor())
                .isSameAs(panel.workspaceFieldForTest());
        assertThat(namedLabel(panel, "launcher.bazelLabel").getLabelFor())
                .isSameAs(panel.bazelFieldForTest());
        assertThat(namedLabel(panel, "launcher.captureDetailLabel").getLabelFor())
                .isSameAs(panel.presetChoiceForTest());
        assertThat(namedLabel(panel, "launcher.commandLabel").getLabelFor())
                .isSameAs(panel.commandFieldForTest());
        assertThat(namedLabel(panel, "launcher.workspaceLabel").getText()).isEqualTo("Workspace:");
        assertThat(namedLabel(panel, "launcher.bazelLabel").getText())
                .isEqualTo("Bazel executable:");
        assertThat(namedLabel(panel, "launcher.captureDetailLabel").getText())
                .isEqualTo("Capture detail:");
        assertThat(namedLabel(panel, "launcher.commandLabel").getText())
                .isEqualTo("Bazel command (without bazel):");
        assertThat(panel.chooseWorkspaceForTest().getText()).isEqualTo("Choose workspace…");
        assertThat(panel.chooseBazelForTest().getText()).isEqualTo("Choose Bazel…");
    }

    @Test
    void offersThreeExplainedPresetsWithoutCustomAndDefaultsToRecommended() throws Exception {
        LauncherPanel panel = panel();
        JComboBox<CapturePreset> choices = panel.presetChoiceForTest();

        assertThat(items(choices)).containsExactly(
                CapturePreset.LIVE_ESSENTIALS,
                CapturePreset.PERFORMANCE_DIAGNOSTICS,
                CapturePreset.FULL_GRAPH_DIAGNOSTICS);
        assertThat(choices.getSelectedItem()).isEqualTo(CapturePreset.PERFORMANCE_DIAGNOSTICS);
        assertThat(renderedText(choices, CapturePreset.PERFORMANCE_DIAGNOSTICS))
                .isEqualTo("Performance Diagnostics (recommended)");
        assertThat(panel.presetExplanationForTest().getText())
                .containsIgnoringCase("Recommended")
                .containsIgnoringCase("timing");

        SwingUtilities.invokeAndWait(
                () -> choices.setSelectedItem(CapturePreset.FULL_GRAPH_DIAGNOSTICS));
        assertThat(panel.presetExplanationForTest().getText())
                .containsIgnoringCase("Warning")
                .containsIgnoringCase("disk")
                .containsIgnoringCase("CPU")
                .containsIgnoringCase("indexing");
        assertThat(panel.presetExplanationForTest().getFont().getStyle()).isEqualTo(Font.BOLD);
    }

    @Test
    void commandStaysEditableAndHasPinnedCompletionHistoryAndVisibleBound() throws Exception {
        LauncherPanel panel = panel();
        assertThat(LauncherPanel.commonSubcommands()).containsExactly(
                "aquery", "build", "clean", "cquery", "help", "info", "query", "run",
                "shutdown", "test", "version");
        assertThat(panel.commandFieldForTest().isEditable()).isTrue();
        assertThat(panel.historyExplanationForTest().getText())
                .contains(Integer.toString(LauncherHistory.MAX_ENTRIES))
                .contains("Up/Down");

        SwingUtilities.invokeAndWait(() -> {
            panel.rememberCommand("build //old");
            panel.rememberCommand("test //new");
            panel.commandFieldForTest().setText("query //draft");
            panel.commandFieldForTest().getActionMap().get("bbv-older-command")
                    .actionPerformed(new ActionEvent(panel, 0, "up"));
        });
        assertThat(panel.command()).isEqualTo("test //new");

        SwingUtilities.invokeAndWait(() -> panel.commandFieldForTest()
                .getActionMap().get("bbv-newer-command")
                .actionPerformed(new ActionEvent(panel, 0, "down")));
        assertThat(panel.command()).isEqualTo("query //draft");
    }

    private static LauncherPanel panel() throws Exception {
        AtomicReference<LauncherPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new LauncherPanel(() -> {}, () -> {}, () -> {})));
        return panel.get();
    }

    private static JLabel namedLabel(Container root, String name) {
        for (Component component : allComponents(root)) {
            if (component instanceof JLabel label && name.equals(label.getName())) {
                return label;
            }
        }
        throw new AssertionError("No label named " + name);
    }

    private static List<Component> allComponents(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container nested) {
                result.addAll(allComponents(nested));
            }
        }
        return result;
    }

    private static List<CapturePreset> items(JComboBox<CapturePreset> choices) {
        List<CapturePreset> values = new ArrayList<>();
        for (int i = 0; i < choices.getItemCount(); i++) {
            values.add(choices.getItemAt(i));
        }
        return values;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String renderedText(JComboBox<CapturePreset> choices, CapturePreset value) {
        Component rendered = choices.getRenderer().getListCellRendererComponent(
                new javax.swing.JList(), value, 0, false, false);
        return ((JLabel) rendered).getText();
    }
}
