package com.holtherndon.bazelviz.core.id;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable cross-restart identity of a session. Database row ids must never leak out of the storage
 * layer as session identity (plan section 11.1).
 */
public record SessionId(UUID value) {
  public SessionId {
    Objects.requireNonNull(value, "value");
  }

  public static SessionId random() {
    return new SessionId(UUID.randomUUID());
  }

  public static SessionId parse(String text) {
    return new SessionId(UUID.fromString(text));
  }

  /**
   * Parses the one stable textual form used at persistence and filesystem boundaries.
   *
   * <p>{@link UUID#fromString(String)} accepts some shortened and upper-case spellings. Those are
   * UUIDs, but they are not the lower-case {@code 8-4-4-4-12} form emitted by {@link
   * UUID#toString()}. Accepting aliases at a boundary would let one session identity acquire
   * several directory or lock keys.
   */
  public static SessionId parseCanonical(String text) {
    Objects.requireNonNull(text, "text");
    SessionId parsed;
    try {
      parsed = parse(text);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("session id must be a canonical UUID", malformed);
    }
    if (!parsed.toString().equals(text)) {
      throw new IllegalArgumentException("session id must be a canonical UUID");
    }
    return parsed;
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
