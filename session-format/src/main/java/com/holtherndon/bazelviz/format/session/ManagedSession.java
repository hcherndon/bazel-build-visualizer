package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * An open, exclusively held managed session.
 *
 * <p>Holding one of these means holding its {@link SessionLock}: everything on this object writes
 * into the session directory, and the directory has exactly one writer. {@link #close()} releases
 * the lock. It does not finalize the session — closing a window is not the same as declaring a
 * capture finished, and a session left non-terminal is precisely what {@link
 * SessionManager#findInterrupted()} is for.
 *
 * <p>Every state change goes through {@link SessionState#canTransitionTo}. The state machine is
 * defined once, in {@code core-model}, and this class asks it rather than restating it.
 *
 * <p>Not thread-safe by field, but every mutation is synchronized on this object, so concurrent
 * transitions serialize rather than interleave a manifest write. Callers must still keep it off the
 * EDT: every method here does file I/O.
 */
public final class ManagedSession implements AutoCloseable {

  private final ManagedSessionLayout layout;
  private final SessionManifestCodec codec;
  private final SessionLock lock;
  private final SessionManager.TransitionPublisher publisher;
  private final SessionManager.MicrosClock clock;

  private SessionManifest manifest;
  private boolean closed;

  ManagedSession(
      ManagedSessionLayout layout,
      SessionManifestCodec codec,
      SessionLock lock,
      SessionManifest manifest,
      SessionManager.TransitionPublisher publisher,
      SessionManager.MicrosClock clock) {
    this.layout = layout;
    this.codec = codec;
    this.lock = lock;
    this.manifest = manifest;
    this.publisher = publisher;
    this.clock = clock;
  }

  public ManagedSessionLayout layout() {
    return layout;
  }

  public Path root() {
    return layout.root();
  }

  public SessionId id() {
    return manifest.sessionId();
  }

  /** The manifest as last written. Immutable; re-read after any mutation. */
  public synchronized SessionManifest manifest() {
    return manifest;
  }

  public synchronized SessionState state() {
    return manifest.state();
  }

  /** The lock this session holds. */
  public SessionLock lock() {
    return lock;
  }

  /**
   * Moves to {@code next}, rewriting the manifest atomically and publishing the change.
   *
   * @throws IllegalStateException when the transition is not legal from the current state, naming
   *     the states that are — an illegal transition is a programming error, not a user-visible
   *     condition
   */
  public void transitionTo(SessionState next) throws IOException {
    transitionTo(next, SessionStateChange.Reason.PROGRESS);
  }

  synchronized void transitionTo(SessionState next, SessionStateChange.Reason reason)
      throws IOException {
    Objects.requireNonNull(next, "next");
    checkOpen();
    SessionState from = manifest.state();
    if (from == next) {
      return;
    }
    if (!from.canTransitionTo(next)) {
      throw new IllegalStateException(
          "cannot move session "
              + manifest.sessionId()
              + " from "
              + from
              + " to "
              + next
              + "; legal next states are "
              + from.allowedNext());
    }
    long atMicros = clock.nowMicros();
    SessionManifest.Builder builder = manifest.toBuilder().state(next);
    if (next.isTerminal()) {
      builder.finalizedAtMicros(atMicros);
    }
    persist(builder.build());
    publisher.publish(
        new SessionStateChange(manifest.sessionId(), layout.root(), from, next, atMicros, reason));
  }

  /**
   * Walks the shortest legal path to {@code terminal} and stops there.
   *
   * <p>The intermediate steps are real transitions with real manifest writes, not a shortcut around
   * the state machine: a session that goes {@code CAPTURING -> INCOMPLETE} and one that goes {@code
   * BUILD_FINISHED -> INDEXING -> INCOMPLETE} genuinely passed through different states, and the
   * manifest should be able to say so at any moment.
   *
   * @throws IllegalArgumentException when {@code terminal} is not a terminal state
   * @throws IllegalStateException when no legal path exists from the current state
   */
  public void finalizeSession(SessionState terminal) throws IOException {
    finalizeSession(terminal, SessionStateChange.Reason.PROGRESS);
  }

  synchronized void finalizeSession(SessionState terminal, SessionStateChange.Reason reason)
      throws IOException {
    if (!terminal.isTerminal()) {
      throw new IllegalArgumentException(terminal + " is not a terminal session state");
    }
    checkOpen();
    List<SessionState> path =
        SessionStates.shortestPath(manifest.state(), terminal)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no legal path from " + manifest.state() + " to " + terminal));
    for (SessionState step : path) {
      transitionTo(step, reason);
    }
  }

  /**
   * Read-modify-write of the manifest, atomically. The builder handed to {@code mutation} already
   * carries any members written by a newer build, so they survive the rewrite (plan 21.5).
   *
   * <p>The state field is not writable here — use {@link #transitionTo} so the change is validated
   * and published.
   */
  public synchronized SessionManifest updateManifest(
      UnaryOperator<SessionManifest.Builder> mutation) throws IOException {
    checkOpen();
    SessionState before = manifest.state();
    SessionManifest updated = mutation.apply(manifest.toBuilder()).build();
    if (updated.state() != before) {
      throw new IllegalArgumentException(
          "manifest updates must not change session state; use transitionTo("
              + updated.state()
              + ")");
    }
    persist(updated);
    return updated;
  }

  /** Appends a warning to the manifest, ignoring an exact duplicate. */
  public SessionManifest addWarning(String warning) throws IOException {
    return updateManifest(builder -> builder.addWarning(warning));
  }

  private void persist(SessionManifest updated) throws IOException {
    codec.write(layout.manifestFile(), updated);
    this.manifest = updated;
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("session " + manifest.sessionId() + " is closed");
    }
  }

  /** Releases the lock. Leaves the recorded state exactly as it is. */
  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    lock.close();
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  /** Breadth-first search over {@link SessionState#allowedNext()}. */
  static final class SessionStates {

    private SessionStates() {}

    /**
     * The shortest sequence of transitions from {@code from} to {@code to}, excluding {@code from}
     * itself, or empty when none exists.
     */
    static Optional<List<SessionState>> shortestPath(SessionState from, SessionState to) {
      if (from == to) {
        return Optional.of(List.of());
      }
      Map<SessionState, SessionState> cameFrom = new EnumMap<>(SessionState.class);
      Deque<SessionState> queue = new ArrayDeque<>();
      queue.add(from);
      cameFrom.put(from, from);
      while (!queue.isEmpty()) {
        SessionState current = queue.removeFirst();
        for (SessionState next : current.allowedNext()) {
          if (cameFrom.containsKey(next)) {
            continue;
          }
          cameFrom.put(next, current);
          if (next == to) {
            List<SessionState> path = new ArrayList<>();
            for (SessionState step = to; step != from; step = cameFrom.get(step)) {
              path.add(step);
            }
            Collections.reverse(path);
            return Optional.of(List.copyOf(path));
          }
          queue.addLast(next);
        }
      }
      return Optional.empty();
    }
  }
}
