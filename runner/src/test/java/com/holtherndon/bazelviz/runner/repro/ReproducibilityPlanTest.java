package com.holtherndon.bazelviz.runner.repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import com.holtherndon.bazelviz.runner.command.EnvironmentInheritance;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ReproducibilityPlanTest {
  @Test
  void isolatesEveryHelperAndBuildAtOneBaseWithoutNormalLinks() {
    var plan = plan("build", "//pkg:target");
    assertThat(plan.canLaunch()).isTrue();
    assertThat(plan.build().startupArgs())
        .containsExactly(
            "--ignore_all_rc_files",
            "--output_base=/tmp/owned/output-base",
            "--host_jvm_args=-Xmx1g",
            "--max_idle_secs=15");
    assertThat(plan.build().commandArgs())
        .contains(
            "--disk_cache=",
            "--remote_cache=",
            "--remote_executor=",
            "--symlink_prefix=/",
            "--jobs=2",
            "--lockfile_mode=off");
    assertThat(plan.clean().startupArgs()).isEqualTo(plan.build().startupArgs());
    assertThat(plan.clean().commandArgs()).containsExactly("--symlink_prefix=/");
    assertThat(plan.clean().targets()).isEmpty();
    assertThat(plan.shutdown().commandArgs()).isEmpty();
    assertThat(plan.shutdown().targets()).isEmpty();
    assertThat(plan.changes())
        .anySatisfy(
            change -> assertThat(change.explanation()).contains("not an audit of your usual"));
    assertThat(plan.effectiveBlockers(plan.build())).isEmpty();
  }

  @Test
  void neverSilentlyReplacesUserStartupCacheOrRemoteExecutionPolicy() {
    for (List<String> args :
        List.of(
            List.of("--output_base=/user/base", "build", "//..."),
            List.of("build", "--disk_cache=/user/cache", "//..."),
            List.of("build", "--remote_executor=grpc://cluster", "//..."),
            List.of("build", "--remote_cache", "grpc://cache", "//..."),
            List.of("build", "--symlink_prefix=custom-", "//..."),
            List.of("build", "--spawn_strategy=remote,local", "//..."),
            List.of("build", "--config=ci", "//..."),
            List.of("build", "--jobs=100", "//..."))) {
      assertThat(plan(args.toArray(String[]::new)).canLaunch()).as(args.toString()).isFalse();
    }
    assertThat(
            plan("build", "--disk_cache=", "--remote_executor=", "--jobs=2", "//...").canLaunch())
        .isTrue();
  }

  @Test
  void excludesCommandsWithUnreviewedSideEffectsAndInvalidPaths() {
    assertThat(plan("test", "//...").canLaunch()).isFalse();
    assertThat(plan("run", "//:server").canLaunch()).isFalse();
    assertThat(plan("clean").canLaunch()).isFalse();
    assertThat(plan("build", "//...", "--", "argument").canLaunch()).isFalse();
    assertThatThrownBy(() -> ReproducibilityPlan.controlled(command("build"), "/"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ReproducibilityPlan.controlled(command("build"), "/tmp/x/../output-base"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresTheMeasuredReleaseAndObservedBuildFlags() {
    Map<String, FlagSpec> flags = new LinkedHashMap<>();
    for (String name :
        List.of(
            "disk_cache",
            "remote_cache",
            "remote_executor",
            "symlink_prefix",
            "jobs",
            "lockfile_mode")) {
      flags.put(name, FlagSpec.of(name, Set.of("build")));
    }
    var known =
        BazelCapabilities.fromFlags(
            "bazel 9.2.0",
            Optional.of("9.2.0"),
            BazelCapabilities.DetectionMethod.FLAGS_PROTO,
            flags,
            List.of());
    assertThat(plan("build", "//...").capabilityBlockers(known)).isEmpty();
    var other =
        BazelCapabilities.fromFlags(
            "bazel 10.0.0",
            Optional.of("10.0.0"),
            BazelCapabilities.DetectionMethod.FLAGS_PROTO,
            flags,
            List.of());
    assertThat(plan("build", "//...").capabilityBlockers(other))
        .anyMatch(value -> value.contains("9.2.0"));
    assertThat(plan("build", "//...").capabilityBlockers(BazelCapabilities.unprobed("failed")))
        .isNotEmpty();
  }

  @Test
  void instrumentationReplanningCannotDiscardIsolation() {
    var plan = plan("build", "//...");
    assertThat(plan.effectiveBlockers(plan.build().toBuilder().startupArgs(List.of()).build()))
        .isNotEmpty();
    assertThat(plan.effectiveBlockers(plan.build().toBuilder().commandArgs(List.of()).build()))
        .isNotEmpty();
    for (BazelCommand altered :
        List.of(
            plan.build().toBuilder().executable(Path.of("/another/bazel")).build(),
            plan.build().toBuilder().targets(List.of("//other:target")).build(),
            plan.build().toBuilder()
                .environmentOverrides(Map.of("MODE", Optional.of("other")))
                .build(),
            plan.build().toBuilder().inheritance(EnvironmentInheritance.NONE).build(),
            plan.build().toBuilder().argsAfterDoubleDash(List.of("unreviewed")).build())) {
      assertThat(plan.effectiveBlockers(altered)).isNotEmpty();
    }
  }

  private static ReproducibilityPlan plan(String... args) {
    return ReproducibilityPlan.controlled(command(args), "/tmp/owned/output-base");
  }

  private static BazelCommand command(String... args) {
    return new CommandLineParser()
        .parse(Path.of("/usr/bin/bazel"), Path.of("/workspace"), List.of(args));
  }
}
