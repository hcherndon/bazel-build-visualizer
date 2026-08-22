package com.holtherndon.bazelviz.capture.live;

/**
 * Diagnostic codes a live capture can record, beyond the shared ones in
 * {@code storage-sqlite}'s {@code DiagnosticCodes}.
 *
 * <p>Codes are stable strings stored in {@code import_diagnostics.code}. They
 * are what a user filters on and what a support conversation quotes, so they
 * are constants rather than message text: the message can be reworded, the code
 * cannot.
 */
public final class CaptureDiagnosticCodes {

    /** The build was stopped by the user before Bazel finished. */
    public static final String CAPTURE_CANCELLED = "CAPTURE_CANCELLED";

    /** A BES stream ended without its terminating event. */
    public static final String STREAM_ABORTED = "STREAM_ABORTED";

    /** A BES stream was refused: the pipeline could not accept its events. */
    public static final String STREAM_FAILED = "STREAM_FAILED";

    /** A pipeline queue was full, so the capture applied backpressure. */
    public static final String CAPTURE_LAG = "CAPTURE_LAG";

    /** The journal could not be written; the capture is incomplete from here on. */
    public static final String JOURNAL_WRITE_FAILED = "JOURNAL_WRITE_FAILED";

    /** Bazel exited non-zero, which is a build outcome and not a capture fault. */
    public static final String BUILD_FAILED = "BUILD_FAILED";

    /** An envelope arrived that carried no BEP event, and so produced no row. */
    public static final String NON_EVENT_ENVELOPE = "NON_EVENT_ENVELOPE";

    private CaptureDiagnosticCodes() {}
}
