package com.holtherndon.bazelviz.capture.file.detect;

import java.io.InputStream;

/**
 * A detection performed on a stream, together with the stream to keep reading.
 *
 * <p>Detection has to look at the first bytes, but the parser that follows needs
 * those same bytes. Rather than re-opening the source — impossible for a pipe,
 * and a wasted pass over the file otherwise — {@link FormatDetector#detect(InputStream)}
 * buffers a bounded prefix, rewinds it, and hands back the stream positioned at
 * byte zero again.
 *
 * @param detection what the prefix said the input is
 * @param stream the stream to parse from, rewound to where detection started.
 *     It may be a wrapper around the caller's stream; read from this one, not
 *     from the original
 */
public record StreamDetection(FormatDetection detection, InputStream stream) {

    public StreamDetection {
        if (detection == null || stream == null) {
            throw new IllegalArgumentException("detection and stream are both required");
        }
    }

    public DetectedFormat format() {
        return detection.format();
    }
}
