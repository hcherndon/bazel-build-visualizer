package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LauncherHistoryStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void historiesReloadIndependentlyForEachWorkspaceDirectory() {
    LauncherHistoryStore first = new LauncherHistoryStore(temporaryDirectory.resolve("first"));
    LauncherHistoryStore second = new LauncherHistoryStore(temporaryDirectory.resolve("second"));

    assertThat(first.save(state(List.of("test //first")))).isTrue();
    assertThat(second.save(state(List.of("build //second")))).isTrue();

    assertThat(new LauncherHistoryStore(temporaryDirectory.resolve("first")).load().history())
        .containsExactly("test //first");
    assertThat(new LauncherHistoryStore(temporaryDirectory.resolve("second")).load().history())
        .containsExactly("build //second");
    assertThat(
            new LauncherHistoryStore(temporaryDirectory.resolve("first")).load().bazelExecutable())
        .isEqualTo("/tools/custom-bazel");
  }

  @Test
  void fileContainsOnlyBazelSelectionBoundedHistoryAndFormatMetadata() throws Exception {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);

    assertThat(store.save(state(List.of("test //one", "build //two")))).isTrue();

    Properties values = new Properties();
    try (Reader reader = Files.newBufferedReader(store.file(), StandardCharsets.UTF_8)) {
      values.load(reader);
    }
    assertThat(values.stringPropertyNames())
        .containsExactlyInAnyOrder("format", "bazel", "history.count", "history.0", "history.1");
    assertThat(values.getProperty("format")).isEqualTo("2");
    assertThat(values.getProperty("bazel")).isEqualTo("/tools/custom-bazel");
    assertThat(values.getProperty("history.0")).isEqualTo("test //one");
    assertThat(values.getProperty("history.1")).isEqualTo("build //two");
    assertThat(values.stringPropertyNames())
        .doesNotContain(
            "workspace",
            "preset",
            "command",
            "executionHost",
            "sshDestination",
            "sshPort",
            "sshProfiles.count");
  }

  @Test
  void missingAndCorruptFilesFallBackToEmptyHistory() throws Exception {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);
    assertThat(store.load().history()).isEmpty();
    assertThat(store.load().bazelExecutable()).isEqualTo("bazel");

    Files.writeString(store.file(), "format=1\nhistory.count=not-a-number\n");

    assertThat(store.load().history()).isEmpty();
    assertThat(store.load().bazelExecutable()).isEqualTo("bazel");
  }

  @Test
  void legacyHistoryOnlyFormatLoadsWithTheDefaultBazelCommand() throws Exception {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);
    Files.createDirectories(store.file().getParent());
    Files.writeString(store.file(), "format=1\nhistory.count=1\nhistory.0=test //legacy\n");

    LauncherStateStore.State loaded = store.load();

    assertThat(loaded.bazelExecutable()).isEqualTo("bazel");
    assertThat(loaded.history()).containsExactly("test //legacy");
  }

  @Test
  void storeRefusesDiskIoOnTheEventThread() throws Exception {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);
    AtomicReference<Throwable> loadFailure = new AtomicReference<>();
    AtomicReference<Throwable> saveFailure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            store.load();
          } catch (Throwable failure) {
            loadFailure.set(failure);
          }
          try {
            store.save(state(List.of("test //...")));
          } catch (Throwable failure) {
            saveFailure.set(failure);
          }
        });

    assertThat(loadFailure.get()).isInstanceOf(IllegalStateException.class);
    assertThat(saveFailure.get()).isInstanceOf(IllegalStateException.class);
    assertThat(store.file()).doesNotExist();
  }

  @Test
  void savedHistoryKeepsTheExistingFiftyCommandBound() {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);
    ArrayList<String> commands = new ArrayList<>();
    for (int index = 0; index < LauncherHistory.MAX_ENTRIES + 10; index++) {
      commands.add("test //package:target-" + index);
    }

    assertThat(store.save(state(commands))).isTrue();

    assertThat(store.load().history())
        .hasSize(LauncherHistory.MAX_ENTRIES)
        .containsExactlyElementsOf(commands.subList(0, LauncherHistory.MAX_ENTRIES));
  }

  @Test
  void failedAtomicReplacementPreservesPriorHistoryAndCleansTemporaryFile() throws Exception {
    LauncherHistoryStore normal = new LauncherHistoryStore(temporaryDirectory);
    assertThat(normal.save(state(List.of("build //prior")))).isTrue();
    LauncherHistoryStore failing =
        new LauncherHistoryStore(
            temporaryDirectory,
            (temporary, destination) -> {
              throw new IOException("simulated replacement failure");
            });

    assertThat(failing.save(state(List.of("test //replacement")))).isFalse();

    assertThat(normal.load().history()).containsExactly("build //prior");
    try (var files = Files.list(temporaryDirectory)) {
      assertThat(files.map(path -> path.getFileName().toString()).toList())
          .containsExactly("command-history.properties");
    }
  }

  @Test
  void launcherAdoptsOnlyBazelSelectionAndHistoryAndMergesCommandsEnteredBeforeLoad()
      throws Exception {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);
    assertThat(store.save(state(List.of("build //stored")))).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    AtomicReference<LauncherPanel> panelReference = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          LauncherPanel panel = new LauncherPanel(() -> {}, () -> {});
          panel.useManagedWorkspace(
              "Current", ExecutionHost.SSH, "/srv/current", "bazelisk", "builder", "2222");
          panel.presetChoiceForTest().setSelectedItem(CapturePreset.LIVE_ESSENTIALS);
          panel.commandFieldForTest().setText("query //draft");
          panel.attachHistoryPersistence(store, queuedIo::add);
          panel.rememberCommand("test //new");
          panelReference.set(panel);
        });

    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    LauncherPanel panel = panelReference.get();
    assertThat(panel.historyEntriesForTest()).containsExactly("test //new", "build //stored");
    assertThat(panel.recentCommandsListForTest().getModel().getSize()).isEqualTo(2);
    assertThat(panel.recentCommandsListForTest().getModel().getElementAt(0))
        .isEqualTo("test //new");
    assertThat(panel.recentCommandsListForTest().getModel().getElementAt(1))
        .isEqualTo("build //stored");
    assertThat(panel.workspace()).isEqualTo("/srv/current");
    assertThat(panel.bazelExecutable()).isEqualTo("/tools/custom-bazel");
    assertThat(panel.executionHost()).isEqualTo(ExecutionHost.SSH);
    assertThat(panel.sshDestination()).isEqualTo("builder");
    assertThat(panel.sshPort()).hasValue(2222);
    assertThat(panel.preset()).isEqualTo(CapturePreset.LIVE_ESSENTIALS);
    assertThat(panel.command()).isEqualTo("query //draft");

    assertThat(queuedIo).hasSize(1);
    queuedIo.remove().run();
    assertThat(store.load().history()).containsExactly("test //new", "build //stored");
    assertThat(store.load().bazelExecutable()).isEqualTo("/tools/custom-bazel");
  }

  @Test
  void aLoadedDiscoveredBazelOverrideSurvivesLaterManagedWorkspaceSetup() throws Exception {
    LauncherHistoryStore store = new LauncherHistoryStore(temporaryDirectory);
    assertThat(store.save(state(List.of("test //stored")))).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    AtomicReference<LauncherPanel> panelReference = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          LauncherPanel panel = new LauncherPanel(() -> {}, () -> {});
          panel.attachHistoryPersistence(store, queuedIo::add);
          panelReference.set(panel);
        });

    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    SwingUtilities.invokeAndWait(
        () ->
            panelReference
                .get()
                .useManagedWorkspace("Discovered", ExecutionHost.LOCAL, "/repo", "bazel", "", ""));

    assertThat(panelReference.get().bazelExecutable()).isEqualTo("/tools/custom-bazel");
    assertThat(panelReference.get().historyEntriesForTest()).containsExactly("test //stored");
  }

  private static LauncherStateStore.State state(List<String> history) {
    return new LauncherStateStore.State(
        "/must-not-be-written",
        "/tools/custom-bazel",
        CapturePreset.FULL_GRAPH_DIAGNOSTICS,
        "query //must-not-be-written",
        history,
        ExecutionHost.SSH,
        "private-host-must-not-be-written",
        "2222",
        List.of(
            new SshConnectionProfile(
                "profile-host-must-not-be-written",
                "2222",
                "/profile-path-must-not-be-written",
                "bazelisk")));
  }
}
