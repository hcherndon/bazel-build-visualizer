package com.holtherndon.bazelviz.runner.exec;

import java.io.IOException;

/**
 * The selected Bazel could not be identified, so nothing may be launched with it.
 *
 * <p>Carries what the user entered as well as the reason, because the two are usually both needed
 * to fix it: "bazel" and "no such executable on this machine" together say something that neither
 * says alone.
 */
public class ExecutableNotUsableException extends IOException {

  private static final long serialVersionUID = 1L;

  private final String entered;

  public ExecutableNotUsableException(String entered, String reason) {
    super("cannot use '" + entered + "' as a Bazel executable: " + reason);
    this.entered = entered;
  }

  /** Exactly what the user or the settings supplied. */
  public String entered() {
    return entered;
  }
}
