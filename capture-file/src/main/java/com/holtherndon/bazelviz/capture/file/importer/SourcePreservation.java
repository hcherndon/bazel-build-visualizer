package com.holtherndon.bazelviz.capture.file.importer;

/**
 * How the original capture file is preserved (Phase 1 exit criterion "the original source is
 * preserved").
 *
 * <p>Whichever mode is used, the session records the source's path, byte size and SHA-256 in {@code
 * capture_sources} and in the manifest before a single byte is decoded, and the raw journal holds
 * every payload verbatim. The choice below is only about whether the session also owns a copy of
 * the source bytes.
 */
public enum SourcePreservation {

  /**
   * Copy the source into {@code raw/imported-source.bep} and index the copy (plan 10.2 names this
   * file). The default.
   *
   * <p>The digest is computed <em>during</em> the copy, from the same byte stream that is written,
   * so the recorded SHA-256 is provably the digest of the file the importer then parsed. Afterwards
   * the session can re-hash its own copy and prove that what it indexed is what it read, with no
   * dependency on anything outside the session directory.
   *
   * <p>Failure mode: the session costs a second copy of the source on disk. If the original is
   * later moved or edited, the session is unaffected — it no longer refers to the original for
   * anything — but the recorded original path becomes a historical note rather than a working link.
   */
  COPY_INTO_SESSION,

  /**
   * Reserved persisted value for sessions created by an earlier build; new imports refuse it.
   *
   * <p>A mutable file can change between a hashing pass and a parsing pass while retaining its size
   * and modification time. That makes it impossible to prove that the recorded digest describes the
   * parsed bytes. {@link BepImporter} therefore refuses this mode before creating or recovering a
   * session. The enum value remains so an old checkpoint is decoded and refused with an explicit
   * remedy rather than reported as an unknown future format.
   */
  REFERENCE_ORIGINAL
}
