package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.session.SessionSource;

/** Build-session presentation of the shared profile explorer. */
public final class StarlarkProfileView extends ProfileView {
  private static final long serialVersionUID = 1L;

  /** Opens the managed CPU profile asynchronously, preserving the build-specific presentation. */
  public void openSession(SessionSource opened) {
    openProfile(opened::openStarlarkProfileReader);
  }
}
