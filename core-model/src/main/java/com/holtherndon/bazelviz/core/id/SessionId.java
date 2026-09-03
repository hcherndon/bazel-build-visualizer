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

  @Override
  public String toString() {
    return value.toString();
  }
}
