package com.holtherndon.bazelviz.runner.files;

import java.util.Objects;

/** Strong identity of bytes returned by a bounded file read. */
public record FileVersion(long bytes, long modifiedMillis, String sha256) {

  public FileVersion {
    if (bytes < 0) {
      throw new IllegalArgumentException("file byte count cannot be negative");
    }
    Objects.requireNonNull(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("SHA-256 must be 64 lowercase hexadecimal digits");
    }
  }
}
