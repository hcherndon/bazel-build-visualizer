package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore.ExecutionHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherStateStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void roundTripsLauncherValuesAndNewestFirstHistory() {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State state =
        new LauncherStateStore.State(
            "/workspace",
            "/tools/bazelisk",
            CapturePreset.LIVE_ESSENTIALS,
            "test //...",
            List.of("test //...", "build //app", "test //..."));

    assertThat(store.save(state)).isTrue();

    assertThat(store.load())
        .isEqualTo(
            new LauncherStateStore.State(
                "/workspace",
                "/tools/bazelisk",
                CapturePreset.LIVE_ESSENTIALS,
                "test //...",
                List.of("test //...", "build //app")));
  }

  @Test
  void roundTripsAnSshProfileWithoutCredentials() {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    SshConnectionProfile profile =
        new SshConnectionProfile("build-linux", "2222", "/srv/repo", "bazelisk");
    LauncherStateStore.State state =
        new LauncherStateStore.State(
            "/srv/repo",
            "bazelisk",
            CapturePreset.PERFORMANCE_DIAGNOSTICS,
            "test //...",
            List.of("test //..."),
            ExecutionHost.SSH,
            "build-linux",
            "2222",
            List.of(profile));

    assertThat(store.save(state)).isTrue();
    assertThat(store.load()).isEqualTo(state);
  }

  @Test
  void roundTripsSeveralRepositoriesOnOneSshHostForTheNextRestart() {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    SshConnectionProfile first =
        new SshConnectionProfile("build-linux", "2222", "/srv/one", "bazel");
    SshConnectionProfile second =
        new SshConnectionProfile("build-linux", "2222", "/srv/two", "bazelisk");
    LauncherStateStore.State state =
        new LauncherStateStore.State(
            second.workingDirectory(),
            second.bazelExecutable(),
            CapturePreset.PERFORMANCE_DIAGNOSTICS,
            "test //...",
            List.of("test //..."),
            ExecutionHost.SSH,
            second.destination(),
            second.port(),
            List.of(second, first));

    assertThat(store.save(state)).isTrue();
    assertThat(store.load().sshProfiles()).containsExactly(second, first);
  }

  @Test
  void loadsVersionOneSettingsAsLocal() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    Files.writeString(
        store.file(),
        """
        format=1
        workspace=/old/repo
        bazel=bazel
        preset=LIVE_ESSENTIALS
        command=build //...
        history.count=0
        """);

    assertThat(store.load())
        .isEqualTo(
            new LauncherStateStore.State(
                "/old/repo", "bazel", CapturePreset.LIVE_ESSENTIALS, "build //...", List.of()));
  }

  @Test
  void missingAndCorruptFilesDegradeToDefaults() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    assertThat(store.load()).isEqualTo(LauncherStateStore.State.defaults());

    Files.writeString(store.file(), "format=1\npreset=CUSTOM\nhistory.count=nope\n");
    assertThat(store.load()).isEqualTo(LauncherStateStore.State.defaults());
  }

  @Test
  void storeRefusesToPerformDiskIoOnTheEventThread() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
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
            store.save(LauncherStateStore.State.defaults());
          } catch (Throwable failure) {
            saveFailure.set(failure);
          }
        });

    assertThat(loadFailure.get()).isInstanceOf(IllegalStateException.class);
    assertThat(saveFailure.get()).isInstanceOf(IllegalStateException.class);
    assertThat(store.file()).doesNotExist();
  }

  @Test
  void failedReplacementPreservesPriorStateAndCleansTheTemporaryFile() throws Exception {
    LauncherStateStore normal = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State prior =
        new LauncherStateStore.State(
            "/prior",
            "bazel",
            CapturePreset.LIVE_ESSENTIALS,
            "build //prior",
            List.of("build //prior"));
    assertThat(normal.save(prior)).isTrue();
    LauncherStateStore failing =
        new LauncherStateStore(
            temporaryDirectory,
            (temporary, destination) -> {
              throw new IOException("simulated replacement failure");
            });

    assertThat(
            failing.save(
                new LauncherStateStore.State(
                    "/new",
                    "bazelisk",
                    CapturePreset.PERFORMANCE_DIAGNOSTICS,
                    "test //new",
                    List.of("test //new"))))
        .isFalse();

    assertThat(normal.load()).isEqualTo(prior);
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .containsExactly("launcher.properties");
    }
  }

  @Test
  void panelQueuesBothLoadAndSaveInsteadOfDoingIoOnTheEventThread() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    assertThat(
            store.save(
                new LauncherStateStore.State(
                    "/loaded",
                    "bazelisk",
                    CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                    "build //loaded",
                    List.of("build //loaded"))))
        .isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    AtomicReference<LauncherPanel> panel = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          LauncherPanel created = new LauncherPanel(() -> {}, () -> {}, () -> {});
          created.attachPersistence(store, queuedIo::add);
          panel.set(created);
        });
    assertThat(queuedIo).hasSize(1);

    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertThat(panel.get().workspace()).isEqualTo("/loaded");
    assertThat(panel.get().preset()).isEqualTo(CapturePreset.FULL_GRAPH_DIAGNOSTICS);

    SwingUtilities.invokeAndWait(
        () -> {
          panel.get().setWorkspace("/changed");
          panel.get().flushPersistence();
        });
    assertThat(queuedIo).hasSize(1);
    assertThat(store.load().workspace()).isEqualTo("/loaded");

    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertThat(store.load().workspace()).isEqualTo("/changed");
  }

  @Test
  void lateLoadDoesNotOverwriteAnewerEdit() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    assertThat(
            store.save(
                new LauncherStateStore.State(
                    "/stored",
                    "bazelisk",
                    CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                    "build //stored",
                    List.of("build //stored"))))
        .isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);

    SwingUtilities.invokeAndWait(() -> panel.setWorkspace("/edited-before-load"));
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    assertThat(panel.workspace()).isEqualTo("/edited-before-load");
    assertThat(panel.bazelExecutable()).isEqualTo("bazelisk");
    assertThat(panel.preset()).isEqualTo(CapturePreset.FULL_GRAPH_DIAGNOSTICS);
    assertThat(panel.command()).isEqualTo("build //stored");
    assertThat(queuedIo).hasSize(1);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    assertThat(store.load())
        .isEqualTo(
            new LauncherStateStore.State(
                "/edited-before-load",
                "bazelisk",
                CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                "build //stored",
                List.of("build //stored")));
  }

  @Test
  void restoredWindowCanClearTheDraftBeforeLoadWithoutLosingHistory() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State stored =
        new LauncherStateStore.State(
            "/stored",
            "bazelisk",
            CapturePreset.FULL_GRAPH_DIAGNOSTICS,
            "test //draft",
            List.of("test //draft", "build //older"));
    assertThat(store.save(stored)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);

    SwingUtilities.invokeAndWait(panel::clearCommandOnInitialLoad);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    assertThat(panel.command()).isEmpty();
    assertThat(panel.historyEntriesForTest()).containsExactly("test //draft", "build //older");
    assertThat(queuedIo).hasSize(1);
    queuedIo.remove().run();
    assertThat(store.load().command()).isEmpty();
    assertThat(store.load().history()).containsExactly("test //draft", "build //older");
  }

  @Test
  void lateLoadDoesNotReplaceTheWindowManagedWorkspace() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    assertThat(
            store.save(
                new LauncherStateStore.State(
                    "/stored-local",
                    "bazel",
                    CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                    "build //stored",
                    List.of("build //stored"))))
        .isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);

    SwingUtilities.invokeAndWait(
        () ->
            panel.useManagedWorkspace(
                "Remote compiler",
                ExecutionHost.SSH,
                "/srv/compiler",
                "bazelisk",
                "builder@linux",
                "2222"));
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    assertThat(panel.hasManagedWorkspace()).isTrue();
    assertThat(panel.isRemote()).isTrue();
    assertThat(panel.workspace()).isEqualTo("/srv/compiler");
    assertThat(panel.bazelExecutable()).isEqualTo("bazelisk");
    assertThat(panel.command()).isEqualTo("build //stored");
    assertThat(panel.selectedWorkspaceForTest().getText())
        .isEqualTo("Remote compiler · builder@linux:2222 · /srv/compiler");

    assertThat(queuedIo).hasSize(1);
    queuedIo.remove().run();
    assertThat(store.load().executionHost()).isEqualTo(ExecutionHost.SSH);
    assertThat(store.load().workspace()).isEqualTo("/srv/compiler");
  }

  @Test
  void lateRemoteLoadKeepsAnEditedLocalDraftWithItsHost() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State remote =
        new LauncherStateStore.State(
            "/srv/stored-remote",
            "remote-bazelisk",
            CapturePreset.PERFORMANCE_DIAGNOSTICS,
            "test //...",
            List.of("test //..."),
            ExecutionHost.SSH,
            "build-linux",
            "2222",
            List.of(
                new SshConnectionProfile(
                    "build-linux", "2222", "/srv/stored-remote", "remote-bazelisk")));
    assertThat(store.save(remote)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/Users/example/edited-local");
          panel.setBazelExecutable("/opt/homebrew/bin/bazelisk");
        });
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    assertThat(panel.isRemote()).isTrue();
    assertThat(panel.workspace()).isEqualTo("/srv/stored-remote");
    assertThat(panel.bazelExecutable()).isEqualTo("remote-bazelisk");

    SwingUtilities.invokeAndWait(
        () -> panel.executionHostForTest().setSelectedItem(ExecutionHost.LOCAL));
    assertThat(panel.workspace()).isEqualTo("/Users/example/edited-local");
    assertThat(panel.bazelExecutable()).isEqualTo("/opt/homebrew/bin/bazelisk");
  }

  @Test
  void closeBeforeLoadDoesNotAdoptOrOverwriteExistingState() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State existing =
        new LauncherStateStore.State(
            "/stored",
            "bazelisk",
            CapturePreset.FULL_GRAPH_DIAGNOSTICS,
            "build //stored",
            List.of("build //stored"));
    assertThat(store.save(existing)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);

    SwingUtilities.invokeAndWait(panel::close);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    assertThat(panel.workspace()).isNotEqualTo("/stored");
    assertThat(queuedIo).isEmpty();
    assertThat(store.load()).isEqualTo(existing);
  }

  @Test
  void closeBeforeLoadMergesEditsAndHistoryWithoutAdoptingIntoThePanel() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State existing =
        new LauncherStateStore.State(
            "/stored",
            "bazelisk",
            CapturePreset.FULL_GRAPH_DIAGNOSTICS,
            "build //stored",
            List.of("build //stored"));
    assertThat(store.save(existing)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/edited-before-close");
          panel.commandFieldForTest().setText("test //new");
          panel.rememberCommand("test //new");
          panel.close();
        });
    queuedIo.remove().run();

    assertThat(panel.bazelExecutable()).isEqualTo("bazel");
    assertThat(queuedIo).hasSize(1);
    queuedIo.remove().run();
    assertThat(store.load())
        .isEqualTo(
            new LauncherStateStore.State(
                "/edited-before-close",
                "bazelisk",
                CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                "test //new",
                List.of("test //new", "build //stored")));
  }

  @Test
  void closeCompletionWaitsForALateLoadAndItsFinalSave() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State existing =
        new LauncherStateStore.State(
            "/stored",
            "bazelisk",
            CapturePreset.FULL_GRAPH_DIAGNOSTICS,
            "build //stored",
            List.of("build //stored"));
    assertThat(store.save(existing)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);
    AtomicReference<CompletionStage<Void>> closing = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/edited-before-close");
          closing.set(panel.closeAsync());
        });
    assertThat(closing.get().toCompletableFuture()).isNotDone();

    queuedIo.remove().run();
    assertThat(closing.get().toCompletableFuture()).isNotDone();
    assertThat(queuedIo).hasSize(1);

    queuedIo.remove().run();
    assertThat(closing.get().toCompletableFuture()).isCompleted();
    assertThat(store.load().workspace()).isEqualTo("/edited-before-close");
  }

  @Test
  void closeCompletionReportsAFailedFinalSave() throws Exception {
    LauncherStateStore normal = new LauncherStateStore(temporaryDirectory);
    assertThat(normal.save(LauncherStateStore.State.defaults())).isTrue();
    LauncherStateStore failing =
        new LauncherStateStore(
            temporaryDirectory,
            (temporary, destination) -> {
              throw new IOException("simulated replacement failure");
            });
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(failing, queuedIo);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    AtomicReference<CompletionStage<Void>> closing = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/cannot-save");
          closing.set(panel.closeAsync());
        });
    queuedIo.remove().run();

    assertThat(closing.get().toCompletableFuture()).isCompletedExceptionally();
    assertThat(normal.load()).isEqualTo(LauncherStateStore.State.defaults());
  }

  @Test
  void newestDesiredSnapshotWinsAfterAnOlderSaveCompletes() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State original =
        new LauncherStateStore.State(
            "/original", "bazel", CapturePreset.PERFORMANCE_DIAGNOSTICS, "build //...", List.of());
    assertThat(store.save(original)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/older-queued-value");
          panel.flushPersistence();
          panel.setWorkspace("/original");
          panel.flushPersistence();
        });
    assertThat(queuedIo).hasSize(1);

    queuedIo.remove().run();
    assertThat(store.load().workspace()).isEqualTo("/older-queued-value");
    assertThat(queuedIo).hasSize(1);

    queuedIo.remove().run();
    assertThat(store.load()).isEqualTo(original);
    assertThat(queuedIo).isEmpty();
  }

  @Test
  void closeWaitsForAnInFlightSaveAndTheNewerFinalSnapshot() throws Exception {
    LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
    LauncherStateStore.State original =
        new LauncherStateStore.State(
            "/original", "bazel", CapturePreset.PERFORMANCE_DIAGNOSTICS, "build //...", List.of());
    assertThat(store.save(original)).isTrue();
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(store, queuedIo);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});
    AtomicReference<CompletionStage<Void>> closing = new AtomicReference<>();

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/older-queued-value");
          panel.flushPersistence();
          panel.setWorkspace("/original");
          closing.set(panel.closeAsync());
        });
    assertThat(closing.get().toCompletableFuture()).isNotDone();

    queuedIo.remove().run();
    assertThat(store.load().workspace()).isEqualTo("/older-queued-value");
    assertThat(closing.get().toCompletableFuture()).isNotDone();

    queuedIo.remove().run();
    assertThat(store.load()).isEqualTo(original);
    assertThat(closing.get().toCompletableFuture()).isCompleted();
  }

  @Test
  void failedUnchangedSnapshotCanBeRetried() throws Exception {
    LauncherStateStore normal = new LauncherStateStore(temporaryDirectory);
    assertThat(normal.save(LauncherStateStore.State.defaults())).isTrue();
    LauncherStateStore failing =
        new LauncherStateStore(
            temporaryDirectory,
            (temporary, destination) -> {
              throw new IOException("simulated replacement failure");
            });
    ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
    LauncherPanel panel = panelAttachedTo(failing, queuedIo);
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    SwingUtilities.invokeAndWait(
        () -> {
          panel.setWorkspace("/retry-me");
          panel.flushPersistence();
        });
    queuedIo.remove().run();
    SwingUtilities.invokeAndWait(() -> {});

    SwingUtilities.invokeAndWait(panel::flushPersistence);
    assertThat(queuedIo).hasSize(1);
  }

  private static LauncherPanel panelAttachedTo(
      LauncherStateStore store, ArrayDeque<Runnable> queuedIo) throws Exception {
    AtomicReference<LauncherPanel> panel = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          LauncherPanel created = new LauncherPanel(() -> {}, () -> {}, () -> {});
          created.attachPersistence(store, queuedIo::add);
          panel.set(created);
        });
    return panel.get();
  }
}
