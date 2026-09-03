package com.holtherndon.bazelviz.storage.events;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One page of events plus the anchor that fetches the next page.
 *
 * <p>Keyset pagination, not {@code OFFSET}. The Phase 0 spike measured {@code OFFSET} paging at 2M
 * rows costing 11.3 ms mean and 23 ms worst case and growing linearly with depth, against 0.23 ms
 * mean and 0.65 ms p95 for a keyset anchor that stayed flat from 500k to 2M rows
 * (docs/performance.md). At Tier 3 scale {@code OFFSET} would blow the 100 ms cached-page objective
 * somewhere in the middle of the table, so the anchor is part of the API rather than something a
 * caller might forget to use.
 *
 * @param events the page's rows, ascending by id for a forward page and ascending by id for a
 *     backward page too (backward pages are re-ordered before returning, so callers always see
 *     natural order)
 * @param nextAnchor the id to pass as the next request's anchor, empty when this page reached the
 *     end
 */
public record EventPage(List<EventSummary> events, OptionalLong nextAnchor) {

  public EventPage {
    events = List.copyOf(events);
    Objects.requireNonNull(nextAnchor, "nextAnchor");
  }

  public static EventPage empty() {
    return new EventPage(List.of(), OptionalLong.empty());
  }

  public boolean isEmpty() {
    return events.isEmpty();
  }

  public int size() {
    return events.size();
  }
}
