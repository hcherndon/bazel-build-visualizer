package com.holtherndon.bazelviz.format.session;

/**
 * Notified when a session's recorded state changes.
 *
 * <p>Deliberately free of any UI type. This module has no Swing dependency and must not acquire
 * one: a listener that needs to touch the UI marshals to the EDT itself, at the edge, where the
 * threading rule is visible (plan 19.1). The inverse — publishing on the EDT from here — would put
 * session bookkeeping on the one thread that must never wait for I/O.
 *
 * <p>Called on whichever thread committed the transition, after the manifest has been written.
 * Implementations must be quick and must not block; an exception thrown from a listener is logged
 * and does not undo the transition or prevent the remaining listeners from being called.
 */
@FunctionalInterface
public interface SessionStateListener {

  void sessionStateChanged(SessionStateChange change);
}
