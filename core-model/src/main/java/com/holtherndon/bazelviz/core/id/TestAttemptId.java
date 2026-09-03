package com.holtherndon.bazelviz.core.id;

import java.util.Objects;

/**
 * One execution of a test: a particular run, of a particular shard, on a particular attempt (plan
 * 11.1).
 *
 * <h2>Three numbers, all of them necessary</h2>
 *
 * <p>They are independent, and Bazel's {@code TestResultId} carries all three because it has to:
 *
 * <ul>
 *   <li><strong>run</strong> — {@code --runs_per_test=N} executes the whole test N times to expose
 *       flakiness.
 *   <li><strong>shard</strong> — {@code shard_count} splits the test's cases across parallel
 *       processes, each seeing a different subset.
 *   <li><strong>attempt</strong> — {@code --flaky_test_attempts} retries a failed run, so attempt 1
 *       failing and attempt 2 passing is precisely what "flaky" means.
 * </ul>
 *
 * <p>A key missing any of them merges executions that reported different results. Dropping attempt
 * in particular would hide the failure that made a test flaky, leaving a green result and no
 * evidence — which is the single most misleading thing a test view can do.
 *
 * <p>Bazel numbers all three from 1.
 *
 * @param test the test target
 * @param run which {@code --runs_per_test} run
 * @param shard which shard
 * @param attempt which retry
 */
public record TestAttemptId(TestId test, int run, int shard, int attempt) {

  public TestAttemptId {
    Objects.requireNonNull(test, "test");
    requirePositive(run, "run");
    requirePositive(shard, "shard");
    requirePositive(attempt, "attempt");
  }

  /**
   * The attempt an event identified, defaulting absent numbers to 1.
   *
   * <p>Bazel omits these fields when they are 1, and protobuf reports an unset {@code int32} as 0 —
   * so 0 here means "the first one", not "the zeroth". Treating it as a real value would produce a
   * key no other event shares and split one test's history in two.
   */
  public static TestAttemptId ofEventValues(TestId test, int run, int shard, int attempt) {
    return new TestAttemptId(test, atLeastOne(run), atLeastOne(shard), atLeastOne(attempt));
  }

  private static int atLeastOne(int value) {
    return value < 1 ? 1 : value;
  }

  private static void requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " is numbered from 1, got " + value);
    }
  }

  public String storageKey() {
    return test.storageKey() + "\n" + run + "\n" + shard + "\n" + attempt;
  }

  /** What a user reads, omitting the numbers that are 1. */
  public String displayName() {
    StringBuilder text = new StringBuilder(test.label());
    if (run > 1) {
      text.append(" run ").append(run);
    }
    if (shard > 1) {
      text.append(" shard ").append(shard);
    }
    if (attempt > 1) {
      text.append(" attempt ").append(attempt);
    }
    return text.toString();
  }

  @Override
  public String toString() {
    return displayName();
  }
}
