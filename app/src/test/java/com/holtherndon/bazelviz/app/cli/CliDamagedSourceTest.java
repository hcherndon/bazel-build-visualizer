package com.holtherndon.bazelviz.app.cli;

import static com.holtherndon.bazelviz.app.cli.CliHarness.number;
import static com.holtherndon.bazelviz.app.cli.CliHarness.objects;
import static com.holtherndon.bazelviz.app.cli.CliHarness.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.BepDamage;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A damaged source is not a failed command.
 *
 * <p>The exit code is the contract these tests pin down. A build script that imports a BEP file cut
 * short by a killed Bazel must be able to tell "there is a session here, it is missing its tail"
 * from "there is nothing here", because the right response to the first is to analyse what arrived
 * and the right response to the second is to stop. Code 1 says the first; code 3 says the second.
 */
class CliDamagedSourceTest {

  private static final int EVENT_COUNT = 120;

  @TempDir Path workspace;

  private final CliHarness cli = new CliHarness();

  private Path sessionsRoot() {
    return workspace.resolve("sessions");
  }

  private Path intactSource() throws IOException {
    Path intact = workspace.resolve("build.bep");
    BepBinaryWriter.write(intact, SyntheticBepStream.of(EVENT_COUNT));
    return intact;
  }

  @Test
  void aSourceTruncatedMidPayloadExitsOneAndStillReportsWhatItImported() throws IOException {
    Path damaged = workspace.resolve("truncated.bep");
    BepDamage.Damage damage = BepDamage.truncateMidPayload(intactSource(), damaged);
    long intactFrames = damage.intactFrames().orElseThrow();

    CliHarness.Result result =
        cli.run(
            "import", damaged.toString(), "--sessions-root", sessionsRoot().toString(), "--json");

    assertThat(result.code())
        .as("truncation is 'imported what it could', not a failure; stderr was:%n%s", result.err())
        .isEqualTo(ExitCode.PARTIAL.code());

    JsonObject summary = result.json();
    assertThat(string(summary, "outcome")).isEqualTo("TRUNCATED");
    assertThat(string(summary, "sourceCompleteness")).isEqualTo("TRUNCATED");
    assertThat(string(summary, "sessionState"))
        .as("a truncated source never produces a READY session")
        .isEqualTo("INCOMPLETE");
    assertThat(number(summary, "eventCount"))
        .as("everything before the cut was imported")
        .isEqualTo(intactFrames)
        .isPositive();
    assertThat(number(summary, "damageOffset")).isPositive();
    assertThat(string(objects(summary, "sources").get(0), "completeness")).isEqualTo("TRUNCATED");

    // The offset reaches the session's own diagnostics too, which is where a
    // tailing reader would look for the byte to resume at.
    JsonObject diagnostics = CliHarness.object(summary, "diagnosticCountsByCode");
    assertThat(diagnostics.hasKey("SOURCE_TRUNCATED")).isTrue();
  }

  @Test
  void aSourceTruncatedMidVarintIsAlsoPartial() throws IOException {
    Path damaged = workspace.resolve("mid-varint.bep");
    BepDamage.Damage damage = BepDamage.truncateMidVarint(intactSource(), damaged);

    CliHarness.Result result =
        cli.run(
            "import", damaged.toString(), "--sessions-root", sessionsRoot().toString(), "--json");

    assertThat(result.code()).isEqualTo(ExitCode.PARTIAL.code());
    assertThat(number(result.json(), "eventCount")).isEqualTo(damage.intactFrames().orElseThrow());
  }

  @Test
  void theTextSummaryOfATruncatedImportSaysWhereItStopped() throws IOException {
    Path damaged = workspace.resolve("truncated.bep");
    BepDamage.truncateMidPayload(intactSource(), damaged);

    CliHarness.Result result =
        cli.run(
            "import", damaged.toString(),
            "--sessions-root", sessionsRoot().toString());

    assertThat(result.code()).isEqualTo(ExitCode.PARTIAL.code());
    assertThat(result.out())
        .startsWith("import incomplete")
        .contains("ends mid-record at byte")
        .contains("everything before it was imported")
        .contains("damage at byte")
        .contains("completeness TRUNCATED");
  }

  @Test
  void aFileThatIsNotBepFailsOutrightAndLeavesNoSession() throws IOException {
    Path notBep = workspace.resolve("notes.txt");
    Files.writeString(notBep, "this is not a build event protocol file\n");

    CliHarness.Result result =
        cli.run(
            "import", notBep.toString(),
            "--sessions-root", sessionsRoot().toString());

    assertThat(result.code()).isEqualTo(ExitCode.FAILED.code());
    assertThat(result.err())
        .as("the detector's own reason is carried through, not 'unsupported file'")
        .contains("cannot import")
        .contains(notBep.toString());
    assertThat(result.err()).doesNotContain("\tat com.holtherndon");
    assertThat(sessionsRoot()).as("a refused import creates nothing to clean up").doesNotExist();
  }
}
