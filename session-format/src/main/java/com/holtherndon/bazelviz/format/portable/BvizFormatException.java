package com.holtherndon.bazelviz.format.portable;

import java.io.IOException;

/**
 * An archive that cannot be trusted.
 *
 * <p>An {@link IOException} rather than a runtime exception because every
 * caller is already handling one: reading an archive is I/O, and a malformed
 * archive is one of the ways that I/O fails. The message always names what was
 * wrong and, where there is one, the entry it was wrong in — a refusal a user
 * cannot act on is not much better than a crash.
 */
public class BvizFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public BvizFormatException(String message) {
        super(message);
    }

    public BvizFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
