package com.holtherndon.bazelviz.format.journal;

import java.io.IOException;

/**
 * A checkpoint file could not be believed: it is not JSON, is missing a field,
 * or states something impossible such as more events normalized than frames
 * written. It is a distinct type because the caller's response is specific —
 * fall back to the previous checkpoint, or rescan the journal from its start —
 * and must never be confused with the disk failing.
 */
public class CheckpointFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public CheckpointFormatException(String message) {
        super(message);
    }

    public CheckpointFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
