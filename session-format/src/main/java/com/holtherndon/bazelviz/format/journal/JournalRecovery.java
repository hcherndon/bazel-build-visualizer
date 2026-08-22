package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings a journal back to a known-good state after an unclean shutdown
 * (plan 21.1 steps 2 and 3).
 *
 * <p>The contract is narrow on purpose. Recovery scans forward from the last
 * believable checkpoint, verifies every frame's checksum, and finds the last
 * intact frame. It then removes <em>only</em> the trailing bytes that follow
 * that frame, and only when they are provably not a frame this build could
 * ever read. It never rewrites a frame, never reorders segments, never deletes
 * a segment file, and never touches bytes that verified.
 *
 * <p>Only the <em>last</em> segment's tail is ever trimmed. Damage found in an
 * earlier segment is reported and left exactly where it is: bytes after it are
 * not a trailing tail — later segments exist and may be full of valid frames —
 * so removing them would destroy real data to tidy up a diagnostic.
 *
 * <p>Truncation is a deliberate, logged act: every removal is logged at WARN
 * with the segment, the offset and the byte count, and returned in the
 * {@link RecoveryReport} so it reaches {@code import_diagnostics} and the user.
 *
 * <p>Recovery is idempotent. Running it again on a journal it has already
 * trimmed finds a clean end, removes nothing, and reports the same last valid
 * position.
 */
public final class JournalRecovery {

    private static final Logger log = LoggerFactory.getLogger(JournalRecovery.class);

    private JournalRecovery() {}

    /** Scans and repairs with default settings and no checkpoint. */
    public static RecoveryReport recover(Path journalDirectory) throws IOException {
        return recover(journalDirectory, Optional.empty(), JournalReaderConfig.verifyOnly());
    }

    /**
     * Scans from the checkpoint and truncates invalid trailing bytes.
     *
     * @param checkpoint where to resume verification, or empty to verify the
     *     journal from its first segment
     */
    public static RecoveryReport recover(
            Path journalDirectory, Optional<ImportCheckpoint> checkpoint, JournalReaderConfig config)
            throws IOException {
        return run(journalDirectory, checkpoint, config, true);
    }

    /**
     * Scans without changing anything on disk. Use this to report a journal's
     * condition — for instance before asking the user whether to repair it.
     */
    public static RecoveryReport inspect(
            Path journalDirectory, Optional<ImportCheckpoint> checkpoint, JournalReaderConfig config)
            throws IOException {
        return run(journalDirectory, checkpoint, config, false);
    }

    private static RecoveryReport run(
            Path journalDirectory,
            Optional<ImportCheckpoint> checkpoint,
            JournalReaderConfig config,
            boolean truncate)
            throws IOException {
        Objects.requireNonNull(journalDirectory, "journalDirectory");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(config, "config");

        List<Integer> present = JournalSegments.listSegmentIndexes(journalDirectory);
        List<Integer> missing = JournalSegments.missingSegmentIndexes(present);
        if (present.isEmpty()) {
            return new RecoveryReport(
                    journalDirectory,
                    List.of(),
                    0L,
                    Optional.empty(),
                    OptionalLong.empty(),
                    0L,
                    JournalScanStatus.OK,
                    "no journal segments in " + journalDirectory,
                    false,
                    missing,
                    List.of());
        }

        StartPoint start = chooseStart(journalDirectory, present, checkpoint);

        List<SegmentScan> scans = new ArrayList<>();
        long framesVerified = 0L;
        long bytesTruncated = 0L;
        OptionalLong lastSequence = start.inheritedSequence();
        Optional<JournalPosition> lastValidPosition = Optional.empty();
        JournalScanStatus status = JournalScanStatus.OK;
        String detail = "journal ends cleanly";
        List<Integer> orphaned = List.of();

        List<Integer> toScan = present.stream().filter(index -> index >= start.segmentIndex()).toList();
        for (int i = 0; i < toScan.size(); i++) {
            int segmentIndex = toScan.get(i);
            Path file = JournalSegments.segmentFile(journalDirectory, segmentIndex);
            long fromOffset = segmentIndex == start.segmentIndex()
                    ? start.byteOffset()
                    : JournalFormat.SEGMENT_HEADER_BYTES;

            long size = Files.size(file);
            if (size < JournalFormat.SEGMENT_HEADER_BYTES) {
                // The segment header itself never landed. There is no frame in
                // here to save, and nothing before it to protect.
                boolean isLast = i == toScan.size() - 1;
                status = JournalScanStatus.TRUNCATED_TAIL;
                detail = "segment " + segmentIndex + " has only " + size + " of "
                        + JournalFormat.SEGMENT_HEADER_BYTES + " header bytes";
                if (isLast && truncate && size > 0) {
                    truncateTo(file, 0L, size);
                    bytesTruncated += size;
                }
                orphaned = toScan.subList(Math.min(i + 1, toScan.size()), toScan.size());
                break;
            }

            SegmentScan scan;
            try (JournalReader reader = JournalReader.open(file, fromOffset, config)) {
                scan = reader.verify();
            } catch (IOException unopenable) {
                // The segment header is present in full but is not one this
                // build can read — wrong magic, or a format version from a
                // newer application. Neither is a torn tail, so nothing is
                // truncated: a future build may read this file perfectly.
                status = JournalScanStatus.BAD_MAGIC;
                detail = "segment " + segmentIndex + " could not be opened: " + unopenable.getMessage();
                log.warn("segment {} in {} could not be opened and is left untouched: {}",
                        segmentIndex, journalDirectory, unopenable.getMessage());
                orphaned = toScan.subList(Math.min(i + 1, toScan.size()), toScan.size());
                break;
            }
            scans.add(scan);
            framesVerified += scan.framesRead();
            if (scan.lastSequence().isPresent()) {
                lastSequence = scan.lastSequence();
            }
            lastValidPosition = Optional.of(scan.endPosition());

            if (scan.status().isClean()) {
                continue;
            }

            status = scan.status();
            detail = "segment " + segmentIndex + " at byte " + scan.endOffset() + ": " + scan.detail();
            boolean isLastSegment = i == toScan.size() - 1;
            if (!scan.hasTruncatableTail()) {
                // Either nothing follows the last good frame, or what follows is
                // intact and merely uninterpretable. Both mean: leave it alone.
                log.warn("segment {} stops at byte {} with {} byte(s) remaining, left in place: {}",
                        segmentIndex, scan.endOffset(), scan.trailingBytes(), scan.detail());
            } else if (!isLastSegment) {
                // Damage in the middle of the journal. The bytes after it are not
                // a "trailing tail" — later segments exist and may be full of
                // valid frames — so removing them would destroy real data. Report
                // it and let the caller decide.
                log.warn("segment {} is damaged at byte {} but is not the last segment; "
                                + "nothing is truncated because {} byte(s) here and {} later segment(s) "
                                + "may still hold valid frames",
                        segmentIndex, scan.endOffset(), scan.trailingBytes(),
                        toScan.size() - i - 1);
            } else if (truncate) {
                truncateTo(file, scan.endOffset(), scan.trailingBytes());
                bytesTruncated += scan.trailingBytes();
            }
            orphaned = toScan.subList(Math.min(i + 1, toScan.size()), toScan.size());
            break;
        }

        if (!orphaned.isEmpty()) {
            log.warn("journal {} has {} segment(s) after the damaged one; they are left untouched "
                            + "because they may hold valid frames: {}",
                    journalDirectory, orphaned.size(), orphaned);
        }
        if (!missing.isEmpty()) {
            log.warn("journal {} is missing segment(s) {}; the capture has a hole, not a short tail",
                    journalDirectory, missing);
        }

        return new RecoveryReport(
                journalDirectory,
                scans,
                framesVerified,
                lastValidPosition,
                lastSequence,
                bytesTruncated,
                status,
                detail,
                start.checkpointHonoured(),
                missing,
                List.copyOf(orphaned));
    }

    /**
     * Where to begin verifying. A checkpoint is believed only when the segment
     * it names exists and is at least as long as the offset it names. A
     * checkpoint that points past the end of its segment is not corrupt — the
     * journal does not fsync per event, so it is the expected result of a crash
     * — but it cannot be used as a starting point, because the bytes between
     * the file's end and the checkpoint were never written. In that case the
     * whole segment is re-verified from its first frame.
     */
    private static StartPoint chooseStart(
            Path journalDirectory, List<Integer> present, Optional<ImportCheckpoint> checkpoint)
            throws IOException {
        int firstSegment = present.get(0);
        if (checkpoint.isEmpty()) {
            return new StartPoint(
                    firstSegment, JournalFormat.SEGMENT_HEADER_BYTES, false, OptionalLong.empty());
        }
        ImportCheckpoint point = checkpoint.get();
        if (!present.contains(point.segmentIndex())) {
            log.warn("checkpoint names segment {} which is not present in {}; "
                            + "verifying from segment {} instead",
                    point.segmentIndex(), journalDirectory, firstSegment);
            return new StartPoint(
                    firstSegment, JournalFormat.SEGMENT_HEADER_BYTES, false, OptionalLong.empty());
        }
        long size = Files.size(JournalSegments.segmentFile(journalDirectory, point.segmentIndex()));
        if (point.segmentOffset() > size) {
            log.info("checkpoint is ahead of segment {} ({} > {} bytes on disk); "
                            + "re-verifying that segment from its first frame",
                    point.segmentIndex(), point.segmentOffset(), size);
            return new StartPoint(
                    point.segmentIndex(), JournalFormat.SEGMENT_HEADER_BYTES, false, OptionalLong.empty());
        }
        return new StartPoint(
                point.segmentIndex(), point.segmentOffset(), true, point.lastSequence());
    }

    private static void truncateTo(Path file, long validBytes, long removedBytes) throws IOException {
        log.warn("truncating {} to {} bytes, discarding {} invalid trailing byte(s)",
                file, validBytes, removedBytes);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(validBytes);
            // Forced here so the repair itself cannot be lost to a second crash,
            // which would otherwise leave recovery re-finding the same damage.
            channel.force(true);
        }
    }

    /**
     * @param inheritedSequence the last sequence known from a believed
     *     checkpoint, carried forward when the scan itself verifies no frames
     */
    private record StartPoint(
            int segmentIndex, long byteOffset, boolean checkpointHonoured, OptionalLong inheritedSequence) {}
}
