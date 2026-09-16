package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Fresh-path evidence for a controlled capture. Not proof of an ID inside the log. */
final class ControlledExecutionLog {
  private final ExecutionFileSystem files;
  private final ExecutionPath source;
  private final ExecutionPath sourceParent;
  private final Path local;
  private final Path localParent;
  private final boolean remote;
  private boolean transferred;

  private ControlledExecutionLog(ExecutionFileSystem files, Path source, Path local, boolean remote)
      throws IOException {
    this.files = files;
    this.source = files.path(source.toString());
    sourceParent = files.canonicalize(files.path(source.getParent().toString()));
    this.local = local;
    localParent = local.getParent().toRealPath();
    this.remote = remote;
    requireFresh();
  }

  static ControlledExecutionLog prepare(
      InstrumentationPlan plan,
      Path executionRaw,
      Path localRaw,
      ExecutionFileSystem files,
      boolean remote)
      throws IOException {
    Path source = executionRaw.resolve(InstrumentationPlanner.EXECUTION_LOG_FILE).toAbsolutePath();
    var flags =
        plan.appliedFlags().stream()
            .filter(flag -> flag.capability() == Capability.EXECUTION_LOG_COMPACT)
            .toList();
    List<String> logArgs =
        plan.effective().commandArgs().stream()
            .filter(ControlledExecutionLog::isLogArgument)
            .toList();
    if (flags.size() != 1
        || !flags.getFirst().writesFile().filter(source::equals).isPresent()
        || !plan.expectedOutputs().contains(source)
        || !logArgs.equals(List.of(flags.getFirst().argv()))) {
      throw new IOException("A controlled capture requires one app-owned compact execution log.");
    }
    return new ControlledExecutionLog(
        files, source, localRaw.resolve(InstrumentationPlanner.EXECUTION_LOG_FILE), remote);
  }

  private static boolean isLogArgument(String arg) {
    String name = arg.split("=", 2)[0];
    return List.of(
            "--execution_log_compact_file",
            "--experimental_execution_log_compact_file",
            "--execution_log_binary_file",
            "--execution_log_json_file")
        .contains(name);
  }

  void requireFresh() throws IOException {
    requireUnchangedParents();
    if (files.stat(source).state() != FileMetadata.State.MISSING
        || Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("The controlled execution-log path is not fresh; refusing to launch.");
    }
  }

  private void requireUnchangedParents() throws IOException {
    if (!files
            .canonicalize(files.path(Path.of(source.value()).getParent().toString()))
            .equals(sourceParent)
        || !local.getParent().toRealPath().equals(localParent)) {
      throw new IOException("The controlled execution-log directory changed.");
    }
  }

  boolean matches(String executionPath) {
    return source.value().equals(executionPath);
  }

  /** Called only after the SSH client has a known exit, before staging cleanup. */
  void download(long maxBytes) throws IOException {
    requireUnchangedParents();
    if (!remote
        || !files.stat(source).isRegularFile()
        || Files.exists(local, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Cannot preserve a stale or non-regular controlled execution log.");
    }
    ExecutionPath canonical = files.canonicalize(source);
    if (!canonical.equals(files.resolve(sourceParent, InstrumentationPlanner.EXECUTION_LOG_FILE))) {
      throw new IOException("The controlled execution log resolves outside its planned location.");
    }
    files.download(canonical, local, maxBytes);
    requireLocalFile();
    transferred = true;
  }

  CaptureCoordinator.ExecutionLogReceipt receipt() throws IOException {
    if (remote && !transferred) {
      throw new IOException("The controlled execution log was not successfully transferred.");
    }
    // Remote staging may already have been removed; the preserved local parent must still match.
    requireLocalFile();
    return new CaptureCoordinator.ExecutionLogReceipt(Path.of(source.value()), local);
  }

  private void requireLocalFile() throws IOException {
    if (!local.getParent().toRealPath().equals(localParent)
        || !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)
        || Files.size(local) == 0) {
      throw new IOException("The controlled execution log was not preserved as a regular file.");
    }
  }
}
