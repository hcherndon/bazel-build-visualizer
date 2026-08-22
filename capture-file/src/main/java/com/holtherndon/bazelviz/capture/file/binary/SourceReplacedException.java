package com.holtherndon.bazelviz.capture.file.binary;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A file being tailed shrank below the offset already consumed, so it is not
 * the file that was being read (plan 9.5, "detect truncation or file
 * replacement"). Bazel rewrites {@code --build_event_binary_file} from byte
 * zero on each invocation, so a second build in the same workspace replaces the
 * file underneath a tail reader.
 *
 * <p>This is raised rather than papered over because continuing would splice
 * two different builds' events into one session and report the result as a
 * single coherent capture.
 */
public final class SourceReplacedException extends IOException {

    private static final long serialVersionUID = 1L;

    private final transient Path file;
    private final long consumedOffset;
    private final long currentSize;

    public SourceReplacedException(Path file, long consumedOffset, long currentSize) {
        super("file " + file + " is now " + currentSize + " bytes but " + consumedOffset
                + " bytes had already been read; it was replaced or rewritten");
        this.file = file;
        this.consumedOffset = consumedOffset;
        this.currentSize = currentSize;
    }

    public Path file() {
        return file;
    }

    /** How far the reader had consumed before the file shrank. */
    public long consumedOffset() {
        return consumedOffset;
    }

    /** The file's size when the shrink was noticed. */
    public long currentSize() {
        return currentSize;
    }
}
