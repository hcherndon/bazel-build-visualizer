package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceWindowSnapshotsTest {

  @Test
  void retainsUnavailableRestoresInTheirOriginalOrder() {
    WorkspaceWindowState.OpenWorkspace firstPending = open("first", false);
    WorkspaceWindowState.OpenWorkspace live = open("live", true);
    WorkspaceWindowState.OpenWorkspace secondPending = open("second", false);
    LinkedHashMap<String, WorkspaceWindowState.OpenWorkspace> pending = new LinkedHashMap<>();
    pending.put(firstPending.workspaceId(), firstPending);
    pending.put(secondPending.workspaceId(), secondPending);

    WorkspaceWindowState merged =
        WorkspaceWindowSnapshots.merge(List.of(live), pending, List.of("first", "live", "second"));

    assertThat(merged.openWorkspaces()).containsExactly(firstPending, live, secondPending);
  }

  @Test
  void liveWindowWinsAndNewWindowsAppendAfterTheRestoreOrder() {
    WorkspaceWindowState.OpenWorkspace stalePending = open("same", false);
    WorkspaceWindowState.OpenWorkspace live = open("same", true);
    WorkspaceWindowState.OpenWorkspace newlyOpened = open("new", false);

    WorkspaceWindowState merged =
        WorkspaceWindowSnapshots.merge(
            List.of(live, newlyOpened), Map.of("same", stalePending), List.of("same"));

    assertThat(merged.openWorkspaces()).containsExactly(live, newlyOpened);
  }

  @Test
  void refusesMismatchedPendingStateInsteadOfSavingItUnderTheWrongId() {
    assertThatThrownBy(
            () ->
                WorkspaceWindowSnapshots.merge(
                    List.of(), Map.of("map-id", open("entry-id", false)), List.of("map-id")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  void refusesToSilentlyDropAnUnavailableRestoreWhenTheExactSnapshotIsFull() {
    LinkedHashMap<String, WorkspaceWindowState.OpenWorkspace> pending = new LinkedHashMap<>();
    for (int index = 0; index < WorkspaceWindowState.MAX_OPEN_WORKSPACES; index++) {
      WorkspaceWindowState.OpenWorkspace entry = open("pending-" + index, false);
      pending.put(entry.workspaceId(), entry);
    }

    assertThatThrownBy(
            () ->
                WorkspaceWindowSnapshots.merge(
                    List.of(open("new-live", false)), pending, List.copyOf(pending.keySet())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most");
  }

  private static WorkspaceWindowState.OpenWorkspace open(String id, boolean maximized) {
    return new WorkspaceWindowState.OpenWorkspace(id, Optional.empty(), maximized);
  }
}
