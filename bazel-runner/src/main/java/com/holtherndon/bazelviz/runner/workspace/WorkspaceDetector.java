package com.holtherndon.bazelviz.runner.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the workspace a directory belongs to (plan 8.2).
 *
 * <h2>The working directory is not rewritten</h2>
 *
 * <p>Finding the root is the easy half. The half that matters is what the
 * caller does with it, and the answer is: nothing. Bazel resolves relative
 * target patterns — {@code :all}, {@code ...}, a bare package name — against
 * the <em>process working directory</em>, not the workspace root. Verified by
 * building from a subdirectory: {@code bazel info workspace} reports the root
 * while {@code :all} means the subdirectory's package.
 *
 * <p>So a launcher that helpfully normalized the working directory to the
 * workspace root would silently build a different set of targets than the user
 * typed. Both values are recorded, the process runs where the user is, and the
 * root is used only to identify the project.
 */
public final class WorkspaceDetector {

    private WorkspaceDetector() {}

    /**
     * Walks up from {@code directory} looking for a workspace marker.
     *
     * <p>Filesystem only — no Bazel is run. {@code bazel info workspace} would
     * give the same answer, and would cost a Bazel server: it contacts one, so
     * it can block behind a build the user has running and can restart their
     * server if the startup options differ. Four filenames and a walk up the
     * tree produce the same result for free.
     */
    public static WorkspaceInfo detect(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Path start = directory.toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Optional<String> marker = markerIn(candidate);
            if (marker.isPresent()) {
                return new WorkspaceInfo(
                        start,
                        Optional.of(candidate),
                        marker,
                        WorkspaceInfo.Detection.MARKER_SEARCH);
            }
        }
        return new WorkspaceInfo(
                start, Optional.empty(), Optional.empty(), WorkspaceInfo.Detection.NOT_FOUND);
    }

    /**
     * A workspace the user named outright.
     *
     * <p>Recorded as {@link WorkspaceInfo.Detection#USER_SELECTED} even when the
     * directory has no marker. Plan 8.2 puts the user's choice first in the
     * detection order, and a repository laid out in a way this build does not
     * recognize is a reason to believe the user over the heuristic, not to
     * overrule them. The absent marker is visible in the result either way.
     */
    public static WorkspaceInfo selected(Path workingDirectory, Path workspaceRoot) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Path root = workspaceRoot.toAbsolutePath().normalize();
        return new WorkspaceInfo(
                workingDirectory.toAbsolutePath().normalize(),
                Optional.of(root),
                markerIn(root),
                WorkspaceInfo.Detection.USER_SELECTED);
    }

    /** The first marker file present in {@code directory}, if any. */
    public static Optional<String> markerIn(Path directory) {
        for (String marker : WorkspaceInfo.MARKERS) {
            if (Files.isRegularFile(directory.resolve(marker))) {
                return Optional.of(marker);
            }
        }
        return Optional.empty();
    }

    /** True when {@code directory} is itself a workspace root. */
    public static boolean isWorkspaceRoot(Path directory) {
        return markerIn(directory).isPresent();
    }
}
