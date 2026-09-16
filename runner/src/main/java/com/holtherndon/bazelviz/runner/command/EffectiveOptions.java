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
import java.util.regex.Pattern;
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
 * <p>{@code bazel canonicalize-flags --announce_rc} needs no build. Two channels are read: the
 * canonical form of the options passed in arrives on stdout, and options from rc files are
 * announced on stderr. Explicit {@code --config} selections are also passed to the inspecting
 * command before its separator: selections after the separator alone are printed without being
 * expanded. Bazel announces the selected configs and their nested expansions as {@code Found
 * applicable config definition ...}.
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
 * <p>Rc announcements join arguments with spaces without preserving their original quoting. Values
 * containing spaces are retained, including literal quote characters, but text within a value that
 * itself looks like another option cannot be distinguished from a separate option. These are
 * observed options for conservative conflict checks, not a complete reconstruction of effective
 * argv or its precedence.
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

  private static final Pattern ANNOUNCED_OPTION_BOUNDARY =
      Pattern.compile("(?<=\\S)\\s+(?=--?[A-Za-z][A-Za-z0-9_-]*(?:[=\\s]|$))");

  /**
   * Generous, because the answer matters and the command is cheap. The cost of waiting is a slower
   * launch; the cost of giving up is injecting a backend over somebody else's.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  private EffectiveOptions() {}

  /**
   * Observed options for {@code command}, including inherited rc sections and selected configs.
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
    List<String> arguments = command.commandArgs();
    for (int i = 0; i < arguments.size(); i++) {
      String argument = arguments.get(i);
      if (argument.equals("--")) {
        break;
      }
      if (argument.startsWith("--config=")) {
        argv.add(argument);
      } else if (argument.equals("--config")) {
        argv.add(argument);
        if (i + 1 < arguments.size()) {
          argv.add(arguments.get(++i));
        }
      }
    }
    // Keep inspection controls after config expansions that could otherwise disable announcements.
    argv.add("--announce_rc");
    argv.add("--for_command=" + command.command());
    argv.add("--");
    argv.addAll(command.commandArgs());
    return argv;
  }

  private static Optional<List<String>> parse(String stdout, String stderr) {
    Optional<List<String>> inherited = rcOptions(stderr);
    if (inherited.isEmpty()) {
      log.debug("canonicalize-flags produced an unreadable rc announcement");
      return Optional.empty();
    }
    List<String> options = new ArrayList<>(inherited.orElseThrow());
    for (String line : stdout.split("\n")) {
      String option = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
      if (option.startsWith("--")) {
        options.add(option);
      }
    }
    return Optional.of(List.copyOf(options));
  }

  /**
   * The options Bazel announced as coming from rc files.
   *
   * <p>Both inherited sections and named config definitions contain unquoted, space-joined
   * arguments. Split only before another option so that values containing spaces or literal quotes
   * survive. Keep a separate value as its own list entry for the existing command parser. The
   * client's own terminal options are excluded because they are not user configuration.
   */
  private static Optional<List<String>> rcOptions(String stderr) {
    List<String> options = new ArrayList<>();
    for (String line : stderr.split("\n")) {
      int marker;
      int config = line.indexOf("Found applicable config definition ");
      if (config >= 0) {
        int file = line.indexOf(" in file ", config);
        marker = file < 0 ? -1 : line.indexOf(": ", file + " in file ".length());
        if (marker < 0) {
          return Optional.empty();
        }
        marker += 2;
      } else if (line.contains("Inherited '")) {
        marker = line.indexOf("options:");
        if (marker < 0) {
          return Optional.empty();
        }
        marker += "options:".length();
      } else {
        continue;
      }
      String announcement = line.substring(marker).stripLeading();
      if (announcement.isBlank()) {
        continue;
      }
      for (String argument : ANNOUNCED_OPTION_BOUNDARY.split(announcement)) {
        int separateValue = -1;
        for (int i = 0; i < argument.length(); i++) {
          if (argument.charAt(i) == '=') {
            break;
          }
          if (Character.isWhitespace(argument.charAt(i))) {
            separateValue = i;
            break;
          }
        }
        String option = separateValue < 0 ? argument : argument.substring(0, separateValue);
        if (!CommandLineParser.isFlag(option)) {
          return Optional.empty();
        }
        if (!CLIENT_OPTIONS.contains(nameOf(option))) {
          options.add(option);
          if (separateValue >= 0) {
            options.add(argument.substring(separateValue + 1));
          }
        }
      }
    }
    return Optional.of(List.copyOf(options));
  }

  private static String nameOf(String token) {
    String bare = token.substring(token.startsWith("--") ? 2 : 1);
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
