package com.holtherndon.bazelviz.runner.proc;

import java.time.Duration;
import java.util.Optional;

/**
 * The three stops offered by plan 8.7, in increasing order of violence.
 *
 * <h2>What each one actually does, measured</h2>
 *
 * <p>Less separates them than their names suggest, and the measurements are worth stating because
 * they contradict the obvious reading.
 *
 * <p>{@link #CANCEL} and {@link #TERMINATE} are, for the Bazel client, <em>the same thing</em>:
 * both exit 8, both leave a complete event stream ending in {@code BuildFinished(INTERRUPTED)}, and
 * only the banner text differs. TERMINATE is kept because a user who asks for something firmer
 * should get a firmer signal, not because it achieves more.
 *
 * <p>{@link #FORCE_KILL} does not stop the build. The Bazel server runs with {@code PPID 1} in its
 * own session — it is not a descendant of the client at any point, not even during a cold start —
 * so killing the client's process tree reaps nothing. The server notices the client is gone after
 * about two and a half seconds, cancels the build itself, and finishes writing the event stream.
 * Force-killing therefore costs the ability to know when the stream is complete and buys no speed
 * at all.
 *
 * <p>The server is deliberately never reaped. It is a shared daemon serving every terminal using
 * that output base, and killing it would discard analysis state belonging to work this application
 * knows nothing about.
 *
 * <p>Whichever is used, plan 8.7's "always" list applies: keep draining output briefly, finalize
 * the raw data already captured, and mark the invocation cancelled rather than failed.
 */
public enum CancellationMode {

  /**
   * Ask Bazel to stop as it would on Ctrl-C: {@code SIGINT} to the client, which asks the server to
   * interrupt the build and still flush its event stream. The client exits within about 25
   * milliseconds and the stream is complete at that instant.
   */
  CANCEL(Duration.ofSeconds(30)),

  /**
   * {@code SIGTERM} to the client. Measurably identical to {@link #CANCEL} in outcome; kept as a
   * distinct rung so that "stop harder" sends a different signal rather than repeating one that has
   * already failed to land.
   */
  TERMINATE(Duration.ofSeconds(10)),

  /**
   * {@code SIGKILL} to the client. The last resort for a client that is not responding to signals
   * at all.
   *
   * <p>It does not reach the build: the server keeps running actions for a couple of seconds
   * afterwards and then stops them itself. It also leaves the workspace's command lock held — by
   * the PID of the now-dead client — for around two and a half seconds, so a build started
   * immediately afterwards fails with exit 9.
   */
  FORCE_KILL(Duration.ofSeconds(5));

  private final Duration defaultGrace;

  CancellationMode(Duration defaultGrace) {
    this.defaultGrace = defaultGrace;
  }

  /** How long to wait for this stop before escalating to the next one. */
  public Duration defaultGracePeriod() {
    return defaultGrace;
  }

  /** The next, harsher mode, or empty when this is the last resort. */
  public Optional<CancellationMode> escalation() {
    return switch (this) {
      case CANCEL -> Optional.of(TERMINATE);
      case TERMINATE -> Optional.of(FORCE_KILL);
      case FORCE_KILL -> Optional.empty();
    };
  }
}
