package com.holtherndon.bazelviz.ui.terminal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** Transport-neutral name for the persistent execution-workspace terminal. */
public final class TerminalView extends SshTerminalView {

  private static final long serialVersionUID = 1L;

  public TerminalView(ExecutorService worker, ScheduledExecutorService scheduler) {
    super(worker, scheduler);
  }

  TerminalView(
      int scrollbackLineLimit, ExecutorService worker, ScheduledExecutorService scheduler) {
    super(scrollbackLineLimit, worker, scheduler);
  }
}
