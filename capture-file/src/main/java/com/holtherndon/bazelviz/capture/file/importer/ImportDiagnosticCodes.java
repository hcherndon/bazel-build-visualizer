package com.holtherndon.bazelviz.capture.file.importer;

/**
 * {@code import_diagnostics.code} values the file importer adds to the ones in {@link
 * com.holtherndon.bazelviz.storage.events.DiagnosticCodes}.
 *
 * <p>Spelled out as constants rather than derived from enum names: these strings are stored in
 * session databases and are read back by later builds, so they are part of the on-disk contract and
 * must not change because an enum was renamed.
 */
public final class ImportDiagnosticCodes {

  private ImportDiagnosticCodes() {}

  /** The source ends mid-record. Everything before the offset was imported. */
  public static final String SOURCE_TRUNCATED = "SOURCE_TRUNCATED";

  /**
   * The source contains a record whose framing contradicts itself, so no reliable next boundary
   * exists and reading stopped at the offset given.
   */
  public static final String SOURCE_CORRUPT = "SOURCE_CORRUPT";

  /** A JSON record exceeded the configured per-record limit and was not retained. */
  public static final String RECORD_TOO_LARGE = "RECORD_TOO_LARGE";

  /** A byte appeared between JSON records where only an object may start. */
  public static final String MALFORMED_TOP_LEVEL = "MALFORMED_TOP_LEVEL";

  /** The import was cancelled; the session is resumable from its checkpoint. */
  public static final String IMPORT_CANCELLED = "IMPORT_CANCELLED";

  /** The import was resumed from a checkpoint after an interruption. */
  public static final String IMPORT_RESUMED = "IMPORT_RESUMED";

  /** A referenced source changed size or modification time while being read. */
  public static final String SOURCE_CHANGED = "SOURCE_CHANGED";

  /** A preserved source no longer hashes to the digest recorded for it. */
  public static final String SOURCE_DIGEST_MISMATCH = "SOURCE_DIGEST_MISMATCH";

  /** A parser noticed something worth saying that is neither loss nor damage. */
  public static final String SOURCE_NOTE = "SOURCE_NOTE";

  /**
   * Repeated diagnostics of one code hit the configured cap; this row carries the true total so
   * nothing is lost by the cap, only repeated.
   */
  public static final String DIAGNOSTIC_SUMMARY = "DIAGNOSTIC_SUMMARY";
}
