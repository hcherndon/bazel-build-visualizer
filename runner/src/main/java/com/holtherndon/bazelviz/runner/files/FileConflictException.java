package com.holtherndon.bazelviz.runner.files;

import java.io.IOException;

/** A conditional save refused to overwrite bytes newer than its baseline. */
public final class FileConflictException extends IOException {

  private static final long serialVersionUID = 1L;

  public FileConflictException(String message) {
    super(message);
  }
}
