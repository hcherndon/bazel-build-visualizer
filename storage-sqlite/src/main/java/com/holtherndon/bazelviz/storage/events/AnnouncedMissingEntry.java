package com.holtherndon.bazelviz.storage.events;

/**
 * One row of {@code bep_announced_missing}: a child that a parent promised and the stream never
 * delivered.
 *
 * @param childEventIdHash the canonical id hash that never arrived
 * @param announcedByEventId the lowest-numbered event that announced it
 */
public record AnnouncedMissingEntry(long childEventIdHash, long announcedByEventId) {}
