package com.holtherndon.bazelviz.format.session;

import java.io.IOException;

/**
 * A managed session directory is not in a shape this build can work with: the
 * manifest is missing, malformed, contradicts itself, or names something —
 * a state, a completeness — that this build does not recognize.
 *
 * <p>Checked, and an {@link IOException}, because every caller is already
 * handling I/O failure when it touches a session directory, and because the two
 * failures deserve the same treatment at the call site: report the path, do not
 * pretend the session opened.
 */
public class SessionFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    public SessionFormatException(String message) {
        super(message);
    }

    public SessionFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
