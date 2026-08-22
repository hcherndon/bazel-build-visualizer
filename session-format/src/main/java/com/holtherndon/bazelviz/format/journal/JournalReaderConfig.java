package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;

/**
 * Tunables for {@link JournalReader}.
 *
 * @param maxPayloadBytes largest payload length the reader will honour. A
 *     declared length above this is {@link JournalScanStatus#LENGTH_OUT_OF_BOUNDS}
 *     and stops the scan, so a damaged length field can never make the reader
 *     allocate an arbitrary array (plan 21.3).
 * @param bufferBytes size of the single scratch buffer the reader streams
 *     through. It is independent of file size and of frame size — payload bytes
 *     are checksummed in {@code bufferBytes}-sized chunks — so scanning a 40 GiB
 *     journal costs the same working set as scanning a 40 KiB one.
 * @param readPayloads when false the reader verifies frames without ever
 *     materialising a payload array, which is what recovery needs: it must
 *     prove the bytes are intact, not read them. Frames then carry no payload
 *     and say so via {@link JournalFrame#hasPayload()}.
 */
public record JournalReaderConfig(int maxPayloadBytes, int bufferBytes, boolean readPayloads) {

    /** Default scratch buffer: large enough to amortise syscalls, small enough to ignore. */
    public static final int DEFAULT_BUFFER_BYTES = 64 * 1024;

    /** Smallest buffer that can hold a frame header, which is read in one piece. */
    public static final int MIN_BUFFER_BYTES = JournalFormat.FRAME_HEADER_BYTES;

    public JournalReaderConfig {
        if (maxPayloadBytes < 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be >= 0, got " + maxPayloadBytes);
        }
        if (maxPayloadBytes > JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("maxPayloadBytes " + maxPayloadBytes
                    + " exceeds the format ceiling " + JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES);
        }
        if (bufferBytes < MIN_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "bufferBytes must be at least " + MIN_BUFFER_BYTES + ", got " + bufferBytes);
        }
    }

    public static JournalReaderConfig defaults() {
        return new JournalReaderConfig(
                JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES, DEFAULT_BUFFER_BYTES, true);
    }

    /** Verification only: checksums every frame, allocates no payloads. */
    public static JournalReaderConfig verifyOnly() {
        return defaults().withReadPayloads(false);
    }

    public JournalReaderConfig withReadPayloads(boolean newReadPayloads) {
        return new JournalReaderConfig(maxPayloadBytes, bufferBytes, newReadPayloads);
    }

    public JournalReaderConfig withBufferBytes(int newBufferBytes) {
        return new JournalReaderConfig(maxPayloadBytes, newBufferBytes, readPayloads);
    }

    public JournalReaderConfig withMaxPayloadBytes(int newMaxPayloadBytes) {
        return new JournalReaderConfig(newMaxPayloadBytes, bufferBytes, readPayloads);
    }
}
