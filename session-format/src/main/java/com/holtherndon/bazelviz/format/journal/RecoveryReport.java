package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.source.Completeness;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What recovery found and what it did about it.
 *
 * <p>This is written into {@code import_diagnostics} and shown to the user (plan 21.1 step 7).
 * Truncation is never a silent housekeeping step: if bytes were removed, this report says how many,
 * from where, and why.
 *
 * @param journalDirectory the {@code raw/} directory that was recovered
 * @param scans one entry per segment scanned, in segment order
 * @param framesVerified frames whose checksums were verified in this run; frames before the
 *     honoured checkpoint are not re-verified and not counted
 * @param lastValidPosition end of the last intact frame, and the position a normalizer replays up
 *     to. Empty when the journal holds no readable segment at all. A writer reopening the journal
 *     usually resumes exactly here, but not always: if the crash left a later segment file that
 *     never got its header, the writer continues at the start of that empty segment instead, which
 *     appends after this position just the same.
 * @param lastSequence sequence number of the last intact frame, empty when unknown (no frame
 *     verified and no checkpoint to inherit it from)
 * @param bytesTruncated invalid trailing bytes removed, zero when nothing was removed
 * @param status why the scan stopped
 * @param detail human-readable summary for the diagnostic record
 * @param checkpointHonoured whether the supplied checkpoint was believed and used as the scan's
 *     starting point
 * @param missingSegments segment indexes absent from an otherwise contiguous run — files that were
 *     deleted or never landed
 * @param orphanedSegments segments that exist after the segment where scanning stopped. They are
 *     left untouched: they may contain valid frames, and deleting data is never recovery's job.
 */
public record RecoveryReport(
    Path journalDirectory,
    List<SegmentScan> scans,
    long framesVerified,
    Optional<JournalPosition> lastValidPosition,
    OptionalLong lastSequence,
    long bytesTruncated,
    JournalScanStatus status,
    String detail,
    boolean checkpointHonoured,
    List<Integer> missingSegments,
    List<Integer> orphanedSegments) {

  public RecoveryReport {
    Objects.requireNonNull(journalDirectory, "journalDirectory");
    scans = List.copyOf(scans);
    Objects.requireNonNull(lastValidPosition, "lastValidPosition");
    Objects.requireNonNull(lastSequence, "lastSequence");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(detail, "detail");
    missingSegments = List.copyOf(missingSegments);
    orphanedSegments = List.copyOf(orphanedSegments);
    if (bytesTruncated < 0) {
      throw new IllegalArgumentException("bytesTruncated must be >= 0, got " + bytesTruncated);
    }
  }

  public int segmentsScanned() {
    return scans.size();
  }

  /** True when this run removed bytes from a segment. */
  public boolean truncationOccurred() {
    return bytesTruncated > 0;
  }

  /**
   * True when the journal needs nothing further: it ended cleanly, nothing was removed, and no
   * segment is missing or orphaned.
   */
  public boolean isClean() {
    return status.isClean()
        && bytesTruncated == 0
        && missingSegments.isEmpty()
        && orphanedSegments.isEmpty();
  }

  /** How complete the journal is as a capture source, for the manifest and the UI. */
  public Completeness completeness() {
    if (!missingSegments.isEmpty()) {
      // A hole in the middle is not a short tail: data from the middle of
      // the build is gone and no scan of the remainder can recover it.
      return Completeness.CORRUPT_PARTIAL;
    }
    return status.completeness();
  }
}
