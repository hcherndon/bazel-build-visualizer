package com.holtherndon.bazelviz.ui.capture;

/** Persistence used by the launcher's serialized save queue. */
interface LauncherSettingsStore {

  LauncherStateStore.State load();

  boolean save(LauncherStateStore.State state);

  /** Reduces a live launcher snapshot to the state this store owns. */
  default LauncherStateStore.State persistedState(LauncherStateStore.State state) {
    return state;
  }

  /** Whether this store owns only safe conveniences for an ephemeral discovered Workspace. */
  default boolean discoveredWorkspaceOnly() {
    return false;
  }
}
