package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launcher values stored under the application settings directory.
 *
 * <p>Both methods perform blocking file I/O and refuse to run on the Swing
 * event thread. A missing, unreadable, or malformed preference file is only a
 * lost convenience: loading returns defaults and never prevents a build from
 * being launched.
 */
public final class LauncherStateStore {

    private static final Logger log = LoggerFactory.getLogger(LauncherStateStore.class);
    private static final String FORMAT = "1";

    private final Path file;

    public LauncherStateStore(Path settingsDirectory) {
        file = Objects.requireNonNull(settingsDirectory, "settingsDirectory")
                .resolve("launcher.properties");
    }

    /** Visible for focused persistence tests and troubleshooting. */
    public Path file() {
        return file;
    }

    /** Loads state, or defaults when the file is absent or corrupt. Blocking. */
    public State load() {
        requireBackgroundThread();
        if (!Files.isRegularFile(file)) {
            return State.defaults();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties values = new Properties();
            values.load(reader);
            if (!FORMAT.equals(values.getProperty("format"))) {
                throw new IllegalArgumentException("unknown launcher settings format");
            }
            CapturePreset preset = CapturePreset.valueOf(required(values, "preset"));
            if (preset == CapturePreset.CUSTOM) {
                throw new IllegalArgumentException("Custom has no launcher editor");
            }
            int count = Integer.parseInt(required(values, "history.count"));
            if (count < 0 || count > LauncherHistory.MAX_ENTRIES) {
                throw new IllegalArgumentException("invalid history count " + count);
            }
            java.util.ArrayList<String> history = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                history.add(required(values, "history." + i));
            }
            return new State(
                    required(values, "workspace"),
                    required(values, "bazel"),
                    preset,
                    required(values, "command"),
                    history);
        } catch (IOException | RuntimeException unreadable) {
            log.warn("launcher settings at {} could not be read; using defaults", file, unreadable);
            return State.defaults();
        }
    }

    /** Saves one snapshot. A failed preference save is logged and dropped. Blocking. */
    public void save(State state) {
        requireBackgroundThread();
        Objects.requireNonNull(state, "state");
        try {
            Files.createDirectories(file.getParent());
            Properties values = new Properties();
            values.setProperty("format", FORMAT);
            values.setProperty("workspace", state.workspace());
            values.setProperty("bazel", state.bazelExecutable());
            values.setProperty("preset", state.preset().name());
            values.setProperty("command", state.command());
            values.setProperty("history.count", Integer.toString(state.history().size()));
            for (int i = 0; i < state.history().size(); i++) {
                values.setProperty("history." + i, state.history().get(i));
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                values.store(writer, "Bazel Build Visualizer launcher");
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("launcher settings could not be saved to {}", file, failure);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("launcher settings I/O must not run on the EDT");
        }
    }

    /** One immutable settings snapshot. History is normalized newest first. */
    public record State(
            String workspace,
            String bazelExecutable,
            CapturePreset preset,
            String command,
            List<String> history) {

        public State {
            workspace = Objects.requireNonNull(workspace, "workspace");
            bazelExecutable = Objects.requireNonNull(bazelExecutable, "bazelExecutable");
            preset = Objects.requireNonNull(preset, "preset");
            command = Objects.requireNonNull(command, "command");
            LauncherHistory normalized = new LauncherHistory();
            normalized.replaceNewestFirst(Objects.requireNonNull(history, "history"));
            history = normalized.entries();
        }

        public static State defaults() {
            return new State(
                    System.getProperty("user.dir", ""),
                    "bazel",
                    CapturePreset.defaultPreset(),
                    "",
                    List.of());
        }
    }
}
