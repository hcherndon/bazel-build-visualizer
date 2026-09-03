package com.holtherndon.bazelviz.enrich.execlog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * The file's first bytes match no execution-log format this build knows.
 *
 * <p>Carries the bytes it saw, because "not an execution log" is a claim the user is entitled to
 * check — the usual causes are pointing at the profile by mistake, or at a log Bazel never finished
 * writing.
 */
public final class UnknownExecLogFormatException extends IOException {

  private static final long serialVersionUID = 1L;

  private final transient Path file;

  public UnknownExecLogFormatException(Path file, byte[] head, int length) {
    super(describe(file, head, length));
    this.file = file;
  }

  private static String describe(Path file, byte[] head, int length) {
    if (length == 0) {
      return file
          + " is empty, so it is either a build in which every action was"
          + " cached or a log that was never written; which one cannot be told"
          + " from the file alone";
    }
    return file
        + " does not start like any execution log this build reads."
        + " Its first "
        + length
        + " bytes are "
        + HexFormat.of().withDelimiter(" ").formatHex(head, 0, length);
  }

  /** The file that could not be identified. */
  public Path file() {
    return file;
  }
}
