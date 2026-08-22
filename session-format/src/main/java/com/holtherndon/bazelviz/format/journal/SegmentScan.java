package com.holtherndon.bazelviz.format.journal;

import java.util.OptionalLong;

/**
 * The outcome of scanning one journal segment: how far the intact region
 * reaches, why it ends there, and what is left over.
 *
 * @param segmentIndex the segment scanned
 * @param startOffset offset the scan began at
 * @param endOffset offset of the first byte that is not part of an intact
 *     frame — equivalently, the end of the last valid frame. Everything in
 *     {@code [startOffset, endOffset)} was verified.
 * @param fileSize size of the segment file when the scan ran
 * @param framesRead frames verified during this scan
 * @param lastSequence sequence number of the last verified frame, empty when
 *     the scan verified none (not zero — zero is a legal sequence)
 * @param status why the scan stopped
 * @param detail human-readable explanation, suitable for
 *     {@code import_diagnostics.message}
 */
public record SegmentScan(
        int segmentIndex,
        long startOffset,
        long endOffset,
        long fileSize,
        long framesRead,
        OptionalLong lastSequence,
        JournalScanStatus status,
        String detail) {

    /** Bytes past the last intact frame. Zero when the segment ends cleanly. */
    public long trailingBytes() {
        return fileSize - endOffset;
    }

    /** Position of the first byte after the intact region. */
    public JournalPosition endPosition() {
        return new JournalPosition(segmentIndex, endOffset);
    }

    /** True when trailing bytes exist and are known to be garbage. */
    public boolean hasTruncatableTail() {
        return status.isTruncatable() && trailingBytes() > 0;
    }
}
