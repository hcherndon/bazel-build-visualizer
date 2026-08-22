package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.capture.file.detect.FormatDetection;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The file offered for import is not a BEP capture this build can read.
 *
 * <p>Thrown before any session directory is created, so a refused import leaves
 * nothing behind to clean up.
 *
 * <p>The detector's reason is carried through verbatim into the message.
 * "Unsupported file" tells a user nothing; "the first frame declares 4 bytes
 * that do not parse as a BuildEvent" tells them their file is truncated at the
 * head or is not BEP at all. Guessing a format instead would move the failure
 * from the door to the middle of an import, where it is far harder to explain
 * (plan 5.2: ambiguity is reported, not guessed).
 */
public class UnsupportedSourceException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path source;
    private final transient FormatDetection detection;

    public UnsupportedSourceException(Path source, FormatDetection detection) {
        super("cannot import " + source + ": " + detection.reason()
                + " (detected " + detection.format() + " after inspecting "
                + detection.bytesInspected() + " byte(s))");
        this.source = source;
        this.detection = detection;
    }

    public Path source() {
        return source;
    }

    /** The verdict, with the evidence behind it, for a user-facing message. */
    public FormatDetection detection() {
        return detection;
    }
}
