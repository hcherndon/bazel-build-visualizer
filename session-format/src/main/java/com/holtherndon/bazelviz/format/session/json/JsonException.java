package com.holtherndon.bazelviz.format.session.json;

/**
 * A JSON document is malformed, exceeds a configured limit, or holds a value of the wrong shape.
 *
 * <p>Unchecked on purpose: it signals bad <em>content</em>, which is a different failure from bad
 * <em>I/O</em>. {@link JsonReader} still throws {@link java.io.IOException} for the latter, so a
 * caller can tell "the file is garbage" apart from "the disk went away" and report each accurately.
 * Callers that need a checked, domain-typed failure catch this and translate it — see {@code
 * SessionManifestCodec}.
 */
public class JsonException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JsonException(String message) {
    super(message);
  }

  public JsonException(String message, Throwable cause) {
    super(message, cause);
  }
}
