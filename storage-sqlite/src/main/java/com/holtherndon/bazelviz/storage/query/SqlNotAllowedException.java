package com.holtherndon.bazelviz.storage.query;

import java.util.Objects;

/**
 * The submitted text is not a single read-only statement, so it was never sent to SQLite.
 *
 * <p>Deliberately not a {@code SQLException}: nothing was executed, and the only thing a caller can
 * do is show {@link #getMessage()} beside the text the user typed. {@link #submitted()} carries
 * that text back so a panel can point at it.
 */
public final class SqlNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String submitted;

  public SqlNotAllowedException(String submitted, String reason) {
    super(reason);
    this.submitted = Objects.requireNonNull(submitted, "submitted");
  }

  /** Exactly what the user asked to run. */
  public String submitted() {
    return submitted;
  }
}
