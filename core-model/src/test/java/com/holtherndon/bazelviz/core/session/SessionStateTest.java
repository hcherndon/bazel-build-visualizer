package com.holtherndon.bazelviz.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionStateTest {

  @Test
  void happyPathIsFullyConnected() {
    assertThat(SessionState.NEW.canTransitionTo(SessionState.PREFLIGHT)).isTrue();
    assertThat(SessionState.PREFLIGHT.canTransitionTo(SessionState.CAPTURING)).isTrue();
    assertThat(SessionState.CAPTURING.canTransitionTo(SessionState.BUILD_FINISHED)).isTrue();
    assertThat(SessionState.BUILD_FINISHED.canTransitionTo(SessionState.ENRICHING)).isTrue();
    assertThat(SessionState.ENRICHING.canTransitionTo(SessionState.INDEXING)).isTrue();
    assertThat(SessionState.INDEXING.canTransitionTo(SessionState.READY)).isTrue();
  }

  @Test
  void terminalStatesHaveNoSuccessors() {
    for (SessionState state : SessionState.values()) {
      if (state.isTerminal()) {
        assertThat(state.allowedNext()).as("terminal %s", state).isEmpty();
      } else {
        assertThat(state.allowedNext()).as("non-terminal %s", state).isNotEmpty();
      }
    }
  }

  @Test
  void skippingCaptureIsNotAllowed() {
    assertThat(SessionState.NEW.canTransitionTo(SessionState.READY)).isFalse();
    assertThat(SessionState.PREFLIGHT.canTransitionTo(SessionState.BUILD_FINISHED)).isFalse();
  }

  @Test
  void enrichmentMayBeSkipped() {
    assertThat(SessionState.BUILD_FINISHED.canTransitionTo(SessionState.INDEXING)).isTrue();
  }
}
