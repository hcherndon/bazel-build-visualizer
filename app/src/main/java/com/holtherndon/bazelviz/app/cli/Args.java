package com.holtherndon.bazelviz.app.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * The hand-rolled argument parser. Deliberately not a library: the whole grammar is {@code --flag},
 * {@code --option VALUE}, {@code --option=VALUE}, {@code --} and positionals, and a dependency that
 * has to be reviewed, licensed and locked is a poor trade for sixty lines.
 *
 * <p>Every option a subcommand accepts is declared up front, so an unrecognized one is refused with
 * a suggestion rather than silently ignored — silently ignoring {@code --quite} would make the tool
 * lie about what it was told to do.
 */
final class Args {

  private final String command;
  private final Set<String> flagNames;
  private final Set<String> optionNames;
  private final Set<String> present = new LinkedHashSet<>();
  private final Map<String, String> values = new LinkedHashMap<>();
  private final List<String> positionals = new ArrayList<>();

  private Args(String command, Set<String> flagNames, Set<String> optionNames) {
    this.command = command;
    this.flagNames = flagNames;
    this.optionNames = optionNames;
  }

  /**
   * @param command the subcommand these tokens belong to, for messages
   * @param tokens the arguments after the subcommand name
   * @param flagNames boolean options, without the leading dashes
   * @param optionNames options that take a value, without the leading dashes
   */
  static Args parse(
      String command, List<String> tokens, Set<String> flagNames, Set<String> optionNames)
      throws CliUsageException {
    Args args = new Args(command, flagNames, optionNames);
    boolean endOfOptions = false;
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if (endOfOptions || token.equals("-") || !token.startsWith("-")) {
        args.positionals.add(token);
        continue;
      }
      if (token.equals("--")) {
        endOfOptions = true;
        continue;
      }
      if (!token.startsWith("--")) {
        throw new CliUsageException(
            command,
            "unknown option '"
                + token
                + "'; every option here is spelled with two dashes, so try '-"
                + token
                + "'");
      }
      String body = token.substring(2);
      int equals = body.indexOf('=');
      String name = equals < 0 ? body : body.substring(0, equals);
      String inline = equals < 0 ? null : body.substring(equals + 1);
      if (name.isEmpty()) {
        throw new CliUsageException(command, "'" + token + "' is not an option name");
      }
      if (flagNames.contains(name)) {
        if (inline != null) {
          throw new CliUsageException(
              command, "option '--" + name + "' is a switch and does not take a value");
        }
        args.require(name);
      } else if (optionNames.contains(name)) {
        String value = inline;
        if (value == null) {
          if (i + 1 >= tokens.size()) {
            throw new CliUsageException(command, "option '--" + name + "' requires a value");
          }
          String next = tokens.get(i + 1);
          if (next.startsWith("--")) {
            throw new CliUsageException(
                command,
                "option '--" + name + "' requires a value, but was followed by '" + next + "'");
          }
          value = next;
          i++;
        }
        args.require(name);
        args.values.put(name, value);
      } else {
        throw new CliUsageException(command, unknownOption(name, flagNames, optionNames));
      }
    }
    return args;
  }

  private void require(String name) throws CliUsageException {
    if (!present.add(name)) {
      throw new CliUsageException(command, "option '--" + name + "' was given more than once");
    }
  }

  boolean has(String flag) {
    return present.contains(flag);
  }

  Optional<String> value(String option) {
    return Optional.ofNullable(values.get(option));
  }

  List<String> positionals() {
    return List.copyOf(positionals);
  }

  /** A value option parsed as a positive count, absent when not given. */
  OptionalLong positiveLong(String option) throws CliUsageException {
    String text = values.get(option);
    if (text == null) {
      return OptionalLong.empty();
    }
    long parsed;
    try {
      parsed = Long.parseLong(text.replace("_", ""));
    } catch (NumberFormatException e) {
      throw new CliUsageException(
          command, "option '--" + option + "' expects a whole number, got '" + text + "'");
    }
    if (parsed < 1) {
      throw new CliUsageException(
          command, "option '--" + option + "' expects a number of at least 1, got " + parsed);
    }
    return OptionalLong.of(parsed);
  }

  /** A value option parsed as a non-negative row id, absent when not given. */
  OptionalLong nonNegativeLong(String option) throws CliUsageException {
    String text = values.get(option);
    if (text == null) {
      return OptionalLong.empty();
    }
    try {
      long parsed = Long.parseLong(text.replace("_", ""));
      if (parsed < 0) {
        throw new CliUsageException(
            command, "option '--" + option + "' expects a non-negative id, got " + parsed);
      }
      return OptionalLong.of(parsed);
    } catch (NumberFormatException e) {
      throw new CliUsageException(
          command, "option '--" + option + "' expects a whole number, got '" + text + "'");
    }
  }

  private static String unknownOption(String name, Set<String> flagNames, Set<String> optionNames) {
    Set<String> known = new TreeSet<>();
    known.addAll(flagNames);
    known.addAll(optionNames);
    String nearest = nearest(name, known);
    StringBuilder message = new StringBuilder("unknown option '--").append(name).append('\'');
    if (nearest != null) {
      message.append("; did you mean '--").append(nearest).append("'?");
    }
    return message.toString();
  }

  /**
   * The closest known option name within a small edit distance, or null. The threshold scales with
   * the length of what was typed so that a two-letter slip on a long name still suggests, while a
   * completely different word suggests nothing rather than something misleading.
   */
  private static String nearest(String typed, Set<String> known) {
    int budget = Math.max(1, Math.min(3, typed.length() / 3));
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : known) {
      int distance = editDistance(typed, candidate);
      if (distance <= budget && distance < bestDistance) {
        best = candidate;
        bestDistance = distance;
      }
    }
    return best;
  }

  private static int editDistance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
        current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
