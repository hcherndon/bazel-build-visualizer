package com.holtherndon.bazelviz.runner.files;

import java.util.Arrays;
import java.util.Objects;

/** A bounded immutable read result and the strong version of those bytes. */
public final class FileContents {

  private final ExecutionPath path;
  private final byte[] bytes;
  private final FileVersion version;

  public FileContents(ExecutionPath path, byte[] bytes, FileVersion version) {
    this.path = Objects.requireNonNull(path, "path");
    this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    this.version = Objects.requireNonNull(version, "version");
    if (version.bytes() != bytes.length) {
      throw new IllegalArgumentException(
          "file version byte count does not match the returned contents");
    }
  }

  public ExecutionPath path() {
    return path;
  }

  /** Returns a defensive copy so an editor cannot mutate its conflict baseline. */
  public byte[] bytes() {
    return bytes.clone();
  }

  public FileVersion version() {
    return version;
  }

  @Override
  public boolean equals(Object candidate) {
    return candidate instanceof FileContents other
        && path.equals(other.path)
        && version.equals(other.version)
        && Arrays.equals(bytes, other.bytes);
  }

  @Override
  public int hashCode() {
    return 31 * Objects.hash(path, version) + Arrays.hashCode(bytes);
  }
}
