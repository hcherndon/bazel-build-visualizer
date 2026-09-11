package com.holtherndon.bazelviz.enrich.repro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.EnvironmentVariable;
import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.protobuf.MessageLite;
import com.google.protobuf.UnknownFieldSet;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison;
import com.holtherndon.bazelviz.core.repro.ReproComparison.Finding;
import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionLogComparisonTest {
  @TempDir Path temp;

  @Test
  void detectsOriginalDivergenceAndExactGeneratedInputPropagation() throws Exception {
    Path a =
        binary(
            "a",
            spawn("stable", "same"),
            spawn("random", "one"),
            spawn("consumer", "first").addInputs(file("random.out", "one")));
    Path b =
        binary(
            "b",
            spawn("consumer", "second").addInputs(file("random.out", "two")),
            spawn("random", "two"),
            spawn("stable", "same"));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(3, comparison.summary().matched());
      assertEquals(1, comparison.summary().unchanged());
      assertEquals(1, comparison.summary().outputDivergences());
      assertEquals(1, comparison.summary().downstream());
      var rows = comparison.page(FilterExpression.ALL, 0, 10).rows();
      assertEquals(Finding.OUTPUT_DIVERGENCE, rows.get(1).finding());
      assertEquals(Finding.DOWNSTREAM, rows.get(2).finding());
    }
    try (var children = Files.list(temp.resolve("scratch"))) {
      assertEquals(0, children.count());
    }
    assertTrue(Files.exists(a));
    assertTrue(Files.exists(b));
  }

  @Test
  void cachesAndMissingPlatformNeverPass() throws Exception {
    Path a = binary("a", spawn("cached", "same"), spawn("unknown", "same").clearPlatform());
    Path b =
        binary(
            "b",
            spawn("cached", "same").setCacheHit(true).setRunner("disk cache hit"),
            spawn("unknown", "same").clearPlatform());
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(0, comparison.summary().unchanged());
      assertEquals(2, comparison.summary().inconclusive());
      assertTrue(
          comparison.summary().coverageNotes().stream().anyMatch(s -> s.contains("platform")));
    }
  }

  @Test
  void orderedArgsDifferButEnvironmentOrderDoesNotAndSecretsAreMasked() throws Exception {
    var envA = EnvironmentVariable.newBuilder().setName("TOKEN").setValue("private-token-a");
    var envB = EnvironmentVariable.newBuilder().setName("TOKEN").setValue("private-token-b");
    var path = EnvironmentVariable.newBuilder().setName("PATH").setValue("/bin");
    Path a =
        binary(
            "a",
            spawn("env", "same").addEnvironmentVariables(envA).addEnvironmentVariables(path),
            spawn("args", "same").addCommandArgs("first").addCommandArgs("second"));
    Path b =
        binary(
            "b",
            spawn("env", "same").addEnvironmentVariables(path).addEnvironmentVariables(envB),
            spawn("args", "same").addCommandArgs("second").addCommandArgs("first"));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(2, comparison.summary().drift());
      var rows = comparison.page(FilterExpression.ALL, 0, 10).rows();
      var details = comparison.details(rows.getFirst().id(), 10);
      assertEquals(1, details.total());
      assertEquals("TOKEN", details.differences().getFirst().field());
      assertFalse(details.toString().contains("private-token"));
    }
  }

  @Test
  void environmentReorderingIsCanonicalButDuplicateValuesArePreserved() throws Exception {
    var one = EnvironmentVariable.newBuilder().setName("X").setValue("1");
    var two = EnvironmentVariable.newBuilder().setName("Y").setValue("2");
    Path a =
        binary(
            "a",
            spawn("same", "x").addEnvironmentVariables(one).addEnvironmentVariables(two),
            spawn("duplicate", "x").addEnvironmentVariables(one).addEnvironmentVariables(one));
    Path b =
        binary(
            "b",
            spawn("same", "x").addEnvironmentVariables(two).addEnvironmentVariables(one),
            spawn("duplicate", "x").addEnvironmentVariables(one));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().unchanged());
      assertEquals(1, comparison.summary().drift());
    }
  }

  @Test
  void equivalentCompactDagShapesAndIdsCompareEqual() throws Exception {
    Path a =
        compact(
            "a",
            header(),
            compactFile(1, "source", "x"),
            compactFile(2, "out", "y"),
            set(3, List.of(1), List.of()),
            compactSpawn(3, 2));
    Path b =
        compact(
            "b",
            header(),
            compactFile(90, "source", "x"),
            compactFile(91, "out", "y"),
            set(80, List.of(90), List.of()),
            set(70, List.of(90), List.of(80)),
            compactSpawn(70, 91));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().unchanged());
    }
  }

  @Test
  void compactTreeMembersAndSymlinkTargetsParticipateInOutputComparison() throws Exception {
    Path a = compact("a", header(), tree(1, "aaa"), symlink(2, "first"), compactSpawn(0, 1, 2));
    Path b = compact("b", header(), tree(9, "bbb"), symlink(8, "second"), compactSpawn(0, 9, 8));
    try (ReproComparison comparison = open(a, b)) {
      var row = comparison.page(FilterExpression.ALL, 0, 10).rows().getFirst();
      assertEquals(Finding.OUTPUT_DIVERGENCE, row.finding());
      var details = comparison.details(row.id(), 10);
      assertEquals(2, details.total());
    }
  }

  @Test
  void unsupportedRunfilesAndMissingReferencesStayInconclusive() throws Exception {
    var runfiles =
        ExecLogEntry.newBuilder()
            .setId(1)
            .setRunfilesTree(ExecLogEntry.RunfilesTree.newBuilder().setPath("x.runfiles"));
    Path a =
        compact(
            "a",
            header(),
            runfiles,
            compactFile(2, "out", "y"),
            set(3, List.of(1, 99), List.of()),
            compactSpawn(3, 2));
    Path b =
        compact(
            "b",
            header(),
            runfiles,
            compactFile(2, "out", "y"),
            set(3, List.of(1, 99), List.of()),
            compactSpawn(3, 2));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().inconclusive());
      assertEquals(0, comparison.summary().unchanged());
    }
  }

  @Test
  void duplicateObservationsAreNotPairedByPosition() throws Exception {
    Path a = binary("a", spawn("same", "x"), spawn("same", "x"));
    Path b = binary("b", spawn("same", "x"), spawn("same", "x"));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(0, comparison.summary().matched());
      assertEquals(4, comparison.page(FilterExpression.ALL, 0, 10).total());
      assertEquals(4, comparison.summary().inconclusive());
    }
  }

  @Test
  void missingDigestIsNotAnEmptyFile() throws Exception {
    var missing = File.newBuilder().setPath("source");
    Path a = binary("a", spawn("same", "x").addInputs(missing));
    Path b = binary("b", spawn("same", "x").addInputs(missing));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().inconclusive());
    }
  }

  @Test
  void filtersAndDetailPagesUseExactTotals() throws Exception {
    Path a =
        binary("a", spawn("one", "x").addCommandArgs("a").addCommandArgs("b"), spawn("two", "x"));
    Path b =
        binary("b", spawn("one", "x").addCommandArgs("c").addCommandArgs("d"), spawn("two", "x"));
    try (ReproComparison comparison = open(a, b)) {
      var filter =
          new FilterExpression.Condition(
              "target", FilterExpression.Operator.REGEX, List.of(":one$"));
      var page = comparison.page(filter, 0, 1);
      assertEquals(1, page.total());
      var first = comparison.details(page.rows().getFirst().id(), 0, 1);
      var next = comparison.details(page.rows().getFirst().id(), 1, 1);
      assertEquals(2, first.total());
      assertTrue(first.truncated());
      assertFalse(next.truncated());
      assertTrue(comparison.page(filter, page.rows().getFirst().id(), 1).rows().isEmpty());
    }
  }

  @Test
  void sourceRecordExpandedAndWorkLimitsRefuseWithoutPublishing() throws Exception {
    Path a = binary("a", spawn("one", "x"));
    Path b = binary("b", spawn("one", "x"));
    var normal = ComparisonLimits.defaults();
    for (ComparisonLimits limits :
        List.of(
            new ComparisonLimits(
                1,
                normal.expandedBytes(),
                normal.recordBytes(),
                normal.records(),
                normal.databaseBytes(),
                normal.sqlSteps(),
                10),
            new ComparisonLimits(
                normal.sourceBytes(),
                1,
                normal.recordBytes(),
                normal.records(),
                normal.databaseBytes(),
                normal.sqlSteps(),
                10),
            new ComparisonLimits(
                normal.sourceBytes(),
                normal.expandedBytes(),
                1,
                normal.records(),
                normal.databaseBytes(),
                normal.sqlSteps(),
                10),
            new ComparisonLimits(
                normal.sourceBytes(),
                normal.expandedBytes(),
                normal.recordBytes(),
                normal.records(),
                normal.databaseBytes(),
                1,
                10))) {
      assertThrows(
          IOException.class,
          () -> ExecutionLogComparison.open(a, b, temp.resolve("scratch"), () -> false, limits));
    }
    assertThrows(
        IOException.class,
        () -> ExecutionLogComparison.open(a, b, temp.resolve("scratch"), () -> true));
    try (var files = Files.list(temp.resolve("scratch"))) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void malformedAndSameFileRefuseRatherThanPublishEmptySuccess() throws Exception {
    Path a = binary("a", spawn("one", "x"));
    Path b = temp.resolve("bad");
    Files.write(b, new byte[] {100, 1, 2});
    assertThrows(IOException.class, () -> open(a, b));
    assertThrows(IOException.class, () -> open(a, a));
  }

  @Test
  void zeroSpawnPairDoesNotClaimSuccess() throws Exception {
    Path a = compact("a", header());
    Path b = compact("b", header());
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(0, comparison.summary().unchanged());
      assertTrue(
          comparison.summary().coverageNotes().stream().anyMatch(s -> s.contains("No spawns")));
    }
  }

  @Test
  void outputSetChangesUseAnUnambiguousSharedOutputAnchor() throws Exception {
    Path a = binary("a", spawn("same", "x"));
    Path b =
        binary(
            "b", spawn("same", "x").addListedOutputs("extra").addActualOutputs(file("extra", "y")));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().matched());
      var row = comparison.page(FilterExpression.ALL, 0, 10).rows().getFirst();
      assertEquals(Finding.RECIPE_DRIFT, row.finding());
      assertTrue(row.reason().contains("shared output"));
      assertTrue(row.outputsChanged());
    }
  }

  @Test
  void changedActionKeysAreEvidenceNotMatchingIdentityOrPublishedSecretHashes() throws Exception {
    Path a = binary("a", spawn("same", "x").setDigest(digest("private-key-one")));
    Path b = binary("b", spawn("same", "x").setDigest(digest("private-key-two")));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().matched());
      var row = comparison.page(FilterExpression.ALL, 0, 10).rows().getFirst();
      assertEquals(Finding.CACHE_IDENTITY_DRIFT, row.finding());
      assertFalse(comparison.details(row.id(), 10).toString().contains("private-key"));
    }
  }

  @Test
  void duplicateFileCoverageMergesConservativelyIndependentOfEntryOrder() throws Exception {
    var unknown = compactFile(3, "source", "x");
    unknown.setFile(
        unknown.getFile().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build()));
    Path a =
        compact(
            "a",
            header(),
            compactFile(1, "source", "x"),
            unknown,
            compactFile(2, "out", "y"),
            set(4, List.of(1, 3), List.of()),
            compactSpawn(4, 2));
    Path b =
        compact(
            "b",
            header(),
            unknown,
            compactFile(1, "source", "x"),
            compactFile(2, "out", "y"),
            set(4, List.of(3, 1), List.of()),
            compactSpawn(4, 2));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().inconclusive());
      assertEquals(0, comparison.summary().unchanged());
    }
  }

  @Test
  void cyclesInvalidSetKindsAndConflictingEnvironmentKeysCannotBeComplete() throws Exception {
    Path a =
        compact(
            "a",
            header(),
            compactFile(1, "source", "x"),
            compactFile(2, "out", "y"),
            set(3, List.of(1), List.of(4)),
            set(4, List.of(), List.of(3)),
            compactSpawn(3, 2));
    Path b =
        compact(
            "b",
            header(),
            compactFile(1, "source", "x"),
            compactFile(2, "out", "y"),
            set(3, List.of(1), List.of(4)),
            set(4, List.of(), List.of(3)),
            compactSpawn(3, 2));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(1, comparison.summary().inconclusive());
    }
    Path c =
        compact(
            "c",
            header(),
            compactFile(1, "source", "x"),
            compactFile(2, "out", "y"),
            compactSpawn(1, 2));
    Path d =
        compact(
            "d",
            header(),
            compactFile(1, "source", "x"),
            compactFile(2, "out", "y"),
            compactSpawn(1, 2));
    try (ReproComparison comparison = open(c, d)) {
      assertEquals(1, comparison.summary().inconclusive());
    }
    var one = EnvironmentVariable.newBuilder().setName("X").setValue("1");
    var two = EnvironmentVariable.newBuilder().setName("X").setValue("2");
    Path e =
        binary("e", spawn("same", "x").addEnvironmentVariables(one).addEnvironmentVariables(two));
    Path f =
        binary("f", spawn("same", "x").addEnvironmentVariables(two).addEnvironmentVariables(one));
    try (ReproComparison comparison = open(e, f)) {
      assertEquals(1, comparison.summary().inconclusive());
      assertEquals(0, comparison.summary().unchanged());
    }
  }

  @Test
  void ambiguousProducerIsNotUsedToAttributeDownstreamChanges() throws Exception {
    Path a =
        binary(
            "a",
            spawn("producer", "old"),
            spawn("producer", "old").setTargetLabel("//other:producer"),
            spawn("consumer", "old").addInputs(file("producer.out", "old")));
    Path b =
        binary(
            "b",
            spawn("producer", "new"),
            spawn("producer", "new").setTargetLabel("//other:producer"),
            spawn("consumer", "new").addInputs(file("producer.out", "new")));
    try (ReproComparison comparison = open(a, b)) {
      assertEquals(0, comparison.summary().downstream());
    }
  }

  @Test
  void verificationBindsCompactInvocationAndChecksumAndRejectsMalformedStreams() throws Exception {
    Path a =
        compact(
            "a",
            ExecLogEntry.newBuilder()
                .setInvocation(
                    ExecLogEntry.Invocation.newBuilder()
                        .setHashFunctionName("SHA-256")
                        .setId("build-a")),
            compactFile(2, "out", "y"),
            compactSpawn(0, 2));
    var verified =
        ExecutionLogComparison.verify(a, temp.resolve("scratch"), "build-a", () -> false);
    assertTrue(verified.invocationMatched());
    assertEquals(3, verified.records());
    assertEquals(1, verified.spawns());
    assertEquals(64, verified.sha256().length());
    assertThrows(
        IOException.class,
        () -> ExecutionLogComparison.verify(a, temp.resolve("scratch"), "build-b", () -> false));
    Path b = binary("b", spawn("same", "x"));
    assertFalse(
        ExecutionLogComparison.verify(b, temp.resolve("scratch"), "build-a", () -> false)
            .invocationMatched());
    assertThrows(
        IOException.class,
        () -> ExecutionLogComparison.verify(temp, temp.resolve("scratch"), "build-a", () -> false));
  }

  @Test
  void excessiveCellsRefuseWithoutReturningAPartialPage() throws Exception {
    String name = "x".repeat(ComparisonLimits.MAX_CELL_CHARACTERS + 1);
    Path a = binary("a", spawn("same", "x").setTargetLabel(name));
    Path b = binary("b", spawn("same", "x").setTargetLabel(name));
    try (ReproComparison comparison = open(a, b)) {
      assertThrows(IOException.class, () -> comparison.page(FilterExpression.ALL, 0, 10));
    }
  }

  @Test
  void canonicalManifestAndDetailPagingUseCoveringIndexesNotExternalSorts() throws Exception {
    Path a = binary("a", spawn("same", "x"));
    Path b = binary("b", spawn("same", "y"));
    try (ExecutionLogComparison comparison = (ExecutionLogComparison) open(a, b)) {
      for (String query :
          List.of(
              "SELECT section,field,value FROM recipe WHERE side=0 AND spawn=1 AND"
                  + " section<>'Evidence' ORDER BY section,field,value",
              "SELECT path,kind,value FROM manifest WHERE section='Inputs' ORDER BY"
                  + " path,kind,value",
              "SELECT section,path,kind,value FROM manifest ORDER BY section,path,kind,value",
              "SELECT * FROM differences ORDER BY section,field,position LIMIT 100")) {
        assertTrue(
            comparison.queryPlan(query).stream().noneMatch(s -> s.contains("USE TEMP B-TREE")),
            query);
      }
    }
  }

  @Test
  void zstdPreflightChecksEveryFrameBeforeDecoderAllocation() throws Exception {
    Path valid = compact("valid", header());
    ZstdFrameGuard.verify(valid, () -> false);
    Path excessiveWindow = temp.resolve("window");
    Files.write(excessiveWindow, new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0, 112});
    assertThrows(IOException.class, () -> ZstdFrameGuard.verify(excessiveWindow, () -> false));
    Path excessiveSingle = temp.resolve("single");
    Files.write(
        excessiveSingle,
        new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, (byte) 0xa0, 0, 0, 0, 1});
    assertThrows(IOException.class, () -> ZstdFrameGuard.verify(excessiveSingle, () -> false));
    Path concatenated = temp.resolve("concat");
    try (var out = Files.newOutputStream(concatenated)) {
      Files.copy(valid, out);
      Files.copy(valid, out);
    }
    ZstdFrameGuard.verify(concatenated, () -> false);
    try (var out = Files.newOutputStream(concatenated)) {
      Files.copy(valid, out);
      Files.copy(excessiveWindow, out);
    }
    assertThrows(IOException.class, () -> ZstdFrameGuard.verify(concatenated, () -> false));
    assertThrows(IOException.class, () -> ZstdFrameGuard.verify(valid, () -> true));
  }

  @Test
  void savedAuditHashesBindTheActualComparedSnapshots() throws Exception {
    Path a = binary("a", spawn("same", "x"));
    Path b = binary("b", spawn("same", "y"));
    String hashA =
        ExecutionLogComparison.verify(a, temp.resolve("scratch"), "", () -> false).sha256();
    String hashB =
        ExecutionLogComparison.verify(b, temp.resolve("scratch"), "", () -> false).sha256();
    try (ReproComparison comparison =
        ExecutionLogComparison.open(
            a, b, temp.resolve("scratch"), () -> false, Optional.of(hashA), Optional.of(hashB))) {
      assertEquals(1, comparison.summary().outputDivergences());
    }
    binary("a", spawn("same", "changed"));
    assertThrows(
        IOException.class,
        () ->
            ExecutionLogComparison.open(
                a,
                b,
                temp.resolve("scratch"),
                () -> false,
                Optional.of(hashA),
                Optional.of(hashB)));
  }

  @Test
  void verificationReportsActualCacheRemoteAndUnknownRunnerEvidence() throws Exception {
    Path log =
        binary(
            "runners",
            spawn("cached", "x").setCacheHit(true).clearPlatform(),
            spawn("remote", "x").setRunner("remote"),
            spawn("unknown", "x").setRunner("custom"),
            spawn("local", "x").setRunner("linux-sandbox"));
    var verification = ExecutionLogComparison.verify(log, temp.resolve("scratch"), "", () -> false);
    assertEquals(1, verification.cachedSpawns());
    assertEquals(1, verification.remoteSpawns());
    assertEquals(1, verification.unknownRunnerSpawns());
  }

  @Test
  void zstdPreflightRejectsTruncatedBlocksAndFrameCountOverflow() throws Exception {
    Path truncated = temp.resolve("truncated");
    Files.write(truncated, new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0, 0, 81, 0, 0, 1, 2});
    assertThrows(IOException.class, () -> ZstdFrameGuard.verify(truncated, () -> false));
    byte[] emptyFrame = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 32, 0, 1, 0, 0};
    Path excessive = temp.resolve("frames");
    try (var out = Files.newOutputStream(excessive)) {
      for (long i = 0; i <= ZstdFrameGuard.MAX_FRAMES; i++) {
        out.write(emptyFrame);
      }
    }
    assertThrows(IOException.class, () -> ZstdFrameGuard.verify(excessive, () -> false));
  }

  private ReproComparison open(Path a, Path b) throws IOException {
    return ExecutionLogComparison.open(a, b, temp.resolve("scratch"), () -> false);
  }

  private Path binary(String name, SpawnExec.Builder... spawns) throws IOException {
    Path path = temp.resolve(name);
    try (var out = Files.newOutputStream(path)) {
      for (var spawn : spawns) {
        spawn.build().writeDelimitedTo(out);
      }
    }
    return path;
  }

  private Path compact(String name, MessageLite.Builder... entries) throws IOException {
    Path path = temp.resolve(name);
    try (var out = new ZstdOutputStream(Files.newOutputStream(path))) {
      for (var entry : entries) {
        entry.build().writeDelimitedTo(out);
      }
    }
    return path;
  }

  private static SpawnExec.Builder spawn(String name, String output) {
    return SpawnExec.newBuilder()
        .setTargetLabel("//pkg:" + name)
        .setMnemonic("Genrule")
        .addCommandArgs("tool")
        .setRunner("local")
        .setPlatform(Platform.getDefaultInstance())
        .addListedOutputs(name + ".out")
        .addActualOutputs(file(name + ".out", output));
  }

  private static File.Builder file(String path, String hash) {
    return File.newBuilder().setPath(path).setDigest(digest(hash));
  }

  private static Digest.Builder digest(String hash) {
    return Digest.newBuilder().setHash(hash).setSizeBytes(1).setHashFunctionName("SHA-256");
  }

  private static ExecLogEntry.Builder header() {
    return ExecLogEntry.newBuilder()
        .setInvocation(ExecLogEntry.Invocation.newBuilder().setHashFunctionName("SHA-256"));
  }

  private static ExecLogEntry.Builder compactFile(int id, String path, String hash) {
    return ExecLogEntry.newBuilder()
        .setId(id)
        .setFile(ExecLogEntry.File.newBuilder().setPath(path).setDigest(digest(hash)));
  }

  private static ExecLogEntry.Builder set(int id, List<Integer> files, List<Integer> sets) {
    return ExecLogEntry.newBuilder()
        .setId(id)
        .setInputSet(
            ExecLogEntry.InputSet.newBuilder().addAllInputIds(files).addAllTransitiveSetIds(sets));
  }

  private static ExecLogEntry.Builder compactSpawn(int inputs, int... outputs) {
    var spawn =
        ExecLogEntry.Spawn.newBuilder()
            .setTargetLabel("//pkg:action")
            .setMnemonic("Genrule")
            .addArgs("tool")
            .setRunner("local")
            .setInputSetId(inputs)
            .setPlatform(Platform.getDefaultInstance());
    for (int output : outputs) {
      spawn.addOutputs(ExecLogEntry.Output.newBuilder().setOutputId(output));
    }
    return ExecLogEntry.newBuilder().setSpawn(spawn);
  }

  private static ExecLogEntry.Builder tree(int id, String hash) {
    return ExecLogEntry.newBuilder()
        .setId(id)
        .setDirectory(
            ExecLogEntry.Directory.newBuilder()
                .setPath("tree")
                .addFiles(ExecLogEntry.File.newBuilder().setPath("child").setDigest(digest(hash))));
  }

  private static ExecLogEntry.Builder symlink(int id, String target) {
    return ExecLogEntry.newBuilder()
        .setId(id)
        .setUnresolvedSymlink(
            ExecLogEntry.UnresolvedSymlink.newBuilder().setPath("link").setTargetPath(target));
  }
}
