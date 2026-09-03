package com.holtherndon.bazelviz.capture.file.importer;

/**
 * Which stage of an import is running, so a progress bar can label itself instead of showing an
 * unexplained percentage.
 *
 * <p>The stages are separate because their costs are not comparable: hashing and copying a source
 * is one linear pass over bytes, reading is another, replaying a journal after a crash touches only
 * the backlog, and finalizing is index construction whose duration has nothing to do with byte
 * counts. A single blended percentage across all four would be a fiction.
 */
public enum ImportPhase {

  /** Deciding what the file is, by content (plan 5.2). */
  DETECTING,

  /** Hashing the source, and copying it into the session when configured to. */
  PRESERVING,

  /**
   * Replaying journal frames that were written but not normalized before an interruption. Only ever
   * reported by a resume.
   */
  REPLAYING_JOURNAL,

  /** Reading the source: journal first, then normalize (ADR-004). */
  READING,

  /** Building indexes, resolving announced children, writing the manifest. */
  FINALIZING
}
