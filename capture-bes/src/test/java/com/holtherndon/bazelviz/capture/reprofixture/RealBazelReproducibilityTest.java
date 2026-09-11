package com.holtherndon.bazelviz.capture.reprofixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.holtherndon.bazelviz.testsupport.bazel.BazelBinary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins repeat-build assumptions against one real Bazel version, without cleaning the repository
 * running the test. These are protocol fixtures, not a production hermeticity verdict or a version
 * sweep. Binary logs keep the assertions independent of the application's execution-log importer.
 */
@Tag("real-bazel")
class RealBazelReproducibilityTest {

  @Test
  @DisplayName("clean uncached builds expose output drift, while disk-cache reuse masks it")
  void comparesExecutionEvidenceAndPreservesExistingWorkspaceLinks(
      @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory) throws Exception {
    Optional<Path> bazel = BazelBinary.find();
    assumeTrue(bazel.isPresent(), BazelBinary::whyUnavailable);
    assumeTrue(Files.isReadable(Path.of("/dev/urandom")), "fixture needs a POSIX execution host");

    try (PrivateBazel fixture = new PrivateBazel(directory, bazel.orElseThrow())) {
      assertThat(fixture.command(List.of("version", "--gnu_format")).lines())
          .contains("bazel 9.2.0");

      Map<String, SpawnExec> first = fixture.cleanBuild("A", false);
      Map<String, SpawnExec> second = fixture.cleanBuild("B", false);
      assertActuallyExecuted(first);
      assertActuallyExecuted(second);
      for (String target : first.keySet()) {
        assertSameRecordedRecipe(first.get(target), second.get(target));
        // Timing is not reproducibility evidence. Even the stable action has a different start.
        assertThat(first.get(target).getMetrics().getStartTime())
            .isNotEqualTo(second.get(target).getMetrics().getStartTime());
      }

      SpawnExec stableA = first.get("//:stable");
      SpawnExec stableB = second.get("//:stable");
      assertSameInputs(stableA, stableB);
      assertThat(stableA.getActualOutputsList()).isEqualTo(stableB.getActualOutputsList());

      SpawnExec randomA = first.get("//:random");
      SpawnExec randomB = second.get("//:random");
      assertSameInputs(randomA, randomB);
      File outputA = soleOutput(randomA);
      File outputB = soleOutput(randomB);
      assertThat(outputA.getPath()).isEqualTo(outputB.getPath());
      assertThat(outputA.getDigest().getHash()).isNotEqualTo(outputB.getDigest().getHash());
      assertThat(outputA.getDigest().getSizeBytes()).isEqualTo(32);
      assertThat(outputB.getDigest().getSizeBytes()).isEqualTo(32);

      SpawnExec downstreamA = first.get("//:downstream");
      SpawnExec downstreamB = second.get("//:downstream");
      assertThat(inputAt(downstreamA, outputA.getPath()).getDigest())
          .isEqualTo(outputA.getDigest());
      assertThat(inputAt(downstreamB, outputB.getPath()).getDigest())
          .isEqualTo(outputB.getDigest());
      assertThat(soleOutput(downstreamA).getDigest()).isEqualTo(outputA.getDigest());
      assertThat(soleOutput(downstreamB).getDigest()).isEqualTo(outputB.getDigest());
      assertThat(withoutInput(downstreamA, outputA.getPath()))
          .containsExactlyInAnyOrderElementsOf(withoutInput(downstreamB, outputB.getPath()));

      Map<String, SpawnExec> cacheSeed = fixture.cleanBuild("C", true);
      Map<String, SpawnExec> cacheReuse = fixture.cleanBuild("D", true);
      assertActuallyExecuted(cacheSeed);
      for (String target : cacheSeed.keySet()) {
        SpawnExec original = cacheSeed.get(target);
        SpawnExec cached = cacheReuse.get(target);
        assertSameRecordedRecipe(original, cached);
        assertSameInputs(original, cached);
        assertThat(cached.getCacheHit()).describedAs(target).isTrue();
        assertThat(cached.getRunner()).isEqualTo("disk cache hit");
        assertThat(cached.getStatus()).isEmpty();
        assertThat(cached.getExitCode()).isZero();
        assertThat(cached.getActualOutputsList()).isEqualTo(original.getActualOutputsList());
      }
      fixture.assertWorkspaceLinksUnchanged();
    }
  }

  private static void assertActuallyExecuted(Map<String, SpawnExec> spawns) {
    assertThat(spawns).containsOnlyKeys("//:stable", "//:random", "//:downstream");
    for (SpawnExec spawn : spawns.values()) {
      assertThat(spawn.getCacheHit()).describedAs(spawn.getTargetLabel()).isFalse();
      assertThat(spawn.getRunner()).isNotEmpty().doesNotContain("cache hit");
      assertThat(spawn.getStatus()).isEmpty();
      assertThat(spawn.getExitCode()).isZero();
      assertThat(spawn.getMnemonic()).isEqualTo("Genrule");
      assertThat(spawn.hasMetrics()).isTrue();
      assertThat(spawn.getMetrics().hasStartTime()).isTrue();
      soleOutput(spawn);
    }
  }

  private static void assertSameRecordedRecipe(SpawnExec first, SpawnExec second) {
    assertThat(first.getCommandArgsList()).isEqualTo(second.getCommandArgsList());
    assertThat(first.getEnvironmentVariablesList())
        .containsExactlyInAnyOrderElementsOf(second.getEnvironmentVariablesList());
    assertThat(first.getListedOutputsList()).isEqualTo(second.getListedOutputsList());
    assertThat(first.getTimeoutMillis()).isEqualTo(second.getTimeoutMillis());
    // This local fixture need not emit platform properties. Absent properties do not prove
    // platform equivalence; do not replace that missing evidence with an equality assertion.
  }

  private static void assertSameInputs(SpawnExec first, SpawnExec second) {
    assertThat(first.getInputsList()).containsExactlyInAnyOrderElementsOf(second.getInputsList());
  }

  private static File soleOutput(SpawnExec spawn) {
    assertThat(spawn.getActualOutputsList()).hasSize(1);
    File output = spawn.getActualOutputs(0);
    assertThat(output.hasDigest()).isTrue();
    assertThat(output.getDigest().getHash()).isNotEmpty();
    return output;
  }

  private static File inputAt(SpawnExec spawn, String path) {
    List<File> matching =
        spawn.getInputsList().stream().filter(f -> f.getPath().equals(path)).toList();
    assertThat(matching).hasSize(1);
    return matching.getFirst();
  }

  private static List<File> withoutInput(SpawnExec spawn, String path) {
    return spawn.getInputsList().stream().filter(f -> !f.getPath().equals(path)).toList();
  }

  private static final class PrivateBazel implements AutoCloseable {
    private final Path workspace;
    private final Path outputBase;
    private final Path evidence;
    private final Path diskCache;
    private final List<String> startup;
    private final Map<Path, Path> existingLinks = new LinkedHashMap<>();
    private int commandIndex;

    PrivateBazel(Path directory, Path bazel) throws IOException {
      Path root = directory.toRealPath();
      workspace = Files.createDirectory(root.resolve("ws"));
      outputBase = Files.createDirectory(root.resolve("private-output-base"));
      evidence = Files.createDirectory(root.resolve("evidence"));
      diskCache = Files.createDirectory(root.resolve("disk-cache"));
      Files.writeString(
          workspace.resolve("MODULE.bazel"), "module(name = \"reproducibility_fixture\")\n");
      Files.writeString(
          workspace.resolve("BUILD.bazel"),
          """
          genrule(name = "stable", outs = ["stable.txt"], cmd = "echo stable > $@")
          genrule(name = "random", outs = ["random.bin"],
                  cmd = "dd if=/dev/urandom bs=32 count=1 2>/dev/null > $@")
          genrule(name = "downstream", srcs = [":random"], outs = ["downstream.bin"],
                  cmd = "cat $(location :random) > $@")
          """);
      Path normalOutputs = Files.createDirectory(root.resolve("normal-outputs"));
      for (String name : List.of("bazel-bin", "bazel-out", "bazel-testlogs", "bazel-ws")) {
        Path target = Files.createDirectory(normalOutputs.resolve(name));
        Files.writeString(target.resolve("keep.txt"), "not owned by the audit\n");
        Path link = Files.createSymbolicLink(workspace.resolve(name), target);
        existingLinks.put(link, target);
      }
      startup =
          List.of(
              bazel.toString(),
              "--ignore_all_rc_files",
              "--host_jvm_args=-Xmx1g",
              "--max_idle_secs=15",
              "--output_user_root=" + root.resolve("private-user-root"),
              "--output_base=" + outputBase);
    }

    Map<String, SpawnExec> cleanBuild(String name, boolean useDiskCache) throws Exception {
      // Every clean is visibly scoped to the same private base, never the test runner's base.
      assertThat(command(List.of("info", "output_base")).lines()).contains(outputBase.toString());
      command(List.of("clean", "--symlink_prefix=/"));
      assertWorkspaceLinksUnchanged();
      Path log = evidence.resolve(name + ".bin");
      command(
          List.of(
              "build",
              "//...",
              "--jobs=2",
              "--lockfile_mode=off",
              "--symlink_prefix=/",
              "--remote_cache=",
              "--remote_executor=",
              "--disk_cache=" + (useDiskCache ? diskCache : ""),
              "--execution_log_binary_file=" + log));
      assertWorkspaceLinksUnchanged();
      assertThat(Files.size(log)).isPositive().isLessThan(1024 * 1024);
      Map<String, SpawnExec> spawns = new LinkedHashMap<>();
      try (InputStream input = Files.newInputStream(log)) {
        SpawnExec spawn;
        while ((spawn = SpawnExec.parseDelimitedFrom(input)) != null) {
          assertThat(spawns).doesNotContainKey(spawn.getTargetLabel());
          spawns.put(spawn.getTargetLabel(), spawn);
        }
      }
      assertThat(spawns).containsOnlyKeys("//:stable", "//:random", "//:downstream");
      return spawns;
    }

    void assertWorkspaceLinksUnchanged() throws IOException {
      for (Map.Entry<Path, Path> entry : existingLinks.entrySet()) {
        assertThat(Files.isSymbolicLink(entry.getKey()))
            .describedAs(entry.getKey().toString())
            .isTrue();
        assertThat(Files.readSymbolicLink(entry.getKey())).isEqualTo(entry.getValue());
        assertThat(Files.readString(entry.getValue().resolve("keep.txt")))
            .isEqualTo("not owned by the audit\n");
      }
    }

    String command(List<String> args) throws IOException, InterruptedException {
      List<String> argv = new ArrayList<>(startup);
      argv.addAll(args);
      Path transcript = evidence.resolve("command-" + commandIndex++ + ".txt");
      ProcessBuilder builder =
          new ProcessBuilder(argv)
              .directory(workspace.toFile())
              .redirectErrorStream(true)
              .redirectOutput(transcript.toFile());
      builder.environment().put(BazelBinary.VERSION_ENV, "9.2.0");
      Process process = builder.start();
      try {
        assertThat(process.waitFor(2, TimeUnit.MINUTES))
            .describedAs("timed out: %s; transcript: %s", args, transcript)
            .isTrue();
        String output = Files.readString(transcript, StandardCharsets.UTF_8);
        assertThat(process.exitValue()).describedAs("%s failed:%n%s", args, output).isZero();
        return output;
      } finally {
        if (process.isAlive()) {
          process.destroy();
          if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
          }
        }
      }
    }

    @Override
    public void close() throws IOException, InterruptedException {
      // Evidence is outside the base. Shutdown uses exactly the same owned startup context even
      // after assertion failure. Cleanup failure retains the private tree rather than deleting
      // files underneath a possibly live server; its idle lifetime is capped as a final safeguard.
      command(List.of("shutdown"));
      assertWorkspaceLinksUnchanged();
    }
  }
}
