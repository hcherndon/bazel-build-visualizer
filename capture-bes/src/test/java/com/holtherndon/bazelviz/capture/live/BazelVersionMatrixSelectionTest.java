package com.holtherndon.bazelviz.capture.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Safe selector checks: this class never finds or starts Bazel. */
final class BazelVersionMatrixSelectionTest {

  @ParameterizedTest
  @ValueSource(strings = {"6.5.0", "7.6.1", "8.4.1", "9.2.0"})
  @DisplayName("each supported selection produces exactly one matching parameter")
  void supportedVersionProducesOneParameter(String version) {
    assertThat(
            BazelVersionMatrixSelection.fromEnvironment(
                    Map.of(BazelVersionMatrixSelection.ENVIRONMENT_VARIABLE, version))
                .toList())
        .containsExactly(version);
  }

  @Test
  @DisplayName("surrounding whitespace does not change the selected version")
  void surroundingWhitespaceIsIgnored() {
    assertThat(
            BazelVersionMatrixSelection.fromEnvironment(
                    Map.of(BazelVersionMatrixSelection.ENVIRONMENT_VARIABLE, "  9.2.0\t"))
                .toList())
        .containsExactly("9.2.0");
  }

  @Test
  @DisplayName("a missing selector fails with the safe invocation requirement")
  void missingSelectionFails() {
    assertInvalid(Map.of(), "missing or blank");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  @DisplayName("a blank selector fails with the safe invocation requirement")
  void blankSelectionFails(String version) {
    assertInvalid(
        Map.of(BazelVersionMatrixSelection.ENVIRONMENT_VARIABLE, version), "missing or blank");
  }

  @ParameterizedTest
  @ValueSource(strings = {"5.4.1", "latest", "6.5.0,7.6.1", "6.5.0 7.6.1"})
  @DisplayName("unsupported and multiple selections fail before they become parameters")
  void unsupportedSelectionFails(String version) {
    assertInvalid(
        Map.of(BazelVersionMatrixSelection.ENVIRONMENT_VARIABLE, version), "unsupported value");
  }

  private static void assertInvalid(Map<String, String> environment, String problem) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> BazelVersionMatrixSelection.fromEnvironment(environment))
        .withMessageContaining(BazelVersionMatrixSelection.ENVIRONMENT_VARIABLE)
        .withMessageContaining(problem)
        .withMessageContaining("exactly one supported version")
        .withMessageContaining("6.5.0")
        .withMessageContaining("9.2.0");
  }
}
