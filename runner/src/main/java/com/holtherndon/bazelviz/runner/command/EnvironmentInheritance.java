package com.holtherndon.bazelviz.runner.command;

/**
 * What the launched Bazel process inherits from this one (plan 8.1 "inheritance mode").
 *
 * <p>This is a capture-fidelity decision before it is a security one. Bazel's action environment,
 * its toolchain resolution and its remote-cache credentials all come from the environment, so a
 * build launched with a different environment than the user's shell would give is a different build
 * — and the session would be a recording of something the user never runs. The default is therefore
 * to inherit everything.
 *
 * <p>The narrower modes exist for the case where a session will be shared and the environment is
 * known to carry secrets (plan 22.2). Whichever is chosen is recorded in the manifest, because a
 * reader who cannot tell which environment produced a build cannot judge whether it reproduces.
 */
public enum EnvironmentInheritance {

  /** The child gets this process's environment, plus any overrides. */
  INHERIT_ALL,

  /**
   * The child gets only the variables the user allow-listed, plus overrides. Bazel needs {@code
   * PATH} and {@code HOME} to function at all, so a filter that removes them produces a build that
   * fails for reasons unrelated to the user's command; the launcher warns rather than silently
   * re-adding them.
   */
  INHERIT_ALLOWLISTED,

  /**
   * The child gets only the explicit overrides. Almost always wrong for Bazel and offered only so
   * that a user reproducing a hermetic CI environment can state it exactly.
   */
  NONE
}
