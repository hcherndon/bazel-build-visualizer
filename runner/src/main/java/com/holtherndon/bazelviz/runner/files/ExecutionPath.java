package com.holtherndon.bazelviz.runner.files;

import java.util.Objects;

/**
 * An opaque path on one execution host.
 *
 * <p>The value is deliberately a string rather than a {@code java.nio.file.Path}: a Linux path
 * received from an SSH workspace must never be interpreted using the desktop machine's filesystem
 * rules. Only the owning {@link ExecutionFileSystem} may resolve or canonicalize it.
 */
public record ExecutionPath(String executionId, String value) {

  public ExecutionPath {
    Objects.requireNonNull(executionId, "executionId");
    Objects.requireNonNull(value, "value");
    if (executionId.isBlank()) {
      throw new IllegalArgumentException("an execution path needs an execution id");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("an execution path cannot be blank");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("an execution path cannot contain NUL");
    }
  }

  /** Filename-shaped display text without applying local filesystem rules. */
  public String fileName() {
    String withoutTrailing = value;
    while (withoutTrailing.length() > 1 && withoutTrailing.endsWith("/")) {
      withoutTrailing = withoutTrailing.substring(0, withoutTrailing.length() - 1);
    }
    int slash = Math.max(withoutTrailing.lastIndexOf('/'), withoutTrailing.lastIndexOf('\\'));
    return slash < 0 ? withoutTrailing : withoutTrailing.substring(slash + 1);
  }

  @Override
  public String toString() {
    return value;
  }
}
