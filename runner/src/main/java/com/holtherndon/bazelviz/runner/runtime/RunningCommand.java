package com.holtherndon.bazelviz.runner.runtime;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.OptionalLong;

/** A long-running command on either the local or a remote executor. */
public interface RunningCommand {

  InputStream stdout();

  InputStream stderr();

  OutputStream stdin();

  /** Whether stdout and stderr arrive together through {@link #stdout()}. */
  boolean streamsMerged();

  /** The target-side process id, when one exists. */
  long pid();

  /** The target-side process group, used to stop wrappers and children together. */
  OptionalLong processGroupId();

  boolean isAlive();

  int waitFor() throws InterruptedException;

  boolean waitFor(Duration timeout) throws InterruptedException;

  int exitValue();

  /** Deliver one cancellation rung on the executor's machine. */
  void signal(CancellationMode mode) throws IOException, InterruptedException;
}
