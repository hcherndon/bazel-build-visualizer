package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

        store.save(state);

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
    void panelQueuesBothLoadAndSaveInsteadOfDoingIoOnTheEventThread() throws Exception {
        LauncherStateStore store = new LauncherStateStore(temporaryDirectory);
        store.save(new LauncherStateStore.State(
                "/loaded", "bazelisk", CapturePreset.FULL_GRAPH_DIAGNOSTICS,
                "build //loaded", List.of("build //loaded")));
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
        assertThat(store.load().workspace()).isEqualTo("/changed");
    }
}
