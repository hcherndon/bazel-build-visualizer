package com.holtherndon.bazelviz.app.cli;

import static com.holtherndon.bazelviz.app.cli.CliHarness.number;
import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The headless guarantee, checked rather than asserted in a comment.
 *
 * <p>CI runs {@code bbv import} on machines with no display, where touching a window toolkit throws
 * {@link java.awt.HeadlessException} and the command dies having imported nothing. The test suite
 * sets {@code java.awt.headless=true} for this whole module (see {@code app/BUILD.bazel}'s {@code
 * headless = True}); this test confirms the mode really is in force and then runs a complete import
 * inside it.
 *
 * <p>Note what this does <em>not</em> rely on: {@code Main} dispatching before it creates a window
 * is what keeps the real process headless, and that seam is a plain {@code if} on {@code
 * args.length} placed before any other statement. This test covers the other half — that nothing
 * the commands themselves reach needs a graphics environment, however deep in the import pipeline
 * it lives.
 */
class CliHeadlessTest {

  @TempDir Path workspace;

  private final CliHarness cli = new CliHarness();

  @Test
  void aFullImportRunsWithNoGraphicsEnvironment() throws IOException {
    assertThat(GraphicsEnvironment.isHeadless())
        .as(
            "this test is meaningless unless the JVM really is headless;"
                + " app/BUILD.bazel sets java.awt.headless for the test suite")
        .isTrue();

    Path source = workspace.resolve("build.bep");
    BepBinaryWriter.write(source, SyntheticBepStream.of(120));

    CliHarness.Result imported =
        cli.run(
            "import",
            source.toString(),
            "--sessions-root",
            workspace.resolve("sessions").toString(),
            "--json");

    assertThat(imported.code()).as("stderr was:%n%s", imported.err()).isZero();
    assertThat(number(imported.json(), "eventCount")).isEqualTo(120);

    // And reading it back is headless too, so the whole verify-an-import
    // loop works over ssh with no display.
    CliHarness.Result inspected =
        cli.run(
            "inspect",
            CliHarness.sessionDirectory(imported.json()).toString(),
            "--events",
            "5",
            "--json");
    assertThat(inspected.code()).isZero();
    assertThat(CliHarness.objects(inspected.json(), "events")).hasSize(5);

    assertThat(GraphicsEnvironment.isHeadless())
        .as("nothing in the import or inspect path quietly left headless mode")
        .isTrue();
  }
}
