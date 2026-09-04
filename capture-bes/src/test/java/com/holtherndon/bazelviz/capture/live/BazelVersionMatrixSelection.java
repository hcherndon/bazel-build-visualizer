package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import java.util.Map;
import java.util.stream.Stream;

/** Selects the one supported Bazel version a deliberate compatibility run may start. */
final class BazelVersionMatrixSelection {

  static final String ENVIRONMENT_VARIABLE = "BBV_BAZEL_MATRIX_VERSION";

  private BazelVersionMatrixSelection() {}

  /**
   * Returns exactly one parameter, or fails before the matrix test can find or launch Bazel.
   *
   * @param environment the inherited test environment
   */
  static Stream<String> fromEnvironment(Map<String, String> environment) {
    String configured = environment.get(ENVIRONMENT_VARIABLE);
    if (configured == null || configured.isBlank()) {
      throw invalidSelection("is missing or blank");
    }
    String selected = configured.strip();
    if (!BazelBinary.targetedVersions().contains(selected)) {
      throw invalidSelection("is set to unsupported value '" + selected + "'");
    }
    return Stream.of(selected);
  }

  private static IllegalArgumentException invalidSelection(String problem) {
    return new IllegalArgumentException(
        ENVIRONMENT_VARIABLE
            + " "
            + problem
            + "; set it to exactly one supported version: "
            + String.join(", ", BazelBinary.targetedVersions()));
  }
}
