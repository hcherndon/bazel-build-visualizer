package com.holtherndon.bazelviz.storage.events;

import java.util.OptionalLong;

/**
 * A child a parent event announced, and whether it ever arrived.
 *
 * <p>{@code arrivedEventId} is an {@link OptionalLong} rather than a sentinel: "this child never
 * arrived" is a finding the Events view reports (plan 17.11 missing-announced-child report), not a
 * zero to be glossed over.
 *
 * @param parentEventId the announcing event's row id
 * @param ordinal announcement order within that parent
 * @param childEventIdHash the canonical id hash the parent named
 * @param arrivedEventId row id of the event that turned up carrying that hash, empty when none has
 */
public record AnnouncedChild(
    long parentEventId, int ordinal, long childEventIdHash, OptionalLong arrivedEventId) {

  /** True when the announced child has not been delivered. */
  public boolean isMissing() {
    return arrivedEventId.isEmpty();
  }
}
