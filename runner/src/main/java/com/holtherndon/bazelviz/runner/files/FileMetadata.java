package com.holtherndon.bazelviz.runner.files;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Explicit filesystem metadata; unavailable and inaccessible are not missing. */
public record FileMetadata(
    ExecutionPath path,
    State state,
    Kind kind,
    OptionalLong bytes,
    OptionalLong modifiedMillis,
    Optional<String> detail) {

  public enum State {
    PRESENT,
    MISSING,
    INACCESSIBLE,
    UNAVAILABLE
  }

  public enum Kind {
    REGULAR_FILE,
    DIRECTORY,
    SYMBOLIC_LINK,
    OTHER,
    UNKNOWN
  }

  public FileMetadata {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(modifiedMillis, "modifiedMillis");
    Objects.requireNonNull(detail, "detail");
    if (bytes.isPresent() && bytes.getAsLong() < 0) {
      throw new IllegalArgumentException("file byte count cannot be negative");
    }
    if (state != State.PRESENT && kind != Kind.UNKNOWN) {
      throw new IllegalArgumentException("non-present metadata must have unknown kind");
    }
  }

  public static FileMetadata present(
      ExecutionPath path, Kind kind, OptionalLong bytes, long modifiedMillis) {
    return new FileMetadata(
        path, State.PRESENT, kind, bytes, OptionalLong.of(modifiedMillis), Optional.empty());
  }

  public static FileMetadata missing(ExecutionPath path) {
    return absent(path, State.MISSING, "the path does not exist");
  }

  public static FileMetadata inaccessible(ExecutionPath path, String detail) {
    return absent(path, State.INACCESSIBLE, detail);
  }

  public static FileMetadata unavailable(ExecutionPath path, String detail) {
    return absent(path, State.UNAVAILABLE, detail);
  }

  private static FileMetadata absent(ExecutionPath path, State state, String detail) {
    return new FileMetadata(
        path,
        state,
        Kind.UNKNOWN,
        OptionalLong.empty(),
        OptionalLong.empty(),
        Optional.of(Objects.requireNonNull(detail, "detail")));
  }

  public boolean isRegularFile() {
    return state == State.PRESENT && kind == Kind.REGULAR_FILE;
  }

  public boolean isDirectory() {
    return state == State.PRESENT && kind == Kind.DIRECTORY;
  }
}
