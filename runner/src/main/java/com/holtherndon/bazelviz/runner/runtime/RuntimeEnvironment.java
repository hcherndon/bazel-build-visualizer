package com.holtherndon.bazelviz.runner.runtime;

/** How a command inherits the environment of the machine that executes it. */
public enum RuntimeEnvironment {
  /** Keep the executor's complete environment, then apply explicit overrides. */
  INHERIT_ALL,
  /** Keep only the small environment needed to launch ordinary command-line tools. */
  INHERIT_ESSENTIAL,
  /** Start empty, then apply explicit overrides. */
  NONE
}
