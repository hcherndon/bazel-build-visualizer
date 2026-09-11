package com.holtherndon.bazelviz.capture.repro;

import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Exact private ownership guard; never accepts an imported or user-supplied cleanup path. */
final class OwnedAuditBase {
  @FunctionalInterface
  interface AllocationObserver {
    void allocated(String directory) throws IOException;
  }

  private final ExecutionFileSystem files;
  private final CommandExecutor executor;
  private final String marker;
  private final ExecutionPath directory;
  private final ExecutionPath outputBase;

  private OwnedAuditBase(
      ExecutionFileSystem files, CommandExecutor executor, String marker, ExecutionPath directory)
      throws IOException {
    this.files = files;
    this.executor = executor;
    this.marker = marker;
    this.directory = directory;
    outputBase = files.resolve(directory, "output-base");
    validate();
  }

  static OwnedAuditBase allocate(
      ExecutionFileSystem files,
      CommandExecutor executor,
      String marker,
      AllocationObserver observer)
      throws IOException, InterruptedException {
    if (!marker.matches("[a-f0-9-]{36}")) {
      throw new IllegalArgumentException("invalid private allocation identity");
    }
    // Fixed POSIX helper, no user text is interpolated into shell source. Root and base are
    // private.
    String script =
        "set -eu; umask 077; base=$(mktemp -d \"/tmp/bbv-repro-$1-XXXXXXXXXX\"); printf '%s' \"$1\""
            + " > \"$base/owner\"; mkdir \"$base/output-base\"; cd \"$base\"; pwd -P";
    var result =
        executor.run(
            CommandRequest.of(
                List.of("/bin/sh", "-c", script, "bbv-audit-allocate", marker), (String) null),
            Duration.ofSeconds(30));
    if (!result.isSuccess()) {
      throw new IOException("could not allocate a private audit base: " + result.failureDetail());
    }
    String text = result.stdout().strip();
    if (!text.matches("/(?:private/)?tmp/bbv-repro-" + marker + "-[A-Za-z0-9]+")) {
      throw new IOException("private audit allocator returned an unexpected directory");
    }
    observer.allocated(text);
    return new OwnedAuditBase(files, executor, marker, files.path(text));
  }

  String outputBase() {
    return outputBase.value();
  }

  String directory() {
    return directory.value();
  }

  void validate() throws IOException {
    if (!files.stat(directory).isDirectory()
        || !files.stat(outputBase).isDirectory()
        || !files.canonicalize(directory).equals(directory)
        || !files.canonicalize(outputBase).equals(outputBase)
        || !files.isWithin(directory, outputBase)) {
      throw new IOException("the private audit base changed or escaped its owned directory");
    }
    String observed =
        new String(
            files.read(files.resolve(directory, "owner"), 128).bytes(), StandardCharsets.UTF_8);
    if (!marker.equals(observed)) {
      throw new IOException("the private audit base ownership marker changed");
    }
  }

  void removeAfterShutdown() throws IOException, InterruptedException {
    validate();
    // Recheck inside the same helper as removal, after the caller confirmed server shutdown.
    String script =
        "set -eu; test -d \"$1\"; test ! -L \"$1\"; test \"$(cat \"$1/owner\")\" = \"$2\"; test"
            + " \"$(cd \"$1\" && pwd -P)\" = \"$1\"; rm -r -- \"$1\"";
    var result =
        executor.run(
            CommandRequest.of(
                List.of("/bin/sh", "-c", script, "bbv-audit-cleanup", directory.value(), marker),
                (String) null),
            Duration.ofSeconds(60));
    if (!result.isSuccess()) {
      throw new IOException("private audit cleanup did not complete: " + result.failureDetail());
    }
  }
}
