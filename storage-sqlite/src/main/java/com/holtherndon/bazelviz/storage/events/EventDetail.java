package com.holtherndon.bazelviz.storage.events;

import java.util.Objects;
import java.util.Optional;

/**
 * Everything stored about one event, including where its verbatim bytes live.
 *
 * <p>The raw location is what makes the inspector's "show the raw protobuf" possible without ever
 * having held the payload in memory: the UI asks for one event, gets a segment/offset/length, and
 * reads exactly those bytes back off disk.
 *
 * @param identity the event's own canonical id, empty when it declares none
 */
public record EventDetail(
    EventSummary summary, RawLocation rawLocation, Optional<EventIdentity> identity) {

  public EventDetail {
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(rawLocation, "rawLocation");
    Objects.requireNonNull(identity, "identity");
  }

  public long id() {
    return summary.id();
  }
}
