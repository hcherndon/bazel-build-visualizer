package com.holtherndon.bazelviz.ui.workspace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Screen-independent state for restoring the ordered set of open Workspace windows.
 *
 * <p>Bounds describe the normal, non-maximized window geometry. Applying those bounds to the
 * screens that are currently available is deliberately left to the UI layer.
 */
public record WorkspaceWindowState(List<OpenWorkspace> openWorkspaces) {

  /** Maximum live or unavailable Workspace-window entries retained in one restore snapshot. */
  public static final int MAX_OPEN_WORKSPACES = 8;

  public WorkspaceWindowState {
    Objects.requireNonNull(openWorkspaces, "openWorkspaces");
    if (openWorkspaces.size() > MAX_OPEN_WORKSPACES) {
      throw new IllegalArgumentException(
          "at most " + MAX_OPEN_WORKSPACES + " Workspace windows can be restored");
    }
    List<OpenWorkspace> copied = new ArrayList<>(openWorkspaces.size());
    Set<String> identifiers = new HashSet<>();
    for (OpenWorkspace openWorkspace : openWorkspaces) {
      OpenWorkspace required = Objects.requireNonNull(openWorkspace, "openWorkspace");
      if (!identifiers.add(required.workspaceId())) {
        throw new IllegalArgumentException("duplicate open Workspace id " + required.workspaceId());
      }
      copied.add(required);
    }
    openWorkspaces = List.copyOf(copied);
  }

  /** Returns a snapshot with no open Workspace windows. */
  public static WorkspaceWindowState empty() {
    return new WorkspaceWindowState(List.of());
  }

  /** Stable Workspace identifiers in the order the caller should restore them. */
  public List<String> orderedWorkspaceIds() {
    return openWorkspaces.stream().map(OpenWorkspace::workspaceId).toList();
  }

  /** Restore data for one Workspace window. */
  public record OpenWorkspace(
      String workspaceId, Optional<WindowBounds> bounds, boolean maximized) {

    public OpenWorkspace {
      workspaceId = checkedWorkspaceId(workspaceId);
      bounds = Objects.requireNonNull(bounds, "bounds");
    }

    private static String checkedWorkspaceId(String value) {
      String checked = Objects.requireNonNull(value, "workspaceId").strip();
      if (checked.isEmpty()) {
        throw new IllegalArgumentException("workspace id must not be blank");
      }
      if (checked.length() > WorkspaceProfile.MAX_TEXT_CHARACTERS) {
        throw new IllegalArgumentException(
            "workspace id exceeds " + WorkspaceProfile.MAX_TEXT_CHARACTERS + " characters");
      }
      return checked;
    }
  }

  /**
   * Logical window geometry that can be validated without querying the current display setup.
   *
   * <p>Negative and otherwise off-screen coordinates remain valid here. The UI can reconcile them
   * with the displays available when a future process restores the window.
   */
  public record WindowBounds(int x, int y, int width, int height) {

    public WindowBounds {
      if (!hasUsableSize(width, height)) {
        throw new IllegalArgumentException("window width and height must be positive");
      }
    }

    /** Pure geometry validation suitable before any Swing or screen access. */
    public static boolean hasUsableSize(int width, int height) {
      return width > 0 && height > 0;
    }
  }
}
