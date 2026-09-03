package com.holtherndon.bazelviz.runner.ssh;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** The one audited boundary where argv becomes a remote POSIX-shell command. */
final class PosixShell {

  private PosixShell() {}

  static String quote(String value) {
    Objects.requireNonNull(value, "value");
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("a shell value cannot contain NUL");
    }
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  static String argv(List<String> values) {
    Objects.requireNonNull(values, "values");
    return values.stream().map(PosixShell::quote).collect(Collectors.joining(" "));
  }
}
