package com.holtherndon.bazelviz.runner.command;

import com.holtherndon.bazelviz.runner.proc.Subprocess;
import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.RuntimeEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands a command's options the way Bazel will, including the ones the user never typed.
 *
 * <h2>Why the argv is not the whole command</h2>
 *
 * <p>Options come from {@code .bazelrc} files as well as the command line — the system rc, the
 * user's {@code ~/.bazelrc}, the workspace's, and any {@code --config} expansion — and Bazel merges
 * them all before it runs anything. A planner that reads only the argv therefore cannot see a
 * {@code --bes_backend} the user's team set in their workspace rc, and will inject its own over the
 * top of it. Measured: the team's backend receives nothing for that invocation, with no conflict
 * raised, no {@code ReplacedFlag} recorded, and nothing in the session to say it happened. That is
 * precisely the silent override ADR-007 exists to prevent.
 *
 * <p>{@code bazel canonicalize-flags --announce_rc} is Bazel's own answer, and it needs no build.
 * Two channels, and both are read: the canonical form of the options passed in arrives on stdout,
 * and the options Bazel picked up from rc files are announced on stderr as {@code Inherited
 * '<section>' options: --flag=value …}.
 *
 * <h2>What this does not see</h2>
 *
 * <p>The announcement covers the sections {@code canonicalize-flags} itself inherits — {@code
 * common} and {@code build} — which is where {@code --bes_backend} is set in practice. An option
 * set under a command-specific section that this command does not inherit, such as a {@code
 * test}-only line, is <em>not</em> reported. Measured: a {@code test --bes_backend=…} line is
 * invisible here even with {@code --for_command=test}. So a plan built from this is better informed
 * than one built from the argv alone and is still not omniscient, which is why the planner warns
 * rather than claiming to have checked everything.
 *
 * <h2>Why this one runs inside the workspace</h2>
 *
 * <p>Unlike the capability probe, which deliberately runs outside the workspace in batch mode, this
 * has to run inside it and <em>with</em> the rc files: the rc files are the thing being asked
 * about. That means it contacts the Bazel server, so it can queue behind a build the user has
 * running — hence the timeout — but it does not restart it, because it uses the same rc-derived
 * startup options the user's own builds do.
 */
public final class EffectiveOptions {

  private static final Logger log = LoggerFactory.getLogger(EffectiveOptions.class);

  /**
   * Generous, because the answer matters and the command is cheap. The cost of waiting is a slower
   * launch; the cost of giving up is injecting a backend over somebody else's.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  private EffectiveOptions() {}

  /**
   * The canonical option list for {@code command}, rc files included.
   *
   * @return the expanded options, or empty when Bazel could not be asked — which the caller must
   *     report rather than treat as "no extra options"
   */
  public static Optional<List<String>> resolve(
      Path executable, Path workingDirectory, BazelCommand command, Duration timeout) {
    Objects.requireNonNull(executable, "executable");
    Objects.requireNonNull(command, "command");
    if (command.isEmpty()) {
      return Optional.empty();
    }
    List<String> argv = argv(executable.toString(), command);

    Subprocess.Result result;
    try {
      result = Subprocess.run(argv, workingDirectory, Map.of(), timeout);
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.debug(
          "canonicalize-flags could not run; failureType={}", failure.getClass().getSimpleName());
      return Optional.empty();
    }
    if (!result.isSuccess()) {
      log.debug(
          "canonicalize-flags failed exitCode={} timedOut={}",
          result.exitCode(),
          result.timedOut());
      return Optional.empty();
    }

    return parse(result.stdout(), result.stderr());
  }

  /**
   * Executor-backed form for a local or remote working directory.
   *
   * <p>The path strings belong to the executor. This method never resolves them through the desktop
   * filesystem.
   */
  public static Optional<List<String>> resolve(
      String executable,
      String workingDirectory,
      BazelCommand command,
      Duration timeout,
      CommandExecutor executor) {
    Objects.requireNonNull(executable, "executable");
    Objects.requireNonNull(workingDirectory, "workingDirectory");
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(executor, "executor");
    if (command.isEmpty()) {
      return Optional.empty();
    }
    CommandResult result;
    try {
      RuntimeEnvironment inheritance =
          switch (command.inheritance()) {
            case INHERIT_ALL -> RuntimeEnvironment.INHERIT_ALL;
            case INHERIT_ALLOWLISTED -> RuntimeEnvironment.INHERIT_ESSENTIAL;
            case NONE -> RuntimeEnvironment.NONE;
          };
      result =
          executor.run(
              new CommandRequest(
                  argv(executable, command),
                  Optional.of(workingDirectory),
                  command.environmentOverrides(),
                  inheritance,
                  false),
              timeout);
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.debug(
          "canonicalize-flags could not run; failureType={}", failure.getClass().getSimpleName());
      return Optional.empty();
    }
    if (!result.isSuccess()) {
      log.debug(
          "canonicalize-flags failed exitCode={} timedOut={}",
          result.exitCode(),
          result.timedOut());
      return Optional.empty();
    }
    return parse(result.stdout(), result.stderr());
  }

  public static Optional<List<String>> resolve(
      String executable, String workingDirectory, BazelCommand command, CommandExecutor executor) {
    return resolve(executable, workingDirectory, command, DEFAULT_TIMEOUT, executor);
  }

  private static List<String> argv(String executable, BazelCommand command) {
    List<String> argv = new ArrayList<>();
    argv.add(executable);
    argv.addAll(command.startupArgs());
    argv.add("canonicalize-flags");
    argv.add("--announce_rc");
    argv.add("--for_command=" + command.command());
    argv.add("--");
    argv.addAll(command.commandArgs());
    return argv;
  }

  private static Optional<List<String>> parse(String stdout, String stderr) {
    List<String> options = new ArrayList<>();
    options.addAll(rcOptions(stderr));
    for (String line : stdout.split("\n")) {
      String option = line.strip();
      if (option.startsWith("--")) {
        options.add(option);
      }
    }
    return Optional.of(List.copyOf(options));
  }

  /**
   * The options Bazel announced as coming from rc files.
   *
   * <p>Parsed from lines of the form {@code Inherited 'build' options: --a=1 --b=2}. The client's
   * own terminal options are announced the same way and are dropped: they are Bazel talking to
   * itself about the tty, not something the user configured, and reporting them as user options
   * would produce nonsense conflicts.
   */
  private static List<String> rcOptions(String stderr) {
    List<String> options = new ArrayList<>();
    for (String line : stderr.split("\n")) {
      int marker = line.indexOf("options:");
      if (marker < 0 || !line.contains("Inherited")) {
        continue;
      }
      for (String token : line.substring(marker + "options:".length()).strip().split("\\s+")) {
        if (token.startsWith("--") && !CLIENT_OPTIONS.contains(nameOf(token))) {
          options.add(token);
        }
      }
    }
    return options;
  }

  private static String nameOf(String token) {
    String bare = token.substring(2);
    int equals = bare.indexOf('=');
    return equals < 0 ? bare : bare.substring(0, equals);
  }

  /** Options Bazel's client adds for itself, which no user set. */
  private static final Set<String> CLIENT_OPTIONS =
      Set.of(
          "isatty",
          "terminal_columns",
          "client_env",
          "client_cwd",
          "rc_source",
          "default_override");

  public static Optional<List<String>> resolve(
      Path executable, Path workingDirectory, BazelCommand command) {
    return resolve(executable, workingDirectory, command, DEFAULT_TIMEOUT);
  }
}
