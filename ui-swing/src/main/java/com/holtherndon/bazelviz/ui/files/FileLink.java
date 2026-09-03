package com.holtherndon.bazelviz.ui.files;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * A file-valued inspector field that can be opened without doing path work on the Swing event
 * thread.
 *
 * <p>The stored value stays a string because BEP test outputs are URIs while action outputs are
 * workspace-relative paths. {@link WorkspaceFileResolver} turns either form into a logical path on
 * the selected execution filesystem only after the user asks to open it.
 */
public record FileLink(String title, String location, Base base, OptionalInt line) {

  /** How {@link #location} is interpreted. */
  public enum Base {
    /** A {@code file:} URI on the execution host recorded by Bazel. */
    FILE_URI,
    /** A path relative to the invocation's recorded Bazel workspace. */
    WORKSPACE
  }

  public FileLink {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(line, "line");
    if (title.isBlank() || location.isBlank()) {
      throw new IllegalArgumentException("an openable file needs a title and location");
    }
    if (line.isPresent() && line.getAsInt() < 1) {
      throw new IllegalArgumentException("an openable file line must be positive");
    }
  }

  public FileLink(String title, String location, Base base) {
    this(title, location, base, OptionalInt.empty());
  }

  /** A test output URI. Test outputs are evidence and open read-only. */
  public static FileLink testLog(String title, String uri) {
    return new FileLink(title, uri, Base.FILE_URI, OptionalInt.empty());
  }

  /** An action output path. Generated outputs open read-only. */
  public static FileLink actionOutput(String path) {
    return new FileLink("Action output", path, Base.WORKSPACE, OptionalInt.empty());
  }
}
