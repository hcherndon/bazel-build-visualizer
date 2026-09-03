package com.holtherndon.bazelviz.core.id;

import java.util.Objects;

/**
 * A test target, which is the thing a summary is about (plan 11.1).
 *
 * <p>One test target produces many results: Bazel runs it once per shard, once per {@code
 * --runs_per_test}, and again per retry when it is flaky. Those are {@link TestAttemptId}s. This is
 * the target they all belong to, and it is what the tests view lists.
 */
public record TestId(TargetId target) {

  public TestId {
    Objects.requireNonNull(target, "target");
  }

  public static TestId of(TargetId target) {
    return new TestId(target);
  }

  public String label() {
    return target.label();
  }

  public String storageKey() {
    return target.storageKey();
  }

  @Override
  public String toString() {
    return target.toString();
  }
}
