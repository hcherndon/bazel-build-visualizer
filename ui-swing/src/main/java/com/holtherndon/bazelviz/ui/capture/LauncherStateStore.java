package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final String FORMAT = "3";
    private static final String LEGACY_FORMAT_1 = "1";
    private static final String LEGACY_FORMAT_2 = "2";

    private final Path file;
    private final Replacer replacer;

    public LauncherStateStore(Path settingsDirectory) {
        this(settingsDirectory, LauncherStateStore::replace);
    }

    LauncherStateStore(Path settingsDirectory, Replacer replacer) {
        file = Objects.requireNonNull(settingsDirectory, "settingsDirectory")
                .resolve("launcher.properties");
        this.replacer = Objects.requireNonNull(replacer, "replacer");
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
            String format = values.getProperty("format");
            if (!FORMAT.equals(format)
                    && !LEGACY_FORMAT_1.equals(format)
                    && !LEGACY_FORMAT_2.equals(format)) {
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
            boolean hasRemoteSettings = !LEGACY_FORMAT_1.equals(format);
            List<SshConnectionProfile> profiles = FORMAT.equals(format)
                    ? readProfiles(values) : List.of();
            return new State(
                    required(values, "workspace"),
                    required(values, "bazel"),
                    preset,
                    required(values, "command"),
                    history,
                    hasRemoteSettings
                            ? ExecutionHost.valueOf(required(values, "executionHost"))
                            : ExecutionHost.LOCAL,
                    hasRemoteSettings ? required(values, "sshDestination") : "",
                    hasRemoteSettings ? required(values, "sshPort") : "",
                    profiles);
        } catch (IOException | RuntimeException unreadable) {
            log.warn("launcher settings at {} could not be read; using defaults", file, unreadable);
            return State.defaults();
        }
    }

    /**
     * Saves one snapshot through a sibling temporary file. Blocking.
     *
     * @return true only after the replacement succeeds; false leaves the
     *     previous live file intact and lets the caller retry the snapshot
     */
    public boolean save(State state) {
        requireBackgroundThread();
        Objects.requireNonNull(state, "state");
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".launcher-", ".tmp");
            Properties values = new Properties();
            values.setProperty("format", FORMAT);
            values.setProperty("workspace", state.workspace());
            values.setProperty("bazel", state.bazelExecutable());
            values.setProperty("preset", state.preset().name());
            values.setProperty("command", state.command());
            values.setProperty("executionHost", state.executionHost().name());
            values.setProperty("sshDestination", state.sshDestination());
            values.setProperty("sshPort", state.sshPort());
            values.setProperty("sshProfiles.count", Integer.toString(state.sshProfiles().size()));
            for (int i = 0; i < state.sshProfiles().size(); i++) {
                SshConnectionProfile profile = state.sshProfiles().get(i);
                String prefix = "sshProfiles." + i + ".";
                values.setProperty(prefix + "destination", profile.destination());
                values.setProperty(prefix + "port", profile.port());
                values.setProperty(prefix + "workspace", profile.workingDirectory());
                values.setProperty(prefix + "bazel", profile.bazelExecutable());
            }
            values.setProperty("history.count", Integer.toString(state.history().size()));
            for (int i = 0; i < state.history().size(); i++) {
                values.setProperty("history." + i, state.history().get(i));
            }
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "Bazel Build Visualizer launcher");
            }
            replacer.replace(temporary, file);
            temporary = null;
            return true;
        } catch (IOException | RuntimeException failure) {
            log.warn("launcher settings could not be saved to {}", file, failure);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException cleanupFailure) {
                    log.warn("temporary launcher settings could not be removed from {}",
                            temporary, cleanupFailure);
                }
            }
        }
    }

    private static void replace(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface Replacer {
        void replace(Path temporary, Path destination) throws IOException;
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }

    private static List<SshConnectionProfile> readProfiles(Properties values) {
        int count = Integer.parseInt(required(values, "sshProfiles.count"));
        if (count < 0 || count > SshConnectionProfile.MAX_SAVED_PROFILES) {
            throw new IllegalArgumentException("invalid SSH profile count " + count);
        }
        java.util.ArrayList<SshConnectionProfile> profiles = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String prefix = "sshProfiles." + i + ".";
            profiles.add(new SshConnectionProfile(
                    required(values, prefix + "destination"),
                    required(values, prefix + "port"),
                    required(values, prefix + "workspace"),
                    required(values, prefix + "bazel")));
        }
        return List.copyOf(profiles);
    }

    private static void requireBackgroundThread() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("launcher settings I/O must not run on the EDT");
        }
    }

    /** Where the requested command should run. */
    public enum ExecutionHost {
        LOCAL("This computer"),
        SSH("SSH host");

        private final String displayName;

        ExecutionHost(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** One immutable settings snapshot. History is normalized newest first. */
    public record State(
            String workspace,
            String bazelExecutable,
            CapturePreset preset,
            String command,
            List<String> history,
            ExecutionHost executionHost,
            String sshDestination,
            String sshPort,
            List<SshConnectionProfile> sshProfiles) {

        public State {
            workspace = Objects.requireNonNull(workspace, "workspace");
            bazelExecutable = Objects.requireNonNull(bazelExecutable, "bazelExecutable");
            preset = Objects.requireNonNull(preset, "preset");
            command = Objects.requireNonNull(command, "command");
            executionHost = Objects.requireNonNull(executionHost, "executionHost");
            sshDestination = Objects.requireNonNull(sshDestination, "sshDestination");
            sshPort = Objects.requireNonNull(sshPort, "sshPort");
            Map<String, SshConnectionProfile> uniqueProfiles = new LinkedHashMap<>();
            for (SshConnectionProfile profile : Objects.requireNonNull(
                    sshProfiles, "sshProfiles")) {
                uniqueProfiles.putIfAbsent(profile.key(), profile);
                if (uniqueProfiles.size() == SshConnectionProfile.MAX_SAVED_PROFILES) {
                    break;
                }
            }
            sshProfiles = List.copyOf(uniqueProfiles.values());
            LauncherHistory normalized = new LauncherHistory();
            normalized.replaceNewestFirst(Objects.requireNonNull(history, "history"));
            history = normalized.entries();
        }

        /** Compatibility constructor for local-only callers and older tests. */
        public State(
                String workspace,
                String bazelExecutable,
                CapturePreset preset,
                String command,
                List<String> history) {
            this(workspace, bazelExecutable, preset, command, history,
                    ExecutionHost.LOCAL, "", "", List.of());
        }

        /** Compatibility constructor from the first remote-settings format. */
        public State(
                String workspace,
                String bazelExecutable,
                CapturePreset preset,
                String command,
                List<String> history,
                ExecutionHost executionHost,
                String sshDestination,
                String sshPort) {
            this(workspace, bazelExecutable, preset, command, history,
                    executionHost, sshDestination, sshPort, List.of());
        }

        public static State defaults() {
            return new State(
                    System.getProperty("user.dir", ""),
                    "bazel",
                    CapturePreset.defaultPreset(),
                    "",
                    List.of(),
                    ExecutionHost.LOCAL,
                    "",
                    "",
                    List.of());
        }
    }
}
