package com.holtherndon.bazelviz.ui.workspace;

import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.LocalCommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Executes the optional local workspace-discovery script and parses its bounded output. */
public final class WorkspaceDiscovery implements AutoCloseable {

  /** Maximum wall time for one discovery-script invocation. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** Maximum discovery stdout retained and parsed. */
  public static final int MAX_STDOUT_BYTES = 1_048_576;

  /** Maximum discovery stderr retained for diagnostics. */
  public static final int MAX_STDERR_BYTES = 65_536;

  /** Maximum valid, distinct workspace rows accepted from one invocation. */
  public static final int MAX_ACCEPTED_ROWS = 100;

  /** Maximum detailed row diagnostics retained from one invocation. */
  public static final int MAX_ROW_DIAGNOSTICS = 1_000;

  /** Grace shared by process teardown and output-drain cleanup. */
  public static final Duration CLEANUP_GRACE = Duration.ofSeconds(1);

  private static final Logger log = LoggerFactory.getLogger(WorkspaceDiscovery.class);

  private final WorkspaceDiscoveryScriptStore store;
  private final Duration timeout;
  private final int stdoutLimit;
  private final int stderrLimit;
  private final Duration cleanupGrace;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean invocationRunning = new AtomicBoolean();
  private final AtomicBoolean closeCancelledActive = new AtomicBoolean();
  private final AtomicReference<RunningCommand> activeCommand = new AtomicReference<>();

  public WorkspaceDiscovery(WorkspaceDiscoveryScriptStore store) {
    this(store, DEFAULT_TIMEOUT, MAX_STDOUT_BYTES, MAX_STDERR_BYTES, CLEANUP_GRACE);
  }

  WorkspaceDiscovery(
      WorkspaceDiscoveryScriptStore store,
      Duration timeout,
      int stdoutLimit,
      int stderrLimit,
      Duration cleanupGrace) {
    this.store = Objects.requireNonNull(store, "store");
    this.timeout = positive(timeout, "timeout");
    this.stdoutLimit = positive(stdoutLimit, "stdoutLimit");
    this.stderrLimit = positive(stderrLimit, "stderrLimit");
    this.cleanupGrace = positive(cleanupGrace, "cleanupGrace");
  }

  /**
   * Runs one fresh discovery invocation on this computer.
   *
   * <p>This method performs settings, process, and stream I/O and rejects the Swing EDT. Every
   * failure is returned as a safe diagnostic; valid complete rows before a failure remain
   * available.
   */
  public DiscoveryResult discover() {
    requireBackgroundThread();
    if (!invocationRunning.compareAndSet(false, true)) {
      return new DiscoveryResult(
          List.of(),
          List.of("Workspace discovery is already running."),
          "",
          OptionalInt.empty(),
          false);
    }
    try {
      return discoverOnce();
    } finally {
      invocationRunning.set(false);
    }
  }

  private DiscoveryResult discoverOnce() {
    if (closed.get()) {
      return closedResult();
    }
    WorkspaceDiscoveryScriptStore.LoadResult loaded = store.loadWithDiagnostics();
    List<String> diagnostics = new ArrayList<>(loaded.diagnostics());
    if (closed.get()) {
      diagnostics.add("Workspace discovery was not run because its owner closed.");
      return new DiscoveryResult(List.of(), diagnostics, "", OptionalInt.empty(), false);
    }
    if (!loaded.configured()) {
      return new DiscoveryResult(List.of(), diagnostics, "", OptionalInt.empty(), false);
    }

    Path script = store.file().toAbsolutePath().normalize();
    if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(script)) {
      diagnostics.add("The workspace discovery script is not an executable regular file.");
      return new DiscoveryResult(List.of(), diagnostics, "", OptionalInt.empty(), false);
    }

    RunningCommand command;
    long startedNanos = System.nanoTime();
    try {
      command =
          LocalCommandExecutor.INSTANCE.start(
              CommandRequest.of(List.of(script.toString()), script.getParent()));
      try {
        command.stdin().close();
      } catch (IOException closeFailure) {
        log.debug("workspace discovery stdin could not be closed", closeFailure);
      }
    } catch (IOException | RuntimeException failure) {
      log.warn("workspace discovery could not start from {}", script, failure);
      diagnostics.add("Workspace discovery could not start on this computer.");
      return new DiscoveryResult(List.of(), diagnostics, "", OptionalInt.empty(), false);
    }

    closeCancelledActive.set(false);
    activeCommand.set(command);
    if (closed.get()) {
      closeCancelledActive.set(true);
      if (command.isAlive()) {
        forceStopFromClose(command);
      }
    }

    try {
      OutputCapture stdout = new OutputCapture(command.stdout(), stdoutLimit);
      OutputCapture stderr = new OutputCapture(command.stderr(), stderrLimit);
      ExecutorService drains =
          Executors.newThreadPerTaskExecutor(
              Thread.ofVirtual().name("bbv-workspace-discovery-output-", 0).factory());
      Future<?> stdoutFuture = drains.submit(stdout::drain);
      Future<?> stderrFuture = drains.submit(stderr::drain);
      boolean timedOut = false;
      boolean interrupted = false;
      OptionalInt exitCode = OptionalInt.empty();
      try {
        if (command.waitFor(timeout)) {
          exitCode = OptionalInt.of(command.exitValue());
        } else {
          timedOut = true;
          diagnostics.add(
              "Workspace discovery exceeded its "
                  + timeout.toSeconds()
                  + " second timeout; complete output received before it stopped was kept.");
        }
      } catch (InterruptedException waitInterrupted) {
        interrupted = true;
        diagnostics.add(
            "Workspace discovery was interrupted; complete output received"
                + " before it stopped was kept.");
      }

      if (command.isAlive()) {
        StopOutcome stopped = stopCommand(command, cleanupGrace);
        interrupted |= stopped.interrupted();
        if (!stopped.stopped()) {
          diagnostics.add(
              "Workspace discovery could not confirm process shutdown during"
                  + " bounded cleanup.");
        }
      }
      if (!command.isAlive()) {
        exitCode = OptionalInt.of(command.exitValue());
      }
      DrainOutcome drainOutcome =
          finishDrains(
              stdoutFuture, stderrFuture, command.stdout(), command.stderr(), cleanupGrace);
      interrupted |= drainOutcome.interrupted();
      drains.shutdownNow();

      if (!drainOutcome.stdoutComplete()) {
        diagnostics.add(
            "Workspace discovery stdout did not close during bounded cleanup;"
                + " only bytes already received were considered.");
      } else if (stdout.failure() != null) {
        diagnostics.add("Workspace discovery stdout could not be read completely.");
      }
      if (!drainOutcome.stderrComplete()) {
        diagnostics.add(
            "Workspace discovery stderr did not close during bounded cleanup;"
                + " only bytes already received were retained.");
      } else if (stderr.failure() != null) {
        diagnostics.add("Workspace discovery stderr could not be read completely.");
      }

      if (stdout.overflowed()) {
        diagnostics.add(
            "Workspace discovery stdout exceeded "
                + stdoutLimit
                + " bytes; only complete rows within that prefix were parsed.");
      }
      if (stderr.overflowed()) {
        diagnostics.add(
            "Workspace discovery stderr exceeded "
                + stderrLimit
                + " bytes and was truncated for display.");
      }

      String stderrText = new String(stderr.retained(), StandardCharsets.UTF_8);
      if (!stderrText.isEmpty()) {
        diagnostics.add(
            "Workspace discovery wrote stderr; bounded text is available" + " with this result.");
      }

      List<WorkspaceProfile> workspaces = List.of();
      byte[] retainedStdout = stdout.retained();
      if (stdout.overflowed()) {
        retainedStdout = completeLinePrefix(retainedStdout);
      }
      try {
        String stdoutText = decodeUtf8(retainedStdout);
        WorkspaceDiscoveryParser.ParseResult parsed = WorkspaceDiscoveryParser.parse(stdoutText);
        workspaces = parsed.workspaces();
        diagnostics.addAll(parsed.diagnostics());
      } catch (CharacterCodingException malformed) {
        diagnostics.add("Workspace discovery stdout was not valid UTF-8; no rows were accepted.");
      }

      boolean cancelledByClose = closeCancelledActive.get();
      if (cancelledByClose) {
        diagnostics.add(
            "Workspace discovery was cancelled because its owner closed;"
                + " complete output received before cancellation was kept.");
      } else if (exitCode.isPresent() && exitCode.getAsInt() != 0 && !timedOut) {
        diagnostics.add(
            "Workspace discovery exited with status "
                + exitCode.getAsInt()
                + "; valid stdout rows were kept.");
      }

      log.info(
          "workspace discovery finished exitCode={} timedOut={} workspaces={}"
              + " stdoutBytes={} stderrBytes={} durationMs={}",
          exitCode.isPresent() ? Integer.toString(exitCode.getAsInt()) : "unavailable",
          timedOut,
          workspaces.size(),
          stdout.totalBytes(),
          stderr.totalBytes(),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
      return new DiscoveryResult(workspaces, diagnostics, stderrText, exitCode, timedOut);
    } finally {
      activeCommand.compareAndSet(command, null);
      closeCancelledActive.set(false);
    }
  }

  /**
   * Permanently closes this discovery lifecycle and force-stops an active local process tree.
   *
   * <p>The signal is idempotent and does not wait for process exit, so window disposal may call it
   * directly. The worker inside {@link #discover()} performs the bounded reap and returns a
   * cancellation diagnostic. A closed instance cannot be reused.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    RunningCommand command = activeCommand.get();
    if (command != null) {
      closeCancelledActive.set(true);
      if (command.isAlive()) {
        forceStopFromClose(command);
      }
    }
  }

  private static void forceStopFromClose(RunningCommand command) {
    try {
      command.signal(CancellationMode.FORCE_KILL);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException failure) {
      log.debug("workspace discovery close signal failed", failure);
    }
  }

  private static DiscoveryResult closedResult() {
    return new DiscoveryResult(
        List.of(),
        List.of("Workspace discovery is closed; create a new instance to run it again."),
        "",
        OptionalInt.empty(),
        false);
  }

  private static DrainOutcome finishDrains(
      Future<?> stdoutFuture,
      Future<?> stderrFuture,
      InputStream stdout,
      InputStream stderr,
      Duration grace) {
    long deadline = System.nanoTime() + grace.toNanos();
    AwaitOutcome stdoutOutcome = await(stdoutFuture, stdout, deadline);
    AwaitOutcome stderrOutcome = await(stderrFuture, stderr, deadline);
    return new DrainOutcome(
        stdoutOutcome.complete(),
        stderrOutcome.complete(),
        stdoutOutcome.interrupted() || stderrOutcome.interrupted());
  }

  private static AwaitOutcome await(Future<?> future, InputStream stream, long deadline) {
    boolean interrupted = false;
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) {
        future.get(remaining, TimeUnit.NANOSECONDS);
        return new AwaitOutcome(true, false);
      }
    } catch (InterruptedException waitInterrupted) {
      interrupted = true;
    } catch (ExecutionException executionFailure) {
      return new AwaitOutcome(false, false);
    } catch (TimeoutException timeout) {
      // Close below to release a reader even when a descendant retained the pipe.
    }
    try {
      stream.close();
    } catch (IOException ignored) {
      // The bounded cleanup outcome is already explicit in the result.
    }
    future.cancel(true);
    return new AwaitOutcome(false, interrupted);
  }

  private static StopOutcome stopCommand(RunningCommand command, Duration grace) {
    boolean interrupted = false;
    try {
      command.signal(CancellationMode.TERMINATE);
    } catch (IOException | RuntimeException stopFailure) {
      log.debug("workspace discovery process cleanup failed", stopFailure);
    } catch (InterruptedException stopInterrupted) {
      interrupted = true;
    }
    try {
      if (!command.isAlive() || command.waitFor(grace)) {
        return new StopOutcome(true, interrupted);
      }
    } catch (InterruptedException waitInterrupted) {
      interrupted = true;
    }
    try {
      command.signal(CancellationMode.FORCE_KILL);
    } catch (IOException | RuntimeException forceFailure) {
      log.debug("workspace discovery forced cleanup failed", forceFailure);
    } catch (InterruptedException forceInterrupted) {
      interrupted = true;
    }
    try {
      if (command.isAlive()) {
        command.waitFor(grace);
      }
    } catch (InterruptedException waitInterrupted) {
      interrupted = true;
    }
    return new StopOutcome(!command.isAlive(), interrupted);
  }

  private static byte[] completeLinePrefix(byte[] retained) {
    for (int index = retained.length - 1; index >= 0; index--) {
      if (retained[index] == '\n' || retained[index] == '\r') {
        return Arrays.copyOf(retained, index + 1);
      }
    }
    return new byte[0];
  }

  private static String decodeUtf8(byte[] encoded) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(encoded))
        .toString();
  }

  private static Duration positive(Duration value, String name) {
    Duration checked = Objects.requireNonNull(value, name);
    if (checked.isZero() || checked.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return checked;
  }

  private static int positive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static void requireBackgroundThread() {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace discovery must not run on the EDT");
    }
  }

  private static <T> List<T> freshImmutable(List<T> values) {
    return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values")));
  }

  /** Immutable result of one complete script invocation. */
  public record DiscoveryResult(
      List<WorkspaceProfile> workspaces,
      List<String> diagnostics,
      String stderr,
      OptionalInt exitCode,
      boolean timedOut) {

    public DiscoveryResult {
      workspaces = freshImmutable(workspaces);
      diagnostics = freshImmutable(diagnostics);
      stderr = Objects.requireNonNull(stderr, "stderr");
      exitCode = Objects.requireNonNull(exitCode, "exitCode");
    }
  }

  private record AwaitOutcome(boolean complete, boolean interrupted) {}

  private record DrainOutcome(
      boolean stdoutComplete, boolean stderrComplete, boolean interrupted) {}

  private record StopOutcome(boolean stopped, boolean interrupted) {}

  private static final class OutputCapture {

    private final InputStream source;
    private final int limit;
    private final ByteArrayOutputStream retained;
    private long totalBytes;
    private IOException failure;

    OutputCapture(InputStream source, int limit) {
      this.source = Objects.requireNonNull(source, "source");
      this.limit = limit;
      retained = new ByteArrayOutputStream(Math.min(limit, 8_192));
    }

    void drain() {
      byte[] buffer = new byte[8_192];
      try (InputStream input = source) {
        int count;
        while ((count = input.read(buffer)) >= 0) {
          if (count == 0) {
            continue;
          }
          synchronized (this) {
            totalBytes = saturatedAdd(totalBytes, count);
            int remaining = limit - retained.size();
            if (remaining > 0) {
              retained.write(buffer, 0, Math.min(remaining, count));
            }
          }
        }
      } catch (IOException readFailure) {
        synchronized (this) {
          failure = readFailure;
        }
      }
    }

    synchronized byte[] retained() {
      return retained.toByteArray();
    }

    synchronized long totalBytes() {
      return totalBytes;
    }

    synchronized boolean overflowed() {
      return totalBytes > limit;
    }

    synchronized IOException failure() {
      return failure;
    }

    private static long saturatedAdd(long value, int increment) {
      return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
  }
}
