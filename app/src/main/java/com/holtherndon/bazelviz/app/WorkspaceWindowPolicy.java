package com.holtherndon.bazelviz.app;

import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowState;

/** Pure multi-window routing and capacity decisions used by the Swing composition root. */
final class WorkspaceWindowPolicy {

  enum DesktopOpenTarget {
    MANAGER,
    WORKSPACE
  }

  private WorkspaceWindowPolicy() {}

  static boolean canOpenRestoreSlot(
      boolean alreadyOpen,
      boolean replacesPendingRestore,
      int trackedWindows,
      int pendingRestores) {
    requireNonNegative(trackedWindows, "trackedWindows");
    requireNonNegative(pendingRestores, "pendingRestores");
    if (alreadyOpen || replacesPendingRestore) {
      return true;
    }
    return trackedWindows < WorkspaceWindowState.MAX_OPEN_WORKSPACES
        && pendingRestores < WorkspaceWindowState.MAX_OPEN_WORKSPACES - trackedWindows;
  }

  static DesktopOpenTarget desktopOpenTarget(
      boolean managerOwnsRouting, boolean hasActiveWorkspace) {
    return managerOwnsRouting || !hasActiveWorkspace
        ? DesktopOpenTarget.MANAGER
        : DesktopOpenTarget.WORKSPACE;
  }

  static boolean showManagerAfterCompletedClose(boolean quitting, boolean registryEmpty) {
    return !quitting && registryEmpty;
  }

  static boolean canRestoreDiscoveredWindow(
      boolean startupDiscovery, boolean matchingProfile, int trackedWindows) {
    requireNonNegative(trackedWindows, "trackedWindows");
    return startupDiscovery
        && matchingProfile
        && trackedWindows < WorkspaceWindowState.MAX_OPEN_WORKSPACES;
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " cannot be negative");
    }
  }
}
