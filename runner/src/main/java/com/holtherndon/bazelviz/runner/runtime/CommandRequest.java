package com.holtherndon.bazelviz.runner.runtime;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A transport-neutral command request. Paths name the executor's machine. */
public record CommandRequest(
    List<String> argv,
    Optional<String> workingDirectory,
    Map<String, Optional<String>> environmentOverrides,
    RuntimeEnvironment environment,
    boolean forceTty) {

  public CommandRequest {
    argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("argv must not be empty");
    }
    for (String argument : argv) {
      Objects.requireNonNull(argument, "argv contains null");
      if (argument.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("argv cannot contain NUL");
      }
    }
    workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    environmentOverrides =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(
                Objects.requireNonNull(environmentOverrides, "environmentOverrides")));
    environmentOverrides.forEach(
        (name, value) -> {
          requireEnvironmentName(name);
          Objects.requireNonNull(value, "environment override for " + name);
          value.ifPresent(
              present -> {
                if (present.indexOf('\0') >= 0) {
                  throw new IllegalArgumentException(
                      "environment value cannot contain NUL: " + name);
                }
              });
        });
    Objects.requireNonNull(environment, "environment");
  }

  /** A normal non-TTY command in {@code workingDirectory}. */
  public static CommandRequest of(List<String> argv, Path workingDirectory) {
    return new CommandRequest(
        argv,
        Optional.ofNullable(workingDirectory).map(Path::toString),
        Map.of(),
        RuntimeEnvironment.INHERIT_ALL,
        false);
  }

  /** A normal non-TTY command whose working directory is on the executor. */
  public static CommandRequest of(List<String> argv, String workingDirectory) {
    return new CommandRequest(
        argv,
        Optional.ofNullable(workingDirectory),
        Map.of(),
        RuntimeEnvironment.INHERIT_ALL,
        false);
  }

  public CommandRequest withEnvironment(
      Map<String, Optional<String>> overrides, RuntimeEnvironment policy) {
    return new CommandRequest(argv, workingDirectory, overrides, policy, forceTty);
  }

  public CommandRequest withForceTty(boolean value) {
    return new CommandRequest(argv, workingDirectory, environmentOverrides, environment, value);
  }

  private static void requireEnvironmentName(String name) {
    Objects.requireNonNull(name, "environment name");
    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("invalid environment name: " + name);
    }
  }
}
