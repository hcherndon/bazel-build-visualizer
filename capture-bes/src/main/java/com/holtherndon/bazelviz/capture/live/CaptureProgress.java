package com.holtherndon.bazelviz.capture.live;

import java.util.List;
import java.util.Objects;

/**
 * What the capture has done so far, for the live status display (plan 24, Phase 2 UI deliverable
 * "live capture status").
 *
 * <p>Three counters rather than one, because they answer three different questions and the
 * differences between them are the only visible sign of pressure. {@link #received} minus {@link
 * #journaled} is what is at risk if this process dies. {@link #journaled} minus {@link #normalized}
 * is the indexing backlog — safe, but the reason the tables lag behind the console. A single
 * "events" number would hide both.
 *
 * @param received events accepted from the wire
 * @param journaled events whose frame is in the journal, and which have therefore been acknowledged
 *     to Bazel
 * @param normalized events turned into database rows
 * @param bytesJournaled total payload bytes written
 * @param decodeFailures events that were journaled but could not be decoded; they are still rows,
 *     and still bytes, and are counted separately so the display never implies a clean capture that
 *     was not
 * @param streams one line per BES stream
 * @param lagged true when a pipeline queue has been full, which is the capture-lag indicator plan
 *     9.4 requires
 */
public record CaptureProgress(
    long received,
    long journaled,
    long normalized,
    long bytesJournaled,
    long decodeFailures,
    List<String> streams,
    boolean lagged) {

  public CaptureProgress {
    streams = List.copyOf(Objects.requireNonNull(streams, "streams"));
  }

  /** Events accepted but not yet durable. */
  public long journalBacklog() {
    return received - journaled;
  }

  /** Events durable but not yet queryable. */
  public long normalizeBacklog() {
    return journaled - normalized;
  }
}
