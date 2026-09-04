package com.holtherndon.bazelviz.format.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.SessionManifest.AuxiliaryCommand;
import com.holtherndon.bazelviz.format.session.SessionManifest.CaptureSourceEntry;
import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManifestCodecTest {

  private static final SessionId ID =
      new SessionId(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

  /**
   * A hand-written version 1 manifest. This literal is the compatibility fixture: when the format
   * moves to version 2, this text must keep loading through the migration chain rather than being
   * edited to match the new shape.
   */
  private static final String V1_MANIFEST =
      """
      {
        "formatVersion": 1,
        "appVersion": "0.1.0-SNAPSHOT",
        "sessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        "createdMicros": 1755800000000000,
        "state": "READY",
        "workspaceRoot": "/work/repo",
        "originalCommand": ["bazel", "build", "//..."],
        "sources": [
          {
            "kind": "BEP_BINARY",
            "path": "/tmp/build.bep",
            "sha256": "abc123",
            "byteSize": 4096,
            "completeness": "TRUNCATED",
            "note": "cancelled mid-write"
          }
        ],
        "eventCount": 1234,
        "schemaVersion": 1,
        "warnings": ["one warning"],
        "containsAbsolutePaths": true
      }
      """;

  private final SessionManifestCodec codec = SessionManifestCodec.standard();

  @TempDir Path root;

  @Test
  void loadsAVersion1Manifest() throws Exception {
    SessionManifest manifest = codec.readText(V1_MANIFEST, "fixture");

    assertThat(manifest.formatVersion()).isEqualTo(1);
    assertThat(manifest.sessionId()).isEqualTo(ID);
    assertThat(manifest.state()).isEqualTo(SessionState.READY);
    assertThat(manifest.createdMicros()).isEqualTo(1_755_800_000_000_000L);
    assertThat(manifest.workspaceRoot()).contains("/work/repo");
    assertThat(manifest.executionLocation()).isEmpty();
    assertThat(manifest.originalCommand()).contains(List.of("bazel", "build", "//..."));
    assertThat(manifest.eventCount()).hasValue(1234L);
    assertThat(manifest.schemaVersion()).hasValue(1);
    assertThat(manifest.warnings()).containsExactly("one warning");
    assertThat(manifest.containsAbsolutePaths()).contains(true);

    assertThat(manifest.sources())
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.kind()).isEqualTo("BEP_BINARY");
              assertThat(source.sha256()).contains("abc123");
              assertThat(source.byteSize()).hasValue(4096L);
              assertThat(source.completeness()).isEqualTo(Completeness.TRUNCATED);
              assertThat(source.note()).contains("cancelled mid-write");
            });
  }

  @Test
  void localManifestsRetainVersion1UuidAliasCompatibility() throws Exception {
    String source =
        V1_MANIFEST.replace(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");

    assertThat(codec.readText(source, "legacy-local.json").sessionId()).isEqualTo(ID);

    Path file = root.resolve("legacy-local.json");
    Files.writeString(file, source);
    assertThat(codec.read(file).sessionId()).isEqualTo(ID);
  }

  @Test
  void portableManifestReadsRequireCanonicalSessionIdText() throws Exception {
    Path file = root.resolve("portable.json");
    Files.writeString(
        file,
        V1_MANIFEST.replace(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"));

    assertThatThrownBy(() -> codec.readForPortableArchive(file))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("canonical UUID sessionId");
  }

  @Test
  void absentOptionalsStayAbsentThroughARoundTrip() throws Exception {
    SessionManifest original = SessionManifest.newSession(ID, "0.1.0", 1_000L).build();

    String text = codec.writeText(original);
    SessionManifest reloaded = codec.readText(text, "memory");

    // Nothing unknown was invented as 0, "" or [].
    assertThat(text)
        .doesNotContain("finalizedMicros")
        .doesNotContain("workingDirectory")
        .doesNotContain("workspaceRoot")
        .doesNotContain("executionLocation")
        .doesNotContain("bazelExecutable")
        .doesNotContain("bazelVersion")
        .doesNotContain("originalCommand")
        .doesNotContain("effectiveCommand")
        .doesNotContain("injectedFlags")
        .doesNotContain("auxiliaryCommands")
        .doesNotContain("eventCount")
        .doesNotContain("actionCount")
        .doesNotContain("indexVersions")
        .doesNotContain("schemaVersion")
        .doesNotContain("containsAbsolutePaths")
        .doesNotContain("null");

    assertThat(reloaded.finalizedMicros()).isEmpty();
    assertThat(reloaded.workingDirectory()).isEmpty();
    assertThat(reloaded.executionLocation()).isEmpty();
    assertThat(reloaded.originalCommand()).isEmpty();
    assertThat(reloaded.injectedFlags()).isEmpty();
    assertThat(reloaded.auxiliaryCommands()).isEmpty();
    assertThat(reloaded.eventCount()).isEmpty();
    assertThat(reloaded.actionCount()).isEmpty();
    assertThat(reloaded.indexVersions()).isEmpty();
    assertThat(reloaded.schemaVersion()).isEmpty();
    assertThat(reloaded.containsAbsolutePaths()).isEmpty();
    assertThat(reloaded.containsEnvironmentValues()).isEmpty();
    assertThat(reloaded).isEqualTo(original);
  }

  @Test
  void aPresentButEmptyListIsNotTheSameAsAnAbsentOne() throws Exception {
    SessionManifest manifest =
        SessionManifest.newSession(ID, "0.1.0", 1_000L)
            .injectedFlags(Optional.of(List.of()))
            .build();

    SessionManifest reloaded = codec.readText(codec.writeText(manifest), "memory");

    // "we injected no flags" survives as a statement, distinct from "we do
    // not know what was injected".
    assertThat(reloaded.injectedFlags()).contains(List.of());
    assertThat(reloaded.originalCommand()).isEmpty();
  }

  @Test
  void everyPopulatedFieldSurvivesARoundTrip() throws Exception {
    SessionManifest original =
        SessionManifest.newSession(ID, "0.1.0", 1_000L)
            .state(SessionState.READY_WITH_WARNINGS)
            .finalizedAtMicros(2_000L)
            .workingDirectory(Optional.of("/work"))
            .workspaceRoot(Optional.of("/work/repo"))
            .executionLocation(
                Optional.of(
                    ExecutionLocation.ssh(
                        "Build server", "builder@example.internal", OptionalInt.of(2222))))
            .bazelExecutable(Optional.of("/usr/bin/bazel"))
            .bazelVersion(Optional.of("7.4.1"))
            .originalCommand(Optional.of(List.of("bazel", "test", "//...")))
            .effectiveCommand(
                Optional.of(List.of("bazel", "test", "--bes_backend=grpc://x", "//...")))
            .environmentCapturePolicy(Optional.of("ALLOWLIST"))
            .capturePreset(Optional.of("PERFORMANCE_DIAGNOSTICS"))
            .injectedFlags(Optional.of(List.of("--bes_backend=grpc://x")))
            .auxiliaryCommands(
                Optional.of(List.of(new AuxiliaryCommand("aquery", List.of("bazel", "aquery")))))
            .addSource(
                CaptureSourceEntry.of(
                    "BES_STREAM",
                    Optional.empty(),
                    Optional.empty(),
                    OptionalLong.empty(),
                    Completeness.UNKNOWN,
                    Optional.empty()))
            .redactionState(Optional.of("NONE"))
            .eventCount(OptionalLong.of(9))
            .actionCount(OptionalLong.of(8))
            .indexVersions(Optional.of(Map.of("action-forward", 2)))
            .schemaVersion(OptionalInt.of(1))
            .addWarning("first")
            .addWarning("second")
            .containsAbsolutePaths(Optional.of(true))
            .containsEnvironmentValues(Optional.of(false))
            .build();

    assertThat(codec.readText(codec.writeText(original), "memory")).isEqualTo(original);
  }

  @Test
  void rejectsAnInvalidSshExecutionLocation() {
    String source =
        V1_MANIFEST.replace(
            "\"workspaceRoot\": \"/work/repo\",",
            """
            "workspaceRoot": "/work/repo",
            "executionLocation": {
              "kind": "SSH",
              "displayName": "Build server",
              "sshPort": 70000
            },\
            """);

    assertThatThrownBy(() -> codec.readText(source, "invalid-remote.json"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("invalid execution location");
  }

  @Test
  void anUnknownSourceSizeIsAbsentRatherThanZero() throws Exception {
    SessionManifest manifest =
        SessionManifest.newSession(ID, "0.1.0", 1_000L)
            .addSource(CaptureSourceEntry.pending("BEP_JSON", "/tmp/growing.json"))
            .build();

    String text = codec.writeText(manifest);

    assertThat(text).doesNotContain("byteSize").doesNotContain("sha256");
    assertThat(codec.readText(text, "memory").sources())
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.byteSize()).isEmpty();
              assertThat(source.sha256()).isEmpty();
              assertThat(source.completeness()).isEqualTo(Completeness.UNKNOWN);
            });
  }

  @Test
  void membersWrittenByANewerBuildSurviveAReadModifyWrite() throws Exception {
    String fromTheFuture =
        """
        {
          "formatVersion": 1,
          "appVersion": "9.9.9",
          "sessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
          "createdMicros": 5,
          "state": "CAPTURING",
          "sources": [
            {
              "kind": "BES_STREAM",
              "completeness": "UNKNOWN",
              "remoteCacheDigest": "sha256:deadbeef"
            }
          ],
          "warnings": [],
          "remoteExecutionCluster": {"name": "rbe-prod", "shards": 12},
          "futureCounters": [1, 2, 3]
        }
        """;

    SessionManifest manifest = codec.readText(fromTheFuture, "future");
    assertThat(manifest.unknownFields())
        .containsOnlyKeys("remoteExecutionCluster", "futureCounters");

    // An older build opens the session, changes what it understands, saves.
    String rewritten =
        codec.writeText(manifest.toBuilder().addWarning("opened by an older build").build());
    JsonObject document = (JsonObject) JsonReader.parse(rewritten);

    assertThat(document.member("remoteExecutionCluster"))
        .contains(
            new JsonObject(
                Map.of(
                    "name", new JsonString("rbe-prod"),
                    "shards", new JsonNumber("12"))));
    assertThat(document.member("futureCounters"))
        .contains(
            new JsonArray(List.of(new JsonNumber("1"), new JsonNumber("2"), new JsonNumber("3"))));

    // Per-source unknown members are preserved too.
    JsonArray sources = (JsonArray) document.member("sources").orElseThrow();
    JsonObject source = (JsonObject) sources.elements().getFirst();
    assertThat(source.member("remoteCacheDigest")).contains(new JsonString("sha256:deadbeef"));

    // And the change the older build made is there.
    assertThat(codec.readText(rewritten, "future").warnings())
        .containsExactly("opened by an older build");
  }

  @Test
  void aKnownMemberIsNeverTreatedAsUnknown() throws Exception {
    SessionManifest manifest = codec.readText(V1_MANIFEST, "fixture");

    assertThat(manifest.unknownFields()).isEmpty();
  }

  @Test
  void rejectsAManifestFromTheFutureRatherThanGuessing() {
    String source = V1_MANIFEST.replace("\"formatVersion\": 1", "\"formatVersion\": 99");

    assertThatThrownBy(() -> codec.readText(source, "future.json"))
        .isInstanceOf(UnsupportedManifestVersionException.class)
        .hasMessageContaining("format version 99")
        .hasMessageContaining("at most version 1")
        .satisfies(
            thrown -> {
              UnsupportedManifestVersionException e = (UnsupportedManifestVersionException) thrown;
              assertThat(e.foundVersion()).isEqualTo(99);
              assertThat(e.supportedVersion()).isEqualTo(1);
            });
  }

  @Test
  void migrationHookCarriesAV1DocumentForwardToANewerVersion() throws Exception {
    // Stands in for the real 1 -> 2 step this codebase will eventually ship:
    // it adds a member the newer record needs and bumps the version.
    ManifestMigrations.ManifestMigration oneToTwo =
        document -> {
          Map<String, JsonValue> members = new LinkedHashMap<>(document.members());
          members.put("redactionState", new JsonString("NONE"));
          return ManifestMigrations.withFormatVersion(new JsonObject(members), 2);
        };
    SessionManifestCodec future =
        new SessionManifestCodec(ManifestMigrations.of(2, Map.of(1, oneToTwo)));

    SessionManifest migrated = future.readText(V1_MANIFEST, "fixture");

    assertThat(migrated.formatVersion()).isEqualTo(2);
    assertThat(migrated.redactionState()).contains("NONE");
    assertThat(migrated.state()).isEqualTo(SessionState.READY);
  }

  @Test
  void refusesAnOldManifestWhenNoMigrationIsRegistered() {
    SessionManifestCodec future = new SessionManifestCodec(ManifestMigrations.of(3, Map.of()));

    assertThatThrownBy(() -> future.readText(V1_MANIFEST, "fixture"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("no migration to version 2 is registered");
  }

  @Test
  void refusesAMigrationStepThatDidNotBumpTheVersion() {
    ManifestMigrations.ManifestMigration lazy = document -> document;
    SessionManifestCodec broken =
        new SessionManifestCodec(ManifestMigrations.of(2, Map.of(1, lazy)));

    assertThatThrownBy(() -> broken.readText(V1_MANIFEST, "fixture"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("half-migrated");
  }

  @Test
  void reportsMissingAndMalformedMembersByName() {
    assertThatThrownBy(() -> codec.readText("{\"formatVersion\":1}", "x"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("missing required member 'appVersion'");

    assertThatThrownBy(() -> codec.readText("{\"appVersion\":\"1\"}", "x"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("'formatVersion'");

    assertThatThrownBy(() -> codec.readText("not json at all", "x"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("not valid JSON");

    String unknownState =
        V1_MANIFEST.replace("\"state\": \"READY\"", "\"state\": \"TIME_TRAVELLING\"");
    assertThatThrownBy(() -> codec.readText(unknownState, "x"))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("TIME_TRAVELLING")
        .hasMessageContaining("newer version");
  }

  @Test
  void writesAtomicallyAndLeavesNoTemporaryFilesBehind() throws IOException {
    Path manifestFile = root.resolve("manifest.json");
    SessionManifest first = SessionManifest.newSession(ID, "0.1.0", 1_000L).build();
    SessionManifest second = first.toBuilder().addWarning("second write").build();

    codec.write(manifestFile, first);
    codec.write(manifestFile, second);

    assertThat(codec.read(manifestFile).warnings()).containsExactly("second write");
    try (var entries = Files.list(root)) {
      assertThat(entries.map(path -> path.getFileName().toString()))
          .containsExactly("manifest.json");
    }
  }

  @Test
  void readingAMissingManifestNamesThePath() {
    Path missing = root.resolve("nope").resolve("manifest.json");

    assertThatThrownBy(() -> codec.read(missing))
        .isInstanceOf(SessionFormatException.class)
        .hasMessageContaining("no manifest at");
  }
}
