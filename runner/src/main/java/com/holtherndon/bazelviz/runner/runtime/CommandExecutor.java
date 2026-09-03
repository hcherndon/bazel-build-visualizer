package com.holtherndon.bazelviz.runner.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** Executes commands without assuming that their working directory is local. */
public interface CommandExecutor {

  CommandResult run(CommandRequest request, Duration timeout)
      throws IOException, InterruptedException;

  CommandResult runRedirectingStdout(CommandRequest request, Duration timeout, Path localOutputFile)
      throws IOException, InterruptedException;

  RunningCommand start(CommandRequest request) throws IOException;

  /**
   * Opens an interactive shell and returns once its process has started. This may block on
   * transport setup and must never be called on the Swing EDT.
   */
  InteractiveChannel openTerminal(String workingDirectory) throws IOException;
}
