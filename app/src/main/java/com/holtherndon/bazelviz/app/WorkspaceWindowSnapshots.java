package com.holtherndon.bazelviz.app;

import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure assembly of live and unresolved Workspace-window restore state. */
final class WorkspaceWindowSnapshots {

  private WorkspaceWindowSnapshots() {}

  static WorkspaceWindowState merge(
      List<WorkspaceWindowState.OpenWorkspace> live,
      Map<String, WorkspaceWindowState.OpenWorkspace> pending,
      List<String> restoreOrder) {
    Objects.requireNonNull(live, "live");
    Objects.requireNonNull(pending, "pending");
    Objects.requireNonNull(restoreOrder, "restoreOrder");

    LinkedHashMap<String, WorkspaceWindowState.OpenWorkspace> byId = new LinkedHashMap<>();
    for (WorkspaceWindowState.OpenWorkspace entry : live) {
      WorkspaceWindowState.OpenWorkspace checked = Objects.requireNonNull(entry, "live entry");
      if (byId.putIfAbsent(checked.workspaceId(), checked) != null) {
        throw new IllegalArgumentException("duplicate live Workspace ID " + checked.workspaceId());
      }
    }
    pending.forEach(
        (id, entry) -> {
          String checkedId = Objects.requireNonNull(id, "pending id");
          WorkspaceWindowState.OpenWorkspace checkedEntry =
              Objects.requireNonNull(entry, "pending entry");
          if (!checkedId.equals(checkedEntry.workspaceId())) {
            throw new IllegalArgumentException("pending Workspace ID does not match its state");
          }
          byId.putIfAbsent(checkedId, checkedEntry);
        });

    ArrayList<WorkspaceWindowState.OpenWorkspace> entries = new ArrayList<>(byId.size());
    for (String id : restoreOrder) {
      WorkspaceWindowState.OpenWorkspace entry = byId.remove(id);
      if (entry != null) {
        entries.add(entry);
      }
    }
    entries.addAll(byId.values());
    return new WorkspaceWindowState(entries);
  }
}
