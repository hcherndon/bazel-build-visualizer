package com.holtherndon.bazelviz.ui.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LogVerbosityTest {

  @Test
  @DisplayName("logging levels have stable ordered identities and useful descriptions")
  void identitiesAreStable() {
    assertThat(Arrays.stream(LogVerbosity.values()).map(LogVerbosity::id))
        .containsExactly("error", "warn", "info", "debug", "trace")
        .doesNotHaveDuplicates();
    assertThat(Arrays.stream(LogVerbosity.values()).map(LogVerbosity::displayName))
        .containsExactly("Error", "Warn", "Info", "Debug", "Trace")
        .doesNotHaveDuplicates();
    assertThat(LogVerbosity.values())
        .allSatisfy(verbosity -> assertThat(verbosity.description()).isNotBlank());
    assertThat(LogVerbosity.defaultVerbosity()).isEqualTo(LogVerbosity.INFO);
  }

  @Test
  @DisplayName("ids are case-insensitive and unknown values stay absent")
  void resolvesIds() {
    assertThat(LogVerbosity.fromId("  DEBUG ")).contains(LogVerbosity.DEBUG);
    assertThat(LogVerbosity.fromId("Trace")).contains(LogVerbosity.TRACE);
    assertThat(LogVerbosity.fromId("")).isEmpty();
    assertThat(LogVerbosity.fromId(null)).isEmpty();
    assertThat(LogVerbosity.fromId("verbose")).isEmpty();
  }

  @Test
  @DisplayName("a valid process override wins for startup without hiding the saved fallback")
  void startupResolution() {
    assertThat(LogVerbosity.startupVerbosity("trace", LogVerbosity.WARN))
        .isEqualTo(LogVerbosity.TRACE);
    assertThat(LogVerbosity.startupVerbosity("typo", LogVerbosity.DEBUG))
        .isEqualTo(LogVerbosity.DEBUG);
    assertThat(LogVerbosity.startupVerbosity(null, LogVerbosity.ERROR))
        .isEqualTo(LogVerbosity.ERROR);
  }
}
