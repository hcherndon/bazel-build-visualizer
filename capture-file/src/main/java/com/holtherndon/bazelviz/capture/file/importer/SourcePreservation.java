package com.holtherndon.bazelviz.capture.file.importer;

/**
 * How the original capture file is preserved (Phase 1 exit criterion "the
 * original source is preserved").
 *
 * <p>Whichever mode is used, the session records the source's path, byte size
 * and SHA-256 in {@code capture_sources} and in the manifest before a single
 * byte is decoded, and the raw journal holds every payload verbatim. The choice
 * below is only about whether the session also owns a copy of the source bytes.
 */
public enum SourcePreservation {

    /**
     * Copy the source into {@code raw/imported-source.bep} and index the copy
     * (plan 10.2 names this file). The default.
     *
     * <p>The digest is computed <em>during</em> the copy, from the same byte
     * stream that is written, so the recorded SHA-256 is provably the digest of
     * the file the importer then parsed. Afterwards the session can re-hash its
     * own copy and prove that what it indexed is what it read, with no
     * dependency on anything outside the session directory.
     *
     * <p>Failure mode: the session costs a second copy of the source on disk.
     * If the original is later moved or edited, the session is unaffected — it
     * no longer refers to the original for anything — but the recorded original
     * path becomes a historical note rather than a working link.
     */
    COPY_INTO_SESSION,

    /**
     * Leave the source where it is and index it in place, recording a verified
     * reference: absolute path, byte size, last-modified time and SHA-256.
     *
     * <p>The digest is taken in a streaming pass before parsing, and size and
     * modification time are re-checked after parsing; a change between the two
     * is recorded as a diagnostic and the source's completeness becomes
     * {@link com.holtherndon.bazelviz.core.source.Completeness#UNKNOWN}, because
     * an import that read a file while it changed cannot honestly claim to have
     * read all of it.
     *
     * <p>Failure mode: if the original moves, the reference dangles — the
     * session keeps every event and every raw payload in its journal, so it
     * stays fully inspectable, but it can no longer re-verify the digest or
     * re-import from source. If the original is edited in place, the next
     * verification reports a digest mismatch: the session cannot repair itself,
     * but it says so rather than silently indexing different bytes.
     */
    REFERENCE_ORIGINAL
}
