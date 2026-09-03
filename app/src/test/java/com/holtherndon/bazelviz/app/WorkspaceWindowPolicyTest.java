package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowState;
import org.junit.jupiter.api.Test;

final class WorkspaceWindowPolicyTest {

  @Test
  void unavailableRestoresRetainTheirSlotsUntilForgottenOrResolved() {
    int limit = WorkspaceWindowState.MAX_OPEN_WORKSPACES;

    assertThat(WorkspaceWindowPolicy.canOpenRestoreSlot(false, false, 0, limit)).isFalse();
    assertThat(WorkspaceWindowPolicy.canOpenRestoreSlot(false, true, 0, limit)).isTrue();
    assertThat(WorkspaceWindowPolicy.canOpenRestoreSlot(false, false, 0, limit - 1)).isTrue();
  }

  @Test
  void focusingAnExistingWindowNeverConsumesAnotherSlot() {
    assertThat(
            WorkspaceWindowPolicy.canOpenRestoreSlot(
                true, false, WorkspaceWindowState.MAX_OPEN_WORKSPACES, 0))
        .isTrue();
  }

  @Test
  void desktopOpenUsesTheManagerOnlyWhenItOwnsRoutingOrNoWorkspaceIsActive() {
    assertThat(WorkspaceWindowPolicy.desktopOpenTarget(true, true))
        .isEqualTo(WorkspaceWindowPolicy.DesktopOpenTarget.MANAGER);
    assertThat(WorkspaceWindowPolicy.desktopOpenTarget(false, false))
        .isEqualTo(WorkspaceWindowPolicy.DesktopOpenTarget.MANAGER);
    assertThat(WorkspaceWindowPolicy.desktopOpenTarget(false, true))
        .isEqualTo(WorkspaceWindowPolicy.DesktopOpenTarget.WORKSPACE);
  }

  @Test
  void managerReturnsOnlyAfterCompletedRemovalOfTheLastWindow() {
    assertThat(WorkspaceWindowPolicy.showManagerAfterCompletedClose(false, false)).isFalse();
    assertThat(WorkspaceWindowPolicy.showManagerAfterCompletedClose(true, true)).isFalse();
    assertThat(WorkspaceWindowPolicy.showManagerAfterCompletedClose(false, true)).isTrue();
  }

  @Test
  void onlyStartupDiscoveryCanSatisfyAnAutomaticRestore() {
    assertThat(WorkspaceWindowPolicy.canRestoreDiscoveredWindow(false, true, 0)).isFalse();
    assertThat(WorkspaceWindowPolicy.canRestoreDiscoveredWindow(true, false, 0)).isFalse();
    assertThat(
            WorkspaceWindowPolicy.canRestoreDiscoveredWindow(
                true, true, WorkspaceWindowState.MAX_OPEN_WORKSPACES))
        .isFalse();
    assertThat(WorkspaceWindowPolicy.canRestoreDiscoveredWindow(true, true, 0)).isTrue();
  }

  @Test
  void invalidCountsAreRejected() {
    assertThatThrownBy(() -> WorkspaceWindowPolicy.canOpenRestoreSlot(false, false, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
