package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.format.session.SessionManifest.ExecutionLocation;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the window shows about the open session, taken from its manifest.
 *
 * <p>Every count here is an {@link OptionalLong} that stays empty when the
 * manifest does not carry it. A session interrupted before finalization has no
 * event count in its manifest, and showing "0 events" for a session that holds
 * eight hundred thousand of them would be exactly the unknown-as-zero mistake
 * plan 11.4 forbids — the status bar renders an em dash instead, and the real
 * count comes from {@link SessionReader#eventCount()} once a reader is open.
 *
 * @param root the managed session directory
 * @param sessionId the session's stable identity, as text
 * @param state the state the manifest records
 * @param manifestEventCount event count as finalized into the manifest, empty
 *     when the import never reached finalization
 * @param sources one entry per capture source, with its completeness
 * @param warnings manifest warnings, shown verbatim
 * @param workingDirectory where Bazel was invoked, when recorded and still
 *     representable as a local path
 * @param workspaceRoot the Bazel main-repository root, preferred for resolving
 *     labels because the invocation may have started in a subdirectory
 * @param executionLocation the recorded execution host provenance; an SSH
 *     location never becomes a local path and never reconnects implicitly
 * @param executionWorkingDirectory invocation-directory text owned by the
 *     recorded execution host; never interpreted without explicit access
 * @param executionWorkspaceRoot workspace-root text owned by the recorded
 *     execution host; never interpreted without explicit access
 */
public record SessionInfo(
        Path root,
        String sessionId,
        SessionState state,
        OptionalLong manifestEventCount,
        List<SourceInfo> sources,
        List<String> warnings,
        Optional<Path> workingDirectory,
        Optional<Path> workspaceRoot,
        Optional<ExecutionLocation> executionLocation,
        Optional<String> executionWorkingDirectory,
        Optional<String> executionWorkspaceRoot) {

    public SessionInfo {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(manifestEventCount, "manifestEventCount");
        sources = List.copyOf(sources);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(executionLocation, "executionLocation");
        executionWorkingDirectory = executionPathText(executionWorkingDirectory);
        executionWorkspaceRoot = executionPathText(executionWorkspaceRoot);
        if (executionLocation
                .map(location -> location.kind() == ExecutionLocation.Kind.SSH)
                .orElse(false)
                && (workingDirectory.isPresent() || workspaceRoot.isPresent())) {
            throw new IllegalArgumentException(
                    "SSH invocation paths must not be represented as local Paths");
        }
    }

    /** Compatibility shape retaining execution provenance and local invocation paths. */
    public SessionInfo(
            Path root,
            String sessionId,
            SessionState state,
            OptionalLong manifestEventCount,
            List<SourceInfo> sources,
            List<String> warnings,
            Optional<Path> workingDirectory,
            Optional<Path> workspaceRoot,
            Optional<ExecutionLocation> executionLocation) {
        this(root, sessionId, state, manifestEventCount, sources, warnings,
                workingDirectory, workspaceRoot, executionLocation,
                workingDirectory.map(Path::toString), workspaceRoot.map(Path::toString));
    }

    /** Compatibility shape for local callers created before execution provenance existed. */
    public SessionInfo(
            Path root,
            String sessionId,
            SessionState state,
            OptionalLong manifestEventCount,
            List<SourceInfo> sources,
            List<String> warnings,
            Optional<Path> workingDirectory,
            Optional<Path> workspaceRoot) {
        this(root, sessionId, state, manifestEventCount, sources, warnings,
                workingDirectory, workspaceRoot, Optional.empty(),
                workingDirectory.map(Path::toString), workspaceRoot.map(Path::toString));
    }

    /** Compatibility shape for in-memory test sources that carry no invocation paths. */
    public SessionInfo(
            Path root,
            String sessionId,
            SessionState state,
            OptionalLong manifestEventCount,
            List<SourceInfo> sources,
            List<String> warnings) {
        this(root, sessionId, state, manifestEventCount, sources, warnings,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /** One capture source and how much of it was read. */
    public record SourceInfo(
            String kind,
            Optional<String> path,
            Optional<String> sha256,
            OptionalLong byteSize,
            Completeness completeness) {

        public SourceInfo {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(byteSize, "byteSize");
            Objects.requireNonNull(completeness, "completeness");
        }
    }

    /** Reads the displayable parts of a manifest. */
    public static SessionInfo of(Path root, SessionManifest manifest) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(manifest, "manifest");
        List<SourceInfo> sources = manifest.sources().stream()
                .map(entry -> new SourceInfo(
                        entry.kind(),
                        entry.path(),
                        entry.sha256(),
                        entry.byteSize(),
                        entry.completeness()))
                .toList();
        Optional<ExecutionLocation> location = manifest.executionLocation();
        boolean local = location.map(value -> value.kind() == ExecutionLocation.Kind.LOCAL)
                .orElse(true);
        return new SessionInfo(
                root,
                manifest.sessionId().toString(),
                manifest.state(),
                manifest.eventCount(),
                sources,
                manifest.warnings(),
                local ? localPath(manifest.workingDirectory()) : Optional.empty(),
                local ? localPath(manifest.workspaceRoot()) : Optional.empty(),
                location,
                manifest.workingDirectory(),
                manifest.workspaceRoot());
    }

    /**
     * Adds invocation paths normalized from the BEP database when an imported
     * session's manifest did not carry them. Manifest values win because a live
     * capture records the exact preflight workspace before the build starts.
     */
    public SessionInfo withInvocationPaths(
            Optional<String> databaseWorkingDirectory,
            Optional<String> databaseWorkspaceRoot) {
        boolean local = executionLocation
                .map(value -> value.kind() == ExecutionLocation.Kind.LOCAL)
                .orElse(true);
        return new SessionInfo(
                root,
                sessionId,
                state,
                manifestEventCount,
                sources,
                warnings,
                local
                        ? workingDirectory.or(() -> localPath(databaseWorkingDirectory))
                        : Optional.empty(),
                local
                        ? workspaceRoot.or(() -> localPath(databaseWorkspaceRoot))
                        : Optional.empty(),
                executionLocation,
                executionWorkingDirectory.or(() -> databaseWorkingDirectory),
                executionWorkspaceRoot.or(() -> databaseWorkspaceRoot));
    }

    /**
     * True when the session's own manifest says its data is partial. The view
     * says so out loud rather than presenting a truncated capture as if it were
     * whole.
     */
    public boolean isPartial() {
        return state == SessionState.INCOMPLETE
                || state == SessionState.CORRUPT_PARTIAL
                || state == SessionState.CANCELLED
                || sources.stream().anyMatch(source -> !source.completeness().isComplete());
    }

    private static Optional<Path> localPath(Optional<String> value) {
        return value.flatMap(text -> {
            try {
                if (text.isBlank() || text.startsWith("[")) {
                    // Redacted archive placeholders are explanations, not
                    // filesystem paths on the machine opening the session.
                    return Optional.empty();
                }
                return Optional.of(Path.of(text).toAbsolutePath().normalize());
            } catch (java.nio.file.InvalidPathException invalid) {
                return Optional.empty();
            }
        });
    }

    private static Optional<String> executionPathText(Optional<String> value) {
        return Objects.requireNonNull(value, "execution path").flatMap(text -> {
            String checked = text.strip();
            return checked.isEmpty() || checked.indexOf('\0') >= 0 || checked.startsWith("[")
                    ? Optional.empty() : Optional.of(checked);
        });
    }
}
