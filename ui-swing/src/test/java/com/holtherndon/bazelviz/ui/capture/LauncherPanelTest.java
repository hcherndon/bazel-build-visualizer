package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
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
        assertThat(panel.getPreferredSize().height).isLessThanOrEqualTo(190);
    }

    @Test
    void selectedWorkspaceReplacesTheHostConnectionAndPathForm() throws Exception {
        LauncherPanel panel = panel();

        SwingUtilities.invokeAndWait(() -> panel.useManagedWorkspace(
                "Compiler checkout",
                ExecutionHost.SSH,
                "/srv/compiler",
                "bazelisk",
                "builder@example.internal",
                "2222"));

        assertThat(panel.hasManagedWorkspace()).isTrue();
        assertThat(panel.selectedWorkspaceForTest().getText())
                .isEqualTo("Compiler checkout · builder@example.internal:2222 · /srv/compiler");
        assertThat(panel.changeWorkspaceForTest().isVisible()).isTrue();
        assertThat(panel.executionHostForTest().isVisible()).isFalse();
        assertThat(panel.workspaceFieldForTest().isVisible()).isFalse();
        assertThat(panel.bazelFieldForTest().isVisible()).isFalse();
        assertThat(panel.commandFieldForTest().isVisible()).isTrue();
        assertThat(panel.presetChoiceForTest().isVisible()).isTrue();
        assertThat(panel.workspace()).isEqualTo("/srv/compiler");
        assertThat(panel.bazelExecutable()).isEqualTo("bazelisk");
        assertThat(panel.isRemote()).isTrue();
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
        assertPresetSummary(panel, CapturePreset.LIVE_ESSENTIALS,
                "BEP + console · graph queries after build",
                "Live BEP and console; no execution log, timing trace, or Starlark CPU profile.");
        assertPresetSummary(panel, CapturePreset.PERFORMANCE_DIAGNOSTICS,
                "Adds execution log + two profiles · graph queries after build",
                "Recommended: adds the execution log, timing trace, and Starlark CPU profile"
                        + " to live BEP and console.");
        assertPresetSummary(panel, CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                "Same capture as Performance today · graph queries after build",
                "Currently the same sources as Performance Diagnostics;");

        assertThat(choices.getToolTipText())
                .contains("aquery and cquery")
                .contains("extra disk, CPU, and indexing time");
        assertThat(allComponents(panel)).noneMatch(JTextArea.class::isInstance);
        assertThat(Arrays.stream(CapturePreset.values()))
                .allSatisfy(preset -> assertThat(preset.requiresCostWarning()).isTrue());
    }

    @Test
    void commandStaysEditableAndHasPinnedCompletionHistoryInItsTooltip() throws Exception {
        LauncherPanel panel = panel();
        assertThat(LauncherPanel.commonSubcommands()).containsExactly(
                "aquery", "build", "clean", "cquery", "help", "info", "query", "run",
                "shutdown", "test", "version");
        assertThat(panel.commandFieldForTest().isEditable()).isTrue();
        assertThat(panel.commandFieldForTest().getToolTipText())
                .contains(Integer.toString(LauncherHistory.MAX_ENTRIES))
                .contains("Up/Down")
                .contains("Ctrl+Space");

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

    @Test
    void sshFieldsAreCompactConditionalAndValidateTheOptionalPort() throws Exception {
        LauncherPanel panel = panel();

        assertThat(namedLabel(panel, "launcher.executionHostLabel").getLabelFor())
                .isSameAs(panel.executionHostForTest());
        assertThat(panel.sshOptionsForTest().isVisible()).isFalse();
        assertThat(panel.chooseWorkspaceForTest().isVisible()).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
            panel.sshDestinationForTest().setText("builder@example.internal");
            panel.sshPortForTest().setText("2222");
        });

        assertThat(panel.isRemote()).isTrue();
        assertThat(panel.sshOptionsForTest().isVisible()).isTrue();
        assertThat(panel.chooseWorkspaceForTest().isVisible()).isFalse();
        assertThat(panel.chooseBazelForTest().isVisible()).isFalse();
        assertThat(panel.sshDestination()).isEqualTo("builder@example.internal");
        assertThat(panel.sshPort()).hasValue(2222);

        SwingUtilities.invokeAndWait(() -> panel.sshPortForTest().setText("70000"));
        org.assertj.core.api.Assertions.assertThatThrownBy(panel::sshPort)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..65535");
    }

    @Test
    void savedSshConnectionRestoresItsRemoteLaunchSettings() throws Exception {
        LauncherPanel panel = panel();
        SwingUtilities.invokeAndWait(() -> {
            panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
            panel.sshDestinationForTest().setText("build-linux");
            panel.sshPortForTest().setText("2222");
            panel.setWorkspace("/srv/project");
            panel.setBazelExecutable("bazelisk");
            panel.rememberSshConnection();

            panel.sshDestinationForTest().setText("changed");
            panel.sshPortForTest().setText("");
            panel.setWorkspace("/changed");
            panel.setBazelExecutable("bazel");
            panel.savedSshConnectionsForTest().setSelectedItem(
                    "build-linux:2222 — /srv/project");
        });

        assertThat(panel.sshDestination()).isEqualTo("build-linux");
        assertThat(panel.sshPort()).hasValue(2222);
        assertThat(panel.workspace()).isEqualTo("/srv/project");
        assertThat(panel.bazelExecutable()).isEqualTo("bazelisk");
    }

    @Test
    void savedConnectionsKeepSeparateRepositoriesOnTheSameHost() throws Exception {
        LauncherPanel panel = panel();
        SwingUtilities.invokeAndWait(() -> {
            panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
            panel.sshDestinationForTest().setText("build-linux");
            panel.sshPortForTest().setText("2222");
            panel.setWorkspace("/srv/one");
            panel.setBazelExecutable("bazel");
            panel.rememberSshConnection();
            panel.setWorkspace("/srv/two");
            panel.setBazelExecutable("bazelisk");
            panel.rememberSshConnection();
        });

        assertThat(panel.savedSshConnectionsForTest().getItemCount()).isEqualTo(3);
        assertThat(java.util.List.of(
                panel.savedSshConnectionsForTest().getItemAt(1),
                panel.savedSshConnectionsForTest().getItemAt(2)))
                .containsExactly(
                        "build-linux:2222 — /srv/two",
                        "build-linux:2222 — /srv/one");
    }

    @Test
    void localAndRemoteHostsKeepSeparateWorkspaceAndBazelDrafts() throws Exception {
        LauncherPanel panel = panel();
        SwingUtilities.invokeAndWait(() -> {
            panel.setWorkspace("/Users/example/local-repo");
            panel.setBazelExecutable("/opt/homebrew/bin/bazelisk");
            panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
        });

        assertThat(panel.workspace()).isEmpty();
        assertThat(panel.bazelExecutable()).isEqualTo("bazel");

        SwingUtilities.invokeAndWait(() -> {
            panel.setWorkspace("/srv/remote-repo");
            panel.setBazelExecutable("remote-bazelisk");
            panel.executionHostForTest().setSelectedItem(ExecutionHost.LOCAL);
        });
        assertThat(panel.workspace()).isEqualTo("/Users/example/local-repo");
        assertThat(panel.bazelExecutable()).isEqualTo("/opt/homebrew/bin/bazelisk");

        SwingUtilities.invokeAndWait(
                () -> panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH));
        assertThat(panel.workspace()).isEqualTo("/srv/remote-repo");
        assertThat(panel.bazelExecutable()).isEqualTo("remote-bazelisk");
    }

    @Test
    void busyStateDisablesEveryLaunchAndProfileInput() throws Exception {
        LauncherPanel panel = panel();
        SwingUtilities.invokeAndWait(() -> {
            panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
            panel.sshDestinationForTest().setText("build-linux");
            panel.setWorkspace("/srv/project");
            panel.rememberSshConnection();
            panel.setRunEnabled(false);
        });

        assertThat(List.of(
                        "launcher.run",
                        "launcher.executionHost",
                        "launcher.savedSshConnections",
                        "launcher.forgetSshConnection",
                        "launcher.sshDestination",
                        "launcher.sshPort",
                        "launcher.workspace",
                        "launcher.bazel",
                        "launcher.captureDetail",
                        "launcher.command",
                        "launcher.chooseWorkspace",
                        "launcher.chooseBazel"))
                .allSatisfy(name -> assertThat(namedComponent(panel, name).isEnabled())
                        .as(name)
                        .isFalse());

        SwingUtilities.invokeAndWait(() -> panel.setRunEnabled(true));
        assertThat(namedComponent(panel, "launcher.run").isEnabled()).isTrue();
        assertThat(namedComponent(panel, "launcher.executionHost").isEnabled()).isTrue();
        assertThat(namedComponent(panel, "launcher.forgetSshConnection").isEnabled()).isTrue();
    }

    @Test
    void sshProfilesUseTheRunnerDestinationValidationAndRejectAllControlCharacters() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SshConnectionProfile(
                        "-oProxyCommand=unexpected", "", "/srv/project", "bazel"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SshConnectionProfile(
                        "build\u0007linux", "", "/srv/project", "bazel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        assertThat(new SshConnectionProfile(
                        "user@[2001:db8::1]", "2222", "/srv/project", "bazelisk"))
                .extracting(SshConnectionProfile::destination, SshConnectionProfile::port)
                .containsExactly("user@[2001:db8::1]", "2222");
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

    private static JComponent namedComponent(Container root, String name) {
        for (Component component : allComponents(root)) {
            if (component instanceof JComponent swingComponent
                    && name.equals(swingComponent.getName())) {
                return swingComponent;
            }
        }
        throw new AssertionError("No component named " + name);
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

    private static void assertPresetSummary(
            LauncherPanel panel, CapturePreset preset, String summary, String explanation)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> panel.presetChoiceForTest().setSelectedItem(preset));
        assertThat(panel.presetSummaryForTest().getText()).isEqualTo(summary);
        assertThat(panel.presetSummaryForTest().getToolTipText())
                .startsWith(explanation)
                .contains("aquery and cquery");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String renderedText(JComboBox<CapturePreset> choices, CapturePreset value) {
        Component rendered = choices.getRenderer().getListCellRendererComponent(
                new javax.swing.JList(), value, 0, false, false);
        return ((JLabel) rendered).getText();
    }
}
