package com.holtherndon.bazelviz.capture.file.importer;

import java.io.IOException;

/**
 * An importer-owned file — the source checkpoint sidecar — is present but
 * cannot be understood.
 *
 * <p>An {@link IOException} rather than a runtime exception because it is a
 * condition of the data on disk, not a programming error, and the caller's only
 * sensible response is to tell the user which file is wrong and offer to start
 * the import over.
 */
public class ImportFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public ImportFormatException(String message) {
        super(message);
    }

    public ImportFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
