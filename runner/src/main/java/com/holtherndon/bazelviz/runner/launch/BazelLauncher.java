package com.holtherndon.bazelviz.runner.launch;

import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.EnvironmentInheritance;
import com.holtherndon.bazelviz.runner.proc.CancellationMode;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import com.holtherndon.bazelviz.runner.proc.ProcessOutcome;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a Bazel process, streams its console output, and stops it when asked (plan 8.1, 8.7).
 *
 * <h2>Direct argv, never a shell</h2>
 *
 * <p>Every element of {@link BazelCommand#toArgv()} becomes one {@code argv} entry. Nothing is
 * quoted, escaped or concatenated into a string, so a target pattern containing a space or a dollar
 * sign is passed through as itself and there is no expansion for anything to be injected into (plan
 * 22.3). Shell mode exists for users who need expansion, and is a separate, labelled path rather
 * than the default one.
 */
public final class BazelLauncher {

  private static final Logger log = LoggerFactory.getLogger(BazelLauncher.class);

  /**
   * How old the client must be before a cancellation signal is sent to it.
   *
   * <p>One second, and empirical: signals delivered inside the first few tens of milliseconds are a
   * race that can lose the signal entirely or kill the Bazel server. See {@code
   * BazelProcess.awaitSignalReadiness}.
   */
  private static final long SIGNAL_READY_MILLIS = 1_000;

  /** Environment variables Bazel needs to run at all. */
  private static final Set<String> ESSENTIAL_ENVIRONMENT =
      Set.of("PATH", "HOME", "USER", "LOGNAME", "TMPDIR", "SHELL", "LANG", "LC_ALL");

  /** Bazel operations safe to name in logs; arbitrary argv text is never logged. */
  private static final Set<String> SAFE_OPERATIONS =
      Set.of(
          "analyze-profile",
          "aquery",
          "build",
          "canonicalize-flags",
          "clean",
          "coverage",
          "cquery",
          "dump",
          "fetch",
          "help",
          "info",
          "license",
          "mobile-install",
          "mod",
          "print_action",
          "query",
          "run",
          "shutdown",
          "sync",
          "test",
          "version");

  private BazelLauncher() {}

  /**
   * Starts the process and returns a handle to it.
   *
   * <p>Two pump threads begin draining stdout and stderr immediately, before this returns. A pipe
   * has a finite buffer, and Bazel fills the stderr one quickly; a caller that started the process
   * and then did something else first would find the build blocked on a write nobody was reading.
   */
  public static BazelProcess start(LaunchRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    BazelCommand command = request.command();
    if (command.isEmpty()) {
      throw new IOException("there is no Bazel command to run");
    }
    if (!Files.isDirectory(command.workingDirectory())) {
      throw new IOException("the working directory does not exist: " + command.workingDirectory());
    }

    List<String> argv =
        command.shellMode() ? shellArgv(command, request.shellPath()) : command.toArgv();

    ProcessBuilder builder =
        new ProcessBuilder(argv).directory(command.workingDirectory().toFile());
    applyEnvironment(builder, command);

    logLaunch(request, "local-process", argv.size(), false);
    Process process = builder.start();
    return new BazelProcess(process, request, argv);
  }

  /**
   * Starts through an explicit execution runtime.
   *
   * <p>The command's historical {@code Path} fields are converted to text only. They are never
   * checked against the desktop filesystem, so this overload is safe for a remote Linux executor
   * while those domain fields are being migrated to execution paths.
   */
  public static BazelProcess start(
      LaunchRequest request, CommandExecutor executor, boolean forceTty) throws IOException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(executor, "executor");
    BazelCommand command = request.command();
    if (command.isEmpty()) {
      throw new IOException("there is no Bazel command to run");
    }
    List<String> argv =
        command.shellMode() ? executorShellArgv(command, request.shellPath()) : command.toArgv();
    RuntimeEnvironment inheritance =
        switch (command.inheritance()) {
          case INHERIT_ALL -> RuntimeEnvironment.INHERIT_ALL;
          case INHERIT_ALLOWLISTED -> RuntimeEnvironment.INHERIT_ESSENTIAL;
          case NONE -> RuntimeEnvironment.NONE;
        };
    CommandRequest commandRequest =
        new CommandRequest(
            argv,
            Optional.of(command.workingDirectory().toString()),
            command.environmentOverrides(),
            inheritance,
            forceTty);
    logLaunch(request, safeExecutorName(executor), argv.size(), forceTty);
    return new BazelProcess(executor.start(commandRequest), request, argv);
  }

  private static void logLaunch(
      LaunchRequest request, String executor, int argumentCount, boolean forceTty) {
    BazelCommand command = request.command();
    String operation = operationForLogging(command.command());
    log.info(
        "Bazel launch started operation={} executor={} workingDirectory={}" + " argumentCount={}",
        operation,
        executor,
        command.workingDirectory(),
        argumentCount);
    log.debug(
        "Bazel launch structure operation={} shellMode={} tty={}"
            + " startupArgumentCount={} commandArgumentCount={} targetCount={}"
            + " residueArgumentCount={} environment={} environmentOverrideCount={}",
        operation,
        command.shellMode(),
        forceTty,
        command.startupArgs().size(),
        command.commandArgs().size(),
        command.targets().size(),
        command.argsAfterDoubleDash().size(),
        command.inheritance(),
        command.environmentOverrides().size());
  }

  /**
   * Returns a structural Bazel operation name that is safe to include in logs.
   *
   * <p>Callers that describe a planned launch must use this allowlist too; the command field
   * originates in user-entered argv and can otherwise contain labels, paths, tokens, or control
   * text.
   */
  public static String operationForLogging(String operation) {
    return operation != null && SAFE_OPERATIONS.contains(operation) ? operation : "other";
  }

  private static String safeExecutorName(CommandExecutor executor) {
    return switch (executor.getClass().getSimpleName()) {
      case "LocalCommandExecutor" -> "local-runtime";
      case "SshCommandExecutor" -> "ssh";
      default -> "custom";
    };
  }

  /**
   * The argv for shell mode.
   *
   * <p>Present so that a user who needs a wrapper, a pipe or a substitution can have one, and so
   * that what they get is exactly what the dialog showed them. It is not reachable by default, and
   * the command it builds is displayed verbatim — a shell mode whose real command line the user
   * cannot read would be worse than none.
   */
  private static List<String> shellArgv(BazelCommand command, Optional<String> shellPath) {
    String shell =
        shellPath.orElseGet(
            () -> {
              String fromEnvironment = System.getenv("SHELL");
              return fromEnvironment == null || fromEnvironment.isBlank()
                  ? "/bin/sh"
                  : fromEnvironment;
            });
    return List.of(shell, "-c", String.join(" ", command.toArgv()));
  }

  private static List<String> executorShellArgv(BazelCommand command, Optional<String> shellPath) {
    // A desktop SHELL value is not a fact about an SSH host.
    String shell = shellPath.orElse("/bin/sh");
    return List.of(shell, "-c", String.join(" ", command.toArgv()));
  }

  private static void applyEnvironment(ProcessBuilder builder, BazelCommand command) {
    Map<String, String> environment = builder.environment();
    switch (command.inheritance()) {
      case INHERIT_ALL -> {
        // Nothing to do: ProcessBuilder starts from this process's
        // environment, which is the user's.
      }
      case INHERIT_ALLOWLISTED -> {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : ESSENTIAL_ENVIRONMENT) {
          String value = environment.get(name);
          if (value != null) {
            kept.put(name, value);
          }
        }
        environment.clear();
        environment.putAll(kept);
      }
      case NONE -> environment.clear();
    }
    command
        .environmentOverrides()
        .forEach(
            (name, value) ->
                value.ifPresentOrElse(
                    present -> environment.put(name, present), () -> environment.remove(name)));
    if (command.inheritance() != EnvironmentInheritance.INHERIT_ALL
        && !environment.containsKey("PATH")) {
      // Said out loud rather than silently repaired: Bazel without a PATH
      // fails for reasons that have nothing to do with the user's command,
      // and quietly adding one back would make the recorded environment a
      // lie about what ran.
      log.warn("the launched build has no PATH; Bazel will probably fail to find its tools");
    }
  }

  /**
   * A running Bazel process.
   *
   * <p>Owns the two pump threads and the cancellation ladder. Closing it does not stop the process
   * — {@link #cancel} does that — because a session may legitimately outlive the object watching
   * it, and a {@code close} that killed a build would be a footgun in a try-with-resources.
   */
  public static final class BazelProcess {

    private final RunningCommand process;
    private final LaunchRequest request;
    private final List<String> argv;
    private final Thread stdout;
    private final Thread stderr;
    private final long startedNanos = System.nanoTime();
    private final long signalReadyMillis;

    /**
     * Serializes the delivery of rungs, and nothing else.
     *
     * <p>Held only for as long as a signal takes to send, never across a grace period. Two threads
     * may legitimately be stopping the same client at once — the user clicks Cancel and then
     * Terminate — and they must not interleave signals or send the same rung twice, but a lock held
     * across the thirty-second Cancel grace would make Terminate do nothing for thirty seconds,
     * which is the complaint this whole class exists to answer.
     */
    private final Object stopLock = new Object();

    /** The harshest rung actually delivered. Guarded by {@link #stopLock}. */
    private CancellationMode delivered;

    private volatile CancellationMode cancelledWith;

    BazelProcess(Process process, LaunchRequest request, List<String> argv) {
      this(process, request, argv, OsStopSignals.INSTANCE, SIGNAL_READY_MILLIS);
    }

    /**
     * For tests: a scripted process, a recording signal sink and no readiness delay.
     *
     * @param signals where the rungs are delivered
     * @param signalReadyMillis how old the client must be before a signal is sent; zero to send at
     *     once
     */
    BazelProcess(
        Process process,
        LaunchRequest request,
        List<String> argv,
        StopSignals signals,
        long signalReadyMillis) {
      this(new LocalRunningCommand(process, signals), request, argv, signalReadyMillis);
    }

    private BazelProcess(RunningCommand process, LaunchRequest request, List<String> argv) {
      this(process, request, argv, SIGNAL_READY_MILLIS);
    }

    private BazelProcess(
        RunningCommand process, LaunchRequest request, List<String> argv, long signalReadyMillis) {
      this.process = Objects.requireNonNull(process, "process");
      this.request = request;
      this.argv = List.copyOf(argv);
      this.signalReadyMillis = signalReadyMillis;
      this.stdout = pump(process.stdout(), ConsoleSink.ConsoleStream.STDOUT);
      this.stderr =
          process.streamsMerged()
              ? completedPump("bbv-console-merged-stderr")
              : pump(process.stderr(), ConsoleSink.ConsoleStream.STDERR);
    }

    /** The exact argv that was executed, for the manifest. */
    public List<String> argv() {
      return argv;
    }

    public long pid() {
      return process.pid();
    }

    public boolean isAlive() {
      return process.isAlive();
    }

    /**
     * Waits for the process to exit, then keeps draining briefly.
     *
     * <p>The drain is plan 8.7's "continue draining output briefly": the last lines of a failing
     * build arrive after the client has exited, and a capture that stopped reading at exit would
     * lose exactly the error the user is looking for.
     */
    public ProcessOutcome await() throws InterruptedException {
      int exitCode = process.waitFor();
      drain();
      Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
      CancellationMode mode = cancelledWith;
      ProcessOutcome outcome =
          mode == null
              ? ProcessOutcome.exited(exitCode, elapsed)
              : ProcessOutcome.cancelled(OptionalInt.of(exitCode), mode, elapsed);
      logOutcome(outcome);
      return outcome;
    }

    /** The harshest stop actually delivered to this client, if any. */
    public Optional<CancellationMode> stoppedBy() {
      synchronized (stopLock) {
        return Optional.ofNullable(delivered);
      }
    }

    /**
     * Stops the process, escalating through the ladder if it does not go.
     *
     * <p>{@link CancellationMode#CANCEL} sends {@code SIGINT}, which is what Ctrl-C sends and what
     * makes Bazel interrupt the build <em>and still flush its event stream</em>. {@link
     * Process#destroy()} sends {@code SIGTERM} instead, which is a different and less useful
     * ending, so the interrupt is sent with {@code kill} rather than through the Java API that
     * looks like it would do it.
     *
     * <h2>What {@code escalate} decides, and why the caller that matters asks for it</h2>
     *
     * <p>{@code escalate=false} sends one rung and reports what happened. It is for a caller that
     * owns its own ladder — a user interface with a Terminate button, or a test — and it makes no
     * promise that the client is dead when it returns.
     *
     * <p>{@code escalate=true} promises the opposite: the client will be gone within the sum of the
     * grace periods, because the last rung is {@code SIGKILL} and a process cannot ignore that.
     * That promise is not a nicety. A Bazel client holds its workspace's command lock for the whole
     * of its life, so a client that survives a cancellation blocks every later Bazel command in
     * that workspace — including {@code bazel clean}, which is the first thing a user reaches for.
     * Every caller that has nobody to click a second button therefore asks to escalate, and the
     * escalation is <em>reported</em> rather than silent: {@link #stoppedBy()} says which rung it
     * took, so a session can tell the user that its gentle stop was not enough.
     *
     * <p>Rungs already delivered are never re-sent, and a harsher rung delivered by another thread
     * is picked up here rather than fought with, so a second click during a grace period shortens
     * the ladder instead of starting a second one.
     *
     * @param mode the gentlest stop to try
     * @param escalate whether to continue to harsher stops when the grace period passes
     */
    public ProcessOutcome cancel(CancellationMode mode, boolean escalate)
        throws InterruptedException {
      Objects.requireNonNull(mode, "mode");
      awaitSignalReadiness();
      CancellationMode current = mode;
      while (true) {
        // deliver() returns the harshest rung sent so far, which may be
        // harsher than the one asked for here: another thread's click
        // has already overtaken this ladder, and its grace period is the
        // one that now applies.
        current = deliver(current);
        Duration grace = request.gracePeriodFor(current);
        if (waitForExit(grace, escalate)) {
          drain();
          ProcessOutcome outcome =
              ProcessOutcome.cancelled(
                  OptionalInt.of(process.exitValue()),
                  current,
                  Duration.ofNanos(System.nanoTime() - startedNanos));
          logOutcome(outcome);
          return outcome;
        }
        Optional<CancellationMode> next = escalate ? current.escalation() : Optional.empty();
        if (next.isEmpty()) {
          drain();
          // No exit code: the process is still running, or was killed
          // without producing one. Reporting 0 or -1 here would be an
          // unavailable value shown as a number.
          ProcessOutcome outcome =
              ProcessOutcome.cancelled(
                  OptionalInt.empty(), current, Duration.ofNanos(System.nanoTime() - startedNanos));
          logOutcome(outcome);
          return outcome;
        }
        log.info("{} did not stop within {}; escalating to {}", pid(), grace, next.get());
        current = next.get();
      }
    }

    /**
     * Delivers one stop and returns immediately.
     *
     * <p>For a caller that is not the one waiting: the capture thread is already blocked in {@link
     * #await()}, so a second thread that only needs the signal to go out has no reason to sit
     * through a grace period it will do nothing with. Waiting there is worse than useless — it is
     * what makes a Terminate click appear to do nothing until the Cancel grace expires.
     */
    public void requestStop(CancellationMode mode) throws InterruptedException {
      Objects.requireNonNull(mode, "mode");
      awaitSignalReadiness();
      log.debug(
          "Bazel cancellation requested operation={} mode={} processId={}",
          operationForLogging(request.command().command()),
          mode,
          pid());
      deliver(mode);
    }

    private void logOutcome(ProcessOutcome outcome) {
      String exit =
          outcome.exitCode().isPresent()
              ? Integer.toString(outcome.exitCode().getAsInt())
              : "unavailable";
      if (outcome.wasCancelled()) {
        log.info(
            "Bazel launch cancelled operation={} mode={} exitCode={} durationMs={}",
            operationForLogging(request.command().command()),
            outcome.terminatedBy().orElseThrow(),
            exit,
            outcome.duration().toMillis());
      } else {
        log.info(
            "Bazel launch completed operation={} exitCode={} durationMs={}",
            operationForLogging(request.command().command()),
            exit,
            outcome.duration().toMillis());
      }
    }

    /**
     * Sends {@code mode} unless something at least as harsh has already gone out, and returns the
     * harshest rung delivered.
     *
     * <p>A rung is sent at most once. Repeating one that has already failed to land achieves
     * nothing and, for {@code SIGINT}, races the pid reuse that {@link OsStopSignals} guards
     * against.
     */
    private CancellationMode deliver(CancellationMode mode) throws InterruptedException {
      synchronized (stopLock) {
        if (delivered == null && !process.isAlive()) {
          // Nothing to stop: it ended on its own between the click and
          // this call. Recording a cancellation here would relabel a
          // finished build as one the user stopped, and a session that
          // says CANCELLED about a build that completed is a lie about
          // what happened.
          return mode;
        }
        if (delivered == null || mode.ordinal() > delivered.ordinal()) {
          try {
            process.signal(mode);
            delivered = mode;
            cancelledWith = mode;
          } catch (IOException failure) {
            log.warn(
                "could not deliver {} to process {}; failureType={}",
                mode,
                pid(),
                failure.getClass().getSimpleName());
          }
        }
        return delivered == null ? mode : delivered;
      }
    }

    /**
     * Waits out one grace period, force-stopping rather than abandoning the client if the wait is
     * interrupted.
     *
     * <p>An interrupt used to end the ladder where it stood. The thread died with its interrupt
     * flag set and the client — which had just been sent a signal it was demonstrably ignoring —
     * was left running, holding the workspace lock, with nothing left in the process that would
     * ever come back to it. Interrupting the thread that is stopping a build is a request to stop
     * <em>sooner</em>, so it takes the last rung immediately.
     */
    private boolean waitForExit(Duration grace, boolean escalate) throws InterruptedException {
      try {
        return process.waitFor(grace);
      } catch (InterruptedException interrupted) {
        if (escalate && process.isAlive()) {
          log.info(
              "the stop of {} was interrupted; force-killing rather than leaving it"
                  + " holding the workspace lock",
              pid());
          deliver(CancellationMode.FORCE_KILL);
        }
        throw interrupted;
      }
    }

    /**
     * Waits until the client is old enough to have installed its signal handler.
     *
     * <p>Measured, and worth the wait: a signal delivered in the first few tens of milliseconds of
     * the client's life is a race with four outcomes. It can exit 130 with no event stream written
     * at all; it can be handled normally; it can be <em>lost</em>, leaving the build to run to
     * completion and exit 0 despite the user having pressed Cancel; and on Bazel 9 it can take the
     * whole Bazel server down with exit 37.
     *
     * <p>Delaying the signal instead of firing it immediately turns all four into the one
     * predictable outcome. A user who clicks Cancel in the first second waits a fraction of a
     * second longer and gets a session that says what happened.
     */
    private void awaitSignalReadiness() throws InterruptedException {
      long ageMillis = (System.nanoTime() - startedNanos) / 1_000_000;
      long remaining = signalReadyMillis - ageMillis;
      if (remaining <= 0 || !process.isAlive()) {
        return;
      }
      log.debug(
          "holding the cancel signal for {}ms: the client is only {}ms old", remaining, ageMillis);
      process.waitFor(Duration.ofMillis(remaining));
    }

    /** Waits out the configured post-exit drain window. */
    private void drain() {
      long deadline = System.nanoTime() + request.drainAfterExit().toNanos();
      joinUntil(stdout, deadline);
      joinUntil(stderr, deadline);
    }

    private static void joinUntil(Thread thread, long deadlineNanos) {
      long remaining = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000);
      try {
        thread.join(remaining);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private Thread pump(InputStream stream, ConsoleSink.ConsoleStream which) {
      Thread thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[8 * 1024];
                try (stream) {
                  int read;
                  while ((read = stream.read(buffer)) > 0) {
                    request.console().accept(which, buffer, 0, read);
                  }
                } catch (IOException closed) {
                  // The process ended and the pipe went with it. Everything
                  // read so far has already been delivered.
                  log.debug(
                      "{} pipe closed; failureType={}", which, closed.getClass().getSimpleName());
                } catch (RuntimeException misbehaving) {
                  // A sink that throws must not take the build's output with
                  // it, and must not leave the pipe unread and the build
                  // blocked on a full buffer.
                  log.warn(
                      "the console sink failed while reading {}; failureType={}",
                      which,
                      misbehaving.getClass().getSimpleName());
                }
              },
              "bbv-console-" + which.name().toLowerCase(Locale.ROOT));
      thread.setDaemon(true);
      thread.start();
      return thread;
    }

    private static Thread completedPump(String name) {
      Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {});
      thread.start();
      return thread;
    }

    private static final class LocalRunningCommand implements RunningCommand {

      private final Process process;
      private final StopSignals signals;

      private LocalRunningCommand(Process process, StopSignals signals) {
        this.process = Objects.requireNonNull(process, "process");
        this.signals = Objects.requireNonNull(signals, "signals");
      }

      @Override
      public InputStream stdout() {
        return process.getInputStream();
      }

      @Override
      public InputStream stderr() {
        return process.getErrorStream();
      }

      @Override
      public OutputStream stdin() {
        return process.getOutputStream();
      }

      @Override
      public boolean streamsMerged() {
        return false;
      }

      @Override
      public long pid() {
        return process.pid();
      }

      @Override
      public OptionalLong processGroupId() {
        return OptionalLong.empty();
      }

      @Override
      public boolean isAlive() {
        return process.isAlive();
      }

      @Override
      public int waitFor() throws InterruptedException {
        return process.waitFor();
      }

      @Override
      public boolean waitFor(Duration timeout) throws InterruptedException {
        return process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
      }

      @Override
      public int exitValue() {
        return process.exitValue();
      }

      @Override
      public void signal(CancellationMode mode) {
        signals.deliver(mode, process);
      }
    }
  }

  /** The environment names kept by {@link EnvironmentInheritance#INHERIT_ALLOWLISTED}. */
  public static List<String> essentialEnvironmentNames() {
    return List.copyOf(new ArrayList<>(ESSENTIAL_ENVIRONMENT));
  }
}
