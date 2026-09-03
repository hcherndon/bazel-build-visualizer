package com.holtherndon.bazelviz.capture.file.detect;

/**
 * What a candidate capture input turned out to be (plan 5.2: detect the format by content, not only
 * by extension).
 *
 * <p>{@link #UNKNOWN} is a real verdict with a stated reason, not a fallback bucket. Guessing
 * between two plausible formats would send a file to a parser that cannot read it and produce a
 * confusing failure deep inside an import instead of a clear one at the door.
 */
public enum DetectedFormat {

  /** Length-delimited {@code BuildEvent} messages, Bazel's {@code --build_event_binary_file}. */
  BEP_BINARY,

  /** Protobuf-JSON {@code BuildEvent} objects, Bazel's {@code --build_event_json_file}. */
  BEP_JSON,

  /** A managed session directory written by this application. */
  MANAGED_SESSION_DIR,

  /** Not recognized, or recognized as more than one thing. The reason says which. */
  UNKNOWN;

  /** True when a parser can be selected from this verdict. */
  public boolean isKnown() {
    return this != UNKNOWN;
  }
}
