package com.holtherndon.bazelviz.app.cli;

/**
 * Process exit codes, chosen so a script can tell the three interesting outcomes apart without
 * parsing any output.
 *
 * <p>The distinction that matters most is {@link #PARTIAL} versus {@link #FAILED}. A truncated or
 * corrupt BEP file is not an error: the whole point of Phase 1 is that such a file still imports
 * everything it honestly can, and the session it produces is real and usable. Reporting that as a
 * failure would tell a script to throw away a good session; reporting it as success would hide the
 * fact that data is missing. So it gets a code of its own.
 *
 * <p>These values are part of the tool's contract and are documented in {@code --help}. They must
 * not be renumbered.
 */
public enum ExitCode {

  /** Everything asked for was done, and no source was damaged. */
  OK(0),

  /**
   * The command did its work, but the source was truncated or corrupt. Events before the damage
   * were imported, the session exists and can be opened, and a diagnostic carries the exact byte
   * offset.
   */
  PARTIAL(1),

  /**
   * The command line was wrong: an unknown option, a missing argument, or a path that is not there.
   * Nothing was attempted.
   */
  USAGE(2),

  /**
   * The command failed outright: the file is not a BEP capture this build can read, the disk
   * refused, the session could not be opened. There is no usable result.
   */
  FAILED(3),

  /**
   * The operator interrupted the run. The session was left on a record boundary with a checkpoint
   * and can be continued with {@code --resume}; it is unfinished, not corrupt.
   */
  CANCELLED(4);

  private final int code;

  ExitCode(int code) {
    this.code = code;
  }

  /** The number handed to {@code System.exit}. */
  public int code() {
    return code;
  }
}
