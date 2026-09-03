package com.holtherndon.bazelviz.format.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exhaustive check that {@link ManagedSession} obeys the state machine defined in {@code
 * core-model} rather than a copy of it: every ordered pair of states is attempted, and the outcome
 * must agree with {@link SessionState#canTransitionTo}.
 */
class SessionStateTransitionTest {

  @TempDir Path root;

  @Test
  void everyLegalTransitionIsAcceptedAndEveryIllegalOneIsRejected() throws Exception {
    SessionManager manager = newManager();
    List<String> unexpected = new ArrayList<>();

    for (SessionState from : SessionState.values()) {
      for (SessionState to : SessionState.values()) {
        if (from == to) {
          continue;
        }
        boolean legal = from.canTransitionTo(to);
        try (ManagedSession session = openInState(manager, from)) {
          try {
            session.transitionTo(to);
            if (!legal) {
              unexpected.add(from + " -> " + to + " was accepted but is illegal");
            } else if (session.state() != to) {
              unexpected.add(from + " -> " + to + " left the session in " + session.state());
            }
          } catch (IllegalStateException e) {
            if (legal) {
              unexpected.add(from + " -> " + to + " was rejected but is legal: " + e.getMessage());
            }
          }
        }
      }
    }

    assertThat(unexpected).isEmpty();
  }

  @Test
  void aRejectedTransitionNamesTheStatesThatWouldHaveWorked() throws Exception {
    SessionManager manager = newManager();

    try (ManagedSession session = openInState(manager, SessionState.CAPTURING)) {
      assertThatThrownBy(() -> session.transitionTo(SessionState.READY))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("from CAPTURING to READY")
          .hasMessageContaining("BUILD_FINISHED");

      // ...and the rejection changed nothing on disk.
      assertThat(manager.readManifest(session.root()).state()).isEqualTo(SessionState.CAPTURING);
    }
  }

  @Test
  void aTerminalStateAcceptsNothingFurther() throws Exception {
    SessionManager manager = newManager();

    for (SessionState terminal : SessionState.values()) {
      if (!terminal.isTerminal()) {
        continue;
      }
      try (ManagedSession session = openInState(manager, terminal)) {
        for (SessionState next : SessionState.values()) {
          if (next == terminal) {
            continue;
          }
          SessionState target = next;
          assertThatThrownBy(() -> session.transitionTo(target))
              .as("%s -> %s", terminal, target)
              .isInstanceOf(IllegalStateException.class);
        }
      }
    }
  }

  @Test
  void aTransitionToTheCurrentStateIsANoOp() throws Exception {
    SessionManager manager = newManager();

    try (ManagedSession session = openInState(manager, SessionState.READY)) {
      session.transitionTo(SessionState.READY);

      assertThat(session.state()).isEqualTo(SessionState.READY);
    }
  }

  @Test
  void reachingATerminalStateStampsAFinalizationTimeAndOnlyThen() throws Exception {
    SessionManager manager = newManager();

    try (ManagedSession session = manager.create(SessionId.random())) {
      assertThat(session.manifest().finalizedMicros()).isEmpty();

      session.transitionTo(SessionState.PREFLIGHT);
      session.transitionTo(SessionState.CAPTURING);
      assertThat(session.manifest().finalizedMicros()).isEmpty();

      session.transitionTo(SessionState.BUILD_FINISHED);
      session.transitionTo(SessionState.INDEXING);
      assertThat(session.manifest().finalizedMicros()).isEmpty();

      session.transitionTo(SessionState.READY);
      assertThat(session.manifest().finalizedMicros()).isPresent();
      assertThat(session.manifest().isFinalized()).isTrue();
    }
  }

  @Test
  void finalizingWalksTheShortestLegalPath() throws Exception {
    SessionManager manager = newManager();

    try (ManagedSession session = openInState(manager, SessionState.BUILD_FINISHED)) {
      session.finalizeSession(SessionState.READY);

      assertThat(session.state()).isEqualTo(SessionState.READY);
      assertThat(session.manifest().finalizedMicros()).isPresent();
    }
  }

  @Test
  void finalizingRefusesANonTerminalTargetAndAnUnreachableOne() throws Exception {
    SessionManager manager = newManager();

    try (ManagedSession session = openInState(manager, SessionState.CAPTURING)) {
      assertThatThrownBy(() -> session.finalizeSession(SessionState.INDEXING))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not a terminal");

      // Nothing can reach FAILED_TO_START once capture has begun.
      assertThatThrownBy(() -> session.finalizeSession(SessionState.FAILED_TO_START))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no legal path");
    }
  }

  @Test
  void aManifestUpdateMayNotSmuggleInAStateChange() throws Exception {
    SessionManager manager = newManager();

    try (ManagedSession session = openInState(manager, SessionState.CAPTURING)) {
      assertThatThrownBy(() -> session.updateManifest(builder -> builder.state(SessionState.READY)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("use transitionTo");
    }
  }

  @Test
  void aClosedSessionRefusesFurtherWrites() throws Exception {
    SessionManager manager = newManager();
    ManagedSession session = openInState(manager, SessionState.CAPTURING);
    session.close();

    assertThatThrownBy(() -> session.transitionTo(SessionState.BUILD_FINISHED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is closed");
  }

  private SessionManager newManager() {
    return new SessionManager(
        root, "test-app", SessionManifestCodec.standard(), new CountingClock(), "test-host");
  }

  /**
   * A fresh session whose manifest records {@code state}, written directly so that states only
   * reachable through several steps can be tested one pair at a time.
   */
  private static ManagedSession openInState(SessionManager manager, SessionState state)
      throws IOException {
    SessionId id = SessionId.random();
    ManagedSessionLayout layout = ManagedSessionLayout.forSession(manager.sessionsRoot(), id);
    layout.createDirectories(ManagedSessionLayout.CAPTURE_DIRECTORIES);
    SessionManifest manifest =
        SessionManifest.newSession(id, manager.appVersion(), 1_000L).state(state).build();
    SessionManifestCodec.standard().write(layout.manifestFile(), manifest);
    return manager.open(layout.root());
  }

  /** Monotonic and deterministic, so a finalization stamp is checkable. */
  private static final class CountingClock implements SessionManager.MicrosClock {
    private long now = 1_000_000L;

    @Override
    public long nowMicros() {
      return now += 1_000L;
    }
  }
}
