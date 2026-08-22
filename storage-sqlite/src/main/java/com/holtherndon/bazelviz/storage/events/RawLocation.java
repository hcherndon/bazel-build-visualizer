package com.holtherndon.bazelviz.storage.events;

/**
 * Where an event's verbatim payload lives in the raw journal.
 *
 * <p>Every normalized row can be traced back to the exact bytes it came from
 * (ADR-004), which is what lets the Events view show a raw protobuf inspector
 * and what lets a later build re-derive richer data without re-running the
 * build.
 *
 * @param segment journal segment index, matching {@code bes-%06d.journal}
 * @param offset byte offset of the frame within that segment, from the start
 *     of the frame header
 * @param length payload length in bytes, excluding frame header and CRC
 */
public record RawLocation(int segment, long offset, int length) {

    public RawLocation {
        if (segment < 0) {
            throw new IllegalArgumentException("segment must not be negative: " + segment);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length);
        }
    }
}
