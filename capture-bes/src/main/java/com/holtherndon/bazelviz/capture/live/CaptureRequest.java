package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.runner.command.EnvironmentInheritance;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the user asked for, before anything has been resolved or started.
 *
 * <p>Everything here is the user's input. Nothing in it has been probed,
 * planned or launched — which is what makes it safe to build one from a dialog,
 * hand it to {@link CaptureCoordinator#preflight}, show the result and then
 * throw it away when the user changes their mind.
 *
 * @param sessionsRoot where managed sessions live
 * @param appVersion recorded in the manifest
 * @param executable what the user typed for the Bazel launcher
 * @param workingDirectory where the build runs. Not normalized to the workspace
 *     root: Bazel resolves relative target patterns against this, so changing
 *     it would change what gets built
 * @param args everything after the executable, as typed
 * @param preset how much instrumentation to ask for
 * @param environmentOverrides variables to set or unset for the build
 * @param inheritance what the build inherits from this process
 * @param shellMode whether to run through a shell; carries a security warning
 * @param console receives console output as it arrives, for a live view
 * @param progress receives capture counters, throttled
 * @param options pipeline tunables
 */
public record CaptureRequest(
        Path sessionsRoot,
        String appVersion,
        String executable,
        Path workingDirectory,
        List<String> args,
        CapturePreset preset,
        Map<String, Optional<String>> environmentOverrides,
        EnvironmentInheritance inheritance,
        boolean shellMode,
        ConsoleSink console,
        CaptureProgressListener progress,
        CaptureOptions options) {

    public CaptureRequest {
        Objects.requireNonNull(sessionsRoot, "sessionsRoot");
        Objects.requireNonNull(appVersion, "appVersion");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        args = List.copyOf(args);
        Objects.requireNonNull(preset, "preset");
        environmentOverrides = Map.copyOf(
                Objects.requireNonNull(environmentOverrides, "environmentOverrides"));
        Objects.requireNonNull(inheritance, "inheritance");
        Objects.requireNonNull(console, "console");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(options, "options");
    }

    /** A headless request with the recommended preset and no listeners. */
    public static CaptureRequest of(
            Path sessionsRoot, String appVersion, String executable, Path workingDirectory,
            List<String> args) {
        return new CaptureRequest(
                sessionsRoot,
                appVersion,
                executable,
                workingDirectory,
                args,
                CapturePreset.defaultPreset(),
                Map.of(),
                EnvironmentInheritance.INHERIT_ALL,
                false,
                ConsoleSink.discarding(),
                CaptureProgressListener.ignoring(),
                CaptureOptions.defaults());
    }

    public CaptureRequest withPreset(CapturePreset value) {
        return new CaptureRequest(sessionsRoot, appVersion, executable, workingDirectory, args,
                value, environmentOverrides, inheritance, shellMode, console, progress, options);
    }

    public CaptureRequest withConsole(ConsoleSink value) {
        return new CaptureRequest(sessionsRoot, appVersion, executable, workingDirectory, args,
                preset, environmentOverrides, inheritance, shellMode, value, progress, options);
    }

    public CaptureRequest withProgress(CaptureProgressListener value) {
        return new CaptureRequest(sessionsRoot, appVersion, executable, workingDirectory, args,
                preset, environmentOverrides, inheritance, shellMode, console, value, options);
    }

    public CaptureRequest withOptions(CaptureOptions value) {
        return new CaptureRequest(sessionsRoot, appVersion, executable, workingDirectory, args,
                preset, environmentOverrides, inheritance, shellMode, console, progress, value);
    }
}
