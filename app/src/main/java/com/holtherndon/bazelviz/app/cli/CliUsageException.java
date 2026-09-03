package com.holtherndon.bazelviz.app.cli;

import java.util.Optional;

/**
 * The command line could not be understood.
 *
 * <p>Carries the subcommand it happened under so the handler can point at the right {@code --help}.
 * A stack trace is never printed for one of these: a typo is a conversation with the user, not a
 * defect report.
 */
final class CliUsageException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient String command;

  CliUsageException(String message) {
    this(null, message);
  }

  CliUsageException(String command, String message) {
    super(message);
    this.command = command;
  }

  /** The subcommand in force, empty for a top-level error. */
  Optional<String> command() {
    return Optional.ofNullable(command);
  }

  /** The line to print after the message: how to get help for this context. */
  String helpHint() {
    return command == null
        ? "run 'bbv --help' for usage"
        : "run 'bbv " + command + " --help' for usage";
  }
}
