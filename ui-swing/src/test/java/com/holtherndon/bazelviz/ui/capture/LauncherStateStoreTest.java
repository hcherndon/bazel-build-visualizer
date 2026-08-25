package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherStateStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsLauncherValuesAndNewestFirstHistory() {
        LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
        LauncherStateStore.State state = new LauncherStateStore.State(
                "/workspace",
                "/tools/bazelisk",
                CapturePreset.LIVE_ESSENTIALS,
                "test //...",
                List.of("test //...", "build //app", "test //..."));

        assertThat(store.save(state)).isTrue();

        assertThat(store.load()).isEqualTo(new LauncherStateStore.State(
                "/workspace",
                "/tools/bazelisk",
                CapturePreset.LIVE_ESSENTIALS,
                "test //...",
                List.of("test //...", "build //app")));
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
        SwingUtilities.invokeAndWait(() -> {
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
        LauncherStateStore.State prior = new LauncherStateStore.State(
                "/prior", "bazel", CapturePreset.LIVE_ESSENTIALS,
                "build //prior", List.of("build //prior"));
        assertThat(normal.save(prior)).isTrue();
        LauncherStateStore failing = new LauncherStateStore(
                temporaryDirectory,
                (temporary, destination) -> {
                    throw new java.io.IOException("simulated replacement failure");
                });

        assertThat(failing.save(new LauncherStateStore.State(
                "/new", "bazelisk", CapturePreset.PERFORMANCE_DIAGNOSTICS,
                "test //new", List.of("test //new"))))
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
        assertThat(store.save(new LauncherStateStore.State(
                "/loaded", "bazelisk", CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                "build //loaded", List.of("build //loaded")))).isTrue();
        ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
        AtomicReference<LauncherPanel> panel = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            LauncherPanel created = new LauncherPanel(() -> {}, () -> {}, () -> {});
            created.attachPersistence(store, queuedIo::add);
            panel.set(created);
        });
        assertThat(queuedIo).hasSize(1);

        queuedIo.remove().run();
        SwingUtilities.invokeAndWait(() -> {});
        assertThat(panel.get().workspace()).isEqualTo("/loaded");
        assertThat(panel.get().preset()).isEqualTo(CapturePreset.FULL_GRAPH_DIAGNOSTICS);

        SwingUtilities.invokeAndWait(() -> {
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
        assertThat(store.save(new LauncherStateStore.State(
                "/stored", "bazel", CapturePreset.LIVE_ESSENTIALS,
                "build //stored", List.of("build //stored")))).isTrue();
        ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
        LauncherPanel panel = panelAttachedTo(store, queuedIo);

        SwingUtilities.invokeAndWait(() -> panel.setWorkspace("/edited-before-load"));
        queuedIo.remove().run();
        SwingUtilities.invokeAndWait(() -> {});

        assertThat(panel.workspace()).isEqualTo("/edited-before-load");
        assertThat(queuedIo).hasSize(1);
        queuedIo.remove().run();
        SwingUtilities.invokeAndWait(() -> {});
        assertThat(store.load().workspace()).isEqualTo("/edited-before-load");
    }

    @Test
    void closeBeforeLoadDoesNotAdoptOrOverwriteExistingState() throws Exception {
        LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
        LauncherStateStore.State existing = new LauncherStateStore.State(
                "/stored", "bazelisk", CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                "build //stored", List.of("build //stored"));
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
    void failedUnchangedSnapshotCanBeRetried() throws Exception {
        LauncherStateStore normal = new LauncherStateStore(temporaryDirectory);
        assertThat(normal.save(LauncherStateStore.State.defaults())).isTrue();
        LauncherStateStore failing = new LauncherStateStore(
                temporaryDirectory,
                (temporary, destination) -> {
                    throw new java.io.IOException("simulated replacement failure");
                });
        ArrayDeque<Runnable> queuedIo = new ArrayDeque<>();
        LauncherPanel panel = panelAttachedTo(failing, queuedIo);
        queuedIo.remove().run();
        SwingUtilities.invokeAndWait(() -> {});

        SwingUtilities.invokeAndWait(() -> {
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
        SwingUtilities.invokeAndWait(() -> {
            LauncherPanel created = new LauncherPanel(() -> {}, () -> {}, () -> {});
            created.attachPersistence(store, queuedIo::add);
            panel.set(created);
        });
        return panel.get();
    }
}
