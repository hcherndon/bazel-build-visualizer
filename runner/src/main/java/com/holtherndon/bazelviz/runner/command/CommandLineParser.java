package com.holtherndon.bazelviz.runner.command;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits a user's Bazel command into the parts plan 8.1 requires.
 *
 * <h2>The grammar, as Bazel actually implements it</h2>
 *
 * <p>Established by running real binaries rather than by reading documentation:
 *
 * <ul>
 *   <li>Startup options come before the command and are rejected after it, and vice versa. The two
 *       misplacements even fail differently — one from the C++ client, one from the server — but
 *       both are fatal, so the split is not a nicety.
 *   <li>Flags and target patterns are freely interleaved after the command. {@code build //a
 *       --keep_going //b} is accepted and the flag takes full effect, so a parser that assumes
 *       flags-then-targets mis-reads ordinary commands.
 *   <li>Values may be attached ({@code --flag=value}) or separate ({@code --flag value}). Splitting
 *       on {@code =} alone loses the second form, and the second form is what people type.
 *   <li>The first {@code --} is a separator; every later one is an ordinary argument. After it,
 *       <em>everything</em> is residue — on {@code build}, a flag placed there is silently
 *       reinterpreted as a negative target pattern.
 * </ul>
 *
 * <h2>Why capabilities are an input</h2>
 *
 * <p>{@code --flag value} cannot be told from {@code --flag} followed by a target without knowing
 * whether the flag takes a value, and only the binary knows that. When capabilities are available
 * the parser asks them. When they are not — an unprobed binary, or a Bazel too old to report {@code
 * requires_value} — it falls back to a stated heuristic rather than pretending to certainty: a
 * following token that starts with {@code -} or looks like a target pattern is not treated as the
 * flag's value.
 */
public final class CommandLineParser {

  private final Optional<BazelCapabilities> capabilities;

  public CommandLineParser() {
    this(Optional.empty());
  }

  public CommandLineParser(Optional<BazelCapabilities> capabilities) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
  }

  /**
   * Parses {@code args} — everything the user typed after the executable.
   *
   * @param executable the resolved launcher
   * @param workingDirectory where the process will run
   */
  public BazelCommand parse(Path executable, Path workingDirectory, List<String> args) {
    Objects.requireNonNull(args, "args");
    BazelCommand.Builder builder = BazelCommand.builder(executable, workingDirectory);

    int index = 0;
    // Startup options: everything before the first token that is not a flag
    // and not a flag's value.
    List<String> startupArgs = new ArrayList<>();
    while (index < args.size()) {
      String token = args.get(index);
      if (!isFlag(token)) {
        break;
      }
      startupArgs.add(token);
      index++;
      if (takesSeparateValue(token, "startup", args, index, true)) {
        startupArgs.add(args.get(index));
        index++;
      }
    }
    builder.startupArgs(startupArgs);

    if (index >= args.size()) {
      // Flags but no command. Returned as-is rather than guessed at: the
      // launcher reports it as a usage problem, which is what it is.
      return builder.build();
    }

    String command = args.get(index);
    index++;
    builder.command(command);

    List<String> commandArgs = new ArrayList<>();
    List<String> targets = new ArrayList<>();
    List<String> afterSeparator = new ArrayList<>();
    boolean pastSeparator = false;

    while (index < args.size()) {
      String token = args.get(index);
      index++;
      if (pastSeparator) {
        afterSeparator.add(token);
        continue;
      }
      if (token.equals("--")) {
        // Only the first one separates. A later "--" is an argument to
        // whatever is being run.
        pastSeparator = true;
        continue;
      }
      if (isFlag(token) && !isNegativeTargetPattern(token)) {
        commandArgs.add(token);
        if (takesSeparateValue(token, command, args, index, false)) {
          commandArgs.add(args.get(index));
          index++;
        }
        continue;
      }
      targets.add(token);
    }

    return builder
        .commandArgs(commandArgs)
        .targets(targets)
        .argsAfterDoubleDash(afterSeparator)
        .build();
  }

  /** Splits a single typed string on whitespace, honouring simple quoting. */
  public static List<String> tokenize(String commandLine) {
    Objects.requireNonNull(commandLine, "commandLine");
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inToken = false;
    char quote = 0;
    for (int i = 0; i < commandLine.length(); i++) {
      char c = commandLine.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          current.append(c);
        }
        continue;
      }
      if (c == '\'' || c == '"') {
        quote = c;
        inToken = true;
        continue;
      }
      if (Character.isWhitespace(c)) {
        if (inToken) {
          tokens.add(current.toString());
          current.setLength(0);
          inToken = false;
        }
        continue;
      }
      current.append(c);
      inToken = true;
    }
    if (inToken) {
      tokens.add(current.toString());
    }
    return List.copyOf(tokens);
  }

  /** True when {@code token} is a flag rather than a target or a command. */
  public static boolean isFlag(String token) {
    return token.length() > 1 && token.startsWith("-") && !token.equals("--");
  }

  /**
   * The flag's name, without dashes or negation prefix, or empty when {@code token} is not a flag.
   */
  public static Optional<String> flagName(String token) {
    if (!isFlag(token)) {
      return Optional.empty();
    }
    String bare = token.startsWith("--") ? token.substring(2) : token.substring(1);
    int equals = bare.indexOf('=');
    if (equals >= 0) {
      bare = bare.substring(0, equals);
    }
    return bare.isEmpty() ? Optional.empty() : Optional.of(bare);
  }

  /** The value attached with {@code =}, when there is one. */
  public static Optional<String> attachedValue(String token) {
    if (!isFlag(token)) {
      return Optional.empty();
    }
    int equals = token.indexOf('=');
    return equals < 0 ? Optional.empty() : Optional.of(token.substring(equals + 1));
  }

  /**
   * @param beforeCommand true while scanning startup options, where the next non-flag token is the
   *     Bazel command itself. Guessing wrong there does not merely mis-attribute an argument: it
   *     eats the command, and the plan then refuses a perfectly good build with a reason that is
   *     not true. So an unknown flag never binds a value in that position — the cost of being wrong
   *     is asymmetric.
   */
  private boolean takesSeparateValue(
      String token, String command, List<String> args, int nextIndex, boolean beforeCommand) {
    if (attachedValue(token).isPresent() || nextIndex >= args.size()) {
      return false;
    }
    String next = args.get(nextIndex);
    Optional<String> name = flagName(token);
    if (name.isEmpty()) {
      return false;
    }
    // A negated boolean never takes a value: --noflag=x is not a thing.
    if (name.get().startsWith("no")
        && capabilities
            .flatMap(caps -> caps.flag(name.get().substring(2)))
            .map(FlagSpec::hasNegativeForm)
            .orElse(false)) {
      return false;
    }
    Optional<Boolean> requiresValue =
        capabilities
            .flatMap(caps -> caps.flag(name.get()))
            .filter(spec -> spec.appliesTo(command))
            .flatMap(FlagSpec::requiresValue);
    if (requiresValue.isPresent()) {
      return requiresValue.get() && !looksLikeTarget(next);
    }
    if (beforeCommand) {
      // Nothing is known about this flag and the next token is where the
      // command lives. Leave it alone.
      return false;
    }
    // Unknown. Take the conservative reading: a token that looks like a
    // target or another flag is not swallowed as a value, because turning a
    // target into a flag's argument silently changes what gets built.
    return !isFlag(next) && !looksLikeTarget(next) && !next.equals("--");
  }

  /**
   * Whether a token looks like a Bazel target pattern.
   *
   * <p>Deliberately narrow. A bare word such as {@code foo} is a legal target pattern and is also a
   * legal flag value, and nothing about the token can tell them apart — which is exactly why
   * capabilities are consulted first and this is only the fallback.
   */
  public static boolean looksLikeTarget(String token) {
    return token.startsWith("//")
        || token.startsWith("@")
        || token.startsWith(":")
        || token.equals("...")
        || token.endsWith("/...")
        || token.startsWith("-//");
  }

  /** Bazel's exclusion spelling is a target pattern, not a short option. */
  private static boolean isNegativeTargetPattern(String token) {
    return token.startsWith("-//") || token.startsWith("-@");
  }
}
