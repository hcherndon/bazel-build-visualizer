package com.holtherndon.bazelviz.capture.bes;

import java.util.Objects;

/**
 * The identity of one BES stream, as Bazel's {@code StreamId} states it.
 *
 * <p>A single build produces more than one stream: Bazel opens one per component, and a retried
 * invocation opens another with the same build id and a different invocation id. Keying on any one
 * of the three fields alone would merge streams that are genuinely separate, which is how duplicate
 * sequence numbers from different streams turn into phantom duplicates or, worse, into one stream's
 * event overwriting another's at the same sequence.
 *
 * <p>{@link #storageKey()} is what goes in {@code event_streams.stream_key}, whose {@code UNIQUE}
 * constraint then does the deduplication in the database rather than in memory.
 *
 * @param buildId Bazel's build id, constant across invocation attempts
 * @param invocationId the id of this attempt; also what {@code BuildStarted.uuid} carries in the
 *     BEP stream, which is what lets a BES stream be correlated with the events inside it
 * @param component the {@code StreamId.BuildComponent} name, e.g. {@code TOOL}
 */
public record BesStreamKey(String buildId, String invocationId, String component) {

  public BesStreamKey {
    Objects.requireNonNull(buildId, "buildId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(component, "component");
  }

  /**
   * The stable text key for {@code event_streams.stream_key}.
   *
   * <p>Slash-separated in a fixed order. The fields cannot contain a slash — they are uuids and an
   * enum name — so the encoding is unambiguous without escaping, and a human reading the column can
   * see which build it belongs to.
   */
  public String storageKey() {
    return "bes/" + buildId + "/" + invocationId + "/" + component;
  }

  @Override
  public String toString() {
    return storageKey();
  }
}
