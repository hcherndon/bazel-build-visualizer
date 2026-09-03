package com.holtherndon.bazelviz.ui.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LoggingRuntimeTest {

  @Test
  @DisplayName("the unavailable runtime is safe except for requesting a nonexistent file")
  void unavailableRuntimeIsExplicit() {
    LoggingRuntime runtime = LoggingRuntime.unavailable();

    assertThat(runtime.available()).isFalse();
    assertThat(runtime.verbosity()).isEqualTo(LogVerbosity.INFO);
    assertThat(runtime.droppedRecordCount()).isZero();
    runtime.setVerbosity(LogVerbosity.TRACE);
    assertThat(runtime.verbosity()).isEqualTo(LogVerbosity.INFO);
    assertThatThrownBy(runtime::currentLog)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  @DisplayName("the unavailable runtime still rejects a null level")
  void unavailableRuntimeRejectsNull() {
    assertThatThrownBy(() -> LoggingRuntime.unavailable().setVerbosity(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("verbosity");
  }
}
