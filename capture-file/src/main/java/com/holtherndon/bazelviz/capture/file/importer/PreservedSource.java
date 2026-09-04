package com.holtherndon.bazelviz.capture.file.importer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The record of the original capture file that the session keeps, written before a single byte is
 * decoded (Phase 1 exit criterion "the original source is preserved").
 *
 * <p>The digest is the proof obligation: {@link #sha256} is computed while copying the source and
 * is the digest of the exact session-owned byte stream the importer went on to parse. The session
 * can therefore demonstrate that what it indexed is what it read.
 *
 * @param preservation which mode produced this record
 * @param originalPath absolute path the file was read from
 * @param storedPath the copy inside the session, present only for {@link
 *     SourcePreservation#COPY_INTO_SESSION}
 * @param sha256 lower-case hex digest of the source bytes
 * @param byteSize size of the source in bytes
 */
public record PreservedSource(
    SourcePreservation preservation,
    Path originalPath,
    Optional<Path> storedPath,
    String sha256,
    long byteSize) {

  public PreservedSource {
    Objects.requireNonNull(preservation, "preservation");
    Objects.requireNonNull(originalPath, "originalPath");
    Objects.requireNonNull(storedPath, "storedPath");
    Objects.requireNonNull(sha256, "sha256");
    if (byteSize < 0) {
      throw new IllegalArgumentException("byteSize must be >= 0, got " + byteSize);
    }
    if (preservation == SourcePreservation.COPY_INTO_SESSION && storedPath.isEmpty()) {
      throw new IllegalArgumentException("a copied source must record where it was copied to");
    }
  }

  /** The file the importer actually parses: the session's copy, or the original. */
  public Path effectivePath() {
    return storedPath.orElse(originalPath);
  }
}
