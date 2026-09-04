package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicHTML;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LauncherPanelTest {

  private static final String HOSTILE_COMMAND =
      "<html><img src=\"http://example.invalid/command.png\">test //pkg:all";

  @Test
  void sharedConsoleChromeOwnsTheManagedWorkspaceActionAndIdentity() throws Exception {
    AtomicInteger chooseCalls = new AtomicInteger();
    AtomicReference<LauncherPanel> panelReference = new AtomicReference<>();
    AtomicReference<PageToolbar> toolbarReference = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          LauncherPanel panel = new LauncherPanel(() -> {}, chooseCalls::incrementAndGet);
          PageToolbar toolbar = new PageToolbar("Console");
          panel.useManagedWorkspace(
              "Compiler checkout",
              ExecutionHost.SSH,
              "/srv/compiler",
              "bazelisk",
              "builder@example.internal",
              "2222");
          panel.installPageToolbar(toolbar);
          panel.changeWorkspaceForTest().doClick();
          panelReference.set(panel);
          toolbarReference.set(toolbar);
        });

    LauncherPanel panel = panelReference.get();
    assertThat(toolbarReference.get().actionCount()).isOne();
    assertThat(panel.selectedWorkspaceForTest().isVisible()).isFalse();
    assertThat(panel.changeWorkspaceForTest().getParent()).isNotSameAs(panel);
    assertThat(chooseCalls).hasValue(1);
  }

  @Test
  void compactFormHasExplicitLabelsAndLeftAlignedInlineLaunchOptions() throws Exception {
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
    assertThat(namedLabel(panel, "launcher.bazelLabel").getText()).isEqualTo("Bazel Executable:");
    assertThat(namedLabel(panel, "launcher.captureDetailLabel").getText())
        .isEqualTo("Capture detail:");
    assertThat(namedLabel(panel, "launcher.commandLabel").getText()).isEqualTo("Bazel command:");
    assertThat(panel.chooseWorkspaceForTest().getText()).isEqualTo("Choose workspace…");

    JPanel options = (JPanel) namedComponent(panel, "launcher.optionsRow");
    assertThat(options.getLayout()).isInstanceOf(GridBagLayout.class);
    assertThat(options.getComponents())
        .containsExactly(
            panel.bazelFieldForTest(),
            namedLabel(panel, "launcher.captureDetailLabel"),
            panel.presetChoiceForTest());

    GridBagLayout form = (GridBagLayout) panel.getLayout();
    assertThat(form.getConstraints(namedLabel(panel, "launcher.bazelLabel")))
        .extracting(constraints -> constraints.gridx, constraints -> constraints.gridy)
        .containsExactly(0, 2);
    assertThat(form.getConstraints(options))
        .extracting(constraints -> constraints.gridx, constraints -> constraints.gridy)
        .containsExactly(1, 2);
    assertThat(form.getConstraints(namedLabel(panel, "launcher.commandLabel")))
        .extracting(constraints -> constraints.gridx, constraints -> constraints.gridy)
        .containsExactly(0, 3);
    assertThat(form.getConstraints(panel.commandFieldForTest()))
        .extracting(constraints -> constraints.gridx, constraints -> constraints.gridy)
        .containsExactly(1, 3);
    assertThat(form.getConstraints(namedLabel(panel, "launcher.bazelLabel")).anchor)
        .isEqualTo(GridBagConstraints.WEST);
    assertThat(form.getConstraints(namedLabel(panel, "launcher.commandLabel")).anchor)
        .isEqualTo(GridBagConstraints.WEST);

    GridBagLayout inline = (GridBagLayout) options.getLayout();
    assertThat(inline.getConstraints(panel.bazelFieldForTest()).gridx).isZero();
    assertThat(inline.getConstraints(namedLabel(panel, "launcher.captureDetailLabel")).gridx)
        .isEqualTo(1);
    assertThat(inline.getConstraints(panel.presetChoiceForTest()).gridx).isEqualTo(2);
    assertThat(allComponents(panel))
        .noneMatch(
            component ->
                "launcher.chooseBazel".equals(component.getName())
                    || "launcher.presetSummary".equals(component.getName()));
    assertThat(panel.getPreferredSize().height).isLessThanOrEqualTo(190);
  }

  @Test
  void selectedWorkspaceReplacesTheHostConnectionAndPathForm() throws Exception {
    LauncherPanel panel = panel();

    SwingUtilities.invokeAndWait(
        () ->
            panel.useManagedWorkspace(
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
    assertThat(panel.bazelFieldForTest().isVisible()).isTrue();
    assertThat(panel.bazelFieldForTest().isEditable()).isTrue();
    assertThat(panel.commandFieldForTest().isVisible()).isTrue();
    assertThat(panel.presetChoiceForTest().isVisible()).isTrue();
    assertThat(panel.workspace()).isEqualTo("/srv/compiler");
    assertThat(panel.bazelExecutable()).isEqualTo("bazelisk");
    assertThat(panel.isRemote()).isTrue();

    GridBagLayout form = (GridBagLayout) panel.getLayout();
    assertThat(form.getConstraints(namedLabel(panel, "launcher.selectedWorkspaceLabel")))
        .extracting(constraints -> constraints.gridx, constraints -> constraints.gridy)
        .containsExactly(0, 0);
    assertThat(form.getConstraints(panel.selectedWorkspaceForTest()))
        .extracting(constraints -> constraints.gridx, constraints -> constraints.gridy)
        .containsExactly(1, 0);
    assertThat(form.getConstraints(namedComponent(panel, "launcher.optionsRow")).gridx)
        .isEqualTo(1);
    assertThat(form.getConstraints(panel.commandFieldForTest()).gridx).isEqualTo(1);
  }

  @Test
  void managedBazelCommandCanBeTypedAndCommitted() throws Exception {
    AtomicReference<String> committed = new AtomicReference<>();
    AtomicReference<LauncherPanel> panelReference = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          LauncherPanel panel =
              new LauncherPanel(
                  () -> {},
                  () -> {},
                  executable -> {
                    committed.set(executable);
                    return true;
                  });
          panel.useManagedWorkspace(
              "Local checkout", ExecutionHost.LOCAL, "/repo", "bazel", "", "");
          panel.bazelFieldForTest().setText("  /opt/tools/bazelisk  ");
          panelReference.set(panel);
        });

    LauncherPanel panel = panelReference.get();
    AtomicReference<Boolean> accepted = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> accepted.set(panel.commitManagedBazelExecutable()));
    assertThat(accepted).hasValue(true);
    assertThat(committed).hasValue("/opt/tools/bazelisk");
    assertThat(panel.bazelExecutable()).isEqualTo("/opt/tools/bazelisk");
  }

  @Test
  void offersThreeExplainedPresetsWithoutCustomAndDefaultsToRecommended() throws Exception {
    LauncherPanel panel = panel();
    JComboBox<CapturePreset> choices = panel.presetChoiceForTest();

    assertThat(items(choices))
        .containsExactly(
            CapturePreset.LIVE_ESSENTIALS,
            CapturePreset.PERFORMANCE_DIAGNOSTICS,
            CapturePreset.FULL_GRAPH_DIAGNOSTICS);
    assertThat(choices.getSelectedItem()).isEqualTo(CapturePreset.PERFORMANCE_DIAGNOSTICS);
    assertThat(renderedText(choices, CapturePreset.PERFORMANCE_DIAGNOSTICS))
        .isEqualTo("Performance Diagnostics (recommended)");
    assertPresetTooltip(
        panel,
        CapturePreset.LIVE_ESSENTIALS,
        "Live BEP and console; no execution log, timing trace, or Starlark CPU profile.");
    assertPresetTooltip(
        panel,
        CapturePreset.PERFORMANCE_DIAGNOSTICS,
        "Recommended: adds the execution log, timing trace, and Starlark CPU profile"
            + " to live BEP and console.");
    assertPresetTooltip(
        panel,
        CapturePreset.FULL_GRAPH_DIAGNOSTICS,
        "Currently the same sources as Performance Diagnostics;");

    assertThat(allComponents(panel))
        .noneMatch(component -> "launcher.presetSummary".equals(component.getName()));
    assertThat(allComponents(panel)).noneMatch(JTextArea.class::isInstance);
    assertThat(Arrays.stream(CapturePreset.values()))
        .allSatisfy(preset -> assertThat(preset.requiresCostWarning()).isTrue());
  }

  @Test
  void presetTooltipsAreImmediateOnlyWhileDropdownsAreOpen() throws Exception {
    LauncherPanel first = panel();
    LauncherPanel second = panel();
    ToolTipManager tooltips = ToolTipManager.sharedInstance();
    int originalDelay = tooltips.getInitialDelay();
    SwingUtilities.invokeAndWait(() -> tooltips.setInitialDelay(640));
    try {
      SwingUtilities.invokeAndWait(() -> firePresetPopupVisible(first.presetChoiceForTest()));
      assertThat(tooltips.getInitialDelay()).isZero();

      SwingUtilities.invokeAndWait(() -> firePresetPopupVisible(second.presetChoiceForTest()));
      SwingUtilities.invokeAndWait(() -> firePresetPopupHidden(first.presetChoiceForTest()));
      assertThat(tooltips.getInitialDelay())
          .as("another Workspace still has its Capture detail dropdown open")
          .isZero();

      SwingUtilities.invokeAndWait(() -> firePresetPopupHidden(second.presetChoiceForTest()));
      assertThat(tooltips.getInitialDelay()).isEqualTo(640);
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            firePresetPopupHidden(first.presetChoiceForTest());
            firePresetPopupHidden(second.presetChoiceForTest());
            tooltips.setInitialDelay(originalDelay);
          });
    }
  }

  @Test
  void commandFieldOffersCompletionAndHistoryWithoutASeparateRecentButton() throws Exception {
    LauncherPanel panel = panel();
    assertThat(LauncherPanel.commonSubcommands())
        .containsExactly(
            "aquery",
            "build",
            "clean",
            "cquery",
            "help",
            "info",
            "query",
            "run",
            "shutdown",
            "test",
            "version");
    assertThat(panel.commandFieldForTest().isEditable()).isTrue();
    assertThat(panel.commandFieldForTest().getToolTipText())
        .contains("recent")
        .contains("Tab")
        .contains("Enter")
        .contains("Ctrl+Space");

    assertThat(
            panel
                .commandFieldForTest()
                .getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)))
        .as("Ctrl+Space remains available for Bazel subcommand completion")
        .isNotNull();
    assertThat(allComponents(panel))
        .noneMatch(
            component ->
                "launcher.recentCommands".equals(component.getName())
                    || (component instanceof JButton button
                        && button.getText().startsWith("Recent")));
  }

  @Test
  void openingCommandHistoryStartsUnselectedAndTypingRemainsNormal() throws Exception {
    LauncherPanel panel = panel();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.rememberCommand("build //older");
          panel.rememberCommand("test //newer");
          MouseEvent click =
              new MouseEvent(
                  panel.commandFieldForTest(),
                  MouseEvent.MOUSE_RELEASED,
                  System.currentTimeMillis(),
                  0,
                  2,
                  2,
                  1,
                  false,
                  MouseEvent.BUTTON1);
          Arrays.stream(panel.commandFieldForTest().getMouseListeners())
              .forEach(listener -> listener.mouseReleased(click));
          assertThat(panel.recentCommandsListForTest().getSelectedIndex()).isEqualTo(-1);
          invokeCommandKey(panel, KeyEvent.VK_DOWN, 0);
        });

    assertThat(items(panel.recentCommandsListForTest()))
        .containsExactly("test //newer", "build //older");
    assertThat(panel.recentCommandsListForTest().getSelectedIndex()).isZero();
    assertThat(panel.recentCommandsMenuForTest().getInvoker())
        .as("the history popup is anchored below the command field")
        .isSameAs(panel.commandFieldForTest());
    assertThat(panel.recentCommandsMenuForTest().isFocusable()).isFalse();
    assertThat(panel.recentCommandsListForTest().isFocusable()).isFalse();
    assertThat(panel.recentCommandsScrollForTest().isFocusable()).isFalse();
    assertThat(panel.recentCommandsScrollForTest().getVerticalScrollBar().isFocusable()).isFalse();
    assertThat(panel.commandFieldForTest().getFocusListeners()).isNotEmpty();
    assertThat(panel.commandFieldForTest().getMouseListeners()).isNotEmpty();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.commandFieldForTest().setText("");
          panel.commandFieldForTest().replaceSelection("query //typed");
        });
    assertThat(panel.command()).isEqualTo("query //typed");
    assertThat(panel.recentCommandsListForTest().getSelectedIndex()).isEqualTo(-1);
  }

  @Test
  void arrowsSelectHistoryTabFillsAndEnterRunsImmediately() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    LauncherPanel panel = panel(runs::incrementAndGet);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.rememberCommand("build //oldest");
          panel.rememberCommand("test //middle");
          panel.rememberCommand("query //newest");
          panel.commandFieldForTest().setText("build //draft");
          panel.showRecentCommandsForTest();

          invokeCommandKey(panel, KeyEvent.VK_DOWN, 0);
          assertThat(panel.recentCommandsListForTest().getSelectedIndex()).isZero();
          assertThat(panel.command()).isEqualTo("build //draft");

          invokeCommandKey(panel, KeyEvent.VK_DOWN, 0);
          assertThat(panel.recentCommandsListForTest().getSelectedIndex()).isEqualTo(1);
          invokeCommandKey(panel, KeyEvent.VK_UP, 0);
          assertThat(panel.recentCommandsListForTest().getSelectedIndex()).isZero();

          invokeCommandKey(panel, KeyEvent.VK_TAB, 0);
        });

    assertThat(panel.command()).isEqualTo("query //newest");
    assertThat(runs).hasValue(0);

    SwingUtilities.invokeAndWait(
        () -> {
          panel.showRecentCommandsForTest();
          invokeCommandKey(panel, KeyEvent.VK_DOWN, 0);
          invokeCommandKey(panel, KeyEvent.VK_DOWN, 0);
          invokeCommandKey(panel, KeyEvent.VK_ENTER, 0);
        });

    assertThat(panel.command()).isEqualTo("test //middle");
    assertThat(runs).hasValue(1);

    SwingUtilities.invokeAndWait(
        () -> {
          panel.commandFieldForTest().setText("build //typed");
          panel.showRecentCommandsForTest();
          invokeCommandKey(panel, KeyEvent.VK_ENTER, 0);
        });
    assertThat(panel.command()).isEqualTo("build //typed");
    assertThat(runs).hasValue(2);
  }

  @Test
  void recentCommandPopupShowsFiveUniqueRowsThenScrolls() throws Exception {
    LauncherPanel panel = panel();
    String suffix = "-with-a-name-that-is-deliberately-much-wider-than-the-launcher";
    SwingUtilities.invokeAndWait(
        () -> {
          for (int index = 0; index < 8; index++) {
            panel.rememberCommand("test //package:target-" + index + suffix);
          }
          panel.rememberCommand("test //package:target-4" + suffix);
          panel.sizeRecentCommandsPopupForTest(new Dimension(420, 300));
        });

    JList<String> commands = panel.recentCommandsListForTest();
    assertThat(commands.getModel().getSize()).isEqualTo(8);
    assertThat(commands.getModel().getElementAt(0)).contains("target-4");
    assertThat(panel.recentCommandsScrollForTest().getPreferredSize().width)
        .isLessThanOrEqualTo(420);
    int rowHeight = commands.getFixedCellHeight();
    int popupHeight = panel.recentCommandsScrollForTest().getPreferredSize().height;
    assertThat(popupHeight).isBetween(rowHeight * 5, rowHeight * 5 + 8);
    assertThat(commands.getPreferredSize().height)
        .isGreaterThan(panel.recentCommandsScrollForTest().getPreferredSize().height);
    assertThat(panel.recentCommandsScrollForTest().getVerticalScrollBarPolicy())
        .isEqualTo(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

    String fullCommand = commands.getModel().getElementAt(0);
    JLabel rendered =
        (JLabel)
            commands
                .getCellRenderer()
                .getListCellRendererComponent(commands, fullCommand, 0, false, false);
    assertThat(rendered.getText()).endsWith("…").isNotEqualTo(fullCommand);
    assertThat(rendered.getToolTipText()).contains(fullCommand);
  }

  @Test
  void recentCommandsRenderAsLiteralText() throws Exception {
    LauncherPanel panel = panel();
    JList<String> commands = panel.recentCommandsListForTest();
    AtomicReference<JLabel> held = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          commands.setFixedCellWidth(1_000);
          held.set(
              (JLabel)
                  commands
                      .getCellRenderer()
                      .getListCellRendererComponent(commands, HOSTILE_COMMAND, 0, false, false));
        });
    JLabel rendered = held.get();

    assertThat(rendered.getText()).isEqualTo(HOSTILE_COMMAND);
    assertThat(rendered.getClientProperty(BasicHTML.propertyKey)).isNull();
    assertThat(rendered.getToolTipText()).isEqualTo(" " + HOSTILE_COMMAND);
    assertThat(BasicHTML.isHTMLString(rendered.getToolTipText())).isFalse();
  }

  @Test
  void sshFieldsAreCompactConditionalAndValidateTheOptionalPort() throws Exception {
    LauncherPanel panel = panel();

    assertThat(namedLabel(panel, "launcher.executionHostLabel").getLabelFor())
        .isSameAs(panel.executionHostForTest());
    assertThat(panel.sshOptionsForTest().isVisible()).isFalse();
    assertThat(panel.chooseWorkspaceForTest().isVisible()).isTrue();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
          panel.sshDestinationForTest().setText("builder@example.internal");
          panel.sshPortForTest().setText("2222");
        });

    assertThat(panel.isRemote()).isTrue();
    assertThat(panel.sshOptionsForTest().isVisible()).isTrue();
    assertThat(panel.chooseWorkspaceForTest().isVisible()).isFalse();
    assertThat(panel.sshDestination()).isEqualTo("builder@example.internal");
    assertThat(panel.sshPort()).hasValue(2222);

    SwingUtilities.invokeAndWait(() -> panel.sshPortForTest().setText("70000"));
    Assertions.assertThatThrownBy(panel::sshPort)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1..65535");
  }

  @Test
  void savedSshConnectionRestoresItsRemoteLaunchSettings() throws Exception {
    LauncherPanel panel = panel();
    SwingUtilities.invokeAndWait(
        () -> {
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
          panel.savedSshConnectionsForTest().setSelectedItem("build-linux:2222 — /srv/project");
        });

    assertThat(panel.sshDestination()).isEqualTo("build-linux");
    assertThat(panel.sshPort()).hasValue(2222);
    assertThat(panel.workspace()).isEqualTo("/srv/project");
    assertThat(panel.bazelExecutable()).isEqualTo("bazelisk");
  }

  @Test
  void savedConnectionsKeepSeparateRepositoriesOnTheSameHost() throws Exception {
    LauncherPanel panel = panel();
    SwingUtilities.invokeAndWait(
        () -> {
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
    assertThat(
            List.of(
                panel.savedSshConnectionsForTest().getItemAt(1),
                panel.savedSshConnectionsForTest().getItemAt(2)))
        .containsExactly("build-linux:2222 — /srv/two", "build-linux:2222 — /srv/one");
  }

  @Test
  void localAndRemoteHostsKeepSeparateWorkspaceAndBazelDrafts() throws Exception {
    LauncherPanel panel = panel();
    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/Users/example/local-repo");
          panel.setBazelExecutable("/opt/homebrew/bin/bazelisk");
          panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
        });

    assertThat(panel.workspace()).isEmpty();
    assertThat(panel.bazelExecutable()).isEqualTo("bazel");

    SwingUtilities.invokeAndWait(
        () -> {
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
    SwingUtilities.invokeAndWait(
        () -> {
          panel.executionHostForTest().setSelectedItem(ExecutionHost.SSH);
          panel.sshDestinationForTest().setText("build-linux");
          panel.setWorkspace("/srv/project");
          panel.rememberSshConnection();
          panel.setRunEnabled(false);
        });

    assertThat(
            List.of(
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
                "launcher.chooseWorkspace"))
        .allSatisfy(name -> assertThat(namedComponent(panel, name).isEnabled()).as(name).isFalse());

    SwingUtilities.invokeAndWait(() -> panel.setRunEnabled(true));
    assertThat(namedComponent(panel, "launcher.run").isEnabled()).isTrue();
    assertThat(namedComponent(panel, "launcher.executionHost").isEnabled()).isTrue();
    assertThat(namedComponent(panel, "launcher.forgetSshConnection").isEnabled()).isTrue();
  }

  @Test
  void sshProfilesUseTheRunnerDestinationValidationAndRejectAllControlCharacters() {
    Assertions.assertThatThrownBy(
            () ->
                new SshConnectionProfile("-oProxyCommand=unexpected", "", "/srv/project", "bazel"))
        .isInstanceOf(IllegalArgumentException.class);
    Assertions.assertThatThrownBy(
            () -> new SshConnectionProfile("build\u0007linux", "", "/srv/project", "bazel"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("control characters");

    assertThat(new SshConnectionProfile("user@[2001:db8::1]", "2222", "/srv/project", "bazelisk"))
        .extracting(SshConnectionProfile::destination, SshConnectionProfile::port)
        .containsExactly("user@[2001:db8::1]", "2222");
  }

  private static LauncherPanel panel() throws Exception {
    return panel(() -> {});
  }

  private static LauncherPanel panel(Runnable runAction) throws Exception {
    AtomicReference<LauncherPanel> panel = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> panel.set(new LauncherPanel(runAction, () -> {})));
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
      if (component instanceof JComponent swingComponent && name.equals(swingComponent.getName())) {
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

  private static List<String> items(JList<String> list) {
    List<String> values = new ArrayList<>();
    for (int index = 0; index < list.getModel().getSize(); index++) {
      values.add(list.getModel().getElementAt(index));
    }
    return values;
  }

  private static void invokeCommandKey(LauncherPanel panel, int keyCode, int modifiers) {
    KeyStroke keyStroke = KeyStroke.getKeyStroke(keyCode, modifiers);
    assertThat(panel.processCommandKeyForTest(keyCode, modifiers))
        .as(keyStroke.toString())
        .isTrue();
  }

  private static void firePresetPopupVisible(JComboBox<CapturePreset> choices) {
    PopupMenuEvent event = new PopupMenuEvent(choices);
    for (PopupMenuListener listener : choices.getPopupMenuListeners()) {
      listener.popupMenuWillBecomeVisible(event);
    }
  }

  private static void firePresetPopupHidden(JComboBox<CapturePreset> choices) {
    PopupMenuEvent event = new PopupMenuEvent(choices);
    for (PopupMenuListener listener : choices.getPopupMenuListeners()) {
      listener.popupMenuWillBecomeInvisible(event);
    }
  }

  private static void assertPresetTooltip(
      LauncherPanel panel, CapturePreset preset, String explanation) throws Exception {
    SwingUtilities.invokeAndWait(() -> panel.presetChoiceForTest().setSelectedItem(preset));
    JLabel rendered = renderedLabel(panel.presetChoiceForTest(), preset);
    assertThat(rendered.getHorizontalAlignment()).isEqualTo(JLabel.LEFT);
    assertThat(rendered.getToolTipText()).startsWith(explanation).contains("aquery and cquery");
    assertThat(panel.presetChoiceForTest().getToolTipText())
        .startsWith(explanation)
        .contains("aquery and cquery")
        .contains("extra disk, CPU, and indexing time");
    assertThat(panel.presetChoiceForTest().getAccessibleContext().getAccessibleDescription())
        .isEqualTo(panel.presetChoiceForTest().getToolTipText());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String renderedText(JComboBox<CapturePreset> choices, CapturePreset value) {
    return renderedLabel(choices, value).getText();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static JLabel renderedLabel(JComboBox<CapturePreset> choices, CapturePreset value) {
    Component rendered =
        choices.getRenderer().getListCellRendererComponent(new JList(), value, 0, false, false);
    return (JLabel) rendered;
  }
}
