package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.jupiter.api.Test;

final class WorkspaceSelectionPanelTest {

  private static final String HOSTILE =
      "<html><img src=\"http://example.invalid/workspace.png\">Repository";

  @Test
  void recentListIsSortedAndOpeningIsAlwaysAnExplicitAction() throws Exception {
    WorkspaceProfile old =
        WorkspaceProfile.local("old", "Old", "/old", "bazel", OptionalLong.of(10));
    WorkspaceProfile recent =
        WorkspaceProfile.ssh(
            "recent",
            "Recent",
            "linux",
            OptionalInt.empty(),
            "/recent",
            "bazel",
            OptionalLong.of(20));
    WorkspaceSelectionPanel panel = panel();
    AtomicReference<WorkspaceProfile> opened = new AtomicReference<>();
    AtomicInteger calls = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.onOpen(
              profile -> {
                opened.set(profile);
                calls.incrementAndGet();
              });
          panel.setWorkspaces(List.of(old, recent));
        });

    assertThat(panel.workspaceListForTest().getModel().getElementAt(0)).isEqualTo(recent);
    assertThat(panel.workspaceListForTest().getModel().getElementAt(1)).isEqualTo(old);
    assertThat(panel.selectedWorkspace()).hasValue(recent);
    assertThat(calls).hasValue(0);

    SwingUtilities.invokeAndWait(panel.openForTest()::doClick);
    assertThat(opened).hasValue(recent);
    assertThat(calls).hasValue(1);
  }

  @Test
  void workspaceRowsRenderRepositoryTextLiterally() throws Exception {
    WorkspaceSelectionPanel panel = panel();
    WorkspaceProfile workspace =
        WorkspaceProfile.local("id", HOSTILE, "/repo", "bazel", OptionalLong.empty());
    SwingUtilities.invokeAndWait(() -> panel.setWorkspaces(List.of(workspace)));

    Component row =
        panel
            .workspaceListForTest()
            .getCellRenderer()
            .getListCellRendererComponent(panel.workspaceListForTest(), workspace, 0, false, false);
    JComponent rendered = (JComponent) row;
    BasicHTML.updateRenderer(rendered, ((JLabel) rendered).getText());
    assertThat(rendered.getClientProperty(BasicHTML.propertyKey)).isNull();
    assertThat(((JLabel) rendered).getText()).startsWith(HOSTILE);
  }

  @Test
  void emptyAndPopulatedStatesExposeOnlyChooserActions() throws Exception {
    WorkspaceSelectionPanel panel = panel();

    assertThat(panel.openForTest().isEnabled()).isFalse();
    assertThat(panel.createForTest().isEnabled()).isTrue();
    assertThat(panel.discoverForTest().isEnabled()).isTrue();
    assertThat(panel.editDiscoveryForTest().isEnabled()).isTrue();
    assertThat(namedLabel(panel, "workspaces.count").getText()).contains("No saved workspaces");

    SwingUtilities.invokeAndWait(
        () ->
            panel.setWorkspaces(
                List.of(
                    WorkspaceProfile.local("id", "Repo", "/repo", "bazel", OptionalLong.empty()))));

    assertThat(panel.openForTest().isEnabled()).isTrue();
    assertThat(namedLabel(panel, "workspaces.count").getText())
        .isEqualTo("1 saved workspace, most recent first.");
  }

  @Test
  void newLocalAndSshFormsReturnValidatedProfilesWithoutManagingTheList() throws Exception {
    WorkspaceSelectionPanel panel = panel();
    AtomicReference<WorkspaceProfile> created = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          panel.onCreate(created::set);
          panel.createForTest().doClick();
          panel.labelForTest().setText("Local repo");
          panel.workingDirectoryForTest().setText("/code/repo");
          panel.bazelExecutableForTest().setText("bazelisk");
          panel.saveForTest().doClick();
        });

    assertThat(created.get())
        .extracting(
            WorkspaceProfile::id,
            WorkspaceProfile::label,
            WorkspaceProfile::kind,
            WorkspaceProfile::workingDirectory,
            WorkspaceProfile::bazelExecutable)
        .containsExactly(
            "generated-id", "Local repo", WorkspaceProfile.Kind.LOCAL, "/code/repo", "bazelisk");
    assertThat(created.get().lastOpenedMicros()).isEmpty();
    assertThat(panel.workspaceListForTest().getModel().getSize()).isZero();
    assertThat(panel.editorVisibleForTest()).isFalse();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.createForTest().doClick();
          panel.kindForTest().setSelectedItem(WorkspaceProfile.Kind.SSH);
          panel.labelForTest().setText("Remote repo");
          panel.destinationForTest().setText("builder@linux");
          panel.portForTest().setText("2222");
          panel.workingDirectoryForTest().setText("/srv/repo");
          panel.bazelExecutableForTest().setText("bazel");
          panel.saveForTest().doClick();
        });

    assertThat(created.get().kind()).isEqualTo(WorkspaceProfile.Kind.SSH);
    assertThat(created.get().destination()).hasValue("builder@linux");
    assertThat(created.get().port()).hasValue(2222);
  }

  @Test
  void invalidFormStaysVisibleWithInlineErrorAndDoesNotCallOwner() throws Exception {
    WorkspaceSelectionPanel panel = panel();
    AtomicInteger creates = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.onCreate(ignored -> creates.incrementAndGet());
          panel.showNewWorkspaceForm();
          panel.kindForTest().setSelectedItem(WorkspaceProfile.Kind.SSH);
          panel.labelForTest().setText("Remote");
          panel.destinationForTest().setText("linux");
          panel.portForTest().setText("not-a-port");
          panel.workingDirectoryForTest().setText("/srv/repo");
          panel.saveForTest().doClick();
        });

    assertThat(creates).hasValue(0);
    assertThat(panel.editorVisibleForTest()).isTrue();
    assertThat(panel.errorForTest().getText()).contains("number").contains("65535");

    SwingUtilities.invokeAndWait(
        () -> {
          panel.kindForTest().setSelectedItem(WorkspaceProfile.Kind.LOCAL);
          panel.saveForTest().doClick();
        });
    assertThat(creates).hasValue(1);
  }

  @Test
  void editingASpecificActiveProfilePreservesItsIdentityAndRecency() throws Exception {
    WorkspaceProfile active =
        WorkspaceProfile.ssh(
            "active-id",
            "Before",
            "linux",
            OptionalInt.of(2200),
            "/srv/repo",
            "bazel",
            OptionalLong.of(987));
    WorkspaceProfile differentRecentRow =
        WorkspaceProfile.local(
            "selected-id", "Selected", "/selected", "bazel", OptionalLong.of(1_000));
    WorkspaceSelectionPanel panel = panel();
    AtomicReference<WorkspaceProfile> updated = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspaces(List.of(differentRecentRow));
          panel.onUpdate(
              profile -> {
                updated.set(profile);
                return true;
              });
          panel.showEditWorkspaceForm(active);
          panel.labelForTest().setText("After");
          panel.bazelExecutableForTest().setText("bazelisk");
          panel.saveForTest().doClick();
        });

    assertThat(updated.get().id()).isEqualTo("active-id");
    assertThat(updated.get().label()).isEqualTo("After");
    assertThat(updated.get().bazelExecutable()).isEqualTo("bazelisk");
    assertThat(updated.get().lastOpenedMicros()).hasValue(987);
    assertThat(updated.get().destination()).hasValue("linux");
    assertThat(updated.get().port()).hasValue(2200);
  }

  @Test
  void rejectedEditStaysOpenAndCanBeRetriedWithoutLosingTheOriginalIdentity() throws Exception {
    WorkspaceProfile active =
        WorkspaceProfile.local("active-id", "Before", "/repo", "bazel", OptionalLong.of(987));
    WorkspaceSelectionPanel panel = panel();
    AtomicBoolean accept = new AtomicBoolean();
    AtomicReference<WorkspaceProfile> updated = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspaces(List.of(active));
          panel.onUpdate(
              profile -> {
                updated.set(profile);
                return accept.get();
              });
          panel.showEditWorkspaceForm(active);
          panel.labelForTest().setText("After");
          panel.saveForTest().doClick();
        });

    assertThat(updated.get().id()).isEqualTo("active-id");
    assertThat(panel.editorVisibleForTest()).isTrue();
    assertThat(panel.errorForTest().getText()).contains("could not be updated");

    accept.set(true);
    SwingUtilities.invokeAndWait(() -> panel.saveForTest().doClick());
    assertThat(panel.editorVisibleForTest()).isFalse();
    assertThat(updated.get().label()).isEqualTo("After");
  }

  @Test
  void savedRowContextActionsReuseCallbacksWithoutMutatingOwnerState() throws Exception {
    WorkspaceProfile profile =
        WorkspaceProfile.local("id", "Repo", "/repo", "bazel", OptionalLong.empty());
    WorkspaceSelectionPanel panel = panel();
    AtomicReference<WorkspaceProfile> removed = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspaces(List.of(profile));
          panel.onRemove(removed::set);
          menuItem(panel.contextMenuForTest(profile), "Remove").doClick();
        });

    assertThat(removed).hasValue(profile);
    assertThat(panel.workspaceListForTest().getModel().getSize()).isEqualTo(1);
  }

  @Test
  void discoveredRowsAreTaggedAndTheirContextMenuOnlyOpens() throws Exception {
    WorkspaceProfile saved =
        WorkspaceProfile.local("saved", "Saved", "/saved", "bazel", OptionalLong.of(20));
    WorkspaceProfile discovered =
        WorkspaceProfile.ssh(
            "discovered",
            "Remote",
            "build-host",
            OptionalInt.empty(),
            "/remote",
            "bazel",
            OptionalLong.empty());
    WorkspaceSelectionPanel panel = panel();
    AtomicReference<WorkspaceProfile> opened = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.onOpen(opened::set);
          panel.setWorkspaces(List.of(saved), List.of(discovered));
          panel.setDiscoveryStatus("Discovery found 1 workspace.");
          panel.workspaceListForTest().setSelectedValue(discovered, true);
        });

    assertThat(panel.openForTest().isEnabled()).isTrue();
    assertThat(namedLabel(panel, "workspaces.count").getText())
        .isEqualTo("1 saved workspace · 1 discovered this run.");
    assertThat(namedLabel(panel, "workspaces.discoveryStatus").getText())
        .isEqualTo("Discovery found 1 workspace.");

    Component rendered =
        panel
            .workspaceListForTest()
            .getCellRenderer()
            .getListCellRendererComponent(
                panel.workspaceListForTest(), discovered, 1, false, false);
    assertThat(((JLabel) rendered).getText()).contains("[Discovered]");

    SwingUtilities.invokeAndWait(panel.openForTest()::doClick);
    assertThat(opened).hasValue(discovered);
    assertThat(menuLabels(panel.contextMenuForTest(discovered))).containsExactly("Open Workspace");
    assertThat(menuLabels(panel.contextMenuForTest(saved)))
        .containsExactly("Open Workspace", "Edit", "Remove");
  }

  @Test
  void discoverActionsAreExplicitCallbacksAndContextSelectionCannotUseABlankRow() throws Exception {
    WorkspaceProfile first =
        WorkspaceProfile.local("first", "First", "/first", "bazel", OptionalLong.empty());
    WorkspaceProfile second =
        WorkspaceProfile.local("second", "Second", "/second", "bazel", OptionalLong.empty());
    WorkspaceSelectionPanel panel = panel();
    AtomicInteger discovers = new AtomicInteger();
    AtomicInteger edits = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.onDiscover(discovers::incrementAndGet);
          panel.onEditDiscovery(edits::incrementAndGet);
          panel.setWorkspaces(List.of(first, second));
          panel.workspaceListForTest().setSize(400, 100);
          panel.workspaceListForTest().setSelectedValue(first, true);
          panel.discoverForTest().doClick();
          panel.editDiscoveryForTest().doClick();
        });

    assertThat(discovers).hasValue(1);
    assertThat(edits).hasValue(1);
    assertThat(onEdt(() -> panel.selectWorkspaceAtForTest(new Point(4, 35)))).hasValue(second);
    assertThat(panel.selectedWorkspace()).hasValue(second);
    assertThat(onEdt(() -> panel.selectWorkspaceAtForTest(new Point(4, 90)))).isEmpty();
    assertThat(panel.selectedWorkspace()).isEmpty();
    assertThat(
            panel
                .workspaceListForTest()
                .getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK)))
        .isEqualTo("workspace-menu");
    assertThat(
            panel
                .workspaceListForTest()
                .getInputMap(JComponent.WHEN_FOCUSED)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0)))
        .isEqualTo("workspace-menu");
  }

  @Test
  void unavailableRestoredIdsRemainVisibleUntilExplicitlyForgotten() throws Exception {
    WorkspaceSelectionPanel panel = panel();
    AtomicInteger forgotten = new AtomicInteger();

    SwingUtilities.invokeAndWait(
        () ->
            panel.setUnavailableRestoreIds(
                List.of("missing-one", "missing-two"), forgotten::incrementAndGet));

    assertThat(panel.unavailableRestoreStatusForTest().getText())
        .contains("2 previously open Workspaces")
        .contains("missing-one")
        .contains("missing-two");
    assertThat(panel.forgetUnavailableRestoresForTest().isVisible()).isTrue();
    assertThat(forgotten).hasValue(0);

    SwingUtilities.invokeAndWait(panel.forgetUnavailableRestoresForTest()::doClick);
    assertThat(forgotten).hasValue(1);

    SwingUtilities.invokeAndWait(() -> panel.setUnavailableRestoreIds(List.of(), () -> {}));
    assertThat(panel.forgetUnavailableRestoresForTest().isVisible()).isFalse();
    assertThat(panel.unavailableRestoreStatusForTest().getText()).isBlank();
  }

  @Test
  void formFieldsHaveAccessibleLabelsAndSshInputsAreConditional() throws Exception {
    WorkspaceSelectionPanel panel = panel();
    SwingUtilities.invokeAndWait(panel::showNewWorkspaceForm);

    assertThat(namedLabel(panel, "workspaces.labelLabel").getLabelFor())
        .isSameAs(panel.labelForTest());
    assertThat(namedLabel(panel, "workspaces.kindLabel").getLabelFor())
        .isSameAs(panel.kindForTest());
    assertThat(namedLabel(panel, "workspaces.destinationLabel").getLabelFor())
        .isSameAs(panel.destinationForTest());
    assertThat(namedLabel(panel, "workspaces.portLabel").getLabelFor())
        .isSameAs(panel.portForTest());
    assertThat(namedLabel(panel, "workspaces.workingDirectoryLabel").getLabelFor())
        .isSameAs(panel.workingDirectoryForTest());
    assertThat(namedLabel(panel, "workspaces.bazelLabel").getLabelFor())
        .isSameAs(panel.bazelExecutableForTest());
    assertThat(panel.destinationForTest().isEnabled()).isFalse();
    assertThat(panel.portForTest().isEnabled()).isFalse();

    SwingUtilities.invokeAndWait(
        () -> panel.kindForTest().setSelectedItem(WorkspaceProfile.Kind.SSH));
    assertThat(panel.destinationForTest().isEnabled()).isTrue();
    assertThat(panel.portForTest().isEnabled()).isTrue();
  }

  private static WorkspaceSelectionPanel panel() throws Exception {
    AtomicReference<WorkspaceSelectionPanel> panel = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> panel.set(new WorkspaceSelectionPanel(() -> "generated-id")));
    return panel.get();
  }

  private static JLabel namedLabel(Container root, String name) {
    for (Component component : allComponents(root)) {
      if (component instanceof JLabel found && name.equals(found.getName())) {
        return found;
      }
    }
    throw new AssertionError("No JLabel named " + name);
  }

  private static JMenuItem menuItem(JPopupMenu menu, String text) {
    for (Component component : menu.getComponents()) {
      if (component instanceof JMenuItem item && text.equals(item.getText())) {
        return item;
      }
    }
    throw new AssertionError("No menu item named " + text);
  }

  private static List<String> menuLabels(JPopupMenu menu) {
    List<String> labels = new ArrayList<>();
    for (Component component : menu.getComponents()) {
      if (component instanceof JMenuItem item) {
        labels.add(item.getText());
      }
    }
    return labels;
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return result.get();
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
}
