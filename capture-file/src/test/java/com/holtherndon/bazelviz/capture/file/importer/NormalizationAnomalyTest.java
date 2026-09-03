package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ActionExecuted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.NamedSetOfFiles;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.OutputGroup;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetComplete;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.DiagnosticCodes;
import com.holtherndon.bazelviz.storage.events.DiagnosticEntry;
import com.holtherndon.bazelviz.storage.events.EventQueries;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two things normalization can discover, and neither is visible any other way.
 *
 * <p>A duplicate action primary output means one of two actions is not in the table. A file set
 * referenced and never defined means every byte total reached through it is a lower bound. Both
 * look exactly like a smaller build, so a session that found one and said nothing would be
 * indistinguishable from a session that had nothing to find. The writer counted both from the start
 * and nothing read the counters; these tests exist so that stays fixed.
 */
class NormalizationAnomalyTest {

  @Test
  @DisplayName("two actions with one primary output are reported, not quietly merged")
  void duplicatePrimaryOutputIsDiagnosed(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("duplicate.bep");
    BepBinaryWriter.write(
        source,
        List.of(action("bazel-out/bin/a.o", "//pkg:a"), action("bazel-out/bin/a.o", "//pkg:b"))
            .iterator());

    List<DiagnosticEntry> diagnostics = importAndReadDiagnostics(temporary, source);

    assertThat(diagnostics)
        .anySatisfy(
            entry -> {
              assertThat(entry.diagnostic().code())
                  .isEqualTo(DiagnosticCodes.DUPLICATE_ACTION_OUTPUT);
              assertThat(entry.diagnostic().message()).contains("primary output");
            });
  }

  @Test
  @DisplayName("a file set referenced and never defined is reported")
  void undefinedFileSetIsDiagnosed(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("dangling.bep");
    // The target names file set "7"; no namedSetOfFiles event ever defines
    // it. Measured zero times in 1,829 references, so it is evidence the
    // capture is missing events rather than a normal state.
    BepBinaryWriter.write(
        source, List.of(namedSet("1"), targetReferencing("//pkg:lib", "7")).iterator());

    List<DiagnosticEntry> diagnostics = importAndReadDiagnostics(temporary, source);

    assertThat(diagnostics)
        .anySatisfy(
            entry -> {
              assertThat(entry.diagnostic().code()).isEqualTo(DiagnosticCodes.UNDEFINED_FILE_SET);
              assertThat(entry.diagnostic().message()).contains("lower bound");
            });
  }

  @Test
  @DisplayName("a clean stream reports neither")
  void aCleanStreamIsQuiet(@TempDir Path temporary) throws Exception {
    Path source = temporary.resolve("clean.bep");
    BepBinaryWriter.write(
        source,
        List.of(
                namedSet("1"),
                targetReferencing("//pkg:lib", "1"),
                action("bazel-out/bin/a.o", "//pkg:a"))
            .iterator());

    List<DiagnosticEntry> diagnostics = importAndReadDiagnostics(temporary, source);

    assertThat(diagnostics)
        .as("a warning nobody needs is a warning nobody reads")
        .noneMatch(
            entry ->
                entry.diagnostic().code().equals(DiagnosticCodes.DUPLICATE_ACTION_OUTPUT)
                    || entry.diagnostic().code().equals(DiagnosticCodes.UNDEFINED_FILE_SET));
  }

  private static List<DiagnosticEntry> importAndReadDiagnostics(Path temporary, Path source)
      throws Exception {
    SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
    ImportResult imported = new BepImporter(sessions).importFile(source);
    Path database = ManagedSessionLayout.at(imported.sessionRoot()).databaseFile();
    try (SessionDatabase opened = SessionDatabase.open(database);
        EventQueries queries = new EventQueries(opened.newReadConnection())) {
      return queries.diagnosticsPage(OptionalLong.empty(), 200);
    }
  }

  private static BuildEvent action(String primaryOutput, String label) {
    return BuildEvent.newBuilder()
        .setId(
            BuildEventId.newBuilder()
                .setActionCompleted(
                    BuildEventId.ActionCompletedId.newBuilder()
                        .setPrimaryOutput(primaryOutput)
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId("cfg"))))
        .setAction(ActionExecuted.newBuilder().setSuccess(true).setType("Genrule"))
        .build();
  }

  private static BuildEvent namedSet(String id) {
    return BuildEvent.newBuilder()
        .setId(
            BuildEventId.newBuilder()
                .setNamedSet(BuildEventId.NamedSetOfFilesId.newBuilder().setId(id)))
        .setNamedSetOfFiles(NamedSetOfFiles.newBuilder())
        .build();
  }

  private static BuildEvent targetReferencing(String label, String fileSetId) {
    return BuildEvent.newBuilder()
        .setId(
            BuildEventId.newBuilder()
                .setTargetCompleted(
                    BuildEventId.TargetCompletedId.newBuilder()
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId("cfg"))))
        .setCompleted(
            TargetComplete.newBuilder()
                .setSuccess(true)
                .addOutputGroup(
                    OutputGroup.newBuilder()
                        .setName("default")
                        .addFileSets(BuildEventId.NamedSetOfFilesId.newBuilder().setId(fileSetId))))
        .build();
  }
}
