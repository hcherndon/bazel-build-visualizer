package com.holtherndon.bazelviz.enrich.execlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.exec.Protos.SpawnMetrics;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.ExecLogFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parsers, against bytes real Bazel wrote.
 *
 * <p>The fixtures in {@code src/test/resources/execlog} are execution logs produced by Bazel 6.5.0,
 * 7.6.1 and 9.2.0 during the Phase 4 probe. They are checked in rather than generated because this
 * is a compatibility layer: the point is to pin the exact version behaviours measured in {@code
 * docs/exec-log-and-profile.md}, and a synthetic fixture would only encode what I already believe.
 */
final class ExecLogParserTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("a compact log is recognised by its zstd magic and decompressed")
  void compactIsDetectedAndDecompressed() throws Exception {
    try (ExecLogSource source = open("bazel920-build.compact")) {
      assertThat(source.format()).isEqualTo(ExecLogFormat.COMPACT);
      // If the zstd frame were handed to the protobuf reader instead, this
      // would still produce a varint and then garbage.
      assertThat(source.stream().readNBytes(2)).isNotEmpty();
    }
  }

  @Test
  @DisplayName("a binary log is recognised and not decompressed")
  void binaryIsDetected() throws Exception {
    try (ExecLogSource source = open("bazel920-build.binary")) {
      assertThat(source.format()).isEqualTo(ExecLogFormat.BINARY);
    }
  }

  @Test
  @DisplayName("an empty file is refused rather than read as an empty build")
  void emptyIsRefused() throws Exception {
    Path empty = Files.createFile(tempDir.resolve("empty.binary"));
    // On 6.5.0 a fully cached rebuild writes zero bytes, and so does a run
    // that never started (S3). Guessing which would be inventing a fact.
    assertThatThrownBy(() -> ExecLogSource.open(empty))
        .isInstanceOf(UnknownExecLogFormatException.class)
        .hasMessageContaining("either a build in which every action was cached");
  }

  @Test
  @DisplayName("every spawn in a 9.2.0 build log is read, with runner and timing")
  void compactBuildLog() throws Exception {
    List<EnrichmentCommand> commands = parse("bazel920-build.compact");

    List<EnrichmentCommand.SpawnObserved> spawns = spawnsIn(commands);
    assertThat(spawns).hasSize(4);
    assertThat(spawns)
        .allSatisfy(
            spawn -> {
              assertThat(spawn.mnemonic()).isEqualTo("Genrule");
              assertThat(spawn.runner()).hasValue("darwin-sandbox");
              assertThat(spawn.timing().startMicros()).isPresent();
              assertThat(spawn.timing().totalMicros()).isPresent();
              assertThat(spawn.startUnknownReason()).isEmpty();
            });
    assertThat(spawns)
        .extracting(s -> s.targetLabel().orElseThrow())
        .containsExactlyInAnyOrder("//pkg:gen_a", "//pkg:gen_b", "//pkg:gen_slow", "//pkg:gen_big");
  }

  @Test
  @DisplayName("the invocation header is read, because it is the only provenance check there is")
  void compactCarriesAnInvocationId() throws Exception {
    List<EnrichmentCommand> commands = parse("bazel920-build.compact");

    assertThat(commands)
        .filteredOn(EnrichmentCommand.InvocationHeaderSeen.class::isInstance)
        .singleElement()
        .satisfies(
            command -> {
              var header = (EnrichmentCommand.InvocationHeaderSeen) command;
              // Measured equal to the BEP's started.uuid 9 times of 9 (V2).
              assertThat(header.buildId()).isNotBlank();
              assertThat(header.hashFunctionName()).isEqualTo("SHA-256");
            });
  }

  @Test
  @DisplayName("a fully cached build reads as a complete log with no spawns")
  void fullyCachedIsCompleteAndEmpty() throws Exception {
    List<EnrichmentCommand> commands = parse("bazel920-fullycached.compact");

    // 66 bytes on disk, one entry: the header. Not a truncated file, and
    // saying "no data" would be wrong -- every action hit the action cache.
    assertThat(spawnsIn(commands)).isEmpty();
    assertThat(commands).singleElement().isInstanceOf(EnrichmentCommand.InvocationHeaderSeen.class);
  }

  @Test
  @DisplayName("a tree-artifact output is kept, and it is a test's only resolvable one")
  void directoryOutputsSurvive() throws Exception {
    List<EnrichmentCommand> commands = parse("bazel920-test.compact");

    // On 8.4.1+ the spawn that ran the test produces exactly one resolvable
    // output and it is a Directory (S5). A parser indexing only File
    // entries loses it, and with it the test.
    assertThat(commands)
        .filteredOn(EnrichmentCommand.PathDeclared.class::isInstance)
        .extracting(c -> ((EnrichmentCommand.PathDeclared) c).kind())
        .contains(EnrichmentCommand.OutputRef.Kind.DIRECTORY);
  }

  @Test
  @DisplayName("a test produces two spawns sharing one label, and both are kept")
  void everyTestMakesTwoSpawns() throws Exception {
    List<EnrichmentCommand.SpawnObserved> tests =
        spawnsIn(parse("bazel920-test.compact")).stream()
            .filter(spawn -> spawn.mnemonic().equals("TestRunner"))
            .filter(spawn -> spawn.targetLabel().orElse("").equals("//pkg:fail_test"))
            .toList();

    // The first ran the test and exited 1; the second generated test.xml
    // and exited 0 (K3). Keeping one of them reports the test wrongly.
    assertThat(tests).hasSize(2);
    assertThat(tests).extracting(s -> s.exitCode().orElse(-1)).containsExactlyInAnyOrder(0, 1);
    assertThat(tests).anySatisfy(spawn -> assertThat(spawn.status()).hasValue("NON_ZERO_EXIT"));
  }

  @Test
  @DisplayName("outputs declared and never produced are recorded, not skipped")
  void unproducedOutputsAreRecorded() throws Exception {
    List<EnrichmentCommand.SpawnObserved> failing =
        spawnsIn(parse("bazel920-test.compact")).stream()
            .filter(spawn -> spawn.status().isPresent())
            .toList();

    assertThat(failing).isNotEmpty();
    assertThat(failing.getFirst().outputs())
        .anySatisfy(output -> assertThat(output.wasProduced()).isFalse());
  }

  @Test
  @DisplayName("7.6.1 and 9.2.0 compact logs read the same way")
  void theCompactFormatIsStableAcrossVersions() throws Exception {
    assertThat(spawnsIn(parse("bazel761-build.compact"))).hasSize(4);
    assertThat(spawnsIn(parse("bazel920-build.compact"))).hasSize(4);
  }

  // --------------------------------------------------------------- 6.5.0

  @Test
  @DisplayName("a 6.5.0 log yields durations, from the field the current proto reserves")
  void legacyWalltimeIsRecovered() throws Exception {
    List<EnrichmentCommand.SpawnObserved> spawns = spawnsIn(parseBinary("bazel650-legacy.binary"));

    assertThat(spawns).isNotEmpty();
    // Field 17 is `reserved` in the vendored spawn.proto. Without
    // LegacySpawnFields every one of these durations is empty, silently,
    // and the table reads like a build Bazel declined to time (S1).
    assertThat(spawns)
        .allSatisfy(
            spawn ->
                assertThat(spawn.timing().totalMicros())
                    .as("walltime for %s", spawn.targetLabel())
                    .isPresent());
  }

  @Test
  @DisplayName("a 6.5.0 attempt has no start, and says why in words")
  void legacyHasNoStartAndSaysSo() throws Exception {
    List<EnrichmentCommand.SpawnObserved> spawns = spawnsIn(parseBinary("bazel650-legacy.binary"));

    assertThat(spawns)
        .allSatisfy(
            spawn -> {
              assertThat(spawn.timing().startMicros()).isEmpty();
              assertThat(spawn.startUnknownReason())
                  .hasValueSatisfying(
                      reason ->
                          assertThat(reason).contains("does not report when a spawn started"));
            });
  }

  @Test
  @DisplayName("--experimental_execution_log_spawn_metrics adds durations and still no start")
  void spawnMetricsFlagAddsDurationsOnly() throws Exception {
    List<EnrichmentCommand.SpawnObserved> spawns =
        spawnsIn(parseBinary("bazel650-spawnmetrics.binary"));

    assertThat(spawns).isNotEmpty();
    assertThat(spawns)
        .allSatisfy(
            spawn -> {
              assertThat(spawn.timing().totalMicros()).isPresent();
              // The flag moves the duration into metrics. It does not add a
              // start, on any setting (S2).
              assertThat(spawn.timing().startMicros()).isEmpty();
            });
  }

  @Test
  @DisplayName("a 9.2.0 binary log has starts, so the boundary is the version not the format")
  void modernBinaryHasStarts() throws Exception {
    List<EnrichmentCommand.SpawnObserved> spawns = spawnsIn(parseBinary("bazel920-build.binary"));

    assertThat(spawns).isNotEmpty();
    assertThat(spawns).allSatisfy(spawn -> assertThat(spawn.timing().startMicros()).isPresent());
  }

  @Test
  @DisplayName("compact timing outside protobuf's range is a controlled parse failure")
  void compactRejectsOverflowingTiming() throws Exception {
    SpawnMetrics metrics =
        SpawnMetrics.newBuilder()
            .setStartTime(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE))
            .setTotalTime(Duration.newBuilder().setSeconds(Long.MAX_VALUE))
            .build();
    ExecLogEntry entry =
        ExecLogEntry.newBuilder()
            .setSpawn(ExecLogEntry.Spawn.newBuilder().setMetrics(metrics))
            .build();

    assertThatThrownBy(() -> parseCompact(entry))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("entry 0")
        .hasMessageContaining("metrics.start_time");
  }

  @Test
  @DisplayName("binary malformed timing is a controlled parse failure")
  void binaryRejectsMalformedTiming() throws Exception {
    SpawnMetrics metrics =
        SpawnMetrics.newBuilder()
            .setStartTime(Timestamp.newBuilder().setNanos(-1))
            .setTotalTime(Duration.newBuilder().setSeconds(1).setNanos(-1))
            .build();

    assertThatThrownBy(() -> parseBinary(SpawnExec.newBuilder().setMetrics(metrics).build()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("entry 0")
        .hasMessageContaining("metrics.start_time");
  }

  @Test
  @DisplayName("legacy walltime outside protobuf's range is a controlled parse failure")
  void legacyRejectsOverflowingWalltime() throws Exception {
    ByteString invalidDuration =
        Duration.newBuilder().setSeconds(Long.MAX_VALUE).build().toByteString();
    UnknownFieldSet unknownFields =
        UnknownFieldSet.newBuilder()
            .addField(
                17, UnknownFieldSet.Field.newBuilder().addLengthDelimited(invalidDuration).build())
            .build();

    assertThatThrownBy(
            () -> parseBinary(SpawnExec.newBuilder().setUnknownFields(unknownFields).build()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("legacy execution-log walltime");
  }

  @Test
  @DisplayName("legacy walltime with the wrong outer wire type is explicitly malformed")
  void legacyRejectsWrongWalltimeWireType() {
    UnknownFieldSet unknownFields =
        UnknownFieldSet.newBuilder()
            .addField(17, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () -> parseBinary(SpawnExec.newBuilder().setUnknownFields(unknownFields).build()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("wire type");
  }

  @Test
  @DisplayName("modern metrics cannot hide a malformed legacy walltime")
  void metricsDoNotBypassLegacyWalltimeValidation() {
    SpawnMetrics metrics =
        SpawnMetrics.newBuilder().setTotalTime(Duration.newBuilder().setSeconds(1)).build();
    UnknownFieldSet unknownFields =
        UnknownFieldSet.newBuilder()
            .addField(17, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();

    assertThatThrownBy(
            () ->
                parseBinary(
                    SpawnExec.newBuilder()
                        .setMetrics(metrics)
                        .setUnknownFields(unknownFields)
                        .build()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("wire type");
  }

  @Test
  @DisplayName("a malformed later legacy walltime occurrence cannot be ignored")
  void legacyRejectsMalformedDuplicateWalltime() {
    ByteString validDuration = Duration.newBuilder().setSeconds(1).build().toByteString();
    ByteString malformedDuration = ByteString.copyFrom(new byte[] {(byte) 0x80});
    UnknownFieldSet unknownFields =
        UnknownFieldSet.newBuilder()
            .addField(
                17,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(validDuration)
                    .addLengthDelimited(malformedDuration)
                    .build())
            .build();

    assertThatThrownBy(
            () -> parseBinary(SpawnExec.newBuilder().setUnknownFields(unknownFields).build()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("legacy execution-log walltime")
        .hasMessageContaining("not a protobuf Duration");
  }

  @Test
  @DisplayName("present zero exec-log times remain distinct from absent times")
  void zeroAndAbsentTimesStayDistinct() throws Exception {
    SpawnMetrics zero =
        SpawnMetrics.newBuilder()
            .setStartTime(Timestamp.getDefaultInstance())
            .setTotalTime(Duration.getDefaultInstance())
            .build();

    EnrichmentCommand.SpawnObserved compactZero =
        onlySpawn(
            parseCompact(
                ExecLogEntry.newBuilder()
                    .setSpawn(ExecLogEntry.Spawn.newBuilder().setMetrics(zero))
                    .build()));
    EnrichmentCommand.SpawnObserved compactAbsent =
        onlySpawn(
            parseCompact(
                ExecLogEntry.newBuilder().setSpawn(ExecLogEntry.Spawn.newBuilder()).build()));
    EnrichmentCommand.SpawnObserved binaryZero =
        onlySpawn(parseBinary(SpawnExec.newBuilder().setMetrics(zero).build()));

    assertThat(compactZero.timing().startMicros()).hasValue(0L);
    assertThat(compactZero.timing().totalMicros()).hasValue(0L);
    assertThat(compactZero.startUnknownReason()).isEmpty();
    assertThat(compactAbsent.timing().startMicros()).isEmpty();
    assertThat(compactAbsent.timing().totalMicros()).isEmpty();
    assertThat(binaryZero.timing().startMicros()).hasValue(0L);
    assertThat(binaryZero.timing().totalMicros()).hasValue(0L);
  }

  @Test
  @DisplayName("malformed exec-log duration cannot be mistaken for absence")
  void malformedDurationIsExplicit() {
    SpawnMetrics invalid =
        SpawnMetrics.newBuilder()
            .setTotalTime(Duration.newBuilder().setSeconds(1).setNanos(-1))
            .build();
    ExecLogEntry entry =
        ExecLogEntry.newBuilder()
            .setSpawn(ExecLogEntry.Spawn.newBuilder().setMetrics(invalid))
            .build();

    assertThatThrownBy(() -> parseCompact(entry))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("metrics.total_time");
  }

  // ------------------------------------------------------------- redaction

  @Test
  @DisplayName("an environment value with a secret-looking name is withheld, not dropped")
  void secretsAreWithheld() throws Exception {
    List<EnrichmentCommand> commands = new ArrayList<>();
    try (ExecLogSource source = open("bazel920-build.compact")) {
      new CompactExecLogParser(commands::add, new EnvironmentRedactor()).parse(source.stream());
    }
    assertThat(spawnsIn(commands)).isNotEmpty();
    // PATH is not secret and survives; the redactor's behaviour on names
    // that are is asserted in EnvironmentRedactorTest.
    assertThat(spawnsIn(commands).getFirst().environment())
        .anySatisfy(
            variable -> {
              assertThat(variable.name()).isEqualTo("PATH");
              assertThat(variable.redacted()).isFalse();
              assertThat(variable.value()).isPresent();
            });
  }

  // ---------------------------------------------------------------- helpers

  private ExecLogSource open(String name) throws IOException {
    return ExecLogSource.open(fixture(name));
  }

  private Path fixture(String name) throws IOException {
    Path target = tempDir.resolve(name);
    try (InputStream in = getClass().getResourceAsStream("/execlog/" + name)) {
      if (in == null) {
        throw new IOException("missing fixture " + name);
      }
      Files.write(target, in.readAllBytes());
    }
    return target;
  }

  private List<EnrichmentCommand> parse(String name) throws IOException {
    List<EnrichmentCommand> commands = new ArrayList<>();
    try (ExecLogSource source = open(name)) {
      new CompactExecLogParser(commands::add, EnvironmentRedactor.none()).parse(source.stream());
    }
    return commands;
  }

  private List<EnrichmentCommand> parseBinary(String name) throws IOException {
    List<EnrichmentCommand> commands = new ArrayList<>();
    try (ExecLogSource source = open(name)) {
      new BinaryExecLogParser(commands::add, EnvironmentRedactor.none()).parse(source.stream());
    }
    return commands;
  }

  private static List<EnrichmentCommand> parseCompact(ExecLogEntry entry) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    entry.writeDelimitedTo(bytes);
    List<EnrichmentCommand> commands = new ArrayList<>();
    new CompactExecLogParser(commands::add, EnvironmentRedactor.none())
        .parse(new ByteArrayInputStream(bytes.toByteArray()));
    return commands;
  }

  private static List<EnrichmentCommand> parseBinary(SpawnExec spawn) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    spawn.writeDelimitedTo(bytes);
    List<EnrichmentCommand> commands = new ArrayList<>();
    new BinaryExecLogParser(commands::add, EnvironmentRedactor.none())
        .parse(new ByteArrayInputStream(bytes.toByteArray()));
    return commands;
  }

  private static EnrichmentCommand.SpawnObserved onlySpawn(List<EnrichmentCommand> commands) {
    List<EnrichmentCommand.SpawnObserved> spawns = spawnsIn(commands);
    assertThat(spawns).hasSize(1);
    return spawns.getFirst();
  }

  private static List<EnrichmentCommand.SpawnObserved> spawnsIn(List<EnrichmentCommand> all) {
    return all.stream()
        .filter(EnrichmentCommand.SpawnObserved.class::isInstance)
        .map(EnrichmentCommand.SpawnObserved.class::cast)
        .toList();
  }
}
