package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;

/**
 * How an import ended.
 *
 * <p>Each value maps to exactly one terminal session state, and the mapping is here so that "what
 * the import did" and "what the session says about itself" cannot drift apart. A truncated source
 * does not produce a {@code READY} session; the session says {@code INCOMPLETE}, because that is
 * what it is.
 */
public enum ImportOutcome {

  /** Every record in the source was read, journaled and normalized. */
  COMPLETE,

  /**
   * The source ended mid-record. Everything before the cut was imported and a diagnostic carries
   * the exact byte offset.
   */
  TRUNCATED,

  /**
   * A record's framing contradicted itself. Everything before the damage was imported; reading
   * stopped there rather than guessing at the next boundary (plan 21.3).
   */
  CORRUPT_PARTIAL,

  /**
   * The caller cancelled. The session is left non-terminal and resumable, with a checkpoint on a
   * record boundary — see {@link #sessionState()}.
   */
  CANCELLED;

  /** How the capture source is described in {@code capture_sources.completeness}. */
  public Completeness completeness() {
    return switch (this) {
      case COMPLETE -> Completeness.COMPLETE;
      case TRUNCATED -> Completeness.TRUNCATED;
      case CORRUPT_PARTIAL -> Completeness.CORRUPT_PARTIAL;
      // Cancelling says nothing about the bytes not yet read. Calling the
      // unread remainder complete or truncated would be an invention.
      case CANCELLED -> Completeness.UNKNOWN;
    };
  }

  /**
   * The session state this outcome finishes in, or {@code null} for {@link #CANCELLED}.
   *
   * <p>A cancelled import deliberately does <em>not</em> reach a terminal state. {@link
   * SessionState#INCOMPLETE} is terminal and has no legal successor, so marking a cancelled import
   * incomplete would make it permanently unresumable and would contradict the exit criterion
   * "restart resumes interrupted indexing". The session is instead left in {@link
   * SessionState#CAPTURING} — which is precisely what {@link
   * com.holtherndon.bazelviz.format.session.SessionManager#findInterrupted()} looks for — with a
   * diagnostic and a manifest warning saying it was cancelled. A caller that wants to give up for
   * good calls {@link BepImporter#abandon} and gets {@code INCOMPLETE}.
   *
   * <p>{@link #COMPLETE} answers {@code READY}; whether the session ends up {@code READY} or {@code
   * READY_WITH_WARNINGS} additionally depends on whether anything was recorded as a warning, which
   * is a property of the run rather than of the outcome.
   */
  public SessionState sessionState() {
    return switch (this) {
      case COMPLETE -> SessionState.READY;
      case TRUNCATED -> SessionState.INCOMPLETE;
      case CORRUPT_PARTIAL -> SessionState.CORRUPT_PARTIAL;
      case CANCELLED -> null;
    };
  }

  /** True when the import can be continued from its checkpoint. */
  public boolean isResumable() {
    return this == CANCELLED || this == TRUNCATED;
  }
}
