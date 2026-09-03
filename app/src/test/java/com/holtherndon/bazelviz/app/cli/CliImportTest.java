package com.holtherndon.bazelviz.app.cli;

import static com.holtherndon.bazelviz.app.cli.CliHarness.number;
import static com.holtherndon.bazelviz.app.cli.CliHarness.objects;
import static com.holtherndon.bazelviz.app.cli.CliHarness.sessionDirectory;
import static com.holtherndon.bazelviz.app.cli.CliHarness.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.testsupport.bep.BepFixtures;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code bbv import} over undamaged sources.
 *
 * <p>The fixtures are the real synthetic BEP streams from {@code test-support}, written by the same
 * protobuf classes Bazel writes, so a passing test says the command imports build event protocol
 * files rather than that it imports bytes this test made up.
 */
class CliImportTest {

  @TempDir static Path fixtureDirectory;

  private static BepFixtures.Set fixtures;

  @TempDir Path workspace;

  private final CliHarness cli = new CliHarness();

  @BeforeAll
  static void writeFixtures() throws IOException {
    fixtures = BepFixtures.writeAll(fixtureDirectory, SyntheticBepStream.of(120));
  }

  private Path sessionsRoot() {
    return workspace.resolve("sessions");
  }

  @Test
  void importsABinaryBepFileAndSummarizesIt() {
    CliHarness.Result result =
        cli.run(
            "import",
            fixtures.binary().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--json");

    assertThat(result.code()).as("stderr was:%n%s", result.err()).isZero();
    JsonObject summary = result.json();
    assertThat(string(summary, "command")).isEqualTo("import");
    assertThat(string(summary, "outcome")).isEqualTo("COMPLETE");
    assertThat(string(summary, "format")).isEqualTo("BEP_BINARY");
    assertThat(string(summary, "sessionState")).isEqualTo("READY");
    assertThat(string(summary, "sourceCompleteness")).isEqualTo("COMPLETE");
    assertThat(number(summary, "eventCount")).isEqualTo(fixtures.stream().eventCount());
    assertThat(number(summary, "recordsJournaledThisRun"))
        .isEqualTo(fixtures.stream().eventCount());
    assertThat(number(summary, "exitCode")).isZero();
    assertThat(CliHarness.isNull(summary, "damageOffset"))
        .as("an undamaged source reports no damage offset at all, not offset 0")
        .isTrue();

    // Every event decoded, and the breakdown is a real map rather than a
    // total: an import that quietly stored 118 failures would still have the
    // right event count.
    JsonObject decode = CliHarness.object(summary, "decodeStatusCounts");
    assertThat(number(decode, "OK")).isEqualTo(fixtures.stream().eventCount());
    assertThat(decode.members()).containsOnlyKeys("OK");
  }

  @Test
  void preservesTheSourceInsideTheSession() throws IOException {
    CliHarness.Result result =
        cli.run(
            "import",
            fixtures.binary().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--json");
    assertThat(result.code()).isZero();

    JsonObject summary = result.json();
    List<JsonObject> sources = objects(summary, "sources");
    assertThat(sources).hasSize(1);
    JsonObject source = sources.get(0);
    assertThat(string(source, "kind")).isEqualTo("BEP_BINARY");
    assertThat(string(source, "path")).isEqualTo(fixtures.binary().toString());
    assertThat(string(source, "sha256")).hasSize(64);
    assertThat(number(source, "byteSize")).isEqualTo(Files.size(fixtures.binary()));
    assertThat(string(source, "completeness")).isEqualTo("COMPLETE");

    // Phase 1 exit criterion: the original source is preserved. The default
    // preservation copies it into the session, so the session owns the bytes
    // it indexed and does not depend on the original staying where it was.
    Path preservedCopy = Path.of(string(summary, "preservedCopy"));
    assertThat(preservedCopy).exists();
    assertThat(preservedCopy).hasSameBinaryContentAs(fixtures.binary());
    assertThat(preservedCopy.getParent().getFileName()).hasToString("raw");
  }

  @Test
  void importsAJsonBepFile() {
    CliHarness.Result result =
        cli.run(
            "import",
            fixtures.jsonLines().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--json");

    assertThat(result.code()).as("stderr was:%n%s", result.err()).isZero();
    JsonObject summary = result.json();
    assertThat(string(summary, "format")).isEqualTo("BEP_JSON");
    assertThat(string(summary, "outcome")).isEqualTo("COMPLETE");
    assertThat(number(summary, "eventCount")).isEqualTo(fixtures.stream().eventCount());
  }

  @Test
  void importsAPrettyPrintedJsonBepFile() {
    CliHarness.Result result =
        cli.run(
            "import",
            fixtures.jsonPretty().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--json");

    assertThat(result.code()).as("stderr was:%n%s", result.err()).isZero();
    assertThat(number(result.json(), "eventCount")).isEqualTo(fixtures.stream().eventCount());
  }

  @Test
  void writesAReadableSummaryWithoutJson() {
    CliHarness.Result result =
        cli.run(
            "import", fixtures.binary().toString(),
            "--sessions-root", sessionsRoot().toString());

    assertThat(result.code()).isZero();
    assertThat(result.out())
        .startsWith("import complete")
        .contains("session directory")
        .contains("session state      READY")
        .contains("detected format    BEP_BINARY")
        .contains("completeness COMPLETE")
        .contains("events stored      " + fixtures.stream().eventCount())
        .contains("decode status      OK " + fixtures.stream().eventCount());
  }

  @Test
  void drawsProgressOnStderrAndKeepsStdoutParseable() {
    CliHarness.Result result =
        cli.run(
            "import",
            fixtures.binary().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--json");

    assertThat(result.code()).isZero();
    // Progress must not contaminate the machine-readable stream: stdout has
    // to parse as JSON on its own, which is the whole point of the split.
    assertThat(result.json().hasKey("eventCount")).isTrue();
    assertThat(result.err()).contains("finalizing");
  }

  @Test
  void quietSuppressesProgressButNotTheSummary() {
    CliHarness.Result result =
        cli.run(
            "import",
            fixtures.binary().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--quiet");

    assertThat(result.code()).isZero();
    assertThat(result.err()).isEmpty();
    assertThat(result.out()).startsWith("import complete");
  }

  @Test
  void nameMakesTheSessionDirectoryPredictable() {
    CliHarness.Result first =
        cli.run(
            "import",
            fixtures.binary().toString(),
            "--sessions-root",
            sessionsRoot().toString(),
            "--name",
            "nightly-build",
            "--json");
    assertThat(first.code()).isZero();
    Path directory = sessionDirectory(first.json());
    assertThat(directory.getParent()).isEqualTo(sessionsRoot());

    // The same name means the same session, so a second import refuses
    // rather than silently writing a second copy under a new random id.
    CliHarness.Result second =
        cli.run(
            "import", fixtures.binary().toString(),
            "--sessions-root", sessionsRoot().toString(),
            "--name", "nightly-build");
    assertThat(second.code()).isEqualTo(ExitCode.USAGE.code());
    assertThat(second.err()).contains("already exists").contains("--resume");
  }

  @Test
  void twoImportsOfTheSameFileProduceTheSameCounts() {
    JsonObject first =
        cli.run(
                "import",
                fixtures.binary().toString(),
                "--sessions-root",
                workspace.resolve("a").toString(),
                "--json")
            .json();
    JsonObject second =
        cli.run(
                "import",
                fixtures.binary().toString(),
                "--sessions-root",
                workspace.resolve("b").toString(),
                "--json")
            .json();

    // Phase 1 exit criterion: event counts and offsets are reproducible.
    assertThat(number(second, "eventCount")).isEqualTo(number(first, "eventCount"));
    assertThat(number(second, "recordsJournaledThisRun"))
        .isEqualTo(number(first, "recordsJournaledThisRun"));
    assertThat(string(objects(second, "sources").get(0), "sha256"))
        .isEqualTo(string(objects(first, "sources").get(0), "sha256"));
  }
}
