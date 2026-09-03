package com.holtherndon.bazelviz.core.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What each identifier does and does not promise. */
class IdentityTest {

  @Test
  @DisplayName("one label in two configurations is two targets")
  void configurationIsPartOfTargetIdentity() {
    TargetId forTarget = TargetId.of("//foo:bar", new ConfigurationId("abc123"));
    TargetId forExec = TargetId.of("//foo:bar", new ConfigurationId("def456"));

    // The same label is routinely built twice in one invocation, once for
    // the target platform and once for the tools that build it. Merging
    // them produces a row whose numbers belong to neither.
    assertThat(forTarget).isNotEqualTo(forExec);
    assertThat(forTarget.storageKey()).isNotEqualTo(forExec.storageKey());
  }

  @Test
  @DisplayName("an aspect is a different configured target from the target it applies to")
  void aspectsAreDistinct() {
    TargetId target = TargetId.of("//foo:proto", new ConfigurationId("abc123"));
    TargetId aspect = target.withAspect("//tools:cc_proto_aspect");

    assertThat(aspect).isNotEqualTo(target);
    assertThat(aspect.displayName()).contains("aspect");
  }

  @Test
  @DisplayName("a storage key cannot be forged by a label containing the separator")
  void storageKeysDoNotCollideOnPunctuation() {
    // Labels can contain colons, at-signs, spaces and plus signs, so any of
    // those as a separator lets one target's key equal another's. A newline
    // cannot appear in a label.
    TargetId first = TargetId.of("//a:b", new ConfigurationId("c"));
    TargetId second = TargetId.of("//a", new ConfigurationId("b:c"));

    assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
  }

  @Test
  @DisplayName("an absent configuration is the null configuration, not an error")
  void absentConfigurationIsNone() {
    assertThat(ConfigurationId.ofEventValue(null)).isEqualTo(ConfigurationId.NONE);
    assertThat(ConfigurationId.ofEventValue("")).isEqualTo(ConfigurationId.NONE);
    assertThat(ConfigurationId.NONE.isNull()).isTrue();
    assertThat(new ConfigurationId("abc").isNull()).isFalse();
  }

  @Test
  @DisplayName("an artifact path is joined from the prefixes and the name")
  void artifactPathsAreJoined() {
    ArtifactId artifact =
        ArtifactId.ofFile(List.of("bazel-out", "darwin_arm64-fastbuild", "bin"), "foo/bar.txt");

    assertThat(artifact.path()).isEqualTo("bazel-out/darwin_arm64-fastbuild/bin/foo/bar.txt");
    assertThat(artifact.fileName()).isEqualTo("bar.txt");
    assertThat(ArtifactId.ofFile(List.of(), "plain.txt").path()).isEqualTo("plain.txt");
  }

  @Test
  @DisplayName("a test attempt's run, shard and attempt default to one, not zero")
  void unsetAttemptNumbersMeanTheFirst() {
    TestId test = TestId.of(TargetId.of("//t:test", ConfigurationId.NONE));

    // Bazel omits these when they are 1, and protobuf reports an unset
    // int32 as 0. Treating 0 as a real value would give the first attempt a
    // key no other event shares and split one test's history in two.
    TestAttemptId first = TestAttemptId.ofEventValues(test, 0, 0, 0);

    assertThat(first.run()).isEqualTo(1);
    assertThat(first.shard()).isEqualTo(1);
    assertThat(first.attempt()).isEqualTo(1);
    assertThat(first.displayName()).isEqualTo("//t:test");
  }

  @Test
  @DisplayName("run, shard and attempt are independent parts of a test attempt's identity")
  void attemptsAreDistinguishedByAllThree() {
    TestId test = TestId.of(TargetId.of("//t:test", ConfigurationId.NONE));

    TestAttemptId run2 = TestAttemptId.ofEventValues(test, 2, 1, 1);
    TestAttemptId shard2 = TestAttemptId.ofEventValues(test, 1, 2, 1);
    TestAttemptId retry = TestAttemptId.ofEventValues(test, 1, 1, 2);

    assertThat(List.of(run2, shard2, retry)).doesNotHaveDuplicates();
    assertThat(run2.storageKey()).isNotEqualTo(shard2.storageKey());
    // Losing the attempt number would hide the failure that made a test
    // flaky, leaving a green result and no evidence.
    assertThat(retry.displayName()).contains("attempt 2");
  }

  @Test
  @DisplayName("empty identifiers are refused rather than stored as blanks")
  void emptyIdentifiersAreRefused() {
    assertThatThrownBy(() -> new ConfigurationId("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DepsetId("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ArtifactId("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TargetId("", ConfigurationId.NONE, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
