package com.holtherndon.bazelviz.ui.theme;

/** Installs one view's existing page-level controls and metadata into the window toolbar. */
public interface PageChrome {

  /** Called once, on the EDT, before the page is shown. */
  void installPageToolbar(PageToolbar toolbar);
}
