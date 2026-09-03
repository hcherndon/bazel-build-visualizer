package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceDiscoveryScriptStoreTest {

  @TempDir Path settings;

  @Test
  void missingScriptIsANormalEmptySetting() {
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);

    WorkspaceDiscoveryScriptStore.LoadResult loaded = store.loadWithDiagnostics();

    assertThat(loaded.script()).isEmpty();
    assertThat(loaded.configured()).isFalse();
    assertThat(loaded.source()).isEqualTo(WorkspaceDiscoveryScriptStore.Source.MISSING);
    assertThat(loaded.diagnostics()).isEmpty();
    assertThat(store.file()).isEqualTo(settings.resolve("workspace-discovery"));
    assertThat(store.file()).doesNotExist();
  }

  @Test
  void scriptRoundTripsExactlyAsAnOwnerExecutableFile() {
    String script = "#!/bin/sh\nprintf 'local|Repository|/code/repository\\n'\n";
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);

    WorkspaceDiscoveryScriptStore.SaveResult saved = store.saveWithDiagnostics(script);
    WorkspaceDiscoveryScriptStore.LoadResult loaded =
        new WorkspaceDiscoveryScriptStore(settings).loadWithDiagnostics();

    assertThat(saved.saved()).isTrue();
    assertThat(saved.diagnostics()).isEmpty();
    assertThat(loaded.script()).isEqualTo(script);
    assertThat(loaded.configured()).isTrue();
    assertThat(loaded.source()).isEqualTo(WorkspaceDiscoveryScriptStore.Source.STORED);
    assertThat(loaded.diagnostics()).isEmpty();
    assertThat(store.file()).isExecutable();
  }

  @Test
  void nonEmptyScriptNeedsARealFirstLineShebangAndKeepsThePreviousFile() throws Exception {
    String original = "#!/bin/sh\nprintf 'local|Original|/original\\n'\n";
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    assertThat(store.save(original)).isTrue();

    WorkspaceDiscoveryScriptStore.SaveResult missing =
        store.saveWithDiagnostics("printf 'local|Replacement|/replacement\\n'\n");
    WorkspaceDiscoveryScriptStore.SaveResult blank = store.saveWithDiagnostics("   \n");
    WorkspaceDiscoveryScriptStore.SaveResult unnamed =
        store.saveWithDiagnostics("#!\nprintf 'ignored\\n'\n");

    assertThat(missing.saved()).isFalse();
    assertThat(missing.diagnostics().getFirst()).contains("shebang", "#!");
    assertThat(blank.saved()).isFalse();
    assertThat(blank.diagnostics().getFirst()).contains("shebang");
    assertThat(unnamed.saved()).isFalse();
    assertThat(unnamed.diagnostics().getFirst()).contains("name an interpreter");
    assertThat(Files.readString(store.file())).isEqualTo(original);
  }

  @Test
  void encodedByteLimitRejectsTheWholeReplacement() throws Exception {
    String original = "#!/bin/sh\nexit 0\n";
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    assertThat(store.save(original)).isTrue();
    String oversizedUnicode =
        "#!/bin/sh\n#" + "é".repeat(WorkspaceDiscoveryScriptStore.MAX_SCRIPT_BYTES / 2);

    WorkspaceDiscoveryScriptStore.SaveResult result = store.saveWithDiagnostics(oversizedUnicode);

    assertThat(result.saved()).isFalse();
    assertThat(result.diagnostics().getFirst())
        .contains(Integer.toString(WorkspaceDiscoveryScriptStore.MAX_SCRIPT_BYTES));
    assertThat(Files.readString(store.file())).isEqualTo(original);
  }

  @Test
  void oversizedAndMalformedFilesReturnSafeDiagnostics() throws Exception {
    Files.createDirectories(settings);
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);
    Files.write(store.file(), new byte[WorkspaceDiscoveryScriptStore.MAX_SCRIPT_BYTES + 1]);

    WorkspaceDiscoveryScriptStore.LoadResult oversized = store.loadWithDiagnostics();

    assertThat(oversized.source()).isEqualTo(WorkspaceDiscoveryScriptStore.Source.UNUSABLE);
    assertThat(oversized.script()).isEmpty();
    assertThat(oversized.diagnostics())
        .containsExactly("The workspace discovery script is invalid and was not loaded.");

    Files.write(store.file(), new byte[] {(byte) 0xc3, (byte) 0x28});
    WorkspaceDiscoveryScriptStore.LoadResult malformed = store.loadWithDiagnostics();
    assertThat(malformed.source()).isEqualTo(WorkspaceDiscoveryScriptStore.Source.UNUSABLE);
    assertThat(malformed.diagnostics())
        .containsExactly("The workspace discovery script is invalid and was not loaded.");
    assertThat(malformed.diagnostics().getFirst())
        .doesNotContain(settings.toString())
        .doesNotContain(
            new String(new byte[] {(byte) 0xc3, (byte) 0x28}, StandardCharsets.ISO_8859_1));
  }

  @Test
  void failedAtomicReplacementPreservesThePreviousScriptAndRemovesTemporaryFile() throws Exception {
    String original = "#!/bin/sh\nexit 0\n";
    assertThat(new WorkspaceDiscoveryScriptStore(settings).save(original)).isTrue();
    WorkspaceDiscoveryScriptStore failing =
        new WorkspaceDiscoveryScriptStore(
            settings,
            (temporary, destination) -> {
              throw new IOException("simulated replacement failure");
            });

    WorkspaceDiscoveryScriptStore.SaveResult result =
        failing.saveWithDiagnostics("#!/bin/sh\nexit 1\n");

    assertThat(result.saved()).isFalse();
    assertThat(result.diagnostics())
        .containsExactly(
            "The workspace discovery script could not be saved;"
                + " the previous script is unchanged.");
    assertThat(Files.readString(failing.file())).isEqualTo(original);
    try (var files = Files.list(settings)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .containsExactly("workspace-discovery");
    }
  }

  @Test
  void scriptSettingsIoRejectsTheSwingEventThread() throws Exception {
    WorkspaceDiscoveryScriptStore store = new WorkspaceDiscoveryScriptStore(settings);

    SwingUtilities.invokeAndWait(
        () -> {
          assertThatThrownBy(store::load)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
          assertThatThrownBy(() -> store.save(""))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("EDT");
        });
  }
}
